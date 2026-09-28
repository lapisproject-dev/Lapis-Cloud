package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.articles.ArticleCoverPolicy
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.requireActiveMembership
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ArticleCoverResultDto
import network.lapis.cloud.shared.domain.ArticleStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- upload/remove/serve routes for an
 * article's own cover image, under `/api/articles/{id}`. Reuses [network.lapis.cloud.server.events
 * .EventCoverImageProcessor]/[EventCoverStorage]/the shared upload pipeline in
 * `CoverUploadSupport.kt` 1:1 with an article-specific [EventCoverStorage] instance (root
 * `article-covers/`, see `Application.kt` wiring) -- no new image processing/storage code, same
 * "kein Neubau" discipline `ArticleCoverPolicy`'s own KDoc establishes.
 *
 * **Two-step ownership/status check around the upload**, same "Lock-Reihenfolge" the implementation
 * plan documents as Stolperfalle §3: a cheap, NON-locking pre-check runs BEFORE the (potentially
 * slow) multipart receive/re-encode, so an obviously-wrong request (wrong author, wrong status)
 * fails fast without ever writing a file. The upload itself still writes to disk BEFORE any lock is
 * taken (same ordering [EventCoverRoutes] already establishes -- writing the file is the slow part,
 * and it must not happen while holding a row lock). A SECOND check, this time `FOR UPDATE`-locked,
 * re-verifies ownership/status right before [ArticleStore.setCoverImageId] -- if the article was
 * concurrently submitted/approved/deleted between the pre-check and this point, the just-written
 * file is deleted and the request fails with the SAME status code the pre-check would have used.
 *
 * **CSRF**: same `Origin`/`Sec-Fetch-Site` same-origin check [EventCoverRoutes] uses (a
 * `multipart/form-data` POST is a "simple request", no CORS preflight applies) -- see
 * `CoverUploadSupport.isSameOriginRequest` KDoc.
 *
 * **Existence-oracle discipline on the GET route**: a fremdes Mitglied and an anonymous caller both
 * see the IDENTICAL 404 a genuinely-missing article/cover gets -- never a 401/403 (mirrors
 * [EventCoverRoutes]' own GET route KDoc "Existence-oracle discipline"). The AUTHOR sees their own
 * cover in every status; a BOARD/ADMIN reviewer sees it in every status EXCEPT `DRAFT` (an
 * unsubmitted draft is not theirs to look at yet).
 */
internal fun Route.registerArticleCoverRoutes(
    storage: EventCoverStorage,
    baseUrl: String,
    writeRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/articles/{id}/cover") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respond(HttpStatusCode.Forbidden, "Invalid origin")
            return@post
        }
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }

        if (!writeRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.response.header(HttpHeaders.RetryAfter, writeRateLimiter.retryAfterSeconds("member:${current.memberId}").toString())
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@post
        }

        val articleId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (articleId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }

        // Cheap pre-check BEFORE the slow upload -- see class KDoc "Two-step ownership/status check".
        val preCheck = transaction { ArticleStore.getOrNull(articleId) }
        if (preCheck == null || preCheck[ArticleTable.authorId] != current.memberId) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        if (preCheck[ArticleTable.status] != ArticleStatus.DRAFT && preCheck[ArticleTable.status] != ArticleStatus.REJECTED) {
            call.respond(HttpStatusCode.Conflict, "Article $articleId is not editable in status ${preCheck[ArticleTable.status]}")
            return@post
        }

        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > COVER_UPLOAD_MAX_BYTES_WITH_MULTIPART_OVERHEAD) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${EventCoverPolicy.MAX_UPLOAD_BYTES} bytes")
            return@post
        }
        val ok = call.receiveSingleCoverUpload() ?: return@post

        val newId = Uuid.random()
        storage.write(id = newId, format = ok.format, bytes = ok.bytes)

        val setOutcome: ArticleSetCoverOutcome =
            try {
                transaction {
                    val locked = ArticleStore.lockForUpdate(articleId)
                    when {
                        locked == null || locked[ArticleTable.authorId] != current.memberId -> ArticleSetCoverOutcome.NotFound
                        locked[ArticleTable.status] != ArticleStatus.DRAFT && locked[ArticleTable.status] != ArticleStatus.REJECTED ->
                            ArticleSetCoverOutcome.WrongStatus(locked[ArticleTable.status])
                        else -> ArticleSetCoverOutcome.Set(ArticleStore.setCoverImageId(id = articleId, coverImageId = newId))
                    }
                }
            } catch (e: Exception) {
                storage.delete(newId)
                throw e
            }
        val previousId =
            when (setOutcome) {
                ArticleSetCoverOutcome.NotFound -> {
                    storage.delete(newId)
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                is ArticleSetCoverOutcome.WrongStatus -> {
                    storage.delete(newId)
                    call.respond(HttpStatusCode.Conflict, "Article $articleId is not editable in status ${setOutcome.status}")
                    return@post
                }
                is ArticleSetCoverOutcome.Set -> setOutcome.previousCoverImageId
            }
        if (previousId != null) storage.delete(previousId)

        logger.info { "Article cover set: articleId=$articleId memberId=${current.memberId} action=cover.set bytes=${ok.bytes.size}" }

        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            HttpStatusCode.OK,
            ArticleCoverResultDto(
                coverImageUrl =
                    ArticleCoverPolicy.authenticatedCoverImageUrl(
                        baseUrl = baseUrl,
                        articleId = articleId,
                        coverImageId = newId,
                    ),
            ),
        )
    }

    delete("/api/articles/{id}/cover") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respond(HttpStatusCode.Forbidden, "Invalid origin")
            return@delete
        }
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }

        if (!writeRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.response.header(HttpHeaders.RetryAfter, writeRateLimiter.retryAfterSeconds("member:${current.memberId}").toString())
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@delete
        }

        val articleId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (articleId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@delete
        }

        val setOutcome =
            transaction {
                val locked = ArticleStore.lockForUpdate(articleId)
                when {
                    locked == null || locked[ArticleTable.authorId] != current.memberId -> ArticleSetCoverOutcome.NotFound
                    locked[ArticleTable.status] != ArticleStatus.DRAFT && locked[ArticleTable.status] != ArticleStatus.REJECTED ->
                        ArticleSetCoverOutcome.WrongStatus(locked[ArticleTable.status])
                    else -> ArticleSetCoverOutcome.Set(ArticleStore.setCoverImageId(id = articleId, coverImageId = null))
                }
            }
        when (setOutcome) {
            ArticleSetCoverOutcome.NotFound -> {
                call.respond(HttpStatusCode.NotFound)
                return@delete
            }
            is ArticleSetCoverOutcome.WrongStatus -> {
                call.respond(HttpStatusCode.Conflict, "Article $articleId is not editable in status ${setOutcome.status}")
                return@delete
            }
            is ArticleSetCoverOutcome.Set -> {
                if (setOutcome.previousCoverImageId != null) storage.delete(setOutcome.previousCoverImageId)
            }
        }
        logger.info { "Article cover removed: articleId=$articleId memberId=${current.memberId} action=cover.remove" }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.OK, ArticleCoverResultDto(coverImageUrl = null))
    }

    /**
     * Authenticated cover-image bytes for a not-yet-(or-no-longer)-public article. See class KDoc
     * "Existence-oracle discipline on the GET route".
     */
    get("/api/articles/{id}/cover") {
        if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val articleId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (articleId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val row: ResultRow? = transaction { ArticleStore.getOrNull(articleId) }
        if (row == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val coverImageId = row[ArticleTable.coverImageId]
        // A failing session resolution must NOT throw -- an anonymous caller sees the identical 404
        // a fremdes Mitglied gets, never a 401 (see class KDoc).
        val current = runCatching { resolveCurrentMember(call) }.getOrNull()
        val isAuthor = current != null && row[ArticleTable.authorId] == current.memberId
        val isReviewerAllowed = current != null && current.isPrivileged && row[ArticleTable.status] != ArticleStatus.DRAFT
        if (coverImageId == null || (!isAuthor && !isReviewerAllowed)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respondCoverFile(storage = storage, coverImageId = coverImageId)
    }
}

/** Mirrors `EventCoverRoutes`' own `SetCoverOutcome`, extended with a [WrongStatus] arm (the article family additionally re-checks status, not just existence/ownership, under the lock -- see class KDoc). */
private sealed interface ArticleSetCoverOutcome {
    data object NotFound : ArticleSetCoverOutcome

    data class WrongStatus(
        val status: ArticleStatus,
    ) : ArticleSetCoverOutcome

    data class Set(
        val previousCoverImageId: Uuid?,
    ) : ArticleSetCoverOutcome
}
