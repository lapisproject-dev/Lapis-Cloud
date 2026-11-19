package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingRewardCommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyChangeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.meetingreward.MEETING_REWARD_POLICY_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingRewardLineReason
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ProtocolConfirmationMode
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.316 -- the five meeting_reward tables are organisational data and travel in the whole-organisation backup. The restore order follows the foreign keys
 * on its own (policy change before committee and settlement, settlement before line, ledger and protocol version before both); this test pins it with a
 * row in every table, including the seeded singleton policy row (it is overwritten by the restore, not duplicated, and does not make the target "non-empty").
 */
class MeetingRewardBackupTest :
    FunSpec({
        test("policy, change, committee, settlement and line rows round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("reward-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("reward-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("reward-source").toFile()
            val targetRoot = Files.createTempDirectory("reward-target").toFile()
            val adminId = Uuid.random()
            val otherAdminId = Uuid.random()
            val memberId = Uuid.random()
            val assemblyId = Uuid.random()
            val committeeId = Uuid.random()
            val assemblyMeetingId = Uuid.random()
            val meetingId = Uuid.random()
            val resolutionId = Uuid.random()
            val changeId = Uuid.random()
            val protocolId = Uuid.random()
            val settlementId = Uuid.random()
            val lineId = Uuid.random()
            val ledgerId = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)

            transaction(sourceDb) {
                listOf(adminId to "admin", otherAdminId to "admin2", memberId to "member").forEach { (id, label) ->
                    MemberTable.insert {
                        it[MemberTable.id] = id
                        it[displayName] = "Reward Backup $label"
                        it[email] = "reward-backup-$label@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                    }
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it.legacyAccountRole(role = AccountRole.ADMIN)
                }
                val committeeTypes = listOf(assemblyId to CommitteeType.GENERAL_ASSEMBLY, committeeId to CommitteeType.WORKING_GROUP)
                committeeTypes.forEach { (id, committeeType) ->
                    CommitteeTable.insert {
                        it[CommitteeTable.id] = id
                        it[name] = "Backup-Gremium $committeeType"
                        it[type] = committeeType
                        it[description] = "x"
                        it[active] = true
                        it[quorumPercent] = 50
                        it[createdAt] = at
                    }
                }
                listOf(assemblyMeetingId to assemblyId, meetingId to committeeId).forEach { (id, committee) ->
                    MeetingTable.insert {
                        it[MeetingTable.id] = id
                        it[MeetingTable.committeeId] = committee
                        it[title] = "Sitzung"
                        it[scheduledAt] = at
                        it[location] = null
                        it[format] = MeetingFormat.IN_PERSON
                        it[status] = MeetingStatus.HELD
                        it[calledBy] = null
                        it[calledAt] = null
                        it[chairMemberId] = null
                        it[minuteTakerMemberId] = null
                        it[protocolDocumentId] = null
                        it[createdAt] = at
                    }
                }
                ResolutionTable.insert {
                    it[id] = resolutionId
                    it[number] = "REWARD-BACKUP-2027-01"
                    it[title] = "Beschluss"
                    it[text] = "Text"
                    it[votesYes] = 3
                    it[votesNo] = 0
                    it[votesAbstain] = 0
                    it[quorumMet] = true
                    it[status] = ResolutionStatus.ADOPTED
                    it[decidedAt] = at
                    it[recordedBy] = adminId
                    it[resolutionMode] = ResolutionMode.COMMITTEE_QUORUM
                    it[voteId] = null
                    it[electionId] = null
                    it[systemicConsensusId] = null
                    it[ResolutionTable.meetingId] = assemblyMeetingId
                    it[agendaItemId] = null
                }
                ProtocolVersionTable.insert {
                    it[id] = protocolId
                    it[ProtocolVersionTable.meetingId] = meetingId
                    it[versionNo] = 1
                    it[contentJson] = "{}"
                    it[contentSha256] = "0".repeat(64)
                    it[correctionNote] = null
                    it[finalisedBy] = adminId
                    it[finalisedAt] = at
                    it[confirmationMode] = ProtocolConfirmationMode.APP_CONFIRMATION
                    it[minutesRequired] = false
                    it[status] = ProtocolVersionStatus.CONFIRMED
                    it[pdfDocumentVersionId] = null
                }
                MeetingRewardPolicyChangeTable.insert {
                    it[id] = changeId
                    it[kind] = MeetingRewardPolicyChangeKind.CHANGE
                    it[status] = MeetingRewardPolicyChangeStatus.CONFIRMED
                    it[proposedBy] = adminId
                    it[proposedAt] = at
                    it[decidedBy] = otherAdminId
                    it[decidedAt] = at
                    it[MeetingRewardPolicyChangeTable.resolutionId] = resolutionId
                    it[resolutionReference] = "TOP 7"
                    it[oldEnabled] = false
                    it[oldAttendanceAmount] = BigDecimal("0.00")
                    it[oldBonusChair] = BigDecimal("0.00")
                    it[oldBonusDeputyChair] = BigDecimal("0.00")
                    it[oldBonusSecretary] = BigDecimal("0.00")
                    it[oldBonusManagingDirector] = BigDecimal("0.00")
                    it[oldCapPerPersonPerYear] = BigDecimal("240.00")
                    it[oldCapPerMeeting] = BigDecimal("1000.00")
                    it[newEnabled] = true
                    it[newAttendanceAmount] = BigDecimal("10.00")
                    it[newBonusChair] = BigDecimal("5.00")
                    it[newBonusDeputyChair] = BigDecimal("4.00")
                    it[newBonusSecretary] = BigDecimal("3.00")
                    it[newBonusManagingDirector] = BigDecimal("6.00")
                    it[newCapPerPersonPerYear] = BigDecimal("240.00")
                    it[newCapPerMeeting] = BigDecimal("1000.00")
                    it[oldCommitteeIds] = ""
                    it[newCommitteeIds] = committeeId.toString()
                }
                MeetingRewardPolicyTable.update({ MeetingRewardPolicyTable.id eq MEETING_REWARD_POLICY_ID }) {
                    it[enabled] = true
                    it[attendanceAmount] = BigDecimal("10.00")
                    it[bonusChair] = BigDecimal("5.00")
                    it[MeetingRewardPolicyTable.resolutionId] = resolutionId
                    it[resolutionReference] = "TOP 7"
                    it[lastChangeId] = changeId
                    it[updatedAt] = at
                }
                MeetingRewardCommitteeTable.insert {
                    it[MeetingRewardCommitteeTable.committeeId] = committeeId
                    it[enabledByChangeId] = changeId
                    it[enabledAt] = at
                }
                MeetingRewardSettlementTable.insert {
                    it[id] = settlementId
                    it[MeetingRewardSettlementTable.meetingId] = meetingId
                    it[status] = MeetingRewardSettlementStatus.RELEASED
                    it[preparedBy] = adminId
                    it[preparedAt] = at
                    it[releasedBy] = otherAdminId
                    it[releasedAt] = at
                    it[previewHash] = "a".repeat(64)
                    it[policyChangeId] = changeId
                    it[protocolVersionId] = protocolId
                    it[totalAmount] = BigDecimal("15.00")
                }
                LtrLedgerEntryTable.insert {
                    it[id] = ledgerId
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MEETING_REWARD
                    it[amountLtr] = BigDecimal("15.00")
                    it[referenceType] = LtrLedgerReferenceType.MEETING
                    it[referenceId] = meetingId
                    it[note] = null
                    it[createdBy] = otherAdminId
                    it[createdAt] = at
                }
                MeetingRewardLineTable.insert {
                    it[id] = lineId
                    it[MeetingRewardLineTable.settlementId] = settlementId
                    it[MeetingRewardLineTable.meetingId] = meetingId
                    it[MeetingRewardLineTable.memberId] = memberId
                    it[attendanceStatus] = AttendanceStatus.PRESENT
                    it[reason] = MeetingRewardLineReason.CAP_REDUCED
                    it[functionRole] = CommitteeRole.CHAIR
                    it[baseAmount] = BigDecimal("10.00")
                    it[bonusAmount] = BigDecimal("5.00")
                    it[amount] = BigDecimal("15.00")
                    it[reduced] = true
                    it[periodYear] = 2027
                    it[idempotencyKey] = "meeting-reward:$meetingId:$memberId"
                    it[ledgerEntryId] = ledgerId
                }
            }

            val bundle = File.createTempFile("reward-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    // the singleton was overwritten, not duplicated
                    val policy = MeetingRewardPolicyTable.selectAll().single()
                    policy[MeetingRewardPolicyTable.id] shouldBe MEETING_REWARD_POLICY_ID
                    policy[MeetingRewardPolicyTable.enabled] shouldBe true
                    policy[MeetingRewardPolicyTable.attendanceAmount].toPlainString() shouldBe "10.00"
                    policy[MeetingRewardPolicyTable.lastChangeId] shouldBe changeId
                    policy[MeetingRewardPolicyTable.resolutionId] shouldBe resolutionId
                    val change = MeetingRewardPolicyChangeTable.selectAll().single()
                    change[MeetingRewardPolicyChangeTable.kind] shouldBe MeetingRewardPolicyChangeKind.CHANGE
                    change[MeetingRewardPolicyChangeTable.newCommitteeIds] shouldBe committeeId.toString()
                    change[MeetingRewardPolicyChangeTable.resolutionReference] shouldBe "TOP 7"
                    MeetingRewardCommitteeTable.selectAll().single()[MeetingRewardCommitteeTable.committeeId] shouldBe committeeId
                    val settlement = MeetingRewardSettlementTable.selectAll().single()
                    settlement[MeetingRewardSettlementTable.status] shouldBe MeetingRewardSettlementStatus.RELEASED
                    settlement[MeetingRewardSettlementTable.totalAmount].toPlainString() shouldBe "15.00"
                    val line = MeetingRewardLineTable.selectAll().single()
                    line[MeetingRewardLineTable.ledgerEntryId] shouldBe ledgerId
                    line[MeetingRewardLineTable.reason] shouldBe MeetingRewardLineReason.CAP_REDUCED
                    line[MeetingRewardLineTable.functionRole] shouldBe CommitteeRole.CHAIR
                    line[MeetingRewardLineTable.amount].toPlainString() shouldBe "15.00"
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
