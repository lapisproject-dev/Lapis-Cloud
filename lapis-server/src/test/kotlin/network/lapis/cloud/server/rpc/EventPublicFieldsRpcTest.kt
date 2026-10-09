package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.BadRequestException
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
 * Welle V1.9.82 (review follow-up) -- the three new public fields `summary`, `coverImageAlt` and `onlineUrlPublic` (plus the
 * `imported` flag in the [network.lapis.cloud.shared.domain.EventDto] mapping) are wired through six call sites in
 * `EventService` and `EventSeriesMaterializer`. Every other test writes straight into `EventTable`, so passing a wrong value
 * (e.g. `summary = null`) at one of those call sites would silently clear the teaser on every form save and stay green.
 * This spec drives the real RPC methods (same throwaway-route house style as [EventServiceSeriesRpcTest]).
 */
class EventPublicFieldsRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdSeriesIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdSeriesIds.isNotEmpty()) {
                    val seriesEventIds =
                        EventTable.selectAll().where { EventTable.seriesId inList createdSeriesIds }.map { it[EventTable.id] }
                    if (seriesEventIds.isNotEmpty()) EventRegistrationTable.deleteWhere { eventId inList seriesEventIds }
                    EventTable.deleteWhere { seriesId inList createdSeriesIds }
                    // a FOLLOWING split creates a new series that points back at the original one
                    EventSeriesTable.update({ EventSeriesTable.id inList createdSeriesIds }) { it[splitFromSeriesId] = null }
                    EventSeriesTable.deleteWhere { id inList createdSeriesIds }
                }
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PublicFieldsRpcTest Mitglied"
                    it[email] = "public-fields-rpc-test-$id@example.org"
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

        fun insertImportedEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "public-fields-rpc-test-$id"
                    it[title] = "Importierte Veranstaltung"
                    it[description] = "alt"
                    it[locationText] = "Altort"
                    it[onlineUrl] = null
                    it[onlineUrlPublic] = false
                    it[summary] = "Alter Teaser"
                    it[coverImageAlt] = null
                    it[startsAt] = LocalDateTime(2031, 3, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2031, 3, 1, 20, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                    it[imported] = true
                }
            }
            createdEventIds += id
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

        fun inputFrom(
            call: ApplicationCall,
            title: String,
            startsAt: LocalDateTime,
            endsAt: LocalDateTime,
        ): EventInput {
            val q = call.request.queryParameters
            return EventInput(
                title = title,
                description = "test",
                locationText = "Testort",
                onlineUrl = q["onlineUrl"],
                startsAt = startsAt,
                endsAt = endsAt,
                capacity = null,
                feeAmount = BigDecimal.ZERO,
                feeCurrency = "EUR",
                visibility = EventVisibility.PUBLIC,
                registrationClosesAt = null,
                summary = q["summary"],
                coverImageAlt = q["alt"],
                onlineUrlPublic = q["pub"] == "true",
            )
        }

        fun fmt(
            summary: String?,
            alt: String?,
            pub: Boolean,
            imported: Boolean,
        ) = "${summary ?: "~"}|${alt ?: "~"}|$pub|$imported"

        fun Route.registerRoutes(mailDispatcher: MailDispatcher) {
            fun serviceFor(call: ApplicationCall) =
                EventService(
                    call = call,
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher = mailDispatcher,
                    writeRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                    checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                    seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                )
            val start = LocalDateTime(2031, 5, 6, 18, 0)
            val end = LocalDateTime(2031, 5, 6, 20, 0)
            post("/t/create") {
                val dto = serviceFor(call).createEvent(inputFrom(call, "Felder-Test", start, end))
                createdEventIds += Uuid.parse(dto.id)
                call.respondText("${dto.id}|" + fmt(dto.summary, dto.coverImageAlt, dto.onlineUrlPublic, dto.imported))
            }
            post("/t/{id}/update") {
                val dto =
                    serviceFor(
                        call,
                    ).updateEvent(id = call.parameters["id"]!!, input = inputFrom(call, "Felder-Test (geändert)", start, end))
                call.respondText(fmt(dto.summary, dto.coverImageAlt, dto.onlineUrlPublic, dto.imported))
            }
            post("/t/series/create") {
                val dto =
                    serviceFor(call).createEventSeries(
                        input = inputFrom(call, "Felder-Serie", LocalDateTime(2028, 1, 4, 19, 0), LocalDateTime(2028, 1, 4, 21, 0)),
                        rule =
                            RecurrenceRuleInput(
                                frequency = RecurrenceFrequency.WEEKLY,
                                byWeekdays = setOf(RecurrenceWeekday.TU),
                                count = 4,
                            ),
                    )
                createdSeriesIds += Uuid.parse(dto.seriesId)
                call.respondText(dto.seriesId)
            }
            post("/t/series/{eventId}/update") {
                val scope = EventSeriesEditScope.valueOf(call.request.queryParameters["scope"]!!)
                val originalStart = LocalDateTime.parse(call.request.queryParameters["startsAt"]!!)
                val dto =
                    serviceFor(call).updateSeriesEvent(
                        eventId = call.parameters["eventId"]!!,
                        input =
                            inputFrom(
                                call,
                                "Felder-Serie (geändert)",
                                originalStart,
                                originalStart.let { LocalDateTime(it.year, it.month, it.day, 21, 0) },
                            ),
                        scope = scope,
                    )
                call.respondText("${dto.affectedEventCount}")
            }
        }

        suspend fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(organizer: Uuid) -> Unit) {
            testApplication {
                val mailDispatcher =
                    MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application {
                    install(StatusPages) {
                        exception<BadRequestException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing { registerRoutes(mailDispatcher) }
                }
                block(createMember(AccountRole.BOARD))
            }
        }

        test("createEvent stores the NORMALIZED summary, alt text and onlineUrlPublic and returns them in the EventDto") {
            withApp { organizer ->
                val response =
                    client.post(
                        "/t/create?summary=" + "  Ein   Teaser%0Amit%20Umbruch  ".replace(" ", "%20") +
                            "&alt=%20Plakat%20%20mit%20Kerzen%20&onlineUrl=https://meet.example/x&pub=true",
                    ) { header("X-Member-Id", organizer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val (id, summary, alt, pub, imported) = response.bodyAsText().split("|")
                summary shouldBe "Ein Teaser mit Umbruch"
                alt shouldBe "Plakat mit Kerzen"
                pub shouldBe "true"
                imported shouldBe "false"
                transaction {
                    val row = EventTable.selectAll().where { EventTable.id eq Uuid.parse(id) }.single()
                    row[EventTable.summary] shouldBe "Ein Teaser mit Umbruch"
                    row[EventTable.coverImageAlt] shouldBe "Plakat mit Kerzen"
                    row[EventTable.onlineUrlPublic] shouldBe true
                    row[EventTable.imported] shouldBe false
                }
            }
        }

        test("createEvent without the optional fields stores null/false (blank summary and alt normalize to null)") {
            withApp { organizer ->
                val response =
                    client.post("/t/create?summary=%20%20&onlineUrl=https://meet.example/x") {
                        header("X-Member-Id", organizer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().substringAfter("|") shouldBe "~|~|false|false"
            }
        }

        test("updateEvent changes summary, alt and onlineUrlPublic (and clears them when omitted)") {
            withApp { organizer ->
                val id =
                    client
                        .post("/t/create?summary=Alt&alt=AltAlt&onlineUrl=https://meet.example/x&pub=true") {
                            header("X-Member-Id", organizer.toString())
                        }.bodyAsText()
                        .substringBefore("|")
                val changed =
                    client.post("/t/$id/update?summary=Neu&alt=NeuAlt&onlineUrl=https://meet.example/x&pub=false") {
                        header("X-Member-Id", organizer.toString())
                    }
                changed.status shouldBe HttpStatusCode.OK
                changed.bodyAsText() shouldBe "Neu|NeuAlt|false|false"
                transaction {
                    val row = EventTable.selectAll().where { EventTable.id eq Uuid.parse(id) }.single()
                    row[EventTable.summary] shouldBe "Neu"
                    row[EventTable.coverImageAlt] shouldBe "NeuAlt"
                    row[EventTable.onlineUrlPublic] shouldBe false
                }
                val cleared =
                    client.post("/t/$id/update?onlineUrl=https://meet.example/x") { header("X-Member-Id", organizer.toString()) }
                cleared.bodyAsText() shouldBe "~|~|false|false"
            }
        }

        test("updateEvent on an IMPORTED event keeps imported=true while changing the public fields") {
            withApp { organizer ->
                val id = insertImportedEvent(createdBy = organizer)
                val response =
                    client.post("/t/$id/update?summary=Bearbeitet&alt=Bild&onlineUrl=https://meet.example/x&pub=true") {
                        header("X-Member-Id", organizer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Bearbeitet|Bild|true|true"
                transaction {
                    EventTable.selectAll().where { EventTable.id eq id }.single()[EventTable.imported] shouldBe true
                }
            }
        }

        test("onlineUrlPublic=true with an http link is rejected through the RPC call (400) and nothing is stored") {
            withApp { organizer ->
                val rejectedCreate =
                    client.post("/t/create?onlineUrl=http://meet.example/x&pub=true") { header("X-Member-Id", organizer.toString()) }
                rejectedCreate.status shouldBe HttpStatusCode.BadRequest

                val id =
                    client
                        .post("/t/create?onlineUrl=https://meet.example/x&pub=false") { header("X-Member-Id", organizer.toString()) }
                        .bodyAsText()
                        .substringBefore("|")
                val rejectedUpdate =
                    client.post("/t/$id/update?onlineUrl=http://meet.example/x&pub=true") { header("X-Member-Id", organizer.toString()) }
                rejectedUpdate.status shouldBe HttpStatusCode.BadRequest
                transaction {
                    EventTable.selectAll().where { EventTable.id eq Uuid.parse(id) }.single()[EventTable.onlineUrlPublic] shouldBe false
                }
            }
        }

        test("createEventSeries carries all three fields onto every occurrence; updateSeriesEvent(ALL) and (FOLLOWING) re-apply them") {
            withApp { organizer ->
                val seriesId =
                    client
                        .post("/t/series/create?summary=Serien-Teaser&alt=Serien-Alt&onlineUrl=https://meet.example/s&pub=true") {
                            header("X-Member-Id", organizer.toString())
                        }.bodyAsText()

                fun occurrences() =
                    transaction {
                        EventTable
                            .selectAll()
                            .where { EventTable.seriesId eq Uuid.parse(seriesId) }
                            .orderBy(EventTable.seriesOriginalStart)
                            .map {
                                Triple(
                                    it[EventTable.id],
                                    it[EventTable.summary] to it[EventTable.coverImageAlt],
                                    it[EventTable.onlineUrlPublic],
                                )
                            }
                    }
                val created = occurrences()
                created.size shouldBe 4
                created.forEach {
                    it.second shouldBe ("Serien-Teaser" to "Serien-Alt")
                    it.third shouldBe true
                }

                val firstId = created.first().first
                val firstStart = transaction { EventTable.selectAll().where { EventTable.id eq firstId }.single()[EventTable.startsAt] }
                val all =
                    client.post(
                        "/t/series/$firstId/update?scope=ALL&startsAt=$firstStart&summary=Alle-Teaser&alt=Alle-Alt&onlineUrl=https://meet.example/s&pub=false",
                    ) {
                        header("X-Member-Id", organizer.toString())
                    }
                all.status shouldBe HttpStatusCode.OK
                occurrences().forEach {
                    it.second shouldBe ("Alle-Teaser" to "Alle-Alt")
                    it.third shouldBe false
                }

                val afterAll = occurrences()
                val secondId = afterAll[1].first
                val secondStart = transaction { EventTable.selectAll().where { EventTable.id eq secondId }.single()[EventTable.startsAt] }
                val future =
                    client.post(
                        "/t/series/$secondId/update?scope=FOLLOWING&startsAt=$secondStart&summary=Ab-hier&alt=Ab-hier-Alt&onlineUrl=https://meet.example/s&pub=true",
                    ) {
                        header("X-Member-Id", organizer.toString())
                    }
                future.status shouldBe HttpStatusCode.OK
                // the split put the tail into a NEW series -- register it for cleanup
                createdSeriesIds +=
                    transaction {
                        EventTable
                            .selectAll()
                            .where { EventTable.id inList afterAll.map { it.first } }
                            .mapNotNull { it[EventTable.seriesId] }
                            .distinct()
                    }.filterNot { it in createdSeriesIds }
                // the split moved occurrences 2..n to a NEW series, so read all by event id
                val afterFuture =
                    transaction {
                        afterAll.map { (id, _, _) ->
                            val row = EventTable.selectAll().where { EventTable.id eq id }.single()
                            Triple(id, row[EventTable.summary] to row[EventTable.coverImageAlt], row[EventTable.onlineUrlPublic])
                        }
                    }
                afterFuture.size shouldBe 4
                afterFuture[0].second shouldBe ("Alle-Teaser" to "Alle-Alt")
                afterFuture[0].third shouldBe false
                afterFuture.drop(1).size shouldBe 3
                afterFuture.drop(1).forEach {
                    it.second shouldBe ("Ab-hier" to "Ab-hier-Alt")
                    it.third shouldBe true
                }
            }
        }
    })
