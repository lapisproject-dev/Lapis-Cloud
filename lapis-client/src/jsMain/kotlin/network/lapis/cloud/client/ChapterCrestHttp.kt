package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ChapterCrestUploadError
import network.lapis.cloud.shared.domain.ChapterCrestUploadResultDto
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the byte-carrying upload of a regional chapter crest
 * (`POST /api/regional-chapters/{chapterId}/crest`, BOARD/ADMIN). `XMLHttpRequest` for reliable
 * upload progress, same reasoning as [MemberPhotoHttp]/[EventCoverHttp].
 *
 * **The response body is NEVER shown.** The server answers every failure with a JSON
 * [ChapterCrestUploadResultDto] carrying an enum code only; [chapterCrestErrorOf] maps status + code
 * to a [ChapterCrestUploadError] and [chapterCrestUploadErrorMessage] to a FIXED, translated sentence.
 * [chapterId] is a server-issued UUID taken from the chapter list, never user input.
 */
object ChapterCrestHttp {
    sealed interface Result {
        data object Ok : Result

        data class Error(
            val code: ChapterCrestUploadError,
        ) : Result
    }

    suspend fun upload(
        chapterId: String,
        file: File,
        onProgress: (fraction: Double) -> Unit = {},
    ): Result =
        suspendCancellableCoroutine { cont ->
            val formData = FormData()
            formData.append("file", file, file.name)

            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/regional-chapters/$chapterId/crest")
            xhr.withCredentials = true
            xhr.upload.onprogress = { event ->
                val total = event.total.toDouble()
                if (event.lengthComputable && total > 0.0) {
                    onProgress((event.loaded.toDouble() / total).coerceIn(0.0, 1.0))
                }
                Unit
            }
            xhr.onload = {
                cont.resume(chapterCrestResultOf(status = xhr.status.toInt(), body = xhr.responseText))
                Unit
            }
            xhr.onerror = {
                cont.resume(Result.Error(ChapterCrestUploadError.INVALID_REQUEST))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(formData)
        }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Pure: HTTP status + raw body -> [ChapterCrestHttp.Result]. Internal for tests. */
internal fun chapterCrestResultOf(
    status: Int,
    body: String,
): ChapterCrestHttp.Result {
    if (status in 200..299) return ChapterCrestHttp.Result.Ok
    return ChapterCrestHttp.Result.Error(chapterCrestErrorOf(status = status, body = body))
}

/** The JSON code wins; without a readable code the status decides; anything unknown is [ChapterCrestUploadError.INVALID_REQUEST]. */
internal fun chapterCrestErrorOf(
    status: Int,
    body: String,
): ChapterCrestUploadError {
    val coded =
        runCatching { lenientJson.decodeFromString(ChapterCrestUploadResultDto.serializer(), body).error }.getOrNull()
    if (coded != null) return coded
    return when (status) {
        413 -> ChapterCrestUploadError.FILE_TOO_LARGE
        415 -> ChapterCrestUploadError.UNSUPPORTED_FORMAT
        429 -> ChapterCrestUploadError.RATE_LIMITED
        else -> ChapterCrestUploadError.INVALID_REQUEST
    }
}

/** One fixed, translated sentence per code -- never server text. */
internal fun chapterCrestUploadErrorMessage(code: ChapterCrestUploadError): String =
    when (code) {
        ChapterCrestUploadError.UNSUPPORTED_FORMAT, ChapterCrestUploadError.UNDECODABLE ->
            tr("Nur JPEG- oder PNG-Bilder sind erlaubt.")
        ChapterCrestUploadError.FILE_TOO_LARGE -> tr("Die Datei ist größer als 2 MB.")
        ChapterCrestUploadError.TOO_SMALL -> tr("Das Bild ist zu klein (mindestens 64 × 64 Pixel).")
        ChapterCrestUploadError.RATE_LIMITED -> tr("Zu viele Versuche. Bitte später erneut versuchen.")
        ChapterCrestUploadError.DIMENSIONS_TOO_LARGE -> tr("Das Bild ist zu groß (maximal 8000 Pixel Kantenlänge).")
        ChapterCrestUploadError.FORBIDDEN,
        ChapterCrestUploadError.NOT_FOUND,
        ChapterCrestUploadError.INVALID_REQUEST,
        ChapterCrestUploadError.BUSY,
        -> tr("Das Wappen konnte nicht hochgeladen werden.")
    }
