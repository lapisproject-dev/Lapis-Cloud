package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

/** Result of one attachment upload: created, or refused with the FIXED `code` the server answered (empty == no usable answer). */
internal sealed interface MailingUploadOutcome {
    data class Created(
        val id: String,
    ) : MailingUploadOutcome

    data class Refused(
        val status: Int,
        val code: String,
    ) : MailingUploadOutcome
}

/**
 * V1.9.232 -- the dedicated HTTP routes for attachments on mailing messages (`MailingAttachmentRoutes`); file bytes do not travel over
 * Kilua RPC. Only the fixed `code` of an error body is read; a server text is never shown.
 */
internal object MailingHttp {
    fun downloadUrl(attachmentId: String): String = "/api/mailing/attachments/$attachmentId/download"

    suspend fun uploadAttachment(
        messageId: String,
        file: File,
    ): MailingUploadOutcome {
        val formData = FormData()
        formData.append("file", file, file.name)
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/mailing/messages/$messageId/attachments",
                        RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE),
                    ).await()
            }.getOrNull() ?: return MailingUploadOutcome.Refused(status = 0, code = "")
        val text = runCatching { response.text().await() }.getOrDefault("")
        if (!response.ok) return MailingUploadOutcome.Refused(status = response.status.toInt(), code = codeOf(text))
        return MailingUploadOutcome.Created(id = idOf(text))
    }

    /** Returns `null` on success, otherwise the fixed error code (empty == no usable answer). */
    suspend fun deleteAttachment(attachmentId: String): String? {
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/mailing/attachments/$attachmentId",
                        RequestInit(method = "DELETE", credentials = RequestCredentials.INCLUDE),
                    ).await()
            }.getOrNull() ?: return ""
        if (response.ok) return null
        return codeOf(runCatching { response.text().await() }.getOrDefault(""))
    }

    private fun codeOf(body: String): String =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull().orEmpty()

    private fun idOf(body: String): String =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["attachmentId"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull().orEmpty()
}
