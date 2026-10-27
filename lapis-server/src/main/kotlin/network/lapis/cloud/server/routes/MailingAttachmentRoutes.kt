package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.attachments.AttachmentUploadClassification
import network.lapis.cloud.server.attachments.attachmentNameKey
import network.lapis.cloud.server.attachments.attachmentStreamFailureCode
import network.lapis.cloud.server.attachments.classifyAttachmentUpload
import network.lapis.cloud.server.attachments.resolveContainedAttachmentFile
import network.lapis.cloud.server.attachments.respondAttachmentDownload
import network.lapis.cloud.server.attachments.respondAttachmentError
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.newsletter.MailingAttachmentStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachmentErrorCode
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

private val ATTACHMENT_BOARD_ROLES = setOf(AccountRole.BOARD, AccountRole.ADMIN)
private const val MAILING_STORAGE_PREFIX = "mailing"

/**
 * V1.9.232 -- attachments on mailing messages. File bytes travel over these dedicated routes, not Kilua RPC, and reuse the upload
 * hardening of `TravelExpenseFileSupport` (streaming size cap, one file part, SHA-256, server-generated storage key, orphan deletion).
 * - **Who**: BOARD/ADMIN only (the rule of `createDraftMessage`/`sendMailingMessage`); the check comes BEFORE existence and status, so
 *   there is no state oracle. Members never download: recipients get the file as a real MIME attachment of their mail.
 * - **CSRF**: upload and delete require a same-origin request ([isSameOriginRequest]); errors are `{"code": ...}` with a FIXED code
 *   ([AttachmentErrorCode]), never free text.
 * - **Classification**: extension whitelist, then [AttachmentSniffer] on the complete stored file; the MIME type is the server's,
 *   the client `Content-Type` is never read. Every refusal deletes the file.
 * - **Authoritative check under a lock**: the pre-check only fails fast. Existence, `DRAFT`, count, total size and the duplicate name
 *   are re-verified inside the insert transaction under `forUpdate()` on the MESSAGE row -- the row `sendMailingMessage`'s conditional
 *   `DRAFT -> QUEUED` update locks too -- so a concurrent send or parallel uploads cannot slip past.
 * - **Path traversal**: `storage_key` is `mailing/{messageId}/{attachmentId}.bin`, generated here; the name is display metadata only.
 * - Logs carry nothing: no file name, no storage key, no path.
 */
fun Route.registerMailingAttachmentRoutes(
    storageRoot: File,
    rateLimiter: FederationInboxRateLimiter,
    canonicalOrigin: String,
) {
    val origin = runCatching { URI(canonicalOrigin) }.getOrNull()

    post("/api/mailing/messages/{messageId}/attachments") {
        val messageId = runCatching { Uuid.parse(call.parameters["messageId"]!!) }.getOrNull()
        if (messageId == null) {
            call.respondAttachmentError(AttachmentErrorCode.NOT_FOUND)
            return@post
        }
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = origin)) {
            call.respondAttachmentError(AttachmentErrorCode.INVALID_ORIGIN)
            return@post
        }
        val current = resolveCurrentMember(call)
        if (current.role !in ATTACHMENT_BOARD_ROLES) {
            call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
            return@post
        }
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respondAttachmentError(AttachmentErrorCode.RATE_LIMITED)
            return@post
        }
        // Fail-fast pre-check (NOT authoritative, see class KDoc).
        val gate = transaction { checkUploadAllowed(messageId = messageId, newSize = 0L, nameKey = null, lock = false) }
        if (gate != null) {
            call.respondAttachmentError(gate)
            return@post
        }

        val attachmentId = Uuid.random()
        val storageKey = "$MAILING_STORAGE_PREFIX/$messageId/$attachmentId.bin"
        val targetFile = storageRoot.resolve(storageKey)
        val stored =
            when (
                val streamed =
                    call.streamSingleTravelExpenseFile(
                        targetFile = targetFile,
                        maxBytes = AttachmentRules.MAX_FILE_BYTES,
                        fallbackName = "anhang",
                        // The real classification needs the file NAME (extension), known only afterwards.
                        classify = { _, _, _ -> FileClassification.Accepted("application/octet-stream") },
                    )
            ) {
                is StreamedTravelExpenseFile.Stored -> streamed
                else -> {
                    call.respondAttachmentError(attachmentStreamFailureCode(streamed))
                    return@post
                }
            }

        val refusal = classifyAttachmentUpload(rawName = stored.rawFileName, file = targetFile)
        if (refusal is AttachmentUploadClassification.Refused) {
            targetFile.delete()
            call.respondAttachmentError(refusal.code)
            return@post
        }
        val accepted = refusal as AttachmentUploadClassification.Ok
        val nameKey = attachmentNameKey(accepted.name)
        val now = DbClock.nowLocalDateTime()

        val outcome: AttachmentErrorCode? =
            try {
                transaction {
                    val check = checkUploadAllowed(messageId = messageId, newSize = stored.sizeBytes, nameKey = nameKey, lock = true)
                    if (check != null) return@transaction check
                    MailingAttachmentStore.insertInTx(
                        attachmentId = attachmentId,
                        messageId = messageId,
                        storageKey = storageKey,
                        fileName = accepted.name,
                        nameKey = nameKey,
                        mimeType = accepted.mime,
                        textCharset = accepted.charset,
                        sizeBytes = stored.sizeBytes,
                        sha256 = stored.sha256,
                        uploadedBy = current.memberId,
                        uploadedAt = now,
                    )
                    null
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }
        if (outcome != null) {
            targetFile.delete()
            call.respondAttachmentError(outcome)
            return@post
        }
        call.respond(HttpStatusCode.Created, mapOf("attachmentId" to attachmentId.toString()))
    }

    delete("/api/mailing/attachments/{attachmentId}") {
        val attachmentId = runCatching { Uuid.parse(call.parameters["attachmentId"]!!) }.getOrNull()
        if (attachmentId == null) {
            call.respondAttachmentError(AttachmentErrorCode.NOT_FOUND)
            return@delete
        }
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = origin)) {
            call.respondAttachmentError(AttachmentErrorCode.INVALID_ORIGIN)
            return@delete
        }
        val current = resolveCurrentMember(call)
        if (current.role !in ATTACHMENT_BOARD_ROLES) {
            call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
            return@delete
        }
        val outcome =
            transaction {
                val attachment =
                    MailingAttachmentStore.find(attachmentId)
                        ?: return@transaction MailingAttachmentDeleteOutcome.Refused(AttachmentErrorCode.NOT_FOUND)
                // forUpdate(): serializes against sendMailingMessage's conditional DRAFT -> QUEUED update on the same message row.
                val message =
                    MailingMessageTable
                        .selectAll()
                        .where { MailingMessageTable.id eq attachment.mailingMessageId }
                        .forUpdate()
                        .singleOrNull()
                        ?: return@transaction MailingAttachmentDeleteOutcome.Refused(AttachmentErrorCode.NOT_FOUND)
                if (message[MailingMessageTable.status] != MailingMessageStatus.DRAFT) {
                    return@transaction MailingAttachmentDeleteOutcome.Refused(AttachmentErrorCode.NOT_DRAFT)
                }
                val key = MailingAttachmentStore.deleteOneInTx(attachmentId)
                if (key ==
                    null
                ) {
                    MailingAttachmentDeleteOutcome.Refused(AttachmentErrorCode.NOT_FOUND)
                } else {
                    MailingAttachmentDeleteOutcome.Deleted(key)
                }
            }
        when (outcome) {
            is MailingAttachmentDeleteOutcome.Refused -> call.respondAttachmentError(outcome.code)
            is MailingAttachmentDeleteOutcome.Deleted -> {
                // File removal AFTER the commit.
                MailingAttachmentStore.deleteFilesAfterCommit(root = storageRoot, keys = listOf(outcome.storageKey))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    get("/api/mailing/attachments/{attachmentId}/download") {
        val attachmentId = runCatching { Uuid.parse(call.parameters["attachmentId"]!!) }.getOrNull()
        if (attachmentId == null) {
            call.respondAttachmentError(AttachmentErrorCode.NOT_FOUND)
            return@get
        }
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (current.role !in ATTACHMENT_BOARD_ROLES) {
            call.respondAttachmentError(AttachmentErrorCode.FORBIDDEN)
            return@get
        }
        val attachment = transaction { MailingAttachmentStore.find(attachmentId) }
        if (attachment == null || attachment.fileDeletedAt != null) {
            call.respondAttachmentError(AttachmentErrorCode.NOT_FOUND)
            return@get
        }
        val file = resolveContainedAttachmentFile(root = storageRoot, storageKey = attachment.storageKey)
        if (file == null || !file.isFile) {
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

/**
 * Must run inside a transaction. With [lock] the message row is read `forUpdate()` (authoritative). [newSize]/[nameKey] are checked only
 * when given (the pre-check does not know them yet). Returns the refusal code, or `null` if the upload may proceed.
 */
private fun checkUploadAllowed(
    messageId: Uuid,
    newSize: Long,
    nameKey: String?,
    lock: Boolean,
): AttachmentErrorCode? {
    val query = MailingMessageTable.selectAll().where { MailingMessageTable.id eq messageId }
    val message = (if (lock) query.forUpdate() else query).singleOrNull() ?: return AttachmentErrorCode.NOT_FOUND
    if (message[MailingMessageTable.status] != MailingMessageStatus.DRAFT) return AttachmentErrorCode.NOT_DRAFT
    val (count, total) = MailingAttachmentStore.countAndTotalBytes(messageId)
    if (count >= AttachmentRules.MAX_FILES) return AttachmentErrorCode.TOO_MANY
    if (total + newSize > AttachmentRules.MAX_TOTAL_BYTES) return AttachmentErrorCode.TOTAL_TOO_LARGE
    if (nameKey != null &&
        MailingAttachmentStore.nameKeyExists(messageId = messageId, nameKey = nameKey)
    ) {
        return AttachmentErrorCode.DUPLICATE_NAME
    }
    return null
}

private sealed interface MailingAttachmentDeleteOutcome {
    data class Refused(
        val code: AttachmentErrorCode,
    ) : MailingAttachmentDeleteOutcome

    data class Deleted(
        val storageKey: String,
    ) : MailingAttachmentDeleteOutcome
}
