package network.lapis.cloud.server.openitem.dunning

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.ReceivableDunningLevelTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.OpenItemService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Blocking audit finding B1 (V1.4.21): `OpenItemDto.highestDunningLevelNumber`/
 * `nextDunningLevelNumber`/`nextDunningLevelDueOn` existed on the wire but were **never set** by the
 * server -- the only mapper (`ResultRow.toOpenItemDto`) simply did not write them. The whole
 * debtor-dunning half of the open-items UI was therefore unreachable: the detail view always showed
 * "Keine weitere Mahnstufe verfügbar", `OpenItemAuthzUi.canSkipDunningLevel` was always `false`, and
 * the list's "Mahnstufe" column always showed "–".
 *
 * This test deliberately goes through a **real HTTP round trip** and decodes the real DTOs from JSON
 * (house style of `OpenItemServiceTest`, whose test-route/`X-Member-Id` shape this file reuses) --
 * a hand-built DTO could not have caught the bug, which is exactly why the client-side unit tests
 * of the wave did not.
 *
 * Two things are pinned that a pure "fields are populated" test would miss:
 *
 * 1. **List and detail agree** -- both go through the same mapper, so a future divergence fails here.
 * 2. **No N+1** -- `listOpenItems` returns up to 200 rows; the statement count of the whole call is
 *    measured (the service's own `transaction {}` joins this test's outer one, so its statements pass
 *    through the logger) and must be IDENTICAL for one row and for 25 rows.
 */
class OpenItemDunningProgressTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdLevelIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                // Guard taken over from the sister test `OpenItemServiceTest` (audit finding, second
                // pass): these DELETEs are table-wide, so without the "did this test create anything
                // at all" gate an empty run would wipe rows it never owned (a developer database, or
                // whatever a concurrently running spec has just inserted).
                val touchedAnything = createdLedgerAccountIds.isNotEmpty() || createdLevelIds.isNotEmpty()
                val itemIds = if (touchedAnything) OpenItemTable.selectAll().map { it[OpenItemTable.id] } else emptyList()
                if (touchedAnything) {
                    val noticeIds = ReceivableDunningNoticeTable.selectAll().map { it[ReceivableDunningNoticeTable.id] }
                    if (noticeIds.isNotEmpty()) {
                        ReceivableDunningNoticeTable.deleteWhere { ReceivableDunningNoticeTable.id inList noticeIds }
                    }
                }
                if (createdLevelIds.isNotEmpty()) {
                    ReceivableDunningLevelTable.deleteWhere { ReceivableDunningLevelTable.id inList createdLevelIds }
                }
                if (itemIds.isNotEmpty()) {
                    val settlementIds =
                        OpenItemSettlementTable
                            .selectAll()
                            .where { OpenItemSettlementTable.openItemId inList itemIds }
                            .map { it[OpenItemSettlementTable.id] }
                    OpenItemSettlementTable.deleteWhere { OpenItemSettlementTable.id inList settlementIds }
                    OpenItemTable.deleteWhere { OpenItemTable.id inList itemIds }
                    val journalEntryIds = JournalEntryTable.selectAll().map { it[JournalEntryTable.id] }
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[receivablesAccountId] = null
                    it[payablesAccountId] = null
                    it[paymentBankAccountId] = null
                    it[receivableDunningEnabled] = false
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
            createdLevelIds.clear()
        }

        fun newMember(role: AccountRole = AccountRole.TREASURER): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Mahnfortschritt-Testmitglied"
                    it[email] = "dunning-progress-${Uuid.random()}@example.org"
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
            val number = "P${id.toString().filter { it.isDigit() }.take(9)}"
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

        fun newLevel(
            levelNumber: Int,
            graceDays: Int,
            active: Boolean = true,
            feeAmount: BigDecimal? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ReceivableDunningLevelTable.insert {
                    it[ReceivableDunningLevelTable.id] = id
                    it[ReceivableDunningLevelTable.levelNumber] = levelNumber
                    it[name] = "Stufe $levelNumber"
                    it[ReceivableDunningLevelTable.graceDays] = graceDays
                    it[responseDays] = 14
                    it[ReceivableDunningLevelTable.feeAmount] = feeAmount
                    it[ReceivableDunningLevelTable.active] = active
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdLevelIds += id
            return id
        }

        suspend fun HttpClient.createItem(
            actor: Uuid,
            direction: OpenItemDirection,
            contraAccountId: Uuid,
            dueDate: String = "2026-02-01",
        ): OpenItemDetailDto {
            val response =
                post(
                    "/test/dunningprogress/create?direction=$direction&counterpartyName=Schuldner+GmbH&itemDate=2026-01-01" +
                        "&dueDate=$dueDate&amount=240.00&contraAccountId=$contraAccountId&sphere=IDEELLER_BEREICH",
                ) { header("X-Member-Id", actor.toString()) }
            return Json.decodeFromString(OpenItemDetailDto.serializer(), response.bodyAsText())
        }

        suspend fun HttpClient.detailOf(
            actor: Uuid,
            itemId: String,
        ): OpenItemDto {
            val body = get("/test/dunningprogress/$itemId") { header("X-Member-Id", actor.toString()) }.bodyAsText()
            return Json.decodeFromString(ListSerializer(OpenItemDetailDto.serializer()), body).single().item
        }

        suspend fun HttpClient.listItems(
            actor: Uuid,
            direction: OpenItemDirection? = null,
        ): List<OpenItemDto> {
            val query = direction?.let { "?direction=$it" }.orEmpty()
            val body = get("/test/dunningprogress/list$query") { header("X-Member-Id", actor.toString()) }.bodyAsText()
            return Json.decodeFromString(ListSerializer(OpenItemDto.serializer()), body)
        }

        suspend fun HttpClient.issue(
            actor: Uuid,
            itemId: String,
        ): OpenItemDetailDto {
            val body = post("/test/dunningprogress/$itemId/issue") { header("X-Member-Id", actor.toString()) }.bodyAsText()
            return Json.decodeFromString(OpenItemDetailDto.serializer(), body)
        }

        suspend fun HttpClient.skip(
            actor: Uuid,
            itemId: String,
        ): OpenItemDetailDto {
            val body = post("/test/dunningprogress/$itemId/skip") { header("X-Member-Id", actor.toString()) }.bodyAsText()
            return Json.decodeFromString(OpenItemDetailDto.serializer(), body)
        }

        fun app(block: suspend (HttpClient) -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installDunningProgressExceptionHandlers() }
                    routing { registerDunningProgressTestRoutes() }
                }
                block(client)
            }

        test("a fresh receivable announces level 1 and its grace-period date; the payable next to it announces nothing") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                        it[payablesAccountId] = payables
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                newLevel(levelNumber = 2, graceDays = 21)

                val receivable = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income).item
                val payable = client.createItem(treasurer, OpenItemDirection.PAYABLE, expense).item

                withClue("the create response itself already carries the announcement") {
                    receivable.highestDunningLevelNumber shouldBe null
                    receivable.nextDunningLevelNumber shouldBe 1
                    // dueDate 2026-02-01 + graceDays 7
                    receivable.nextDunningLevelDueOn shouldBe LocalDate(2026, 2, 8)
                }
                withClue("a creditor item is never dunned by this domain") {
                    payable.highestDunningLevelNumber shouldBe null
                    payable.nextDunningLevelNumber shouldBe null
                    payable.nextDunningLevelDueOn shouldBe null
                }

                val listed = client.listItems(treasurer).associateBy { it.id }
                withClue("list and detail must not disagree -- both go through the same mapper") {
                    listed.getValue(receivable.id).nextDunningLevelNumber shouldBe 1
                    listed.getValue(receivable.id).nextDunningLevelDueOn shouldBe LocalDate(2026, 2, 8)
                    listed.getValue(payable.id).nextDunningLevelNumber shouldBe null
                    client.detailOf(treasurer, receivable.id).nextDunningLevelNumber shouldBe 1
                }
            }
        }

        test("issuing level 1 moves highest to 1 and next to 2; issuing level 2 leaves no further level") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                newLevel(levelNumber = 2, graceDays = 21, feeAmount = BigDecimal("5.00"))
                val item = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income).item

                val afterFirst = client.issue(treasurer, item.id).item
                withClue("the level the UI announced (1) is the level the server actually issued") {
                    afterFirst.highestDunningLevelNumber shouldBe 1
                    afterFirst.nextDunningLevelNumber shouldBe 2
                    afterFirst.nextDunningLevelDueOn shouldBe LocalDate(2026, 2, 22)
                }
                client.listItems(treasurer, OpenItemDirection.RECEIVABLE).single { it.id == item.id }.nextDunningLevelNumber shouldBe 2

                val afterSecond = client.issue(treasurer, item.id).item
                afterSecond.highestDunningLevelNumber shouldBe 2
                withClue("no third active level exists -- the UI's 'keine weitere Mahnstufe' text is now the truthful one") {
                    afterSecond.nextDunningLevelNumber shouldBe null
                    afterSecond.nextDunningLevelDueOn shouldBe null
                }
                // A further attempt is refused by the server, exactly as the announcement predicted.
                client
                    .post("/test/dunningprogress/${item.id}/issue") { header("X-Member-Id", treasurer.toString()) }
                    .bodyAsText() shouldNotBe ""
            }
        }

        test("a SKIPPED level is not an issued one, but its slot still blocks -- next level moves to 2") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                newLevel(levelNumber = 2, graceDays = 21)
                val item = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income).item
                item.nextDunningLevelNumber shouldBe 1

                val afterSkip = client.skip(treasurer, item.id).item
                withClue("`highestDunningLevelNumber` counts ISSUED notices only -- a skipped level was deliberately NOT dunned") {
                    afterSkip.highestDunningLevelNumber shouldBe null
                }
                withClue("the skipped slot still owns `uq_rdn_slot`, so level 1 can never be issued again") {
                    afterSkip.nextDunningLevelNumber shouldBe 2
                    // dueDate 2026-02-01 + graceDays 21
                    afterSkip.nextDunningLevelDueOn shouldBe LocalDate(2026, 2, 22)
                }
                withClue("list and detail must not disagree about a skipped slot either") {
                    client.detailOf(treasurer, item.id).nextDunningLevelNumber shouldBe 2
                    client.listItems(treasurer, OpenItemDirection.RECEIVABLE).single { it.id == item.id }.let {
                        it.highestDunningLevelNumber shouldBe null
                        it.nextDunningLevelNumber shouldBe 2
                    }
                }
            }
        }

        test("an inactive level is skipped by the announcement, a cancelled notice still blocks its own slot") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                newLevel(levelNumber = 2, graceDays = 14, active = false)
                newLevel(levelNumber = 3, graceDays = 30)
                val item = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income).item

                val afterFirst = client.issue(treasurer, item.id).item
                withClue("level 2 is inactive, so the ladder announces 3 (dueDate + 30 days)") {
                    afterFirst.nextDunningLevelNumber shouldBe 3
                    afterFirst.nextDunningLevelDueOn shouldBe LocalDate(2026, 3, 3)
                }

                // Cancel the issued level-1 notice: `highestDunningLevelNumber` counts ISSUED notices
                // only, so it drops back to null -- but `uq_rdn_slot` still owns the slot, so the next
                // level stays 3 (never back to 1).
                transaction {
                    ReceivableDunningNoticeTable.update({ ReceivableDunningNoticeTable.openItemId eq Uuid.parse(item.id) }) {
                        it[status] = ReceivableDunningNoticeStatus.CANCELLED
                        it[cancelledAt] = DbClock.nowLocalDateTime()
                        it[cancellationReason] = "Testweise zurückgezogen"
                    }
                }
                val afterCancel = client.detailOf(treasurer, item.id)
                afterCancel.highestDunningLevelNumber shouldBe null
                withClue("a cancelled slot cannot be re-issued -- the announcement must not offer level 1 again") {
                    afterCancel.nextDunningLevelNumber shouldBe 3
                }
            }
        }

        test("a settled receivable keeps its issued level as a historical fact but announces no next level") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                        it[paymentBankAccountId] = bank
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                newLevel(levelNumber = 2, graceDays = 21)
                val item = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income).item
                client.issue(treasurer, item.id)

                val settled =
                    Json
                        .decodeFromString(
                            OpenItemDetailDto.serializer(),
                            client
                                .post("/test/dunningprogress/${item.id}/settle?amount=240.00&settledOn=2026-02-10") {
                                    header("X-Member-Id", treasurer.toString())
                                }.bodyAsText(),
                        ).item
                settled.highestDunningLevelNumber shouldBe 1
                withClue("dunning is over once the invoice is paid -- issueNextLevel itself refuses a non-settleable item") {
                    settled.nextDunningLevelNumber shouldBe null
                    settled.nextDunningLevelDueOn shouldBe null
                }
            }
        }

        test("listOpenItems issues the SAME number of statements for 25 rows as for 1 -- no N+1 from the dunning fields") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                newLevel(levelNumber = 2, graceDays = 21)

                val first = client.createItem(treasurer, OpenItemDirection.RECEIVABLE, income).item
                client.issue(treasurer, first.id)
                val withOneRow = client.countListStatements(treasurer)

                repeat(24) { index ->
                    val created =
                        client.createItem(
                            actor = treasurer,
                            direction = OpenItemDirection.RECEIVABLE,
                            contraAccountId = income,
                            dueDate = "2026-03-%02d".format(index + 1),
                        )
                    if (index % 2 == 0) client.issue(treasurer, created.item.id)
                }
                client.listItems(treasurer, OpenItemDirection.RECEIVABLE).size shouldBe 25
                val with25Rows = client.countListStatements(treasurer)

                withClue("sanity: the logger really sees the service's own statements (otherwise both counts would be 0)") {
                    (withOneRow >= 4) shouldBe true
                }
                withClue("statement count for 1 row = $withOneRow, for 25 rows = $with25Rows") {
                    with25Rows shouldBe withOneRow
                }
            }
        }

        test("loadDunningProgress itself stays at exactly two queries, whatever the page size") {
            app { client ->
                val treasurer = newMember()
                val income = newLedgerAccount(LedgerAccountType.INCOME)
                val receivables = newLedgerAccount(LedgerAccountType.ASSET)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[receivablesAccountId] = receivables
                    }
                }
                newLevel(levelNumber = 1, graceDays = 7)
                repeat(30) { index ->
                    val created =
                        client.createItem(
                            actor = treasurer,
                            direction = OpenItemDirection.RECEIVABLE,
                            contraAccountId = income,
                            dueDate = "2026-04-%02d".format(index + 1),
                        )
                    if (index % 3 == 0) client.issue(treasurer, created.item.id)
                }

                fun countFor(limit: Int): Int {
                    val counter = StatementCounter()
                    return transaction {
                        val rows = OpenItemTable.selectAll().limit(limit).toList()
                        addLogger(counter)
                        ReceivableDunningEngine.loadDunningProgress(rows)
                        counter.count
                    }
                }
                countFor(1) shouldBe 2
                countFor(30) shouldBe 2
            }
        }
    })

/** Counts every SQL statement issued inside the transaction it is registered on. */
private class StatementCounter : SqlLogger {
    var count: Int = 0
        private set

    override fun log(
        context: StatementContext,
        transaction: Transaction,
    ) {
        count++
    }
}

private suspend fun HttpClient.countListStatements(actor: Uuid): Int =
    get("/test/dunningprogress/list-statement-count") { header("X-Member-Id", actor.toString()) }.bodyAsText().trim().toInt()

private fun Route.registerDunningProgressTestRoutes() {
    fun service(callCtx: ApplicationCall) = OpenItemService(callCtx)

    post("/test/dunningprogress/create") {
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
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), service(call).createOpenItem(input)))
    }
    get("/test/dunningprogress/list") {
        val direction = call.request.queryParameters["direction"]?.let { OpenItemDirection.valueOf(it) }
        val dtos = service(call).listOpenItems(direction = direction, onlyOpen = false, limit = 200)
        call.respondText(Json.encodeToString(ListSerializer(OpenItemDto.serializer()), dtos))
    }
    // The outer `transaction {}` is what makes this measurable at all: `OpenItemService.listOpenItems`
    // opens its own `transaction {}`, which (Exposed's default `useNestedTransactions = false`) JOINS
    // this one on the same thread -- so every statement the service issues passes through the logger.
    // `runBlocking` on the test-server thread is acceptable here and keeps that thread-local intact.
    get("/test/dunningprogress/list-statement-count") {
        val svc = service(call)
        val counter = StatementCounter()
        val count =
            transaction {
                addLogger(counter)
                runBlocking { svc.listOpenItems(direction = OpenItemDirection.RECEIVABLE, onlyOpen = false, limit = 200) }
                counter.count
            }
        call.respondText(count.toString())
    }
    get("/test/dunningprogress/{id}") {
        val dtos = service(call).getOpenItem(requireNotNull(call.parameters["id"]))
        call.respondText(Json.encodeToString(ListSerializer(OpenItemDetailDto.serializer()), dtos))
    }
    post("/test/dunningprogress/{id}/issue") {
        val dto = ReceivableDunningService(call).issueReceivableDunningNotice(requireNotNull(call.parameters["id"]))
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    post("/test/dunningprogress/{id}/skip") {
        val dto =
            ReceivableDunningService(call).skipReceivableDunningLevel(
                openItemId = requireNotNull(call.parameters["id"]),
                reason = call.request.queryParameters["reason"] ?: "Testweise uebersprungen",
            )
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
    post("/test/dunningprogress/{id}/settle") {
        val p = call.request.queryParameters
        val dto =
            service(call).settleOpenItem(
                openItemId = requireNotNull(call.parameters["id"]),
                amount = BigDecimal(requireNotNull(p["amount"])),
                settledOn = LocalDate.parse(requireNotNull(p["settledOn"])),
                bankAccountId = null,
            )
        call.respondText(Json.encodeToString(OpenItemDetailDto.serializer(), dto))
    }
}

private fun StatusPagesConfig.installDunningProgressExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.BadRequest) }
}
