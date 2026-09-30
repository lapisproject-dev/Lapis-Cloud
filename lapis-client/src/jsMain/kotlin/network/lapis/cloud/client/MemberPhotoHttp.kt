package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.MemberPhotoUploadError
import network.lapis.cloud.shared.domain.MemberPhotoUploadResultDto
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- the byte-carrying upload of the member's OWN photo
 * (`POST /api/member-photo`). `XMLHttpRequest` for reliable upload progress, same reasoning as
 * [EventCoverHttp]/[DocumentHttp].
 *
 * **The response body is NEVER shown.** The server answers every failure with a JSON
 * [MemberPhotoUploadResultDto] carrying an enum code only; [memberPhotoErrorOf] maps status + code to a
 * [MemberPhotoUploadError] and [memberPhotoUploadErrorMessage] to a FIXED, translated sentence.
 */
object MemberPhotoHttp {
    sealed interface Result {
        data object Ok : Result

        data class Error(
            val code: MemberPhotoUploadError,
        ) : Result
    }

    suspend fun upload(
        file: File,
        onProgress: (fraction: Double) -> Unit = {},
    ): Result =
        suspendCancellableCoroutine { cont ->
            val formData = FormData()
            formData.append("file", file, file.name)

            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/member-photo")
            xhr.withCredentials = true
            xhr.upload.onprogress = { event ->
                val total = event.total.toDouble()
                if (event.lengthComputable && total > 0.0) {
                    onProgress((event.loaded.toDouble() / total).coerceIn(0.0, 1.0))
                }
                Unit
            }
            xhr.onload = {
                cont.resume(memberPhotoResultOf(status = xhr.status.toInt(), body = xhr.responseText))
                Unit
            }
            xhr.onerror = {
                cont.resume(Result.Error(MemberPhotoUploadError.INVALID_REQUEST))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(formData)
        }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Pure: HTTP status + raw body -> [MemberPhotoHttp.Result]. Internal for tests. */
internal fun memberPhotoResultOf(
    status: Int,
    body: String,
): MemberPhotoHttp.Result {
    if (status in 200..299) return MemberPhotoHttp.Result.Ok
    return MemberPhotoHttp.Result.Error(memberPhotoErrorOf(status = status, body = body))
}

/** The JSON code wins; without a readable code the status decides; anything unknown is [MemberPhotoUploadError.INVALID_REQUEST]. */
internal fun memberPhotoErrorOf(
    status: Int,
    body: String,
): MemberPhotoUploadError {
    val coded =
        runCatching { lenientJson.decodeFromString(MemberPhotoUploadResultDto.serializer(), body).error }.getOrNull()
    if (coded != null) return coded
    return when (status) {
        413 -> MemberPhotoUploadError.FILE_TOO_LARGE
        429 -> MemberPhotoUploadError.RATE_LIMITED
        else -> MemberPhotoUploadError.INVALID_REQUEST
    }
}

/** One fixed, translated sentence per code -- never server text. */
internal fun memberPhotoUploadErrorMessage(code: MemberPhotoUploadError): String =
    when (code) {
        MemberPhotoUploadError.UNSUPPORTED_FORMAT, MemberPhotoUploadError.UNDECODABLE ->
            tr("Nur JPEG- oder PNG-Bilder sind erlaubt. iPhone: Kamera-Einstellung ‚Maximal kompatibel‘ verwenden.")
        MemberPhotoUploadError.FILE_TOO_LARGE -> tr("Die Datei ist größer als 10 MB.")
        MemberPhotoUploadError.TOO_SMALL -> tr("Das Bild ist zu klein (mindestens 400 × 400 Pixel).")
        MemberPhotoUploadError.RATE_LIMITED -> tr("Zu viele Versuche. Bitte später erneut versuchen.")
        MemberPhotoUploadError.DIMENSIONS_TOO_LARGE -> tr("Das Bild ist zu groß (maximal 8000 Pixel Kantenlänge).")
        MemberPhotoUploadError.NOT_ELIGIBLE, MemberPhotoUploadError.INVALID_REQUEST, MemberPhotoUploadError.BUSY ->
            tr("Das Foto konnte nicht hochgeladen werden.")
    }
