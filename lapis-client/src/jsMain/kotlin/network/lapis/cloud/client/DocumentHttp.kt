package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

/**
 * Mirrors `network.lapis.cloud.server.routes.DocumentRoutes.kt` -- file bytes travel over dedicated
 * HTTP routes, not Kilua RPC (see `IDocumentService` KDoc: "inefficient for large byte arrays").
 * Upload takes the native `org.w3c.files.File` behind an `io.kvision.form.upload.Upload` control's
 * selected `KFile` (via `Upload.getNativeFile`) rather than the `KFile`'s own base64 `content`
 * field -- avoids a ~33% size inflation and an extra encode/decode round-trip for uploads up to the
 * server's 25MB cap (`DocumentRoutes.MAX_UPLOAD_BYTES`).
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

    suspend fun uploadVersion(
        documentId: String,
        file: File,
        changeNote: String?,
    ): String? {
        val formData = FormData()
        formData.append("file", file, file.name)
        if (!changeNote.isNullOrBlank()) formData.append("changeNote", changeNote)
        val response =
            window
                .fetch(
                    "/api/documents/$documentId/versions",
                    RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE),
                ).await()
        return if (response.ok) null else response.text().await().ifBlank { tr("Upload fehlgeschlagen.") }
    }
}
