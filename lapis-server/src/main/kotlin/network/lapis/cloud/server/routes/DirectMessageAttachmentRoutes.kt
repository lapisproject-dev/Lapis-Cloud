package network.lapis.cloud.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.attachments.AttachmentUploadClassification
import network.lapis.cloud.server.attachments.attachmentNameKey
import network.lapis.cloud.server.attachments.attachmentStreamFailureCode
import network.lapis.cloud.server.attachments.classifyAttachmentUpload
import network.lapis.cloud.server.attachments.resolveContainedAttachmentFile
import network.lapis.cloud.server.attachments.respondAttachmentDownload
import network.lapis.cloud.server.attachments.respondAttachmentError
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.messaging.DirectMessageAttachmentStore
import network.lapis.cloud.server.messaging.DirectMessagePendingLimits
import network.lapis.cloud.server.rpc.directMessageRecipientRefusal
import network.lapis.cloud.server.rpc.requireActiveMembership
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AttachmentErrorCode
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.DirectMessageAttachmentDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private const val DM_STORAGE_PREFIX = "dm"
private const val RECIPIENT_FIELD = "recipientId"

/**
 * V1.9.233 -- attachments on direct messages. Bytes travel over these dedicated routes, not Kilua RPC; the upload pipeline (streaming
 * size cap, one file part, SHA-256, server-generated storage key, extension whitelist + content sniffing, orphan deletion) is the SAME
 * code as the mailing attachments ([classifyAttachmentUpload] etc.), not a copy.
 * - **Pending -> sent**: an upload is first PENDING (`message_id` NULL: owned by the uploader, addressed to ONE recipient) and becomes
 *   part of a message only through `IDirectMessageService.sendDirectMessageWithAttachments`.
 * - **Who**: an active member (the rule of every direct-message method), and the recipient must be receivable by the SAME rule as
 *   `sendDirectMessage` ([directMessageRecipientRefusal]); unknown, inactive and own id all answer `RECIPIENT_UNAVAILABLE` (no oracle).
 * - **Download**: only the uploader (while pending) and the sender/recipient of the message. NO role grants access -- there is no
 *   BOARD/ADMIN bypass (a tripwire pins that this file never reads a role). Unknown id, foreign id, malformed id, inactive caller and a
 *   missing file give the SAME 404 (same body, `no-store`); 401 and 429 do not depend on the id either.
 * - **Upload order**: the form field `recipientId` must come BEFORE the file. A gate checks recipient and pending limits before one byte
 *   of the file is written; the authoritative check repeats inside the insert transaction under the uploader's member-row lock.
 * - **CSRF**: upload and delete require a same-origin request; errors are `{"code": ...}` with a FIXED [AttachmentErrorCode].
 * - Logs carry nothing: no file name, no storage key, no path.
 */
@Suppress("LongMethod")
fun Route.registerDirectMessageAttachmentRoutes(
    storageRoot: File,
    uploadLimiter: FederationInboxRateLimiter,
    downloadLimiter: FederationInboxRateLimiter,
    canonicalOrigin: String,
) {
    val origin = runCatching { URI(canonicalOrigin) }.getOrNull()

    post("/api/direct-messages/attachments") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = origin)) {
            call.respondAttachmentError(AttachmentErrorCode.INVALID_ORIGIN)
            return@post
        }
        val current = resolveCurrentMember(call)
        if (!isActiveMember(current)) {
            call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
            return@post
        }
        if (!uploadLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respondAttachmentError(AttachmentErrorCode.RATE_LIMITED)
            return@post
        }

        val attachmentId = Uuid.random()
        val storageKey = "$DM_STORAGE_PREFIX/${current.memberId}/$attachmentId.bin"
        val targetFile = storageRoot.resolve(storageKey)
        // Gate BEFORE the file stream (fail fast, not authoritative); remembers its own reason.
        var gateRefusal: AttachmentErrorCode? = null
        val streamed =
            call.streamSingleTravelExpenseFile(
                targetFile = targetFile,
                maxBytes = AttachmentRules.MAX_FILE_BYTES,
                fallbackName = "anhang",
                // The real classification needs the file NAME (extension), known only afterwards.
                classify = { _, _, _ -> FileClassification.Accepted("application/octet-stream") },
                maxFormFields = 1,
                preFileGate = { fields ->
                    val recipient = fields[RECIPIENT_FIELD]?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
                    gateRefusal =
                        if (recipient == null) {
                            AttachmentErrorCode.NO_FILE
                        } else {
                            transaction { checkPendingUpload(me = current.memberId, recipient = recipient, newSize = 0L, nameKey = null) }
                        }
                    gateRefusal == null
                },
            )
        val stored =
            when (streamed) {
                is StreamedTravelExpenseFile.Stored -> streamed
                else -> {
                    call.respondAttachmentError(gateRefusal ?: attachmentStreamFailureCode(streamed))
                    return@post
                }
            }
        // The gate parsed it already; this cannot fail for a Stored outcome (the gate refuses otherwise).
        val recipientId = Uuid.parse(stored.formFields.getValue(RECIPIENT_FIELD))

        val classification = classifyAttachmentUpload(rawName = stored.rawFileName, file = targetFile)
        if (classification is AttachmentUploadClassification.Refused) {
            targetFile.delete()
            call.respondAttachmentError(classification.code)
            return@post
        }
        val accepted = classification as AttachmentUploadClassification.Ok
        val nameKey = attachmentNameKey(accepted.name)
        val now = DbClock.nowLocalDateTime()

        val refusal: AttachmentErrorCode? =
            try {
                transaction {
                    // The uploader's member row serializes parallel uploads and the uploader's sends (same lock as the send).
                    requireActiveMembership(memberId = current.memberId, forUpdate = true)
                    val check =
                        checkPendingUpload(me = current.memberId, recipient = recipientId, newSize = stored.sizeBytes, nameKey = nameKey)
                    if (check != null) return@transaction check
                    DirectMessageAttachmentStore.insertPendingInTx(
                        attachmentId = attachmentId,
                        uploader = current.memberId,
                        recipient = recipientId,
                        storageKey = storageKey,
                        fileName = accepted.name,
                        nameKey = nameKey,
                        mimeType = accepted.mime,
                        textCharset = accepted.charset,
                        sizeBytes = stored.sizeBytes,
                        sha256 = stored.sha256,
                        createdAt = now,
                    )
                    null
                }
            } catch (_: ForbiddenException) {
                targetFile.delete()
                call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
                return@post
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }
        if (refusal != null) {
            targetFile.delete()
            call.respondAttachmentError(refusal)
            return@post
        }
        call.respond(HttpStatusCode.Created, mapOf("attachmentId" to attachmentId.toString()))
    }

    // The caller's own PENDING uploads for ONE partner, so a rebuilt composer (reload, navigation) shows them again.
    get("/api/direct-messages/attachments/pending") {
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (!isActiveMember(current)) {
            call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
            return@get
        }
        val recipient = call.request.queryParameters[RECIPIENT_FIELD]?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
        if (recipient == null) {
            call.respondAttachmentError(AttachmentErrorCode.NO_FILE)
            return@get
        }
        val items =
            transaction { DirectMessageAttachmentStore.listPendingFor(uploader = current.memberId, recipient = recipient) }
                .map {
                    DirectMessageAttachmentDto(
                        id = it.id.toString(),
                        fileName = it.fileName,
                        mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes,
                    )
                }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(items)
    }

    delete("/api/direct-messages/attachments/{attachmentId}") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = origin)) {
            call.respondAttachmentError(AttachmentErrorCode.INVALID_ORIGIN)
            return@delete
        }
        val current = resolveCurrentMember(call)
        if (!isActiveMember(current)) {
            call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
            return@delete
        }
        val attachmentId = runCatching { Uuid.parse(call.parameters["attachmentId"]!!) }.getOrNull()
        val key = attachmentId?.let { transaction { DirectMessageAttachmentStore.deletePendingInTx(id = it, uploader = current.memberId) } }
        if (key == null) {
            call.respondAttachmentError(AttachmentErrorCode.NOT_FOUND)
            return@delete
        }
        // File removal AFTER the commit.
        DirectMessageAttachmentStore.deleteFiles(root = storageRoot, keys = listOf(key))
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/direct-messages/attachments/{attachmentId}/download") {
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (!downloadLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respondAttachmentError(AttachmentErrorCode.RATE_LIMITED)
            return@get
        }
        val attachmentId = runCatching { Uuid.parse(call.parameters["attachmentId"]!!) }.getOrNull()
        val attachment =
            if (attachmentId == null || !isActiveMember(current)) {
                null
            } else {
                transaction { DirectMessageAttachmentStore.findDownloadableFor(id = attachmentId, viewer = current.memberId) }
            }
        val file = attachment?.let { resolveContainedAttachmentFile(root = storageRoot, storageKey = it.storageKey) }
        if (attachment == null || file == null || !file.isFile) {
            call.respondAttachmentError(AttachmentErrorCode.NOT_FOUND)
            return@get
        }
        call.respondAttachmentDownload(
            file = file,
            fileName = attachment.fileName,
            mimeType = attachment.mimeType,
            textCharset = attachment.textCharset,
        )
    }
}

/** The membership rule of every direct-message method (active organization member), without throwing. */
private fun isActiveMember(current: CurrentMember): Boolean =
    try {
        transaction { requireActiveMembership(memberId = current.memberId) }
        true
    } catch (_: ForbiddenException) {
        false
    }

/**
 * Inside a transaction. The recipient rule is [directMessageRecipientRefusal] (all refusals -> one uniform code); then the limits of
 * the PENDING uploads: per pair [AttachmentRules.MAX_FILES]/[AttachmentRules.MAX_TOTAL_BYTES] and per uploader
 * [DirectMessagePendingLimits]. [newSize]/[nameKey] are checked only when given (the gate does not know them yet). `null` == allowed.
 */
private fun checkPendingUpload(
    me: Uuid,
    recipient: Uuid,
    newSize: Long,
    nameKey: String?,
): AttachmentErrorCode? {
    if (directMessageRecipientRefusal(senderId = me, recipientId = recipient) != null) return AttachmentErrorCode.RECIPIENT_UNAVAILABLE
    val (pairCount, pairBytes) = DirectMessageAttachmentStore.pendingForPair(uploader = me, recipient = recipient)
    if (pairCount >= AttachmentRules.MAX_FILES) return AttachmentErrorCode.TOO_MANY
    if (pairBytes + newSize > AttachmentRules.MAX_TOTAL_BYTES) return AttachmentErrorCode.TOTAL_TOO_LARGE
    val (allCount, allBytes) = DirectMessageAttachmentStore.pendingForUploader(me)
    if (allCount >= DirectMessagePendingLimits.MAX_FILES_PER_UPLOADER ||
        allBytes + newSize > DirectMessagePendingLimits.MAX_BYTES_PER_UPLOADER
    ) {
        return AttachmentErrorCode.PENDING_LIMIT
    }
    val windowStart =
        (ServerClock.nowInstant() - DirectMessagePendingLimits.ROLLING_WINDOW_HOURS.hours).toLocalDateTime(ServerClock.zone)
    val (windowCount, windowBytes) = DirectMessageAttachmentStore.uploadedSince(uploader = me, since = windowStart)
    if (windowCount >= DirectMessagePendingLimits.MAX_FILES_PER_WINDOW ||
        windowBytes + newSize > DirectMessagePendingLimits.MAX_BYTES_PER_WINDOW
    ) {
        return AttachmentErrorCode.PENDING_LIMIT
    }
    if (nameKey != null && DirectMessageAttachmentStore.pendingNameKeyExists(uploader = me, recipient = recipient, nameKey = nameKey)) {
        return AttachmentErrorCode.DUPLICATE_NAME
    }
    return null
}
