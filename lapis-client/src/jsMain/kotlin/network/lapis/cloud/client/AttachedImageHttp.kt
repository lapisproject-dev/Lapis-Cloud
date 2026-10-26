package network.lapis.cloud.client

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AttachedImageUploadResultDto
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * V1.9.222 -- the upload of ONE gallery picture. `XMLHttpRequest` (not `fetch`) for the same reason [EventCoverHttp] gives: reliable
 * upload
 * progress.
 * The server answers with plain JSON of primitives ([AttachedImageUploadResultDto]) -- an error is always a stable CODE, never free
 * text,
 * so
 * this client maps only codes ([galleryUploadErrorText]); a server text is never shown. A network failure is `Error(null)`.
 */
internal object AttachedImageHttp {
    sealed interface Result {
        data class Ok(
            val id: String,
            val url: String,
            val position: Int,
        ) : Result

        data class Error(
            val code: GalleryUploadErrorCode?,
        ) : Result
    }

    suspend fun upload(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        file: File,
        onProgress: (fraction: Double) -> Unit = {},
    ): Result =
        suspendCancellableCoroutine { cont ->
            val formData = FormData()
            formData.append("file", file, file.name)
            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/images/${ownerType.name}/$ownerId")
            xhr.withCredentials = true
            xhr.upload.onprogress = { event ->
                val total = event.total.toDouble()
                if (event.lengthComputable && total > 0.0) onProgress((event.loaded.toDouble() / total).coerceIn(0.0, 1.0))
                Unit
            }
            xhr.onload = {
                cont.resume(parse(status = xhr.status.toInt(), body = xhr.responseText))
                Unit
            }
            xhr.onerror = {
                cont.resume(Result.Error(null))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(formData)
        }

    /** Pure: the mapping of a status and a body to a [Result] (tested without a network). */
    internal fun parse(
        status: Int,
        body: String,
    ): Result {
        val dto =
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString(
                    AttachedImageUploadResultDto.serializer(),
                    body,
                )
            }.getOrNull()
        if (status in 200..299 && dto?.id != null && dto.url != null && dto.position != null) {
            return Result.Ok(id = dto.id!!, url = dto.url!!, position = dto.position!!)
        }
        return Result.Error(dto?.error)
    }
}
