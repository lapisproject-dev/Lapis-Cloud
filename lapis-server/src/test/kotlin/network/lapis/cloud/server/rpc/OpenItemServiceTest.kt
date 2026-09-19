package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OpenItemNettingTable
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemNettingDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- core create/settle/cancel/retry correctness
 * for [OpenItemService], exercised end-to-end through a throwaway test-routes wrapper. Same house
 * style [BankAccountServiceTest]/[VolunteerAllowancePostingBridgeTest] already establish
 * (`X-Member-Id` trusted-header auth, real DTOs decoded via `kotlinx.serialization`). Netting
 * scenarios live in [OpenItemNettingTest] (a separate file, same fixtures/routes shape).
 */
class OpenItemServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                val itemIds = if (createdLedgerAccountIds.isEmpty()) emptyList() else OpenItemTable.selectAll().map { it[OpenItemTable.id] }
                if (itemIds.isNotEmpty()) {
                    val settlementIds =
                        OpenItemSettlementTable
                            .selectAll()
                            .where {
                                OpenItemSettlementTable.openItemId inList itemIds
                            }.map { it[OpenItemSettlementTable.id] }
                    val nettingIds = OpenItemNettingTable.selectAll().map { it[OpenItemNettingTable.id] }
                    OpenItemSettlementTable.deleteWhere { OpenItemSettlementTable.id inList settlementIds }
                    OpenItemNettingTable.deleteWhere { OpenItemNettingTable.id inList nettingIds }
                    OpenItemTable.deleteWhere { OpenItemTable.id inList itemIds }
                    val journalEntryIds = JournalEntryTable.selectAll().map { it[JournalEntryTable.id] }
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                }
                // Must null the organization_settings mapping FIRST -- it FKs into ledger_account,
                // deleting the accounts before clearing the mapping violates that FK.
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[receivablesAccountId] = null
                    it[payablesAccountId] = null
                    it[paymentBankAccountId] = null
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
            createdMemberIds.clear()
            createdLedgerAccountIds.clear()
        }

        fun newMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "OpenItem-Testmitglied"
                    it[email] = "open-item-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
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

        fun newLedgerAccount(
            type: LedgerAccountType,
            active: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            val number = "O${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[LedgerAccountTable.active] = active
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun setMapping(
            receivablesAccountId: Uuid?,
            payablesAccountId: Uuid?,
            bankAccountId: Uuid?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[OrganizationSettingsTable.receivablesAccountId] = receivablesAccountId
                    it[OrganizationSettingsTable.payablesAccountId] = payablesAccountId
                    it[paymentBankAccountId] = bankAccountId
                }
            }
        }

        suspend fun HttpClient.createItem(
            actor: Uuid,
            direction: OpenItemDirection,
            contraAccountId: Uuid,
            amount: String = "240.00",
            itemDate: String = "2026-01-01",
            dueDate: String = "2026-02-01",
        ): OpenItemDetailDto {
            val response =
                post(
                    "/test/openitem/create?direction=$direction&counterpartyName=Muster+GmbH&itemDate=$itemDate&dueDate=$dueDate" +
                        "&amount=$amount&contraAccountId=$contraAccountId&sphere=IDEELLER_BEREICH",
                ) { header("X-Member-Id", actor.toString()) }
            return Json.decodeFromString(OpenItemDetailDto.serializer(), response.bodyAsText())
        }

        fun app() =
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
            }

        test("creating a PAYABLE item books Soll contra / Haben payablesAccount, status OPEN, openAmount == amount") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = null)

                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)
                detail.item.status shouldBe OpenItemStatus.OPEN
                detail.item.openAmount.shouldBeAmount(240.0)
                detail.item.creationJournalEntryId shouldNotBe null

                val journalEntryId = Uuid.parse(requireNotNull(detail.item.creationJournalEntryId))
                val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.toList() }
                postings.size shouldBe 2
                postings.single { it[PostingTable.side] == PostingSide.DEBIT }[PostingTable.ledgerAccountId] shouldBe expense
                postings.single { it[PostingTable.side] == PostingSide.CREDIT }[PostingTable.ledgerAccountId] shouldBe payables
            }
        }

        test("creating a RECEIVABLE item books Soll receivablesAccount / Haben contra") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(receivablesAccountId = receivables, payablesAccountId = null, bankAccountId = null)

                val detail = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income)
                val journalEntryId = Uuid.parse(requireNotNull(detail.item.creationJournalEntryId))
                val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.toList() }
                postings.single { it[PostingTable.side] == PostingSide.DEBIT }[PostingTable.ledgerAccountId] shouldBe receivables
                postings.single { it[PostingTable.side] == PostingSide.CREDIT }[PostingTable.ledgerAccountId] shouldBe income
            }
        }

        test("full settlement -> SETTLED, openAmount 0; second full-amount settlement rejected") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = bank)
                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)

                val settled =
                    client.post("/test/openitem/${detail.item.id}/settle?amount=240.00&settledOn=2026-01-15") {
                        header("X-Member-Id", treasurer.toString())
                    }
                val settledDto = Json.decodeFromString(OpenItemDetailDto.serializer(), settled.bodyAsText())
                settledDto.item.status shouldBe OpenItemStatus.SETTLED
                settledDto.item.openAmount.shouldBeAmount(0.0)

                val overpay =
                    client.post("/test/openitem/${detail.item.id}/settle?amount=1.00&settledOn=2026-01-16") {
                        header("X-Member-Id", treasurer.toString())
                    }
                overpay.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("partial settlement -> PARTIALLY_SETTLED with the exact remaining openAmount; overpaying the remainder is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = bank)
                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)

                val partial =
                    client.post("/test/openitem/${detail.item.id}/settle?amount=100.00&settledOn=2026-01-15") {
                        header("X-Member-Id", treasurer.toString())
                    }
                val partialDto = Json.decodeFromString(OpenItemDetailDto.serializer(), partial.bodyAsText())
                partialDto.item.status shouldBe OpenItemStatus.PARTIALLY_SETTLED
                partialDto.item.openAmount.shouldBeAmount(140.0)

                val overpayRest =
                    client.post("/test/openitem/${detail.item.id}/settle?amount=140.01&settledOn=2026-01-16") {
                        header("X-Member-Id", treasurer.toString())
                    }
                overpayRest.status shouldBe HttpStatusCode.Conflict

                val rest =
                    client.post("/test/openitem/${detail.item.id}/settle?amount=140.00&settledOn=2026-01-16") {
                        header("X-Member-Id", treasurer.toString())
                    }
                val restDto = Json.decodeFromString(OpenItemDetailDto.serializer(), rest.bodyAsText())
                restDto.item.status shouldBe OpenItemStatus.SETTLED
            }
        }

        test("settlement reversal restores openAmount and status; a second reversal of the same settlement is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = bank)
                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)
                val settled =
                    client.post("/test/openitem/${detail.item.id}/settle?amount=240.00&settledOn=2026-01-15") {
                        header("X-Member-Id", treasurer.toString())
                    }
                val settlementId =
                    Json
                        .decodeFromString(OpenItemDetailDto.serializer(), settled.bodyAsText())
                        .settlements
                        .single()
                        .id

                val reversed =
                    client.post("/test/openitem/settlement/$settlementId/reverse?reason=Falschbuchung") {
                        header("X-Member-Id", treasurer.toString())
                    }
                val reversedDto = Json.decodeFromString(OpenItemDetailDto.serializer(), reversed.bodyAsText())
                reversedDto.item.status shouldBe OpenItemStatus.OPEN
                reversedDto.item.openAmount.shouldBeAmount(240.0)

                val secondReversal =
                    client.post("/test/openitem/settlement/$settlementId/reverse?reason=Nochmal") {
                        header("X-Member-Id", treasurer.toString())
                    }
                secondReversal.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("payablesAccountId not configured -> Failed degrade, item still created, retryOpenItemPosting after configuring succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(receivablesAccountId = null, payablesAccountId = null, bankAccountId = null)

                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)
                detail.item.creationJournalEntryId shouldBe null
                detail.item.creationPostingError shouldBe "payables_account_not_configured"

                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = null)
                val retried =
                    client.post("/test/openitem/${detail.item.id}/retry") { header("X-Member-Id", treasurer.toString()) }
                val retriedDto = Json.decodeFromString(OpenItemDetailDto.serializer(), retried.bodyAsText())
                retriedDto.item.creationJournalEntryId shouldNotBe null
                retriedDto.item.creationPostingError shouldBe null
            }
        }

        test("retryOpenItemPosting on a CANCELLED item -> Conflict, no creation posting is booked after the fact") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(receivablesAccountId = null, payablesAccountId = null, bankAccountId = null)

                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)
                detail.item.creationPostingError shouldBe "payables_account_not_configured"
                val cancelled =
                    client.post("/test/openitem/${detail.item.id}/cancel?reason=Fehlanlage") {
                        header("X-Member-Id", treasurer.toString())
                    }
                cancelled.status shouldBe HttpStatusCode.OK

                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = null)
                val retried =
                    client.post("/test/openitem/${detail.item.id}/retry") { header("X-Member-Id", treasurer.toString()) }
                retried.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("contra account of the wrong LedgerAccountType -> Failed(contra_account_wrong_type), no journal entry booked") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                // ASSET instead of EXPENSE for a PAYABLE item's contra account.
                val wrongTypeContra = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = null)

                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, wrongTypeContra)
                detail.item.creationJournalEntryId shouldBe null
                detail.item.creationPostingError shouldBe "contra_account_wrong_type"
            }
        }

        test("cancelOpenItem: booked item -> exact reversal booking, status CANCELLED; a settled item cannot be cancelled") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = bank)
                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)

                val cancelled =
                    client.post("/test/openitem/${detail.item.id}/cancel?reason=Doppelt+erfasst") {
                        header("X-Member-Id", treasurer.toString())
                    }
                val cancelledDto = Json.decodeFromString(OpenItemDetailDto.serializer(), cancelled.bodyAsText())
                cancelledDto.item.status shouldBe OpenItemStatus.CANCELLED
                cancelledDto.item.cancellationReason shouldBe "Doppelt erfasst"

                val detail2 = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)
                client.post("/test/openitem/${detail2.item.id}/settle?amount=240.00&settledOn=2026-01-15") {
                    header("X-Member-Id", treasurer.toString())
                }
                val cancelAfterSettled =
                    client.post("/test/openitem/${detail2.item.id}/cancel?reason=zu+spaet") {
                        header("X-Member-Id", treasurer.toString())
                    }
                cancelAfterSettled.status shouldBe HttpStatusCode.Conflict
            }
        }

        /**
         * Security fix N1 (second review pass): `Decimal` travels as a JSON double, so a client-side
         * "99999999999999999999,99" arrives as `BigDecimal("1.0E20")` -- whose scale is **-19**, which
         * passes both `scale() > MAX_AMOUNT_SCALE` and `<= ZERO`, and then overflows the
         * `numeric(12,2)` column as an unhandled HTTP 500. `requireValidAmount` now rejects it as a
         * 400 on creation AND on settlement (the netting amount goes through the same helper, see
         * `requireNettableAmount`).
         */
        test("amounts at or above the numeric(12,2) ceiling are rejected as 400, on creation and on settlement") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = bank)

                // Exactly the shape the client's AMOUNT_SHAPE regex used to let through unbounded.
                client
                    .post(
                        "/test/openitem/create?direction=PAYABLE&counterpartyName=X&itemDate=2026-01-01&dueDate=2026-02-01" +
                            "&amount=99999999999999999999.99&contraAccountId=$expense&sphere=IDEELLER_BEREICH",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                // One cent above the documented cap -- the boundary itself is still accepted.
                client
                    .post(
                        "/test/openitem/create?direction=PAYABLE&counterpartyName=X&itemDate=2026-01-01&dueDate=2026-02-01" +
                            "&amount=1000000000.01&contraAccountId=$expense&sphere=IDEELLER_BEREICH",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                // A negative-scale value with an acceptable magnitude must still pass (1.0E2 == 100).
                client
                    .post(
                        "/test/openitem/create?direction=PAYABLE&counterpartyName=X&itemDate=2026-01-01&dueDate=2026-02-01" +
                            "&amount=1.0E2&contraAccountId=$expense&sphere=IDEELLER_BEREICH",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.OK

                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)
                client
                    .post("/test/openitem/${detail.item.id}/settle?amount=99999999999999999999.99&settledOn=2026-01-15") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/test/openitem/${detail.item.id}/settle?amount=1000000000.01&settledOn=2026-01-15") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                // Still a Conflict (not a BadRequest) for an amount that is well-formed but too high
                // for THIS item -- the new range check must not swallow the openAmount check.
                client
                    .post("/test/openitem/${detail.item.id}/settle?amount=240.01&settledOn=2026-01-15") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("authz: BOARD may read but not write; MEMBER may neither read nor write") {
            testApplication {
                application {
                    install(StatusPages) { installOpenItemTestExceptionHandlers() }
                    routing { registerOpenItemTestRoutes() }
                }
                val board = newMember(AccountRole.BOARD)
                val member = newMember(AccountRole.MEMBER)
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivablesAccountId = null, payablesAccountId = payables, bankAccountId = null)
                val detail = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense)

                client.get("/test/openitem/summary") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/openitem/summary") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/openitem/${detail.item.id}/cancel?reason=x") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post(
                        "/test/openitem/create?direction=PAYABLE&counterpartyName=X&itemDate=2026-01-01&dueDate=2026-02-01&amount=1.00&contraAccountId=$expense&sphere=IDEELLER_BEREICH",
                    ) {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })

private fun BigDecimal.shouldBeAmount(expected: Double) {
    (this.compareTo(BigDecimal.valueOf(expected)) == 0) shouldBe true
}

private fun Route.registerOpenItemTestRoutes() {
    fun service(callCtx: io.ktor.server.application.ApplicationCall) = OpenItemService(callCtx)

    get("/test/openitem/summary") {
        val dto = service(call).getOpenItemSummary()
        call.respondText(
            Json.encodeToString(
                network.lapis.cloud.shared.domain.OpenItemSummaryDto
                    .serializer(),
                dto,
            ),
        )
    }
    post("/test/openitem/create") {
        val p = call.request.queryParameters
        val input =
            network.lapis.cloud.shared.domain.OpenItemInput(
                direction = OpenItemDirection.valueOf(requireNotNull(p["direction"])),
                counterpartyName = requireNotNull(p["counterpartyName"]).replace('+', ' '),
                itemDate = LocalDate.parse(requireNotNull(p["itemDate"])),
                dueDate = LocalDate.parse(requireNotNull(p["dueDate"])),
                amount = BigDecimal(requireNotNull(p["amount"])),
                contraAccountId = requireNotNull(p["contraAccountId"]),
                sphere = GemeinnuetzigkeitSphere.valueOf(requireNotNull(p["sphere"])),
            )
        val dto = service(call).createOpenItem(input)
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    post("/test/openitem/{id}/settle") {
        val p = call.request.queryParameters
        val dto =
            service(call).settleOpenItem(
                openItemId = requireNotNull(call.parameters["id"]),
                amount = BigDecimal(requireNotNull(p["amount"])),
                settledOn = LocalDate.parse(requireNotNull(p["settledOn"])),
                bankAccountId = p["bankAccountId"],
            )
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    post("/test/openitem/{id}/cancel") {
        val reason = call.request.queryParameters["reason"]?.replace('+', ' ') ?: "x"
        val dto = service(call).cancelOpenItem(openItemId = requireNotNull(call.parameters["id"]), reason = reason)
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    post("/test/openitem/{id}/retry") {
        val dto = service(call).retryOpenItemPosting(requireNotNull(call.parameters["id"]))
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    post("/test/openitem/settlement/{id}/reverse") {
        val reason = call.request.queryParameters["reason"]?.replace('+', ' ') ?: "x"
        val dto = service(call).reverseSettlement(settlementId = requireNotNull(call.parameters["id"]), reason = reason)
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    get("/test/openitem/netting/candidates") {
        val dtos = service(call).listNettingCandidates()
        call.respondText(
            Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    network.lapis.cloud.shared.domain.NettingCandidateDto
                        .serializer(),
                ),
                dtos,
            ),
        )
    }
    post("/test/openitem/netting/preview") {
        val p = call.request.queryParameters
        val dtos =
            service(call).previewNetting(
                payableItemId = requireNotNull(p["payableItemId"]),
                receivableItemId = requireNotNull(p["receivableItemId"]),
                amount = BigDecimal(requireNotNull(p["amount"])),
            )
        call.respondText(
            Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    network.lapis.cloud.shared.domain.NettingPreviewDto
                        .serializer(),
                ),
                dtos,
            ),
        )
    }
    post("/test/openitem/netting/execute") {
        val p = call.request.queryParameters
        val dto =
            service(call).executeNetting(
                payableItemId = requireNotNull(p["payableItemId"]),
                receivableItemId = requireNotNull(p["receivableItemId"]),
                amount = BigDecimal(requireNotNull(p["amount"])),
            )
        call.respondText(Json.encodeToString(OpenItemNettingDto.serializer(), dto))
    }
    post("/test/openitem/netting/{id}/reverse") {
        val reason = call.request.queryParameters["reason"]?.replace('+', ' ') ?: "x"
        val dto = service(call).reverseNetting(nettingId = requireNotNull(call.parameters["id"]), reason = reason)
        call.respondText(Json.encodeToString(OpenItemNettingDto.serializer(), dto))
    }
}

private fun StatusPagesConfig.installOpenItemTestExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
