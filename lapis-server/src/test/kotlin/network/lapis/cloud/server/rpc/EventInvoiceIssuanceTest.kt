package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemStatus
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen" -- [EventService.issueEventInvoice]
 * exercised end to end over the real RPC surface, same "throwaway test routes + `X-Member-Id`
 * header" house style [OpenItemServiceTest]/[EventServiceRpcTest] already establish. A separate
 * file (not an addition to the already-818-line [EventServiceRpcTest]) -- this wave's own fixtures
 * (a ledger income account, `organization_settings.event_income_account_id`, a fee-bearing
 * registration) are self-contained and orthogonal to that file's room/cancellation scenarios.
 */
class EventInvoiceIssuanceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    val openItemIds =
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.eventId inList createdEventIds }
                            .mapNotNull { it[EventRegistrationTable.openItemId] }
                    EventRegistrationTable.update({ EventRegistrationTable.eventId inList createdEventIds }) {
                        it[openItemId] = null
                        it[invoiceIssuedAt] = null
                        it[invoiceIssuedBy] = null
                    }
                    if (openItemIds.isNotEmpty()) {
                        val journalEntryIds =
                            OpenItemTable
                                .selectAll()
                                .where { OpenItemTable.id inList openItemIds }
                                .mapNotNull { it[OpenItemTable.creationJournalEntryId] }
                        OpenItemTable.deleteWhere { OpenItemTable.id inList openItemIds }
                        if (journalEntryIds.isNotEmpty()) {
                            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                        }
                    }
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                // Must null the mapping FIRST -- it FKs into ledger_account.
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[eventIncomeAccountId] = null
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
            createdEventIds.clear()
            createdLedgerAccountIds.clear()
            createdMemberIds.clear()
        }

        fun newMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Event-Invoice-Testmitglied"
                    it[email] = "event-invoice-${Uuid.random()}@example.org"
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

        fun newEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "invoice-test-$id"
                    it[title] = "Rechnungs-Testveranstaltung"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = DbClock.nowLocalDateTime()
                    it[endsAt] = DbClock.nowLocalDateTime()
                    it[capacity] = null
                    it[feeAmount] = BigDecimal("25.00")
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun newIncomeLedgerAccount(): Uuid {
            val id = Uuid.random()
            val number = "I${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Test-Ertragskonto $number"
                    it[accountClass] = 4
                    it[type] = LedgerAccountType.INCOME
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun setEventIncomeAccount(accountId: Uuid?) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[eventIncomeAccountId] = accountId
                }
            }
        }

        fun newRegistration(
            eventId: Uuid,
            memberId: Uuid? = null,
            guestName: String? = null,
            guestEmail: String? = null,
            status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
            feeAmount: BigDecimal = BigDecimal("25.00"),
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val activeKey =
                if (status in network.lapis.cloud.shared.domain.EventRegistrationStatusSets.INACTIVE) {
                    null
                } else {
                    memberId?.let { "m:$it" } ?: "g:$guestEmail"
                }
            transaction {
                EventRegistrationTable.insert {
                    it[EventRegistrationTable.id] = id
                    it[EventRegistrationTable.eventId] = eventId
                    it[EventRegistrationTable.memberId] = memberId
                    it[EventRegistrationTable.guestName] = guestName
                    it[EventRegistrationTable.guestEmail] = guestEmail
                    it[activeParticipantKey] = activeKey
                    it[EventRegistrationTable.status] = status
                    it[EventRegistrationTable.feeAmount] = feeAmount
                    it[holdExpiresAt] = if (status == EventRegistrationStatus.PENDING_PAYMENT) now else null
                    it[waitlistPosition] = if (status == EventRegistrationStatus.WAITLISTED) 1 else null
                    it[cancelTokenSha256] = null
                    it[registeredAt] = now
                    it[confirmedAt] = if (status == EventRegistrationStatus.CONFIRMED) now else null
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
            return id
        }

        class NoopMailTransport : MailTransport {
            override suspend fun send(
                to: String,
                subject: String,
                plainTextBody: String,
                htmlBody: String,
            ): MailSendOutcome = MailSendOutcome.Sent
        }

        fun Route.registerEventInvoiceTestRoutes(mailDispatcher: MailDispatcher) {
            fun serviceFor(call: io.ktor.server.application.ApplicationCall) =
                EventService(
                    call = call,
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher = mailDispatcher,
                    writeRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                    checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                )
            post("/test/event-invoice/issue") {
                val p = call.request.queryParameters
                val input =
                    network.lapis.cloud.shared.domain.EventInvoiceRequestDto(
                        registrationId = requireNotNull(p["registrationId"]),
                        billingStreet = p["billingStreet"],
                        billingPostalCode = p["billingPostalCode"],
                        billingCity = p["billingCity"],
                        billingCountry = p["billingCountry"],
                        dueInDays = p["dueInDays"]?.toInt() ?: 14,
                    )
                val dto = serviceFor(call).issueEventInvoice(input)
                call.respondText(Json.encodeToString(EventRegistrationDto.serializer(), dto))
            }
        }

        fun statusPages(): StatusPagesConfig.() -> Unit =
            {
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
            }

        fun mailDispatcher() = MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        test("TREASURER can issue an invoice for a CONFIRMED member registration -- books a RECEIVABLE open item") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val member = newMember(AccountRole.MEMBER)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(treasurer)
                val registrationId = newRegistration(eventId = eventId, memberId = member, feeAmount = BigDecimal("25.00"))

                val response =
                    client.post(
                        "/test/event-invoice/issue?registrationId=$registrationId&billingStreet=Teststrasse+1" +
                            "&billingPostalCode=38100&billingCity=Braunschweig&billingCountry=Deutschland&dueInDays=14",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString(EventRegistrationDto.serializer(), response.bodyAsText())
                (dto.openItemId != null) shouldBe true
                (dto.invoiceIssuedAt != null) shouldBe true
                dto.billingCity shouldBe "Braunschweig"

                transaction {
                    val openItemId = Uuid.parse(requireNotNull(dto.openItemId))
                    val row = OpenItemTable.selectAll().where { OpenItemTable.id eq openItemId }.single()
                    row[OpenItemTable.direction] shouldBe OpenItemDirection.RECEIVABLE
                    row[OpenItemTable.amount] shouldBe BigDecimal("25.00")
                    row[OpenItemTable.status] shouldBe OpenItemStatus.OPEN
                    row[OpenItemTable.contraAccountId] shouldBe incomeAccountId
                }
            }
        }

        test("ADMIN can issue an invoice for a CONFIRMED guest registration") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val admin = newMember(AccountRole.ADMIN)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(admin)
                val registrationId =
                    newRegistration(
                        eventId = eventId,
                        guestName = "Gast Testperson",
                        guestEmail = "gast-${Uuid.random()}@example.org",
                        feeAmount = BigDecimal("25.00"),
                    )

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString(EventRegistrationDto.serializer(), response.bodyAsText())
                (dto.openItemId != null) shouldBe true
            }
        }

        test("MEMBER is rejected (role gate)") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val organizer = newMember(AccountRole.ADMIN)
                val member = newMember(AccountRole.MEMBER)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(organizer)
                val registrationId = newRegistration(eventId = eventId, memberId = member)

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", member.toString())
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD is rejected (role gate -- only TREASURER/ADMIN, unlike other financial-doc reads)") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val board = newMember(AccountRole.BOARD)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(board)
                val registrationId =
                    newRegistration(eventId = eventId, guestName = "Gast", guestEmail = "gast2-${Uuid.random()}@example.org")

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", board.toString())
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("rejects a WAITLISTED registration (not CONFIRMED)") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(treasurer)
                val registrationId =
                    newRegistration(
                        eventId = eventId,
                        guestName = "Gast",
                        guestEmail = "gast3-${Uuid.random()}@example.org",
                        status = EventRegistrationStatus.WAITLISTED,
                    )

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rejects a registration that already has an open item") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(treasurer)
                val registrationId =
                    newRegistration(eventId = eventId, guestName = "Gast", guestEmail = "gast4-${Uuid.random()}@example.org")

                val first =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", treasurer.toString())
                    }
                first.status shouldBe HttpStatusCode.OK

                val second =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", treasurer.toString())
                    }
                second.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rejects a registration with feeAmount == 0") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(treasurer)
                val registrationId =
                    newRegistration(
                        eventId = eventId,
                        guestName = "Gast",
                        guestEmail = "gast5-${Uuid.random()}@example.org",
                        feeAmount = BigDecimal.ZERO,
                    )

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rejects an oversized billingStreet (BadRequestException, not a raw 500)") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                val incomeAccountId = newIncomeLedgerAccount()
                setEventIncomeAccount(incomeAccountId)
                val eventId = newEvent(treasurer)
                val registrationId =
                    newRegistration(eventId = eventId, guestName = "Gast", guestEmail = "gast6-${Uuid.random()}@example.org")
                val tooLong = "x".repeat(201)

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId&billingStreet=$tooLong") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("degrades cleanly (409) when organization_settings.event_income_account_id is not configured") {
            testApplication {
                application {
                    install(StatusPages, statusPages())
                    routing { registerEventInvoiceTestRoutes(mailDispatcher()) }
                }
                val treasurer = newMember(AccountRole.TREASURER)
                setEventIncomeAccount(null)
                val eventId = newEvent(treasurer)
                val registrationId =
                    newRegistration(eventId = eventId, guestName = "Gast", guestEmail = "gast7-${Uuid.random()}@example.org")

                val response =
                    client.post("/test/event-invoice/issue?registrationId=$registrationId") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }
    })
