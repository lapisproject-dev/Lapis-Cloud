package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.accounting.export.AccountingExportProviderAdapter
import network.lapis.cloud.server.accounting.export.AccountingExportStore
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AccountingExportCategoryMapTable
import network.lapis.cloud.server.db.generated.AccountingExportConnectionTable
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.AccountingExportRunTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AccountingExportBlockerKind
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val TEST_KEY = ByteArray(SecretBox.KEY_SIZE_BYTES) { it.toByte() }
private const val TEST_TOKEN = "lex-test-token-1234567890abcdef"

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- same "own throwaway routing calling the service
 * directly" house style [BankStatementServiceTest]/[SepaServiceTest] establish. Covers the role
 * matrix (deliberately narrower than [IAccountingService]'s own TREASURER/BOARD/ADMIN read tier --
 * no BOARD here at all, see [IAccountingExportService] KDoc), the token lifecycle (never returned in
 * plaintext, AAD-bound so a copied ciphertext does not decrypt under a different row), and the
 * zero-VAT disclaimer's hash-pinning.
 */
class AccountingExportServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        // Security review Fund 2026-09-07 (Runde 5, MAJOR): `createBookableEntry` used to hardcode
        // "1000"/"8000" as its two account numbers, which is fine as long as it is called at most
        // once per test -- the `listUnknownItems` test below is the first to call it TWICE in the
        // SAME test (two independent periods/entries), which collided on `uq_ledger_account_number`.
        // A monotonic counter (spec-lifetime, not reset in afterEach) keeps every call's pair unique
        // regardless of how many tests or how many calls within one test.
        var bookableEntryCounter = 0

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                // Order matters -- FKs run item -> journal_entry/ledger_account, posting ->
                // journal_entry/ledger_account, category_map -> ledger_account, all the way down to
                // member. Runs/items/postings/category-maps must go before the journal entries/
                // ledger accounts/members they reference.
                if (createdJournalEntryIds.isNotEmpty()) {
                    AccountingExportItemTable.deleteWhere { AccountingExportItemTable.journalEntryId inList createdJournalEntryIds }
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList createdJournalEntryIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // Runs are started_by a test member -- any run this file created is reachable
                    // that way, without needing a separate createdRunIds tracker.
                    AccountingExportRunTable.deleteWhere { AccountingExportRunTable.startedBy inList createdMemberIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    AccountingExportCategoryMapTable.deleteWhere {
                        AccountingExportCategoryMapTable.ledgerAccountId inList createdLedgerAccountIds
                    }
                }
                if (createdJournalEntryIds.isNotEmpty()) {
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdJournalEntryIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                exec("DELETE FROM accounting_export_connection")
                createdMemberIds.forEach {
                    // setToken/acknowledgeZeroVat write an audit_log_entry row referencing the actor
                    // -- fk_audit_log_entry_actor_member_id would otherwise block deleting the member.
                    exec("DELETE FROM audit_log_entry WHERE actor_member_id = '$it'")
                    AccountTable.deleteWhere { AccountTable.memberId eq it }
                    MemberTable.deleteWhere { MemberTable.id eq it }
                }
            }
            createdMemberIds.clear()
            createdJournalEntryIds.clear()
            createdLedgerAccountIds.clear()
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "AccountingExportService-Testmitglied"
                    it[email] = "accexp-svc-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
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

        /** One bookable entry: Bank (ASSET, debit) against a mapped income account (credit) --
         * exactly the "singular CREDIT side is INCOME" shape AccountingExportPlanner.plan resolves to
         * a single, mappable INCOME voucher (see its own KDoc) -- shared fixture for every test below
         * that needs a real [buildJournalExportRequest]/[AccountingExportPlanner.plan] pass, not a
         * hand-built [PlannedVoucher]. */
        fun createBookableEntry(
            treasurer: Uuid,
            entryDate: LocalDate = LocalDate(2026, 1, 15),
        ): Uuid {
            val now = DbClock.nowLocalDateTime()
            val bankAccountId = Uuid.random()
            val incomeAccountId = Uuid.random()
            val suffix = (bookableEntryCounter++).toString().padStart(3, '0')
            transaction {
                LedgerAccountTable.insert {
                    it[id] = bankAccountId
                    it[accountNumber] = "1$suffix"
                    it[name] = "Bank (AccountingExportServiceTest)"
                    it[accountClass] = 0
                    it[type] = LedgerAccountType.ASSET
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
                LedgerAccountTable.insert {
                    it[id] = incomeAccountId
                    it[accountNumber] = "8$suffix"
                    it[name] = "Spenden (AccountingExportServiceTest)"
                    it[accountClass] = 0
                    it[type] = LedgerAccountType.INCOME
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += bankAccountId
            createdLedgerAccountIds += incomeAccountId
            AccountingExportStore.mapAccount(
                provider = AccountingExportProvider.LEXOFFICE,
                ledgerAccountId = incomeAccountId,
                externalCategoryId = "cat-income-1",
                externalCategoryName = "Spenden",
                mappedBy = treasurer,
                now = now,
            )
            val entryId = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[id] = entryId
                    it[JournalEntryTable.entryDate] = entryDate
                    it[description] = "AccountingExportServiceTest-Buchung"
                    it[voucherReference] = null
                    it[createdBy] = treasurer
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(entryDate.year, entryDate.monthNumber, entryDate.dayOfMonth, 9, 0)
                    it[createdAt] = LocalDateTime(entryDate.year, entryDate.monthNumber, entryDate.dayOfMonth, 8, 0)
                }
                PostingTable.insert {
                    it[id] = Uuid.random()
                    it[journalEntryId] = entryId
                    it[ledgerAccountId] = bankAccountId
                    it[side] = PostingSide.DEBIT
                    it[amount] = BigDecimal("42.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
                PostingTable.insert {
                    it[id] = Uuid.random()
                    it[journalEntryId] = entryId
                    it[ledgerAccountId] = incomeAccountId
                    it[side] = PostingSide.CREDIT
                    it[amount] = BigDecimal("42.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += entryId
            return entryId
        }

        /** A single, otherwise-unmapped ledger account -- fixture for the mapAccount validation
         * tests below, which do not need a full [createBookableEntry] journal entry. */
        fun createLedgerAccount(): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = "8100"
                    it[name] = "AccountingExportServiceTest-mapAccount-Konto"
                    it[accountClass] = 0
                    it[type] = LedgerAccountType.INCOME
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        /** Security review Fund 2026-09-07 (Runde 5, MINOR): a terminal (`COMPLETED_WITH_ERRORS`,
         * no `active_key`) run with exactly ONE `UNKNOWN` item for [journalEntryId] -- direct table
         * inserts, deliberately bypassing `AccountingExportService.startExport`/`AccountingExportStore
         * .createRun`, because the `UNRESOLVED_UNKNOWN_ITEMS` blocker those go through now refuses to
         * plan a SECOND item for a journal entry that already has an `UNKNOWN` one -- exactly the
         * "two UNKNOWN items, same entry" shape the `exported_key` collision test below needs to
         * reach `resolveUnknownItem`'s new `ConflictException` translation. Cleaned up by the shared
         * `afterEach` above via `createdMemberIds`/`createdJournalEntryIds` -- no separate tracker
         * needed. Returns `runId to itemId`. */
        fun insertUnknownRunAndItem(
            treasurer: Uuid,
            journalEntryId: Uuid,
            now: LocalDateTime,
        ): Pair<Uuid, Uuid> {
            val runId = Uuid.random()
            val itemId = Uuid.random()
            transaction {
                AccountingExportRunTable.insert {
                    it[id] = runId
                    it[provider] = AccountingExportProvider.LEXOFFICE
                    it[periodFrom] = LocalDate(2026, 1, 1)
                    it[periodTo] = LocalDate(2026, 1, 31)
                    it[status] = network.lapis.cloud.shared.domain.AccountingExportRunStatus.COMPLETED_WITH_ERRORS
                    it[activeKey] = null
                    it[startedBy] = treasurer
                    it[startedAt] = now
                    it[finishedAt] = now
                    it[totalCount] = 1
                    it[succeededCount] = 0
                    it[failedCount] = 0
                    it[skippedCount] = 0
                    it[unknownCount] = 1
                }
                AccountingExportItemTable.insert {
                    it[id] = itemId
                    it[AccountingExportItemTable.runId] = runId
                    it[provider] = AccountingExportProvider.LEXOFFICE
                    it[AccountingExportItemTable.journalEntryId] = journalEntryId
                    it[entryDate] = LocalDate(2026, 1, 15)
                    it[externalCategoryId] = "cat-income-1"
                    it[voucherNumber] = "TEST-${itemId.toString().take(8)}"
                    it[direction] = network.lapis.cloud.shared.domain.AccountingExportDirection.INCOME
                    it[grossAmount] = BigDecimal("42.00")
                    it[status] = network.lapis.cloud.shared.domain.AccountingExportItemStatus.UNKNOWN
                    it[exportedKey] = null
                    it[attempts] = 1
                    it[finishedAt] = now
                }
            }
            return runId to itemId
        }

        test("every method: MEMBER and BOARD are forbidden, TREASURER/ADMIN pass the role gate") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val member = createMember(AccountRole.MEMBER)
                val board = createMember(AccountRole.BOARD)
                val treasurer = createMember(AccountRole.TREASURER)
                val admin = createMember(AccountRole.ADMIN)

                client.get("/test/accexp/connection") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/accexp/connection") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/accexp/connection") { header("X-Member-Id", treasurer.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/accexp/connection") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("setToken without a configured SecretBox throws ConflictException (409), never stores plaintext") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = null) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client
                    .post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
                transaction { AccountingExportConnectionTable.selectAll().count() } shouldBe 0L
            }
        }

        test("setToken stores a sealed ciphertext, never the plaintext token, and getConnection only ever exposes the last 4 characters") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val row = transaction { AccountingExportConnectionTable.selectAll().single() }
                row[AccountingExportConnectionTable.tokenCiphertext]?.contains(TEST_TOKEN) shouldBe false
                row[AccountingExportConnectionTable.tokenLast4] shouldBe TEST_TOKEN.takeLast(4)

                val roundTripped =
                    AccountingExportStore.readToken(provider = AccountingExportProvider.LEXOFFICE, secretBox = SecretBox(TEST_KEY))
                roundTripped shouldBe TEST_TOKEN
            }
        }

        test("a ciphertext copied onto a different connection row's AAD fails to decrypt (SecretBoxException), not silently") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }

                val box = SecretBox(TEST_KEY)
                val ciphertext =
                    transaction {
                        AccountingExportConnectionTable.selectAll().single()[AccountingExportConnectionTable.tokenCiphertext]
                    }!!
                val wrongAad = Uuid.random().toString()
                val exception = runCatching { box.open(sealed = ciphertext, aad = wrongAad) }.exceptionOrNull()
                (exception is SecretBoxException) shouldBe true
            }
        }

        test("setToken with an invalid format (too short, or containing CR/LF) is a 400, never reaches the store") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client.post("/test/accexp/set-token?token=short") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.BadRequest
                transaction { AccountingExportConnectionTable.selectAll().count() } shouldBe 0L
            }
        }

        test(
            "mapAccount rejects an oversized externalCategoryId/-Name with a readable 400 instead of a generic 500 " +
                "from the VARCHAR(64)/VARCHAR(200) column overflow (Fund 2026-09-07 security review Runde 3, " +
                "Befund 3)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val accountId = createLedgerAccount()

                val oversizedId = "x".repeat(65)
                client
                    .post(
                        "/test/accexp/map-account?ledgerAccountId=$accountId&externalCategoryId=$oversizedId",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest

                val oversizedName = "y".repeat(201)
                client
                    .post(
                        "/test/accexp/map-account?ledgerAccountId=$accountId&externalCategoryId=cat-1&externalCategoryName=$oversizedName",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest

                transaction { AccountingExportCategoryMapTable.selectAll().count() } shouldBe 0L
            }
        }

        test(
            "mapAccount for an unknown ledgerAccountId is a 404, not a generic 500 from the foreign-key violation " +
                "(Fund 2026-09-07 security review Runde 3, Befund 3)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val unknownAccountId = Uuid.random()
                client
                    .post(
                        "/test/accexp/map-account?ledgerAccountId=$unknownAccountId&externalCategoryId=cat-1",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("mapAccount with a valid ledgerAccountId and well-formed category fields succeeds and persists the mapping") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val accountId = createLedgerAccount()
                client
                    .post(
                        "/test/accexp/map-account?ledgerAccountId=$accountId&externalCategoryId=cat-1&externalCategoryName=Spenden",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.OK

                val row =
                    transaction {
                        AccountingExportCategoryMapTable
                            .selectAll()
                            .where { AccountingExportCategoryMapTable.ledgerAccountId eq accountId }
                            .single()
                    }
                row[AccountingExportCategoryMapTable.externalCategoryId] shouldBe "cat-1"
                row[AccountingExportCategoryMapTable.externalCategoryName] shouldBe "Spenden"

                // Security review Runde 3, Befund 4 (Fund 2026-09-07): mapAccount now writes an
                // ACCOUNTING_EXPORT_MAPPING audit entry -- previously none at all. CREATE, since this
                // ledger account was never mapped before.
                val auditRow =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ACCOUNTING_EXPORT_MAPPING) and
                                    (AuditLogEntryTable.entityId eq row[AccountingExportCategoryMapTable.id])
                            }.single()
                    }
                auditRow[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
                auditRow[AuditLogEntryTable.actorMemberId] shouldBe treasurer
            }
        }

        test(
            "previewExport is rate-limited per member (409 once the budget is exhausted), same as every other " +
                "expensive method on this service (Fund 2026-09-07 security review Runde 3, Befund 5: previewExport " +
                "previously had no rate limit of its own at all)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing {
                        val tightPreviewLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
                        get("/test/accexp/preview-tight-limit") {
                            val svc =
                                AccountingExportService(
                                    call = call,
                                    secretBox = SecretBox(TEST_KEY),
                                    adaptersByProvider = emptyMap(),
                                    testRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                                    startExportRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                                    previewRateLimiter = tightPreviewLimiter,
                                )
                            val dto =
                                svc.previewExport(
                                    provider = AccountingExportProvider.LEXOFFICE,
                                    from = LocalDate(2026, 1, 1),
                                    to = LocalDate(2026, 1, 31),
                                )
                            call.respondText("exportable=${dto.exportable}")
                        }
                    }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                repeat(2) {
                    client.get("/test/accexp/preview-tight-limit") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                        HttpStatusCode.OK
                }
                client.get("/test/accexp/preview-tight-limit") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("acknowledgeZeroVat with a wrong hash is rejected (409), a correct hash succeeds and is reflected in getConnection") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client
                    .post("/test/accexp/acknowledge-zero-vat?sha256=not-the-real-hash") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
                client
                    .post(
                        "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                    ) {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK

                val row = transaction { AccountingExportConnectionTable.selectAll().single() }
                row[AccountingExportConnectionTable.zeroVatAcknowledgedAt].shouldNotBeNull()
                row[AccountingExportConnectionTable.zeroVatDisclaimerVersion] shouldBe ZeroVatExportDisclaimer.VERSION
            }
        }

        test(
            "previewExport re-blocks with ZERO_VAT_NOT_ACKNOWLEDGED when the stored zero_vat_disclaimer_version is " +
                "STALE, even though zero_vat_acknowledged_at is still set (Fund 2026-09-07: buildPreview previously " +
                "checked only zeroVatAcknowledgedAt != null, never the version -- a disclaimer wording revision would " +
                "silently NOT require re-quittance from an already-acknowledged organization)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client
                    .post(
                        "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                    ) {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK

                // Simulate a later disclaimer wording revision: the stored acknowledgment now
                // points at a superseded version, exactly like a real VERSION bump would leave it
                // for every already-connected organization.
                transaction {
                    AccountingExportConnectionTable.update({
                        AccountingExportConnectionTable.provider eq AccountingExportProvider.LEXOFFICE
                    }) {
                        it[zeroVatDisclaimerVersion] = "2020-01-01.v0-stale"
                    }
                }

                val response = client.get("/test/accexp/preview") { header("X-Member-Id", treasurer.toString()) }
                val body = response.bodyAsText()
                body.contains(AccountingExportBlockerKind.ZERO_VAT_NOT_ACKNOWLEDGED.name) shouldBe true
                body.contains("exportable=false") shouldBe true
            }
        }

        test(
            "previewExport re-blocks with ZERO_VAT_NOT_ACKNOWLEDGED when the stored zero_vat_disclaimer_sha256 is " +
                "STALE even though zero_vat_disclaimer_version still equals the CURRENT VERSION (Security-Audit-Fund " +
                "2026-09-07, Runde 6: a provider displayName edit changes what sha256For(provider) computes without " +
                "touching VERSION -- a version-only gate would silently accept a quittance of a text nobody re-read)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client
                    .post(
                        "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                    ) {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK

                // Simulate a later displayName/text edit that leaves VERSION untouched -- exactly
                // the scenario the KDoc on `ZeroVatExportDisclaimer.sha256For` and the buildPreview
                // gate now guard against. The version alone still matches; only the hash is stale.
                transaction {
                    AccountingExportConnectionTable.update({
                        AccountingExportConnectionTable.provider eq AccountingExportProvider.LEXOFFICE
                    }) {
                        it[zeroVatDisclaimerSha256] = "0".repeat(64)
                    }
                }

                val response = client.get("/test/accexp/preview") { header("X-Member-Id", treasurer.toString()) }
                val body = response.bodyAsText()
                body.contains(AccountingExportBlockerKind.ZERO_VAT_NOT_ACKNOWLEDGED.name) shouldBe true
                body.contains("exportable=false") shouldBe true

                val getConnectionResponse =
                    client.get("/test/accexp/connection") { header("X-Member-Id", treasurer.toString()) }
                getConnectionResponse.bodyAsText() shouldContain "zeroVatAcknowledged=false"
            }
        }

        test("markTestSuccess truncates an over-long provider company name instead of throwing on the length-limited column") {
            val provider = AccountingExportProvider.LEXOFFICE
            AccountingExportStore.getOrCreateConnection(provider = provider, now = DbClock.nowLocalDateTime())
            val overLong = "X".repeat(350) // connected_company_name is VARCHAR(300)
            AccountingExportStore.markTestSuccess(provider = provider, companyName = overLong, now = DbClock.nowLocalDateTime())
            val stored =
                transaction {
                    val row =
                        AccountingExportConnectionTable
                            .selectAll()
                            .where { AccountingExportConnectionTable.provider eq provider }
                            .single()
                    row[AccountingExportConnectionTable.connectedCompanyName]
                }
            stored?.length shouldBe 300
        }

        test("previewExport without any connected token reports NOT_CONNECTED and ZERO_VAT_NOT_ACKNOWLEDGED, exportable == false") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val response =
                    client.get("/test/accexp/preview") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body.contains(AccountingExportBlockerKind.NOT_CONNECTED.name) shouldBe true
                body.contains(AccountingExportBlockerKind.ZERO_VAT_NOT_ACKNOWLEDGED.name) shouldBe true
                body.contains("exportable=false") shouldBe true
            }
        }

        test("startExport with from > to is a 400 (BadRequestException), before any blocker/role logic beyond the role gate itself") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client
                    .post("/test/accexp/start-invalid-range") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "startExport whose run's planned vouchers are ALL already exported finalizes to COMPLETED right at " +
                "ITS OWN real call site, active_key clears, and a subsequent startExport for the same provider is " +
                "NOT blocked with 409 (Fund 2026-09-07 review Runde 2, Befund 1: the prior regression test for this " +
                "exact fix -- AccountingExportPollerTest's \"a run whose planned vouchers are ALL already exported " +
                "finalizes to COMPLETED right at creation\" -- calls AccountingExportStore.recomputeRunCounts " +
                "DIRECTLY, never through AccountingExportService.startExport's own wiring; this test drives the " +
                "real RPC method twice end-to-end so the one-line fix at its actual call site is what is verified)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val now = DbClock.nowLocalDateTime()

                // Connect + acknowledge zero-VAT so previewExport/startExport see no connection blocker.
                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }
                AccountingExportStore.markTestSuccess(
                    provider = AccountingExportProvider.LEXOFFICE,
                    companyName = "Testverein e.V.",
                    now = now,
                )
                client.post(
                    "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                ) {
                    header("X-Member-Id", treasurer.toString())
                }

                val entryId = createBookableEntry(treasurer)

                // First real startExport: exactly one PENDING item -- nothing has been sent yet, no
                // poller runs in this test, so the run stays RUNNING (this call is only fixture setup).
                val firstBody =
                    client
                        .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()
                firstBody shouldContain "status=RUNNING"
                val firstRunId = Uuid.parse(firstBody.substringAfter("id=").substringBefore(" "))
                val firstItemId =
                    transaction {
                        AccountingExportItemTable
                            .selectAll()
                            .where { AccountingExportItemTable.runId eq firstRunId }
                            .single()[AccountingExportItemTable.id]
                    }
                // Simulate exactly what AccountingExportPoller does on a successful send (mark the
                // item SUCCEEDED, then recompute the run) -- this finishes the FIRST run so the entry
                // counts as already-exported for the second startExport below.
                AccountingExportStore.markSucceeded(
                    id = firstItemId,
                    provider = AccountingExportProvider.LEXOFFICE,
                    journalEntryId = entryId,
                    externalVoucherId = "ext-1",
                    now = DbClock.nowLocalDateTime(),
                )
                AccountingExportStore.recomputeRunCounts(runId = firstRunId, now = DbClock.nowLocalDateTime())
                AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false

                // Second real startExport, SAME period: the one journal entry in range is now
                // already-exported, so this run's planned vouchers are ALL already-exported at
                // creation -- exactly the scenario the fix in AccountingExportService.startExport
                // (the recomputeRunCounts call right after createRun) exists to handle. Unlike the
                // Store-level regression test, THIS call goes through the real RPC method.
                val secondBody =
                    client
                        .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()
                secondBody shouldContain "status=COMPLETED"
                AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false

                // The regression this fix prevents: without it, the second run would stay RUNNING
                // with active_key set forever, and this third call would come back 409 Conflict.
                client
                    .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "retryFailed racing uq_accounting_export_run_active against a concurrently-active second run for the " +
                "SAME provider is a 409 (Conflict), not a generic 500 (Fund 2026-09-07 review Runde 2, Befund 3: " +
                "retryFailed's own KDoc documents \"same caller-translates-to-Conflict contract as [createRun]\", " +
                "and startExport already implements that catch, but retryFailed itself never did -- an " +
                "ExposedSQLException from the unique-index violation reached the client as an unhandled " +
                "exception instead of a readable Conflict)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val now = DbClock.nowLocalDateTime()

                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }
                AccountingExportStore.markTestSuccess(
                    provider = AccountingExportProvider.LEXOFFICE,
                    companyName = "Testverein e.V.",
                    now = now,
                )
                client.post(
                    "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                ) {
                    header("X-Member-Id", treasurer.toString())
                }
                createBookableEntry(treasurer)

                // Run A: started, then aborted -- ABORTED/active_key=NULL, its one item FAILED/
                // ABORTED_BY_USER (exactly retryFailed's own reopen-eligible terminal state).
                val runABody =
                    client
                        .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()
                val runAId = runABody.substringAfter("id=").substringBefore(" ")
                client.post("/test/accexp/abort?id=$runAId") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.OK
                AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false

                // Security review Runde 3, Befund 4 (Fund 2026-09-07): abortRun now writes an
                // ACCOUNTING_EXPORT_RUN/UPDATE audit entry -- previously none at all. Filtered on
                // `action` too, not just `entityType`/`entityId`: startExport's own new CREATE entry
                // for this same run (Befund 4 also covers startExport) is a second, earlier row for
                // the identical entityId -- a plain `.single()` on entityId alone would find both.
                val abortAuditRow =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ACCOUNTING_EXPORT_RUN) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(runAId)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.single()
                    }
                abortAuditRow[AuditLogEntryTable.actorMemberId] shouldBe treasurer

                // Run B: a fresh, independent second start for the SAME provider -- legitimately
                // possible now that run A's abort cleared active_key. This is what run A's retry
                // below collides with (its item was never SUCCEEDED, so the one journal entry is
                // NOT already-exported and run B gets a real PENDING item of its own).
                client.post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }.status shouldBe HttpStatusCode.OK
                AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe true

                // retryFailed(run A) reopens it to RUNNING + active_key=LEXOFFICE -- but run B already
                // holds that same (provider) active_key, so this must come back 409, not crash.
                client.post("/test/accexp/retry-failed?id=$runAId") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Conflict

                // The failed reopen attempt's own transaction rolled back -- run A is still ABORTED,
                // never left half-reopened.
                client
                    .get("/test/accexp/run?id=$runAId") { header("X-Member-Id", treasurer.toString()) }
                    .bodyAsText() shouldContain "status=ABORTED"
            }
        }

        test(
            "previewExport/startExport block with UNRESOLVED_UNKNOWN_ITEMS while a journal entry in range has an " +
                "UNKNOWN item from an earlier run, and resolveUnknownItem(CONFIRMED_NOT_SENT) lifts the block " +
                "(Fund 2026-09-07 security review Runde 4, Befund 2: retryFailed already refused to silently reopen " +
                "an UNKNOWN item, but nothing stopped the SAME entry being re-planned via previewExport/startExport)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val now = DbClock.nowLocalDateTime()
                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }
                AccountingExportStore.markTestSuccess(
                    provider = AccountingExportProvider.LEXOFFICE,
                    companyName = "Testverein e.V.",
                    now = now,
                )
                client.post(
                    "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                ) {
                    header("X-Member-Id", treasurer.toString())
                }
                createBookableEntry(treasurer)

                val startBody =
                    client
                        .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()
                val runId = Uuid.parse(startBody.substringAfter("id=").substringBefore(" "))
                val itemId =
                    transaction {
                        AccountingExportItemTable
                            .selectAll()
                            .where { AccountingExportItemTable.runId eq runId }
                            .single()[AccountingExportItemTable.id]
                    }
                // Simulate exactly what reapStaleClaims/abortRun leave behind: a SENDING claim whose
                // outcome at the provider was never confirmed.
                AccountingExportStore.markUnknown(id = itemId, errorCode = "STALE_CLAIM_REAPED", now = DbClock.nowLocalDateTime())
                AccountingExportStore.recomputeRunCounts(runId = runId, now = DbClock.nowLocalDateTime())

                val previewBody =
                    client.get("/test/accexp/preview") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                previewBody.contains(AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS.name) shouldBe true
                previewBody.contains("exportable=false") shouldBe true

                // A fresh startExport for the SAME period must be refused -- re-planning the entry
                // now would risk a genuine duplicate voucher, exactly what retryFailed's own
                // UNKNOWN-skipping guard already protects against on the reopen path.
                client.post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.Conflict

                // A nonexistent item id is a 404.
                client
                    .post("/test/accexp/resolve-unknown?itemId=${Uuid.random()}&resolution=CONFIRMED_NOT_SENT") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.NotFound

                // Resolve: a TREASURER manually checked lexoffice and confirmed the voucher was NEVER
                // created there.
                client
                    .post("/test/accexp/resolve-unknown?itemId=$itemId&resolution=CONFIRMED_NOT_SENT") {
                        header("X-Member-Id", treasurer.toString())
                    }.also { it.status shouldBe HttpStatusCode.OK }
                    .bodyAsText() shouldContain "status=FAILED"

                // Security review Fund 2026-09-07 (Runde 4, Befund 2): resolveUnknownItem writes an
                // ACCOUNTING_EXPORT_RUN/UPDATE audit entry too, entityId = the item's OWNING run.
                val resolveAuditRow =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ACCOUNTING_EXPORT_RUN) and
                                    (AuditLogEntryTable.entityId eq runId) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.single()
                    }
                resolveAuditRow[AuditLogEntryTable.actorMemberId] shouldBe treasurer

                // Resolving the SAME item a second time is a 409 -- it is no longer UNKNOWN.
                client
                    .post("/test/accexp/resolve-unknown?itemId=$itemId&resolution=CONFIRMED_NOT_SENT") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                // The blocker is lifted -- a fresh startExport for the same period is accepted again
                // (a NEW PENDING item is planned for the entry, same as retryFailed's own reopen
                // path would have produced for a FAILED item).
                val previewAfter =
                    client.get("/test/accexp/preview") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                previewAfter.contains(AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS.name) shouldBe false
            }
        }

        test(
            "resolveUnknownItem(CONFIRMED_SENT) marks the item SUCCEEDED with the given externalVoucherId and " +
                "exported_key set -- alreadyExportedJournalEntryIds recognizes the entry from then on, listRunItems " +
                "exposes the item's own id (Fund 2026-09-07 security review Runde 4, Befund 2: AccountingExportItemDto " +
                "previously carried no id at all, only journalEntryId, so resolveUnknownItem had no target to act on)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val now = DbClock.nowLocalDateTime()
                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }
                AccountingExportStore.markTestSuccess(
                    provider = AccountingExportProvider.LEXOFFICE,
                    companyName = "Testverein e.V.",
                    now = now,
                )
                client.post(
                    "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                ) {
                    header("X-Member-Id", treasurer.toString())
                }
                val entryId = createBookableEntry(treasurer)

                val startBody =
                    client
                        .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .bodyAsText()
                val runId = Uuid.parse(startBody.substringAfter("id=").substringBefore(" "))
                val itemId =
                    transaction {
                        AccountingExportItemTable
                            .selectAll()
                            .where { AccountingExportItemTable.runId eq runId }
                            .single()[AccountingExportItemTable.id]
                    }
                AccountingExportStore.markUnknown(id = itemId, errorCode = "ABORTED_WHILE_SENDING", now = DbClock.nowLocalDateTime())
                AccountingExportStore.recomputeRunCounts(runId = runId, now = DbClock.nowLocalDateTime())

                // listRunItems exposes the item's own id -- the response encodes it via the test
                // route's "id=..." prefix (see registerAccountingExportTestRoutes above).
                val itemsBody =
                    client.get("/test/accexp/run-items?id=$runId") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                itemsBody shouldContain "id=$itemId status=UNKNOWN"

                client
                    .post("/test/accexp/resolve-unknown?itemId=$itemId&resolution=CONFIRMED_SENT&externalVoucherId=ext-manual-1") {
                        header("X-Member-Id", treasurer.toString())
                    }.also { it.status shouldBe HttpStatusCode.OK }
                    .bodyAsText() shouldBe "status=SUCCEEDED externalVoucherId=ext-manual-1"

                AccountingExportStore.isAlreadyExported(provider = AccountingExportProvider.LEXOFFICE, journalEntryId = entryId) shouldBe
                    true
                val row = transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemId }.single() }
                row[AccountingExportItemTable.exportedKey].shouldNotBeNull()

                // The blocker is lifted -- and this entry now counts as already-exported, not
                // re-planned.
                val previewAfter =
                    client.get("/test/accexp/preview") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                previewAfter.contains(AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS.name) shouldBe false
            }
        }

        test(
            "listUnknownItems finds an UNKNOWN item from an EARLIER run even after a LATER run has started for the " +
                "same provider -- getLatestRun only ever returns the most recent run, so without this method the item " +
                "is unreachable from the UI once a later run starts (Fund 2026-09-07 security review Runde 5, MAJOR)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val now = DbClock.nowLocalDateTime()
                client.post("/test/accexp/set-token?token=$TEST_TOKEN") { header("X-Member-Id", treasurer.toString()) }
                AccountingExportStore.markTestSuccess(
                    provider = AccountingExportProvider.LEXOFFICE,
                    companyName = "Testverein e.V.",
                    now = now,
                )
                client.post(
                    "/test/accexp/acknowledge-zero-vat?sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                ) {
                    header("X-Member-Id", treasurer.toString())
                }

                // Run A (January) -- leaves an UNKNOWN item behind, same simulation as the blocker
                // test above.
                createBookableEntry(treasurer, entryDate = LocalDate(2026, 1, 15))
                val runAId =
                    Uuid.parse(
                        client
                            .post("/test/accexp/start") { header("X-Member-Id", treasurer.toString()) }
                            .also { it.status shouldBe HttpStatusCode.OK }
                            .bodyAsText()
                            .substringAfter("id=")
                            .substringBefore(" "),
                    )
                val itemAId =
                    transaction {
                        AccountingExportItemTable
                            .selectAll()
                            .where { AccountingExportItemTable.runId eq runAId }
                            .single()[AccountingExportItemTable.id]
                    }
                AccountingExportStore.markUnknown(id = itemAId, errorCode = "STALE_CLAIM_REAPED", now = DbClock.nowLocalDateTime())
                AccountingExportStore.recomputeRunCounts(runId = runAId, now = DbClock.nowLocalDateTime())
                AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false

                // Run B (an unrelated, later period/entry) -- becomes `getLatestRun` from here on.
                createBookableEntry(treasurer, entryDate = LocalDate(2026, 3, 15))
                val runBId =
                    Uuid.parse(
                        client
                            .post("/test/accexp/start-range?from=2026-03-01&to=2026-03-31") {
                                header("X-Member-Id", treasurer.toString())
                            }.also { it.status shouldBe HttpStatusCode.OK }
                            .bodyAsText()
                            .substringAfter("id=")
                            .substringBefore(" "),
                    )

                // getLatestRun only ever shows run B -- run A is no longer reachable through it.
                client.get("/test/accexp/latest-run") { header("X-Member-Id", treasurer.toString()) }.bodyAsText() shouldBe
                    "id=$runBId status=RUNNING"

                // listUnknownItems still finds run A's UNKNOWN item, carrying its own runId.
                val unknownBody =
                    client.get("/test/accexp/unknown-items") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                unknownBody shouldContain "id=$itemAId runId=$runAId status=UNKNOWN"

                // ...and it is resolvable through the normal path, discovered via that listing's own
                // item id -- the whole point of this fix.
                client
                    .post("/test/accexp/resolve-unknown?itemId=$itemAId&resolution=CONFIRMED_NOT_SENT") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK

                // Resolved -- no longer listed as unknown.
                client
                    .get("/test/accexp/unknown-items") { header("X-Member-Id", treasurer.toString()) }
                    .bodyAsText() shouldBe ""
            }
        }

        test(
            "resolveUnknownItem(CONFIRMED_SENT) on two different UNKNOWN items of the SAME journal entry -- the " +
                "first succeeds, the second is a 409 Conflict (exported_key unique-index collision), never a raw " +
                "500 (Fund 2026-09-07 security review Runde 5, MINOR)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val entryId = createBookableEntry(treasurer)
                val now = DbClock.nowLocalDateTime()

                // Two independent runs, each with its own UNKNOWN item for the SAME journal entry --
                // a shape `AccountingExportStore.unknownJournalEntryIds` KDoc itself documents as
                // reachable (pre-fix legacy data, or a race of two concurrent resolves for two
                // separate UNKNOWN items of one entry), simulated here via direct inserts since the
                // UNRESOLVED_UNKNOWN_ITEMS blocker now prevents reproducing it through the normal
                // previewExport/startExport path.
                val (_, itemOneId) = insertUnknownRunAndItem(treasurer = treasurer, journalEntryId = entryId, now = now)
                val (_, itemTwoId) = insertUnknownRunAndItem(treasurer = treasurer, journalEntryId = entryId, now = now)

                client
                    .post("/test/accexp/resolve-unknown?itemId=$itemOneId&resolution=CONFIRMED_SENT") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK

                // Same exported_key (deterministic from provider+journalEntryId) as item one just
                // wrote -- must be a readable 409, never an uncaught ExposedSQLException/500.
                client
                    .post("/test/accexp/resolve-unknown?itemId=$itemTwoId&resolution=CONFIRMED_SENT") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                // The failed second resolve's transaction rolled back -- item two is still UNKNOWN,
                // never left half-resolved.
                val itemTwoStatus =
                    transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemTwoId }.single() }[
                        AccountingExportItemTable.status,
                    ]
                itemTwoStatus shouldBe network.lapis.cloud.shared.domain.AccountingExportItemStatus.UNKNOWN
            }
        }

        test("getRun with a well-formed but non-existent id is a 404 for TREASURER (passed the role gate), 403 for MEMBER") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val member = createMember(AccountRole.MEMBER)
                val randomRunId = Uuid.random().toString()
                client.get("/test/accexp/run?id=$randomRunId") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
                client.get("/test/accexp/run?id=$randomRunId") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        // ── Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- ZeroVatExportDisclaimer parameterization ──

        test("getZeroVatDisclaimer(provider): both providers return version and a non-blank sha256") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val lexBody =
                    client
                        .get("/test/accexp/zero-vat-disclaimer?provider=LEXOFFICE") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val sevBody =
                    client
                        .get("/test/accexp/zero-vat-disclaimer?provider=SEVDESK") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                lexBody shouldBe
                    "version=${ZeroVatExportDisclaimer.VERSION};sha256=${ZeroVatExportDisclaimer.sha256For(
                        AccountingExportProvider.LEXOFFICE,
                    )}"
                sevBody shouldBe
                    "version=${ZeroVatExportDisclaimer.VERSION};sha256=${ZeroVatExportDisclaimer.sha256For(
                        AccountingExportProvider.SEVDESK,
                    )}"
            }
        }

        test("sha256For(LEXOFFICE) != sha256For(SEVDESK) -- the disclaimer text names the provider, so the hash must differ") {
            ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE) shouldNotBe
                ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.SEVDESK)
        }

        test(
            "acknowledging the zero-VAT disclaimer for LEXOFFICE does NOT acknowledge it for SEVDESK -- each " +
                "provider's connection carries its OWN quittance, structurally independent",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExportTestExceptionHandlers() }
                    routing { registerAccountingExportTestRoutes(secretBox = SecretBox(TEST_KEY)) }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                client
                    .post(
                        "/test/accexp/acknowledge-zero-vat?provider=LEXOFFICE" +
                            "&sha256=${ZeroVatExportDisclaimer.sha256For(AccountingExportProvider.LEXOFFICE)}",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.OK

                val lexConnected =
                    client
                        .get("/test/accexp/connection?provider=LEXOFFICE") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val sevConnected =
                    client
                        .get("/test/accexp/connection?provider=SEVDESK") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                lexConnected shouldBe "connected=false;zeroVatAcknowledged=true" // no token, only the disclaimer was acked
                sevConnected shouldBe "connected=false;zeroVatAcknowledged=false"
            }
        }
    })

private fun Route.registerAccountingExportTestRoutes(secretBox: SecretBox?) {
    val adapters: Map<AccountingExportProvider, AccountingExportProviderAdapter> = emptyMap()
    val testLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)
    val startLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)
    val previewLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)

    fun service(callCtx: ApplicationCall) =
        AccountingExportService(
            call = callCtx,
            secretBox = secretBox,
            adaptersByProvider = adapters,
            testRateLimiter = testLimiter,
            startExportRateLimiter = startLimiter,
            previewRateLimiter = previewLimiter,
        )

    // Welle V1.4.5.4 "sevDesk-Live-Anbindung": an optional `provider` query param, default
    // LEXOFFICE for every pre-existing call site above (backward compatible) -- lets a test target
    // SEVDESK instead without a second route.
    fun providerParam(callCtx: ApplicationCall): AccountingExportProvider =
        callCtx.request.queryParameters["provider"]?.let { AccountingExportProvider.valueOf(it) } ?: AccountingExportProvider.LEXOFFICE

    get("/test/accexp/connection") {
        val dto = service(call).getConnection(providerParam(call))
        call.respondText("connected=${dto.connected};zeroVatAcknowledged=${dto.zeroVatAcknowledged}")
    }
    post("/test/accexp/set-token") {
        val token = call.request.queryParameters["token"]!!
        val dto = service(call).setToken(provider = providerParam(call), token = token)
        call.respondText("tokenLast4=${dto.tokenLast4}")
    }
    post("/test/accexp/acknowledge-zero-vat") {
        val sha256 = call.request.queryParameters["sha256"]!!
        val dto = service(call).acknowledgeZeroVat(provider = providerParam(call), disclaimerSha256 = sha256)
        call.respondText("zeroVatAcknowledged=${dto.zeroVatAcknowledged}")
    }
    // Welle V1.4.5.4 "sevDesk-Live-Anbindung": new route -- getZeroVatDisclaimer(provider) has no
    // prior test coverage at all (the old parameterless form never needed one).
    get("/test/accexp/zero-vat-disclaimer") {
        val dto = service(call).getZeroVatDisclaimer(providerParam(call))
        call.respondText("version=${dto.version};sha256=${dto.sha256}")
    }
    // Security review Runde 3, Befund 3 (Fund 2026-09-07): mapAccount previously had no RPC-level
    // test coverage at all -- AccountingExportStore.mapAccount was only ever called directly (see
    // createBookableEntry above), never through the service's own validation.
    post("/test/accexp/map-account") {
        val ledgerAccountId = call.request.queryParameters["ledgerAccountId"]!!
        val externalCategoryId = call.request.queryParameters["externalCategoryId"]!!
        val externalCategoryName = call.request.queryParameters["externalCategoryName"]
        service(call).mapAccount(
            provider = AccountingExportProvider.LEXOFFICE,
            ledgerAccountId = ledgerAccountId,
            externalCategoryId = externalCategoryId,
            externalCategoryName = externalCategoryName,
        )
        call.respondText("mapped")
    }
    get("/test/accexp/preview") {
        val dto =
            service(
                call,
            ).previewExport(provider = AccountingExportProvider.LEXOFFICE, from = LocalDate(2026, 1, 1), to = LocalDate(2026, 1, 31))
        call.respondText("exportable=${dto.exportable} blockers=${dto.blockers.joinToString(",") { it.kind.name }}")
    }
    post("/test/accexp/start-invalid-range") {
        val dto =
            service(call).startExport(
                provider = AccountingExportProvider.LEXOFFICE,
                from = LocalDate(2026, 2, 1),
                to = LocalDate(2026, 1, 1),
            )
        call.respondText(dto.id)
    }
    // Happy-path start route -- Fund 2026-09-07 review (Runde 2, Befund 1): the pre-existing
    // start-invalid-range route above only ever exercises the 400 path; nothing called the real
    // AccountingExportService.startExport successfully, so its recomputeRunCounts-after-createRun
    // fix was only tested by calling AccountingExportStore.recomputeRunCounts directly (see
    // AccountingExportPollerTest), never at its actual call site. Same date range as
    // /test/accexp/preview so callers only need to seed one bookable period.
    post("/test/accexp/start") {
        val dto =
            service(call).startExport(
                provider = AccountingExportProvider.LEXOFFICE,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 1, 31),
            )
        call.respondText("id=${dto.id} status=${dto.status}")
    }
    get("/test/accexp/run") {
        val id = call.request.queryParameters["id"]!!
        val dto = service(call).getRun(id)
        call.respondText("id=${dto.id} status=${dto.status}")
    }
    // Security review Fund 2026-09-07 (Runde 5, MAJOR): a second start route with a caller-chosen
    // period -- the fixed-period "/test/accexp/start" above cannot exercise "two independent runs
    // for two independent periods", which is exactly the shape the getLatestRun-vs-listUnknownItems
    // test below needs.
    post("/test/accexp/start-range") {
        val from = LocalDate.parse(call.request.queryParameters["from"]!!)
        val to = LocalDate.parse(call.request.queryParameters["to"]!!)
        val dto = service(call).startExport(provider = AccountingExportProvider.LEXOFFICE, from = from, to = to)
        call.respondText("id=${dto.id} status=${dto.status}")
    }
    get("/test/accexp/latest-run") {
        val dto = service(call).getLatestRun(AccountingExportProvider.LEXOFFICE).firstOrNull()
        call.respondText(if (dto == null) "none" else "id=${dto.id} status=${dto.status}")
    }
    post("/test/accexp/abort") {
        val id = call.request.queryParameters["id"]!!
        val dto = service(call).abortRun(id)
        call.respondText("status=${dto.status}")
    }
    post("/test/accexp/retry-failed") {
        val id = call.request.queryParameters["id"]!!
        val dto = service(call).retryFailed(id)
        call.respondText("status=${dto.status}")
    }
    get("/test/accexp/run-items") {
        val id = call.request.queryParameters["id"]!!
        val items = service(call).listRunItems(runId = id, status = null, offset = 0, limit = 200)
        call.respondText(items.joinToString(";") { "id=${it.id} status=${it.status}" })
    }
    // Security review Fund 2026-09-07 (Runde 5, MAJOR).
    get("/test/accexp/unknown-items") {
        val items = service(call).listUnknownItems(provider = AccountingExportProvider.LEXOFFICE, offset = 0, limit = 200)
        call.respondText(items.joinToString(";") { "id=${it.id} runId=${it.runId} status=${it.status}" })
    }
    // Security review Fund 2026-09-07 (Runde 4, Befund 2).
    post("/test/accexp/resolve-unknown") {
        val itemId = call.request.queryParameters["itemId"]!!
        val resolution =
            network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution.valueOf(
                call.request.queryParameters["resolution"]!!,
            )
        val externalVoucherId = call.request.queryParameters["externalVoucherId"]
        val dto = service(call).resolveUnknownItem(itemId = itemId, resolution = resolution, externalVoucherId = externalVoucherId)
        call.respondText("status=${dto.status} externalVoucherId=${dto.externalVoucherId}")
    }
}

private fun StatusPagesConfig.installAccountingExportTestExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
