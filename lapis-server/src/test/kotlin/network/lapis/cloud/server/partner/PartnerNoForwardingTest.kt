package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes

/** V1.9.245 -- what we cached from the partners is never passed on: not in our own partner feed, not in the embed feed. */
class PartnerNoForwardingTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { wipePartnerTables() }
        afterSpec {
            wipePartnerTables()
            transaction { EventTable.deleteWhere { EventTable.slug eq "intern-treffen" } }
        }

        test(
            "after a recursion run our own feed carries neither partner events nor discovered servers, and members-only events never show up",
        ) {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"), displayName = "Alpha", now = realNow())
            world.serve(
                host = "b.beispiel.de",
                name = "Bravo-Server",
                startsAt = feedTime(days = 4),
                endsAt = feedTime(days = 4, hour = 21),
            )
            tickWorld(world)
            transaction { PartnerDiscoveryStore.countsByRoot().values.sumOf { it.first } } shouldBe 1
            insertPartnerTestEvent(slug = "intern-treffen", title = "Intern", visibility = EventVisibility.MEMBERS_ONLY)
            val limiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)
            testApplication {
                application { routing { registerPartnerFeedRoutes(rateLimiter = limiter) } }
                val body = client.get("/api/public/partner-feed/v1").bodyAsText()
                val feed = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), body)
                feed.events.none { it.url.contains("beispiel.de") } shouldBe true
                body shouldNotContain "b.beispiel.de"
                body shouldNotContain "Bravo-Server"
                body shouldNotContain "Direkt"
                body shouldNotContain "Intern"
            }
        }
    })
