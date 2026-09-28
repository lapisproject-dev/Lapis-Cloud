package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.series.EventSeriesMaterializer
import network.lapis.cloud.server.events.series.RecurrenceRuleBuilder
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.StringReader
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Follow-up wave "Wiederkehrende Veranstaltungen: iCal-Feed" -- the RRULE-master/RECURRENCE-ID-
 * exception/EXDATE rendering `EventIcsFeed.render` gained (see that class' own KDoc "RRULE-Serien-
 * Unterstützung"). `EventIcsFeedTest` (the pre-existing, un-touched file) keeps covering the plain,
 * non-series rendering path this wave leaves byte-for-byte unchanged; this file is exclusively about
 * the NEW series path, including a real `net.fortuna.ical4j.data.CalendarBuilder` round-trip parse of
 * the produced text -- proving the output is not merely string-shaped like RFC 5545 but actually
 * PARSES as one, exactly the round-trip rigor `RecurrenceExpander`'s own KDoc anticipated for this
 * exact test file ("no `CalendarBuilder`/`CalendarOutputter` in production code ... only in
 * `EventIcsFeedSeriesTest`'s round-trip assertion, in a later wave").
 */
class EventIcsFeedSeriesTest :
    FunSpec({
        val createdEventIds = mutableListOf<Uuid>()
        val createdSeriesIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdSeriesIds.isNotEmpty()) EventSeriesTable.deleteWhere { id inList createdSeriesIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventIcsFeedSeriesTest Organisator"
                    it[email] = "ics-feed-series-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        val zone = ZoneId.of("Europe/Berlin")
        val dtstart = LocalDateTime(2032, 3, 2, 19, 0) // a Tuesday

        /** Materializes a WEEKLY;COUNT=4 series and returns (seriesId, createdEventIds in occurrence order). */
        fun createWeeklySeries(): Pair<Uuid, List<Uuid>> {
            val organizer = createMember()
            val now = DbClock.nowLocalDateTime()
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = setOf(RecurrenceWeekday.TU), count = 4)
            val built = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = zone)
            val rrule = (built as RecurrenceRuleBuilder.Result.Ok).rrule
            val seriesId = Uuid.random()
            val template =
                EventInput(
                    title = "Ics-Feed-Series-Test",
                    description = "test",
                    locationText = "Testort",
                    onlineUrl = null,
                    startsAt = dtstart,
                    endsAt = dtstart,
                    capacity = null,
                    feeAmount = BigDecimal.ZERO,
                    feeCurrency = "EUR",
                    visibility = EventVisibility.PUBLIC,
                    registrationClosesAt = null,
                    roomId = null,
                )
            val createdIds =
                transaction {
                    EventStore.insertSeries(
                        id = seriesId,
                        rrule = rrule,
                        dtstart = dtstart,
                        timezone = zone.id,
                        durationMinutes = 120,
                        splitFromSeriesId = null,
                        createdBy = organizer,
                        createdAt = now,
                    )
                    EventSeriesMaterializer.materialize(
                        seriesId = seriesId,
                        rrule = rrule,
                        dtstart = dtstart,
                        zone = zone,
                        durationMinutes = 120,
                        template = template,
                        createdBy = organizer,
                        now = now,
                    )
                }
            createdSeriesIds += seriesId
            createdEventIds += createdIds
            return seriesId to createdIds
        }

        fun parseVevents(body: String): List<VEvent> {
            val calendar = CalendarBuilder().build(StringReader(body))
            return calendar.getComponents<VEvent>("VEVENT").toList()
        }

        test("a plain series (no detached/cancelled/deleted occurrences) renders as ONE RRULE master VEVENT, no per-occurrence VEVENTs") {
            val (seriesId, eventIds) = createWeeklySeries()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now = now).filter { it[EventTable.id] in eventIds } }
            val seriesData = transaction { EventIcsFeed.loadSeriesRenderData(setOf(seriesId)) }
            val body = EventIcsFeed.render(rows = rows, baseUrl = "https://example.org", brandTitle = "Testverein", seriesData = seriesData)

            val vevents = parseVevents(body)
            vevents.size shouldBe 1
            val master = vevents.single()
            master.uid.orElseThrow().value shouldBe "series-$seriesId@example.org"
            master.getProperty<RRule<*>>("RRULE").isPresent shouldBe true
            body shouldContain "RRULE:"
            body shouldNotContain "RECURRENCE-ID"
            body shouldNotContain "EXDATE"
        }

        test(
            "an individually detached occurrence gets its own RECURRENCE-ID exception VEVENT alongside the master, sharing the master's UID",
        ) {
            val (seriesId, eventIds) = createWeeklySeries()
            val detachedId = eventIds[1]
            transaction {
                EventTable.update({ EventTable.id eq detachedId }) {
                    it[seriesDetached] = true
                    it[title] = "Verschobener Einzeltermin"
                }
            }
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now = now).filter { it[EventTable.id] in eventIds } }
            val seriesData = transaction { EventIcsFeed.loadSeriesRenderData(setOf(seriesId)) }
            val body = EventIcsFeed.render(rows = rows, baseUrl = "https://example.org", brandTitle = "Testverein", seriesData = seriesData)

            val vevents = parseVevents(body)
            vevents.size shouldBe 2
            val masterUid = "series-$seriesId@example.org"
            vevents.all { it.uid.orElseThrow().value == masterUid } shouldBe true
            val exception = vevents.single { it.getProperty<RecurrenceId<*>>("RECURRENCE-ID").isPresent }
            exception.summary.value shouldBe "Verschobener Einzeltermin"
            body shouldContain "RECURRENCE-ID:"
        }

        test("a hard-deleted occurrence produces an EXDATE on the master, not a ghost VEVENT") {
            val (seriesId, eventIds) = createWeeklySeries()
            val deletedId = eventIds[2]
            transaction { EventTable.deleteWhere { id eq deletedId } }
            val remainingIds = eventIds - deletedId
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now = now).filter { it[EventTable.id] in remainingIds } }
            val seriesData = transaction { EventIcsFeed.loadSeriesRenderData(setOf(seriesId)) }
            val body = EventIcsFeed.render(rows = rows, baseUrl = "https://example.org", brandTitle = "Testverein", seriesData = seriesData)

            val vevents = parseVevents(body)
            vevents.size shouldBe 1
            body shouldContain "EXDATE:"
        }

        test("a cancelled (status != PUBLISHED) occurrence also produces an EXDATE, same as a hard-deleted one") {
            val (seriesId, eventIds) = createWeeklySeries()
            val cancelledId = eventIds[3]
            transaction { EventTable.update({ EventTable.id eq cancelledId }) { it[status] = EventStatus.CANCELLED } }
            val remainingIds = eventIds - cancelledId
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now = now).filter { it[EventTable.id] in remainingIds } }
            val seriesData = transaction { EventIcsFeed.loadSeriesRenderData(setOf(seriesId)) }
            val body = EventIcsFeed.render(rows = rows, baseUrl = "https://example.org", brandTitle = "Testverein", seriesData = seriesData)

            val vevents = parseVevents(body)
            vevents.size shouldBe 1
            body shouldContain "EXDATE:"
        }
    })
