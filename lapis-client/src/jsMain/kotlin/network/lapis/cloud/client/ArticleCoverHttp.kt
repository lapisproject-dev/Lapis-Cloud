package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ArticleCoverResultDto
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- exact mirror of [EventCoverHttp] for
 * the article cover-image family (`POST`/`DELETE /api/articles/{id}/cover`, see
 * `network.lapis.cloud.server.routes.registerArticleCoverRoutes`). Same reasoning for going around
 * Kilua RPC (`XMLHttpRequest`, not `window.fetch`, for reliable upload progress; a narrow
 * [ArticleCoverResultDto] decoded with plain `kotlinx.serialization.Json`, not the RPC serializer
 * module).
 */
object ArticleCoverHttp {
    sealed interface Result {
        data class Ok(
            val coverImageUrl: String?,
        ) : Result

        data class Error(
            val message: String,
        ) : Result
    }

    suspend fun upload(
        articleId: String,
        file: File,
        onProgress: (fraction: Double) -> Unit = {},
    ): Result =
        suspendCancellableCoroutine { cont ->
            val formData = FormData()
            formData.append("file", file, file.name)

            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/articles/$articleId/cover")
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

    suspend fun remove(articleId: String): Result =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            xhr.open("DELETE", "/api/articles/$articleId/cover")
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
            Json.decodeFromString(ArticleCoverResultDto.serializer(), xhr.responseText)
        }.fold(
            onSuccess = { Result.Ok(it.coverImageUrl) },
            onFailure = { Result.Error(tr("Antwort konnte nicht gelesen werden.")) },
        )
    }
}
