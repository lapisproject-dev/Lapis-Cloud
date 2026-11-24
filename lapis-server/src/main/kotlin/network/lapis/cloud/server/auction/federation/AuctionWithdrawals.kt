package network.lapis.cloud.server.auction.federation

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AuctionTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.322 (step c2) -- when does a shared auction leave the feed, and who tells the partners. Everything runs inside the caller's transaction, AFTER
 * the auction row lock and BEFORE the audit entry (lock order: auction rows ascending id, then tombstones, audit last). Nothing here is the audit: the
 * triggering event (the settlement, the switch) is audited already; only the seller's own consent change has its own entry.
 *
 * LTR auctions have no hook on purpose: an LTR auction can never carry a consent (`AuctionFederationConsent`).
 */
internal object AuctionWithdrawals {
    private fun targetOf(row: ResultRow) = AuctionWithdrawalTarget(auctionId = row[AuctionTable.id], endsAt = row[AuctionTable.endsAt])

    /**
     * The auction left the shareable state by a status change (Sofortkauf, time, an accepted offer, no sale). A no-op unless it carried a consent.
     * The consent ends with the auction (an ended auction can never be shared again). [row] is the locked row as it was BEFORE the change.
     */
    fun onAuctionEnded(
        row: ResultRow,
        now: LocalDateTime,
    ) {
        if (!row[AuctionTable.federationOptIn]) return
        AuctionFederationStore.withdraw(
            targets = listOf(targetOf(row)),
            partnerId = null,
            ids = AuctionFederationKey.current(),
            now = now,
            clearOptIn = true,
            requireServed = true,
        )
    }

    /** EUR -> LTR: an LTR auction is never shared. The consent goes (a later switch back does NOT share again), the partners are told at once. */
    fun onSwitchedToLtr(
        row: ResultRow,
        now: LocalDateTime,
    ) {
        if (!row[AuctionTable.federationOptIn]) return
        AuctionFederationStore.withdraw(
            targets = listOf(targetOf(row)),
            partnerId = null,
            ids = AuctionFederationKey.current(),
            now = now,
            clearOptIn = true,
            requireServed = false,
        )
    }

    /** The seller took the consent back (the row is locked by the caller). */
    fun onConsentWithdrawn(
        row: ResultRow,
        now: LocalDateTime,
    ) {
        AuctionFederationStore.withdraw(
            targets = listOf(targetOf(row)),
            partnerId = null,
            ids = AuctionFederationKey.current(),
            now = now,
            clearOptIn = true,
            requireServed = true,
        )
    }

    /**
     * GDPR erasure: withdraw every shared hint of the seller for everybody and end the consent. Runs BEFORE any row of the member is changed; the
     * caller holds the member row lock (member before auction rows). The auction row is retained, so the consent must end here (unlike carpool).
     * @return the number of auctions that carried a consent.
     */
    fun onSellerErased(
        memberId: Uuid,
        now: LocalDateTime,
    ): Int {
        val locked = AuctionFederationStore.lockOptedInTargetsOfSeller(memberId)
        if (locked.isEmpty()) return 0
        AuctionFederationStore.withdraw(
            targets = locked,
            partnerId = null,
            ids = AuctionFederationKey.current(),
            now = now,
            clearOptIn = true,
            requireServed = true,
        )
        return locked.size
    }

    /** The auction module or the Euro module went off: every shared hint is withdrawn for everybody, the consents stay. */
    fun onModuleOff(now: LocalDateTime) = withdrawAllShared(partnerId = null, now = now)

    /** The instance switch went off: same as [onModuleOff]. */
    fun onInstanceOff(now: LocalDateTime) = withdrawAllShared(partnerId = null, now = now)

    /** The switch for ONE partner went off: its hints are withdrawn for THAT partner only. */
    fun onPartnerOff(
        partnerId: Uuid,
        now: LocalDateTime,
    ) = withdrawAllShared(partnerId = partnerId, now = now)

    private fun withdrawAllShared(
        partnerId: Uuid?,
        now: LocalDateTime,
    ) {
        // Lock the auction rows first (ascending id), the tombstones are written after.
        val locked = AuctionFederationStore.lockOptedIn(candidates = AuctionFederationStore.sharedTargets(now = now))
        AuctionFederationStore.withdraw(
            targets = locked,
            partnerId = partnerId,
            ids = AuctionFederationKey.current(),
            now = now,
            clearOptIn = false,
            requireServed = false,
        )
    }

    /**
     * Lazy sweep (bounded): consented auctions that are no longer shareable although nobody touched them -- ended without any access, or the seller
     * left. Re-checks under the lock, ends the consent, writes the tombstones. At most [limit] auctions per call. @return the number withdrawn.
     */
    fun sweep(
        now: LocalDateTime,
        ids: AuctionTeaserIds?,
        limit: Int,
    ): Int {
        val candidates = AuctionFederationStore.optedInNoLongerShareable(limit = limit, now = now)
        if (candidates.isEmpty()) return 0
        val confirmed = AuctionFederationStore.lockOptedIn(candidates)
        if (confirmed.isEmpty()) return 0
        AuctionFederationStore.withdraw(
            targets = confirmed,
            partnerId = null,
            ids = ids,
            now = now,
            clearOptIn = true,
            requireServed = true,
        )
        logger.info { "auction withdrawal: ${confirmed.size} consented auction(s) no longer shareable were withdrawn" }
        return confirmed.size
    }
}
