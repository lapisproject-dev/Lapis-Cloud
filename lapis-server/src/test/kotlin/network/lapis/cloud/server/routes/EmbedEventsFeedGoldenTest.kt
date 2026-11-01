package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partner.PARTNER_TEST_MEMBER
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.248 -- GOLDEN test of the default `GET /api/embed/v1/events` body. It was written and committed BEFORE the route learned `?quelle=alle`,
 * and pins that the default answer (no parameter, or any value other than exactly one `alle`) stays byte-identical, because the websites of
 * the party (zod schema) and the church read it.
 *
 * The database is shared with other specs, so the comparison covers the entries this spec seeded (fixed slugs, year 2099); they are
 * re-encoded with the same compact encoder, key order and all. The full body of every variant is compared byte for byte with the default body.
 */
class EmbedEventsFeedGoldenTest :
    FunSpec({
        val slugs = listOf("golden-feed-a", "golden-feed-b", "golden-feed-c")
        val json = Json

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            cleanup(slugs)
            seed()
        }
        afterSpec { cleanup(slugs) }

        fun generous() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val config =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun app(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    routing {
                        registerEmbedEventsFeedRoutes(
                            config = config,
                            baseUrl = "https://lapis.example",
                            feedRateLimiter = generous(),
                            preflightRateLimiter = generous(),
                            pastFeedRateLimiter = generous(),
                        )
                    }
                }
                block()
            }

        fun ownEntries(body: String): String {
            val events =
                json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("events")
                    .jsonArray
            val mine =
                events.filter {
                    it.jsonObject
                        .getValue("slug")
                        .jsonPrimitive.content in slugs
                }
            return json.encodeToString(JsonArray.serializer(), JsonArray(mine))
        }

        val fixture =
            File("src/test/resources/embed/events-feed-default.golden.json").let {
                if (it.exists()) it else File("lapis-server/src/test/resources/embed/events-feed-default.golden.json")
            }

        test("the default body keeps its exact wire format") {
            app {
                val body = client.get("/api/embed/v1/events") { header("Origin", "https://partei.example") }.bodyAsText()
                body.startsWith("""{"events":[""") shouldBe true
                ownEntries(body) shouldBe fixture.readText().trim()
            }
        }

        test("every spelling other than one single alle gives the byte-identical default body") {
            app {
                suspend fun body(query: String) =
                    client.get("/api/embed/v1/events$query") { header("Origin", "https://partei.example") }.bodyAsText()
                val default = body("")
                for (q in listOf(
                    "?quelle=Alle",
                    "?quelle=xyz",
                    "?quelle=eigene",
                    "?quelle=",
                    "?quelle=alle&quelle=alle",
                    "?quelle=xyz&quelle=alle",
                    "?quelle=alle&quelle=xyz",
                )) {
                    body(q) shouldBe default
                }
            }
        }

        test("schema mirror: top level is exactly events, entries stay inside the known field set") {
            val known =
                setOf(
                    "slug",
                    "title",
                    "startsAt",
                    "endsAt",
                    "locationText",
                    "registrationUrl",
                    "full",
                    "feeAmount",
                    "feeCurrency",
                    "coverImageUrl",
                    "description",
                    "summary",
                    "coverImageAlt",
                    "onlineUrl",
                    "online",
                )
            app {
                val root = json.parseToJsonElement(client.get("/api/embed/v1/events").bodyAsText()).jsonObject
                root.keys shouldBe setOf("events")
                val mine =
                    root.getValue("events").jsonArray.map { it.jsonObject }.filter {
                        it.getValue("slug").jsonPrimitive.content in
                            slugs
                    }
                mine.size shouldBe slugs.size
                for (e in mine) {
                    known.containsAll(e.keys) shouldBe true
                    e.keys shouldContainAll
                        listOf("slug", "title", "startsAt", "endsAt", "registrationUrl", "full", "feeAmount", "feeCurrency")
                    e.getValue("feeAmount").jsonPrimitive.content shouldMatch Regex("""^\d{1,7}\.\d{2}$""")
                    e.getValue("feeCurrency").jsonPrimitive.content shouldMatch Regex("^[A-Z]{3}$")
                    e.getValue("startsAt").jsonPrimitive.content shouldMatch Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")
                    e.getValue("registrationUrl").jsonPrimitive.content shouldBe
                        "https://lapis.example/veranstaltung/${e.getValue("slug").jsonPrimitive.content}"
                }
            }
        }
    })

private fun cleanup(slugs: List<String>) {
    transaction {
        val ids = EventTable.selectAll().where { EventTable.slug inList slugs }.map { it[EventTable.id] }
        if (ids.isNotEmpty()) {
            EventRegistrationTable.deleteWhere { eventId inList ids }
            EventTable.deleteWhere { id inList ids }
        }
    }
}

private fun seed() {
    data class Seed(
        val slug: String,
        val title: String,
        val start: LocalDateTime,
        val loc: String?,
        val cap: Int?,
        val fee: String,
        val online: String?,
        val summary: String?,
    )
    val seeds =
        listOf(
            Seed(
                slug = "golden-feed-a",
                title = "Bürgerabend im Rathaus",
                start = LocalDateTime(2099, 1, 15, 18, 0),
                loc = "Rathaus, Große Straße 1",
                cap = 10,
                fee = "0.00",
                online = null,
                summary = "Kurzer Teaser",
            ),
            Seed(
                slug = "golden-feed-b",
                title = "Sommerfest",
                start = LocalDateTime(2099, 7, 15, 14, 0),
                loc = null,
                cap = 1,
                fee = "12.50",
                online = "https://meet.example/golden",
                summary = null,
            ),
            Seed(
                slug = "golden-feed-c",
                title = "Online-Vortrag",
                start = LocalDateTime(2099, 9, 1, 19, 30),
                loc = "Online",
                cap = null,
                fee = "0.00",
                online = "https://meet.example/vortrag",
                summary = "Vortrag über Äpfel",
            ),
        )
    transaction {
        for (s in seeds) {
            val id = Uuid.random()
            EventTable.insert {
                it[EventTable.id] = id
                it[slug] = s.slug
                it[title] = s.title
                it[description] = "Beschreibung zu ${s.title}.\n\nZweiter Absatz."
                it[locationText] = s.loc
                it[onlineUrl] = s.online
                it[onlineKind] = if (s.online != null) EventOnlineKind.EXTERNAL_LINK else EventOnlineKind.NONE
                it[onlineUrlPublic] = s.online != null
                it[summary] = s.summary
                it[startsAt] = s.start
                it[endsAt] = LocalDateTime(s.start.year, s.start.monthNumber, s.start.dayOfMonth, s.start.hour + 2, s.start.minute)
                it[capacity] = s.cap
                it[feeAmount] = BigDecimal(s.fee)
                it[feeCurrency] = "EUR"
                it[status] = EventStatus.PUBLISHED
                it[visibility] = EventVisibility.PUBLIC
                it[registrationClosesAt] = null
                it[createdAt] = DbClock.nowLocalDateTime()
                it[createdBy] = PARTNER_TEST_MEMBER
                it[cancelledAt] = null
            }
        }
    }
}
