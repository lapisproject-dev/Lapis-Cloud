package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.244 -- what our own feed contains and, more important, what it does not. */
class PartnerFeedBuilderTest :
    FunSpec({
        val prefix = "pfb-" + Uuid.random().toString().take(8)
        val zone = TimeZone.UTC
        val now = LocalDateTime(2026, 11, 5, 10, 0, 0).toInstant(zone)
        val seriesId = Uuid.random()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        beforeTest {
            transaction {
                EventTable.deleteWhere { slug like "$prefix%" }
                EventSeriesTable.deleteWhere { id eq seriesId }
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
                OrganizationSettingsTable.update { it[partnerListHidden] = false }
            }
        }
        afterSpec {
            transaction {
                EventTable.deleteWhere { slug like "$prefix%" }
                EventSeriesTable.deleteWhere { id eq seriesId }
            }
        }

        fun insertEvent(
            slug: String,
            title: String = "Treffen $slug",
            visibility: EventVisibility = EventVisibility.PUBLIC,
            status: EventStatus = EventStatus.PUBLISHED,
            startsAt: LocalDateTime = LocalDateTime(2026, 11, 20, 19, 0, 0),
            endsAt: LocalDateTime = LocalDateTime(2026, 11, 20, 21, 0, 0),
            onlineUrl: String? = null,
            description: String = "SEHR GEHEIME LANGE BESCHREIBUNG",
        ): Uuid =
            insertPartnerTestEvent(
                slug = slug,
                title = title,
                visibility = visibility,
                status = status,
                startsAt = startsAt,
                endsAt = endsAt,
                onlineUrl = onlineUrl,
                description = description,
            )

        fun feedText(): String = String(PartnerFeedBuilder.build(now = now, publicBaseUrl = "https://own.example.com"))

        test("only public, published, not yet ended events appear; drafts, cancelled and members-only never") {
            insertEvent("$prefix-ok")
            insertEvent("$prefix-draft", status = EventStatus.DRAFT)
            insertEvent("$prefix-cancelled", status = EventStatus.CANCELLED)
            insertEvent("$prefix-members", visibility = EventVisibility.MEMBERS_ONLY)
            insertEvent("$prefix-past", startsAt = LocalDateTime(2026, 11, 1, 19, 0, 0), endsAt = LocalDateTime(2026, 11, 1, 21, 0, 0))
            insertEvent("$prefix-running", startsAt = LocalDateTime(2026, 11, 5, 9, 0, 0), endsAt = LocalDateTime(2026, 11, 5, 12, 0, 0))
            insertEvent("$prefix-far", startsAt = LocalDateTime(2028, 1, 1, 19, 0, 0), endsAt = LocalDateTime(2028, 1, 1, 21, 0, 0))
            val text = feedText()
            text shouldNotContain "$prefix-draft"
            text shouldNotContain "$prefix-cancelled"
            text shouldNotContain "$prefix-members"
            text shouldNotContain "$prefix-past"
            text shouldNotContain "$prefix-far"
            (text.contains("$prefix-ok")) shouldBe true
            (text.contains("$prefix-running")) shouldBe true
        }

        test("no description, no online URL, no capacity, no fee, nobody who created it") {
            insertEvent("$prefix-secret", onlineUrl = "https://meet.example.com/GEHEIMER-RAUM")
            val text = feedText()
            text shouldNotContain "GEHEIME LANGE BESCHREIBUNG"
            text shouldNotContain "GEHEIMER-RAUM"
            listOf("\"description\"", "\"onlineUrl\"", "\"capacity\"", "\"fee\"", "\"createdBy\"", "\"registrations\"").forEach {
                text shouldNotContain
                    it
            }
            (text.contains("\"online\":true")) shouldBe true
        }

        test("the start carries the offset of the organisation zone") {
            insertEvent("$prefix-offset")
            val feed = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), feedText())
            val event = feed.events.single { it.url.endsWith("$prefix-offset") }
            val zoneId = OrganizationTimeZone.current().id
            event.timeZone shouldBe zoneId
            // the wall-clock 19:00 is written with the offset valid on that day in that zone
            (event.startsAt.startsWith("2026-11-20T19:00")) shouldBe true
            (event.startsAt.contains('+') || event.startsAt.endsWith("Z") || event.startsAt.substring(16).contains('-')) shouldBe true
            event.url shouldBe "https://own.example.com/veranstaltung/$prefix-offset"
        }

        test("the server id is stable over two builds, the partner list respects the switch, blocks and inactive partners") {
            val first = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), feedText())
            val second = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), feedText())
            first.serverId shouldBe second.serverId
            transaction {
                listOf("a.beispiel.de" to true, "b.beispiel.de" to false, "c.beispiel.de" to true).forEach { (host, active) ->
                    PartnerServerTable.insert {
                        it[id] = Uuid.random()
                        it[PartnerServerTable.host] = host
                        it[PartnerServerTable.active] = active
                        it[createdAt] = LocalDateTime(2026, 11, 1, 0, 0, 0)
                        it[nextFetchAt] = LocalDateTime(2026, 11, 1, 0, 0, 0)
                    }
                }
                PartnerBlocklistTable.insert {
                    it[id] = Uuid.random()
                    it[pattern] = "c.beispiel.de"
                    it[createdAt] = LocalDateTime(2026, 11, 1, 0, 0, 0)
                }
            }
            val listed = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), feedText())
            listed.partners.map { it.host } shouldBe listOf("a.beispiel.de")
            listed.partnersHidden shouldBe false
            transaction { OrganizationSettingsTable.update { it[partnerListHidden] = true } }
            val hidden = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), feedText())
            hidden.partners shouldHaveSize 0
            hidden.partnersHidden shouldBe true
        }

        test("a feed of the maximum number of maximum-length events stays within 512 KiB") {
            val long = "ä".repeat(200)
            val events =
                (1..PartnerFeedBuilder.MAX_EVENTS).map {
                    PartnerFeedEvent(
                        kind = "event",
                        id = Uuid.random().toString(),
                        title = long,
                        startsAt = "2026-11-20T19:00:00+01:00",
                        endsAt = "2026-11-20T21:00:00+01:00",
                        timeZone = "Europe/Berlin",
                        online = true,
                        place = long,
                        summary = "x".repeat(300),
                        url = "https://own.example.com/veranstaltung/" + "a".repeat(120),
                        recurrence = PartnerFeedRecurrence(long),
                    )
                }
            val feed =
                PartnerFeedV1(
                    schemaVersion = 1,
                    kind = PARTNER_FEED_KIND,
                    serverId = Uuid.random().toString(),
                    name = "Org",
                    generatedAt = "2026-11-05T10:00:00Z",
                    events = events,
                )
            val bytes = PartnerFeedBuilder.serializeBounded(feed)
            (bytes.size <= PartnerFeedBuilder.MAX_BYTES) shouldBe true
            val kept = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), String(bytes)).events.size
            (kept in 1 until PartnerFeedBuilder.MAX_EVENTS) shouldBe true
        }

        test("a series appears ONCE with its sentence") {
            transaction {
                EventSeriesTable.insert {
                    it[id] = seriesId
                    it[rrule] = "FREQ=WEEKLY;INTERVAL=1"
                    it[dtstart] = LocalDateTime(2026, 11, 20, 19, 0, 0)
                    it[timezone] = "Europe/Berlin"
                    it[durationMinutes] = 120
                    it[createdBy] = PARTNER_TEST_MEMBER
                    it[createdAt] = LocalDateTime(2026, 11, 1, 0, 0, 0)
                }
            }
            (0..4).forEach { week ->
                insertPartnerTestEvent(
                    slug = "$prefix-series-$week",
                    title = "Serie",
                    startsAt = LocalDateTime(2026, 11, 20 + week, 19, 0, 0),
                    endsAt = LocalDateTime(2026, 11, 20 + week, 21, 0, 0),
                    seriesId = seriesId,
                )
            }
            val feed = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), feedText())
            val entries = feed.events.filter { it.url.contains("$prefix-series-") }
            entries shouldHaveSize 1
            (
                entries
                    .single()
                    .recurrence
                    ?.sentence
                    ?.isNotBlank()
            ) shouldBe true
        }
    })
