package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.245 -- the whole chain on H2: three levels over a fake network, the poller tick, the public overview and the iCal feed. */
class PartnerRecursionScenarioTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            wipePartnerTables()
            PartnerDisplayEpoch.bump()
        }
        afterSpec {
            wipePartnerTables()
            transaction { EventTable.deleteWhere { EventTable.slug eq "eigenes-treffen" } }
        }

        fun seedWorld(): FakeWorld {
            val world = FakeWorld()
            val now = realNow()
            addDirectPartner(
                host = "a.beispiel.de",
                partners = listOf("b.beispiel.de"),
                displayName = "Alpha Verein",
                eventStart = LocalDateTime(now.year + 1, 1, 10, 18, 0, 0),
                eventEnd = LocalDateTime(now.year + 1, 1, 10, 20, 0, 0),
                now = now,
            )
            world.serve(
                host = "b.beispiel.de",
                partners = listOf("c.beispiel.de"),
                name = "Bravo",
                startsAt = feedTime(days = 5),
                endsAt = feedTime(days = 5, hour = 21),
                extra = ""","recurrence":{"sentence":"jeden Montag"}""",
            )
            world.serve(host = "c.beispiel.de", name = "Charlie", startsAt = feedTime(days = 6), endsAt = feedTime(days = 6, hour = 21))
            return world
        }

        test("/veranstaltungen: marks for hop 1 and hop >= 2, the source filter, the link attributes, the series as one entry") {
            val world = seedWorld()
            tickWorld(world)
            partnerPublicApp {
                val body = client.get("/veranstaltungen").bodyAsText()
                body shouldContain "Partnerserver: Alpha Verein"
                body shouldContain "Partnerserver: Bravo (b.beispiel.de) · über Alpha Verein"
                body shouldContain "Partnerserver: Charlie (c.beispiel.de) · über Alpha Verein"
                body shouldContain "aria-label=\"Herkunft der Veranstaltungen\""
                body shouldContain "aria-current=\"page\""
                body shouldContain "rel=\"noopener noreferrer external\""
                body shouldContain "referrerpolicy=\"no-referrer\""
                body shouldContain "(externe Seite)"
                body shouldContain "href=\"https://b.beispiel.de/veranstaltung/e1\""
                body shouldNotContain "target="
                body shouldContain "Reihe: jeden Montag"
                body shouldNotContain "Alle Termine"
                body shouldContain "public-event--partner"
            }
        }

        test(
            "?quelle=eigene lists no partner event and is noindex; an unknown value redirects to the plain URL; no filter without partners",
        ) {
            val world = seedWorld()
            tickWorld(world)
            partnerPublicApp {
                val own = client.get("/veranstaltungen?quelle=eigene")
                own.status shouldBe HttpStatusCode.OK
                val body = own.bodyAsText()
                body shouldNotContain "Partnerserver:"
                body shouldContain "noindex,follow"
                body shouldContain "Derzeit keine eigenen Veranstaltungen."
                val noFollow = createClient { followRedirects = false }
                val bad = noFollow.get("/veranstaltungen?quelle=xyz")
                (bad.status.value in 300..399) shouldBe true
                bad.headers[HttpHeaders.Location]!! shouldNotContain "quelle"
                val twice = noFollow.get("/veranstaltungen?quelle=eigene&quelle=alle")
                (twice.status.value in 300..399) shouldBe true
                val alle = noFollow.get("/veranstaltungen?quelle=alle")
                (alle.status.value in 300..399) shouldBe true
                alle.headers[HttpHeaders.Location]!! shouldNotContain "quelle"
            }
            wipePartnerTables()
            PartnerDisplayEpoch.bump()
            partnerPublicApp {
                val plain = client.get("/veranstaltungen").bodyAsText()
                plain shouldNotContain "Herkunft der Veranstaltungen"
                plain shouldNotContain "Partnerserver:"
            }
        }

        test("own and partner events are merged by time, own first at equal time") {
            val world = seedWorld()
            tickWorld(world)
            val wall = ServerClock.nowIn(OrganizationTimeZone.current())
            val ownStart = LocalDateTime(wall.year + 2, 3, 1, 19, 0, 0)
            insertPartnerTestEvent(
                slug = "eigenes-treffen",
                title = "Eigenes Treffen",
                startsAt = ownStart,
                endsAt = LocalDateTime(ownStart.year, 3, 1, 21, 0, 0),
            )
            partnerPublicApp {
                val body = client.get("/veranstaltungen").bodyAsText()
                (body.indexOf("Partnerserver: Charlie") < body.indexOf("Eigenes Treffen")) shouldBe true
                body shouldContain "href=\"http://localhost:8080/veranstaltung/eigenes-treffen\""
                client.get("/veranstaltungen?quelle=eigene").bodyAsText() shouldContain "Eigenes Treffen"
            }
        }

        test(
            "a block empties the memoised page at once; the ICS stays own-only without the parameter and carries partners with quelle=alle",
        ) {
            val world = seedWorld()
            tickWorld(world)
            partnerPublicApp {
                client.get("/veranstaltungen").bodyAsText() shouldContain "Partnerserver: Bravo"
                val plain = client.get("/veranstaltung.ics").bodyAsText()
                plain shouldNotContain "partner-"
                plain shouldNotContain "CATEGORIES:Partnerserver"
                val other = client.get("/veranstaltung.ics?quelle=eigene").bodyAsText()
                other shouldNotContain "CATEGORIES:Partnerserver"
                val ics = client.get("/veranstaltung.ics?quelle=alle").bodyAsText().replace("\r\n ", "")
                ics shouldContain "CATEGORIES:Partnerserver"
                ics shouldContain "UID:partner-"
                ics shouldContain "@example.org"
                ics shouldContain "URL:https://b.beispiel.de/veranstaltung/e1"
                ics shouldContain "Partnerserver: Bravo (b.beispiel.de) · über Alpha Verein"
                ics shouldNotContain "RRULE"
                // the block: the memoised overview changes immediately, the iCal feed too
                transaction { PartnerServerStore.addBlock(pattern = "b.beispiel.de", now = realNow()) }
                PartnerDisplayEpoch.bump()
                val after = client.get("/veranstaltungen").bodyAsText()
                after shouldNotContain "Partnerserver: Bravo"
                after shouldNotContain "Partnerserver: Charlie"
                client.get("/veranstaltung.ics?quelle=alle").bodyAsText() shouldNotContain "b.beispiel.de/veranstaltung"
            }
        }
    })
