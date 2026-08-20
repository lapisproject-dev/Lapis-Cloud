package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SepaReturnTable
import network.lapis.cloud.server.payment.sepa.SepaConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Security Round 1 (2026-08-19, SHOULD-3) regression coverage for
 * [sepaDisclaimerIsCurrentlyAcknowledged] (unchanged, see original class KDoc below), PLUS -- Review
 * Round 1 (2026-08-19, M-1) -- the first real service-level test coverage of [SepaService] itself.
 * Before this round, this file tested only the standalone helper function above; NONE of the 1536
 * new lines in `SepaService.kt` had any executing test. Own freshly created fixtures, throwaway
 * routes calling [SepaService] directly (no wire-format reverse-engineering), same house style
 * [ContributionPaymentRpcTest] establishes -- [resolveCurrentMember] resolves off the trusted
 * `X-Member-Id` test header (see [network.lapis.cloud.server.security.AuthTestMode] KDoc).
 *
 * Direct [SepaComplianceAcknowledgmentTable] inserts for setup, same "own freshly created fixtures"
 * house style -- no HTTP/RPC layer involved for the ORIGINAL two tests since
 * [sepaDisclaimerIsCurrentlyAcknowledged] is a plain function, not an [ISepaService] method.
 */
class SepaServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdBatchIds = mutableListOf<Uuid>()
        val createdMandateIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        // 32 random bytes, base64-encoded -- exactly SecretBox.KEY_SIZE_BYTES, same
        // "own throwaway test key, never LAPIS_SECRET_ENCRYPTION_KEY itself" idiom
        // ConferenceStreamingConfigTest establishes.
        val testKeyBase64 =
            Base64.getEncoder().encodeToString(
                ByteArray(network.lapis.cloud.server.crypto.SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes),
            )
        val testSepaConfig = SepaConfig.load { key -> if (key == "LAPIS_SECRET_ENCRYPTION_KEY") testKeyBase64 else null }

        beforeSpec { DatabaseConfig.connect() }

        // sepaDisclaimerIsCurrentlyAcknowledged() deliberately has no member scoping -- it reads the
        // single, org-wide latest acknowledgment row, mirroring the org-wide singleton gate it backs
        // (same shape as OrganizationSettings). [PaymentComplianceGateTest] writes to this SAME table
        // (and cleans up after itself via its own afterTest, same "delete all rows" idiom) -- clearing
        // here too, before each test, keeps this Spec's assertions deterministic regardless of
        // cross-Spec test execution order.
        beforeTest {
            transaction {
                SepaComplianceAcknowledgmentTable.deleteWhere {
                    SepaComplianceAcknowledgmentTable.id eq SepaComplianceAcknowledgmentTable.id
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = false
                    it[sepaCreditorId] = null
                    it[sepaCreditorName] = null
                    it[paymentBankAccountId] = null
                    it[paymentFeeAccountId] = null
                    it[contributionIncomeAccountId] = null
                }
            }
        }

        afterSpec {
            transaction {
                SepaComplianceAcknowledgmentTable.deleteWhere {
                    SepaComplianceAcknowledgmentTable.id eq SepaComplianceAcknowledgmentTable.id
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdBatchIds.isNotEmpty()) {
                    val itemIds =
                        SepaDebitItemTable.selectAll().where { SepaDebitItemTable.batchId inList createdBatchIds }.map {
                            it[SepaDebitItemTable.id]
                        }
                    if (itemIds.isNotEmpty()) SepaReturnTable.deleteWhere { SepaReturnTable.debitItemId inList itemIds }
                    val journalEntryIds =
                        SepaDebitItemTable
                            .selectAll()
                            .where { SepaDebitItemTable.batchId inList createdBatchIds }
                            .mapNotNull { it[SepaDebitItemTable.journalEntryId] }
                    SepaDebitItemTable.deleteWhere { SepaDebitItemTable.batchId inList createdBatchIds }
                    SepaDebitBatchTable.deleteWhere { SepaDebitBatchTable.id inList createdBatchIds }
                    if (journalEntryIds.isNotEmpty()) {
                        PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                        JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                    }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdMandateIds.isNotEmpty()) {
                    SepaMandateTable.deleteWhere { SepaMandateTable.id inList createdMandateIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // Catch-all for journal entries NOT reachable via a sepa_debit_item.journalEntryId
                    // (e.g. a directly-inserted seed/fixture entry, like the M-4 test's balance seed)
                    // -- anything created_by one of this file's own fixture members.
                    val strayJournalEntryIds =
                        JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList createdMemberIds }.map {
                            it[JournalEntryTable.id]
                        }
                    if (strayJournalEntryIds.isNotEmpty()) {
                        PostingTable.deleteWhere { PostingTable.journalEntryId inList strayJournalEntryIds }
                        JournalEntryTable.deleteWhere { JournalEntryTable.id inList strayJournalEntryIds }
                    }
                    // generateBatchFile archives the pain.008 XML via archiveGeneratedBytes -- a
                    // document + document_version row, both referencing the acting treasurer. The
                    // SHARED "SEPA-Lastschriften" document_folder row is deliberately left in place
                    // (reused across runs/tests, not owned by any single fixture member).
                    val strayDocumentIds =
                        DocumentTable.selectAll().where { DocumentTable.createdBy inList createdMemberIds }.map { it[DocumentTable.id] }
                    if (strayDocumentIds.isNotEmpty()) {
                        DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList strayDocumentIds }
                        DocumentTable.deleteWhere { DocumentTable.id inList strayDocumentIds }
                    }
                    SepaMandateTable.deleteWhere { SepaMandateTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = false
                    it[sepaCreditorId] = null
                    it[sepaCreditorName] = null
                    it[paymentBankAccountId] = null
                    it[paymentFeeAccountId] = null
                    it[contributionIncomeAccountId] = null
                }
            }
        }

        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.ADMIN,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SepaService Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
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

        fun insertAcknowledgment(
            memberId: Uuid,
            version: String,
            acknowledgedAt: LocalDateTime,
        ) {
            transaction {
                SepaComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = memberId
                    it[SepaComplianceAcknowledgmentTable.acknowledgedAt] = acknowledgedAt
                    it[disclaimerVersion] = version
                    it[disclaimerSha256] = "0".repeat(64)
                }
            }
        }

        test("only acknowledgment on record matches the CURRENT SepaComplianceDisclaimer.VERSION -> currently acknowledged") {
            val member = createTestMember("sepa-disclaimer-current-${Uuid.random()}@example.org")
            insertAcknowledgment(
                memberId = member,
                version = SepaComplianceDisclaimer.VERSION,
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 0),
            )

            sepaDisclaimerIsCurrentlyAcknowledged() shouldBe true
        }

        test("latest acknowledgment is NOT the current version -> NOT currently acknowledged (Security Round 1, SHOULD-3)") {
            val member = createTestMember("sepa-disclaimer-stale-${Uuid.random()}@example.org")
            // Insert an up-to-date ack FIRST, then a strictly-more-recent row whose version does NOT
            // match SepaComplianceDisclaimer.VERSION -- simulates an ADMIN having acknowledged an
            // older/different wording than what is CURRENTLY in force. The MOST RECENT row must be
            // the one this function consults, proving it looks at the latest, not "any".
            insertAcknowledgment(
                memberId = member,
                version = SepaComplianceDisclaimer.VERSION,
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 0),
            )
            insertAcknowledgment(
                memberId = member,
                version = "2020-01-01.v0-not-the-current-version",
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 1),
            )

            sepaDisclaimerIsCurrentlyAcknowledged() shouldBe false
        }

        // ════════════════════════════════════════════════════════════════
        // Review Round 1 (2026-08-19, M-1) -- real SepaService/RPC-level coverage below
        // ════════════════════════════════════════════════════════════════

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Sepa-Fixture Tarif ${id.toString().take(6)}"
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

        fun createOpenContribution(
            memberId: Uuid,
            tierId: Uuid,
            amountDue: BigDecimal = BigDecimal("50.00"),
            dueDate: LocalDate = LocalDate(2020, 1, 15),
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2020, 1, 1)
                    it[periodEnd] = LocalDate(2020, 12, 31)
                    it[ContributionTable.amountDue] = amountDue
                    it[status] = ContributionStatus.OPEN
                    it[ContributionTable.createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[ContributionTable.dueDate] = dueDate
                }
            }
            createdContributionIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
            isCashRegister: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Sepa-Fixture Konto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[LedgerAccountTable.isCashRegister] = isCashRegister
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        /** Enables the full SEPA write path -- feature flag, current disclaimer, creditor config. */
        fun enableSepaForOrg(
            ackByMemberId: Uuid,
            paymentBankAccountId: Uuid? = null,
            paymentFeeAccountId: Uuid? = null,
            contributionIncomeAccountId: Uuid? = null,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = true
                    it[sepaCreditorId] = "DE98ZZZ09999999999"
                    it[sepaCreditorName] = "Sepa-Fixture Verein"
                    it[bankIban] = "DE89370400440532013000"
                    it[bankBic] = "COBADEFFXXX"
                    it[sepaPrenotificationDays] = 14
                    it[OrganizationSettingsTable.paymentBankAccountId] = paymentBankAccountId
                    it[OrganizationSettingsTable.paymentFeeAccountId] = paymentFeeAccountId
                    it[OrganizationSettingsTable.contributionIncomeAccountId] = contributionIncomeAccountId
                }
                SepaComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = ackByMemberId
                    it[acknowledgedAt] = DbClock.nowLocalDateTime()
                    it[disclaimerVersion] = SepaComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = SepaComplianceDisclaimer.SHA256
                }
            }
        }

        fun grantMandateRow(
            memberId: Uuid,
            createdBy: Uuid,
            status: SepaMandateStatus = SepaMandateStatus.ACTIVE,
            // Default is "now" (real wall clock), not a fixed past date -- createDebitBatch's M-5
            // synchronous expiry re-check (real DbClock, not test-injectable) would otherwise treat a
            // fixed old default as expired once enough real time has passed. Tests that specifically
            // need an EXPIRED mandate (e.g. the M-5 test itself) pass an explicit, deliberately old
            // grantedAt.
            grantedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            lastUsedAt: LocalDate? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                SepaMandateTable.insert {
                    it[SepaMandateTable.id] = id
                    it[SepaMandateTable.memberId] = memberId
                    it[mandateReference] = "LC-SVC-${id.toString().take(8)}"
                    it[debtorName] = "Sepa-Fixture Konto"
                    it[debtorIbanCiphertext] = "unused-ciphertext-$id"
                    it[debtorIbanSetAt] = grantedAt
                    it[debtorIbanLast4] = "1234"
                    it[debtorBic] = null
                    it[signatureDate] = grantedAt.date
                    it[sequenceType] = network.lapis.cloud.shared.domain.SepaSequenceType.FRST
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

        /** Attaches a logback [ListAppender] to the ROOT logger for the duration of [block], returns the captured events. */
        fun captureRootLogEvents(block: () -> Unit): List<ILoggingEvent> {
            val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            return try {
                block()
                appender.list.toList()
            } finally {
                root.detachAppender(appender)
            }
        }

        // ── Mandate lifecycle ────────────────────────────────────────────

        test("grantMandate: self-grant succeeds, createdBySelf=true") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val member = createTestMember("sepa-grant-self-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = member)

                val today = DbClock.nowLocalDateTime().date
                val response =
                    client.post(
                        "/test/sepa/grant?debtorName=Erika+Mustermann&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val fields = response.bodyAsText().split("|")
                fields[1] shouldBe "ACTIVE"
                fields[2] shouldBe "true" // createdBySelf
                createdMandateIds += Uuid.parse(fields[0])
            }
        }

        test("grantMandate: TREASURER on behalf of another member succeeds, createdBySelf=false; a plain MEMBER cannot") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-grant-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val otherMember = createTestMember("sepa-grant-other-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val bystander = createTestMember("sepa-grant-bystander-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = treasurer)
                val today = DbClock.nowLocalDateTime().date

                val treasurerResponse =
                    client.post(
                        "/test/sepa/grant?memberId=$otherMember&debtorName=Fremdkonto&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", treasurer.toString()) }
                treasurerResponse.status shouldBe HttpStatusCode.OK
                val fields = treasurerResponse.bodyAsText().split("|")
                fields[2] shouldBe "false" // createdBySelf
                createdMandateIds += Uuid.parse(fields[0])

                // A plain MEMBER attempting to grant a mandate on behalf of a DIFFERENT member is
                // forbidden (SEPA_TREASURY_ROLES == TREASURER/ADMIN only).
                val bystanderAttempt =
                    client.post(
                        "/test/sepa/grant?memberId=$otherMember&debtorName=Fremdkonto2&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", bystander.toString()) }
                bystanderAttempt.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("revokeMandate: member-self and ADMIN succeed; a DIFFERENT non-treasury member gets NotFoundException (no existence oracle)") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val admin = createTestMember("sepa-revoke-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val ownerA = createTestMember("sepa-revoke-ownerA-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val ownerB = createTestMember("sepa-revoke-ownerB-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val bystander = createTestMember("sepa-revoke-bystander-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = admin)
                val mandateA = grantMandateRow(memberId = ownerA, createdBy = ownerA)
                val mandateB = grantMandateRow(memberId = ownerB, createdBy = ownerB)

                // Self-revoke.
                val selfRevoke =
                    client.post("/test/sepa/revoke?mandateId=$mandateA") { header("X-Member-Id", ownerA.toString()) }
                selfRevoke.status shouldBe HttpStatusCode.OK
                selfRevoke.bodyAsText().split("|")[1] shouldBe "REVOKED"

                // A DIFFERENT, non-treasury member cannot revoke someone else's mandate -- and gets
                // NotFoundException (not Forbidden), same "no existence oracle" discipline as every
                // other foreign-resource lookup in SepaService.
                val bystanderAttempt =
                    client.post("/test/sepa/revoke?mandateId=$mandateB") { header("X-Member-Id", bystander.toString()) }
                bystanderAttempt.status shouldBe HttpStatusCode.NotFound

                // ADMIN can revoke on behalf.
                val adminRevoke =
                    client.post("/test/sepa/revoke?mandateId=$mandateB") { header("X-Member-Id", admin.toString()) }
                adminRevoke.status shouldBe HttpStatusCode.OK
                adminRevoke.bodyAsText().split("|")[1] shouldBe "REVOKED"
            }
        }

        test(
            "grantMandate: concurrent-grant guard actually serializes two racing self-grant calls for the SAME member (only one succeeds)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val member = createTestMember("sepa-grant-race-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = member)
                val today = DbClock.nowLocalDateTime().date

                val startLatch = CountDownLatch(2)
                val doneLatch = CountDownLatch(2)
                val results = java.util.Collections.synchronizedList(mutableListOf<HttpStatusCode>())
                val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())

                fun grantThread(client: HttpClient) =
                    Thread {
                        try {
                            startLatch.countDown()
                            startLatch.await(20, TimeUnit.SECONDS)
                            kotlinx.coroutines.runBlocking {
                                val response =
                                    client.post(
                                        "/test/sepa/grant?debtorName=Race+Konto&debtorIban=DE89370400440532013000&signatureDate=$today",
                                    ) { header("X-Member-Id", member.toString()) }
                                results += response.status
                                bodies += response.bodyAsText()
                            }
                        } finally {
                            doneLatch.countDown()
                        }
                    }

                val t1 = grantThread(client)
                val t2 = grantThread(client)
                t1.start()
                t2.start()
                check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent grant calls did not complete in time" }

                results.count { it == HttpStatusCode.OK } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe 1
                bodies.filterIndexed { index, _ -> results[index] == HttpStatusCode.OK }.forEach {
                    createdMandateIds += Uuid.parse(it.split("|")[0])
                }

                // DB-level confirmation: exactly one ACTIVE mandate for this member.
                val activeCount =
                    transaction {
                        SepaMandateTable
                            .selectAll()
                            .where { (SepaMandateTable.memberId eq member) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                            .count()
                    }
                activeCount shouldBe 1L
            }
        }

        // ── Batch lifecycle end to end (also the E2E journey: grant -> create -> notify -> generate ->
        //    submit -> settle -> real accounting entry exists) ──────────────────────────────────────

        test(
            "batch lifecycle end-to-end: createDebitBatch -> notifyBatch -> generateBatchFile -> " +
                "markBatchSubmitted -> settleBatch books a real journal entry; cancelBatch on an " +
                "invalid (already-terminal) state is rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-e2e-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-e2e-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SE1${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SE2${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                // Granted via the REAL grantMandate RPC (not the raw-table grantMandateRow helper
                // used elsewhere in this file) -- generateBatchFile below actually SecretBox-decrypts
                // debtor_iban_ciphertext to embed the real IBAN in the pain.008 file, which a fake
                // placeholder ciphertext (grantMandateRow's own shortcut, fine for tests that never
                // reach generateBatchFile) cannot satisfy.
                val today = DbClock.nowLocalDateTime().date
                val grantResponse =
                    client.post(
                        "/test/sepa/grant?debtorName=E2E+Konto&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                grantResponse.status shouldBe HttpStatusCode.OK
                val mandateId = Uuid.parse(grantResponse.bodyAsText().split("|")[0])
                createdMandateIds += mandateId
                val contributionId = createOpenContribution(memberId = member, tierId = tier)

                val collectionDate = today.plus(30, DateTimeUnit.DAY)

                // membershipTierId scopes this call to ONLY this test's own contribution -- without
                // it, previewDebitBatch/createDebitBatch has no member/tier scoping at all and would
                // also pick up OPEN contributions left behind by earlier tests in this same spec run
                // (afterSpec, not afterTest, cleans up -- same "own freshly created fixtures" house
                // style as every other RPC test file, but createDebitBatch's OWN candidate query is
                // organization-wide by design).
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                createResponse.status shouldBe HttpStatusCode.OK
                val createFields = createResponse.bodyAsText().split("|")
                val batchId = createFields[0]
                createFields[1] shouldBe "DRAFT"
                createdBatchIds += Uuid.parse(batchId)

                // Pre-notification hard gate: generateBatchFile must reject BEFORE notifyBatch runs at all.
                val prematureGenerate =
                    client.post("/test/sepa/generate?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                prematureGenerate.status shouldBe HttpStatusCode.Conflict

                val notifyResponse = client.post("/test/sepa/notify?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                notifyResponse.status shouldBe HttpStatusCode.OK
                notifyResponse.bodyAsText().split("|")[0] shouldBe "NOTIFIED"

                // Immediately after notifyBatch, the notice period has NOT elapsed yet (14 days
                // required, requested collection is 30 days out but notifiedAt is "now") -- actually
                // 30 days out clears a 14-day notice comfortably, so generation should succeed here.
                val generateResponse = client.post("/test/sepa/generate?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                generateResponse.status shouldBe HttpStatusCode.OK
                generateResponse.bodyAsText().split("|")[0] shouldBe "GENERATED"

                val submitResponse = client.post("/test/sepa/submit?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                submitResponse.status shouldBe HttpStatusCode.OK
                submitResponse.bodyAsText().split("|")[0] shouldBe "SUBMITTED"

                // cancelBatch on a SUBMITTED batch is rejected -- only DRAFT/NOTIFIED/GENERATED are cancellable.
                val cancelAttempt =
                    client.post("/test/sepa/cancel?batchId=$batchId&reason=zu+spaet") { header("X-Member-Id", treasurer.toString()) }
                cancelAttempt.status shouldBe HttpStatusCode.Conflict

                // Force the item straight to SETTLEABLE (bypassing the 56-day wait -- direct table
                // update, same "own freshly created fixtures" discipline as the rest of this suite)
                // so settleBatch has something to act on.
                transaction {
                    SepaDebitItemTable.update({ SepaDebitItemTable.batchId eq Uuid.parse(batchId) }) {
                        it[status] = SepaDebitItemStatus.SETTLEABLE
                        it[settleableAt] = today
                    }
                }

                val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }
                val settleResponse = client.post("/test/sepa/settle?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                settleResponse.status shouldBe HttpStatusCode.OK
                val settleFields = settleResponse.bodyAsText().split("|")
                settleFields[0] shouldBe "SETTLED" // batch status
                settleFields[1] shouldBe "" // failedItemIds empty

                val journalEntryCountAfter = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfter shouldBe journalEntryCountBefore + 1L

                val contributionStatus =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                contributionStatus shouldBe ContributionStatus.PAID

                // The real accounting entry exists and is posted for the expected amount.
                val postedAmount =
                    transaction {
                        (PostingTable innerJoin JournalEntryTable)
                            .selectAll()
                            .where { (PostingTable.ledgerAccountId eq bankAccountId) and (JournalEntryTable.createdBy eq treasurer) }
                            .single()[PostingTable.amount]
                    }
                postedAmount.compareTo(BigDecimal("50.00")) shouldBe 0
            }
        }

        test("settleBatch: idempotent -- calling twice books only ONE journal entry, second call is a no-op-ish re-read") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-settle-idem-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-settle-idem-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SE3${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SE4${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                val mandateId = grantMandateRow(memberId = member, createdBy = member)
                val contributionId = createOpenContribution(memberId = member, tierId = tier)
                val batchId = Uuid.random()
                val itemId = Uuid.random()
                val today = DbClock.nowLocalDateTime().date
                transaction {
                    SepaDebitBatchTable.insert {
                        it[id] = batchId
                        it[messageId] = "LC-DD-IDEM-${batchId.toString().take(8)}"
                        it[paymentInfoId] = "LC-DD-IDEM-${batchId.toString().take(8)}-P1"
                        it[requestedCollectionDate] = today
                        it[sequenceType] = network.lapis.cloud.shared.domain.SepaSequenceType.RCUR
                        it[status] = SepaDebitBatchStatus.SUBMITTED
                        it[itemCount] = 1
                        it[totalAmount] = BigDecimal("50.00")
                        it[createdBy] = treasurer
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[notifiedAt] = DbClock.nowLocalDateTime()
                        it[requiredNoticeDays] = 14
                        it[generatedAt] = DbClock.nowLocalDateTime()
                        it[generatedDocumentId] = null
                        it[prenotificationDocumentId] = null
                        it[submittedAt] = DbClock.nowLocalDateTime()
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
                        it[status] = SepaDebitItemStatus.SETTLEABLE
                        it[settleableAt] = today
                        it[journalEntryId] = null
                    }
                }
                createdBatchIds += batchId

                val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }
                val first = client.post("/test/sepa/settle?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                first.status shouldBe HttpStatusCode.OK
                val journalEntryCountAfterFirst = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfterFirst shouldBe journalEntryCountBefore + 1L

                // Second call: the batch is already SETTLED, no SETTLEABLE items remain -- rejected
                // with ConflictException, not a silent double-post.
                val second = client.post("/test/sepa/settle?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                second.status shouldBe HttpStatusCode.Conflict
                val journalEntryCountAfterSecond = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfterSecond shouldBe journalEntryCountAfterFirst
            }
        }

        test("settleBatch: role-gated to TREASURER/ADMIN -- a plain MEMBER and a BOARD member are both rejected") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val admin = createTestMember("sepa-settle-role-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val plainMember = createTestMember("sepa-settle-role-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val board = createTestMember("sepa-settle-role-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                enableSepaForOrg(ackByMemberId = admin)
                val fakeBatchId = Uuid.random()

                val memberAttempt =
                    client.post("/test/sepa/settle?batchId=$fakeBatchId") { header("X-Member-Id", plainMember.toString()) }
                memberAttempt.status shouldBe HttpStatusCode.Forbidden

                val boardAttempt = client.post("/test/sepa/settle?batchId=$fakeBatchId") { header("X-Member-Id", board.toString()) }
                boardAttempt.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "settleBatch: a per-item posting failure is logged, surfaced via failedItemIds, and does NOT " +
                "stop the OTHER item in the same batch from settling successfully (M-4)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-settle-fail-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val memberA = createTestMember("sepa-settle-fail-a-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val memberB = createTestMember("sepa-settle-fail-b-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SE5${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                // Deliberately mis-mapped: contributionIncomeAccountId points at a cash-register
                // account (bypasses the app-level "cash register only on a genuine till" validation
                // via direct table insert, same technique CashRegisterGuard's own KDoc anticipates
                // for a misconfigured mapping) so ContributionPostingBridge's GoBD guard rejects the
                // CREDIT posting once the pre-seeded balance is exhausted.
                val incomeAccountId =
                    createLedgerAccount("SE6${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME, isCashRegister = true)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                val mandateA = grantMandateRow(memberId = memberA, createdBy = memberA)
                val mandateB = grantMandateRow(memberId = memberB, createdBy = memberB)
                val contributionA = createOpenContribution(memberId = memberA, tierId = tier, amountDue = BigDecimal("50.00"))
                val contributionB = createOpenContribution(memberId = memberB, tierId = tier, amountDue = BigDecimal("50.00"))

                // Pre-seed the "cash balance" to exactly ONE item's amount (50.00) via a directly
                // inserted POSTED journal entry crediting the DEBIT (normal ASSET) side of the
                // cash-flagged income account -- CashRegisterGuard's balance calc always treats the
                // normal side as DEBIT (see its own KDoc "isCashRegister implies ASSET"). Whichever
                // item settles FIRST drains this to exactly 0 (allowed); whichever settles SECOND
                // drives it negative (rejected) -- deterministic regardless of iteration order.
                val seedEntryId = Uuid.random()
                transaction {
                    JournalEntryTable.insert {
                        it[id] = seedEntryId
                        it[entryDate] = LocalDate(2020, 1, 1)
                        it[description] = "Seed balance for M-4 test"
                        it[voucherReference] = "SEED-M4"
                        it[createdBy] = treasurer
                        it[status] = network.lapis.cloud.shared.domain.JournalEntryStatus.POSTED
                        it[postedAt] = LocalDateTime(2020, 1, 1, 0, 0)
                        it[createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
                        it[donorMemberId] = null
                        it[externalDonorId] = null
                        it[donorCategory] = null
                    }
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[journalEntryId] = seedEntryId
                        it[ledgerAccountId] = incomeAccountId
                        it[side] = PostingSide.DEBIT
                        it[amount] = BigDecimal("50.00")
                        it[sphere] = network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                        it[costCenterId] = null
                    }
                }

                val batchId = Uuid.random()
                val itemAId = Uuid.random()
                val itemBId = Uuid.random()
                val today = DbClock.nowLocalDateTime().date
                transaction {
                    SepaDebitBatchTable.insert {
                        it[id] = batchId
                        it[messageId] = "LC-DD-M4-${batchId.toString().take(8)}"
                        it[paymentInfoId] = "LC-DD-M4-${batchId.toString().take(8)}-P1"
                        it[requestedCollectionDate] = today
                        it[sequenceType] = network.lapis.cloud.shared.domain.SepaSequenceType.RCUR
                        it[status] = SepaDebitBatchStatus.SUBMITTED
                        it[itemCount] = 2
                        it[totalAmount] = BigDecimal("100.00")
                        it[createdBy] = treasurer
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[notifiedAt] = DbClock.nowLocalDateTime()
                        it[requiredNoticeDays] = 14
                        it[generatedAt] = DbClock.nowLocalDateTime()
                        it[generatedDocumentId] = null
                        it[prenotificationDocumentId] = null
                        it[submittedAt] = DbClock.nowLocalDateTime()
                        it[submittedNote] = null
                        it[settledAt] = null
                        it[cancelledAt] = null
                        it[cancellationReason] = null
                    }
                    listOf(itemAId to (contributionA to mandateA), itemBId to (contributionB to mandateB)).forEach { (itemId, pair) ->
                        val (contributionId, mandateId) = pair
                        SepaDebitItemTable.insert {
                            it[id] = itemId
                            it[SepaDebitItemTable.batchId] = batchId
                            it[SepaDebitItemTable.contributionId] = contributionId
                            it[SepaDebitItemTable.mandateId] = mandateId
                            it[endToEndId] = contributionId.toString().replace("-", "").uppercase()
                            it[amount] = BigDecimal("50.00")
                            it[remittanceInformation] = "Testbeitrag"
                            it[status] = SepaDebitItemStatus.SETTLEABLE
                            it[settleableAt] = today
                            it[journalEntryId] = null
                        }
                    }
                }
                createdBatchIds += batchId

                val logEvents =
                    captureRootLogEvents {
                        kotlinx.coroutines.runBlocking {
                            val response =
                                client.post("/test/sepa/settle?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                            response.status shouldBe HttpStatusCode.OK
                            val fields = response.bodyAsText().split("|")
                            fields[1].split(",").filter { it.isNotBlank() }.size shouldBe 1 // exactly one failedItemId
                        }
                    }

                // (a) the failure is logged.
                logEvents.any { it.formattedMessage.contains("could not be posted") } shouldBe true

                // (c) the OTHER item settled successfully -- exactly one SETTLED, one still SETTLEABLE.
                val statuses =
                    transaction {
                        SepaDebitItemTable.selectAll().where { SepaDebitItemTable.batchId eq batchId }.map { it[SepaDebitItemTable.status] }
                    }
                statuses.count { it == SepaDebitItemStatus.SETTLED } shouldBe 1
                statuses.count { it == SepaDebitItemStatus.SETTLEABLE } shouldBe 1
            }
        }

        test(
            "SepaBatchPoller.kt source-scan: never CALLS ContributionPostingBridge/AccountingService/CashRegisterGuard/" +
                "JournalEntryTable.insert (the class KDoc itself names all four -- explaining the invariant -- so this checks " +
                "for actual qualified invocations, not bare mentions of the names)",
        ) {
            val mainSourceDir =
                File("src/main/kotlin").let { relative -> if (relative.exists()) relative else File("lapis-server/src/main/kotlin") }
            val pollerFile = File(mainSourceDir, "network/lapis/cloud/server/payment/sepa/SepaBatchPoller.kt")
            require(pollerFile.exists()) { "SepaBatchPoller.kt not found at ${pollerFile.absolutePath}" }
            // The class-level KDoc itself legitimately names all four (backtick-quoted, dotted
            // method-reference style, e.g. "ContributionPostingBridge.postContributionPayment
            // requires...") to EXPLAIN the invariant -- so the scan deliberately starts at the class
            // declaration, past that KDoc block, and checks only the actual class body for real calls.
            val content = pollerFile.readText()
            val classBodyStart = content.indexOf("class SepaBatchPoller")
            require(classBodyStart > 0) { "class SepaBatchPoller declaration not found" }
            val classBody = content.substring(classBodyStart)
            classBody.contains("ContributionPostingBridge.") shouldBe false
            classBody.contains("AccountingService(") shouldBe false
            classBody.contains("AccountingService.") shouldBe false
            classBody.contains("CashRegisterGuard.") shouldBe false
            classBody.contains("JournalEntryTable.insert") shouldBe false
        }

        test("behavioral counterpart: SepaBatchPoller.tick() creates zero JournalEntry rows even when items become SETTLEABLE") {
            val treasurer = createTestMember("sepa-poller-no-post-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
            val member = createTestMember("sepa-poller-no-post-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
            val tier = createTier()
            enableSepaForOrg(ackByMemberId = treasurer)
            val mandateId = grantMandateRow(memberId = member, createdBy = member)
            val contributionId = createOpenContribution(memberId = member, tierId = tier)
            val batchId = Uuid.random()
            val itemId = Uuid.random()
            val submittedAt = LocalDateTime(2026, 1, 1, 9, 0)
            transaction {
                SepaDebitBatchTable.insert {
                    it[id] = batchId
                    it[messageId] = "LC-DD-NOPOST-${batchId.toString().take(8)}"
                    it[paymentInfoId] = "LC-DD-NOPOST-${batchId.toString().take(8)}-P1"
                    it[requestedCollectionDate] = submittedAt.date
                    it[sequenceType] = network.lapis.cloud.shared.domain.SepaSequenceType.RCUR
                    it[status] = SepaDebitBatchStatus.SUBMITTED
                    it[itemCount] = 1
                    it[totalAmount] = BigDecimal("50.00")
                    it[createdBy] = treasurer
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
                    it[status] = SepaDebitItemStatus.PENDING
                    it[settleableAt] = null
                    it[journalEntryId] = null
                }
            }
            createdBatchIds += batchId

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }
            val poller =
                network.lapis.cloud.server.payment.sepa.SepaBatchPoller(
                    sepaConfig = testSepaConfig,
                    clock = { LocalDateTime(2026, 2, 27, 9, 0) }, // past the 56-day return window
                )
            kotlinx.coroutines.runBlocking { poller.tick() }

            transaction {
                SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemId }.single()[SepaDebitItemTable.status]
            } shouldBe SepaDebitItemStatus.SETTLEABLE
            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
        }

        // ── recordReturn ─────────────────────────────────────────────────

        test("recordReturn: idempotent -- a second call for the same item gets ConflictException, not a silent no-op") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-return-idem-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-return-idem-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)
                val mandateId = grantMandateRow(memberId = member, createdBy = member)
                val contributionId = createOpenContribution(memberId = member, tierId = tier)
                val (batchId, itemId) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = member,
                        mandateId = mandateId,
                        contributionId = contributionId,
                    )
                createdBatchIds += batchId

                val today = DbClock.nowLocalDateTime().date
                val first =
                    client.post("/test/sepa/return?debitItemId=$itemId&returnedAt=$today&reasonCode=AC01") {
                        header("X-Member-Id", treasurer.toString())
                    }
                first.status shouldBe HttpStatusCode.OK

                val second =
                    client.post("/test/sepa/return?debitItemId=$itemId&returnedAt=$today&reasonCode=AC01") {
                        header("X-Member-Id", treasurer.toString())
                    }
                second.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "recordReturn: MD01 forces mandate revocation; AC01 does NOT belong to that specific rule but still excludes the mandate (M-6)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-return-md01-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val memberMd01 = createTestMember("sepa-return-md01-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val memberAc01 = createTestMember("sepa-return-ac01-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)
                val mandateMd01 = grantMandateRow(memberId = memberMd01, createdBy = memberMd01)
                val mandateAc01 = grantMandateRow(memberId = memberAc01, createdBy = memberAc01)
                val contributionMd01 = createOpenContribution(memberId = memberMd01, tierId = tier)
                val contributionAc01 = createOpenContribution(memberId = memberAc01, tierId = tier)
                val (batchMd01, itemMd01) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = memberMd01,
                        mandateId = mandateMd01,
                        contributionId = contributionMd01,
                    )
                val (batchAc01, itemAc01) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = memberAc01,
                        mandateId = mandateAc01,
                        contributionId = contributionAc01,
                    )
                createdBatchIds += batchMd01
                createdBatchIds += batchAc01

                val today = DbClock.nowLocalDateTime().date
                val md01Response =
                    client.post("/test/sepa/return?debitItemId=$itemMd01&returnedAt=$today&reasonCode=MD01") {
                        header("X-Member-Id", treasurer.toString())
                    }
                md01Response.status shouldBe HttpStatusCode.OK
                md01Response.bodyAsText().split("|")[1] shouldBe "true" // mandateRevoked

                val ac01Response =
                    client.post("/test/sepa/return?debitItemId=$itemAc01&returnedAt=$today&reasonCode=AC01") {
                        header("X-Member-Id", treasurer.toString())
                    }
                ac01Response.status shouldBe HttpStatusCode.OK
                // M-6 (Review Round 1): recordReturn now excludes the mandate from future automatic
                // selection for EVERY return reason, not only MD01/MD06/MD07 -- this DELIBERATELY
                // supersedes the pre-fix behavior (AC01 leaving the mandate untouched), which was the
                // exact bug M-6 fixes (a returned account re-entering the next debit run indefinitely).
                // mandateRevoked reflects the mandate's actual resulting status, true for both classes.
                ac01Response.bodyAsText().split("|")[1] shouldBe "true"

                val md01Status =
                    transaction {
                        SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateMd01 }.single()[SepaMandateTable.status]
                    }
                md01Status shouldBe SepaMandateStatus.REVOKED
                val ac01Status =
                    transaction {
                        SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateAc01 }.single()[SepaMandateTable.status]
                    }
                ac01Status shouldBe SepaMandateStatus.REVOKED

                // Review Round 2 (2026-08-20, M-6 consistency fix): this is a SYSTEM-driven
                // auto-revocation triggered by the return's reason code, not a human decision --
                // revokedBy must be null even though the TREASURER is the one who called
                // recordReturn (they recorded the RETURN, not a manual revocation). Same convention
                // SepaBatchPoller.runPhaseB's own auto-revocation already uses, see that Spec's
                // "system actor, not a human" assertion.
                val md01RevokedBy =
                    transaction {
                        SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateMd01 }.single()[SepaMandateTable.revokedBy]
                    }
                md01RevokedBy shouldBe null // system actor, not a human
                val ac01RevokedBy =
                    transaction {
                        SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateAc01 }.single()[SepaMandateTable.revokedBy]
                    }
                ac01RevokedBy shouldBe null // system actor, not a human

                // Both reason texts are distinguishable (structural-problem vs. mandate-problem framing).
                val md01Reason =
                    transaction {
                        SepaMandateTable
                            .selectAll()
                            .where {
                                SepaMandateTable.id eq mandateMd01
                            }.single()[SepaMandateTable.revocationReason]
                    }
                val ac01Reason =
                    transaction {
                        SepaMandateTable
                            .selectAll()
                            .where {
                                SepaMandateTable.id eq mandateAc01
                            }.single()[SepaMandateTable.revocationReason]
                    }
                (md01Reason?.contains("widerrufen") == true) shouldBe true
                (ac01Reason?.contains("ausgeschlossen") == true) shouldBe true
            }
        }

        test(
            "M-6: after an AC04 return AND after an MD01 return, previewDebitBatch no longer includes the " +
                "affected contribution -- both paths converge on 'excluded from future preview'",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-m6-preview-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val memberAc04 = createTestMember("sepa-m6-ac04-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val memberMd01 = createTestMember("sepa-m6-md01-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)
                val mandateAc04 = grantMandateRow(memberId = memberAc04, createdBy = memberAc04)
                val mandateMd01 = grantMandateRow(memberId = memberMd01, createdBy = memberMd01)
                val contributionAc04 = createOpenContribution(memberId = memberAc04, tierId = tier)
                val contributionMd01 = createOpenContribution(memberId = memberMd01, tierId = tier)
                val (batchAc04, itemAc04) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = memberAc04,
                        mandateId = mandateAc04,
                        contributionId = contributionAc04,
                    )
                val (batchMd01, itemMd01) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = memberMd01,
                        mandateId = mandateMd01,
                        contributionId = contributionMd01,
                    )
                createdBatchIds += batchAc04
                createdBatchIds += batchMd01

                val today = DbClock.nowLocalDateTime().date
                val farFuture = today.plus(365, DateTimeUnit.DAY)

                // Before any return, both contributions would be candidates in a fresh preview once
                // their debit-in-flight items are cleared -- rather than assert the "before" state
                // (which depends on the item still being PENDING/DEBIT_IN_FLIGHT), assert the AFTER
                // state directly: cancel the in-flight items first so the contributions become
                // OPEN-equivalent candidates again, matching what would happen after a real return.
                client.post("/test/sepa/return?debitItemId=$itemAc04&returnedAt=$today&reasonCode=AC04") {
                    header("X-Member-Id", treasurer.toString())
                }
                client.post("/test/sepa/return?debitItemId=$itemMd01&returnedAt=$today&reasonCode=MD01") {
                    header("X-Member-Id", treasurer.toString())
                }

                val previewResponse =
                    client.post("/test/sepa/preview?requestedCollectionDate=$farFuture&dueOnOrBefore=$farFuture") {
                        header("X-Member-Id", treasurer.toString())
                    }
                previewResponse.status shouldBe HttpStatusCode.OK
                val includedContributionIds =
                    previewResponse
                        .bodyAsText()
                        .split("|")[2]
                        .split(",")
                        .filter { it.isNotBlank() }
                includedContributionIds.contains(contributionAc04.toString()) shouldBe false
                includedContributionIds.contains(contributionMd01.toString()) shouldBe false
            }
        }

        // ── M-5: synchronous mandate expiry/membership re-check in createDebitBatch (poller NOT running) ──

        test("M-5: with the poller NOT running, createDebitBatch excludes an expired mandate and a withdrawn member's mandate") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-m5-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val expiredMember = createTestMember("sepa-m5-expired-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val withdrawnMember =
                    createTestMember(
                        "sepa-m5-withdrawn-member-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.WITHDRAWN,
                    )
                val healthyMember = createTestMember("sepa-m5-healthy-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)

                // Expired: granted 40 months ago, never used -- well past the 36-month expiry, but the
                // mandate row itself is still marked ACTIVE (as it would be with the poller disabled).
                val today = DbClock.nowLocalDateTime().date
                val fortyMonthsAgoDate = today.minus(40, DateTimeUnit.MONTH)
                val fortyMonthsAgo =
                    LocalDateTime(fortyMonthsAgoDate.year, fortyMonthsAgoDate.monthNumber, fortyMonthsAgoDate.dayOfMonth, 9, 0)
                grantMandateRow(memberId = expiredMember, createdBy = expiredMember, grantedAt = fortyMonthsAgo, lastUsedAt = null)
                grantMandateRow(memberId = withdrawnMember, createdBy = withdrawnMember)
                grantMandateRow(memberId = healthyMember, createdBy = healthyMember)

                createOpenContribution(memberId = expiredMember, tierId = tier)
                createOpenContribution(memberId = withdrawnMember, tierId = tier)
                createOpenContribution(memberId = healthyMember, tierId = tier)

                val collectionDate = today.plus(30, DateTimeUnit.DAY)
                // membershipTierId scopes to only this test's own three contributions -- see E2E
                // test's identical comment for why this matters (no afterTest cleanup in this file).
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                createResponse.status shouldBe HttpStatusCode.OK
                val fields = createResponse.bodyAsText().split("|")
                val batchId = Uuid.parse(fields[0])
                createdBatchIds += batchId
                fields[2] shouldBe "1" // itemCount -- only the healthy member's contribution was included

                val includedMemberIds =
                    transaction {
                        (SepaDebitItemTable innerJoin ContributionTable)
                            .selectAll()
                            .where { SepaDebitItemTable.batchId eq batchId }
                            .map { it[ContributionTable.memberId] }
                    }
                includedMemberIds shouldBe listOf(healthyMember)
            }
        }

        // ── N-1: listMyPrenotifications (Review Round 2, 2026-08-20, CRITICAL) ──────────

        test(
            "N-1: listMyPrenotifications returns the four legally required disclosure fields for a " +
                "NOTIFIED batch's PENDING item -- would fail against the pre-fix ambiguous join (HTTP 500)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-n1-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-n1-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)
                val mandateId = grantMandateRow(memberId = member, createdBy = member)
                val contributionId = createOpenContribution(memberId = member, tierId = tier)
                val (batchId, _) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = member,
                        mandateId = mandateId,
                        contributionId = contributionId,
                        batchStatus = SepaDebitBatchStatus.NOTIFIED,
                    )
                createdBatchIds += batchId

                val response =
                    client.get("/test/sepa/my-prenotifications") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK // NOT 500 -- this is exactly what the ambiguous join broke
                val fields =
                    response
                        .bodyAsText()
                        .split(";")
                        .single()
                        .split("|")
                fields[0] shouldBe batchId.toString()
                fields[1] shouldBe contributionId.toString()
                val mandateReference =
                    transaction {
                        SepaMandateTable
                            .selectAll()
                            .where { SepaMandateTable.id eq mandateId }
                            .single()[SepaMandateTable.mandateReference]
                    }
                fields[2] shouldBe mandateReference // mandateReference -- disclosure 1
                fields[3] shouldBe "DE98ZZZ09999999999" // creditorId -- disclosure 2
                fields[4] shouldBe "Sepa-Fixture Verein" // creditorName -- disclosure 3
                fields[5] shouldBe "50.00" // amount -- disclosure 4 (collection date is also on the DTO, untested here)
                fields[6] shouldBe "1234" // debtorIbanLast4
            }
        }

        test("N-1: a member cannot see another member's prenotifications") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-n1-iso-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val owner = createTestMember("sepa-n1-iso-owner-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val bystander = createTestMember("sepa-n1-iso-bystander-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)
                val mandateId = grantMandateRow(memberId = owner, createdBy = owner)
                val contributionId = createOpenContribution(memberId = owner, tierId = tier)
                val (batchId, _) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = owner,
                        mandateId = mandateId,
                        contributionId = contributionId,
                        batchStatus = SepaDebitBatchStatus.NOTIFIED,
                    )
                createdBatchIds += batchId

                val ownerResponse =
                    client.get("/test/sepa/my-prenotifications") { header("X-Member-Id", owner.toString()) }
                ownerResponse.status shouldBe HttpStatusCode.OK
                ownerResponse.bodyAsText().isNotBlank() shouldBe true

                val bystanderResponse =
                    client.get("/test/sepa/my-prenotifications") { header("X-Member-Id", bystander.toString()) }
                bystanderResponse.status shouldBe HttpStatusCode.OK
                // the WHERE clause scopes strictly to ContributionTable.memberId eq current.memberId
                bystanderResponse.bodyAsText() shouldBe ""
            }
        }

        // ── N-4: MANDATE_EXPIRED is surfaced in buildPreview, mirroring createDebitBatch's own check ──

        test(
            "N-4: previewDebitBatch surfaces an EXPIRED mandate with reason MANDATE_EXPIRED, " +
                "mirroring what createDebitBatch would actually do",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-n4-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val expiredMember = createTestMember("sepa-n4-expired-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val healthyMember = createTestMember("sepa-n4-healthy-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                enableSepaForOrg(ackByMemberId = treasurer)

                // Same construction as the M-5 test above: granted 40 months ago, never used -- well
                // past the 36-month expiry, mandate row itself still ACTIVE (poller disabled/not run).
                val today = DbClock.nowLocalDateTime().date
                val fortyMonthsAgoDate = today.minus(40, DateTimeUnit.MONTH)
                val fortyMonthsAgo =
                    LocalDateTime(fortyMonthsAgoDate.year, fortyMonthsAgoDate.monthNumber, fortyMonthsAgoDate.dayOfMonth, 9, 0)
                grantMandateRow(memberId = expiredMember, createdBy = expiredMember, grantedAt = fortyMonthsAgo, lastUsedAt = null)
                grantMandateRow(memberId = healthyMember, createdBy = healthyMember)

                val expiredContribution = createOpenContribution(memberId = expiredMember, tierId = tier)
                val healthyContribution = createOpenContribution(memberId = healthyMember, tierId = tier)

                val farFuture = today.plus(365, DateTimeUnit.DAY)
                // membershipTierId scopes to only this test's own two contributions -- same "no
                // afterTest cleanup in this file" reasoning the M-5 test's comment explains.
                val previewResponse =
                    client.post(
                        "/test/sepa/preview?requestedCollectionDate=$farFuture&dueOnOrBefore=$farFuture&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                previewResponse.status shouldBe HttpStatusCode.OK
                val fields = previewResponse.bodyAsText().split("|")
                fields[0] shouldBe "1" // itemCount -- only the healthy member's contribution is included
                val includedContributionIds = fields[2].split(",").filter { it.isNotBlank() }
                includedContributionIds shouldBe listOf(healthyContribution.toString())
                val excludedField = fields[3]
                excludedField shouldBe "$expiredContribution:MANDATE_EXPIRED"
            }
        }

        // ── Security Round 1 (2026-08-20) ────────────────────────────────────────────────

        test(
            "MAJOR-2: generateBatchFile archives the pain.008 file SecretBox-encrypted at rest -- the " +
                "raw bytes on disk are NOT plaintext-IBAN-readable, but decrypt back to the real XML",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-m2-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-m2-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SM1${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SM2${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                val plaintextIban = "DE89370400440532013000"
                val today = DbClock.nowLocalDateTime().date
                val grantResponse =
                    client.post(
                        "/test/sepa/grant?debtorName=M2+Konto&debtorIban=$plaintextIban&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                grantResponse.status shouldBe HttpStatusCode.OK
                createdMandateIds += Uuid.parse(grantResponse.bodyAsText().split("|")[0])
                createOpenContribution(memberId = member, tierId = tier)

                val collectionDate = today.plus(30, DateTimeUnit.DAY)
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val batchId = createResponse.bodyAsText().split("|")[0]
                createdBatchIds += Uuid.parse(batchId)
                client.post("/test/sepa/notify?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                val generateResponse = client.post("/test/sepa/generate?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                generateResponse.status shouldBe HttpStatusCode.OK

                val (_, storageKey) =
                    transaction {
                        val batchRow = SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq Uuid.parse(batchId) }.single()
                        val docId = requireNotNull(batchRow[SepaDebitBatchTable.generatedDocumentId])
                        val versionId =
                            requireNotNull(
                                DocumentTable.selectAll().where { DocumentTable.id eq docId }.single()[DocumentTable.currentVersionId],
                            )
                        val key =
                            DocumentVersionTable
                                .selectAll()
                                .where { DocumentVersionTable.id eq versionId }
                                .single()[DocumentVersionTable.storageKey]
                        docId to key
                    }
                val documentStorageRoot = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")
                val archivedFile = documentStorageRoot.resolve(storageKey)
                archivedFile.exists() shouldBe true
                val rawContentOnDisk = archivedFile.readText(Charsets.UTF_8)

                // The whole point of MAJOR-2's "seal the archive too" fix: the plaintext IBAN (and
                // the plaintext XML structure) must never appear verbatim on disk.
                rawContentOnDisk.contains(plaintextIban) shouldBe false
                rawContentOnDisk.contains("<Document") shouldBe false

                // But it DOES decrypt back to the real pain.008 file, under the SAME key/aad
                // generateBatchFile itself used (SecretBox(key).open with aad = batchId).
                val secretBoxForTest =
                    network.lapis.cloud.server.crypto
                        .SecretBox(requireNotNull(testSepaConfig.secretEncryptionKey))
                val decrypted = secretBoxForTest.open(sealed = rawContentOnDisk, aad = batchId)
                decrypted.contains(plaintextIban) shouldBe true
                decrypted.contains("<Document") shouldBe true
            }
        }

        test(
            "NEW-2 (Security Round 2): finalizeGeneratedBatchFile (generateBatchFile's own phase 3) " +
                "rejects with ConflictException when the live PENDING item count has diverged from what " +
                "phase 1 actually captured -- reproduces the exact phase-1-to-phase-3 gap deterministically " +
                "by calling phase 1 and phase 3 as separate, real steps with a genuine revokeMandate call " +
                "interleaved between them (a true-thread race for this specific sub-second window proved " +
                "unreliable against the fast in-memory test database -- roughly 1 run in 4-5 flaked, see " +
                "finalizeGeneratedBatchFile's own KDoc for why this deterministic shape replaced it)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-n2-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-n2-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SN9${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SNA${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                val today = DbClock.nowLocalDateTime().date
                val grantResponse =
                    client.post(
                        "/test/sepa/grant?debtorName=N2+Konto&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                val mandateId = grantResponse.bodyAsText().split("|")[0]
                createdMandateIds += Uuid.parse(mandateId)
                createOpenContribution(memberId = member, tierId = tier)

                val collectionDate = today.plus(30, DateTimeUnit.DAY)
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val batchId = createResponse.bodyAsText().split("|")[0]
                createdBatchIds += Uuid.parse(batchId)
                client.post("/test/sepa/notify?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }

                // Phase 1, for real -- captures a genuine PreparedBatchFile snapshot (remainingCount
                // = 1: the mandate is ACTIVE and its item PENDING at this exact moment).
                val phase1Response =
                    client.post("/test/sepa/prepare-phase1?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                phase1Response.status shouldBe HttpStatusCode.OK
                phase1Response.bodyAsText() shouldBe "1" // remainingCount

                // The concurrent event phase 3's NEW guard exists for -- a REAL revokeMandate call
                // landing in the gap phase 1's own commit just opened, cancelling this batch's only
                // item. The batch itself stays NOTIFIED throughout (it is not GENERATED yet), so this
                // is an entirely ordinary, successful revocation from the member's own point of view.
                val revokeResponse =
                    client.post("/test/sepa/revoke?mandateId=$mandateId") { header("X-Member-Id", member.toString()) }
                revokeResponse.status shouldBe HttpStatusCode.OK

                // A minimal Document row -- finalizeGeneratedBatchFile only needs a valid FK target
                // here, not real archived content (phase 2's OWN file-writing/encryption is not what
                // NEW-2 guards against, and is already covered by the MAJOR-2 test above).
                val folderId = Uuid.random()
                val documentId = Uuid.random()
                transaction {
                    DocumentFolderTable.insert {
                        it[id] = folderId
                        it[name] = "SEPA-Lastschriften-N2-Test"
                        it[parentFolderId] = null
                    }
                    DocumentTable.insert {
                        it[id] = documentId
                        it[DocumentTable.folderId] = folderId
                        it[title] = "N2-Test SEPA-Datei"
                        it[currentVersionId] = null
                        it[DocumentTable.createdBy] = treasurer
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[accessLevel] = DocumentAccessLevel.ADMIN_ONLY
                        it[isDeleted] = false
                    }
                }

                // Phase 3, for real -- using the STALE prepared snapshot captured BEFORE the
                // revocation (still stashed server-side against batchId by /test/sepa/prepare-phase1).
                val phase3Response =
                    client.post("/test/sepa/finalize-phase3?batchId=$batchId&documentId=$documentId") {
                        header("X-Member-Id", treasurer.toString())
                    }
                phase3Response.status shouldBe HttpStatusCode.Conflict

                // The batch must NOT have been flipped to GENERATED with a stale document attached.
                val batchAfter =
                    transaction { SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq Uuid.parse(batchId) }.single() }
                batchAfter[SepaDebitBatchTable.status] shouldBe SepaDebitBatchStatus.NOTIFIED
                batchAfter[SepaDebitBatchTable.generatedDocumentId] shouldBe null
            }
        }

        test(
            "MAJOR-3: revoking a mandate whose PENDING item sits in a GENERATED batch resets that " +
                "batch to NOTIFIED, soft-deletes the stale document, and writes a SEPA_DEBIT_BATCH audit entry",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-m3-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-m3-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SN1${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SN2${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                val today = DbClock.nowLocalDateTime().date
                val grantResponse =
                    client.post(
                        "/test/sepa/grant?debtorName=M3+Konto&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                val mandateId = grantResponse.bodyAsText().split("|")[0]
                createdMandateIds += Uuid.parse(mandateId)
                createOpenContribution(memberId = member, tierId = tier)

                val collectionDate = today.plus(30, DateTimeUnit.DAY)
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val batchId = createResponse.bodyAsText().split("|")[0]
                createdBatchIds += Uuid.parse(batchId)
                client.post("/test/sepa/notify?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                val generateResponse = client.post("/test/sepa/generate?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                generateResponse.status shouldBe HttpStatusCode.OK
                generateResponse.bodyAsText().split("|")[0] shouldBe "GENERATED"

                val staleDocumentId =
                    transaction {
                        requireNotNull(
                            SepaDebitBatchTable
                                .selectAll()
                                .where { SepaDebitBatchTable.id eq Uuid.parse(batchId) }
                                .single()[SepaDebitBatchTable.generatedDocumentId],
                        )
                    }
                val auditCountBefore =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(batchId)) and
                                    (AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.SEPA_DEBIT_BATCH)
                            }.count()
                    }

                val revokeResponse =
                    client.post("/test/sepa/revoke?mandateId=$mandateId") { header("X-Member-Id", member.toString()) }
                revokeResponse.status shouldBe HttpStatusCode.OK
                revokeResponse.bodyAsText().split("|")[1] shouldBe "REVOKED"

                val batchAfter =
                    transaction { SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq Uuid.parse(batchId) }.single() }
                batchAfter[SepaDebitBatchTable.status] shouldBe SepaDebitBatchStatus.NOTIFIED
                batchAfter[SepaDebitBatchTable.generatedDocumentId] shouldBe null
                batchAfter[SepaDebitBatchTable.generatedAt] shouldBe null
                // notifiedAt/requiredNoticeDays stay untouched -- the treasurer can regenerate
                // immediately, without waiting through the notice period again.
                batchAfter[SepaDebitBatchTable.notifiedAt] shouldNotBe null
                batchAfter[SepaDebitBatchTable.requiredNoticeDays] shouldNotBe null

                val staleDocumentDeleted =
                    transaction {
                        DocumentTable.selectAll().where { DocumentTable.id eq staleDocumentId }.single()[DocumentTable.isDeleted]
                    }
                staleDocumentDeleted shouldBe true

                val auditCountAfter =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(batchId)) and
                                    (AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.SEPA_DEBIT_BATCH)
                            }.count()
                    }
                auditCountAfter shouldBe auditCountBefore + 1L

                // A fresh generateBatchFile call succeeds and now excludes the revoked member's item
                // entirely (its own item was cancelled, so `remaining` no longer contains it).
                val regenerateResponse =
                    client.post("/test/sepa/generate?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                regenerateResponse.status shouldBe HttpStatusCode.Conflict // no PENDING items remain in THIS test's batch (only one item)
            }
        }

        test(
            "NEW-1 (Security Round 2): recordReturn's M-6 auto-revocation resets an UNRELATED " +
                "already-GENERATED batch that holds another PENDING item for the SAME mandate -- not just " +
                "the item the return itself concerns -- and audits the reset to the SYSTEM, not the treasurer",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-n1a-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-n1a-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SN5${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SN6${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                val today = DbClock.nowLocalDateTime().date
                val grantResponse =
                    client.post(
                        "/test/sepa/grant?debtorName=N1A+Konto&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                val mandateId = grantResponse.bodyAsText().split("|")[0]
                createdMandateIds += Uuid.parse(mandateId)

                // Batch B -- real end-to-end flow through GENERATED, so there is a REAL document on
                // disk to prove it gets soft-deleted, and a real frozen itemCount to prove it gets
                // recalculated to 0.
                createOpenContribution(memberId = member, tierId = tier)
                val collectionDate = today.plus(30, DateTimeUnit.DAY)
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val batchBId = createResponse.bodyAsText().split("|")[0]
                createdBatchIds += Uuid.parse(batchBId)
                client.post("/test/sepa/notify?batchId=$batchBId") { header("X-Member-Id", treasurer.toString()) }
                val generateResponse = client.post("/test/sepa/generate?batchId=$batchBId") { header("X-Member-Id", treasurer.toString()) }
                generateResponse.status shouldBe HttpStatusCode.OK
                generateResponse.bodyAsText().split("|")[0] shouldBe "GENERATED"

                val staleDocumentId =
                    transaction {
                        requireNotNull(
                            SepaDebitBatchTable
                                .selectAll()
                                .where { SepaDebitBatchTable.id eq Uuid.parse(batchBId) }
                                .single()[SepaDebitBatchTable.generatedDocumentId],
                        )
                    }
                val itemBId =
                    transaction {
                        SepaDebitItemTable.selectAll().where { SepaDebitItemTable.batchId eq Uuid.parse(batchBId) }.single()[
                            SepaDebitItemTable.id,
                        ]
                    }

                // Batch A -- a SEPARATE, unrelated batch for the SAME mandate (e.g. an earlier due
                // period, already submitted) -- recordReturn below is called against THIS item, not
                // Batch B's. Uses the raw-insert fixture (same house style as the "markBatchSubmitted
                // hardening" test right below) since only its PENDING item status matters here, not
                // its own lifecycle. A SEPARATE tier -- `uq_contribution_member_tier_period` would
                // otherwise collide with the contribution createOpenContribution already made above
                // for Batch B (same member, same hardcoded 2020 period).
                val tierA = createTier()
                val contributionAId = createOpenContribution(memberId = member, tierId = tierA)
                val (batchAId, itemAId) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = member,
                        mandateId = Uuid.parse(mandateId),
                        contributionId = contributionAId,
                        batchStatus = SepaDebitBatchStatus.SUBMITTED,
                    )
                createdBatchIds += batchAId

                val auditCountBBefore =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(batchBId)) and
                                    (AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.SEPA_DEBIT_BATCH)
                            }.count()
                    }

                val returnResponse =
                    client.post(
                        "/test/sepa/return?debitItemId=$itemAId&returnedAt=$today&reasonCode=MD01",
                    ) { header("X-Member-Id", treasurer.toString()) }
                returnResponse.status shouldBe HttpStatusCode.OK
                returnResponse.bodyAsText().split("|")[1] shouldBe "true" // mandateRevoked

                // Batch B -- completely unrelated to the return call -- must ALSO have been reset.
                val batchBAfter =
                    transaction { SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq Uuid.parse(batchBId) }.single() }
                batchBAfter[SepaDebitBatchTable.status] shouldBe SepaDebitBatchStatus.NOTIFIED
                batchBAfter[SepaDebitBatchTable.generatedDocumentId] shouldBe null
                batchBAfter[SepaDebitBatchTable.generatedAt] shouldBe null
                batchBAfter[SepaDebitBatchTable.itemCount] shouldBe 0
                batchBAfter[SepaDebitBatchTable.totalAmount] shouldBe BigDecimal("0.00")
                // notifiedAt/requiredNoticeDays stay untouched -- same MAJOR-3 contract.
                batchBAfter[SepaDebitBatchTable.notifiedAt] shouldNotBe null
                batchBAfter[SepaDebitBatchTable.requiredNoticeDays] shouldNotBe null

                val itemBAfter =
                    transaction { SepaDebitItemTable.selectAll().where { SepaDebitItemTable.id eq itemBId }.single() }
                itemBAfter[SepaDebitItemTable.status] shouldBe SepaDebitItemStatus.CANCELLED

                val staleDocumentDeleted =
                    transaction {
                        DocumentTable.selectAll().where { DocumentTable.id eq staleDocumentId }.single()[DocumentTable.isDeleted]
                    }
                staleDocumentDeleted shouldBe true

                val auditCountBAfter =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(batchBId)) and
                                    (AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.SEPA_DEBIT_BATCH)
                            }.count()
                    }
                auditCountBAfter shouldBe auditCountBBefore + 1L

                // The NEW audit entry for Batch B's reset is attributed to the SYSTEM (null), not the
                // treasurer who called recordReturn -- same actor convention the mandate's own M-6
                // entry already uses.
                val newBatchBAuditActor =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(batchBId)) and
                                    (AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.SEPA_DEBIT_BATCH)
                            }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                            .limit(1)
                            .single()[AuditLogEntryTable.actorMemberId]
                    }
                newBatchBAuditActor shouldBe null
            }
        }

        test(
            "markBatchSubmitted hardening: rejects with ConflictException when the live PENDING item " +
                "count no longer matches the batch's own itemCount frozen at generation time (stale-file guard)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-msub-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-msub-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = treasurer)
                val mandateId = grantMandateRow(memberId = member, createdBy = member)
                val contributionId = createOpenContribution(memberId = member, tierId = createTier())
                // A GENERATED batch (not SUBMITTED) whose itemCount (1) reflects what was archived.
                val (batchId, itemId) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = member,
                        mandateId = mandateId,
                        contributionId = contributionId,
                        batchStatus = SepaDebitBatchStatus.GENERATED,
                    )
                createdBatchIds += batchId

                // Simulates a return recorded against a still-GENERATED (not yet submitted) batch's
                // item -- recordReturn does not recalculate the batch's own itemCount, so this is
                // exactly the "file/DB item-set diverged after generation, without a fresh
                // generateBatchFile call" scenario markBatchSubmitted's own hardening targets.
                val returnResponse =
                    client.post(
                        "/test/sepa/return?debitItemId=$itemId&returnedAt=${DbClock.nowLocalDateTime().date}&reasonCode=MD01",
                    ) { header("X-Member-Id", treasurer.toString()) }
                returnResponse.status shouldBe HttpStatusCode.OK

                val submitResponse = client.post("/test/sepa/submit?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                submitResponse.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "F-1a (Security Round 3): markBatchSubmitted rejects when a GENERATED batch's item mandate " +
                "crossed its 36-month expiry boundary while the batch sat GENERATED -- independent of " +
                "SepaBatchPoller, which is never started/ticked anywhere in this test",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-f1a-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-f1a-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = treasurer)
                // Granted 40 months ago, never used -- well past the 36-month expiry (same
                // construction as the M-5/N-4 tests above), but the mandate row's OWN status stays
                // ACTIVE: SepaBatchPoller.tick() is never called anywhere in this test, so nothing
                // ever flips it to EXPIRED in the DB (mirrors LAPIS_SEPA_POLLER_ENABLED=false, the
                // production default) -- markBatchSubmitted must catch this itself, synchronously.
                val today = DbClock.nowLocalDateTime().date
                val fortyMonthsAgoDate = today.minus(40, DateTimeUnit.MONTH)
                val fortyMonthsAgo =
                    LocalDateTime(fortyMonthsAgoDate.year, fortyMonthsAgoDate.monthNumber, fortyMonthsAgoDate.dayOfMonth, 9, 0)
                val mandateId = grantMandateRow(memberId = member, createdBy = member, grantedAt = fortyMonthsAgo, lastUsedAt = null)
                val contributionId = createOpenContribution(memberId = member, tierId = createTier())
                // A GENERATED batch (as if generated back when the mandate was still valid) whose
                // itemCount (1) matches the live PENDING item -- the pre-existing item-count
                // divergence check alone would NOT catch this, since the item SET is unchanged; only
                // a mandate-validity re-check (this fix) can.
                val (batchId, _) =
                    insertPendingBatchItem(
                        treasurer = treasurer,
                        member = member,
                        mandateId = mandateId,
                        contributionId = contributionId,
                        batchStatus = SepaDebitBatchStatus.GENERATED,
                    )
                createdBatchIds += batchId

                val mandateStatusBeforeSubmit =
                    transaction {
                        SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status]
                    }
                mandateStatusBeforeSubmit shouldBe SepaMandateStatus.ACTIVE // proves the poller never ran

                val submitResponse = client.post("/test/sepa/submit?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                submitResponse.status shouldBe HttpStatusCode.Conflict

                // No side effects from the rejected submission -- the batch stays GENERATED (not
                // silently advanced to SUBMITTED) and the contribution/mandate are untouched.
                val batchStatusAfter =
                    transaction {
                        SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.single()[SepaDebitBatchTable.status]
                    }
                batchStatusAfter shouldBe SepaDebitBatchStatus.GENERATED
                val contributionStatusAfter =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                contributionStatusAfter shouldBe ContributionStatus.OPEN
            }
        }

        test(
            "MAJOR-4: createDebitBatch freezes the creditor identity onto the batch -- a later live " +
                "organization_settings change does NOT retroactively affect this batch's " +
                "generateBatchFile output or listMyPrenotifications",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val treasurer = createTestMember("sepa-m4-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("sepa-m4-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tier = createTier()
                val bankAccountId = createLedgerAccount("SO1${Uuid.random().toString().take(5)}", LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount("SO2${Uuid.random().toString().take(5)}", LedgerAccountType.INCOME)
                enableSepaForOrg(
                    ackByMemberId = treasurer,
                    paymentBankAccountId = bankAccountId,
                    contributionIncomeAccountId = incomeAccountId,
                )
                // enableSepaForOrg's own fixture creditor identity ("X"): sepaCreditorId
                // "DE98ZZZ09999999999" / sepaCreditorName "Sepa-Fixture Verein".
                val today = DbClock.nowLocalDateTime().date
                // Real grantMandate RPC (not the raw-table grantMandateRow helper) -- generateBatchFile
                // below actually SecretBox-decrypts debtor_iban_ciphertext, which a fake placeholder
                // ciphertext cannot satisfy (same reasoning the "batch lifecycle end-to-end" test's own
                // comment gives).
                val grantResponse =
                    client.post(
                        "/test/sepa/grant?debtorName=M4+Konto&debtorIban=DE89370400440532013000&signatureDate=$today",
                    ) { header("X-Member-Id", member.toString()) }
                createdMandateIds += Uuid.parse(grantResponse.bodyAsText().split("|")[0])
                createOpenContribution(memberId = member, tierId = tier)

                val collectionDate = today.plus(30, DateTimeUnit.DAY)
                val createResponse =
                    client.post(
                        "/test/sepa/create-batch?requestedCollectionDate=$collectionDate&dueOnOrBefore=${today.plus(1, DateTimeUnit.DAY)}" +
                            "&membershipTierId=$tier",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val batchId = createResponse.bodyAsText().split("|")[0]
                createdBatchIds += Uuid.parse(batchId)
                client.post("/test/sepa/notify?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }

                // Frozen at createDebitBatch time -- confirm the batch's OWN row already carries X.
                val frozenAfterCreate =
                    transaction { SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq Uuid.parse(batchId) }.single() }
                frozenAfterCreate[SepaDebitBatchTable.creditorId] shouldBe "DE98ZZZ09999999999"
                frozenAfterCreate[SepaDebitBatchTable.creditorName] shouldBe "Sepa-Fixture Verein"

                // Simulates an ADMIN changing the org's creditor identity to Y DURING the mandatory
                // notice window (direct table write -- bypasses updateSepaCreditorSettings' own
                // Security Round 1 in-flight-batch guard, which is tested separately below; this
                // isolates the FREEZE mechanism itself from the GUARD that now also blocks the RPC
                // path).
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[sepaCreditorId] = "DE11YYYYY11111111111"
                        it[sepaCreditorName] = "Divergierender Verein Y"
                    }
                }

                // listMyPrenotifications must STILL show X, not Y.
                val prenotifResponse =
                    client.get("/test/sepa/my-prenotifications") { header("X-Member-Id", member.toString()) }
                val prenotifFields =
                    prenotifResponse
                        .bodyAsText()
                        .split(";")
                        .single()
                        .split("|")
                prenotifFields[3] shouldBe "DE98ZZZ09999999999" // creditorId
                prenotifFields[4] shouldBe "Sepa-Fixture Verein" // creditorName

                // generateBatchFile must ALSO still embed X, not Y.
                val generateResponse = client.post("/test/sepa/generate?batchId=$batchId") { header("X-Member-Id", treasurer.toString()) }
                generateResponse.status shouldBe HttpStatusCode.OK
                val (_, storageKey) =
                    transaction {
                        val batchRow = SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq Uuid.parse(batchId) }.single()
                        val docId = requireNotNull(batchRow[SepaDebitBatchTable.generatedDocumentId])
                        val versionId =
                            requireNotNull(
                                DocumentTable.selectAll().where { DocumentTable.id eq docId }.single()[DocumentTable.currentVersionId],
                            )
                        val key =
                            DocumentVersionTable
                                .selectAll()
                                .where { DocumentVersionTable.id eq versionId }
                                .single()[DocumentVersionTable.storageKey]
                        docId to key
                    }
                val documentStorageRoot = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")
                val sealed = documentStorageRoot.resolve(storageKey).readText(Charsets.UTF_8)
                val decrypted =
                    network.lapis.cloud.server.crypto
                        .SecretBox(requireNotNull(testSepaConfig.secretEncryptionKey))
                        .open(sealed = sealed, aad = batchId)
                decrypted.contains("DE98ZZZ09999999999") shouldBe true
                decrypted.contains("Sepa-Fixture Verein") shouldBe true
                decrypted.contains("DE11YYYYY11111111111") shouldBe false
                decrypted.contains("Divergierender Verein Y") shouldBe false
            }
        }

        test("MAJOR-4 safeguard: updateSepaCreditorSettings rejects a creditor id/name change while a batch is DRAFT/NOTIFIED/GENERATED") {
            testApplication {
                application {
                    install(StatusPages) { installSepaExceptionHandlers() }
                    routing { registerSepaTestRoutes(sepaConfig = testSepaConfig) }
                }
                val admin = createTestMember("sepa-m4guard-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("sepa-m4guard-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableSepaForOrg(ackByMemberId = admin)
                val mandateId = grantMandateRow(memberId = member, createdBy = member)
                val contributionId = createOpenContribution(memberId = member, tierId = createTier())
                val (batchId, _) =
                    insertPendingBatchItem(
                        treasurer = admin,
                        member = member,
                        mandateId = mandateId,
                        contributionId = contributionId,
                        batchStatus = SepaDebitBatchStatus.NOTIFIED,
                    )
                createdBatchIds += batchId

                val blocked =
                    client.post(
                        "/test/sepa/update-creditor-settings?sepaCreditorId=DE22ZZZZ22222222222&sepaCreditorName=Neuer%20Name" +
                            "&sepaPrenotificationDays=14",
                    ) { header("X-Member-Id", admin.toString()) }
                blocked.status shouldBe HttpStatusCode.Conflict

                // Changing ONLY sepaPrenotificationDays (not the creditor identity) is still allowed
                // -- that field is NOT frozen onto a batch, so it cannot diverge the same way.
                val allowedDaysOnlyChange =
                    client.post(
                        "/test/sepa/update-creditor-settings?sepaCreditorId=DE98ZZZ09999999999&sepaCreditorName=Sepa-Fixture%20Verein" +
                            "&sepaPrenotificationDays=21",
                    ) { header("X-Member-Id", admin.toString()) }
                allowedDaysOnlyChange.status shouldBe HttpStatusCode.OK
            }
        }
    })

/** Shared throwaway routes for [SepaServiceTest] -- SepaService(call, sepaConfig).methodName(...), simple pipe-delimited responses. */
private fun Route.registerSepaTestRoutes(sepaConfig: SepaConfig) {
    fun service(callCtx: io.ktor.server.application.ApplicationCall) = SepaService(call = callCtx, sepaConfig = sepaConfig)

    // NEW-1 (Security Round 2, 2026-08-20) test-only seam: stashes the PreparedBatchFile a real
    // /test/sepa/prepare-phase1 call captured, keyed by batchId, so a LATER, separate
    // /test/sepa/finalize-phase3 call can deliberately re-use a now-STALE snapshot -- see
    // [SepaServiceTest]'s own "NEW-2" test and [SepaService.finalizeGeneratedBatchFile] KDoc for why
    // this replaced an earlier, empirically flaky true-thread-race version of that test.
    val preparedStash = mutableMapOf<String, PreparedBatchFile>()

    post("/test/sepa/update-creditor-settings") {
        val q = call.request.queryParameters
        val dto =
            service(call).updateSepaCreditorSettings(
                network.lapis.cloud.shared.domain.SepaCreditorSettingsInput(
                    sepaCreditorId = q["sepaCreditorId"]?.takeIf { it.isNotBlank() },
                    sepaCreditorName = q["sepaCreditorName"]?.takeIf { it.isNotBlank() },
                    sepaPrenotificationDays = q["sepaPrenotificationDays"]!!.toInt(),
                ),
            )
        call.respondText("${dto.sepaCreditorId}|${dto.sepaCreditorName}|${dto.sepaPrenotificationDays}")
    }
    post("/test/sepa/grant") {
        val q = call.request.queryParameters
        val dto =
            service(call).grantMandate(
                network.lapis.cloud.shared.domain.SepaMandateInput(
                    memberId = q["memberId"]?.takeIf { it.isNotBlank() },
                    debtorName = q["debtorName"]!!,
                    debtorIban = q["debtorIban"]!!,
                    debtorBic = q["debtorBic"]?.takeIf { it.isNotBlank() },
                    signatureDate = LocalDate.parse(q["signatureDate"]!!),
                    mandateTextAcknowledged = true,
                ),
            )
        call.respondText("${dto.id}|${dto.status}|${dto.createdBySelf}")
    }
    post("/test/sepa/revoke") {
        val q = call.request.queryParameters
        val dto = service(call).revokeMandate(mandateId = q["mandateId"]!!, reason = q["reason"])
        call.respondText("${dto.id}|${dto.status}")
    }
    post("/test/sepa/preview") {
        val q = call.request.queryParameters
        val dto =
            service(call).previewDebitBatch(
                network.lapis.cloud.shared.domain.SepaDebitBatchInput(
                    requestedCollectionDate = LocalDate.parse(q["requestedCollectionDate"]!!),
                    dueOnOrBefore = LocalDate.parse(q["dueOnOrBefore"]!!),
                    membershipTierId = q["membershipTierId"]?.takeIf { it.isNotBlank() },
                ),
            )
        val excludedField = dto.excluded.joinToString(",") { "${it.contributionId}:${it.reason}" }
        call.respondText("${dto.itemCount}|${dto.totalAmount}|${dto.items.joinToString(",") { it.contributionId }}|$excludedField")
    }
    post("/test/sepa/create-batch") {
        val q = call.request.queryParameters
        val dto =
            service(call).createDebitBatch(
                network.lapis.cloud.shared.domain.SepaDebitBatchInput(
                    requestedCollectionDate = LocalDate.parse(q["requestedCollectionDate"]!!),
                    dueOnOrBefore = LocalDate.parse(q["dueOnOrBefore"]!!),
                    membershipTierId = q["membershipTierId"]?.takeIf { it.isNotBlank() },
                ),
            )
        call.respondText("${dto.id}|${dto.status}|${dto.itemCount}")
    }
    post("/test/sepa/notify") {
        val dto = service(call).notifyBatch(call.request.queryParameters["batchId"]!!)
        call.respondText("${dto.status}|${dto.requiredNoticeDays}")
    }
    post("/test/sepa/generate") {
        val dto = service(call).generateBatchFile(call.request.queryParameters["batchId"]!!)
        call.respondText("${dto.status}|${dto.itemCount}")
    }
    post("/test/sepa/submit") {
        val q = call.request.queryParameters
        val dto = service(call).markBatchSubmitted(batchId = q["batchId"]!!, note = q["note"])
        call.respondText("${dto.status}")
    }
    post("/test/sepa/cancel") {
        val q = call.request.queryParameters
        val dto = service(call).cancelBatch(batchId = q["batchId"]!!, reason = q["reason"] ?: "test")
        call.respondText("${dto.status}")
    }
    post("/test/sepa/settle") {
        val dto = service(call).settleBatch(call.request.queryParameters["batchId"]!!)
        call.respondText("${dto.batch.status}|${dto.failedItemIds.joinToString(",")}")
    }
    get("/test/sepa/batch") {
        val dto = service(call).getBatch(call.request.queryParameters["batchId"]!!)
        call.respondText("${dto.batch.status}|${dto.items.joinToString(",") { "${it.id}:${it.status}" }}")
    }
    get("/test/sepa/my-prenotifications") {
        val dtos = service(call).listMyPrenotifications()
        call.respondText(
            dtos.joinToString(";") {
                "${it.batchId}|${it.contributionId}|${it.mandateReference}|${it.creditorId}|${it.creditorName}|${it.amount}|" +
                    "${it.debtorIbanLast4}"
            },
        )
    }
    post("/test/sepa/return") {
        val q = call.request.queryParameters
        val dto =
            service(call).recordReturn(
                network.lapis.cloud.shared.domain.SepaReturnInput(
                    debitItemId = q["debitItemId"]!!,
                    returnedAt = LocalDate.parse(q["returnedAt"]!!),
                    reasonCode = SepaReturnReason.valueOf(q["reasonCode"]!!),
                    reasonText = q["reasonText"],
                    returnFee = null,
                ),
            )
        call.respondText("${dto.id}|${dto.mandateRevoked}")
    }
    // NEW-2 (Security Round 2, 2026-08-20) -- see [preparedStash] KDoc above and
    // [SepaServiceTest]'s own "NEW-2" test.
    post("/test/sepa/prepare-phase1") {
        val batchId = call.request.queryParameters["batchId"]!!
        val secretBox =
            network.lapis.cloud.server.crypto
                .SecretBox(requireNotNull(sepaConfig.secretEncryptionKey))
        val prepared = service(call).prepareBatchFileGeneration(id = Uuid.parse(batchId), secretBox = secretBox)
        preparedStash[batchId] = prepared
        call.respondText("${prepared.remainingCount}")
    }
    post("/test/sepa/finalize-phase3") {
        val q = call.request.queryParameters
        val batchId = q["batchId"]!!
        val prepared = requireNotNull(preparedStash[batchId]) { "call /test/sepa/prepare-phase1 first" }
        val current =
            network.lapis.cloud.server.security
                .resolveCurrentMember(call)
        val dto =
            service(call).finalizeGeneratedBatchFile(
                id = Uuid.parse(batchId),
                prepared = prepared,
                documentId = Uuid.parse(q["documentId"]!!),
                current = current,
            )
        call.respondText("${dto.status}|${dto.itemCount}")
    }
}

/** Same exception -> HTTP-status mapping [ContributionPaymentRpcTest] uses. */
private fun StatusPagesConfig.installSepaExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}

/**
 * Inserts a PENDING batch/item pair directly (bypassing createDebitBatch/notifyBatch/generateBatchFile
 * /markBatchSubmitted) -- used by every `recordReturn`-focused test, which needs an item eligible for
 * a return (PENDING or SETTLEABLE) without exercising the whole upstream lifecycle again. Also reused
 * by [SepaServiceTest]'s `listMyPrenotifications` (N-1) tests -- SUBMITTED is one of the three
 * statuses that method surfaces (NOTIFIED/GENERATED/SUBMITTED), and [batchStatus] lets those tests
 * pick NOTIFIED explicitly to match the exact real-world case its own KDoc describes.
 */
private fun insertPendingBatchItem(
    treasurer: Uuid,
    member: Uuid,
    mandateId: Uuid,
    contributionId: Uuid,
    batchStatus: SepaDebitBatchStatus = SepaDebitBatchStatus.SUBMITTED,
    // Defaults match enableSepaForOrg's own fixture values (Security Round 1, 2026-08-20, MAJOR-4:
    // listMyPrenotifications now reads these off the BATCH row itself, not organization_settings
    // live -- see that method's own KDoc) -- callers that want to exercise a DIVERGED/frozen value
    // pass their own.
    creditorId: String? = "DE98ZZZ09999999999",
    creditorName: String? = "Sepa-Fixture Verein",
    creditorIban: String? = "DE89370400440532013000",
    creditorBic: String? = "COBADEFFXXX",
    generatedDocumentId: Uuid? = null,
): Pair<Uuid, Uuid> {
    val batchId = Uuid.random()
    val itemId = Uuid.random()
    val now = DbClock.nowLocalDateTime()
    transaction {
        SepaDebitBatchTable.insert {
            it[id] = batchId
            it[messageId] = "LC-DD-RET-${batchId.toString().take(8)}"
            it[paymentInfoId] = "LC-DD-RET-${batchId.toString().take(8)}-P1"
            it[requestedCollectionDate] = now.date
            it[sequenceType] = network.lapis.cloud.shared.domain.SepaSequenceType.RCUR
            it[status] = batchStatus
            it[itemCount] = 1
            it[totalAmount] = BigDecimal("50.00")
            it[createdBy] = treasurer
            it[createdAt] = now
            it[notifiedAt] = now
            it[requiredNoticeDays] = 14
            it[generatedAt] = now
            it[SepaDebitBatchTable.generatedDocumentId] = generatedDocumentId
            it[prenotificationDocumentId] = null
            it[submittedAt] = now
            it[submittedNote] = null
            it[settledAt] = null
            it[cancelledAt] = null
            it[cancellationReason] = null
            it[SepaDebitBatchTable.creditorId] = creditorId
            it[SepaDebitBatchTable.creditorName] = creditorName
            it[SepaDebitBatchTable.creditorIban] = creditorIban
            it[SepaDebitBatchTable.creditorBic] = creditorBic
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
    return batchId to itemId
}
