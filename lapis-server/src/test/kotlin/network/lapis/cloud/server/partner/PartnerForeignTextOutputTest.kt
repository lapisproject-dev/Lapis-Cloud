package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData

private const val EVIL_NAME = "\"><img src=x onerror=alert(1)>"

/** V1.9.245 -- hostile text from a partner feed, through the whole path into the public HTML and the iCal feed. */
class PartnerForeignTextOutputTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            wipePartnerTables()
            PartnerDisplayEpoch.bump()
        }
        afterSpec { wipePartnerTables() }

        fun hostileWorld(): FakeWorld {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"), displayName = "Alpha", now = realNow())
            val start = feedTime(days = 4)
            val end = feedTime(days = 4, hour = 21)
            world.feeds["b.beispiel.de"] =
                feedJson(
                    events =
                        listOf(
                            feedEventJson(
                                id = "x1",
                                title = "<script>alert(1)</script>",
                                startsAt = start,
                                endsAt = end,
                                url = "https://b.beispiel.de/e/x1",
                            ),
                            feedEventJson(
                                id = "x2",
                                title = "Gut\r\nATTENDEE:evil‮gnp",
                                startsAt = start,
                                endsAt = end,
                                url = "https://b.beispiel.de/e/x2",
                            ),
                            feedEventJson(id = "x3", title = "Link ohne Ziel", startsAt = start, endsAt = end, url = "javascript:alert(1)"),
                            feedEventJson(
                                id = "x4",
                                title = "Fremdes Ziel",
                                startsAt = start,
                                endsAt = end,
                                url = "https://evil.example.net/phish",
                            ),
                        ),
                    serverId = kotlin.uuid.Uuid.random(),
                    name = EVIL_NAME + "x".repeat(80),
                )
            return world
        }

        test("HTML: markup is escaped, a surrogate-safe name is cut to 60 characters, javascript: and foreign targets never become links") {
            val world = hostileWorld()
            tickWorld(world)
            partnerPublicApp {
                val body = client.get("/veranstaltungen").bodyAsText()
                body shouldNotContain "<script>alert"
                body shouldNotContain "<img src=x"
                body shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
                body shouldNotContain "javascript:"
                body shouldNotContain "evil.example.net"
                body shouldNotContain "Link ohne Ziel"
                body shouldNotContain "Fremdes Ziel"
                body shouldNotContain "‮"
                // the name is shown with an ellipsis after at most 60 characters
                body shouldContain "…"
                body shouldNotContain "x".repeat(70)
                body shouldContain "Gut ATTENDEE:evil"
            }
        }

        test("iCal: no control character or line break from a partner starts a new property") {
            val world = hostileWorld()
            tickWorld(world)
            partnerPublicApp {
                val ics = client.get("/veranstaltung.ics?quelle=alle").bodyAsText()
                val unfolded = ics.replace("\r\n ", "")
                unfolded.lines().none { it.startsWith("ATTENDEE") } shouldBe true
                unfolded shouldNotContain "‮"
                unfolded.lines().filter { it.startsWith("SUMMARY:") }.any { it.contains("ATTENDEE:evil") } shouldBe true
                unfolded shouldNotContain "javascript:"
                unfolded shouldNotContain "evil.example.net"
            }
        }
    })
