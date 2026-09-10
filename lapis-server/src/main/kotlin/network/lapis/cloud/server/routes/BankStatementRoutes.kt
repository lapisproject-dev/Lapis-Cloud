package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.bankstatement.BankStatementImportService
import network.lapis.cloud.server.payment.bankstatement.BankStatementRejectedException
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import java.io.ByteArrayOutputStream

/** Same TREASURER/ADMIN role set every OTHER booking-capable path in this domain uses -- see `network.lapis.cloud.server.rpc.BANK_STATEMENT_WRITE_ROLES` KDoc (plan OF-1). */
private val BANK_STATEMENT_UPLOAD_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)". File bytes travel over THIS Ktor route, not
 * Kilua RPC -- same "large binary payload" reasoning [network.lapis.cloud.server.routes
 * .DocumentRoutes]/[network.lapis.cloud.server.routes.registerMailmergeRoutes] already establish.
 * Security checklist applied here, mirroring [DocumentRoutes] KDoc exactly:
 * - **Access control BEFORE the body is read** -- the role gate runs before `receiveMultipart()`.
 * - **DoS cap**: [BankStatementImportService.MAX_UPLOAD_BYTES] enforced WHILE streaming, before the
 *   whole file is buffered in memory.
 * - **Rate limiting**: [rateLimiter], same [FederationInboxRateLimiter] class every other
 *   write-capable route in this codebase uses.
 * - **No file is ever written to disk** -- the upload is buffered in memory only (bounded by the
 *   size cap above) and handed straight to [BankStatementImportService.import]; nothing under
 *   `documentStorageRoot` is touched (the parsed statement is not itself a `document`).
 *
 * Welle V1.4.5.1.1 -- every rejection path (413/400/429/422/409) responds with the SAME
 * [BankStatementImportRejectionDto] JSON shape via [respondRejection], not the German plaintext
 * this route used to return -- see that DTO's own KDoc for why [BankStatementImportRejectionDto
 * .detail] is transported but never the string the client actually displays.
 */
fun Route.registerBankStatementRoutes(
    secretBox: SecretBox?,
    rateLimiter: FederationInboxRateLimiter,
) {
    post("/api/bank-statements/import") {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_STATEMENT_UPLOAD_ROLES)

        val remoteHost = call.request.local.remoteHost
        if (!rateLimiter.checkAndRecord(remoteHost)) {
            call.respondRejection(
                status = HttpStatusCode.TooManyRequests,
                code = BankStatementRejectionCode.RATE_LIMITED,
                detail = "Rate limit exceeded",
            )
            return@post
        }

        var uploadedFileName: String? = null
        val buffer = ByteArrayOutputStream()
        var totalBytes = 0L
        var tooLarge = false

        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    if (uploadedFileName == null) uploadedFileName = part.originalFileName ?: "kontoauszug"
                    val channel: ByteReadChannel = part.provider()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = channel.readAvailable(chunk)
                        if (read == -1) break
                        totalBytes += read
                        if (totalBytes > BankStatementImportService.MAX_UPLOAD_BYTES) {
                            tooLarge = true
                            break
                        }
                        buffer.write(chunk, 0, read)
                    }
                }
                else -> {}
            }
            part.release()
        }

        if (tooLarge) {
            call.respondRejection(
                status = HttpStatusCode.PayloadTooLarge,
                code = BankStatementRejectionCode.FILE_TOO_LARGE,
                detail = "Max upload size is ${BankStatementImportService.MAX_UPLOAD_BYTES} bytes",
            )
            return@post
        }
        val fileName = uploadedFileName
        if (fileName == null) {
            call.respondRejection(
                status = HttpStatusCode.BadRequest,
                code = BankStatementRejectionCode.NO_FILE_PART,
                detail = "No file part in request",
            )
            return@post
        }

        val service = BankStatementImportService(secretBox = secretBox)
        try {
            val result =
                service.import(
                    bytes = buffer.toByteArray(),
                    fileName = fileName,
                    uploadedBy = current.memberId,
                    uploaderRole = current.role,
                )
            call.respond(HttpStatusCode.OK, result)
        } catch (e: BankStatementRejectedException) {
            call.respondRejection(
                status = HttpStatusCode.fromValue(e.httpStatus),
                code = e.code,
                detail = e.message,
                lineNumber = e.lineNumber,
                rawLineExcerpt = e.rawLineExcerpt,
                observedHeaderFields = e.observedHeaderFields,
            )
        }
    }
}

private suspend fun ApplicationCall.respondRejection(
    status: HttpStatusCode,
    code: BankStatementRejectionCode,
    detail: String? = null,
    lineNumber: Int? = null,
    rawLineExcerpt: String? = null,
    observedHeaderFields: List<String>? = null,
) = respond(
    status,
    BankStatementImportRejectionDto(
        code = code,
        lineNumber = lineNumber,
        rawLineExcerpt = rawLineExcerpt,
        observedHeaderFields = observedHeaderFields,
        detail = detail,
    ),
)
