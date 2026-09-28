package network.lapis.cloud.server.events.series

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Dritte und letzte Folgewelle "Wiederkehrende Veranstaltungen" -- [EventSeriesMaterializer]
 * against a real (H2-in-memory, see `DatabaseConfig`) DB, exercised inside a real
 * `transaction {}` exactly the way `EventService`'s RPC methods call it.
 */
class EventSeriesMaterializerTest :
    FunSpec({
        val berlin = ZoneId.of("Europe/Berlin")
        val createdMemberIds = mutableListOf<Uuid>()
        val createdSeriesIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdSeriesIds.isNotEmpty()) {
                    // A FOLLOWING split (see the new tests below) inserts a SECOND `event_series`
                    // row (`splitFromSeriesId = seriesId`) and re-points some of the original
                    // series' `event` rows onto it -- neither that new series row nor those
                    // now-repointed events would be found by `seriesId inList createdSeriesIds`
                    // alone, so first widen the id set to include any split-off series too.
                    val splitSeriesIds =
                        EventSeriesTable
                            .selectAll()
                            .where { EventSeriesTable.splitFromSeriesId inList createdSeriesIds }
                            .map { it[EventSeriesTable.id] }
                    val allSeriesIds = createdSeriesIds + splitSeriesIds
                    val eventIds =
                        EventTable
                            .selectAll()
                            .where { EventTable.seriesId inList allSeriesIds }
                            .map { it[EventTable.id] }
                    if (eventIds.isNotEmpty()) {
                        EventRegistrationTable.deleteWhere { eventId inList eventIds }
                    }
                    EventTable.deleteWhere { seriesId inList allSeriesIds }
                    // Children (the split-off series, `fk_event_series_split_from`) must be deleted
                    // BEFORE their parent -- a single `DELETE ... WHERE id IN (...)` covering both
                    // in one statement is not guaranteed to honor that order and can violate the FK.
                    if (splitSeriesIds.isNotEmpty()) {
                        EventSeriesTable.deleteWhere { id inList splitSeriesIds }
                    }
                    EventSeriesTable.deleteWhere { id inList createdSeriesIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    // Any leftover `event` row still referencing a test room (e.g. a collision
                    // fixture the test itself did not already clean up) must go FIRST -- `fk_event_room`
                    // has no `ON DELETE CASCADE`.
                    EventTable.deleteWhere { EventTable.roomId inList createdRoomIds }
                    EventRoomTable.deleteWhere { id inList createdRoomIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventSeriesMaterializerTest Mitglied"
                    it[email] = "materializer-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        val template =
            EventInput(
                title = "Wöchentlicher Stammtisch",
                description = "Test-Serie",
                locationText = "Testort",
                onlineUrl = null,
                startsAt = LocalDateTime(2026, 10, 6, 19, 0), // a Tuesday
                endsAt = LocalDateTime(2026, 10, 6, 21, 0),
                capacity = null,
                feeAmount = BigDecimal.ZERO,
                feeCurrency = "EUR",
                visibility = EventVisibility.PUBLIC,
                registrationClosesAt = null,
            )

        test("materialize creates one PUBLISHED event per occurrence, series-linked") {
            val createdBy = createMember()
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = setOf(RecurrenceWeekday.TU), count = 5)
            val rrule =
                (
                    RecurrenceRuleBuilder.build(
                        rule = rule,
                        dtstart = template.startsAt,
                        zone = berlin,
                    ) as RecurrenceRuleBuilder.Result.Ok
                ).rrule

            val seriesId = Uuid.random()
            val createdIds =
                transaction {
                    EventStore.insertSeries(
                        id = seriesId,
                        rrule = rrule,
                        dtstart = template.startsAt,
                        timezone = "Europe/Berlin",
                        durationMinutes = 120,
                        splitFromSeriesId = null,
                        createdBy = createdBy,
                        createdAt = template.startsAt,
                    )
                    EventSeriesMaterializer.materialize(
                        seriesId = seriesId,
                        rrule = rrule,
                        dtstart = template.startsAt,
                        zone = berlin,
                        durationMinutes = 120,
                        template = template,
                        createdBy = createdBy,
                        now = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                }
            createdSeriesIds += seriesId

            createdIds shouldHaveSize 5
            transaction {
                createdIds.forEach { id ->
                    val row = EventStore.getEventOrThrow(id)
                    row[EventTable.seriesId] shouldBe seriesId
                    row[EventTable.status] shouldBe EventStatus.PUBLISHED
                    row[EventTable.seriesOriginalStart] shouldBe row[EventTable.startsAt]
                    val expectedEnd = row[EventTable.startsAt].toJavaLocalDateTime().plusMinutes(120).toKotlinLocalDateTime()
                    row[EventTable.endsAt] shouldBe expectedEnd
                }
            }
        }

        test("materialize rejects the whole series on a room collision (alles-oder-nichts)") {
            val createdBy = createMember()
            val roomId = Uuid.random()
            transaction {
                EventRoomTable.insert {
                    it[id] = roomId
                    it[name] = "Kollisions-Testraum"
                    it[capacity] = null
                    it[equipmentTags] = ""
                    it[status] = EventRoomStatus.ACTIVE
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[EventRoomTable.createdBy] = createdBy
                }
            }
            createdRoomIds += roomId

            // Pre-existing booking that collides with the THIRD occurrence (2026-10-20 19:00-21:00).
            val collidingEventId = Uuid.random()
            transaction {
                EventTable.insert {
                    it[id] = collidingEventId
                    it[slug] = "collision-$collidingEventId"
                    it[title] = "Fremdbuchung"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2026, 10, 20, 20, 0)
                    it[endsAt] = LocalDateTime(2026, 10, 20, 22, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                    it[EventTable.roomId] = roomId
                }
            }

            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = setOf(RecurrenceWeekday.TU), count = 5)
            val rrule =
                (
                    RecurrenceRuleBuilder.build(
                        rule = rule,
                        dtstart = template.startsAt,
                        zone = berlin,
                    ) as RecurrenceRuleBuilder.Result.Ok
                ).rrule
            val roomTemplate = template.copy(roomId = roomId.toString())
            val seriesId = Uuid.random()
            createdSeriesIds += seriesId

            shouldThrow<ConflictException> {
                transaction {
                    EventStore.insertSeries(
                        id = seriesId,
                        rrule = rrule,
                        dtstart = template.startsAt,
                        timezone = "Europe/Berlin",
                        durationMinutes = 120,
                        splitFromSeriesId = null,
                        createdBy = createdBy,
                        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                    EventSeriesMaterializer.materialize(
                        seriesId = seriesId,
                        rrule = rrule,
                        dtstart = template.startsAt,
                        zone = berlin,
                        durationMinutes = 120,
                        template = roomTemplate,
                        createdBy = createdBy,
                        now = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                }
            }

            // Alles-oder-nichts: no event of this attempted series exists (Exposed rolls the whole
            // `transaction {}` back on the thrown exception) -- neither the `event_series` row nor
            // any materialized instance survives.
            transaction {
                EventStore.getSeriesOrNull(seriesId) shouldBe null
                val remaining = EventTable.selectAll().where { EventTable.seriesId eq seriesId }.toList()
                remaining shouldHaveSize 0
            }

            transaction { EventTable.deleteWhere { EventTable.id eq collidingEventId } }
        }

        // ── Review finding: THIS/FOLLOWING(split)/cancel-with-registrations were completely
        // untested -- only the materialize happy path + room collision (both above) and a single
        // scope=ALL/0-registrations RPC round-trip (`EventServiceSeriesRpcTest`) existed. ─────────

        fun createSeries(
            createdBy: Uuid,
            count: Int,
        ): Pair<Uuid, List<Uuid>> {
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = setOf(RecurrenceWeekday.TU), count = count)
            val rrule =
                (
                    RecurrenceRuleBuilder.build(rule = rule, dtstart = template.startsAt, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
                ).rrule
            val seriesId = Uuid.random()
            val createdIds =
                transaction {
                    EventStore.insertSeries(
                        id = seriesId,
                        rrule = rrule,
                        dtstart = template.startsAt,
                        timezone = "Europe/Berlin",
                        durationMinutes = 120,
                        splitFromSeriesId = null,
                        createdBy = createdBy,
                        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                    EventSeriesMaterializer.materialize(
                        seriesId = seriesId,
                        rrule = rrule,
                        dtstart = template.startsAt,
                        zone = berlin,
                        durationMinutes = 120,
                        template = template,
                        createdBy = createdBy,
                        now = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                }
            createdSeriesIds += seriesId
            return seriesId to createdIds
        }

        test("applyEdit(THIS) detaches ONLY the targeted occurrence -- siblings keep their series link + old title") {
            val createdBy = createMember()
            val (seriesId, createdIds) = createSeries(createdBy = createdBy, count = 5)

            val targetOriginalStart =
                transaction { EventStore.getEventOrThrow(createdIds[1])[EventTable.seriesOriginalStart]!! }
            val scopePlan =
                transaction {
                    val series = EventStore.getSeriesOrThrow(seriesId)
                    EventSeriesScopeEngine.plan(
                        scope = EventSeriesEditScope.THIS,
                        rrule = series[EventSeriesTable.rrule],
                        dtstart = series[EventSeriesTable.dtstart],
                        zone = berlin,
                        targetOriginalStart = targetOriginalStart,
                    )
                }
            scopePlan.affectedOriginalStarts shouldHaveSize 1
            scopePlan.split shouldBe null

            val edited = template.copy(title = "Nur dieser Termin")
            val result =
                transaction {
                    EventSeriesMaterializer.applyEdit(
                        scope = EventSeriesEditScope.THIS,
                        scopePlan = scopePlan,
                        seriesId = seriesId,
                        newTemplate = edited,
                        durationMinutes = 120,
                        createdBy = createdBy,
                        now = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                }
            result.affectedEventIds shouldBe listOf(createdIds[1])

            transaction {
                val editedRow = EventStore.getEventOrThrow(createdIds[1])
                editedRow[EventTable.title] shouldBe "Nur dieser Termin"
                editedRow[EventTable.seriesDetached] shouldBe true
                editedRow[EventTable.seriesId] shouldBe seriesId // detach keeps the FK, only flips the flag

                createdIds.filterNot { it == createdIds[1] }.forEach { id ->
                    val sibling = EventStore.getEventOrThrow(id)
                    sibling[EventTable.title] shouldBe template.title
                    sibling[EventTable.seriesDetached] shouldBe false
                    sibling[EventTable.seriesId] shouldBe seriesId
                }
            }
        }

        test("applyEdit(FOLLOWING) on a non-first occurrence performs a real split: two independent event_series rows") {
            val createdBy = createMember()
            val (seriesId, createdIds) = createSeries(createdBy = createdBy, count = 5)

            // Split at the THIRD occurrence (index 2) -- two occurrences stay on the original
            // series, three (this one + the two following) move to the new, split-off series.
            val splitOriginalStart =
                transaction { EventStore.getEventOrThrow(createdIds[2])[EventTable.seriesOriginalStart]!! }
            val scopePlan =
                transaction {
                    val series = EventStore.getSeriesOrThrow(seriesId)
                    EventSeriesScopeEngine.plan(
                        scope = EventSeriesEditScope.FOLLOWING,
                        rrule = series[EventSeriesTable.rrule],
                        dtstart = series[EventSeriesTable.dtstart],
                        zone = berlin,
                        targetOriginalStart = splitOriginalStart,
                    )
                }
            scopePlan.affectedOriginalStarts shouldHaveSize 3
            scopePlan.split shouldNotBe null

            val edited = template.copy(title = "Ab jetzt anders")
            val result =
                transaction {
                    EventSeriesMaterializer.applyEdit(
                        scope = EventSeriesEditScope.FOLLOWING,
                        scopePlan = scopePlan,
                        seriesId = seriesId,
                        newTemplate = edited,
                        durationMinutes = 120,
                        createdBy = createdBy,
                        now = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                }
            result.affectedEventIds shouldHaveSize 3

            transaction {
                // The FIRST two occurrences stay on the OLD seriesId, untouched, old title.
                createdIds.take(2).forEach { id ->
                    val row = EventStore.getEventOrThrow(id)
                    row[EventTable.seriesId] shouldBe seriesId
                    row[EventTable.title] shouldBe template.title
                }
                // The LAST three occurrences (the edited one + its followers) are re-pointed to a
                // NEW, distinct series id, carry the new title, and are NOT flagged detached (a
                // split is not a detach -- see EventSeriesMaterializer.applyEdit KDoc).
                val newSeriesId = EventStore.getEventOrThrow(createdIds[2])[EventTable.seriesId]!!
                newSeriesId shouldNotBe seriesId
                createdIds.drop(2).forEach { id ->
                    val row = EventStore.getEventOrThrow(id)
                    row[EventTable.seriesId] shouldBe newSeriesId
                    row[EventTable.title] shouldBe "Ab jetzt anders"
                    row[EventTable.seriesDetached] shouldBe false
                }

                // The new `event_series` row exists, points back at the original via
                // `splitFromSeriesId`, and is anchored at the split occurrence's own original start.
                val newSeries = EventStore.getSeriesOrThrow(newSeriesId)
                newSeries[EventSeriesTable.splitFromSeriesId] shouldBe seriesId
                newSeries[EventSeriesTable.dtstart] shouldBe splitOriginalStart

                // The ORIGINAL series' own rrule was truncated (COUNT=2) so it no longer produces
                // occurrences at/after the split point.
                val originalSeries = EventStore.getSeriesOrThrow(seriesId)
                val remainingOriginalOccurrences =
                    RecurrenceExpander.expand(
                        rrule = originalSeries[EventSeriesTable.rrule],
                        dtstart = originalSeries[EventSeriesTable.dtstart],
                        zone = berlin,
                    )
                remainingOriginalOccurrences shouldHaveSize 2
            }
        }

        test(
            "deleteOrCancel: an occurrence with an active registration is CANCELLED + mailed, not hard-deleted, unlike its untouched siblings",
        ) {
            val createdBy = createMember()
            val registrant = createMember()
            val (seriesId, createdIds) = createSeries(createdBy = createdBy, count = 3)

            val registeredEventId = createdIds[1]
            transaction {
                EventRegistrationTable.insert {
                    it[id] = Uuid.random()
                    it[EventRegistrationTable.eventId] = registeredEventId
                    it[memberId] = registrant
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = EventPolicy.activeParticipantKey(memberId = registrant, normalizedGuestEmail = null)
                    it[status] = EventRegistrationStatus.CONFIRMED
                    it[feeAmount] = BigDecimal.ZERO
                    it[holdExpiresAt] = null
                    it[waitlistPosition] = null
                    it[cancelTokenSha256] = null
                    it[registeredAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[confirmedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }

            val scopePlan =
                transaction {
                    val series = EventStore.getSeriesOrThrow(seriesId)
                    EventSeriesScopeEngine.plan(
                        scope = EventSeriesEditScope.ALL,
                        rrule = series[EventSeriesTable.rrule],
                        dtstart = series[EventSeriesTable.dtstart],
                        zone = berlin,
                        targetOriginalStart = series[EventSeriesTable.dtstart],
                    )
                }
            val result =
                transaction {
                    EventSeriesMaterializer.deleteOrCancel(
                        scope = EventSeriesEditScope.ALL,
                        scopePlan = scopePlan,
                        seriesId = seriesId,
                        now = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                }

            result.affectedEventIds shouldHaveSize 3
            result.notices shouldHaveSize 1

            transaction {
                // The two occurrences WITHOUT any registration were hard-deleted -- gone entirely.
                createdIds.filterNot { it == registeredEventId }.forEach { id ->
                    shouldThrow<NotFoundException> { EventStore.getEventOrThrow(id) }
                }
                // The occurrence WITH a registration was CANCELLED, not hard-deleted -- and its
                // registration itself was cancelled, freeing `active_participant_key`.
                val cancelledRow = EventStore.getEventOrThrow(registeredEventId)
                cancelledRow[EventTable.status] shouldBe EventStatus.CANCELLED
                val registrationRow =
                    EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq registeredEventId }.single()
                registrationRow[EventRegistrationTable.status] shouldBe EventRegistrationStatus.CANCELLED
                registrationRow[EventRegistrationTable.activeParticipantKey] shouldBe null
            }
        }
    })
