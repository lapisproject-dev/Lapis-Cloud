package network.lapis.cloud.server.attachments

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import network.lapis.cloud.server.routes.StreamedTravelExpenseFile
import network.lapis.cloud.server.routes.contentDispositionHeader
import network.lapis.cloud.shared.domain.AttachmentErrorCode
import network.lapis.cloud.shared.domain.AttachmentRules
import java.io.File
import java.nio.charset.Charset

/**
 * V1.9.233 -- the part of the attachment upload/download pipeline that mailing messages (V1.9.232) and direct messages (V1.9.233) share,
 * extracted from the mailing routes so the rules exist exactly once (tripwire `AttachmentRulesSingleSourceTripwireTest`).
 */
internal sealed interface AttachmentUploadClassification {
    data class Ok(
        val name: String,
        val mime: String,
        val charset: String?,
    ) : AttachmentUploadClassification

    data class Refused(
        val code: AttachmentErrorCode,
    ) : AttachmentUploadClassification
}

/** Name first (so a bad name never reaches the sniffer), then the extension whitelist, then the content of the complete stored [file]. */
internal fun classifyAttachmentUpload(
    rawName: String,
    file: File,
): AttachmentUploadClassification {
    val name = sanitizeAttachmentName(rawName) ?: return AttachmentUploadClassification.Refused(AttachmentErrorCode.INVALID_NAME)
    val extension = AttachmentRules.extensionOf(name)
    if (extension !in AttachmentRules.ALLOWED) {
        return AttachmentUploadClassification.Refused(AttachmentErrorCode.TYPE_NOT_ALLOWED)
    }
    return when (val verdict = AttachmentSniffer.classify(file = file, declaredExtension = extension)) {
        is AttachmentVerdict.Accepted -> AttachmentUploadClassification.Ok(name = name, mime = verdict.mime, charset = verdict.charset)
        AttachmentVerdict.TypeNotAllowed -> AttachmentUploadClassification.Refused(AttachmentErrorCode.TYPE_NOT_ALLOWED)
        AttachmentVerdict.ContentMismatch -> AttachmentUploadClassification.Refused(AttachmentErrorCode.CONTENT_MISMATCH)
        AttachmentVerdict.MacroOrEmbedded -> AttachmentUploadClassification.Refused(AttachmentErrorCode.MACRO_OR_EMBEDDED)
    }
}

internal fun attachmentStreamFailureCode(outcome: StreamedTravelExpenseFile): AttachmentErrorCode =
    when (outcome) {
        StreamedTravelExpenseFile.TooLarge -> AttachmentErrorCode.FILE_TOO_LARGE
        StreamedTravelExpenseFile.MultipleFileParts -> AttachmentErrorCode.MULTIPLE_FILES
        StreamedTravelExpenseFile.NoFilePart -> AttachmentErrorCode.NO_FILE
        StreamedTravelExpenseFile.UnsupportedType, is StreamedTravelExpenseFile.Rejected -> AttachmentErrorCode.TYPE_NOT_ALLOWED
        StreamedTravelExpenseFile.InvalidFormFields -> AttachmentErrorCode.NO_FILE
        is StreamedTravelExpenseFile.Stored -> error("not a failure")
    }

internal fun attachmentStatusFor(code: AttachmentErrorCode): HttpStatusCode =
    when (code) {
        AttachmentErrorCode.INVALID_ORIGIN, AttachmentErrorCode.FORBIDDEN -> HttpStatusCode.Forbidden
        AttachmentErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
        AttachmentErrorCode.NOT_DRAFT,
        AttachmentErrorCode.TOO_MANY,
        AttachmentErrorCode.TOTAL_TOO_LARGE,
        AttachmentErrorCode.DUPLICATE_NAME,
        AttachmentErrorCode.RECIPIENT_UNAVAILABLE,
        AttachmentErrorCode.PENDING_LIMIT,
        -> HttpStatusCode.Conflict
        AttachmentErrorCode.FILE_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        AttachmentErrorCode.TYPE_NOT_ALLOWED,
        AttachmentErrorCode.CONTENT_MISMATCH,
        AttachmentErrorCode.MACRO_OR_EMBEDDED,
        -> HttpStatusCode.UnsupportedMediaType
        AttachmentErrorCode.INVALID_NAME,
        AttachmentErrorCode.MULTIPLE_FILES,
        AttachmentErrorCode.NO_FILE,
        -> HttpStatusCode.BadRequest
        AttachmentErrorCode.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    }

/** `{"code":"..."}` with a FIXED code; never free text, never anything derived from the request. */
internal suspend fun ApplicationCall.respondAttachmentError(code: AttachmentErrorCode) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(attachmentStatusFor(code), mapOf("code" to code.name))
}

/** Resolves [storageKey] below [root]; `null` if it is blank, absolute or escapes the canonical root (hand-edited rows, restored backups). */
internal fun resolveContainedAttachmentFile(
    root: File,
    storageKey: String,
): File? {
    val canonicalRoot = root.canonicalFile
    if (storageKey.isBlank() || File(storageKey).isAbsolute) return null
    val candidate = canonicalRoot.resolve(storageKey).canonicalFile
    return if (candidate.path.startsWith(canonicalRoot.path + File.separator)) candidate else null
}

/**
 * The download response of a stored attachment: `Content-Disposition: attachment` ALWAYS (also for PDF and images, so nothing renders
 * inline), `nosniff`, `private, no-store`, CORP `same-origin`, and a sandboxing CSP in case a client ever renders the response anyway.
 * The type is the server's whitelist type from the row, never a client-supplied one.
 */
internal suspend fun ApplicationCall.respondAttachmentDownload(
    file: File,
    fileName: String,
    mimeType: String,
    textCharset: String?,
) {
    response.header(HttpHeaders.ContentDisposition, contentDispositionHeader(fileName))
    response.header("X-Content-Type-Options", "nosniff")
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header("Cross-Origin-Resource-Policy", "same-origin")
    response.header("Content-Security-Policy", "default-src 'none'; sandbox")
    val baseType = ContentType.parse(mimeType)
    val contentType = textCharset?.let { baseType.withCharset(Charset.forName(it)) } ?: baseType
    respond(LocalFileContent(file, contentType))
}
