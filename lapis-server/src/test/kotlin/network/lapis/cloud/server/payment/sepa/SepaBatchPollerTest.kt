package network.lapis.cloud.server.payment.sepa

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SepaReturnTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaSequenceType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Review Round 1 (2026-08-19) regression coverage for [SepaBatchPoller] -- before this file, ZERO
 * tests existed for any of the poller's three phases (M-1). Own freshly created fixtures, direct
 * table inserts for setup, same house style [network.lapis.cloud.server.rpc.ContributionPaymentRpcTest]
 * establishes. Every test constructs its own [SepaBatchPoller] with an injected fixed [clock] --
 * [SepaBatchPoller.tick] never depends on the real wall clock in this file.
 */
class SepaBatchPollerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdBatchIds = mutableListOf<Uuid>()
        val createdMandateIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()
        val createdDocumentFolderIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = true
                }
            }
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdBatchIds.isNotEmpty()) {
                    SepaReturnTable.deleteWhere {
                        SepaReturnTable.debitItemId inList
                            SepaDebitItemTable.selectAll().where { SepaDebitItemTable.batchId inList createdBatchIds }.map {
                                it[SepaDebitItemTable.id]
                            }
                    }
                    SepaDebitItemTable.deleteWhere { SepaDebitItemTable.batchId inList createdBatchIds }
                    SepaDebitBatchTable.deleteWhere { SepaDebitBatchTable.id inList createdBatchIds }
                }
                if (createdDocumentIds.isNotEmpty()) {
                    // AFTER the batch deletion above -- generated_document_id is a real FK into
                    // document, and the referencing batch row is already gone by this point.
                    DocumentTable.deleteWhere { DocumentTable.id inList createdDocumentIds }
                }
                if (createdDocumentFolderIds.isNotEmpty()) {
                    DocumentFolderTable.deleteWhere { DocumentFolderTable.id inList createdDocumentFolderIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdMandateIds.isNotEmpty()) {
                    SepaMandateTable.deleteWhere { SepaMandateTable.id inList createdMandateIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = false
                }
            }
        }

        fun createMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Poller-Fixture Mitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
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
                    it[name] = "Poller-Fixture Tarif ${id.toString().take(6)}"
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

        fun createMandate(
            memberId: Uuid,
            createdBy: Uuid,
            grantedAt: LocalDateTime = LocalDateTime(2020, 1, 1, 10, 0),
            lastUsedAt: LocalDate? = null,
            status: SepaMandateStatus = SepaMandateStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                SepaMandateTable.insert {
                    it[SepaMandateTable.id] = id
                    it[SepaMandateTable.memberId] = memberId
                    it[mandateReference] = "LC-POLLER-${id.toString().take(8)}"
                    it[debtorName] = "Poller Testkonto"
                    it[debtorIbanCiphertext] = "unused-ciphertext-$id"
                    it[debtorIbanSetAt] = grantedAt
                    it[debtorIbanLast4] = "1234"
                    it[debtorBic] = null
                    it[signatureDate] = grantedAt.date
                    it[sequenceType] = SepaSequenceType.FRST
                    it[SepaMandateTable.status] = status
                    it[SepaMandateTable.grantedAt] = grantedAt
                    it[revokedAt] = null
                    it[revokedBy] = null
                    it[revocationReason] = null
                    it[SepaMandateTable.lastUsedAt] = lastUsedAt
                    it[lastDebitedAmount] = null
                    it[SepaMandateTable.createdBy] = createdBy
                }
            }
            createdMandateIds += id
            return id
        }

        fun createSubmittedBatchWithItem(
            memberId: Uuid,
            mandateId: Uuid,
            tierId: Uuid,
            createdBy: Uuid,
            submittedAt: LocalDateTime,
            itemStatus: SepaDebitItemStatus = SepaDebitItemStatus.PENDING,
        ): Pair<Uuid, Uuid> {
            val contributionId = Uuid.random()
            val batchId = Uuid.random()
            val itemId = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[id] = contributionId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = ContributionStatus.DEBIT_SUBMITTED
                    it[ContributionTable.createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.SEPA_DEBIT
                    it[sepaMandateId] = mandateId
                }
                SepaDebitBatchTable.insert {
                    it[id] = batchId
                    it[messageId] = "LC-DD-POLLER-${batchId.toString().take(8)}"
                    it[paymentInfoId] = "LC-DD-POLLER-${batchId.toString().take(8)}-P1"
                    it[requestedCollectionDate] = submittedAt.date
                    it[sequenceType] = SepaSequenceType.RCUR
                    it[status] = SepaDebitBatchStatus.SUBMITTED
                    it[itemCount] = 1
                    it[totalAmount] = BigDecimal("50.00")
                    it[SepaDebitBatchTable.createdBy] = createdBy
                    it[createdAt] = submittedAt
                    it[notifiedAt] = submittedAt
                    it[requiredNoticeDays] = 14
                    it[generatedAt] = submittedAt
                    it[generatedDocumentId] = null
                    it[prenotificationDocumentId] = null
                    it[SepaDebitBatchTable.submittedAt] = submittedAt
                    it[submittedNote] = null
                    it[settledAt] = null
                    it[cancelledAt] = null
                    it[cancellationReason] = null
                }
                SepaDebitItemTable.insert {
                    it[id] = itemId
                    it[SepaDebitItemTable.batchId] = batchId
                    it[SepaDebitItemTable.contributionId] = contributionId
                    it[SepaDebitItemTable.mandateId] = mandateId
                    it[endToEndId] = contributionId.toString().replace("-", "").uppercase()
                    it[amount] = BigDecimal("50.00")
                    it[remittanceInformation] = "Testbeitrag"
                    it[status] = itemStatus
                    it[settleableAt] = null
                    it[journalEntryId] = null
                }
            }
            createdContributionIds += contributionId
            createdBatchIds += batchId
            return batchId to itemId
        }

        /**
         * A batch already in [SepaDebitBatchStatus.GENERATED] with a REAL [DocumentTable]/
         * [DocumentFolderTable] row attached (no [network.lapis.cloud.server.db.generated.DocumentVersionTable]
         * row -- [network.lapis.cloud.server.rpc.resetGeneratedBatchesForUnusableMandate]'s own
         * batch-reset step only ever touches `isDeleted`, never a version)
         * -- used by the NEW-1 (Security Round 2) Phase B test below to prove the document actually
         * gets soft-deleted when the poller's own auto-revocation resets this batch, not just that the
         * batch's own `generated_document_id` column goes back to `null`.
         */
        fun createGeneratedBatchWithItem(
            memberId: Uuid,
            mandateId: Uuid,
            tierId: Uuid,
            createdBy: Uuid,
            notifiedAt: LocalDateTime,
        ): Triple<Uuid, Uuid, Uuid> {
            val contributionId = Uuid.random()
            val batchId = Uuid.random()
            val itemId = Uuid.random()
            val folderId = Uuid.random()
            val documentId = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[id] = contributionId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = ContributionStatus.DEBIT_SCHEDULED
                    it[ContributionTable.createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.SEPA_DEBIT
                    it[sepaMandateId] = mandateId
                }
                DocumentFolderTable.insert {
                    it[id] = folderId
                    it[name] = "SEPA-Lastschriften-Poller-Test"
                    it[parentFolderId] = null
                }
                DocumentTable.insert {
                    it[id] = documentId
                    it[DocumentTable.folderId] = folderId
                    it[title] = "Poller-Test SEPA-Datei"
                    it[currentVersionId] = null
                    it[DocumentTable.createdBy] = createdBy
                    it[createdAt] = notifiedAt
                    it[accessLevel] = DocumentAccessLevel.ADMIN_ONLY
                    it[isDeleted] = false
                }
                SepaDebitBatchTable.insert {
                    it[id] = batchId
                    it[messageId] = "LC-DD-POLLER-${batchId.toString().take(8)}"
                    it[paymentInfoId] = "LC-DD-POLLER-${batchId.toString().take(8)}-P1"
                    it[requestedCollectionDate] = notifiedAt.date
                    it[sequenceType] = SepaSequenceType.RCUR
                    it[status] = SepaDebitBatchStatus.GENERATED
                    it[itemCount] = 1
                    it[totalAmount] = BigDecimal("50.00")
                    it[SepaDebitBatchTable.createdBy] = createdBy
                    it[createdAt] = notifiedAt
                    it[SepaDebitBatchTable.notifiedAt] = notifiedAt
                    it[requiredNoticeDays] = 14
                    it[generatedAt] = notifiedAt
                    it[generatedDocumentId] = documentId
                    it[prenotificationDocumentId] = null
                    it[submittedAt] = null
                    it[submittedNote] = null
                    it[settledAt] = null
                    it[cancelledAt] = null
                    it[cancellationReason] = null
                }
                SepaDebitItemTable.insert {
                    it[id] = itemId
                    it[SepaDebitItemTable.batchId] = batchId
                    it[SepaDebitItemTable.contributionId] = contributionId
                    it[SepaDebitItemTable.mandateId] = mandateId
                    it[endToEndId] = contributionId.toString().replace("-", "").uppercase()
                    it[amount] = BigDecimal("50.00")
                    it[remittanceInformation] = "Testbeitrag"
                    it[status] = SepaDebitItemStatus.PENDING
                    it[settleableAt] = null
                    it[journalEntryId] = null
                }
            }
            createdContributionIds += contributionId
            createdBatchIds += batchId
            createdDocumentIds += documentId
            createdDocumentFolderIds += folderId
            return Triple(batchId, itemId, documentId)
        }

        // ── Phase A: 36-month mandate expiry ──────────────────────────────

        test("Phase A: a mandate never used expires exactly 36 months after grantedAt -- boundary inclusive on the exact day") {
            val treasurer = createMember(email = "poller-a1-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-a1-member-${Uuid.random()}@example.org")
            // Feb 29 (leap year) grantedAt -- deliberately a month-end edge case for the date
            // arithmetic itself, not just the >= vs < comparison.
            val grantedAt = LocalDateTime(2024, 2, 29, 9, 0)
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = grantedAt, lastUsedAt = null)
            val expiresAt = SepaConfig.mandateExpiryDate(grantedAt = grantedAt.date, lastUsedAt = null)

            // "now" == exactly the expiry date -- still valid (`expiresAt >= now.date` in the poller).
            val poller =
                SepaBatchPoller(
                    sepaConfig = SepaConfig.load { null },
                    clock = { LocalDateTime(expiresAt.year, expiresAt.monthNumber, expiresAt.dayOfMonth, 12, 0) },
                )
            runBlocking { poller.tick() }
            transaction {
                SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status]
            } shouldBe SepaMandateStatus.ACTIVE

            // "now" == expiry date + 1 day -- now expired.
            val dayAfter = expiresAt.plus(1, DateTimeUnit.DAY)
            val pollerAfter =
                SepaBatchPoller(
                    sepaConfig = SepaConfig.load { null },
                    clock = { LocalDateTime(dayAfter.year, dayAfter.monthNumber, dayAfter.dayOfMonth, 12, 0) },
                )
            runBlocking { pollerAfter.tick() }
            transaction {
                SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status]
            } shouldBe SepaMandateStatus.EXPIRED
        }

        test("Phase A: lastUsedAt resets the 36-month clock, not grantedAt") {
            val treasurer = createMember(email = "poller-a2-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-a2-member-${Uuid.random()}@example.org")
            val grantedAt = LocalDateTime(2018, 1, 1, 9, 0) // long ago -- would be expired by grantedAt alone
            val lastUsedAt = LocalDate(2026, 1, 1) // recent collection resets the clock
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = grantedAt, lastUsedAt = lastUsedAt)

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { LocalDateTime(2026, 8, 19, 12, 0) })
            runBlocking { poller.tick() }
            transaction {
                SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status]
            } shouldBe SepaMandateStatus.ACTIVE
        }

        test(
            "F-1b (Security Round 3): Phase A's 36-month expiry auto-flip also resets a GENERATED " +
                "batch holding a PENDING item for the now-expired mandate -- soft-deletes the stale " +
                "document and audits the reset to the system, proven against a REAL poller tick. " +
                "Mirrors the NEW-1 (Security Round 2) Phase B test below, but for the EXPIRED path " +
                "instead of REVOKED-via-membership-withdrawal",
        ) {
            val treasurer = createMember(email = "poller-f1b-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-f1b-member-${Uuid.random()}@example.org")
            val tier = createTier()
            // Granted long ago, never used -- well past the 36-month expiry boundary relative to the
            // fixed poller clock below.
            val grantedAt = LocalDateTime(2018, 1, 1, 9, 0)
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = grantedAt, lastUsedAt = null)
            val (batchId, itemId, documentId) =
                createGeneratedBatchWithItem(
                    memberId = member,
                    mandateId = mandateId,
                    tierId = tier,
                    createdBy = treasurer,
                    notifiedAt = grantedAt,
                )

            val auditCountBefore =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq batchId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.SEPA_DEBIT_BATCH)
                        }.count()
                }

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { LocalDateTime(2026, 8, 19, 12, 0) })
            runBlocking { poller.tick() }

            // The mandate itself -- unchanged Phase A contract.
            val mandateRow = transaction { SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single() }
            mandateRow[SepaMandateTable.status] shouldBe SepaMandateStatus.EXPIRED

            // F-1b's own consequence: the GENERATED batch is reset, its item cancelled, and its stale
            // document invalidated -- exactly the SAME effect the NEW-1 Phase B test below already
            // proves for the membership-withdrawal/REVOKED path, but here triggered purely by the
            // 36-month EXPIRY path via a real poller tick.
            val batchRow = transaction { SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.single() }
            batchRow[SepaDebitBatchTable.status] shouldBe SepaDebitBatchStatus.NOTIFIED
            batchRow[SepaDebitBatchTable.generatedDocumentId] shouldBe null
            batchRow[SepaDebitBatchTable.generatedAt] shouldBe null
            batchRow[SepaDebitBatchTable.itemCount] shouldBe 0
            batchRow[SepaDebitBatchTable.totalAmount] shouldBe BigDecimal("0.00")
            // notifiedAt/requiredNoticeDays stay untouched -- same contract as the human-initiated path.
            batchRow[SepaDebitBatchTable.notifiedAt] shouldBe grantedAt
            batchRow[SepaDebitBatchTable.requiredNoticeDays] shouldBe 14

            val itemRow = transaction { SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single() }
            itemRow[SepaDebitItemTable.status] shouldBe SepaDebitItemStatus.CANCELLED

            val documentDeleted =
                transaction { DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.isDeleted] }
            documentDeleted shouldBe true

            val auditCountAfter =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq batchId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.SEPA_DEBIT_BATCH)
                        }.count()
                }
            auditCountAfter shouldBe auditCountBefore + 1L

            val newBatchAuditActor =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq batchId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.SEPA_DEBIT_BATCH)
                        }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                        .limit(1)
                        .single()[AuditLogEntryTable.actorMemberId]
                }
            newBatchAuditActor shouldBe null // system actor -- the poller has no human caller at all
        }

        // ── Phase B: WITHDRAWN/REJECTED membership auto-revokes the mandate ─

        test("Phase B: ACTIVE mandate is auto-revoked when the member's status becomes WITHDRAWN") {
            val treasurer = createMember(email = "poller-b1-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-b1-member-${Uuid.random()}@example.org", status = MemberStatus.WITHDRAWN)
            // grantedAt recent enough to stay within the 36-month Phase A window -- otherwise Phase A
            // would expire this mandate first and Phase B would never see it as ACTIVE anymore.
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = LocalDateTime(2026, 1, 1, 9, 0))

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { LocalDateTime(2026, 8, 19, 12, 0) })
            runBlocking { poller.tick() }

            val row = transaction { SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single() }
            row[SepaMandateTable.status] shouldBe SepaMandateStatus.REVOKED
            row[SepaMandateTable.revokedBy] shouldBe null // system actor, not a human
        }

        test("Phase B: ACTIVE mandate untouched while the member stays ACTIVE") {
            val treasurer = createMember(email = "poller-b2-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-b2-member-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
            // grantedAt recent enough to stay within the 36-month Phase A window -- otherwise Phase A
            // would expire this mandate first and Phase B would never see it as ACTIVE anymore.
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = LocalDateTime(2026, 1, 1, 9, 0))

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { LocalDateTime(2026, 8, 19, 12, 0) })
            runBlocking { poller.tick() }

            transaction {
                SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status]
            } shouldBe SepaMandateStatus.ACTIVE
        }

        test(
            "NEW-1 (Security Round 2): Phase B's auto-revocation also resets a GENERATED batch holding " +
                "a PENDING item for the withdrawn member's mandate -- soft-deletes the stale document " +
                "and audits the reset to the system, proven against a REAL poller tick (not a direct " +
                "call to the extracted helper)",
        ) {
            val treasurer = createMember(email = "poller-n1b-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-n1b-member-${Uuid.random()}@example.org", status = MemberStatus.WITHDRAWN)
            val tier = createTier()
            // grantedAt recent enough to stay within the 36-month Phase A window -- otherwise Phase A
            // would expire this mandate first and Phase B would never see it as ACTIVE anymore.
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = LocalDateTime(2026, 1, 1, 9, 0))
            val (batchId, itemId, documentId) =
                createGeneratedBatchWithItem(
                    memberId = member,
                    mandateId = mandateId,
                    tierId = tier,
                    createdBy = treasurer,
                    notifiedAt = LocalDateTime(2026, 1, 1, 9, 0),
                )

            val auditCountBefore =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq batchId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.SEPA_DEBIT_BATCH)
                        }.count()
                }

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { LocalDateTime(2026, 8, 19, 12, 0) })
            runBlocking { poller.tick() }

            // The mandate itself -- unchanged Phase B contract.
            val mandateRow = transaction { SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single() }
            mandateRow[SepaMandateTable.status] shouldBe SepaMandateStatus.REVOKED
            mandateRow[SepaMandateTable.revokedBy] shouldBe null // system actor, not a human

            // NEW-1's own consequence: the GENERATED batch is reset, its item cancelled, and its stale
            // document invalidated -- exactly the SAME effect revokeMandate's own MAJOR-3 fix already
            // proves for the human-initiated path, but here triggered purely by a real poller tick.
            val batchRow = transaction { SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.single() }
            batchRow[SepaDebitBatchTable.status] shouldBe SepaDebitBatchStatus.NOTIFIED
            batchRow[SepaDebitBatchTable.generatedDocumentId] shouldBe null
            batchRow[SepaDebitBatchTable.generatedAt] shouldBe null
            batchRow[SepaDebitBatchTable.itemCount] shouldBe 0
            batchRow[SepaDebitBatchTable.totalAmount] shouldBe BigDecimal("0.00")
            // notifiedAt/requiredNoticeDays stay untouched -- same contract as the human-initiated path.
            batchRow[SepaDebitBatchTable.notifiedAt] shouldBe LocalDateTime(2026, 1, 1, 9, 0)
            batchRow[SepaDebitBatchTable.requiredNoticeDays] shouldBe 14

            val itemRow = transaction { SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single() }
            itemRow[SepaDebitItemTable.status] shouldBe SepaDebitItemStatus.CANCELLED

            val documentDeleted =
                transaction { DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.isDeleted] }
            documentDeleted shouldBe true

            val auditCountAfter =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq batchId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.SEPA_DEBIT_BATCH)
                        }.count()
                }
            auditCountAfter shouldBe auditCountBefore + 1L

            val newBatchAuditActor =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq batchId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.SEPA_DEBIT_BATCH)
                        }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                        .limit(1)
                        .single()[AuditLogEntryTable.actorMemberId]
                }
            newBatchAuditActor shouldBe null // system actor -- the poller has no human caller at all
        }

        // ── Phase C: SETTLEABLE marking after the 8-week return window ─────

        test("Phase C: a PENDING item past the 56-day return window with no return becomes SETTLEABLE") {
            val treasurer = createMember(email = "poller-c1-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-c1-member-${Uuid.random()}@example.org")
            val tier = createTier()
            val mandateId = createMandate(memberId = member, createdBy = treasurer)
            val submittedAt = LocalDateTime(2026, 1, 1, 9, 0)
            val (_, itemId) =
                createSubmittedBatchWithItem(
                    memberId = member,
                    mandateId = mandateId,
                    tierId = tier,
                    createdBy = treasurer,
                    submittedAt = submittedAt,
                )

            // 57 days after submission -- one day past the 56-day window.
            val now = LocalDateTime(2026, 2, 27, 9, 0)
            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { now })
            runBlocking { poller.tick() }

            val row = transaction { SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single() }
            row[SepaDebitItemTable.status] shouldBe SepaDebitItemStatus.SETTLEABLE
            row[SepaDebitItemTable.settleableAt] shouldBe now.date
        }

        test("Phase C: an item still within the 56-day return window stays PENDING") {
            val treasurer = createMember(email = "poller-c2-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-c2-member-${Uuid.random()}@example.org")
            val tier = createTier()
            val mandateId = createMandate(memberId = member, createdBy = treasurer)
            val submittedAt = LocalDateTime(2026, 1, 1, 9, 0)
            val (_, itemId) =
                createSubmittedBatchWithItem(
                    memberId = member,
                    mandateId = mandateId,
                    tierId = tier,
                    createdBy = treasurer,
                    submittedAt = submittedAt,
                )

            val now = LocalDateTime(2026, 1, 10, 9, 0) // well inside the window
            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { now })
            runBlocking { poller.tick() }

            transaction {
                SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single()[SepaDebitItemTable.status]
            } shouldBe SepaDebitItemStatus.PENDING
        }

        test("Phase C: an item that is already RETURNED (return recorded before this tick) is never touched") {
            val treasurer = createMember(email = "poller-c3-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-c3-member-${Uuid.random()}@example.org")
            val tier = createTier()
            val mandateId = createMandate(memberId = member, createdBy = treasurer)
            val submittedAt = LocalDateTime(2026, 1, 1, 9, 0)
            val (_, itemId) =
                createSubmittedBatchWithItem(
                    memberId = member,
                    mandateId = mandateId,
                    tierId = tier,
                    createdBy = treasurer,
                    submittedAt = submittedAt,
                    itemStatus = SepaDebitItemStatus.RETURNED,
                )
            transaction {
                SepaReturnTable.insertIgnore {
                    it[id] = Uuid.random()
                    it[debitItemId] = itemId
                    it[returnedAt] = LocalDate(2026, 1, 5)
                    it[reasonCode] = SepaReturnReason.MD01
                    it[reasonText] = null
                    it[returnFee] = null
                    it[recordedBy] = treasurer
                    it[recordedAt] = LocalDateTime(2026, 1, 5, 9, 0)
                }
            }

            val now = LocalDateTime(2026, 2, 27, 9, 0) // past the return window
            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { now })
            runBlocking { poller.tick() }

            transaction {
                SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single()[SepaDebitItemTable.status]
            } shouldBe SepaDebitItemStatus.RETURNED
        }

        test("tick() is a complete no-op when organization_settings.sepa_debit_enabled is false") {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = false
                }
            }
            val treasurer = createMember(email = "poller-gate-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-gate-member-${Uuid.random()}@example.org", status = MemberStatus.WITHDRAWN)
            val mandateId = createMandate(memberId = member, createdBy = treasurer, grantedAt = LocalDateTime(2018, 1, 1, 0, 0))

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { LocalDateTime(2026, 8, 19, 12, 0) })
            runBlocking { poller.tick() }

            // Neither the 36-month expiry NOR the WITHDRAWN auto-revoke fired -- the org-wide gate
            // short-circuits tick() before any phase runs.
            transaction {
                SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status]
            } shouldBe SepaMandateStatus.ACTIVE

            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = true
                }
            }
        }

        // ── C-1 (CRITICAL, Review Round 1): the actual race, reproduced with real thread concurrency ──

        test(
            "C-1: a concurrent recordReturn racing Phase C's UPDATE never resurrects the item to SETTLEABLE " +
                "(true multi-thread concurrency, not a simulated sequential call)",
        ) {
            val treasurer = createMember(email = "poller-c1race-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createMember(email = "poller-c1race-member-${Uuid.random()}@example.org")
            val tier = createTier()
            val mandateId = createMandate(memberId = member, createdBy = treasurer)
            val submittedAt = LocalDateTime(2026, 1, 1, 9, 0)
            val (_, itemId) =
                createSubmittedBatchWithItem(
                    memberId = member,
                    mandateId = mandateId,
                    tierId = tier,
                    createdBy = treasurer,
                    submittedAt = submittedAt,
                )
            val now = LocalDateTime(2026, 2, 27, 9, 0) // past the return window -- Phase C would act on this item

            val lockAcquired = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)
            val lockingDone = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()

            // Thread A: takes the SAME row lock recordReturn's own forUpdate() would take, holds it
            // open (blocking the poller's later UPDATE, which needs that same lock), THEN performs
            // recordReturn's actual effect (insert sepa_return + flip status to RETURNED) and commits
            // -- reproducing the exact interleaving C-1 describes: the item was still PENDING when
            // Phase C's OWN (unlocked) candidate SELECT ran, but is RETURNED by the time Phase C's
            // UPDATE actually executes.
            val lockingThread =
                Thread {
                    try {
                        transaction {
                            SepaDebitItemTable
                                .selectAll()
                                .where { SepaDebitItemTable.id eq itemId }
                                .forUpdate()
                                .single()
                            lockAcquired.countDown()
                            check(releaseLock.await(20, TimeUnit.SECONDS)) { "releaseLock latch not signaled in time" }
                            SepaReturnTable.insertIgnore {
                                it[id] = Uuid.random()
                                it[debitItemId] = itemId
                                it[returnedAt] = now.date
                                it[reasonCode] = SepaReturnReason.MD01
                                it[reasonText] = null
                                it[returnFee] = null
                                it[recordedBy] = treasurer
                                it[recordedAt] = now
                            }
                            SepaDebitItemTable.update({ SepaDebitItemTable.id eq itemId }) { it[status] = SepaDebitItemStatus.RETURNED }
                        }
                    } catch (t: Throwable) {
                        synchronized(failures) { failures += t }
                    } finally {
                        lockingDone.countDown()
                    }
                }
            lockingThread.start()
            check(lockAcquired.await(20, TimeUnit.SECONDS)) { "row lock not acquired in time" }

            val poller = SepaBatchPoller(sepaConfig = SepaConfig.load { null }, clock = { now })
            val pollerThread =
                Thread {
                    try {
                        runBlocking { poller.tick() }
                    } catch (t: Throwable) {
                        synchronized(failures) { failures += t }
                    }
                }
            pollerThread.start()
            // Give the poller time to run its own (non-blocking) candidate SELECTs and reach the
            // UPDATE, where it must block on Thread A's row lock. A fixed sleep is the pragmatic
            // choice here (same idiom this codebase already uses for timing-sensitive tests, e.g.
            // LoginRateLimiterTest) -- there is no production-code hook to signal "about to UPDATE"
            // without instrumenting SepaBatchPoller itself for testability alone.
            Thread.sleep(500)
            releaseLock.countDown()

            check(lockingDone.await(20, TimeUnit.SECONDS)) { "locking thread did not finish in time" }
            pollerThread.join(20_000)
            if (failures.isNotEmpty()) throw failures.first()

            val finalStatus =
                transaction { SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single()[SepaDebitItemTable.status] }
            finalStatus shouldBe SepaDebitItemStatus.RETURNED
        }
    })
