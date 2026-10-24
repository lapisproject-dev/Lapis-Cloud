package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.tender.TenderMotionBinding
import network.lapis.cloud.server.tender.TenderStore
import network.lapis.cloud.server.tender.tenderBidderEligible
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderAuditSnapshot
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Sample data for tenders (V1.9.205): a draft, a running tender with hidden bids (one bid withdrawn), an awarded tender with its award, and a
 * running tender with open bids, so that bids are visible in all three visibility modes. All in the LTR currency.
 *
 * Written ONLY through [TenderStore] (the tender tables are off limits outside the tender files, pinned by `TenderAccessTripwireTest`), in the
 * order of the live service: insert (always a draft), publish, bids, close once the deadline is past, award. Bids in LTR reserve nothing (decision
 * "no LTR cover or reservation"), so no ledger rows exist here. The award is made by a member who has no bid (conflict-of-interest rule); the
 * awarded tender has three bidders (`conflictedCount` 3). The bidders must be eligible by the live rule ([tenderBidderEligible]); a guest never
 * bids on an LTR tender (that refusal is covered by a test, not by data).
 *
 * Audit entries exist only where the service audits: publish and award (hash chain, default time, last operation of the transaction).
 */
internal object StagingSeedTenders : StagingSeedPart {
    override val name: String = "tenders"

    private const val AWARDER = 1

    data class PlannedBid(
        val slot: Int,
        val bidder: Int,
        val price: String,
        val text: String,
        val daysAgo: Int,
        val withdrawnDaysAgo: Int? = null,
    )

    data class PlannedTender(
        val index: Int,
        val bindingMotion: Uuid,
        val title: String,
        val description: String,
        val expectedResult: String,
        val criterion: TenderAwardCriterion,
        val visibility: TenderBidVisibility,
        val creator: Int,
        val createdDaysAgo: Int,
        val publishedDaysAgo: Int?,
        /** Days from today until the deadline (negative: it passed that many days ago). */
        val deadlineInDays: Int,
        val bids: List<PlannedBid>,
        /** The slot of the winning bid, null while nobody is awarded. */
        val awardedSlot: Int? = null,
    ) {
        val id: Uuid get() = SeedIds.tenders(index)

        fun bidId(slot: Int): Uuid = SeedIds.tenders(0x2000 + index * 0x10 + slot)
    }

    /** Append-only: the index is the id. */
    val tenders: List<PlannedTender> =
        listOf(
            PlannedTender(
                index = 1,
                bindingMotion = StagingSeedGovernanceTopUp.motion2,
                title = "Gestaltung der neuen Vereinswebseite (Demodaten)",
                description = "Entwurf und Umsetzung der neuen Vereinswebseite. Fiktive Ausschreibung der Demodaten, noch im Entwurf.",
                expectedResult = "Eine fertige, barrierearme Webseite mit Redaktionszugang.",
                criterion = TenderAwardCriterion.BEST_OFFER,
                visibility = TenderBidVisibility.OPEN,
                creator = 2,
                createdDaysAgo = 3,
                publishedDaysAgo = null,
                deadlineInDays = 30,
                bids = emptyList(),
            ),
            PlannedTender(
                index = 2,
                bindingMotion = StagingSeedGovernanceTopUp.motion1,
                title = "Sanierung der Vereinsheim-Kueche (Demodaten)",
                description = "Ausbau der alten und Einbau einer neuen Kueche. Fiktive Ausschreibung der Demodaten, Gebote verdeckt.",
                expectedResult = "Eine funktionsfaehige, abgenommene Kueche.",
                criterion = TenderAwardCriterion.LOWEST_PRICE,
                visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                creator = 1,
                createdDaysAgo = 9,
                publishedDaysAgo = 8,
                deadlineInDays = 14,
                bids =
                    listOf(
                        PlannedBid(
                            slot = 1,
                            bidder = 4,
                            price = "450.00",
                            text = "Komplette Sanierung inklusive Entsorgung (Demodaten).",
                            daysAgo = 7,
                        ),
                        PlannedBid(
                            slot = 2,
                            bidder = 13,
                            price = "380.00",
                            text = "Sanierung mit Eigenleistung des Vereins (Demodaten).",
                            daysAgo = 6,
                        ),
                        PlannedBid(
                            slot = 3,
                            bidder = 9,
                            price = "520.00",
                            text = "Sanierung mit hochwertigen Geraeten (Demodaten).",
                            daysAgo = 5,
                            withdrawnDaysAgo = 4,
                        ),
                    ),
            ),
            PlannedTender(
                index = 3,
                bindingMotion = StagingSeedGovernanceTopUp.motion1,
                title = "Malerarbeiten im Vereinsheim (Demodaten)",
                description = "Streichen der Innenraeume. Fiktive Ausschreibung der Demodaten, bereits vergeben.",
                expectedResult = "Frisch gestrichene Raeume, Abnahme durch den Bauausschuss.",
                criterion = TenderAwardCriterion.LOWEST_PRICE,
                visibility = TenderBidVisibility.OWN_ONLY,
                creator = 1,
                createdDaysAgo = 36,
                publishedDaysAgo = 35,
                deadlineInDays = -7,
                bids =
                    listOf(
                        PlannedBid(
                            slot = 1,
                            bidder = 2,
                            price = "300.00",
                            text = "Streichen aller Raeume in zwei Tagen (Demodaten).",
                            daysAgo = 30,
                        ),
                        PlannedBid(
                            slot = 2,
                            bidder = 10,
                            price = "280.00",
                            text = "Streichen mit umweltfreundlicher Farbe (Demodaten).",
                            daysAgo = 28,
                        ),
                        PlannedBid(
                            slot = 3,
                            bidder = 5,
                            price = "310.00",
                            text = "Streichen inklusive Lackierung der Tueren (Demodaten).",
                            daysAgo = 25,
                        ),
                    ),
                awardedSlot = 2,
            ),
            PlannedTender(
                index = 4,
                bindingMotion = StagingSeedGovernanceTopUp.motion2,
                title = "Fotos fuer die Vereinswebseite (Demodaten)",
                description = "Zwanzig Fotos vom Vereinsleben. Fiktive Ausschreibung der Demodaten, Gebote offen sichtbar.",
                expectedResult = "Zwanzig bearbeitete Fotos mit Nutzungsrechten.",
                criterion = TenderAwardCriterion.LOWEST_PRICE,
                visibility = TenderBidVisibility.OPEN,
                creator = 1,
                createdDaysAgo = 5,
                publishedDaysAgo = 4,
                deadlineInDays = 10,
                bids =
                    listOf(
                        PlannedBid(slot = 1, bidder = 21, price = "120.00", text = "Fotografie an drei Terminen (Demodaten).", daysAgo = 3),
                        PlannedBid(
                            slot = 2,
                            bidder = 27,
                            price = "95.00",
                            text = "Fotografie an zwei Terminen mit Nachbearbeitung (Demodaten).",
                            daysAgo = 2,
                        ),
                    ),
            ),
        )

    private fun JdbcTransaction.bindingOk(motionId: Uuid): Boolean {
        val row = MotionTable.selectAll().where { MotionTable.id eq motionId }.singleOrNull() ?: return false
        return TenderMotionBinding.bindingIneligibility(row) == null
    }

    private fun stamp(
        clock: SeedClock,
        daysAgo: Int,
        hour: Int = 10,
    ): LocalDateTime = clock.utcAt(daysAgo = daysAgo, hour = hour)

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val statuses = statusesOf(tenders.flatMap { t -> t.bids.map { it.bidder } + t.creator } + AWARDER)
        var inserted = 0
        var skipped = 0
        val audits = mutableListOf<Pair<Uuid, TenderAuditSnapshot>>()
        tenders.forEach { tender ->
            val bidders = tender.bids.map { it.bidder }
            val ok =
                TenderStore.find(tender.id) == null &&
                    statuses[tender.creator] == MemberStatus.ACTIVE &&
                    statuses[AWARDER] == MemberStatus.ACTIVE &&
                    bidders.all { b -> statuses[b]?.let { tenderBidderEligible(status = it, currency = TenderCurrency.LTR) } == true } &&
                    bindingOk(tender.bindingMotion)
            if (!ok) {
                skipped++
                return@forEach
            }
            TenderStore.insertTender(
                id = tender.id,
                bindingMotionId = tender.bindingMotion,
                title = tender.title,
                description = tender.description,
                descriptionFormat = TenderDescriptionFormat.PLAIN,
                expectedResult = tender.expectedResult,
                deadlineWall = clock.wallAt(daysFromToday = tender.deadlineInDays, hour = 12),
                criterion = tender.criterion,
                visibility = tender.visibility,
                createdBy = SeedIds.member(tender.creator),
                createdAt = stamp(clock = clock, daysAgo = tender.createdDaysAgo),
            )
            if (tender.publishedDaysAgo != null) {
                TenderStore.markPublished(
                    tenderId = tender.id,
                    by = SeedIds.member(AWARDER),
                    at = stamp(clock = clock, daysAgo = tender.publishedDaysAgo),
                )
                audits +=
                    tender.id to
                    TenderAuditSnapshot(
                        event = "PUBLISH",
                        status = TenderStatus.PUBLISHED.name,
                        visibility = tender.visibility.name,
                        criterion = tender.criterion.name,
                        currency = TenderCurrency.LTR.name,
                        deadline = clock.wallAt(daysFromToday = tender.deadlineInDays, hour = 12).toString(),
                        bindingMotionId = tender.bindingMotion.toString(),
                    )
            }
            tender.bids.forEach { bid ->
                val bidId = tender.bidId(bid.slot)
                TenderStore.insertBid(
                    id = bidId,
                    tenderId = tender.id,
                    bidderId = SeedIds.member(bid.bidder),
                    amount = BigDecimal(bid.price),
                    bidText = bid.text,
                    at = stamp(clock = clock, daysAgo = bid.daysAgo),
                )
                if (bid.withdrawnDaysAgo !=
                    null
                ) {
                    TenderStore.markBidWithdrawn(bidId = bidId, at = stamp(clock = clock, daysAgo = bid.withdrawnDaysAgo))
                }
            }
            if (tender.awardedSlot != null) {
                // The deadline is past: the live lazy close turns PUBLISHED into CLOSED, then the award.
                TenderStore.closeIfDue(row = TenderStore.lock(tender.id), wallNow = clock.wallNow)
                val winning = tender.bidId(tender.awardedSlot)
                TenderStore.markBidAwarded(winning)
                TenderStore.rejectOtherSubmittedBids(tenderId = tender.id, winningBidId = winning)
                val conflicted = TenderStore.bidderIds(tender.id).size
                TenderStore.insertAward(
                    id = SeedIds.tenders(0x3000 + tender.index),
                    tenderId = tender.id,
                    bidId = winning,
                    awardedBy = SeedIds.member(AWARDER),
                    awardedAt = clock.now,
                    awardMotionId = null,
                    resolutionId = null,
                    justification = null,
                    conflictedCount = conflicted,
                    quorumEligibleCount = null,
                )
                TenderStore.setStatus(tenderId = tender.id, newStatus = TenderStatus.AWARDED)
                audits +=
                    tender.id to
                    TenderAuditSnapshot(
                        event = "AWARD",
                        status = TenderStatus.AWARDED.name,
                        criterion = tender.criterion.name,
                        bidId = winning.toString(),
                        conflictedCount = conflicted,
                    )
            }
            inserted++
        }
        // The audit chain is the last locking operation of the transaction, like in the live path.
        audits.forEach { (tenderId, snapshot) ->
            AuditLogRecorder.record(
                actorMemberId = SeedIds.member(AWARDER),
                actorRole = AccountRole.ADMIN,
                entityType = AuditEntityType.TENDER,
                entityId = tenderId,
                action = AuditAction.UPDATE,
                before = null,
                after = Json.encodeToString(TenderAuditSnapshot.serializer(), snapshot),
            )
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
