package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * Welle V1.9.198 -- report-level attachments for travel-expense reports (an invitation, an
 * itinerary, ...). File bytes travel over these dedicated routes, not Kilua RPC, and share ALL
 * upload hardening with the receipt routes through `TravelExpenseFileSupport` (magic-byte MIME
 * allowlist, streaming size cap, server-generated storage key, sanitized display name, nosniff +
 * attachment download headers).
 * - **Path traversal**: `storageKey` is always server-generated
 *   (`travel-expenses/{reportId}/attachments/{attachmentId}.bin`); the client file name is display
 *   metadata only.
 * - **Check order differs from the receipt route on purpose**: the self-service (permission) check
 *   comes BEFORE the status check, so a foreign member can never probe the state of someone
 *   else's report (403 instead of 409).
 * - **Authoritative check under a lock**: the pre-check transaction only fails fast. Existence,
 *   permission, status (DRAFT only) and the [TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT]
 *   quota are re-verified inside the insert transaction under `forUpdate()` on the REPORT row --
 *   the row `submitReport`/`withdrawReport`/`decideReport` lock too -- so neither a concurrent
 *   submit nor parallel uploads can slip past.
 * - **Immutable once submitted**: delete is DRAFT-only, there is no replace route.
 * - **Download**: subject/requester always, BOARD/ADMIN only after submission, never TREASURER
 *   (same rule as receipts, one function: [canDownloadTravelExpenseFile]).
 * - Shares the receipt upload's per-member rate limiter instance (one budget for both).
 * - Logs carry ids only, never file names or paths. No audit row (draft mutation, like receipts).
 */
fun Route.registerTravelExpenseAttachmentRoutes(
    storageRoot: File,
    rateLimiter: FederationInboxRateLimiter,
) {
    post("/api/travel-expenses/reports/{reportId}/attachments") {
        val reportId = runCatching { Uuid.parse(call.parameters["reportId"]!!) }.getOrNull()
        if (reportId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid reportId")
            return@post
        }
        val current = resolveCurrentMember(call)
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@post
        }

        // Fail-fast pre-check (NOT authoritative, see class KDoc).
        val gate = transaction { checkUploadAllowed(current = current, reportId = reportId, lock = false) }
        if (gate != AttachmentUploadCheck.Allowed) {
            respondUploadRefusal(call = call, check = gate)
            return@post
        }

        val attachmentId = Uuid.random()
        val storageKey = "$TRAVEL_EXPENSE_STORAGE_PREFIX/$reportId/attachments/$attachmentId.bin"
        val targetFile = storageRoot.resolve(storageKey)

        val stored =
            when (
                val streamed =
                    call.streamSingleTravelExpenseFile(
                        targetFile = targetFile,
                        maxBytes = TravelExpenseAmountRules.MAX_UPLOAD_BYTES,
                        fallbackName = "anlage",
                    )
            ) {
                is StreamedTravelExpenseFile.Stored -> streamed
                else -> {
                    call.respondStreamFailure(outcome = streamed)
                    return@post
                }
            }
        val now = DbClock.nowLocalDateTime()

        val outcome =
            try {
                transaction {
                    val check = checkUploadAllowed(current = current, reportId = reportId, lock = true)
                    if (check != AttachmentUploadCheck.Allowed) return@transaction check
                    TravelExpenseAttachmentTable.insert {
                        it[id] = attachmentId
                        it[TravelExpenseAttachmentTable.reportId] = reportId
                        it[TravelExpenseAttachmentTable.storageKey] = storageKey
                        it[originalFilename] = stored.sanitizedFileName
                        it[mimeType] = stored.mimeType
                        it[sizeBytes] = stored.sizeBytes
                        it[sha256] = stored.sha256
                        it[uploadedBy] = current.memberId
                        it[uploadedAt] = now
                    }
                    AttachmentUploadCheck.Allowed
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }
        if (outcome != AttachmentUploadCheck.Allowed) {
            targetFile.delete()
            respondUploadRefusal(call = call, check = outcome)
            return@post
        }
        call.respond(HttpStatusCode.Created, mapOf("attachmentId" to attachmentId.toString()))
    }

    delete("/api/travel-expenses/attachments/{attachmentId}") {
        val attachmentId = runCatching { Uuid.parse(call.parameters["attachmentId"]!!) }.getOrNull()
        if (attachmentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid attachmentId")
            return@delete
        }
        val current = resolveCurrentMember(call)
        val outcome =
            transaction {
                val attachmentRow =
                    TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq attachmentId }.singleOrNull()
                        ?: return@transaction AttachmentDeleteOutcome.NotFound
                // forUpdate(): serializes against submitReport/withdrawReport on the same report row.
                val reportRow =
                    TravelExpenseReportTable
                        .selectAll()
                        .where { TravelExpenseReportTable.id eq attachmentRow[TravelExpenseAttachmentTable.reportId] }
                        .forUpdate()
                        .singleOrNull()
                        ?: return@transaction AttachmentDeleteOutcome.NotFound
                if (!isTravelExpenseSelfService(
                        current = current,
                        subjectId = reportRow[TravelExpenseReportTable.subjectMemberId],
                        requestedById = reportRow[TravelExpenseReportTable.requestedBy],
                    )
                ) {
                    return@transaction AttachmentDeleteOutcome.Forbidden
                }
                if (reportRow[TravelExpenseReportTable.status] !in TravelExpenseReportStatusSets.EDITABLE) {
                    return@transaction AttachmentDeleteOutcome.NotEditable
                }
                val storageKey = attachmentRow[TravelExpenseAttachmentTable.storageKey]
                TravelExpenseAttachmentTable.deleteWhere { TravelExpenseAttachmentTable.id eq attachmentId }
                AttachmentDeleteOutcome.Deleted(storageKey)
            }
        when (outcome) {
            AttachmentDeleteOutcome.NotFound -> call.respond(HttpStatusCode.NotFound)
            AttachmentDeleteOutcome.Forbidden -> call.respond(HttpStatusCode.Forbidden)
            AttachmentDeleteOutcome.NotEditable -> call.respond(HttpStatusCode.Conflict, "Report is not editable")
            is AttachmentDeleteOutcome.Deleted -> {
                // File removal AFTER the commit (same pattern as receipts).
                storageRoot.resolve(outcome.storageKey).delete()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    get("/api/travel-expenses/attachments/{attachmentId}/download") {
        val attachmentId = runCatching { Uuid.parse(call.parameters["attachmentId"]!!) }.getOrNull()
        if (attachmentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid attachmentId")
            return@get
        }
        val current =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        val row =
            transaction {
                val attachmentRow =
                    TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq attachmentId }.singleOrNull()
                        ?: return@transaction null
                val reportRow =
                    TravelExpenseReportTable
                        .selectAll()
                        .where { TravelExpenseReportTable.id eq attachmentRow[TravelExpenseAttachmentTable.reportId] }
                        .singleOrNull()
                        ?: return@transaction null
                AttachmentDownloadRow(
                    storageKey = attachmentRow[TravelExpenseAttachmentTable.storageKey],
                    originalFilename = attachmentRow[TravelExpenseAttachmentTable.originalFilename],
                    mimeType = attachmentRow[TravelExpenseAttachmentTable.mimeType],
                    subjectId = reportRow[TravelExpenseReportTable.subjectMemberId],
                    requestedById = reportRow[TravelExpenseReportTable.requestedBy],
                    status = reportRow[TravelExpenseReportTable.status],
                )
            }
        if (row == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        if (!canDownloadTravelExpenseFile(
                current = current,
                subjectId = row.subjectId,
                requestedById = row.requestedById,
                status = row.status,
            )
        ) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }
        val file = storageRoot.resolve(row.storageKey)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Stored file missing")
            return@get
        }
        call.respondTravelExpenseFile(file = file, originalFilename = row.originalFilename, mimeType = row.mimeType)
    }
}

/** Must run inside a transaction. With [lock] the report row is read `forUpdate()` (authoritative check). */
private fun checkUploadAllowed(
    current: CurrentMember,
    reportId: Uuid,
    lock: Boolean,
): AttachmentUploadCheck {
    val query = TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }
    val reportRow = (if (lock) query.forUpdate() else query).singleOrNull() ?: return AttachmentUploadCheck.NotFound
    // Permission BEFORE status: no state probing of foreign reports.
    if (!isTravelExpenseSelfService(
            current = current,
            subjectId = reportRow[TravelExpenseReportTable.subjectMemberId],
            requestedById = reportRow[TravelExpenseReportTable.requestedBy],
        )
    ) {
        return AttachmentUploadCheck.Forbidden
    }
    if (reportRow[TravelExpenseReportTable.status] !in TravelExpenseReportStatusSets.EDITABLE) {
        return AttachmentUploadCheck.NotEditable
    }
    val count = TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.reportId eq reportId }.count()
    if (count >= TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT) return AttachmentUploadCheck.TooMany
    return AttachmentUploadCheck.Allowed
}

private suspend fun respondUploadRefusal(
    call: io.ktor.server.application.ApplicationCall,
    check: AttachmentUploadCheck,
) {
    when (check) {
        AttachmentUploadCheck.NotFound -> call.respond(HttpStatusCode.NotFound)
        AttachmentUploadCheck.Forbidden -> call.respond(HttpStatusCode.Forbidden)
        AttachmentUploadCheck.NotEditable ->
            call.respond(HttpStatusCode.Conflict, "Report is not editable (only DRAFT can receive attachments)")
        AttachmentUploadCheck.TooMany ->
            call.respond(
                HttpStatusCode.Conflict,
                "This report already has ${TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT} attachments",
            )
        AttachmentUploadCheck.Allowed -> error("not a refusal")
    }
}

private enum class AttachmentUploadCheck { Allowed, NotFound, Forbidden, NotEditable, TooMany }

private sealed interface AttachmentDeleteOutcome {
    data object NotFound : AttachmentDeleteOutcome

    data object Forbidden : AttachmentDeleteOutcome

    data object NotEditable : AttachmentDeleteOutcome

    data class Deleted(
        val storageKey: String,
    ) : AttachmentDeleteOutcome
}

private data class AttachmentDownloadRow(
    val storageKey: String,
    val originalFilename: String,
    val mimeType: String,
    val subjectId: Uuid,
    val requestedById: Uuid,
    val status: network.lapis.cloud.shared.domain.TravelExpenseReportStatus,
)
