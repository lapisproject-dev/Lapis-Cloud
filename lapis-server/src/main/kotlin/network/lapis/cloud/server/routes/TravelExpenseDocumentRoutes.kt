package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mcp.transport.receiveCappedTextOrNull
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import network.lapis.cloud.shared.domain.TravelExpenseDocumentSnapshot
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val DOCUMENT_STORAGE_PREFIX = "$TRAVEL_EXPENSE_STORAGE_PREFIX/documents"
private const val MAX_RENAME_BODY_BYTES = 4 * 1024
private const val UPLOAD_RATE_LIMIT_TEXT = "Zu viele Anfragen -- bitte spaeter erneut versuchen."

@Serializable
private class RenameBody(
    val title: String,
)

/**
 * Welle V1.9.199 -- organisation-wide travel-expense documents (Reisekostenordnung, Beschluesse, Formulare) that EVERY signed-in member can
 * download; only BOARD/ADMIN upload, rename and delete. The bytes travel over these dedicated routes, not Kilua RPC, and reuse the upload
 * hardening of `TravelExpenseFileSupport` (streaming size cap, server-generated storage key, sanitized display name, nosniff + attachment
 * headers) with ONE addition: the content check is the [TravelExpenseDocumentSniffer] (PDF, XLSX, XLS; encrypted and macro-enabled files are
 * refused with a fixed token body) instead of the receipt magic-byte allowlist.
 * - **Storage**: on disk under `travel-expenses/documents/{id}.bin` (like V113), NOT in a BYTEA column -- the backup export/restore and the
 *   blob handling all hang on the `storage_key` column. `storage_key` is server-generated, the client file name is display metadata only.
 * - **Authoritative quota under a lock**: the pre-check only fails fast; the [TravelExpenseDocumentRules.MAX_DOCUMENTS] count is repeated
 *   inside the insert transaction under `forUpdate()` on the singleton `organization_settings` row, so parallel uploads cannot slip past.
 * - **Order**: authentication (401) -> role BOARD/ADMIN (403) -> rate limit (429) -> quota fail-fast (409) -> streaming (413/415/400) ->
 *   authoritative insert + audit entry. The file is deleted on EVERY refusal and on any exception.
 * - **Audit**: `CREATE` / `UPDATE` / `VOID` (`after = null`) with [TravelExpenseDocumentSnapshot] (title, MIME type, size) -- never content.
 * - **Download**: any authenticated member; the files are deliberately not confidential. An unknown id or a missing file is 404.
 * - Shares the receipt upload's per-member rate limiter instance (one budget for all travel-expense uploads).
 * - Logs carry ids only, never titles or file names.
 */
fun Route.registerTravelExpenseDocumentRoutes(
    storageRoot: File,
    rateLimiter: FederationInboxRateLimiter,
) {
    post("/api/travel-expenses/documents") {
        val current = resolveCurrentMember(call)
        if (!current.isPrivileged) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, UPLOAD_RATE_LIMIT_TEXT)
            return@post
        }
        // Fail-fast pre-check (NOT authoritative, see class KDoc).
        if (transaction { countDocuments() } >= TravelExpenseDocumentRules.MAX_DOCUMENTS) {
            call.respond(HttpStatusCode.Conflict, "limit")
            return@post
        }

        val documentId = Uuid.random()
        val storageKey = "$DOCUMENT_STORAGE_PREFIX/$documentId.bin"
        val targetFile = storageRoot.resolve(storageKey)
        val stored =
            when (
                val streamed =
                    call.streamSingleTravelExpenseFile(
                        targetFile = targetFile,
                        maxBytes = TravelExpenseDocumentRules.MAX_BYTES,
                        fallbackName = "unterlage",
                        classify = TravelExpenseDocumentSniffer::classify,
                        maxFormFields = 1,
                    )
            ) {
                is StreamedTravelExpenseFile.Stored -> streamed
                else -> {
                    call.respondStreamFailure(outcome = streamed, maxBytes = TravelExpenseDocumentRules.MAX_BYTES)
                    return@post
                }
            }
        if (stored.formFields.keys.any { it != "title" }) {
            targetFile.delete()
            call.respond(HttpStatusCode.BadRequest, "Invalid form fields")
            return@post
        }
        val filename = filenameWithExtension(name = stored.sanitizedFileName, mime = stored.mimeType)
        val title =
            TravelExpenseDocumentRules
                .normalizeTitle(stored.formFields["title"].orEmpty())
                .ifBlank { TravelExpenseDocumentRules.titleFromFilename(filename) }
                .ifBlank { "Unterlage" }
        val now = DbClock.nowLocalDateTime()
        val snapshot = TravelExpenseDocumentSnapshot(title = title, mimeType = stored.mimeType, sizeBytes = stored.sizeBytes)

        val accepted =
            try {
                transaction {
                    // Serializes parallel uploads: the singleton settings row is the lock for the quota.
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .forUpdate()
                        .singleOrNull()
                    if (countDocuments() >= TravelExpenseDocumentRules.MAX_DOCUMENTS) return@transaction false
                    TravelExpenseDocumentTable.insert {
                        it[id] = documentId
                        it[TravelExpenseDocumentTable.title] = title
                        it[TravelExpenseDocumentTable.storageKey] = storageKey
                        it[originalFilename] = filename
                        it[mimeType] = stored.mimeType
                        it[sizeBytes] = stored.sizeBytes
                        it[sha256] = stored.sha256
                        it[uploadedBy] = current.memberId
                        it[uploadedAt] = now
                    }
                    record(current = current, id = documentId, action = AuditAction.CREATE, before = null, after = snapshot)
                    true
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }
        if (!accepted) {
            targetFile.delete()
            call.respond(HttpStatusCode.Conflict, "limit")
            return@post
        }
        logger.info { "travel expense document $documentId uploaded by ${current.memberId}" }
        call.respond(HttpStatusCode.Created, mapOf("documentId" to documentId.toString()))
    }

    patch("/api/travel-expenses/documents/{id}") {
        val documentId = call.documentIdOrNull()
        if (documentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid id")
            return@patch
        }
        val current = resolveCurrentMember(call)
        if (!current.isPrivileged) {
            call.respond(HttpStatusCode.Forbidden)
            return@patch
        }
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, UPLOAD_RATE_LIMIT_TEXT)
            return@patch
        }
        // A JSON content type is a non-simple one: a cross-site form cannot send it, so this write needs no extra token.
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return@patch
        }
        val text = call.receiveCappedTextOrNull(MAX_RENAME_BODY_BYTES)
        if (text == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@patch
        }
        val title =
            runCatching { Json.decodeFromString(RenameBody.serializer(), text) }
                .getOrNull()
                ?.let { TravelExpenseDocumentRules.normalizeTitle(it.title) }
        if (title.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Invalid title")
            return@patch
        }
        val found =
            transaction {
                val row = findDocumentForUpdate(documentId) ?: return@transaction false
                val before = row.toSnapshot()
                if (before.title != title) {
                    TravelExpenseDocumentTable.update({ TravelExpenseDocumentTable.id eq documentId }) {
                        it[TravelExpenseDocumentTable.title] = title
                    }
                    record(
                        current = current,
                        id = documentId,
                        action = AuditAction.UPDATE,
                        before = before,
                        after = before.copy(title = title),
                    )
                }
                true
            }
        if (!found) {
            call.respond(HttpStatusCode.NotFound)
            return@patch
        }
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/api/travel-expenses/documents/{id}") {
        val documentId = call.documentIdOrNull()
        if (documentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid id")
            return@delete
        }
        val current = resolveCurrentMember(call)
        if (!current.isPrivileged) {
            call.respond(HttpStatusCode.Forbidden)
            return@delete
        }
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, UPLOAD_RATE_LIMIT_TEXT)
            return@delete
        }
        val storageKey =
            transaction {
                val row = findDocumentForUpdate(documentId) ?: return@transaction null
                val before = row.toSnapshot()
                TravelExpenseDocumentTable.deleteWhere { TravelExpenseDocumentTable.id eq documentId }
                record(current = current, id = documentId, action = AuditAction.VOID, before = before, after = null)
                row[TravelExpenseDocumentTable.storageKey]
            }
        if (storageKey == null) {
            call.respond(HttpStatusCode.NotFound)
            return@delete
        }
        // File removal AFTER the commit (same pattern as receipts/attachments).
        storageRoot.resolve(storageKey).delete()
        logger.info { "travel expense document $documentId deleted by ${current.memberId}" }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/travel-expenses/documents/{id}/download") {
        val documentId = call.documentIdOrNull()
        if (documentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid id")
            return@get
        }
        // Organisation members (status ACTIVE) and privileged staff only: authenticated alone also covers GUEST/FRIEND/DONOR/APPLICATION/
        // WITHDRAWN. An unauthenticated call is mapped to 401 by StatusPages.
        val viewer = resolveCurrentMember(call)
        if (viewer.status !in MemberStatusSets.ORGANIZATION_MEMBER && !viewer.isPrivileged) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }
        val row =
            transaction {
                TravelExpenseDocumentTable.selectAll().where { TravelExpenseDocumentTable.id eq documentId }.singleOrNull()
            }
        if (row == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val file = storageRoot.resolve(row[TravelExpenseDocumentTable.storageKey])
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Stored file missing")
            return@get
        }
        call.respondTravelExpenseFile(
            file = file,
            originalFilename = row[TravelExpenseDocumentTable.originalFilename],
            mimeType = row[TravelExpenseDocumentTable.mimeType],
        )
    }
}

private fun ApplicationCall.documentIdOrNull(): Uuid? = parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }

/** Must run inside a transaction. */
private fun countDocuments(): Long = TravelExpenseDocumentTable.selectAll().count()

/** Must run inside a transaction. */
private fun findDocumentForUpdate(documentId: Uuid): ResultRow? =
    TravelExpenseDocumentTable
        .selectAll()
        .where { TravelExpenseDocumentTable.id eq documentId }
        .forUpdate()
        .singleOrNull()

private fun ResultRow.toSnapshot(): TravelExpenseDocumentSnapshot =
    TravelExpenseDocumentSnapshot(
        title = this[TravelExpenseDocumentTable.title],
        mimeType = this[TravelExpenseDocumentTable.mimeType],
        sizeBytes = this[TravelExpenseDocumentTable.sizeBytes],
    )

/** Must run inside a transaction; the audit chain row is the LAST lock taken. */
private fun record(
    current: CurrentMember,
    id: Uuid,
    action: AuditAction,
    before: TravelExpenseDocumentSnapshot?,
    after: TravelExpenseDocumentSnapshot?,
) {
    AuditLogRecorder.record(
        actorMemberId = current.memberId,
        actorRole = current.role,
        entityType = AuditEntityType.TRAVEL_EXPENSE_DOCUMENT,
        entityId = id,
        action = action,
        before = before?.let { Json.encodeToString(TravelExpenseDocumentSnapshot.serializer(), it) },
        after = after?.let { Json.encodeToString(TravelExpenseDocumentSnapshot.serializer(), it) },
    )
}
