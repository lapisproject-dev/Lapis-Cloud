package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation" -- which of the two OPTIONAL chrome tabs
 * ([PublicChrome.NavTarget.ARTICLES]/[PublicChrome.NavTarget.EVENTS]) should be shown, computed from
 * whether the underlying overview ([PublicArticlesOverviewRoutes]/[PublicEventsOverviewRoutes]) would
 * actually show anything. `NONE` is the fixed value every error page ([SocialPublicHtml.notFoundPage]
 * et al.) renders with -- error pages never touch the DB (see [PublicChrome.renderChrome] KDoc "the
 * WCAG..." and this file's own class KDoc "Fehlerseiten" note).
 */
internal data class PublicNavAvailability(
    val articles: Boolean,
    val events: Boolean,
    /** Welle V1.9.20 -- `/vorstand`: at least one board card ([PublicProfilesReader.loadBoardCards]). */
    val board: Boolean = false,
    /** Welle V1.9.20 -- `/politiker`: at least one listed politician ([PublicProfilesReader.loadPoliticianCards]). */
    val politicians: Boolean = false,
    /** Welle V1.9.20 -- `/landesverbaende`: at least one chapter ([PublicProfilesReader.loadChapterCards]). */
    val chapters: Boolean = false,
) {
    companion object {
        val NONE = PublicNavAvailability(articles = false, events = false, board = false, politicians = false, chapters = false)
    }
}

/**
 * Runs the two existence checks that decide [PublicNavAvailability] -- **MUST stay identical** to
 * the filters [PublicArticlesOverviewRoutes]/[PublicEventsOverviewRoutes] themselves use
 * ([ArticleStore.listPublishedNewestFirst]'s `status eq PUBLISHED` / [EventIcsFeed
 * .loadUpcomingPublicPublished]'s `visibility eq PUBLIC and status eq PUBLISHED and endsAt greater
 * now`) -- a tab that is shown but leads to an empty overview (or vice versa) would violate Raskin's
 * "no dead end, no lie" nav-availability contract this welle exists to establish. `PublicNavAvailabilityTest`
 * "T3c" cross-checks both pairs directly against a shared fixture, rather than sharing the query code
 * itself: [ArticleStore.listPublishedNewestFirst]/[EventIcsFeed.loadUpcomingPublicPublished] both load
 * full rows for rendering, while this check only ever needs `LIMIT 1` existence -- two narrow,
 * independent `WHERE`-only queries are cheaper here than reusing (and then discarding) either loader's
 * result.
 *
 * Runs its own `transaction {}` -- never call this from inside another open transaction (see
 * [PublicNavAvailabilityProvider.current] KDoc "S2, keine verschachtelte Transaktion").
 */
internal fun loadPublicNavAvailability(now: LocalDateTime = DbClock.nowLocalDateTime()): PublicNavAvailability =
    transaction {
        PublicNavAvailability(
            articles =
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.status eq ArticleStatus.PUBLISHED }
                    .limit(1)
                    .empty()
                    .not(),
            events =
                EventTable
                    .selectAll()
                    .where {
                        (EventTable.visibility eq EventVisibility.PUBLIC) and
                            (EventTable.status eq EventStatus.PUBLISHED) and
                            (EventTable.endsAt greater now)
                    }.limit(1)
                    .empty()
                    .not(),
            // Welle V1.9.20 -- the SAME loaders the pages and feeds use, LIMIT 1 -- one definition of
            // "visible", so a tab can never lead to an empty page (or a page exist without its tab).
            board = PublicProfilesReader.loadBoardCards(limit = 1).isNotEmpty(),
            politicians = PublicProfilesReader.loadPoliticianCards(limit = 1).isNotEmpty(),
            chapters = PublicProfilesReader.loadChapterCards(limit = 1).isNotEmpty(),
        )
    }

/**
 * Process-lifetime, 30-second-TTL memo of [loadPublicNavAvailability] -- ONE instance shared by every
 * `register*PublicRoutes` call in `Application.kt` (see [network.lapis.cloud.server.Application]'s own
 * `publicNavAvailability` wiring), so every public page's chrome agrees on the same snapshot within the
 * same short window, instead of each page issuing its own pair of `LIMIT 1` queries on every request.
 *
 * A production installation may show a just-published article/event up to [ttl] late in the chrome
 * (bounded staleness, same posture [PublicLandingRoutes]' own body memo already establishes) -- never
 * early: a lookup failure (see below) never optimistically shows a tab.
 *
 * **Welle V1.9.20, accepted trade-off**: a tab for `/vorstand`, `/politiker` or `/landesverbaende`
 * can remain visible for up to [ttl] after the last entry was withdrawn -- it then leads to a page
 * with an empty state (HTTP 200), never to personal data: the pages themselves are NOT cached (see
 * [PublicProfilesOverviewRoutes]), only the visibility of the tab is delayed.
 *
 * A failed lookup (a transient DB hiccup) is **never cached** -- [current] falls back to the previous
 * snapshot's value (or [PublicNavAvailability.NONE] if there is none yet) but the NEXT call retries the
 * loader immediately, rather than freezing a failure for a full [ttl].
 */
class PublicNavAvailabilityProvider internal constructor(
    private val ttl: Duration = 30.seconds,
    private val clock: Clock = Clock.System,
    private val loader: () -> PublicNavAvailability = { loadPublicNavAvailability() },
) {
    /** Public constructor for `Application.kt` -- the internal one above exists so tests can inject a fake clock/loader. */
    constructor() : this(ttl = 30.seconds)

    private data class Snapshot(
        val value: PublicNavAvailability,
        val expiresAt: Instant,
    )

    private val snapshot = AtomicReference<Snapshot?>(null)

    internal fun current(): PublicNavAvailability {
        val now = clock.now()
        snapshot.get()?.let { cached -> if (cached.expiresAt > now) return cached.value }
        return try {
            loader().also { snapshot.set(Snapshot(value = it, expiresAt = now + ttl)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "public nav availability lookup failed; rendering without the optional public tabs" }
            snapshot.get()?.value ?: PublicNavAvailability.NONE
        }
    }
}
