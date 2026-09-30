package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ChapterCrestUploadError
import network.lapis.cloud.shared.domain.ChapterCrestUploadResultDto
import network.lapis.cloud.shared.domain.RegionalChapterPublicRules
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

/** The `accept` list of the file dialog: raster crests and SVG (`.svg` covers platforms that report no MIME type). */
internal val CREST_ACCEPT: List<String> = listOf("image/jpeg", "image/png", "image/svg+xml", ".svg")

/** What kind of file the user picked -- decides the client-side size limit and the wording of an error. */
internal enum class CrestFileKind { RASTER, SVG }

/**
 * Pure: the kind of a picked file by its declared MIME type, falling back to the `.svg` extension when the
 * browser reports no type (common for SVG on some platforms). `null` = not acceptable. The server never
 * trusts this; it sniffs the bytes itself.
 */
internal fun crestFileKindOf(
    type: String,
    name: String,
): CrestFileKind? =
    when {
        type == "image/jpeg" || type == "image/png" -> CrestFileKind.RASTER
        type == "image/svg+xml" -> CrestFileKind.SVG
        type.isBlank() && name.endsWith(".svg", ignoreCase = true) -> CrestFileKind.SVG
        else -> null
    }

/** Pure pre-flight check before any byte is sent; `null` = the file may be uploaded. */
internal fun crestPrecheck(
    type: String,
    name: String,
    size: Double,
): ChapterCrestUploadError? =
    when (crestFileKindOf(type = type, name = name)) {
        null -> ChapterCrestUploadError.UNSUPPORTED_FORMAT
        CrestFileKind.SVG ->
            if (size > RegionalChapterPublicRules.CREST_SVG_MAX_UPLOAD_BYTES) ChapterCrestUploadError.FILE_TOO_LARGE else null
        CrestFileKind.RASTER ->
            if (size > RegionalChapterPublicRules.CREST_MAX_UPLOAD_BYTES) ChapterCrestUploadError.FILE_TOO_LARGE else null
    }

/**
 * One fixed, translated sentence per code -- never server text. [kind] is the kind of the file the user picked;
 * it selects the SVG wording where the raster wording (2 MB, 8000 px, "damaged image") would be wrong. Every
 * branch is ONE complete `tr(...)` string -- never concatenated.
 */
internal fun chapterCrestUploadErrorMessage(
    code: ChapterCrestUploadError,
    kind: CrestFileKind? = null,
): String =
    when (code) {
        ChapterCrestUploadError.UNSUPPORTED_FORMAT -> tr("Nur JPEG-, PNG- oder SVG-Dateien sind erlaubt.")
        ChapterCrestUploadError.UNDECODABLE ->
            if (kind == CrestFileKind.SVG) {
                tr("Die SVG-Datei ist beschädigt oder kein gültiges SVG.")
            } else {
                tr("Nur JPEG- oder PNG-Bilder sind erlaubt.")
            }
        ChapterCrestUploadError.FILE_TOO_LARGE ->
            if (kind == CrestFileKind.SVG) tr("Die SVG-Datei ist größer als 256 KB.") else tr("Die Datei ist größer als 2 MB.")
        ChapterCrestUploadError.TOO_SMALL -> tr("Das Bild ist zu klein (mindestens 64 × 64 Pixel).")
        ChapterCrestUploadError.RATE_LIMITED -> tr("Zu viele Versuche. Bitte später erneut versuchen.")
        ChapterCrestUploadError.DIMENSIONS_TOO_LARGE ->
            if (kind == CrestFileKind.SVG) {
                tr("Die Zeichenfläche (viewBox) der SVG-Datei ist ungültig oder zu schmal bzw. zu hoch (Seitenverhältnis höchstens 4:1).")
            } else {
                tr("Das Bild ist zu groß (maximal 8000 Pixel Kantenlänge).")
            }
        ChapterCrestUploadError.SVG_SCRIPT -> tr("Die SVG-Datei enthält Skripte oder Animationen und wird nicht akzeptiert.")
        ChapterCrestUploadError.SVG_EXTERNAL_REFERENCE ->
            tr("Die SVG-Datei verweist auf externe Inhalte. Bitte betten Sie alle Inhalte in die Datei ein.")
        ChapterCrestUploadError.SVG_TEXT_NOT_SUPPORTED ->
            tr("Die SVG-Datei enthält Text. Bitte wandeln Sie den Text in Pfade (Kurven) um.")
        ChapterCrestUploadError.SVG_NO_DIMENSIONS -> tr("Der SVG-Datei fehlt die Angabe der Zeichenfläche (viewBox).")
        ChapterCrestUploadError.SVG_TOO_COMPLEX -> tr("Die SVG-Datei ist zu komplex. Bitte vereinfachen Sie die Zeichnung.")
        ChapterCrestUploadError.SVG_UNSUPPORTED_CONTENT ->
            tr("Die SVG-Datei enthält Elemente oder Eigenschaften, die nicht unterstützt werden (zum Beispiel Filter, Stile oder Muster).")
        ChapterCrestUploadError.FORBIDDEN,
        ChapterCrestUploadError.NOT_FOUND,
        ChapterCrestUploadError.INVALID_REQUEST,
        ChapterCrestUploadError.BUSY,
        -> tr("Das Wappen konnte nicht hochgeladen werden.")
    }
