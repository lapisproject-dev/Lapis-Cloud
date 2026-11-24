package network.lapis.cloud.server.auction

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.shared.domain.AuctionOfferStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.321 (Fiat wave b) -- the database operations on `auction_offer`. **Pure bookkeeping: nothing here touches money, a ledger or a member
 * account.** A price offer is only a negotiation record; the award itself runs through the Fiat path of `AuctionService`.
 *
 * ## Lock order
 *
 * The AUCTION row is ALWAYS locked first (`AuctionService.lockAndCloseIfDue`), then an offer row ([lock]), then the decline counter row ([AuctionOfferRestrictionStore]), then the audit chain row (last). Every
 * function named `...Locked` requires the caller to hold the auction row lock. Reading paths never write: they derive the effective status with
 * [effectiveStatus]. Only local (origin) auctions exist; wave (c) must reject remote ones before calling anything here.
 */
internal object AuctionOfferStore {
    /** What the DTO of an auction needs to know about its offers, for ONE caller. Aggregated from one query per list. */
    data class Summary(
        val openForSeller: Int,
        val callerHasOpen: Boolean,
        val callerProposals: Int,
        /** The end of the CALLER's running decline block on this auction (V1.9.323), `null` if he is not blocked. */
        val callerBlockedUntil: LocalDateTime?,
    )

    fun find(offerId: Uuid): ResultRow? = AuctionOfferTable.selectAll().where { AuctionOfferTable.id eq offerId }.singleOrNull()

    /** `SELECT ... FOR UPDATE` -- only after the auction row of the offer is locked. */
    fun lock(offerId: Uuid): ResultRow? =
        AuctionOfferTable
            .selectAll()
            .where { AuctionOfferTable.id eq offerId }
            .forUpdate()
            .singleOrNull()

    /** An OFFERED row whose time ran out reads as EXPIRED even if nothing was written yet. */
    fun effectiveStatus(
        row: ResultRow,
        now: LocalDateTime,
    ): AuctionOfferStatus {
        val status = row[AuctionOfferTable.status]
        return if (status == AuctionOfferStatus.OFFERED && row[AuctionOfferTable.expiresAt] <= now) AuctionOfferStatus.EXPIRED else status
    }

    /** OFFERED rows whose time ran out -> EXPIRED (`decided_at` = the expiry time). Holds the auction lock. */
    fun expireDueLocked(
        auctionId: Uuid,
        now: LocalDateTime,
    ): Int {
        val due =
            AuctionOfferTable
                .selectAll()
                .where {
                    (AuctionOfferTable.auctionId eq auctionId) and
                        (AuctionOfferTable.status eq AuctionOfferStatus.OFFERED) and
                        (AuctionOfferTable.expiresAt lessEq now)
                }.toList()
        // At most MAX_OFFER_ROWS_PER_AUCTION rows; each keeps ITS OWN expiry time as decided_at.
        due.forEach { row ->
            AuctionOfferTable.update({ AuctionOfferTable.id eq row[AuctionOfferTable.id] }) {
                it[AuctionOfferTable.status] = AuctionOfferStatus.EXPIRED
                it[AuctionOfferTable.decidedAt] = row[AuctionOfferTable.expiresAt]
            }
        }
        return due.size
    }

    /** The auction ended (time, Sofortkauf or an accepted offer): every other OFFERED row expires now (`decided_at` = now). Holds the auction lock. */
    fun expireAllOpenLocked(
        auctionId: Uuid,
        now: LocalDateTime,
        exceptOfferId: Uuid? = null,
    ): Int {
        // Rows whose own time already ran out keep THEIR expiry as decided_at, not the (later) end of the auction.
        expireDueLocked(auctionId = auctionId, now = now)
        return AuctionOfferTable.update({
            val base = (AuctionOfferTable.auctionId eq auctionId) and (AuctionOfferTable.status eq AuctionOfferStatus.OFFERED)
            if (exceptOfferId == null) base else base and (AuctionOfferTable.id neq exceptOfferId)
        }) {
            it[AuctionOfferTable.status] = AuctionOfferStatus.EXPIRED
            it[AuctionOfferTable.decidedAt] = now
        }
    }

    /** The buyer's currently open row (his proposal, or the seller's counter-offer to him), `null` if there is none. */
    fun openNegotiationOf(
        auctionId: Uuid,
        buyerId: Uuid,
        now: LocalDateTime,
    ): ResultRow? =
        AuctionOfferTable
            .selectAll()
            .where {
                (AuctionOfferTable.auctionId eq auctionId) and
                    (AuctionOfferTable.buyerMemberId eq buyerId) and
                    (AuctionOfferTable.status eq AuctionOfferStatus.OFFERED) and
                    (AuctionOfferTable.expiresAt greater now)
            }.limit(1)
            .singleOrNull()

    /**
     * Proposals of buyers that are EFFECTIVELY open (status OFFERED and not yet expired) -- the number the cap of
     * [network.lapis.cloud.shared.domain.AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION] is about. Counter-offers (from the seller) and COUNTERED,
     * EXPIRED, WITHDRAWN, DECLINED rows do not count. The expiry is checked here too, although the lazy settlement persists it first.
     */
    fun countOpenBuyerProposals(
        auctionId: Uuid,
        now: LocalDateTime,
    ): Long =
        AuctionOfferTable
            .selectAll()
            .where {
                (AuctionOfferTable.auctionId eq auctionId) and
                    (AuctionOfferTable.fromSeller eq false) and
                    (AuctionOfferTable.status eq AuctionOfferStatus.OFFERED) and
                    (AuctionOfferTable.expiresAt greater now)
            }.count()

    /** Every row the auction holds, whatever its status (the hard cap against unbounded growth). */
    fun countRows(auctionId: Uuid): Long = AuctionOfferTable.selectAll().where { AuctionOfferTable.auctionId eq auctionId }.count()

    /** All proposals of the buyer on the auction, withdrawn ones included (per-buyer cap). */
    fun countBuyerProposals(
        auctionId: Uuid,
        buyerId: Uuid,
    ): Long =
        AuctionOfferTable
            .selectAll()
            .where {
                (AuctionOfferTable.auctionId eq auctionId) and
                    (AuctionOfferTable.buyerMemberId eq buyerId) and
                    (AuctionOfferTable.fromSeller eq false)
            }.count()

    fun hasAny(auctionId: Uuid): Boolean =
        AuctionOfferTable
            .selectAll()
            .where { (AuctionOfferTable.auctionId eq auctionId) and (AuctionOfferTable.status neq AuctionOfferStatus.WITHDRAWN) }
            .limit(1)
            .any()

    /** One query for a whole list: the per-auction summary for [callerId]. Auctions without any offer are absent from the map. */
    fun summaryFor(
        auctionIds: Collection<Uuid>,
        callerId: Uuid,
        now: LocalDateTime,
    ): Map<Uuid, Summary> {
        if (auctionIds.isEmpty()) return emptyMap()
        val blocks = AuctionOfferRestrictionStore.blockedUntilFor(auctionIds = auctionIds, buyerId = callerId, now = now)
        return AuctionOfferTable
            .selectAll()
            .where { AuctionOfferTable.auctionId inList auctionIds.toList() }
            .toList()
            .groupBy { it[AuctionOfferTable.auctionId] }
            .mapValues { (auctionId, rows) ->
                Summary(
                    openForSeller =
                        rows.count {
                            !it[AuctionOfferTable.fromSeller] &&
                                effectiveStatus(
                                    row = it,
                                    now = now,
                                ) == AuctionOfferStatus.OFFERED
                        },
                    callerHasOpen =
                        rows.any {
                            it[AuctionOfferTable.buyerMemberId] == callerId &&
                                effectiveStatus(
                                    row = it,
                                    now = now,
                                ) == AuctionOfferStatus.OFFERED
                        },
                    callerProposals =
                        rows.count {
                            it[AuctionOfferTable.buyerMemberId] == callerId && !it[AuctionOfferTable.fromSeller]
                        },
                    callerBlockedUntil = blocks[auctionId],
                )
            }
    }

    @Suppress("LongParameterList")
    fun insert(
        auctionId: Uuid,
        buyerId: Uuid,
        fromSeller: Boolean,
        parentOfferId: Uuid?,
        amountMinor: Long,
        now: LocalDateTime,
        expiresAt: LocalDateTime,
        noticeVersion: String,
        noticeLang: String,
        noticeSha256: String,
    ): Uuid {
        val newId = Uuid.random()
        AuctionOfferTable.insert {
            it[AuctionOfferTable.id] = newId
            it[AuctionOfferTable.auctionId] = auctionId
            it[AuctionOfferTable.buyerMemberId] = buyerId
            it[AuctionOfferTable.fromSeller] = fromSeller
            it[AuctionOfferTable.parentOfferId] = parentOfferId
            it[AuctionOfferTable.amountMinor] = amountMinor
            it[AuctionOfferTable.status] = AuctionOfferStatus.OFFERED
            it[AuctionOfferTable.createdAt] = now
            it[AuctionOfferTable.expiresAt] = expiresAt
            it[AuctionOfferTable.decidedAt] = null
            it[AuctionOfferTable.noticeVersion] = noticeVersion
            it[AuctionOfferTable.noticeLang] = noticeLang
            it[AuctionOfferTable.noticeSha256] = noticeSha256
        }
        return newId
    }

    /** Sets the final [status] of [offerId] with `decided_at` = [now]. */
    fun setStatus(
        offerId: Uuid,
        status: AuctionOfferStatus,
        now: LocalDateTime,
    ) {
        AuctionOfferTable.update({ AuctionOfferTable.id eq offerId }) {
            it[AuctionOfferTable.status] = status
            it[AuctionOfferTable.decidedAt] = now
        }
    }
}
