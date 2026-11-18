package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgReviewerAccess
import network.lapis.cloud.server.partg.PartgReviewerNotInScopeException
import network.lapis.cloud.server.partg.PartgReviewerTokens
import network.lapis.cloud.server.partg.PartgStoredVersionRenderer
import network.lapis.cloud.server.partg.report.PartgReportTooLargeException
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import network.lapis.cloud.shared.rpc.RateLimitedException
import java.net.URI
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The name of the cookie of the auditor's session. It is read ONLY below `/api/pruefung`; the members' `lapis_session` is never read here. */
internal const val REVIEWER_COOKIE_NAME = "lapis_pruefung"
internal const val REVIEWER_COOKIE_PATH = "/api/pruefung"

private const val MAX_BODY_BYTES = 1024
private const val CODE_NEUTRAL = """{"message":"Falls die Einladung gültig ist, wurde ein Code per E-Mail gesendet."}"""
private const val SESSION_NEUTRAL = """{"message":"Code ungültig oder abgelaufen."}"""
private const val READ_UNAUTHENTICATED = """{"message":"Sitzung beendet. Bitte öffnen Sie den Link aus Ihrer Einladung erneut."}"""
private const val NOT_FOUND_BODY = """{"message":"Nicht gefunden."}"""
private const val BUSY_BODY = """{"message":"Zu viele Anfragen. Bitte versuchen Sie es später erneut."}"""
private const val TOO_LARGE_BODY = """{"message":"Diese Fassung ist zu groß für die Ausgabe."}"""
private const val INTEGRITY_BODY = """{"message":"Die gespeicherte Fassung stimmt nicht mehr mit ihrer Prüfsumme überein."}"""

/**
 * V1.9.310 (R6b) -- the limits of the auditor's public routes (all per instance, in memory). IP keys go through [rateLimitKeyFor] (IPv6 -> /64).
 * A hit answers 429 with the neutral body of the route and no `Retry-After`.
 */
class PartgReviewerRateLimits(
    /** All unauthenticated routes together, per IP. */
    val landing: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
    val codeRequestIp: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.hours),
    /** Per hash of the token, also for tokens that do not exist, so a probe of unknown tokens is slowed the same way. */
    val codeRequestToken: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.hours),
    val codeVerifyIp: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 10.minutes),
    /** Per session id. */
    val read: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes),
)

@Serializable
private data class CodeRequestBody(
    val token: String,
)

@Serializable
private data class SessionRequestBody(
    val token: String,
    val code: String,
)

private val bodyJson = Json { ignoreUnknownKeys = true }

/**
 * V1.9.310 (R6b) -- the routes of the auditor, below `/api/pruefung`. NO account, NO `lapis_session`: the link token (from the fragment of the
 * link, sent in a POST body, never in a URL) buys a 6-digit code by mail, the code buys a session cookie `lapis_pruefung`
 * (`HttpOnly`, `SameSite=Strict`, `Path=/api/pruefung`, `Secure` as the members' cookie). EVERY handler starts with `reviewerGuard` (pinned by
 * `PartgReviewerStoreTripwireTest`): the security headers and the per-IP limit come before anything is read. Answers that could tell a valid link from
 * an invalid one are identical. The logic is in [PartgReviewerAccess]; nothing here logs a token, a code, a cookie, an address or an IP.
 */
internal fun Route.registerPartgReviewerRoutes(
    limits: PartgReviewerRateLimits,
    access: PartgReviewerAccess,
    cookieSecure: Boolean,
) {
    post("/api/pruefung/code") {
        if (!call.reviewerGuard(
                limits = limits,
                neutral = CODE_NEUTRAL,
                neutralStatus = HttpStatusCode.Accepted,
                requireJson = true,
            )
        ) {
            return@post
        }
        val ip = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        if (!limits.codeRequestIp.checkAndRecord(
                ip,
            )
        ) {
            return@post call.respondJson(status = HttpStatusCode.TooManyRequests, body = CODE_NEUTRAL)
        }
        val request =
            call.readBody<CodeRequestBody>() ?: return@post call.respondJson(status = HttpStatusCode.Accepted, body = CODE_NEUTRAL)
        if (!PartgReviewerTokens.plausibleLinkToken(
                request.token,
            )
        ) {
            return@post call.respondJson(status = HttpStatusCode.Accepted, body = CODE_NEUTRAL)
        }
        if (!limits.codeRequestToken.checkAndRecord(PartgReviewerTokens.hash(request.token))) {
            return@post call.respondJson(status = HttpStatusCode.TooManyRequests, body = CODE_NEUTRAL)
        }
        access.requestCode(request.token)
        call.respondJson(status = HttpStatusCode.Accepted, body = CODE_NEUTRAL)
    }

    post("/api/pruefung/session") {
        if (!call.reviewerGuard(
                limits = limits,
                neutral = SESSION_NEUTRAL,
                neutralStatus = HttpStatusCode.Unauthorized,
                requireJson = true,
            )
        ) {
            return@post
        }
        val ip = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        if (!limits.codeVerifyIp.checkAndRecord(
                ip,
            )
        ) {
            return@post call.respondJson(status = HttpStatusCode.TooManyRequests, body = SESSION_NEUTRAL)
        }
        val request =
            call.readBody<SessionRequestBody>()
                ?: return@post call.respondJson(status = HttpStatusCode.Unauthorized, body = SESSION_NEUTRAL)
        if (!PartgReviewerTokens.plausibleLinkToken(request.token) || request.code.length > MAX_CODE_INPUT) {
            return@post call.respondJson(status = HttpStatusCode.Unauthorized, body = SESSION_NEUTRAL)
        }
        // a cookie sent along is never taken over (session fixation): at most its session is ended, and the new cookie overwrites it
        val previous = call.request.cookies[REVIEWER_COOKIE_NAME, CookieEncoding.URI_ENCODING]
        val opened = access.openSession(rawToken = request.token, rawCode = request.code, previousRawSession = previous)
        if (opened == null) return@post call.respondJson(status = HttpStatusCode.Unauthorized, body = SESSION_NEUTRAL)
        call.response.cookies.append(sessionCookie(value = opened.rawToken, maxAge = opened.maxAgeSeconds, secure = cookieSecure))
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    get("/api/pruefung/session") {
        if (!call.reviewerGuard(limits = limits, neutral = READ_UNAUTHENTICATED, neutralStatus = HttpStatusCode.Unauthorized)) return@get
        val session =
            access.resolveSession(call.reviewerCookie())
                ?: return@get call.respondJson(status = HttpStatusCode.Unauthorized, body = READ_UNAUTHENTICATED)
        call.respondJson(
            status = HttpStatusCode.OK,
            body =
                Json.encodeToString(
                    network.lapis.cloud.shared.domain.PartgReviewerSessionInfoDto
                        .serializer(),
                    session.info(),
                ),
        )
    }

    post("/api/pruefung/session/end") {
        if (!call.reviewerGuard(limits = limits, neutral = SESSION_NEUTRAL, neutralStatus = HttpStatusCode.Unauthorized)) return@post
        access.endSession(call.reviewerCookie())
        call.response.cookies.append(sessionCookie(value = "", maxAge = 0, secure = cookieSecure))
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    get("/api/pruefung/versions") {
        if (!call.reviewerGuard(limits = limits, neutral = READ_UNAUTHENTICATED, neutralStatus = HttpStatusCode.Unauthorized)) return@get
        val session = call.readSession(limits = limits, access = access) ?: return@get
        call.respondJson(
            status = HttpStatusCode.OK,
            body =
                Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        network.lapis.cloud.shared.domain.PartgReviewerVersionDto
                            .serializer(),
                    ),
                    access.versions(session),
                ),
        )
    }

    get("/api/pruefung/versions/{id}/document") {
        if (!call.reviewerGuard(limits = limits, neutral = READ_UNAUTHENTICATED, neutralStatus = HttpStatusCode.Unauthorized)) return@get
        val session = call.readSession(limits = limits, access = access) ?: return@get
        val id = call.versionId() ?: return@get call.respondJson(status = HttpStatusCode.NotFound, body = NOT_FOUND_BODY)
        val download = call.request.queryParameters["download"] == "1"
        call.guarded {
            val verified = access.document(session = session, versionId = id, download = download)
            if (download) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${PartgStoredVersionRenderer.fileName(meta = verified.meta, extension = "json")}\"",
                )
            }
            // the stored text, byte for byte (UTF-8)
            call.respondBytes(verified.json.toByteArray(Charsets.UTF_8), ContentType.parse("application/json; charset=utf-8"))
        }
    }

    get("/api/pruefung/versions/{id}/pdf") {
        if (!call.reviewerGuard(limits = limits, neutral = READ_UNAUTHENTICATED, neutralStatus = HttpStatusCode.Unauthorized)) return@get
        val session = call.readSession(limits = limits, access = access) ?: return@get
        val id = call.versionId() ?: return@get call.respondJson(status = HttpStatusCode.NotFound, body = NOT_FOUND_BODY)
        call.guarded { call.respondFile(access = access, session = session, id = id, format = PartgReportFormat.PDF) }
    }

    get("/api/pruefung/versions/{id}/xlsx") {
        if (!call.reviewerGuard(limits = limits, neutral = READ_UNAUTHENTICATED, neutralStatus = HttpStatusCode.Unauthorized)) return@get
        val session = call.readSession(limits = limits, access = access) ?: return@get
        val id = call.versionId() ?: return@get call.respondJson(status = HttpStatusCode.NotFound, body = NOT_FOUND_BODY)
        call.guarded { call.respondFile(access = access, session = session, id = id, format = PartgReportFormat.XLSX) }
    }

    get("/api/pruefung/compare") {
        if (!call.reviewerGuard(limits = limits, neutral = READ_UNAUTHENTICATED, neutralStatus = HttpStatusCode.Unauthorized)) return@get
        val session = call.readSession(limits = limits, access = access) ?: return@get
        val a = call.request.queryParameters["a"]?.let(::parseUuid)
        val b = call.request.queryParameters["b"]?.let(::parseUuid)
        if (a == null || b == null) return@get call.respondJson(status = HttpStatusCode.NotFound, body = NOT_FOUND_BODY)
        call.guarded {
            val diff = access.compare(session = session, idA = a, idB = b)
            call.respondJson(
                status = HttpStatusCode.OK,
                body =
                    Json.encodeToString(
                        network.lapis.cloud.shared.domain.PartgVersionDiffDto
                            .serializer(),
                        diff,
                    ),
            )
        }
    }
}

private const val MAX_CODE_INPUT = 16

private suspend fun ApplicationCall.respondFile(
    access: PartgReviewerAccess,
    session: PartgReviewerAccess.ReviewerSession,
    id: Uuid,
    format: PartgReportFormat,
) {
    val (verified, bytes) = access.render(session = session, versionId = id, format = format)
    val extension = if (format == PartgReportFormat.PDF) "pdf" else "xlsx"
    response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"${PartgStoredVersionRenderer.fileName(meta = verified.meta, extension = extension)}\"",
    )
    respondBytes(
        bytes,
        ContentType.parse(
            if (format == PartgReportFormat.PDF) {
                "application/pdf"
            } else {
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            },
        ),
    )
}

/** Runs a read and maps its failures to the same few answers for every route: unknown or out of scope -> one 404, damaged -> 409, busy -> 429. */
private suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: PartgReviewerNotInScopeException) {
        respondJson(status = HttpStatusCode.NotFound, body = NOT_FOUND_BODY)
    } catch (e: NotFoundException) {
        respondJson(status = HttpStatusCode.NotFound, body = NOT_FOUND_BODY)
    } catch (e: PartyReportVersionIntegrityException) {
        logger.warn { "partg reviewer read: integrity failure" }
        respondJson(status = HttpStatusCode.Conflict, body = INTEGRITY_BODY)
    } catch (e: RateLimitedException) {
        respondJson(status = HttpStatusCode.TooManyRequests, body = BUSY_BODY)
    } catch (e: PdfLimitExceeded) {
        respondJson(status = HttpStatusCode.PayloadTooLarge, body = TOO_LARGE_BODY)
    } catch (e: PartgReportTooLargeException) {
        respondJson(status = HttpStatusCode.PayloadTooLarge, body = TOO_LARGE_BODY)
    }
}

/**
 * The first statement of EVERY handler: the security headers (also on every error), then the per-IP limit of the unauthenticated surface.
 * Returns `false` after it has answered. [neutral] is the body of the route's neutral answer; a limit hit is a 429 with it.
 */
private suspend fun ApplicationCall.reviewerGuard(
    limits: PartgReviewerRateLimits,
    neutral: String,
    neutralStatus: HttpStatusCode,
    requireJson: Boolean = false,
): Boolean {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Content-Type-Options", "nosniff")
    if (!limits.landing.checkAndRecord(rateLimitKeyFor(remoteHost = request.origin.remoteHost))) {
        respondJson(status = HttpStatusCode.TooManyRequests, body = neutral)
        return false
    }
    if (request.local.method == io.ktor.http.HttpMethod.Post) {
        // a state-changing request must come from the page itself: a foreign Origin, a form post or an oversized declared body gets the neutral answer
        val origin = request.headers[HttpHeaders.Origin]
        if (origin != null && origin != ownOrigin()) {
            respondJson(status = HttpStatusCode.Forbidden, body = neutral)
            return false
        }
        val type =
            request.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (requireJson && type != "application/json") {
            respondJson(status = neutralStatus, body = neutral)
            return false
        }
    }
    return true
}

private fun ownOrigin(): String {
    val uri = runCatching { URI(FederationConfig.publicBaseUrl) }.getOrNull() ?: return FederationConfig.publicBaseUrl.trimEnd('/')
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "${uri.scheme}://${uri.host}$port"
}

private suspend inline fun <reified T> ApplicationCall.readBody(): T? {
    val bytes = readCappedBody(call = this, maxBytes = MAX_BODY_BYTES) ?: return null
    return runCatching { bodyJson.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
}

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: String,
) = respondText(body, ContentType.Application.Json, status)

private fun ApplicationCall.reviewerCookie(): String? = request.cookies[REVIEWER_COOKIE_NAME, CookieEncoding.URI_ENCODING]

private fun ApplicationCall.versionId(): Uuid? = parameters["id"]?.let(::parseUuid)

private val STRICT_UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

private fun parseUuid(raw: String): Uuid? = if (STRICT_UUID.matches(raw)) Uuid.parse(raw) else null

/** The session of the cookie (re-checked against the invitation), or a 401 has been sent. Also the per-session read limit (429). */
private suspend fun ApplicationCall.readSession(
    limits: PartgReviewerRateLimits,
    access: PartgReviewerAccess,
): PartgReviewerAccess.ReviewerSession? {
    val session = access.resolveSession(reviewerCookie())
    if (session == null) {
        respondJson(status = HttpStatusCode.Unauthorized, body = READ_UNAUTHENTICATED)
        return null
    }
    if (!limits.read.checkAndRecord("partg:reviewer:read:${session.sessionId}")) {
        respondJson(status = HttpStatusCode.TooManyRequests, body = BUSY_BODY)
        return null
    }
    return session
}

private fun sessionCookie(
    value: String,
    maxAge: Int,
    secure: Boolean,
) = Cookie(
    name = REVIEWER_COOKIE_NAME,
    value = value,
    encoding = CookieEncoding.URI_ENCODING,
    maxAge = maxAge,
    path = REVIEWER_COOKIE_PATH,
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)
