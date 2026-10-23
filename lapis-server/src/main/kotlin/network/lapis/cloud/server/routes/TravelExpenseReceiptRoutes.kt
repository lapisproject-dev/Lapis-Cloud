package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * V1.9.198: the streaming (size cap, sniffing, hashing, cleanup on failure), the download headers and the download rule moved
 * unchanged into `TravelExpenseFileSupport.kt`, shared with `TravelExpenseAttachmentRoutes`; the notes below describe that shared behaviour.
 *
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- file bytes travel
 * over these dedicated routes, not Kilua RPC (same reasoning
 * [network.lapis.cloud.server.routes.registerDocumentRoutes] KDoc gives). Security checklist:
 * - **Path traversal**: `storageKey` is ALWAYS server-generated
 *   (`travel-expenses/{reportId}/{receiptId}.bin`), never derived from the client-supplied file
 *   name -- the file name is stored purely as display metadata for `Content-Disposition`.
 * - **DoS cap**: [TravelExpenseAmountRules.MAX_UPLOAD_BYTES] enforced WHILE streaming, before the whole file is buffered.
 * - **MIME allowlist, derived server-side from magic bytes**: the client-declared `Content-Type`
 *   is DISCARDED entirely, not merely validated -- a PDF-declared SVG is structurally impossible.
 *   `application/pdf`/`image/jpeg`/`image/png` only, deliberately NO `image/svg+xml` (XSS vector).
 * - **Filename sanitization**: `original_filename` is stripped of any path separator and control
 *   characters before it ever reaches the database -- pure display metadata, never used to build
 *   a filesystem path (double safeguard on top of the server-generated `storageKey`).
 * - **IDOR on download**: enforced HERE, not only in the UI -- subject/requester/BOARD/ADMIN only,
 *   deliberately NOT TREASURER (same narrower tier as the decision itself).
 * - **Header injection**: the `Content-Disposition` filename is carried via RFC 5987
 *   `filename*=UTF-8''...` percent-encoding (plus an ASCII `filename=` fallback), and
 *   `X-Content-Type-Options: nosniff` is always sent -- neither of which
 *   [network.lapis.cloud.server.routes.DocumentRoutes] does today (a pre-existing gap in that
 *   route, out of scope for this wave, flagged as a follow-up rather than fixed here).
 * - **TOCTOU across the upload's slow file-streaming phase** (review MAJOR fix): the cheap
 *   pre-check ("gate") transaction only rejects obviously-invalid requests early, before a single
 *   byte is streamed -- it is deliberately NOT the authoritative check. Status/permission/quota
 *   are re-verified from scratch inside the SAME transaction that performs the DB insert, under
 *   `forUpdate()` on the report row -- the identical row [network.lapis.cloud.server.rpc
 *   .TravelExpenseService.submitReport] locks with `forUpdate()` too, so the two paths serialize
 *   against each other and against concurrent uploads on the same report, closing both the
 *   "receipt lands on an already-submitted report" race and the "two parallel uploads both pass
 *   the same MAX_RECEIPTS_PER_LINE/_REPORT count check" quota-bypass race. See that re-check's own
 *   comment below for why the gate is kept anyway (fail fast, no wasted bandwidth/disk for a
 *   request that is invalid from the start).
 * - **Multipart with more than one file part** (review MAJOR fix): only the FIRST
 *   `PartData.FileItem` is streamed to disk/hashed/sniffed; every subsequent file part in the same
 *   request is drained-and-discarded via `part.release()` (same pattern already used for non-file
 *   parts below) without ever being written or hashed, and the whole request is rejected with 400
 *   -- a multi-file-part body must never silently blend one part's magic bytes with another
 *   part's on-disk bytes into checksum/size/MIME metadata that describes neither.
 * - **TOCTOU on receipt deletion vs. `submitReport`** (review MINOR fix): the DELETE route's
 *   report-row read is under `forUpdate()` too, for the same reason as the upload path above --
 *   without it, a plain `SELECT` does not serialize against `submitReport`'s `forUpdate()` lock on
 *   the identical row, so a delete racing a concurrent submit could remove the last receipt of a
 *   RECEIPTED line just as it is being submitted, leaving a submitted report with an unbacked
 *   line.
 */
fun Route.registerTravelExpenseReceiptRoutes(
    storageRoot: File,
    rateLimiter: FederationInboxRateLimiter,
) {
    post("/api/travel-expenses/lines/{lineId}/receipts") {
        val lineId = runCatching { Uuid.parse(call.parameters["lineId"]!!) }.getOrNull()
        if (lineId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid lineId")
            return@post
        }
        val current = resolveCurrentMember(call)

        // Security-Audit fix (2026-09-12, MAJOR "kein Byte-Kontingent und kein Rate-Limit") --
        // every OTHER write-capable route in this codebase gates behind a
        // [FederationInboxRateLimiter], this one did not. Keyed by member, same convention
        // `DunningRoutes`' `previewRateLimiter` uses (`"member:${current.memberId}"`) -- an
        // authenticated route, unlike the public/unauthenticated callers of this class that key by
        // remote host.
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@post
        }

        val gate =
            transaction {
                val lineRow = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq lineId }.singleOrNull()
                if (lineRow == null) return@transaction ReceiptUploadGate.NotFound
                // V1.9.122: day-allowance lines are pure calculation rows and carry no receipt.
                if (lineRow[TravelExpenseLineTable.kind] == TravelExpenseLineKind.DAY_ALLOWANCE) {
                    return@transaction ReceiptUploadGate.DayLine
                }
                val reportId = lineRow[TravelExpenseLineTable.reportId]
                val reportRow = TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.singleOrNull()
                if (reportRow == null) return@transaction ReceiptUploadGate.NotFound
                if (reportRow[TravelExpenseReportTable.status] !in TravelExpenseReportStatusSets.EDITABLE) {
                    return@transaction ReceiptUploadGate.NotEditable
                }
                val subjectId = reportRow[TravelExpenseReportTable.subjectMemberId]
                val requestedById = reportRow[TravelExpenseReportTable.requestedBy]
                if (current.memberId != subjectId && current.memberId != requestedById) return@transaction ReceiptUploadGate.Forbidden
                val lineReceiptCount = TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq lineId }.count()
                if (lineReceiptCount >= TravelExpenseAmountRules.MAX_RECEIPTS_PER_LINE) return@transaction ReceiptUploadGate.TooManyOnLine
                val reportReceiptCount =
                    (TravelExpenseReceiptTable innerJoin TravelExpenseLineTable)
                        .selectAll()
                        .where { TravelExpenseLineTable.reportId eq reportId }
                        .count()
                if (reportReceiptCount >=
                    TravelExpenseAmountRules.MAX_RECEIPTS_PER_REPORT
                ) {
                    return@transaction ReceiptUploadGate.TooManyOnReport
                }
                ReceiptUploadGate.Allowed(reportId)
            }
        when (gate) {
            ReceiptUploadGate.NotFound -> {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            ReceiptUploadGate.DayLine -> {
                call.respond(HttpStatusCode.BadRequest, "Day-allowance lines cannot carry receipts")
                return@post
            }
            ReceiptUploadGate.NotEditable -> {
                call.respond(HttpStatusCode.Conflict, "Report is not editable (only DRAFT can receive receipts)")
                return@post
            }
            ReceiptUploadGate.Forbidden -> {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            ReceiptUploadGate.TooManyOnLine -> {
                call.respond(HttpStatusCode.Conflict, "This line already has ${TravelExpenseAmountRules.MAX_RECEIPTS_PER_LINE} receipts")
                return@post
            }
            ReceiptUploadGate.TooManyOnReport -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    "This report already has ${TravelExpenseAmountRules.MAX_RECEIPTS_PER_REPORT} receipts",
                )
                return@post
            }
            is ReceiptUploadGate.Allowed -> Unit
        }
        val reportId = gate.reportId

        val receiptId = Uuid.random()
        val storageKey = "$TRAVEL_EXPENSE_STORAGE_PREFIX/$reportId/$receiptId.bin"
        val targetFile = storageRoot.resolve(storageKey)

        // Shared streaming/sniffing/hashing (see TravelExpenseFileSupport): deletes targetFile on
        // every failure outcome and on any exception, the client-declared Content-Type is discarded.
        val stored =
            when (
                val streamed =
                    call.streamSingleTravelExpenseFile(
                        targetFile = targetFile,
                        maxBytes = TravelExpenseAmountRules.MAX_UPLOAD_BYTES,
                        fallbackName = "beleg",
                    )
            ) {
                is StreamedTravelExpenseFile.Stored -> streamed
                else -> {
                    call.respondStreamFailure(outcome = streamed)
                    return@post
                }
            }
        val sanitizedFileName = stored.sanitizedFileName
        val checksum = stored.sha256
        val totalBytes = stored.sizeBytes
        val mimeType = stored.mimeType
        val now = DbClock.nowLocalDateTime()

        // Review MAJOR fix: re-verify everything the gate already checked, from scratch, inside
        // the SAME transaction as the insert -- the gate's own transaction committed (and released
        // its locks) before the file streamed, so it cannot be relied on as the authoritative
        // check. `forUpdate()` on the report row serializes this against `submitReport` (which
        // locks the identical row) and against any other concurrent receipt upload on the same
        // report, closing the TOCTOU window described in this file's class KDoc.
        //
        // Security-Audit fix (2026-09-12, MAJOR "Orphaned Beleg-Dateien"): this transaction can
        // also fail with an exception StatusPages never sees mapped (ExposedSQLException, a DB
        // outage, ...) -- the streaming phase's own try/catch above cannot cover this, since the
        // file write already finished successfully by the time this block runs. Same cleanup
        // discipline applies here: delete the just-written file on ANY failure, then rethrow
        // unchanged.
        val insertOutcome =
            try {
                transaction {
                    val reportRow =
                        TravelExpenseReportTable
                            .selectAll()
                            .where { TravelExpenseReportTable.id eq reportId }
                            .forUpdate()
                            .singleOrNull()
                            ?: return@transaction ReceiptInsertOutcome.NotFound
                    val lineRow = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq lineId }.singleOrNull()
                    if (lineRow == null || lineRow[TravelExpenseLineTable.reportId] != reportId) {
                        return@transaction ReceiptInsertOutcome.NotFound
                    }
                    if (reportRow[TravelExpenseReportTable.status] !in TravelExpenseReportStatusSets.EDITABLE) {
                        return@transaction ReceiptInsertOutcome.NotEditable
                    }
                    val subjectId = reportRow[TravelExpenseReportTable.subjectMemberId]
                    val requestedById = reportRow[TravelExpenseReportTable.requestedBy]
                    if (current.memberId != subjectId && current.memberId != requestedById) {
                        return@transaction ReceiptInsertOutcome.Forbidden
                    }
                    val lineReceiptCount =
                        TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq lineId }.count()
                    if (lineReceiptCount >= TravelExpenseAmountRules.MAX_RECEIPTS_PER_LINE) {
                        return@transaction ReceiptInsertOutcome.TooManyOnLine
                    }
                    val reportReceiptCount =
                        (TravelExpenseReceiptTable innerJoin TravelExpenseLineTable)
                            .selectAll()
                            .where { TravelExpenseLineTable.reportId eq reportId }
                            .count()
                    if (reportReceiptCount >= TravelExpenseAmountRules.MAX_RECEIPTS_PER_REPORT) {
                        return@transaction ReceiptInsertOutcome.TooManyOnReport
                    }
                    TravelExpenseReceiptTable.insert {
                        it[id] = receiptId
                        it[TravelExpenseReceiptTable.lineId] = lineId
                        it[TravelExpenseReceiptTable.storageKey] = storageKey
                        it[originalFilename] = sanitizedFileName
                        it[TravelExpenseReceiptTable.mimeType] = mimeType
                        it[sizeBytes] = totalBytes
                        it[sha256] = checksum
                        it[uploadedBy] = current.memberId
                        it[uploadedAt] = now
                    }
                    // Belege aendern keinen Betrag -- kein recomputeTotal. Keine Audit-Zeile (Draft-
                    // Mutation), siehe TravelExpenseService KDoc.
                    ReceiptInsertOutcome.Inserted
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }

        when (insertOutcome) {
            ReceiptInsertOutcome.NotFound -> {
                targetFile.delete()
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            ReceiptInsertOutcome.NotEditable -> {
                targetFile.delete()
                call.respond(HttpStatusCode.Conflict, "Report is not editable (only DRAFT can receive receipts)")
                return@post
            }
            ReceiptInsertOutcome.Forbidden -> {
                targetFile.delete()
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            ReceiptInsertOutcome.TooManyOnLine -> {
                targetFile.delete()
                call.respond(
                    HttpStatusCode.Conflict,
                    "This line already has ${TravelExpenseAmountRules.MAX_RECEIPTS_PER_LINE} receipts",
                )
                return@post
            }
            ReceiptInsertOutcome.TooManyOnReport -> {
                targetFile.delete()
                call.respond(
                    HttpStatusCode.Conflict,
                    "This report already has ${TravelExpenseAmountRules.MAX_RECEIPTS_PER_REPORT} receipts",
                )
                return@post
            }
            ReceiptInsertOutcome.Inserted -> Unit
        }

        call.respond(HttpStatusCode.Created, mapOf("receiptId" to receiptId.toString()))
    }

    delete("/api/travel-expenses/receipts/{receiptId}") {
        val receiptId = runCatching { Uuid.parse(call.parameters["receiptId"]!!) }.getOrNull()
        if (receiptId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid receiptId")
            return@delete
        }
        val current = resolveCurrentMember(call)

        val outcome =
            transaction {
                val receiptRow =
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq receiptId }.singleOrNull()
                        ?: return@transaction null
                val lineRow =
                    TravelExpenseLineTable
                        .selectAll()
                        .where {
                            TravelExpenseLineTable.id eq receiptRow[TravelExpenseReceiptTable.lineId]
                        }.singleOrNull()
                        ?: return@transaction null
                // Review MINOR fix: `forUpdate()` here, symmetric to the upload path's fix above --
                // without it, a receipt deletion never serializes against `submitReport`'s
                // `forUpdate()` lock on the same report row (Postgres READ COMMITTED lets a plain
                // SELECT read past a concurrent uncommitted writer), so a delete and a submit could
                // interleave and leave a RECEIPTED line with zero receipts in a submitted report.
                val reportRow =
                    TravelExpenseReportTable
                        .selectAll()
                        .where {
                            TravelExpenseReportTable.id eq lineRow[TravelExpenseLineTable.reportId]
                        }.forUpdate()
                        .singleOrNull()
                        ?: return@transaction null
                if (reportRow[TravelExpenseReportTable.status] !in
                    TravelExpenseReportStatusSets.EDITABLE
                ) {
                    return@transaction DeleteOutcome.NotEditable
                }
                val subjectId = reportRow[TravelExpenseReportTable.subjectMemberId]
                val requestedById = reportRow[TravelExpenseReportTable.requestedBy]
                if (current.memberId != subjectId && current.memberId != requestedById) return@transaction DeleteOutcome.Forbidden
                val storageKey = receiptRow[TravelExpenseReceiptTable.storageKey]
                TravelExpenseReceiptTable.deleteWhere { TravelExpenseReceiptTable.id eq receiptId }
                DeleteOutcome.Deleted(storageKey)
            }
        when (outcome) {
            null -> call.respond(HttpStatusCode.NotFound)
            DeleteOutcome.NotEditable -> call.respond(HttpStatusCode.Conflict, "Report is not editable")
            DeleteOutcome.Forbidden -> call.respond(HttpStatusCode.Forbidden)
            is DeleteOutcome.Deleted -> {
                storageRoot.resolve(outcome.storageKey).delete()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    get("/api/travel-expenses/receipts/{receiptId}/download") {
        val receiptId = runCatching { Uuid.parse(call.parameters["receiptId"]!!) }.getOrNull()
        if (receiptId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid receiptId")
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
                val receiptRow =
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq receiptId }.singleOrNull()
                        ?: return@transaction null
                val lineRow =
                    TravelExpenseLineTable
                        .selectAll()
                        .where {
                            TravelExpenseLineTable.id eq receiptRow[TravelExpenseReceiptTable.lineId]
                        }.singleOrNull()
                        ?: return@transaction null
                val reportRow =
                    TravelExpenseReportTable
                        .selectAll()
                        .where {
                            TravelExpenseReportTable.id eq lineRow[TravelExpenseLineTable.reportId]
                        }.singleOrNull()
                        ?: return@transaction null
                DownloadRow(
                    receiptRow = receiptRow,
                    subjectId = reportRow[TravelExpenseReportTable.subjectMemberId],
                    requestedById = reportRow[TravelExpenseReportTable.requestedBy],
                    reportStatus = reportRow[TravelExpenseReportTable.status],
                )
            }
        if (row == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val (receiptRow, subjectId, requestedById, reportStatus) = row
        // IDOR-Gate (one shared rule, see canDownloadTravelExpenseFile): subject/requester always,
        // BOARD/ADMIN only once the report left DRAFT, never TREASURER.
        if (!canDownloadTravelExpenseFile(
                current = current,
                subjectId = subjectId,
                requestedById = requestedById,
                status = reportStatus,
            )
        ) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }

        val file = storageRoot.resolve(receiptRow[TravelExpenseReceiptTable.storageKey])
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Stored file missing")
            return@get
        }

        call.respondTravelExpenseFile(
            file = file,
            originalFilename = receiptRow[TravelExpenseReceiptTable.originalFilename],
            mimeType = receiptRow[TravelExpenseReceiptTable.mimeType],
        )
    }
}

private sealed interface ReceiptUploadGate {
    data object NotFound : ReceiptUploadGate

    data object DayLine : ReceiptUploadGate

    data object NotEditable : ReceiptUploadGate

    data object Forbidden : ReceiptUploadGate

    data object TooManyOnLine : ReceiptUploadGate

    data object TooManyOnReport : ReceiptUploadGate

    data class Allowed(
        val reportId: Uuid,
    ) : ReceiptUploadGate
}

/** Outcome of the authoritative re-check inside the insert transaction -- see class KDoc "TOCTOU". */
private sealed interface ReceiptInsertOutcome {
    data object NotFound : ReceiptInsertOutcome

    data object NotEditable : ReceiptInsertOutcome

    data object Forbidden : ReceiptInsertOutcome

    data object TooManyOnLine : ReceiptInsertOutcome

    data object TooManyOnReport : ReceiptInsertOutcome

    data object Inserted : ReceiptInsertOutcome
}

/** Row bundle for the download route's IDOR/DRAFT-privacy gate -- see that route's own comment. */
private data class DownloadRow(
    val receiptRow: ResultRow,
    val subjectId: Uuid,
    val requestedById: Uuid,
    val reportStatus: TravelExpenseReportStatus,
)

private sealed interface DeleteOutcome {
    data object NotEditable : DeleteOutcome

    data object Forbidden : DeleteOutcome

    data class Deleted(
        val storageKey: String,
    ) : DeleteOutcome
}
