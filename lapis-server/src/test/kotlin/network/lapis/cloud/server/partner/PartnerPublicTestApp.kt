package network.lapis.cloud.server.partner

import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.routes.PublicNavAvailabilityProvider
import network.lapis.cloud.server.routes.eventTestBranding
import network.lapis.cloud.server.routes.eventTestEmbedConfig
import network.lapis.cloud.server.routes.eventTestNav
import network.lapis.cloud.server.routes.registerEventPublicRoutes
import network.lapis.cloud.server.routes.registerPublicEventsOverviewRoutes
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.ServerClock
import kotlin.time.Duration.Companion.minutes

/** A real-clock "now" for the public-page scenarios (the pages read the database clock, so the cache must be fresh relative to it). */
internal fun realNow(): LocalDateTime = ServerClock.now()

/** `2026-11-12T19:00:00+00:00`-style offset time [days] days from the real now (UTC). */
internal fun feedTime(
    days: Long,
    hour: Int = 19,
): String =
    java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        java.time.OffsetDateTime
            .now(java.time.ZoneOffset.UTC)
            .plusDays(days)
            .withHour(hour)
            .withMinute(0)
            .withSecond(0)
            .withNano(0),
    )

/** The public overview and the iCal route on a test application. The ICS host is `example.org`. */
internal fun partnerPublicApp(block: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
        val generous = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)
        application {
            install(XForwardedHeaders) { useLastProxy() }
            install(AutoHeadResponse)
            routing {
                val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
                val nav = PublicNavAvailabilityProvider()
                registerPublicEventsOverviewRoutes(readRateLimiter = generous, branding = branding, navAvailability = nav)
                registerEventPublicRoutes(
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher =
                        MailDispatcher(
                            transport = NoOpMailTransport(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        ),
                    branding = eventTestBranding(),
                    navAvailability = eventTestNav(),
                    embedConfig = eventTestEmbedConfig(),
                    pageRateLimiter = generous,
                    attemptRateLimiter = generous,
                    registrationRateLimiter = generous,
                    ticketPageRateLimiter = generous,
                    ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                    icsFeedRateLimiter = generous,
                )
            }
        }
        block()
    }

/** Runs the poller once (recursion included) on the world, with the real "now". */
internal suspend fun tickWorld(world: FakeWorld) {
    val now = realNow()
    val recursion =
        PartnerRecursionRunner(fetcher = world.fetcher(), clock = { now }, publicBaseUrl = "https://own.example.com")
    PartnerFeedPoller(fetcher = world.fetcher(), clock = { now }, recursion = recursion).tick()
}
