package network.lapis.cloud.server.events.series

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventCapacityGuard
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Follow-up wave "Wiederkehrende Veranstaltungen: Client/iCal/i18n" -- proves what the task's own
 * brief calls out explicitly: a series-materialized `event` row is NOT a parallel world with its own
 * registration/capacity/waitlist/cover-image/embed-feed machinery. It is a completely ordinary
 * `EventTable` row (`series_id`/`series_original_start` set, nothing else different) that every
 * pre-existing, already-merged feature from earlier waves keeps working on unmodified:
 *
 * - **Anmeldung/Kontingent/Warteliste**: [EventRegistrationSubmission.submit] and
 *   [EventCapacityGuard.withEventLock]'s waitlist-promotion sweep, run against a `capacity = 1`
 *   series instance exactly like [network.lapis.cloud.server.events.EventCapacityTest] already
 *   proves for a plain event.
 * - **Titelbild**: [EventCoverPolicy.coverImageUrl] is a pure function of `slug`/`coverImageId` --
 *   both already unique per materialized instance (see [EventSeriesMaterializer.materialize]'s own
 *   per-occurrence `EventPolicy.slugFor` call), so no series-specific wiring is needed at all.
 * - **Embed-Widget**: `EmbedEventsFeedRoutes.kt` reuses [EventIcsFeed.loadUpcomingPublicPublished]
 *   verbatim (see that file's own KDoc "BEWUSST eigenständig, NICHT `EventIcsFeed.MAX_EVENTS`
 *   wieder-..."; the query itself is untouched) -- a PUBLIC+PUBLISHED series instance is included in
 *   that result set exactly like a plain event.
 */
class EventSeriesInstanceIntegrationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdSeriesIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdSeriesIds.isNotEmpty()) {
                    EventSeriesTable.deleteWhere { id inList createdSeriesIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createOrganizer(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventSeriesInstanceIntegrationTest Organisator"
                    it[email] = "series-instance-organizer-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        // Far in the future -- same rationale as EventCapacityTest's own `farFutureStartsAt`.
        val farFutureStart = LocalDateTime(2031, 3, 4, 19, 0) // a Tuesday
        val zone = ZoneId.of("Europe/Berlin")

        fun createWeeklySeriesFirstInstance(
            organizer: Uuid,
            capacity: Int?,
        ): Pair<Uuid, Uuid> {
            val now = DbClock.nowLocalDateTime()
            val rule =
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.WEEKLY,
                    interval = 1,
                    byWeekdays = setOf(network.lapis.cloud.shared.domain.RecurrenceWeekday.TU),
                    count = 3,
                )
            val built = RecurrenceRuleBuilder.build(rule = rule, dtstart = farFutureStart, zone = zone)
            val rrule = (built as RecurrenceRuleBuilder.Result.Ok).rrule
            val seriesId = Uuid.random()
            val template =
                EventInput(
                    title = "Series-Instance-Integration-Test",
                    description = "test",
                    locationText = "Testort",
                    onlineUrl = null,
                    startsAt = farFutureStart,
                    endsAt = farFutureStart,
                    capacity = capacity,
                    feeAmount = BigDecimal.ZERO,
                    feeCurrency = "EUR",
                    visibility = EventVisibility.PUBLIC,
                    registrationClosesAt = null,
                    roomId = null,
                )
            val createdIds =
                transaction {
                    network.lapis.cloud.server.events.EventStore.insertSeries(
                        id = seriesId,
                        rrule = rrule,
                        dtstart = farFutureStart,
                        timezone = zone.id,
                        durationMinutes = 120,
                        splitFromSeriesId = null,
                        createdBy = organizer,
                        createdAt = now,
                    )
                    EventSeriesMaterializer.materialize(
                        seriesId = seriesId,
                        rrule = rrule,
                        dtstart = farFutureStart,
                        zone = zone,
                        durationMinutes = 120,
                        template = template,
                        createdBy = organizer,
                        now = now,
                    )
                }
            createdSeriesIds += seriesId
            createdEventIds += createdIds
            return seriesId to createdIds.first()
        }

        test(
            "a series-materialized event row is an ordinary EventTable row -- series_id/series_original_start set, nothing else different",
        ) {
            val organizer = createOrganizer()
            val (seriesId, firstEventId) = createWeeklySeriesFirstInstance(organizer, capacity = null)
            val row = transaction { EventTable.selectAll().where { EventTable.id eq firstEventId }.single() }
            row[EventTable.seriesId] shouldBe seriesId
            row[EventTable.seriesOriginalStart] shouldBe farFutureStart
            row[EventTable.seriesDetached] shouldBe false
            row[EventTable.status] shouldBe network.lapis.cloud.shared.domain.EventStatus.PUBLISHED
            row[EventTable.visibility] shouldBe EventVisibility.PUBLIC
            row[EventTable.slug].isNotBlank() shouldBe true
        }

        test(
            "Anmeldung/Kontingent/Warteliste: capacity=1 series instance -- first guest CONFIRMED, second WAITLISTED, then promoted after a cancellation",
        ) {
            val organizer = createOrganizer()
            val (_, eventId) = createWeeklySeriesFirstInstance(organizer, capacity = 1)

            val submission =
                EventRegistrationSubmission(
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher =
                        MailDispatcher(
                            transport = NoOpMailTransport(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        ),
                )

            val first =
                runBlocking {
                    submission.submit(
                        eventId = eventId,
                        participant =
                            EventParticipant.Guest(
                                name = "Erst-Anmeldung",
                                normalizedEmail = "series-first-${Uuid.random()}@example.org",
                            ),
                    )
                }
            val second =
                runBlocking {
                    submission.submit(
                        eventId = eventId,
                        participant =
                            EventParticipant.Guest(
                                name = "Zweit-Anmeldung",
                                normalizedEmail = "series-second-${Uuid.random()}@example.org",
                            ),
                    )
                }

            (first is EventRegistrationResult.Confirmed) shouldBe true
            (second is EventRegistrationResult.Waitlisted) shouldBe true

            val firstRegistrationId = (first as EventRegistrationResult.Confirmed).registrationId
            val now = DbClock.nowLocalDateTime()
            val (_, promotions) =
                EventCapacityGuard.withEventLock(eventId = eventId, now = now) { _ ->
                    network.lapis.cloud.server.events.EventStore
                        .cancelRegistration(id = firstRegistrationId, now = now)
                }

            // The freed seat is offered to the waitlisted guest -- same promotion mechanics
            // EventCapacityTest already proves for a plain event, exercised here on a series row.
            promotions.size shouldBe 1
            val secondRegistrationId = (second as EventRegistrationResult.Waitlisted).registrationId
            val secondStatusAfter =
                transaction {
                    EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq secondRegistrationId }.single()[
                        EventRegistrationTable.status,
                    ]
                }
            secondStatusAfter shouldBe EventRegistrationStatus.CONFIRMED
        }

        test(
            "Titelbild: EventCoverPolicy.coverImageUrl works for a series instance exactly like for a plain event -- keyed only by slug/coverImageId",
        ) {
            val organizer = createOrganizer()
            val (_, eventId) = createWeeklySeriesFirstInstance(organizer, capacity = null)
            val row = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single() }
            val slug = row[EventTable.slug]

            EventCoverPolicy.coverImageUrl(baseUrl = "https://example.org", slug = slug, coverImageId = null) shouldBe null

            val coverImageId = Uuid.random()
            transaction { EventTable.update({ EventTable.id eq eventId }) { it[EventTable.coverImageId] = coverImageId } }
            val url = EventCoverPolicy.coverImageUrl(baseUrl = "https://example.org", slug = slug, coverImageId = coverImageId)
            url shouldNotBe null
            url shouldBe "https://example.org/veranstaltung/$slug/bild?v=${coverImageId.toString().take(8)}"
        }

        test(
            "Embed-Widget: a PUBLIC+PUBLISHED series instance appears in EventIcsFeed.loadUpcomingPublicPublished exactly like a plain event -- the same query EmbedEventsFeedRoutes reuses",
        ) {
            val organizer = createOrganizer()
            val (_, eventId) = createWeeklySeriesFirstInstance(organizer, capacity = null)
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows =
                transaction {
                    network.lapis.cloud.server.routes.EventIcsFeed
                        .loadUpcomingPublicPublished(now = now)
                }
            (eventId in rows.map { it[EventTable.id] }) shouldBe true
        }
    })
