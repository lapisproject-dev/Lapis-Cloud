package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mcp.transport.receiveCappedTextOrNull
import network.lapis.cloud.server.rpc.SepaFailureKind
import network.lapis.cloud.server.rpc.TravelExpenseSepaException
import network.lapis.cloud.server.rpc.TravelExpenseSepaExport
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.TravelExpenseSepaErrorDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaExportRequest
import network.lapis.cloud.shared.domain.TravelExpenseSepaReexportRequest
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val MAX_BODY_BYTES = 64 * 1024
private val routeJson = Json { ignoreUnknownKeys = true }

/**
 * Welle V1.9.123 "SEPA-Ueberweisungsdatei fuer genehmigte Reisekosten" -- the two routes that hand out a pain.001 file:
 *
 * - `POST /api/travel-expenses/sepa-batches` -- body [TravelExpenseSepaExportRequest]
 * - `POST /api/travel-expenses/reports/{reportId}/sepa-reexport` -- body [TravelExpenseSepaReexportRequest]
 *
 * **Why HTTP and not an RPC returning base64.** `Cache-Control: no-store` and `Content-Disposition` exist only on HTTP, and a
 * separate GET download would have to regenerate the file -- bypassing the "never re-downloadable" rule and the double-payment
 * guard. A POST that commits and delivers in the same request is the only way without storing the XML.
 *
 * **Guards, in this order, before any database access beyond the session lookup:** authentication (`resolveCurrentMember`),
 * same-origin check (CSRF -- the browser sends the session cookie), `Content-Type: application/json` (415), body size <= 64 KiB,
 * a per-member rate limit (shared between both routes, 429). Roles and the four-eyes rule are enforced in
 * [TravelExpenseSepaExport]. Errors are JSON ([TravelExpenseSepaErrorDto]) and never contain an IBAN, a holder or a member name; the
 * log carries only batch id, count and sum.
 */
internal fun Route.registerTravelExpenseSepaRoutes(
    export: TravelExpenseSepaExport,
    rateLimiter: FederationInboxRateLimiter,
    baseUrl: String,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/travel-expenses/sepa-batches") {
        val body = guardedBody(canonicalOrigin = canonicalOrigin, rateLimiter = rateLimiter) ?: return@post
        val request =
            runCatching { routeJson.decodeFromString(TravelExpenseSepaExportRequest.serializer(), body.text) }.getOrNull()
        if (request == null) {
            call.respondError(status = HttpStatusCode.BadRequest, code = "invalid_request")
            return@post
        }
        val reportIds = request.reportIds.map { parseUuid(it) }
        val debtorId = request.debtorBankAccountId?.let { parseUuid(it) }
        if (reportIds.any { it == null } || (request.debtorBankAccountId != null && debtorId == null)) {
            call.respondError(status = HttpStatusCode.NotFound, code = "report_not_found")
            return@post
        }
        deliver(call = call) {
            export.export(
                actor = body.actor,
                reportIds = reportIds.filterNotNull(),
                debtorBankAccountId = debtorId,
                executionDate = request.executionDate,
            )
        }
    }

    post("/api/travel-expenses/reports/{reportId}/sepa-reexport") {
        val body = guardedBody(canonicalOrigin = canonicalOrigin, rateLimiter = rateLimiter) ?: return@post
        val reportId = parseUuid(call.parameters["reportId"].orEmpty())
        val request =
            runCatching { routeJson.decodeFromString(TravelExpenseSepaReexportRequest.serializer(), body.text) }.getOrNull()
        if (request == null) {
            call.respondError(status = HttpStatusCode.BadRequest, code = "invalid_request")
            return@post
        }
        val debtorId = request.debtorBankAccountId?.let { parseUuid(it) }
        if (reportId == null || (request.debtorBankAccountId != null && debtorId == null)) {
            call.respondError(status = HttpStatusCode.NotFound, code = "report_not_found")
            return@post
        }
        deliver(call = call) {
            export.reexport(
                actor = body.actor,
                reportId = reportId,
                reason = request.reason,
                debtorBankAccountId = debtorId,
                executionDate = request.executionDate,
            )
        }
    }
}

private class GuardedBody(
    val actor: network.lapis.cloud.server.security.CurrentMember,
    val text: String,
)

/** Runs every pre-check; responds and returns `null` on the first failure. */
private suspend fun io.ktor.server.routing.RoutingContext.guardedBody(
    canonicalOrigin: URI?,
    rateLimiter: FederationInboxRateLimiter,
): GuardedBody? {
    // UnauthenticatedException is mapped to 401 by the StatusPages plugin.
    val actor = resolveCurrentMember(call)
    if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
        call.respondError(status = HttpStatusCode.Forbidden, code = "invalid_origin")
        return null
    }
    if (!call.request.contentType().match(ContentType.Application.Json)) {
        call.respondError(status = HttpStatusCode.UnsupportedMediaType, code = "unsupported_media_type")
        return null
    }
    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_BODY_BYTES.toLong()) {
        call.respondError(status = HttpStatusCode.PayloadTooLarge, code = "body_too_large")
        return null
    }
    if (!rateLimiter.checkAndRecord("member:${actor.memberId}")) {
        call.response.header(HttpHeaders.RetryAfter, rateLimiter.retryAfterSeconds("member:${actor.memberId}").toString())
        call.respondError(status = HttpStatusCode.TooManyRequests, code = "rate_limited")
        return null
    }
    val text = call.receiveCappedTextOrNull(MAX_BODY_BYTES)
    if (text == null) {
        call.respondError(status = HttpStatusCode.PayloadTooLarge, code = "body_too_large")
        return null
    }
    return GuardedBody(actor = actor, text = text)
}

/** Runs [block] (which commits) and only afterwards writes the response. */
private suspend fun deliver(
    call: ApplicationCall,
    block: () -> TravelExpenseSepaExport.Result,
) {
    val result =
        try {
            block()
        } catch (e: TravelExpenseSepaException) {
            val status =
                when (e.kind) {
                    SepaFailureKind.BAD_REQUEST -> HttpStatusCode.BadRequest
                    SepaFailureKind.FORBIDDEN -> HttpStatusCode.Forbidden
                    SepaFailureKind.NOT_FOUND -> HttpStatusCode.NotFound
                    SepaFailureKind.CONFLICT -> HttpStatusCode.Conflict
                }
            call.respondError(status = status, code = e.code, message = e.message ?: e.code, blockers = e.blockers)
            return
        } catch (e: SerializationException) {
            call.respondError(status = HttpStatusCode.BadRequest, code = "invalid_request")
            return
        }
    logger.info { "travel expense SEPA file delivered: batchId=${result.batchId} count=${result.count} sumCents=${result.sumCents}" }
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.Pragma, "no-cache")
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header("X-Lapis-Sepa-Batch-Id", result.batchId.toString())
    call.response.header("X-Lapis-Sepa-Count", result.count.toString())
    call.response.header("X-Lapis-Sepa-Sum-Cents", result.sumCents.toString())
    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, result.filename).toString(),
    )
    call.respond(ByteArrayContent(result.xml, ContentType.parse("application/xml; charset=UTF-8"), HttpStatusCode.OK))
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String = code,
    blockers: Map<String, network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker> = emptyMap(),
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(
        text =
            Json.encodeToString(
                TravelExpenseSepaErrorDto.serializer(),
                TravelExpenseSepaErrorDto(code = code, message = message, blockers = blockers),
            ),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
