package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.shared.domain.DirectMessageAttachmentDto
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

private external fun encodeURIComponent(value: String): String

/** The HTTP surface of the direct-message composer -- an interface so DOM tests can drive it without a server. */
internal interface DirectMessageAttachmentHttp {
    /** Uploads [file] as a pending attachment for [recipientId]. Reuses [MailingUploadOutcome] (created, or the FIXED refusal code). */
    suspend fun upload(
        recipientId: String,
        file: File,
    ): MailingUploadOutcome

    /** The caller's own pending uploads for [recipientId] (so a rebuilt composer shows them again), or `null` if they could not be read. */
    suspend fun listPending(recipientId: String): List<DirectMessageAttachmentDto>?

    /** Deletes a pending upload. Returns `null` on success, otherwise the fixed error code (empty == no usable answer). */
    suspend fun delete(attachmentId: String): String?
}

/**
 * V1.9.233 -- the dedicated HTTP routes for attachments on direct messages (`DirectMessageAttachmentRoutes`); file bytes do not travel
 * over Kilua RPC. Only the fixed `code` of an error body is read; a server text is never shown. The download URL carries the id only
 * (never the file name, never the member), so a name cannot end up in a proxy log.
 */
internal object DirectMessageHttp : DirectMessageAttachmentHttp {
    fun downloadUrl(attachmentId: String): String = "/api/direct-messages/attachments/$attachmentId/download"

    override suspend fun upload(
        recipientId: String,
        file: File,
    ): MailingUploadOutcome {
        val formData = FormData()
        // The recipient field MUST come before the file: the server checks it before it streams one byte of the file.
        formData.append("recipientId", recipientId)
        formData.append("file", file, file.name)
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/direct-messages/attachments",
                        RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE),
                    ).await()
            }.getOrNull() ?: return MailingUploadOutcome.Refused(status = 0, code = "")
        val text = runCatching { response.text().await() }.getOrDefault("")
        if (!response.ok) return MailingUploadOutcome.Refused(status = response.status.toInt(), code = jsonField(text, "code"))
        return MailingUploadOutcome.Created(id = jsonField(text, "attachmentId"))
    }

    override suspend fun listPending(recipientId: String): List<DirectMessageAttachmentDto>? {
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/direct-messages/attachments/pending?recipientId=${encodeURIComponent(recipientId)}",
                        RequestInit(method = "GET", credentials = RequestCredentials.INCLUDE),
                    ).await()
            }.getOrNull() ?: return null
        if (!response.ok) return null
        val text = runCatching { response.text().await() }.getOrNull() ?: return null
        return runCatching { Json.decodeFromString(ListSerializer(DirectMessageAttachmentDto.serializer()), text) }.getOrNull()
    }

    override suspend fun delete(attachmentId: String): String? {
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/direct-messages/attachments/$attachmentId",
                        RequestInit(method = "DELETE", credentials = RequestCredentials.INCLUDE),
                    ).await()
            }.getOrNull() ?: return ""
        if (response.ok) return null
        return jsonField(runCatching { response.text().await() }.getOrDefault(""), "code")
    }

    private fun jsonField(
        body: String,
        name: String,
    ): String =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject[name]
                ?.jsonPrimitive
                ?.content
        }.getOrNull().orEmpty()
}
