package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

internal const val TRAVEL_EXPENSE_SNIFF_BYTES = 8
internal const val TRAVEL_EXPENSE_STORAGE_PREFIX = "travel-expenses"

/**
 * Outcome of [streamSingleTravelExpenseFile]. Everything except [Stored] has ALREADY deleted the
 * target file; the caller only maps it to an HTTP response ([respondStreamFailure]).
 */
internal sealed interface StreamedTravelExpenseFile {
    data class Stored(
        val sanitizedFileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val mimeType: String,
        /** Text form fields (name -> value) read while streaming; empty unless the caller asked for them (`maxFormFields > 0`). */
        val formFields: Map<String, String> = emptyMap(),
    ) : StreamedTravelExpenseFile

    /** 413 */
    data object TooLarge : StreamedTravelExpenseFile

    /** 400 */
    data object MultipleFileParts : StreamedTravelExpenseFile

    /** 400 */
    data object NoFilePart : StreamedTravelExpenseFile

    /** 415 */
    data object UnsupportedType : StreamedTravelExpenseFile

    /** 415 with a fixed token body (`unsupported_type` | `macro_content` | `encrypted`), produced by a custom `classify`. */
    data class Rejected(
        val reason: FileRejection,
    ) : StreamedTravelExpenseFile

    /** 400: more form fields than allowed, or one of them too long. */
    data object InvalidFormFields : StreamedTravelExpenseFile
}

/** Why a custom classifier refused a streamed file. The wire token is fixed per reason, never built from user input. */
internal enum class FileRejection(
    val token: String,
) {
    TYPE("unsupported_type"),
    MACRO("macro_content"),
    ENCRYPTED("encrypted"),
}

/** Verdict of a `classify` callback of [streamSingleTravelExpenseFile]. */
internal sealed interface FileClassification {
    data class Accepted(
        val mimeType: String,
    ) : FileClassification

    data class Rejected(
        val reason: FileRejection,
    ) : FileClassification
}

/** Longest accepted value of a text form field next to the file part. */
internal const val TRAVEL_EXPENSE_MAX_FORM_FIELD_CHARS = 1024

/** Default classification: magic bytes only (PDF/JPEG/PNG) -- what receipts and report attachments have always used. */
internal val DEFAULT_TRAVEL_EXPENSE_CLASSIFIER: (File, ByteArray, Int) -> FileClassification = { _, head, filled ->
    sniffMimeType(buffer = head, filled = filled)?.let { FileClassification.Accepted(it) }
        ?: FileClassification.Rejected(FileRejection.TYPE)
}

/**
 * Streams the FIRST file part of the multipart request to [targetFile] (parent directories are
 * created), enforcing [maxBytes] WHILE streaming, sniffing the MIME type from magic bytes (the
 * client-declared `Content-Type` is discarded) and hashing with SHA-256.
 *
 * Shared by the receipt routes and the report-attachment routes (V1.9.198) so the upload
 * hardening exists exactly once:
 * - a second file part aborts the whole read (400), bounded work for a many-part body;
 * - [targetFile] is deleted on EVERY non-[StreamedTravelExpenseFile.Stored] outcome and on ANY
 *   exception (rethrown unchanged), so a dropped connection never leaves an orphan on disk;
 * - the file name is returned sanitized and is display metadata only, never part of a path.
 */
internal suspend fun ApplicationCall.streamSingleTravelExpenseFile(
    targetFile: File,
    maxBytes: Long,
    fallbackName: String,
    /**
     * Runs AFTER streaming on the complete [targetFile] (random access possible) with the first 8 bytes; the default is the magic-byte
     * allowlist of receipts/attachments. A custom classifier decides the MIME type itself.
     */
    classify: (File, ByteArray, Int) -> FileClassification = DEFAULT_TRAVEL_EXPENSE_CLASSIFIER,
    /** Number of text form fields to collect (0 == ignore them all, the receipt/attachment behaviour). */
    maxFormFields: Int = 0,
): StreamedTravelExpenseFile {
    targetFile.parentFile.mkdirs()
    var uploadedFileName: String? = null
    var totalBytes = 0L
    val digest = MessageDigest.getInstance("SHA-256")
    var tooLarge = false
    val sniffBuffer = ByteArray(TRAVEL_EXPENSE_SNIFF_BYTES)
    var sniffBufferFilled = 0
    var fileItemCount = 0
    val formFields = linkedMapOf<String, String>()
    var invalidFormFields = false

    try {
        receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    fileItemCount++
                    if (fileItemCount == 1) {
                        uploadedFileName = part.originalFileName ?: fallbackName
                        targetFile.outputStream().use { out ->
                            val channel: ByteReadChannel = part.provider()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = channel.readAvailable(buffer)
                                if (read == -1) break
                                if (sniffBufferFilled < TRAVEL_EXPENSE_SNIFF_BYTES) {
                                    val toCopy = minOf(TRAVEL_EXPENSE_SNIFF_BYTES - sniffBufferFilled, read)
                                    System.arraycopy(buffer, 0, sniffBuffer, sniffBufferFilled, toCopy)
                                    sniffBufferFilled += toCopy
                                }
                                totalBytes += read
                                if (totalBytes > maxBytes) {
                                    tooLarge = true
                                    break
                                }
                                digest.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                            }
                        }
                        part.release()
                    } else {
                        // Abort the whole multipart read the moment a SECOND file part is seen.
                        part.release()
                        throw MultipleFilePartsRejected
                    }
                }
                is PartData.FormItem -> {
                    val name = part.name
                    if (maxFormFields > 0 &&
                        name != null &&
                        formFields.size < maxFormFields &&
                        part.value.length <= TRAVEL_EXPENSE_MAX_FORM_FIELD_CHARS &&
                        name !in formFields
                    ) {
                        formFields[name] = part.value
                    } else if (maxFormFields > 0) {
                        invalidFormFields = true
                    }
                    part.release()
                }
                else -> part.release()
            }
        }
    } catch (e: MultipleFilePartsRejected) {
        fileItemCount = 2
    } catch (e: Exception) {
        targetFile.delete()
        throw e
    }

    if (tooLarge) {
        targetFile.delete()
        return StreamedTravelExpenseFile.TooLarge
    }
    if (fileItemCount > 1) {
        targetFile.delete()
        return StreamedTravelExpenseFile.MultipleFileParts
    }
    val fileName = uploadedFileName
    if (fileName == null) {
        targetFile.delete()
        return StreamedTravelExpenseFile.NoFilePart
    }
    if (invalidFormFields) {
        targetFile.delete()
        return StreamedTravelExpenseFile.InvalidFormFields
    }
    // Fail closed: a classifier that throws rejects the file as an unsupported type; the file never survives a refusal.
    val verdict =
        try {
            classify(targetFile, sniffBuffer, sniffBufferFilled)
        } catch (_: Exception) {
            FileClassification.Rejected(FileRejection.TYPE)
        }
    val mimeType =
        when (verdict) {
            is FileClassification.Accepted -> verdict.mimeType
            is FileClassification.Rejected -> {
                targetFile.delete()
                return if (classify === DEFAULT_TRAVEL_EXPENSE_CLASSIFIER) {
                    StreamedTravelExpenseFile.UnsupportedType
                } else {
                    StreamedTravelExpenseFile.Rejected(verdict.reason)
                }
            }
        }
    return StreamedTravelExpenseFile.Stored(
        sanitizedFileName = sanitizeUploadFilename(raw = fileName, fallback = fallbackName),
        sizeBytes = totalBytes,
        sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        mimeType = mimeType,
        formFields = formFields,
    )
}

/** Maps every non-[StreamedTravelExpenseFile.Stored] outcome to its HTTP response. */
internal suspend fun ApplicationCall.respondStreamFailure(
    outcome: StreamedTravelExpenseFile,
    maxBytes: Long = TravelExpenseAmountRules.MAX_UPLOAD_BYTES,
) {
    when (outcome) {
        StreamedTravelExpenseFile.TooLarge -> respond(HttpStatusCode.PayloadTooLarge, "Max upload size is $maxBytes bytes")
        StreamedTravelExpenseFile.InvalidFormFields -> respond(HttpStatusCode.BadRequest, "Invalid form fields")
        is StreamedTravelExpenseFile.Rejected -> respond(HttpStatusCode.UnsupportedMediaType, outcome.reason.token)
        StreamedTravelExpenseFile.MultipleFileParts ->
            respond(HttpStatusCode.BadRequest, "Request must contain exactly one file part")
        StreamedTravelExpenseFile.NoFilePart -> respond(HttpStatusCode.BadRequest, "No file part in request")
        StreamedTravelExpenseFile.UnsupportedType ->
            respond(HttpStatusCode.UnsupportedMediaType, "Unsupported or unrecognized file type")
        is StreamedTravelExpenseFile.Stored -> error("respondStreamFailure called with a Stored outcome")
    }
}

/**
 * Download response: `Content-Disposition: attachment` (RFC 5987), `nosniff`, `private, no-store`
 * and the SNIFFED type -- so a stored file can never render inline as active content.
 */
internal suspend fun ApplicationCall.respondTravelExpenseFile(
    file: File,
    originalFilename: String,
    mimeType: String,
) {
    response.header(HttpHeaders.ContentDisposition, contentDispositionHeader(originalFilename))
    response.header("X-Content-Type-Options", "nosniff")
    response.header(HttpHeaders.CacheControl, "private, no-store")
    respond(LocalFileContent(file, ContentType.parse(mimeType)))
}

/** Self-service == the report's subject or the member who requested it. */
internal fun isTravelExpenseSelfService(
    current: CurrentMember,
    subjectId: Uuid,
    requestedById: Uuid,
): Boolean = current.memberId == subjectId || current.memberId == requestedById

/**
 * IDOR rule for downloads, in ONE place: subject/requester always; BOARD/ADMIN only once the
 * report left DRAFT (a draft is private); deliberately never TREASURER.
 */
internal fun canDownloadTravelExpenseFile(
    current: CurrentMember,
    subjectId: Uuid,
    requestedById: Uuid,
    status: TravelExpenseReportStatus,
): Boolean =
    isTravelExpenseSelfService(current = current, subjectId = subjectId, requestedById = requestedById) ||
        (current.isPrivileged && status != TravelExpenseReportStatus.DRAFT)

/**
 * Pure control-flow signal for aborting `forEachPart` the instant a second file part is seen --
 * never surfaced to a caller. Stack-trace capture suppressed (hot path of every rejection).
 */
internal object MultipleFilePartsRejected : RuntimeException(null, null, false, false)

/**
 * Derives a MIME type from magic bytes ONLY -- the client-declared `Content-Type` never reaches
 * this function. Returns `null` for anything unrecognized (including a truncated/empty upload),
 * which the caller turns into 415. Deliberately no `image/svg+xml` -- an XSS vector.
 */
internal fun sniffMimeType(
    buffer: ByteArray,
    filled: Int,
): String? =
    when {
        filled >= 4 &&
            buffer[0] == 0x25.toByte() &&
            buffer[1] == 0x50.toByte() &&
            buffer[2] == 0x44.toByte() &&
            buffer[3] == 0x46.toByte()
        -> "application/pdf"
        filled >= 3 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xD8.toByte() && buffer[2] == 0xFF.toByte() -> "image/jpeg"
        filled >= 8 &&
            buffer[0] == 0x89.toByte() &&
            buffer[1] == 0x50.toByte() &&
            buffer[2] == 0x4E.toByte() &&
            buffer[3] == 0x47.toByte() &&
            buffer[4] == 0x0D.toByte() &&
            buffer[5] == 0x0A.toByte() &&
            buffer[6] == 0x1A.toByte() &&
            buffer[7] == 0x0A.toByte() ->
            "image/png"
        else -> null
    }

/**
 * Strips any path separator and control character, keeps at most 255 characters, falls back to
 * "beleg" for an empty result -- pure display metadata, never used to build a filesystem path.
 */
internal fun sanitizeReceiptFilename(raw: String): String = sanitizeUploadFilename(raw = raw, fallback = "beleg")

/** [sanitizeReceiptFilename] with a caller-specific [fallback] for an empty result (documents use "unterlage"). */
internal fun sanitizeUploadFilename(
    raw: String,
    fallback: String,
): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.filterNot { it.code in 0x00..0x1F || it.code == 0x7F }
    val truncated = cleaned.take(255)
    return truncated.ifBlank { fallback }
}

/**
 * Sets the extension that matches the sniffed [mime] (`.pdf`, `.xlsx`, `.xls`): a wrong extension is replaced, a missing one appended, so
 * a stored name never claims a different type than the content. Display metadata only.
 */
internal fun filenameWithExtension(
    name: String,
    mime: String,
): String {
    val extension =
        when (mime) {
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-excel" -> "xls"
            else -> return name
        }
    val hasDot = name.contains('.') && name.substringBeforeLast('.').isNotEmpty()
    val stem = if (hasDot) name.substringBeforeLast('.') else name
    val current = if (hasDot) name.substringAfterLast('.') else ""
    if (current.equals(extension, ignoreCase = true)) return name
    // 255 character cap of the column: shorten the stem, never the extension.
    return stem.take(255 - extension.length - 1) + "." + extension
}

/** RFC 5987 `filename*=UTF-8''...` plus an ASCII `filename=` fallback (header-injection safe). */
internal fun contentDispositionHeader(originalFilename: String): String {
    val asciiFallback =
        originalFilename
            .filter { it.code in 0x20..0x7E && it != '"' && it != '\\' }
            .ifBlank { "beleg" }
    val encoded =
        java.net.URLEncoder
            .encode(originalFilename, "UTF-8")
            .replace("+", "%20")
    return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}
