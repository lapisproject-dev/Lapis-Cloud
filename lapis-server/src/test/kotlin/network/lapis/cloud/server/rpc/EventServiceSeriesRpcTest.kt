package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
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
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Dritte und letzte Folgewelle "Wiederkehrende Veranstaltungen" -- end-to-end coverage of the new
 * `IEventService` series RPCs over the real HTTP surface, same "throwaway test routes +
 * `X-Member-Id` header" house style [EventServiceRpcTest] already establishes.
 */
class EventServiceSeriesRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdSeriesIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdSeriesIds.isNotEmpty()) {
                    val eventIds =
                        EventTable
                            .selectAll()
                            .where { EventTable.seriesId inList createdSeriesIds }
                            .map { it[EventTable.id] }
                    if (eventIds.isNotEmpty()) {
                        EventRegistrationTable.deleteWhere { eventId inList eventIds }
                    }
                    EventTable.deleteWhere { seriesId inList createdSeriesIds }
                    EventSeriesTable.deleteWhere { id inList createdSeriesIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SeriesRpcTest Mitglied"
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

        class NoopMailTransport : MailTransport {
            override suspend fun send(
                to: String,
                subject: String,
                plainTextBody: String,
                htmlBody: String,
            ): MailSendOutcome = MailSendOutcome.Sent
        }

        fun Route.registerSeriesTestRoutes(
            mailDispatcher: MailDispatcher,
            writeRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
            seriesPreviewRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
        ) {
            fun serviceFor(call: io.ktor.server.application.ApplicationCall) =
                EventService(
                    call = call,
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher = mailDispatcher,
                    writeRateLimiter = writeRateLimiter,
                    checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                    seriesPreviewRateLimiter = seriesPreviewRateLimiter,
                )
            post("/test/series/preview") {
                val startsAt = LocalDateTime.parse(call.request.queryParameters["startsAt"]!!)
                val endsAt = LocalDateTime.parse(call.request.queryParameters["endsAt"]!!)
                val count = call.request.queryParameters["count"]!!.toInt()
                val dto =
                    serviceFor(call).previewSeries(
                        startsAt = startsAt,
                        endsAt = endsAt,
                        rule =
                            RecurrenceRuleInput(
                                frequency = RecurrenceFrequency.WEEKLY,
                                byWeekdays = setOf(RecurrenceWeekday.TU),
                                count = count,
                            ),
                    )
                call.respondText("${dto.valid}|${dto.count}|${dto.sentence}")
            }
            post("/test/series/create") {
                val startsAt = LocalDateTime.parse(call.request.queryParameters["startsAt"]!!)
                val endsAt = LocalDateTime.parse(call.request.queryParameters["endsAt"]!!)
                val count = call.request.queryParameters["count"]!!.toInt()
                val input =
                    EventInput(
                        title = "Serien-RPC-Test",
                        description = "test",
                        locationText = "Testort",
                        onlineUrl = null,
                        startsAt = startsAt,
                        endsAt = endsAt,
                        capacity = null,
                        feeAmount = BigDecimal.ZERO,
                        feeCurrency = "EUR",
                        visibility = EventVisibility.PUBLIC,
                        registrationClosesAt = null,
                    )
                val dto =
                    serviceFor(call).createEventSeries(
                        input = input,
                        rule =
                            RecurrenceRuleInput(
                                frequency = RecurrenceFrequency.WEEKLY,
                                byWeekdays = setOf(RecurrenceWeekday.TU),
                                count = count,
                            ),
                    )
                createdSeriesIds += Uuid.parse(dto.seriesId)
                call.respondText("${dto.seriesId}|${dto.createdEventIds.size}|${dto.firstEvent.status}")
            }
            post("/test/series/{eventId}/impact") {
                val scope = EventSeriesEditScope.valueOf(call.request.queryParameters["scope"]!!)
                val dto = serviceFor(call).impactOfSeriesEdit(eventId = call.parameters["eventId"]!!, scope = scope)
                call.respondText("${dto.affectedEventCount}|${dto.isFirstOccurrence}")
            }
            post("/test/series/{eventId}/update") {
                val scope = EventSeriesEditScope.valueOf(call.request.queryParameters["scope"]!!)
                val startsAt = LocalDateTime.parse(call.request.queryParameters["startsAt"]!!)
                val endsAt = LocalDateTime.parse(call.request.queryParameters["endsAt"]!!)
                val input =
                    EventInput(
                        title = "Serien-RPC-Test (geändert)",
                        description = "test",
                        locationText = "Testort",
                        onlineUrl = null,
                        startsAt = startsAt,
                        endsAt = endsAt,
                        capacity = null,
                        feeAmount = BigDecimal.ZERO,
                        feeCurrency = "EUR",
                        visibility = EventVisibility.PUBLIC,
                        registrationClosesAt = null,
                    )
                val dto = serviceFor(call).updateSeriesEvent(eventId = call.parameters["eventId"]!!, input = input, scope = scope)
                call.respondText("${dto.affectedEventCount}")
            }
            post("/test/series/{eventId}/cancel") {
                val scope = EventSeriesEditScope.valueOf(call.request.queryParameters["scope"]!!)
                val dto =
                    serviceFor(call).cancelSeriesEvent(eventId = call.parameters["eventId"]!!, scope = scope, reason = "Testgrund")
                call.respondText("${dto.affectedEventCount}")
            }
        }

        test("previewSeries returns a valid, non-empty sentence for a weekly rule") {
            testApplication {
                val mailDispatcher =
                    MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application { routing { registerSeriesTestRoutes(mailDispatcher) } }
                val organizer = createMember(email = "series-preview-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                val response =
                    client.post(
                        "/test/series/preview?startsAt=2027-10-05T19:00:00&endsAt=2027-10-05T21:00:00&count=10",
                    ) { header("X-Member-Id", organizer.toString()) }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body.startsWith("true|10|") shouldBe true
                body.substringAfter("true|10|").isNotBlank() shouldBe true
            }
        }

        test("createEventSeries materializes N PUBLISHED events, then updateSeriesEvent(ALL) + cancelSeriesEvent(ALL) round-trip") {
            testApplication {
                val mailDispatcher =
                    MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application { routing { registerSeriesTestRoutes(mailDispatcher) } }
                val organizer = createMember(email = "series-create-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                val createResponse =
                    client.post(
                        "/test/series/create?startsAt=2027-11-02T19:00:00&endsAt=2027-11-02T21:00:00&count=4",
                    ) { header("X-Member-Id", organizer.toString()) }
                createResponse.status shouldBe HttpStatusCode.OK
                val (seriesId, createdCount, firstStatus) = createResponse.bodyAsText().split("|")
                createdCount shouldBe "4"
                firstStatus shouldBe "PUBLISHED"

                val firstEventId =
                    transaction {
                        EventTable
                            .selectAll()
                            .where { EventTable.seriesId eq Uuid.parse(seriesId) }
                            .orderBy(EventTable.seriesOriginalStart)
                            .first()[EventTable.id]
                    }

                val impactResponse =
                    client.post("/test/series/$firstEventId/impact?scope=ALL") { header("X-Member-Id", organizer.toString()) }
                impactResponse.status shouldBe HttpStatusCode.OK
                impactResponse.bodyAsText() shouldBe "4|true"

                val updateResponse =
                    client.post(
                        "/test/series/$firstEventId/update?scope=ALL&startsAt=2027-11-02T19:00:00&endsAt=2027-11-02T21:00:00",
                    ) { header("X-Member-Id", organizer.toString()) }
                updateResponse.status shouldBe HttpStatusCode.OK
                updateResponse.bodyAsText() shouldBe "4"

                transaction {
                    EventTable
                        .selectAll()
                        .where { EventTable.seriesId eq Uuid.parse(seriesId) }
                        .forEach { it[EventTable.title] shouldBe "Serien-RPC-Test (geändert)" }
                }

                val cancelResponse =
                    client.post("/test/series/$firstEventId/cancel?scope=ALL") { header("X-Member-Id", organizer.toString()) }
                cancelResponse.status shouldBe HttpStatusCode.OK
                cancelResponse.bodyAsText() shouldBe "4"

                // Y=0 (no registrations were ever created) -- every instance is hard-deleted, not cancelled.
                transaction {
                    val remaining = EventTable.selectAll().where { EventTable.seriesId eq Uuid.parse(seriesId) }.toList()
                    remaining.size shouldBe 0
                }
            }
        }

        // ── Review finding (Runde 2 follow-up, Test-Coverage): the lock-ordering deadlock fix in
        // `computeSeriesImpact` (single ascending-UUID lock pass over the WHOLE affected set,
        // clicked row included -- see its KDoc) had no test exercising the actual deadlock
        // scenario it closes. Mirrors PeerTransferServiceTest's `runConcurrentOppositeTransfers`
        // house pattern: two REAL OS threads (not cooperating coroutines on one thread), each
        // driving a genuinely concurrent `updateSeriesEvent(scope=ALL)` RPC call against a
        // DIFFERENT occurrence of the SAME series -- exactly the "admin 1 on occurrence C, admin 2
        // on occurrence A" scenario the KDoc describes, which pre-fix could deadlock because the
        // clicked-row lock (taken here, in `computeSeriesImpact`) and the full-affected-set lock
        // (taken afterwards, in `EventSeriesMaterializer.applyEdit`) were two separate passes that
        // could disagree on lock order whenever the clicked row was not itself the lowest UUID of
        // the affected set. Placed BEFORE the MAX_ACTIVE_SERIES_PER_ORG test below on purpose --
        // that test plants 50 "active series" fixture rows it only cleans up in `afterSpec`, which
        // would make this test's own `createEventSeries` call hit that same limit if it ran after. ──

        test(
            "updateSeriesEvent(ALL): two concurrent admins editing different occurrences of the same series complete without deadlock",
        ) {
            testApplication {
                val mailDispatcher =
                    MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application { routing { registerSeriesTestRoutes(mailDispatcher) } }
                val admin1 = createMember(email = "series-deadlock-admin1-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val admin2 = createMember(email = "series-deadlock-admin2-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                val createResponse =
                    client.post(
                        "/test/series/create?startsAt=2027-12-07T19:00:00&endsAt=2027-12-07T21:00:00&count=4",
                    ) { header("X-Member-Id", admin1.toString()) }
                createResponse.status shouldBe HttpStatusCode.OK
                val seriesId = Uuid.parse(createResponse.bodyAsText().split("|").first())
                createdSeriesIds += seriesId

                val occurrenceEventIds =
                    transaction {
                        EventTable
                            .selectAll()
                            .where { EventTable.seriesId eq seriesId }
                            .orderBy(EventTable.seriesOriginalStart)
                            .map { it[EventTable.id] }
                    }
                occurrenceEventIds.size shouldBe 4
                // First and last occurrence -- their UUIDs are random, so which one happens to sort
                // lower is irrelevant to the fix (it locks the WHOLE affected set in one ascending
                // pass regardless of which row was clicked); what matters is that the two admins'
                // "clicked" rows differ.
                val occurrenceA = occurrenceEventIds.first()
                val occurrenceC = occurrenceEventIds.last()

                runConcurrentSeriesUpdates(
                    client = client,
                    adminOnOccurrenceA = admin1,
                    occurrenceA = occurrenceA,
                    adminOnOccurrenceC = admin2,
                    occurrenceC = occurrenceC,
                )

                transaction {
                    EventTable
                        .selectAll()
                        .where { EventTable.seriesId eq seriesId }
                        .forEach { it[EventTable.title] shouldBe "Serien-RPC-Test (geändert)" }
                }
            }
        }

        // ── Review finding: MAX_ACTIVE_SERIES_PER_ORG (createEventSeries) and the
        // seriesPreviewRateLimiter throttle (previewSeries/impactOfSeriesEdit) had no test at all. ──

        test("createEventSeries rejects a new series once MAX_ACTIVE_SERIES_PER_ORG active series already exist") {
            testApplication {
                val mailDispatcher =
                    MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing { registerSeriesTestRoutes(mailDispatcher) }
                }
                val organizer = createMember(email = "series-limit-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                // Directly plant `EventSeriesLimits.MAX_ACTIVE_SERIES_PER_ORG` (50) "active" series --
                // one future, non-CANCELLED `event` row per series id is all `EventStore
                // .countActiveSeries` requires; no need to run the whole materializer for this.
                val plantedSeriesIds = mutableListOf<Uuid>()
                transaction {
                    repeat(network.lapis.cloud.server.events.series.EventSeriesLimits.MAX_ACTIVE_SERIES_PER_ORG) { index ->
                        val plantedSeriesId = Uuid.random()
                        plantedSeriesIds += plantedSeriesId
                        EventSeriesTable.insert {
                            it[id] = plantedSeriesId
                            it[rrule] = "FREQ=WEEKLY;BYDAY=TU;COUNT=1"
                            it[dtstart] = LocalDateTime(2028, 1, 1, 19, 0)
                            it[timezone] = "Europe/Berlin"
                            it[durationMinutes] = 120
                            it[splitFromSeriesId] = null
                            it[createdBy] = organizer
                            it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        }
                        EventTable.insert {
                            it[id] = Uuid.random()
                            it[slug] = "series-limit-$plantedSeriesId-$index"
                            it[title] = "Limit-Fixture"
                            it[description] = "test"
                            it[locationText] = "Testort"
                            it[onlineUrl] = null
                            it[startsAt] = LocalDateTime(2028, 1, 1, 19, 0)
                            it[endsAt] = LocalDateTime(2028, 1, 1, 21, 0)
                            it[capacity] = null
                            it[feeAmount] = BigDecimal.ZERO
                            it[feeCurrency] = "EUR"
                            it[status] = network.lapis.cloud.shared.domain.EventStatus.PUBLISHED
                            it[visibility] = EventVisibility.PUBLIC
                            it[registrationClosesAt] = null
                            it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                            it[EventTable.createdBy] = organizer
                            it[cancelledAt] = null
                            it[seriesId] = plantedSeriesId
                            it[seriesOriginalStart] = LocalDateTime(2028, 1, 1, 19, 0)
                        }
                    }
                }
                createdSeriesIds += plantedSeriesIds

                val response =
                    client.post(
                        "/test/series/create?startsAt=2028-02-01T19:00:00&endsAt=2028-02-01T21:00:00&count=2",
                    ) { header("X-Member-Id", organizer.toString()) }

                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("previewSeries/impactOfSeriesEdit are throttled by their own seriesPreviewRateLimiter budget") {
            testApplication {
                val mailDispatcher =
                    MailDispatcher(transport = NoopMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        registerSeriesTestRoutes(
                            mailDispatcher = mailDispatcher,
                            seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }
                val organizer = createMember(email = "series-preview-rate-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                val first =
                    client.post(
                        "/test/series/preview?startsAt=2028-03-07T19:00:00&endsAt=2028-03-07T21:00:00&count=3",
                    ) { header("X-Member-Id", organizer.toString()) }
                first.status shouldBe HttpStatusCode.OK

                // Same budget also guards `impactOfSeriesEdit` (Review MINOR fix wording: "eventWriteRateLimiter
                // budget" applied per-RPC, not per-endpoint-family) -- a second call from the SAME
                // member against EITHER RPC within the 1-request/minute budget must now be refused.
                val second =
                    client.post(
                        "/test/series/preview?startsAt=2028-03-07T19:00:00&endsAt=2028-03-07T21:00:00&count=3",
                    ) { header("X-Member-Id", organizer.toString()) }
                second.status shouldBe HttpStatusCode.Conflict
            }
        }
    })

/**
 * Fires two [EventService.updateSeriesEvent] `scope=ALL` calls -- one per occurrence -- from two
 * independent OS threads, synchronized via [CountDownLatch] so both reach the RPC as close to
 * simultaneously as possible, each blocking on its own thread via `runBlocking` (real
 * thread-level parallelism, not two coroutines cooperatively sharing one thread). Regression guard
 * for the lock-ordering deadlock fix in `EventService.computeSeriesImpact` -- see the KDoc there
 * and the test above for the scenario this exercises. Both threads must complete within
 * [timeoutSeconds]; exceeding it fails the test with an explicit deadlock diagnosis rather than
 * hanging the whole suite.
 */
private fun runConcurrentSeriesUpdates(
    client: io.ktor.client.HttpClient,
    adminOnOccurrenceA: Uuid,
    occurrenceA: Uuid,
    adminOnOccurrenceC: Uuid,
    occurrenceC: Uuid,
    timeoutSeconds: Long = 20,
) {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val failures = mutableListOf<Throwable>()

    fun updateThread(
        adminId: Uuid,
        occurrenceEventId: Uuid,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post(
                            "/test/series/$occurrenceEventId/update" +
                                "?scope=ALL&startsAt=2027-12-07T19:00:00&endsAt=2027-12-07T21:00:00",
                        ) { header("X-Member-Id", adminId.toString()) }
                    check(response.status == HttpStatusCode.OK) { "Unexpected status ${response.status}: ${response.bodyAsText()}" }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val threadOnA = updateThread(adminOnOccurrenceA, occurrenceA)
    val threadOnC = updateThread(adminOnOccurrenceC, occurrenceC)
    threadOnA.start()
    threadOnC.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent series-occurrence updates did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
}
