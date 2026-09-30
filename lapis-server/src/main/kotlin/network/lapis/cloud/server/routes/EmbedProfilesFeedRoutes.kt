package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.embed.respondEmbedPreflight
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

private val EMBED_PROFILES_FEED_JSON = Json { explicitNulls = false }
private val EMBED_PROFILES_FEED_JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

/**
 * One board member in `GET /api/embed/v1/board`. Explicit allowlist -- deliberately NOT derived from
 * any domain DTO: the wire JSON must never contain an id, an e-mail, a `since` date, trust or like
 * figures or a member count (`EmbedProfilesFeedRoutesTest` asserts the exact key set on the RAW
 * JSON). [role] is the stable enum name, [roleLabel] the German display label.
 */
@Serializable
internal data class EmbedBoardMemberDto(
    val name: String,
    val role: String,
    val roleLabel: String,
    val photoUrl: String? = null,
    val bio: String? = null,
)

@Serializable
internal data class EmbedBoardResponse(
    val board: List<EmbedBoardMemberDto>,
)

/** One listed politician in `GET /api/embed/v1/politicians` -- explicit allowlist, see [EmbedBoardMemberDto]. No ranking, no trust figures. */
@Serializable
internal data class EmbedPoliticianDto(
    val name: String,
    val office: String? = null,
    val photoUrl: String? = null,
    val bio: String? = null,
)

@Serializable
internal data class EmbedPoliticiansResponse(
    val politicians: List<EmbedPoliticianDto>,
)

/** One chapter in `GET /api/embed/v1/chapters` -- explicit allowlist, see [EmbedBoardMemberDto]. */
@Serializable
internal data class EmbedChapterDto(
    val name: String,
    val crestUrl: String? = null,
    val description: String? = null,
)

@Serializable
internal data class EmbedChaptersResponse(
    val chapters: List<EmbedChapterDto>,
)

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- `GET /api/embed/v1/board`, `/politicians`, `/chapters` and
 * their `OPTIONS` preflights. 1:1 structural mirror of [registerEmbedArticlesFeedRoutes]: called from
 * [registerEmbedRoutes] INSIDE the `LAPIS_EMBED_ENABLED` gate, per-handler CORS via
 * [applyEmbedCors] (a present-but-disallowed `Origin` -> 403 BEFORE any database access), an IP
 * rate limit before the database, `X-Content-Type-Options: nosniff`, plain try/catch (never
 * `runCatching`, which would swallow a client-disconnect cancellation).
 *
 * **The data is the page's data**: the feeds call the SAME [PublicProfilesReader] loaders as
 * `/vorstand`, `/politiker`, `/landesverbaende` and the nav availability check -- one definition of
 * "visible". **No `limit` parameter**: the fixed caps of [PublicProfilesLimits] always apply, which
 * keeps the lists from being enumerable beyond what the page shows.
 *
 * **`Cache-Control: no-store` is set by [applyEmbedCors] itself** (unconditionally, before the
 * allow/reject decision, including the no-`Origin` case). `response.header` APPENDS, so it must not
 * be set a second time here -- `EmbedProfilesFeedRoutesTest` asserts it occurs exactly once.
 * A withdrawn consent therefore vanishes from the feed on the very next request.
 */
internal fun Route.registerEmbedProfilesFeedRoutes(
    config: EmbedConfig,
    baseUrl: String,
    feedRateLimiter: FederationInboxRateLimiter,
    preflightRateLimiter: FederationInboxRateLimiter,
) {
    registerProfilesFeed(
        path = "/api/embed/v1/board",
        config = config,
        feedRateLimiter = feedRateLimiter,
        preflightRateLimiter = preflightRateLimiter,
    ) {
        val cards = transaction { PublicProfilesReader.loadBoardCards() }
        val items =
            cards.map { item ->
                EmbedBoardMemberDto(
                    name = item.card.name,
                    role = item.role.name,
                    roleLabel = item.role.publicLabel(PublicChrome.stringsFor(PublicLanguage.DE)),
                    photoUrl = PublicProfileUrls.photoUrl(baseUrl = baseUrl, token = item.card.photoToken),
                    bio = item.card.bio,
                )
            }
        EMBED_PROFILES_FEED_JSON.encodeToString(EmbedBoardResponse.serializer(), EmbedBoardResponse(items))
    }
    registerProfilesFeed(
        path = "/api/embed/v1/politicians",
        config = config,
        feedRateLimiter = feedRateLimiter,
        preflightRateLimiter = preflightRateLimiter,
    ) {
        val cards = transaction { PublicProfilesReader.loadPoliticianCards() }
        val items =
            cards.map { card ->
                EmbedPoliticianDto(
                    name = card.name,
                    office = card.roleOrOffice,
                    photoUrl = PublicProfileUrls.photoUrl(baseUrl = baseUrl, token = card.photoToken),
                    bio = card.bio,
                )
            }
        EMBED_PROFILES_FEED_JSON.encodeToString(EmbedPoliticiansResponse.serializer(), EmbedPoliticiansResponse(items))
    }
    registerProfilesFeed(
        path = "/api/embed/v1/chapters",
        config = config,
        feedRateLimiter = feedRateLimiter,
        preflightRateLimiter = preflightRateLimiter,
    ) {
        val cards = transaction { PublicProfilesReader.loadChapterCards() }
        val items =
            cards.map { card ->
                EmbedChapterDto(
                    name = card.name,
                    crestUrl = PublicProfileUrls.crestUrl(baseUrl = baseUrl, token = card.crestToken),
                    description = card.description,
                )
            }
        EMBED_PROFILES_FEED_JSON.encodeToString(EmbedChaptersResponse.serializer(), EmbedChaptersResponse(items))
    }
}

private fun Route.registerProfilesFeed(
    path: String,
    config: EmbedConfig,
    feedRateLimiter: FederationInboxRateLimiter,
    preflightRateLimiter: FederationInboxRateLimiter,
    buildJson: () -> String,
) {
    get(path) {
        call.withEmbedProfilesFeedErrorHandling {
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            if (cors is EmbedCorsResult.Rejected) {
                call.respondEmbedForbiddenOrigin()
                return@withEmbedProfilesFeedErrorHandling
            }

            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!feedRateLimiter.checkAndRecord(rateLimitKey)) {
                call.response.header(HttpHeaders.RetryAfter, feedRateLimiter.retryAfterSeconds(rateLimitKey).toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@withEmbedProfilesFeedErrorHandling
            }

            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondText(text = buildJson(), contentType = EMBED_PROFILES_FEED_JSON_CONTENT_TYPE, status = HttpStatusCode.OK)
        }
    }

    options(path) {
        if (!preflightRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@options
        }
        val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
        if (cors is EmbedCorsResult.Rejected) {
            call.respondEmbedForbiddenOrigin()
        } else {
            call.respondEmbedPreflight(allowedMethods = "GET, OPTIONS")
        }
    }
}

/** Plain `try`/`catch`, NOT `runCatching` (would also swallow [CancellationException] on a client disconnect). Nothing personal is ever logged. */
private suspend fun ApplicationCall.withEmbedProfilesFeedErrorHandling(handler: suspend () -> Unit) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Unhandled exception in an embed profiles-feed handler (${request.path()})" }
        runCatching { respond(HttpStatusCode.InternalServerError) }
    }
}
