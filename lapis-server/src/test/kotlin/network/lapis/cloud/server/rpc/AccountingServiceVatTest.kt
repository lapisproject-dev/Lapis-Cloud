package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PostingSnapshot
import network.lapis.cloud.shared.domain.VatRate
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ENTRY_DATE = "2026-02-10"

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)" -- exercises [AccountingService]'s VAT-related
 * behaviour end to end. Deliberately self-contained (own tiny routes/fixtures, does not reuse
 * [AccountingServiceTest]'s giant shared harness) so it can be reviewed/run independently.
 */
class AccountingServiceVatTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        fun setVatGate(
            vatEnabled: Boolean,
            isKleinunternehmer: Boolean = false,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[OrganizationSettingsTable.vatEnabled] = vatEnabled
                    it[OrganizationSettingsTable.isKleinunternehmer] = isKleinunternehmer
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            setVatGate(vatEnabled = false, isKleinunternehmer = false)
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                    val journalEntryIds =
                        JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList createdMemberIds }.map {
                            it[JournalEntryTable.id]
                        }
                    if (journalEntryIds.isNotEmpty()) {
                        PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                        JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                    }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "VAT Testmitglied"
                    it[MemberTable.email] = email
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

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "VAT-Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun postingsParam(postings: List<PostingInput>): String =
            postings.joinToString(",") { "${it.ledgerAccountId}:${it.side}:${it.amount}:${it.sphere}::${it.vatRate}" }

        fun entryParams(
            postings: List<PostingInput>,
            description: String = "USt-Testbuchung",
        ): String = "date=$ENTRY_DATE&description=$description&postings=${postingsParam(postings)}"

        test("happy path per rate: STANDARD/REDUCED/ZERO all post with correct vatAmount, readable via getJournalEntry") {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-happy@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0100", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4000", LedgerAccountType.INCOME)

                suspend fun postAndRead(rate: VatRate): Pair<String, String> {
                    val postings =
                        listOf(
                            PostingInput(
                                ledgerAccountId = bank.toString(),
                                side = PostingSide.DEBIT,
                                amount = BigDecimal("119.00"),
                                sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            ),
                            PostingInput(
                                ledgerAccountId = income.toString(),
                                side = PostingSide.CREDIT,
                                amount = BigDecimal("119.00"),
                                sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                                vatRate = rate,
                            ),
                        )
                    val id =
                        client
                            .post("/test/vat/post-entry?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }
                            .bodyAsText()
                            .split(":")[0]
                    val read =
                        client.post("/test/vat/get-entry/$id") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                    return id to read
                }

                val (_, standardRead) = postAndRead(VatRate.STANDARD)
                standardRead shouldBe "STANDARD:19.00"

                val (_, reducedRead) = postAndRead(VatRate.REDUCED)
                // gross 119.00 @ 7% -- net = ROUND_HALF_UP(119.00 / 1.07, 2) = 111.21,
                // vat = 119.00 - 111.21 = 7.79 (NOT 7.00 -- that figure is the 107.00-gross
                // example from VatCalculatorTest, not this fixture's 119.00 gross).
                reducedRead shouldBe "REDUCED:7.79"

                val (_, zeroRead) = postAndRead(VatRate.ZERO)
                zeroRead shouldBe "ZERO:0.00"
            }
        }

        test("vat_amount is a SNAPSHOT: mutating posting.amount afterwards does not change getVatReturnPreview's totals") {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-snapshot@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0101", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4001", LedgerAccountType.INCOME)
                val postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = bank.toString(),
                            side = PostingSide.DEBIT,
                            amount = BigDecimal("119.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            ledgerAccountId = income.toString(),
                            side = PostingSide.CREDIT,
                            amount = BigDecimal("119.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            vatRate = VatRate.STANDARD,
                        ),
                    )
                client.post("/test/vat/post-entry?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }

                val before =
                    client
                        .post("/test/vat/preview?from=$ENTRY_DATE&to=$ENTRY_DATE") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()

                // Simulate a changed calculation basis via direct SQL -- the report must NOT re-derive.
                transaction {
                    PostingTable.update({ (PostingTable.ledgerAccountId eq income) }) {
                        it[amount] = BigDecimal("999.00")
                    }
                }

                val after =
                    client
                        .post("/test/vat/preview?from=$ENTRY_DATE&to=$ENTRY_DATE") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                after shouldBe before
            }
        }

        test("DRAFT->POSTED re-freeze: vatRate/vatAmount survive postDraftEntry, audit 'after' snapshot carries both") {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-draft@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0102", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4002", LedgerAccountType.INCOME)
                val postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = bank.toString(),
                            side = PostingSide.DEBIT,
                            amount = BigDecimal("107.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            ledgerAccountId = income.toString(),
                            side = PostingSide.CREDIT,
                            amount = BigDecimal("107.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            vatRate = VatRate.REDUCED,
                        ),
                    )
                val draftId =
                    client
                        .post("/test/vat/save-draft?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .split(":")[0]

                val posted =
                    client.post("/test/vat/post-draft/$draftId") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                posted shouldBe "POSTED"

                val read =
                    client.post("/test/vat/get-entry/$draftId") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                read shouldBe "REDUCED:7.00"

                val afterSnapshotJson =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(draftId))
                            }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                            .toList()
                            .last()[AuditLogEntryTable.afterSnapshot]
                    }
                val afterSnapshot = Json.decodeFromString(JournalEntrySnapshot.serializer(), afterSnapshotJson!!)
                val postingSnapshot = afterSnapshot.postings.first { it.ledgerAccountId == income.toString() }
                postingSnapshot.vatRate shouldBe VatRate.REDUCED
                // Decimal round-trips through a Double on the wire (DecimalSerializer) -- compare via
                // compareTo, not equals/shouldBe, same house idiom BigDecimal comparisons always use
                // in this codebase (a plain equals would fail on a scale-only difference, e.g. 7.0
                // vs. 7.00).
                (postingSnapshot.vatAmount?.compareTo(BigDecimal("7.00")) == 0) shouldBe true
            }
        }

        test(
            "saveDraftEntry rejects a scale>2 amount with BadRequest instead of a 500 " +
                "(regression: VatCalculator.vatAmountOf's RoundingMode.UNNECESSARY used to throw uncaught)",
        ) {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-scale-guard@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0104", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4004", LedgerAccountType.INCOME)
                val postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = bank.toString(),
                            side = PostingSide.DEBIT,
                            amount = BigDecimal("119.567"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            ledgerAccountId = income.toString(),
                            side = PostingSide.CREDIT,
                            amount = BigDecimal("119.567"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            vatRate = VatRate.STANDARD,
                        ),
                    )
                val response =
                    client.post("/test/vat/save-draft?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "saveDraftEntry rejects a non-positive amount with BadRequest instead of a 500 " +
                "(Security Round 2, same regression class as the scale>2 fix above but for the sign: " +
                "a negative amount reaches VatCalculator.vatAmountOf, which can compute a negative " +
                "vat_amount and trip chk_posting_vat_amount_non_negative as an uncaught ExposedSQLException)",
        ) {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-sign-guard@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0106", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4006", LedgerAccountType.INCOME)
                val postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = bank.toString(),
                            side = PostingSide.DEBIT,
                            amount = BigDecimal("-100.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            ledgerAccountId = income.toString(),
                            side = PostingSide.CREDIT,
                            amount = BigDecimal("-100.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            vatRate = VatRate.STANDARD,
                        ),
                    )
                val response =
                    client.post("/test/vat/save-draft?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "postDraftEntry re-checks vatActive() at post time: a draft saved with STANDARD while VAT " +
                "was active, then posted after disableVat, is normalized to UNCLASSIFIED/0.00 -- not carried over",
        ) {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-gate-flip@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0105", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4005", LedgerAccountType.INCOME)
                val postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = bank.toString(),
                            side = PostingSide.DEBIT,
                            amount = BigDecimal("119.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            ledgerAccountId = income.toString(),
                            side = PostingSide.CREDIT,
                            amount = BigDecimal("119.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            vatRate = VatRate.STANDARD,
                        ),
                    )
                val draftId =
                    client
                        .post("/test/vat/save-draft?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .split(":")[0]

                // VAT module switched off (or Kleinunternehmer) between save-draft and post-draft.
                setVatGate(vatEnabled = false)

                val posted =
                    client.post("/test/vat/post-draft/$draftId") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                posted shouldBe "POSTED"

                val read =
                    client.post("/test/vat/get-entry/$draftId") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                read shouldBe "UNCLASSIFIED:0.00"

                val afterSnapshotJson =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(draftId))
                            }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                            .toList()
                            .last()[AuditLogEntryTable.afterSnapshot]
                    }
                val afterSnapshot = Json.decodeFromString(JournalEntrySnapshot.serializer(), afterSnapshotJson!!)
                val postingSnapshot = afterSnapshot.postings.first { it.ledgerAccountId == income.toString() }
                // The GoBD audit 'after' snapshot must match what was actually WRITTEN, not the
                // pre-re-freeze DRAFT values -- regression guard for the before/after-snapshot-
                // divergence bug this test doubles as a fix for.
                postingSnapshot.vatRate shouldBe VatRate.UNCLASSIFIED
                (postingSnapshot.vatAmount?.compareTo(BigDecimal("0.00")) == 0) shouldBe true
            }
        }

        test("Kleinunternehmer short-circuit: applicable=false, KLEINUNTERNEHMER, even with VAT-bearing postings") {
            setVatGate(vatEnabled = true, isKleinunternehmer = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-kleinunternehmer@example.org", AccountRole.TREASURER)
                val preview =
                    client
                        .post("/test/vat/preview?from=$ENTRY_DATE&to=$ENTRY_DATE") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                preview shouldBe "false:KLEINUNTERNEHMER:0.00:0.00:0.00"
            }
        }

        test("vatEnabled=false short-circuit: applicable=false, VAT_DISABLED") {
            setVatGate(vatEnabled = false)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-disabled@example.org", AccountRole.TREASURER)
                val preview =
                    client
                        .post("/test/vat/preview?from=$ENTRY_DATE&to=$ENTRY_DATE") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                preview shouldBe "false:VAT_DISABLED:0.00:0.00:0.00"
            }
        }

        test("server normalizes to UNCLASSIFIED/0.00 when vatEnabled=false, even if the client sends STANDARD") {
            setVatGate(vatEnabled = false)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-manipulated-client@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("VB0103", LedgerAccountType.ASSET)
                val income = createLedgerAccount("VB4003", LedgerAccountType.INCOME)
                val postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = bank.toString(),
                            side = PostingSide.DEBIT,
                            amount = BigDecimal("119.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            ledgerAccountId = income.toString(),
                            side = PostingSide.CREDIT,
                            amount = BigDecimal("119.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                            vatRate = VatRate.STANDARD,
                        ),
                    )
                val id =
                    client
                        .post("/test/vat/post-entry?${entryParams(postings)}") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                val read =
                    client.post("/test/vat/get-entry/$id") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                read shouldBe "UNCLASSIFIED:0.00"
            }
        }

        test("getVatReturnPreview requires ACCOUNTING_READ_ROLES -- a plain member is Forbidden") {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val plainMember = createTestMember("vat-forbidden@example.org", AccountRole.MEMBER)
                val response =
                    client.post("/test/vat/preview?from=$ENTRY_DATE&to=$ENTRY_DATE") { header("X-Member-Id", plainMember.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("getVatReturnPreview rejects from > to with BadRequest") {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-from-after-to@example.org", AccountRole.TREASURER)
                val response =
                    client.post("/test/vat/preview?from=2026-02-10&to=2026-01-01") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "getVatReturnPreview rejects a from/to year near LocalDate's representable minimum with " +
                "BadRequest instead of an uncaught DateTimeException (Security Round 2, MINOR): this method " +
                "derives `LocalDate(from.year - 1, 1, 1)` for the prior-year comparison period, which throws " +
                "an uncaught java.time.DateTimeException (HTTP 500, leaking a java.time-internal message) " +
                "once from.year is at/near Year.MIN_VALUE -- LocalDate.parse itself accepts such a value",
        ) {
            setVatGate(vatEnabled = true)
            testApplication {
                application {
                    install(StatusPages) { installVatTestExceptionHandlers() }
                    routing { registerVatTestRoutes() }
                }
                val treasurer = createTestMember("vat-year-underflow@example.org", AccountRole.TREASURER)
                val response =
                    client.post(
                        "/test/vat/preview?from=-999999999-01-01&to=-999999999-12-31",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a pre-V1.4.13 PostingSnapshot JSON (no vatRate/vatAmount fields) deserializes to UNCLASSIFIED/null") {
            val preV1413Json =
                """
                {"ledgerAccountId":"${Uuid.random()}","side":"DEBIT","amount":100.0,"sphere":"ZWECKBETRIEB","costCenterId":null}
                """.trimIndent()
            val snapshot = Json.decodeFromString(PostingSnapshot.serializer(), preV1413Json)
            snapshot.vatRate shouldBe VatRate.UNCLASSIFIED
            snapshot.vatAmount shouldBe null
        }
    })

private fun StatusPagesConfig.installVatTestExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerVatTestRoutes() {
    post("/test/vat/save-draft") {
        val service = AccountingService(call)
        val dto = service.saveDraftEntry(readVatJournalEntryInput(call))
        call.respondText("${dto.id}:${dto.status}")
    }
    post("/test/vat/post-entry") {
        val service = AccountingService(call)
        val dto = service.postJournalEntry(readVatJournalEntryInput(call))
        call.respondText("${dto.id}:${dto.status}")
    }
    post("/test/vat/post-draft/{id}") {
        val service = AccountingService(call)
        val dto = service.postDraftEntry(call.parameters["id"]!!)
        call.respondText("${dto.status}")
    }
    post("/test/vat/get-entry/{id}") {
        val service = AccountingService(call)
        val dto = service.getJournalEntry(call.parameters["id"]!!)
        // The CREDIT (income) posting's own vatRate/vatAmount -- every fixture in this file posts
        // exactly one INCOME-side posting per entry.
        val incomePosting = dto.postings.first { it.side == PostingSide.CREDIT }
        call.respondText("${incomePosting.vatRate}:${incomePosting.vatAmount}")
    }
    post("/test/vat/preview") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto = service.getVatReturnPreview(from = LocalDate.parse(q["from"]!!), to = LocalDate.parse(q["to"]!!))
        call.respondText("${dto.applicable}:${dto.notApplicableReason}:${dto.totalOutputVat}:${dto.totalInputVat}:${dto.balance}")
    }
}

private suspend fun readVatJournalEntryInput(call: ApplicationCall): JournalEntryInput {
    val q = call.request.queryParameters
    val postings =
        (q["postings"] ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .map { entry ->
                val parts = entry.split(":")
                PostingInput(
                    ledgerAccountId = parts[0],
                    side = PostingSide.valueOf(parts[1]),
                    amount = BigDecimal(parts[2]),
                    sphere = GemeinnuetzigkeitSphere.valueOf(parts[3]),
                    costCenterId = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
                    vatRate = parts.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { VatRate.valueOf(it) } ?: VatRate.UNCLASSIFIED,
                )
            }
    return JournalEntryInput(
        entryDate = LocalDate.parse(q["date"]!!),
        description = q["description"]!!,
        postings = postings,
    )
}
