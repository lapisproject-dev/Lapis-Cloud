package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.201 -- the four tender tables are organizational data and travel in the whole-organization backup. The restore order follows the foreign keys on
 * its own (tender before tender_bid before tender_award / tender_acceptance, all after motion, resolution and member); this test pins it with rows
 * in every table, including the nullable columns (award motion, payout hook).
 */
class TenderBackupTest :
    FunSpec({
        test("tender, bid, award and acceptance rows round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("tender-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("tender-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("tender-source").toFile()
            val targetRoot = Files.createTempDirectory("tender-target").toFile()
            val adminId = Uuid.random()
            val bidderId = Uuid.random()
            val committeeId = Uuid.random()
            val meetingId = Uuid.random()
            val resolutionId = Uuid.random()
            val motionId = Uuid.random()
            val tenderId = Uuid.random()
            val bidId = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)

            transaction(sourceDb) {
                listOf(adminId to "admin", bidderId to "bidder").forEach { (memberId, label) ->
                    MemberTable.insert {
                        it[id] = memberId
                        it[displayName] = "Ausschreibung Backup $label"
                        it[email] = "tender-backup-$label@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                    }
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it[role] = AccountRole.ADMIN
                }
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "Backup-Gremium"
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "x"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = at
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
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
                ResolutionTable.insert {
                    it[id] = resolutionId
                    it[number] = "BACKUP-2027-01"
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
                    it[ResolutionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                }
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = committeeId
                    it[title] = "Antrag"
                    it[rationale] = "x"
                    it[text] = "x"
                    it[submitterMemberId] = adminId
                    it[status] = MotionStatus.RESOLVED
                    it[submittedAt] = at
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[MotionTable.resolutionId] = resolutionId
                    it[withdrawnAt] = null
                    it[amendsMotionId] = null
                    it[currentText] = null
                }
                TenderTable.insert {
                    it[id] = tenderId
                    it[title] = "Ausschreibung"
                    it[description] = "Beschreibung mit **Markdown**"
                    it[descriptionFormat] = TenderDescriptionFormat.MARKDOWN
                    it[expectedResult] = "Ergebnis"
                    it[bindingMotionId] = motionId
                    it[awardCriterion] = TenderAwardCriterion.LOWEST_PRICE
                    it[currency] = TenderCurrency.LTR
                    it[bidVisibility] = TenderBidVisibility.OWN_ONLY
                    it[status] = TenderStatus.AWARDED
                    it[deadline] = at
                    it[createdBy] = adminId
                    it[createdAt] = at
                    it[publishedBy] = adminId
                    it[publishedAt] = at
                    it[closedAt] = at
                    it[cancelledBy] = null
                    it[cancelledAt] = null
                    it[cancelReason] = null
                    it[awardMotionId] = null
                    it[payoutLedgerEntryId] = null
                }
                TenderBidTable.insert {
                    it[id] = bidId
                    it[TenderBidTable.tenderId] = tenderId
                    it[bidderMemberId] = bidderId
                    it[price] = java.math.BigDecimal("12.50")
                    it[currency] = TenderCurrency.LTR
                    it[text] = "Angebot"
                    it[status] = TenderBidStatus.AWARDED
                    it[submittedAt] = at
                    it[priceSetAt] = at
                    it[withdrawnAt] = null
                }
                TenderAwardTable.insert {
                    it[id] = Uuid.random()
                    it[TenderAwardTable.tenderId] = tenderId
                    it[TenderAwardTable.bidId] = bidId
                    it[awardedBy] = adminId
                    it[awardedAt] = at
                    it[awardMotionId] = null
                    it[TenderAwardTable.resolutionId] = null
                    it[justification] = null
                    it[conflictedCount] = 1
                    it[quorumEligibleCount] = null
                }
                TenderAcceptanceTable.insert {
                    it[id] = Uuid.random()
                    it[TenderAcceptanceTable.tenderId] = tenderId
                    it[decision] = TenderAcceptanceDecision.REJECTED
                    it[reason] = "Nachbessern"
                    it[decidedBy] = adminId
                    it[decidedAt] = at
                }
            }

            val bundle = File.createTempFile("tender-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val tender = TenderTable.selectAll().where { TenderTable.id eq tenderId }.single()
                    tender[TenderTable.bidVisibility] shouldBe TenderBidVisibility.OWN_ONLY
                    tender[TenderTable.status] shouldBe TenderStatus.AWARDED
                    tender[TenderTable.descriptionFormat] shouldBe TenderDescriptionFormat.MARKDOWN
                    tender[TenderTable.description] shouldBe "Beschreibung mit **Markdown**"
                    tender[TenderTable.deadline] shouldBe at
                    tender[TenderTable.awardMotionId] shouldBe null
                    val bid = TenderBidTable.selectAll().where { TenderBidTable.id eq bidId }.single()
                    bid[TenderBidTable.price].toPlainString() shouldBe "12.50"
                    bid[TenderBidTable.bidderMemberId] shouldBe bidderId
                    TenderAwardTable.selectAll().single()[TenderAwardTable.conflictedCount] shouldBe 1
                    TenderAcceptanceTable.selectAll().single()[TenderAcceptanceTable.reason] shouldBe "Nachbessern"
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
