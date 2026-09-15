package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * Mirrors `network.lapis.cloud.server.routes.DocumentRoutes.kt` -- file bytes travel over dedicated
 * HTTP routes, not Kilua RPC (see `IDocumentService` KDoc: "inefficient for large byte arrays").
 * Upload takes the native `org.w3c.files.File` behind an `io.kvision.form.upload.Upload` control's
 * selected `KFile` (via `Upload.getNativeFile`) rather than the `KFile`'s own base64 `content`
 * field -- avoids a ~33% size inflation and an extra encode/decode round-trip for uploads up to the
 * server's configurable cap (`DocumentRoutes.documentMaxUploadBytes`, default 128 MiB).
 *
 * `XMLHttpRequest`, NOT `window.fetch` -- Nutzer-Beschwerde 2026-09-15 ("kein Signal während des
 * Uploads, man fängt an wild zu klicken"). The Fetch API has no cross-browser-reliable upload
 * progress event; `XMLHttpRequest.upload.onprogress` does (has, since IE10). This is the one place
 * in this file that justifies the older API over the otherwise-preferred `fetch`.
 */
object DocumentHttp {
    fun downloadUrl(
        documentId: String,
        versionId: String? = null,
    ): String =
        if (versionId != null) {
            "/api/documents/$documentId/download?version=$versionId"
        } else {
            "/api/documents/$documentId/download"
        }

    /**
     * @param onProgress called repeatedly with a fraction in `[0.0, 1.0]` while the upload body is
     *   being sent -- NOT while the server processes it afterward (hashing/DB write are fast enough
     *   here that a second, separate "processing" indicator isn't worth the complexity; see
     *   `DocumentsScreen.kt`'s caller for how the progress bar is finalized either way).
     */
    suspend fun uploadVersion(
        documentId: String,
        file: File,
        changeNote: String?,
        onProgress: (fraction: Double) -> Unit = {},
    ): String? =
        suspendCancellableCoroutine { cont ->
            val formData = FormData()
            formData.append("file", file, file.name)
            if (!changeNote.isNullOrBlank()) formData.append("changeNote", changeNote)

            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/documents/$documentId/versions")
            // `fetch`'s `credentials = INCLUDE` equivalent -- the session cookie must ride along,
            // exactly like every other same-origin call this client makes.
            xhr.withCredentials = true
            xhr.upload.onprogress = { event ->
                val total = event.total.toDouble()
                if (event.lengthComputable && total > 0.0) {
                    onProgress((event.loaded.toDouble() / total).coerceIn(0.0, 1.0))
                }
                Unit
            }
            xhr.onload = {
                val ok = xhr.status.toInt() in 200..299
                cont.resume(if (ok) null else xhr.responseText.ifBlank { tr("Upload fehlgeschlagen.") })
                Unit
            }
            xhr.onerror = {
                // Network-level failure (offline, CORS, connection reset) -- `xhr.responseText` is
                // meaningless here (no response was ever received), unlike the `onload` branch above.
                cont.resume(tr("Upload fehlgeschlagen."))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(formData)
        }
}
