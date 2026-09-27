package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.EventCoverResultDto
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- mirrors [DocumentHttp]'s own reasoning
 * for going around dedicated Ktor routes rather than Kilua RPC ("inefficient for large byte
 * arrays"), plus an additional reason here: [network.lapis.cloud.shared.domain.EventDto] carries
 * `dev.kilua.rpc.types.Decimal`/`kotlinx.datetime.LocalDateTime` fields that no plain Ktor route in
 * this codebase encodes with bare `kotlinx.serialization.Json` (only Kilua RPC's own serializer
 * module does) -- so the server responds with the narrower
 * [network.lapis.cloud.shared.domain.EventCoverResultDto] instead, decoded here with plain `Json`.
 *
 * `XMLHttpRequest`, NOT `window.fetch` -- same reasoning [DocumentHttp] documents (reliable upload
 * progress).
 */
object EventCoverHttp {
    sealed interface Result {
        data class Ok(
            val coverImageUrl: String?,
        ) : Result

        data class Error(
            val message: String,
        ) : Result
    }

    suspend fun upload(
        slug: String,
        file: File,
        onProgress: (fraction: Double) -> Unit = {},
    ): Result =
        suspendCancellableCoroutine { cont ->
            val formData = FormData()
            formData.append("file", file, file.name)

            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/embed/v1/event/$slug/cover")
            xhr.withCredentials = true
            xhr.upload.onprogress = { event ->
                val total = event.total.toDouble()
                if (event.lengthComputable && total > 0.0) {
                    onProgress((event.loaded.toDouble() / total).coerceIn(0.0, 1.0))
                }
                Unit
            }
            xhr.onload = {
                cont.resume(parseResponse(xhr))
                Unit
            }
            xhr.onerror = {
                cont.resume(Result.Error(tr("Upload fehlgeschlagen.")))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(formData)
        }

    suspend fun remove(slug: String): Result =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            xhr.open("DELETE", "/api/embed/v1/event/$slug/cover")
            xhr.withCredentials = true
            xhr.onload = {
                cont.resume(parseResponse(xhr))
                Unit
            }
            xhr.onerror = {
                cont.resume(Result.Error(tr("Entfernen fehlgeschlagen.")))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send()
        }

    private fun parseResponse(xhr: XMLHttpRequest): Result {
        val ok = xhr.status.toInt() in 200..299
        if (!ok) return Result.Error(xhr.responseText.ifBlank { tr("Anfrage fehlgeschlagen.") })
        return runCatching {
            Json.decodeFromString(EventCoverResultDto.serializer(), xhr.responseText)
        }.fold(
            onSuccess = { Result.Ok(it.coverImageUrl) },
            onFailure = { Result.Error(tr("Antwort konnte nicht gelesen werden.")) },
        )
    }
}
