package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.governance.session.MeetingProtocolOperations
import network.lapis.cloud.server.security.resolveCurrentMember
import java.io.File
import kotlin.time.Duration.Companion.minutes

private val PROTOCOL_DOWNLOAD_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

/**
 * V1.9.240 -- `GET /api/meeting-protocol/{versionId}/pdf`: the PDF of ONE finalised Protokoll version, for everybody who has a role in its Sitzung
 * (the generic document routes are ADMIN_ONLY and know nothing of Sitzung roles). Whatever the caller must not get -- a malformed or unknown id,
 * a version of a Sitzung the caller has no role in, a version without a PDF -- is one and the same **404**; no existence oracle. Old versions stay
 * downloadable. The download counter of the document is not touched.
 */
fun Route.registerMeetingProtocolRoutes(storageRoot: File) {
    get("/api/meeting-protocol/{versionId}/pdf") {
        val current =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (!PROTOCOL_DOWNLOAD_RATE_LIMITER.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val located = MeetingProtocolOperations().pdfLocation(current = current, versionId = call.parameters["versionId"].orEmpty())
        val file = located?.let { storageRoot.resolve(it.storageKey) }?.takeIf { it.isFile }
        if (located == null || file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(
                    ContentDisposition.Parameters.FileName,
                    "Protokoll_F${located.versionNo}.pdf",
                ).toString(),
        )
        call.respond(LocalFileContent(file, ContentType.Application.Pdf))
    }
}
