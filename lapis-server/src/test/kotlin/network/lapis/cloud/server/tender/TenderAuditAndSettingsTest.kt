package network.lapis.cloud.server.tender

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days

/**
 * V1.9.201 -- what the tenders write to the append-only audit log (one entry per publication, award-motion registration, award, acceptance and
 * cancellation; nothing for bids, drafts or the lazy close; never a free text, a name or a price), and the ADMIN setting of the bid visibility default.
 */
class TenderAuditAndSettingsTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        fun events(id: String): List<String> = tenderAuditSnapshots(id).map { Regex("\"event\":\"([A-Z_]+)\"").find(it)!!.groupValues[1] }

        fun settingsAudit(): List<Pair<String?, String?>> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                            (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                    }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                    .map { it[AuditLogEntryTable.beforeSnapshot] to it[AuditLogEntryTable.afterSnapshot] }
            }

        test("one entry per audited event, none for drafts, bids or the close, no free text anywhere, and the hash chain stays valid") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val startSequence =
                        transaction { AuditLogEntryTable.selectAll().maxOfOrNull { it[AuditLogEntryTable.sequenceNumber] } ?: 0L }
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val a = fx.member(label = "a")
                    val committee = fx.committee()
                    val lowest = newTender(board = board, binding = fx.approvedMotion(committee = committee), publish = false)
                    events(lowest) shouldBe emptyList()
                    tender(member = board) { publishTender(lowest) }
                    val bid =
                        tender(
                            member = a,
                        ) { submitBid(tenderId = lowest, input = bidInput(price = "12.34", text = "Ich mache das geheim.")) }
                    tender(member = a) { submitBid(tenderId = lowest, input = bidInput(price = "12.33", text = "Ich mache das geheim.")) }
                    tender(member = a) { listTenders() }
                    events(lowest) shouldBe listOf("PUBLISH")

                    clock.advance(1.days)
                    tender(
                        member = board,
                    ) { awardTender(TenderAwardInput(tenderId = lowest, bidId = bid.id, justification = "geheime Begruendung")) }
                    tender(member = board) {
                        recordAcceptance(
                            TenderAcceptanceInput(
                                tenderId = lowest,
                                decision = TenderAcceptanceDecision.REJECTED,
                                reason = "geheimer Mangel",
                            ),
                        )
                    }
                    tender(
                        member = board,
                    ) { recordAcceptance(TenderAcceptanceInput(tenderId = lowest, decision = TenderAcceptanceDecision.ACCEPTED)) }
                    events(lowest) shouldBe listOf("PUBLISH", "AWARD", "ACCEPTANCE", "ACCEPTANCE")

                    // registration of an award motion and a cancellation
                    val best =
                        newTender(
                            board = board,
                            binding = fx.approvedMotion(committee = committee),
                            criterion = TenderAwardCriterion.BEST_OFFER,
                            deadline = LocalDateTime(2026, 11, 6, 11, 0),
                        )
                    tender(member = a) { submitBid(tenderId = best, input = bidInput(price = "9.99")) }
                    clock.advance(4.days)
                    val award = fx.motion(committee = committee, submitter = a, status = MotionStatus.SCHEDULED)
                    tender(member = board) { registerAwardMotion(tenderId = best, motionId = award.toString()) }
                    tender(member = board) { cancelTender(tenderId = best, reason = "geheimer Absagegrund") }
                    events(best) shouldBe listOf("PUBLISH", "AWARD_MOTION_REGISTERED", "CANCEL")

                    val allSnapshots = tenderAuditSnapshots(lowest) + tenderAuditSnapshots(best)
                    listOf("Website", "geheim", "Ich mache", "12.3", "9.99", "Tender-Test", "@").forEach { forbidden ->
                        allSnapshots.none { it.contains(forbidden) } shouldBe true
                    }
                    allSnapshots.first { it.contains("\"AWARD\"") }.contains("\"conflictedCount\":1") shouldBe true
                    // The chain is verified from the SECOND entry of this test on, anchored at the first: older entries may have been detached
                    // from their actors by other specs, and other specs may have left the chain-state row behind the table.
                    val verdict =
                        transaction {
                            val mine =
                                AuditLogEntryTable
                                    .selectAll()
                                    .where { AuditLogEntryTable.sequenceNumber greater startSequence }
                                    .toList()
                            AuditChainVerifier.verify(
                                rows = mine.sortedBy { it[AuditLogEntryTable.sequenceNumber] }.drop(1),
                            )
                        }
                    withClue(verdict.reason.orEmpty()) { verdict.valid shouldBe true }
                    (verdict.checkedCount > 5) shouldBe true
                }
            }
        }

        test(
            "the default visibility: ADMIN only, one audit entry per real change, none for the same value, and the generic update never touches it",
        ) {
            tenderTestApplication {
                val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val start = settingsAudit().size

                listOf(board, fx.member(label = "treasurer", role = AccountRole.TREASURER), fx.member(label = "plain")).forEach {
                    attempt(member = it) { call ->
                        OrganizationSettingsService(
                            call = call,
                            pulpitRecording = NO_PULPIT_RECORDING,
                        ).updateTenderDefaultVisibility(TenderBidVisibility.OPEN)
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
                settingsAudit().size shouldBe start

                // already the default: nothing is written
                call(member = admin) {
                    OrganizationSettingsService(
                        call = it,
                        pulpitRecording = NO_PULPIT_RECORDING,
                    ).updateTenderDefaultVisibility(TenderBidVisibility.HIDDEN_UNTIL_DEADLINE)
                }
                settingsAudit().size shouldBe start

                val changed =
                    call(
                        member = admin,
                    ) {
                        OrganizationSettingsService(
                            call = it,
                            pulpitRecording = NO_PULPIT_RECORDING,
                        ).updateTenderDefaultVisibility(TenderBidVisibility.OPEN)
                    }
                changed.tenderDefaultVisibility shouldBe TenderBidVisibility.OPEN
                val log = settingsAudit()
                log.size shouldBe start + 1
                log.last() shouldBe ("{\"tenderDefaultVisibility\":\"HIDDEN_UNTIL_DEADLINE\"}" to "{\"tenderDefaultVisibility\":\"OPEN\"}")
                call(member = admin) {
                    OrganizationSettingsService(
                        call = it,
                        pulpitRecording = NO_PULPIT_RECORDING,
                    ).updateTenderDefaultVisibility(TenderBidVisibility.OPEN)
                }
                settingsAudit().size shouldBe start + 1

                // the generic update leaves the column alone
                call(member = admin) {
                    OrganizationSettingsService(
                        call = it,
                        pulpitRecording = NO_PULPIT_RECORDING,
                    ).updateOrganizationSettings(OrganizationSettingsInput(name = "Generisch"))
                }
                transaction {
                    OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.tenderDefaultVisibility]
                } shouldBe TenderBidVisibility.OPEN
                // the board's defaults read model shows the stored value
                tender(member = board) { getTenderDefaults() }.defaultVisibility shouldBe TenderBidVisibility.OPEN
                call(member = admin) {
                    OrganizationSettingsService(
                        call = it,
                        pulpitRecording = NO_PULPIT_RECORDING,
                    ).updateTenderDefaultVisibility(TenderBidVisibility.HIDDEN_UNTIL_DEADLINE)
                }
            }
        }
    })
