package network.lapis.cloud.server.payment.dunning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Regression coverage for [issueDunningNotice] itself
 * (as opposed to [DunningPollerTest], which exercises it only indirectly through [DunningPoller
 * .tick]) -- in particular the Phase 1 -> Phase 2 stale-snapshot race [onBeforePhase2Lock] exists
 * specifically to reproduce deterministically. See the security review finding this fixes.
 */
class DunningIssuanceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLevelIds = mutableMapOf<Int, Uuid>()
        val createdAckIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[dunningEnabled] = true
                }
            }
        }

        afterEach {
            transaction {
                val noticeIds =
                    DunningNoticeTable
                        .selectAll()
                        .where { DunningNoticeTable.contributionId inList createdContributionIds }
                        .map { it[DunningNoticeTable.id] }
                if (noticeIds.isNotEmpty()) DunningNoticeTable.deleteWhere { DunningNoticeTable.id inList noticeIds }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdLevelIds.isNotEmpty()) {
                    DunningLevelTable.deleteWhere { DunningLevelTable.id inList createdLevelIds.values }
                }
                if (createdAckIds.isNotEmpty()) {
                    DunningComplianceAcknowledgmentTable.deleteWhere { DunningComplianceAcknowledgmentTable.id inList createdAckIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
            }
            createdContributionIds.clear()
            createdLevelIds.clear()
            createdAckIds.clear()
            createdMemberIds.clear()
            createdTierIds.clear()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[dunningEnabled] = false
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Mahnwesen-Issuance-Fixture Mitglied"
                    it[email] = "dunning-issuance-$id@example.test"
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[street] = "Teststr. 1"
                    it[postalCode] = "38100"
                    it[city] = "Braunschweig"
                    it[country] = "DE"
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Mahnwesen-Issuance-Fixture Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
            status: ContributionStatus,
            dueDate: LocalDate,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2020, 1, 1)
                    it[periodEnd] = LocalDate(2021, 1, 1)
                    it[amountDue] = BigDecimal("50.00")
                    it[ContributionTable.status] = status
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[ContributionTable.dueDate] = dueDate
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[sepaMandateId] = null
                    it[paidAt] = null
                    it[paidAmount] = null
                    it[note] = null
                }
            }
            createdContributionIds += id
            return id
        }

        fun createLevel(
            levelNumber: Int,
            feeAmount: BigDecimal? = if (levelNumber == 1) null else BigDecimal("5.00"),
        ): Uuid {
            val id = Uuid.random()
            transaction {
                DunningLevelTable.insert {
                    it[DunningLevelTable.id] = id
                    it[DunningLevelTable.levelNumber] = levelNumber
                    it[name] = "Stufe $levelNumber"
                    it[graceDays] = 1
                    it[responseDays] = 14
                    it[DunningLevelTable.feeAmount] = feeAmount
                    it[active] = true
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdLevelIds[levelNumber] = id
            return id
        }

        fun ackDisclaimer(memberId: Uuid) {
            val id = Uuid.random()
            transaction {
                DunningComplianceAcknowledgmentTable.insert {
                    it[DunningComplianceAcknowledgmentTable.id] = id
                    it[acknowledgedByMemberId] = memberId
                    it[acknowledgedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[disclaimerVersion] = "test"
                    it[disclaimerSha256] = "test"
                }
            }
            createdAckIds += id
        }

        /** Directly inserts an already-ISSUED notice, bypassing [issueDunningNotice] -- for setting up a "cycle already at level N" fixture without depending on the function under test. */
        fun insertIssuedNotice(
            contributionId: Uuid,
            cycleNumber: Int,
            levelNumber: Int,
        ) {
            transaction {
                DunningNoticeTable.insert {
                    it[id] = Uuid.random()
                    it[DunningNoticeTable.contributionId] = contributionId
                    it[dunningLevelId] = createdLevelIds.getValue(levelNumber)
                    it[DunningNoticeTable.cycleNumber] = cycleNumber
                    it[DunningNoticeTable.levelNumber] = levelNumber
                    it[levelName] = "Stufe $levelNumber"
                    it[feeAmount] = if (levelNumber == 1) null else BigDecimal("5.00")
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = DunningNoticeStatus.ISSUED
                    it[issuedAt] = LocalDateTime(2026, 1, 5, 9, 0)
                    it[respondBy] = LocalDate(2026, 1, 19)
                    it[documentId] = null
                    it[postalDeliveryLogId] = null
                    it[createdBy] = null
                    it[cancelledAt] = null
                    it[cancellationReason] = null
                }
            }
        }

        fun storageRoot() =
            kotlin.io.path
                .createTempDirectory("dunning-issuance-test")
                .toFile()

        test(
            "issueDunningNotice: a whole-cycle-cancel landing between Phase 1 and Phase 2 -> Superseded, no zombie level-3 notice, cycle stays fully cancelled",
        ) {
            val memberId = createMember()
            val tierId = createTier()
            ackDisclaimer(memberId)
            createLevel(1)
            createLevel(2)
            createLevel(3)
            val contributionId = createContribution(memberId, tierId, ContributionStatus.IN_DUNNING, LocalDate(2026, 1, 1))

            // Cycle 1: L1 + L2 already ISSUED -- Phase 1 of the call below computes
            // prepared.cycleNumber = 1, prepared.levelNumber = 3 from exactly this state.
            insertIssuedNotice(contributionId, cycleNumber = 1, levelNumber = 1)
            insertIssuedNotice(contributionId, cycleNumber = 1, levelNumber = 2)

            // `onBeforePhase2Lock` fires after PDF generation -- exactly the real race window --
            // and performs the SAME whole-cycle-cancel DB actions `DunningService
            // .cancelDunningNotice` performs (cancel every live notice in the cycle, fall back to
            // OVERDUE), reproducing the round-3 finding's concrete scenario deterministically
            // instead of relying on timing.
            val outcome =
                issueDunningNotice(
                    request =
                        DunningIssueRequest(
                            contributionId = contributionId,
                            actorMemberId = null,
                            actorRole = null,
                            now = LocalDateTime(2026, 1, 10, 9, 0),
                            respectSchedule = false,
                        ),
                    storageRoot = storageRoot(),
                    onBeforePhase2Lock = {
                        transaction {
                            DunningNoticeTable.update({
                                (DunningNoticeTable.contributionId eq contributionId) and
                                    (DunningNoticeTable.status neq DunningNoticeStatus.CANCELLED)
                            }) {
                                it[status] = DunningNoticeStatus.CANCELLED
                                it[cancelledAt] = LocalDateTime(2026, 1, 10, 8, 59)
                                it[cancellationReason] = "Falscher Betrag (race simulation)"
                            }
                            ContributionTable.update({
                                (ContributionTable.id eq contributionId) and (ContributionTable.status eq ContributionStatus.IN_DUNNING)
                            }) {
                                it[status] = ContributionStatus.OVERDUE
                            }
                        }
                    },
                )

            outcome shouldBe DunningIssueOutcome.Superseded

            val notices =
                transaction {
                    DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq contributionId }.toList()
                }
            // Still exactly the two original notices -- no zombie level-3 row landed inside what
            // is now a fully cancelled cycle.
            notices.size shouldBe 2
            notices.all { it[DunningNoticeTable.status] == DunningNoticeStatus.CANCELLED } shouldBe true
            notices.none { it[DunningNoticeTable.levelNumber] == 3 } shouldBe true
            transaction {
                ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
            } shouldBe ContributionStatus.OVERDUE

            // A fresh call (no race this time) recovers correctly: a NEW cycle starting at level 1
            // -- confirming Superseded left the state fully usable, not stuck.
            val recovered =
                issueDunningNotice(
                    request =
                        DunningIssueRequest(
                            contributionId = contributionId,
                            actorMemberId = null,
                            actorRole = null,
                            now = LocalDateTime(2026, 1, 10, 9, 0),
                            respectSchedule = false,
                        ),
                    storageRoot = storageRoot(),
                )
            (recovered is DunningIssueOutcome.Issued) shouldBe true
            (recovered as DunningIssueOutcome.Issued).levelNumber shouldBe 1
        }

        test(
            "issueDunningNotice: a SKIPPED level does NOT count as delivered -- the first REAL notice of the cycle still charges no fee",
        ) {
            val memberId = createMember()
            val tierId = createTier()
            ackDisclaimer(memberId)
            createLevel(1)
            createLevel(2)
            val contributionId = createContribution(memberId, tierId, ContributionStatus.OVERDUE, LocalDate(2026, 1, 1))

            // Simulates DunningService.skipDunningLevel's own insert shape: a SKIPPED level-1
            // notice, feeAmount = null, no PDF/archive/postal dispatch ever happened for it.
            transaction {
                DunningNoticeTable.insert {
                    it[id] = Uuid.random()
                    it[DunningNoticeTable.contributionId] = contributionId
                    it[dunningLevelId] = createdLevelIds.getValue(1)
                    it[cycleNumber] = 1
                    it[levelNumber] = 1
                    it[levelName] = "Stufe 1"
                    it[feeAmount] = null
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = DunningNoticeStatus.SKIPPED
                    it[issuedAt] = LocalDateTime(2026, 1, 5, 9, 0)
                    it[respondBy] = LocalDate(2026, 1, 5)
                    it[documentId] = null
                    it[postalDeliveryLogId] = null
                    it[createdBy] = memberId
                    it[cancelledAt] = null
                    it[cancellationReason] = "Kulanz (test fixture)"
                }
            }

            val outcome =
                issueDunningNotice(
                    request =
                        DunningIssueRequest(
                            contributionId = contributionId,
                            actorMemberId = memberId,
                            actorRole = AccountRole.TREASURER,
                            now = LocalDateTime(2026, 1, 10, 9, 0),
                            respectSchedule = false,
                        ),
                    storageRoot = storageRoot(),
                )

            (outcome is DunningIssueOutcome.Issued) shouldBe true
            val issued = outcome as DunningIssueOutcome.Issued
            issued.levelNumber shouldBe 2
            transaction {
                DunningNoticeTable
                    .selectAll()
                    .where { DunningNoticeTable.id eq issued.noticeId }
                    .single()[DunningNoticeTable.feeAmount]
            } shouldBe null
        }

        test(
            "issueDunningNotice: respectSchedule=true right after a whole-cycle cancel does NOT immediately re-issue -- full graceDays must elapse again from the cancellation",
        ) {
            val memberId = createMember()
            val tierId = createTier()
            ackDisclaimer(memberId)
            createLevel(1)
            val contributionId = createContribution(memberId, tierId, ContributionStatus.OVERDUE, LocalDate(2026, 1, 1))

            // Cycle 1, level 1: ISSUED then CANCELLED (whole-cycle-cancel shape, as
            // DunningService.resetDunning/cancelDunningNotice produce) at 2026-01-10 09:00 -- the
            // original dueDate (2026-01-01) is long past, so a naive dueDate-fallback would consider
            // ANY later `now` immediately due again.
            transaction {
                DunningNoticeTable.insert {
                    it[id] = Uuid.random()
                    it[DunningNoticeTable.contributionId] = contributionId
                    it[dunningLevelId] = createdLevelIds.getValue(1)
                    it[cycleNumber] = 1
                    it[levelNumber] = 1
                    it[levelName] = "Stufe 1"
                    it[feeAmount] = null
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = DunningNoticeStatus.CANCELLED
                    it[issuedAt] = LocalDateTime(2026, 1, 2, 9, 0)
                    it[respondBy] = LocalDate(2026, 1, 16)
                    it[documentId] = null
                    it[postalDeliveryLogId] = null
                    it[createdBy] = memberId
                    it[cancelledAt] = LocalDateTime(2026, 1, 10, 9, 0)
                    it[cancellationReason] = "Irrtum (test fixture)"
                }
                ContributionTable.update({ ContributionTable.id eq contributionId }) { it[status] = ContributionStatus.OVERDUE }
            }

            // graceDays defaults to 1 (see `createLevel`). One minute after the cancellation --
            // MUCH less than one full day -- the poller-style respectSchedule=true call must NOT
            // yet re-issue.
            val tooSoon =
                issueDunningNotice(
                    request =
                        DunningIssueRequest(
                            contributionId = contributionId,
                            actorMemberId = null,
                            actorRole = null,
                            now = LocalDateTime(2026, 1, 10, 9, 1),
                            respectSchedule = true,
                        ),
                    storageRoot = storageRoot(),
                )
            tooSoon shouldBe DunningIssueOutcome.NotDue

            // A FULL graceDays (1 day) after the cancellation, the cooldown has elapsed and
            // respectSchedule=true issues normally, starting the fresh cycle at level 1.
            val afterCooldown =
                issueDunningNotice(
                    request =
                        DunningIssueRequest(
                            contributionId = contributionId,
                            actorMemberId = null,
                            actorRole = null,
                            now = LocalDateTime(2026, 1, 11, 9, 0),
                            respectSchedule = true,
                        ),
                    storageRoot = storageRoot(),
                )
            (afterCooldown is DunningIssueOutcome.Issued) shouldBe true
            (afterCooldown as DunningIssueOutcome.Issued).levelNumber shouldBe 1
        }

        test("issueDunningNotice: no race (onBeforePhase2Lock no-op) -> issues normally, unaffected by the new Phase 2 re-check") {
            val memberId = createMember()
            val tierId = createTier()
            ackDisclaimer(memberId)
            createLevel(1)
            val contributionId = createContribution(memberId, tierId, ContributionStatus.OVERDUE, LocalDate(2026, 1, 1))

            val outcome =
                issueDunningNotice(
                    request =
                        DunningIssueRequest(
                            contributionId = contributionId,
                            actorMemberId = null,
                            actorRole = null,
                            now = LocalDateTime(2026, 1, 10, 9, 0),
                            respectSchedule = false,
                        ),
                    storageRoot = storageRoot(),
                )

            (outcome is DunningIssueOutcome.Issued) shouldBe true
            (outcome as DunningIssueOutcome.Issued).levelNumber shouldBe 1
        }
    })
