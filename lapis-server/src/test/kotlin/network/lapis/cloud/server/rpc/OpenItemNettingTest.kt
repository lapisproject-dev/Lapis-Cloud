package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
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
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.NettingPreviewDto
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemInput
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
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- the netting (Verrechnung) heart of
 * [OpenItemService]: pairing, preview (no write), execute, reverse, and the double-netting guard
 * (`uq_ois_netting_item`). Own throwaway routes (same house style as [OpenItemServiceTest], not
 * reused directly across files due to file-private visibility).
 */
class OpenItemNettingTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                val settlementIds = OpenItemSettlementTable.selectAll().map { it[OpenItemSettlementTable.id] }
                val nettingIds = OpenItemNettingTable.selectAll().map { it[OpenItemNettingTable.id] }
                OpenItemSettlementTable.deleteWhere { OpenItemSettlementTable.id inList settlementIds }
                OpenItemNettingTable.deleteWhere { OpenItemNettingTable.id inList nettingIds }
                val itemIds = OpenItemTable.selectAll().map { it[OpenItemTable.id] }
                OpenItemTable.deleteWhere { OpenItemTable.id inList itemIds }
                val journalEntryIds = JournalEntryTable.selectAll().map { it[JournalEntryTable.id] }
                PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[receivablesAccountId] = null
                    it[payablesAccountId] = null
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
                    it[displayName] = "Netting-Testmitglied"
                    it[email] = "open-item-netting-${Uuid.random()}@example.org"
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

        fun newLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            val number = "N${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
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

        fun setMapping(
            receivablesAccountId: Uuid,
            payablesAccountId: Uuid,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[OrganizationSettingsTable.receivablesAccountId] = receivablesAccountId
                    it[OrganizationSettingsTable.payablesAccountId] = payablesAccountId
                }
            }
        }

        suspend fun HttpClient.createItem(
            actor: Uuid,
            direction: OpenItemDirection,
            contraAccountId: Uuid,
            amount: String,
            counterpartyName: String = "Muster GmbH",
        ): OpenItemDetailDto {
            val response =
                post(
                    "/test/netting/create?direction=$direction&counterpartyName=${counterpartyName.replace(' ', '+')}" +
                        "&itemDate=2026-01-01&dueDate=2026-02-01&amount=$amount&contraAccountId=$contraAccountId&sphere=IDEELLER_BEREICH",
                ) { header("X-Member-Id", actor.toString()) }
            return Json.decodeFromString(OpenItemDetailDto.serializer(), response.bodyAsText())
        }

        test("equal amounts: full netting settles both items, one journal entry Soll payables / Haben receivables") {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)

                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "240.00")
                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income, "240.00")

                val response =
                    client.post(
                        "/test/netting/execute?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=240.00",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val netting = Json.decodeFromString(OpenItemNettingDto.serializer(), response.bodyAsText())

                val journalEntryId = Uuid.parse(requireNotNull(netting.journalEntryId))
                val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.toList() }
                postings.size shouldBe 2
                postings.single { it[PostingTable.side] == PostingSide.DEBIT }[PostingTable.ledgerAccountId] shouldBe payables
                postings.single { it[PostingTable.side] == PostingSide.CREDIT }[PostingTable.ledgerAccountId] shouldBe receivables

                val payableAfter =
                    Json.decodeFromString(
                        OpenItemDetailDto.serializer(),
                        client.getItem(itemId = payable.item.id, actor = treasurer),
                    )
                val receivableAfter =
                    Json.decodeFromString(
                        OpenItemDetailDto.serializer(),
                        client.getItem(itemId = receivable.item.id, actor = treasurer),
                    )
                payableAfter.item.status shouldBe OpenItemStatus.SETTLED
                receivableAfter.item.status shouldBe OpenItemStatus.SETTLED
            }
        }

        test(
            "partial netting: payable 240 / receivable 100 -> netting 100 settles the receivable, leaves the payable PARTIALLY_SETTLED with openAmount 140",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)

                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "240.00")
                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income, "100.00")

                val previewResponse =
                    client.post(
                        "/test/netting/preview?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=100.00",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val preview = Json.decodeFromString(ListSerializer(NettingPreviewDto.serializer()), previewResponse.bodyAsText()).single()
                preview.payableOpenAmountAfter.compareTo(BigDecimal("140.00")) shouldBe 0
                preview.receivableOpenAmountAfter.compareTo(BigDecimal.ZERO) shouldBe 0

                // Preview must not have written anything.
                transaction { OpenItemNettingTable.selectAll().count() } shouldBe 0L

                client.post(
                    "/test/netting/execute?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=100.00",
                ) { header("X-Member-Id", treasurer.toString()) }

                val payableAfter =
                    Json.decodeFromString(
                        OpenItemDetailDto.serializer(),
                        client.getItem(itemId = payable.item.id, actor = treasurer),
                    )
                payableAfter.item.status shouldBe OpenItemStatus.PARTIALLY_SETTLED
                payableAfter.item.openAmount.compareTo(BigDecimal("140.00")) shouldBe 0
                val receivableAfter =
                    Json.decodeFromString(
                        OpenItemDetailDto.serializer(),
                        client.getItem(itemId = receivable.item.id, actor = treasurer),
                    )
                receivableAfter.item.status shouldBe OpenItemStatus.SETTLED
            }
        }

        test("negative: netting amount above the nettable maximum is rejected, no rows written") {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)
                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "240.00")
                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income, "100.00")

                val response =
                    client.post(
                        "/test/netting/execute?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=100.01",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                transaction { OpenItemNettingTable.selectAll().count() } shouldBe 0L
                transaction { OpenItemSettlementTable.selectAll().count() } shouldBe 0L
            }
        }

        test("negative: two PAYABLE items are never a netting pair (direction mismatch)") {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)
                val payableA = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "100.00")
                val payableB = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "100.00")

                val response =
                    client.post(
                        "/test/netting/execute?payableItemId=${payableA.item.id}&receivableItemId=${payableB.item.id}&amount=100.00",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "negative (CRITICAL, first review pass): netting is rejected when the payable and receivable belong to DIFFERENT counterparties, even with no crmContactId link on either side",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)
                // Aufrechnung/netting requires Gegenseitigkeit (BGB §387): the SAME two parties
                // owing each other. A payable to "Vendor A" and a receivable from an unrelated
                // "Debtor B" must never be nettable against each other, even though both are
                // otherwise valid, booked, SETTLEABLE items -- see OpenItemService.loadNettingPair.
                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "100.00", counterpartyName = "Vendor A")
                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income, "100.00", counterpartyName = "Debtor B")

                val previewResponse =
                    client.post(
                        "/test/netting/preview?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=100.00",
                    ) { header("X-Member-Id", treasurer.toString()) }
                previewResponse.status shouldBe HttpStatusCode.Conflict

                val executeResponse =
                    client.post(
                        "/test/netting/execute?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=100.00",
                    ) { header("X-Member-Id", treasurer.toString()) }
                executeResponse.status shouldBe HttpStatusCode.Conflict
                transaction { OpenItemNettingTable.selectAll().count() } shouldBe 0L
                transaction { OpenItemSettlementTable.selectAll().count() } shouldBe 0L
                // Neither item's status may have been touched by the rejected attempt.
                transaction {
                    OpenItemTable.selectAll().where { OpenItemTable.id eq payable.item.id.let(Uuid::parse) }.single()[OpenItemTable.status]
                } shouldBe OpenItemStatus.OPEN
                transaction {
                    OpenItemTable
                        .selectAll()
                        .where {
                            OpenItemTable.id eq
                                receivable.item.id.let(
                                    Uuid::parse,
                                )
                        }.single()[OpenItemTable.status]
                } shouldBe OpenItemStatus.OPEN
            }
        }

        test("reverseNetting: exact reversal, openAmount restored, status back to OPEN; a second reversal is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)
                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "240.00")
                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income, "240.00")
                val executeResponse =
                    client.post(
                        "/test/netting/execute?payableItemId=${payable.item.id}&receivableItemId=${receivable.item.id}&amount=240.00",
                    ) { header("X-Member-Id", treasurer.toString()) }
                val netting = Json.decodeFromString(OpenItemNettingDto.serializer(), executeResponse.bodyAsText())

                val reversed =
                    client.post(
                        "/test/netting/${netting.id}/reverse?reason=Falsch+verrechnet",
                    ) { header("X-Member-Id", treasurer.toString()) }
                reversed.status shouldBe HttpStatusCode.OK

                val payableAfter =
                    Json.decodeFromString(
                        OpenItemDetailDto.serializer(),
                        client.getItem(itemId = payable.item.id, actor = treasurer),
                    )
                payableAfter.item.status shouldBe OpenItemStatus.OPEN
                payableAfter.item.openAmount.compareTo(BigDecimal("240.00")) shouldBe 0

                val secondReversal =
                    client.post("/test/netting/${netting.id}/reverse?reason=Nochmal") { header("X-Member-Id", treasurer.toString()) }
                secondReversal.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("listNettingCandidates: matches by crmContactId or counterpartyKey, never two PAYABLE items, never a not-yet-booked item") {
            testApplication {
                application {
                    install(StatusPages) { installNettingTestExceptionHandlers() }
                    routing { registerNettingTestRoutes() }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                setMapping(receivables, payables)

                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "50.00", counterpartyName = "Acme AG")
                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income, "80.00", counterpartyName = "Acme AG")
                // Different counterparty -- must never be paired with either item above.
                client.createItem(treasurer, OpenItemDirection.PAYABLE, expense, "10.00", counterpartyName = "Andere GmbH")

                val response = client.post("/test/netting/candidatesAsPost") { header("X-Member-Id", treasurer.toString()) }
                val candidates = Json.decodeFromString(ListSerializer(NettingCandidateDto.serializer()), response.bodyAsText())
                candidates.size shouldBe 1
                candidates.single().payable.id shouldBe payable.item.id
                candidates.single().receivable.id shouldBe receivable.item.id
                candidates.single().matchedByNameOnly shouldBe true
            }
        }
    })

private suspend fun HttpClient.getItem(
    itemId: String,
    actor: Uuid,
): String = post("/test/netting/get?id=$itemId") { header("X-Member-Id", actor.toString()) }.bodyAsText()

private fun Route.registerNettingTestRoutes() {
    fun service(callCtx: io.ktor.server.application.ApplicationCall) = OpenItemService(callCtx)

    post("/test/netting/create") {
        val p = call.request.queryParameters
        val input =
            OpenItemInput(
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
    post("/test/netting/get") {
        val dtos = service(call).getOpenItem(requireNotNull(call.request.queryParameters["id"]))
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dtos.single()))
    }
    post("/test/netting/candidatesAsPost") {
        val dtos = service(call).listNettingCandidates()
        call.respondText(Json.encodeToString(ListSerializer(NettingCandidateDto.serializer()), dtos))
    }
    post("/test/netting/preview") {
        val p = call.request.queryParameters
        val dtos =
            service(call).previewNetting(
                payableItemId = requireNotNull(p["payableItemId"]),
                receivableItemId = requireNotNull(p["receivableItemId"]),
                amount = BigDecimal(requireNotNull(p["amount"])),
            )
        call.respondText(Json.encodeToString(ListSerializer(NettingPreviewDto.serializer()), dtos))
    }
    post("/test/netting/execute") {
        val p = call.request.queryParameters
        val dto =
            service(call).executeNetting(
                payableItemId = requireNotNull(p["payableItemId"]),
                receivableItemId = requireNotNull(p["receivableItemId"]),
                amount = BigDecimal(requireNotNull(p["amount"])),
            )
        call.respondText(Json.encodeToString(OpenItemNettingDto.serializer(), dto))
    }
    post("/test/netting/{id}/reverse") {
        val reason = call.request.queryParameters["reason"]?.replace('+', ' ') ?: "x"
        val dto = service(call).reverseNetting(nettingId = requireNotNull(call.parameters["id"]), reason = reason)
        call.respondText(Json.encodeToString(OpenItemNettingDto.serializer(), dto))
    }
}

private fun StatusPagesConfig.installNettingTestExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
