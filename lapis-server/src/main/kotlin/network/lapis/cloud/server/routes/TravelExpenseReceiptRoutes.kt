package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** DoS cap -- deliberately smaller than [network.lapis.cloud.server.routes.DocumentRoutes]'
 *  25 MiB cap: a receipt is a photo or a one-page PDF. */
private const val MAX_RECEIPT_BYTES = 10L * 1024 * 1024
private const val SNIFF_BYTES = 8
private const val STORAGE_PREFIX = "travel-expenses"

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- file bytes travel
 * over these dedicated routes, not Kilua RPC (same reasoning
 * [network.lapis.cloud.server.routes.registerDocumentRoutes] KDoc gives). Security checklist:
 * - **Path traversal**: `storageKey` is ALWAYS server-generated
 *   (`travel-expenses/{reportId}/{receiptId}.bin`), never derived from the client-supplied file
 *   name -- the file name is stored purely as display metadata for `Content-Disposition`.
 * - **DoS cap**: [MAX_RECEIPT_BYTES] enforced WHILE streaming, before the whole file is buffered.
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
        val storageKey = "$STORAGE_PREFIX/$reportId/$receiptId.bin"
        val targetFile = storageRoot.resolve(storageKey)
        targetFile.parentFile.mkdirs()

        var uploadedFileName: String? = null
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var tooLarge = false
        val sniffBuffer = ByteArray(SNIFF_BYTES)
        var sniffBufferFilled = 0
        // Review MAJOR fix: only the FIRST file part is ever streamed/hashed/sniffed -- every
        // subsequent one is drained-and-discarded (same `part.release()` pattern the `else` branch
        // below already uses for non-file parts) without touching the sniff buffer, digest,
        // totalBytes or the target file. See class KDoc "Multipart with more than one file part".
        var fileItemCount = 0

        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileItemCount++
                        if (fileItemCount == 1) {
                            uploadedFileName = part.originalFileName ?: "beleg"
                            targetFile.outputStream().use { out ->
                                val channel: ByteReadChannel = part.provider()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = channel.readAvailable(buffer)
                                    if (read == -1) break
                                    if (sniffBufferFilled < SNIFF_BYTES) {
                                        val toCopy = minOf(SNIFF_BYTES - sniffBufferFilled, read)
                                        System.arraycopy(buffer, 0, sniffBuffer, sniffBufferFilled, toCopy)
                                        sniffBufferFilled += toCopy
                                    }
                                    totalBytes += read
                                    if (totalBytes > MAX_RECEIPT_BYTES) {
                                        tooLarge = true
                                        break
                                    }
                                    digest.update(buffer, 0, read)
                                    out.write(buffer, 0, read)
                                }
                            }
                            part.release()
                        } else {
                            // Security-Audit fix (2026-09-12, part of the MAJOR "kein Byte-
                            // Kontingent" finding): a request with many file parts used to still be
                            // drained-and-discarded to the END, one `forEachPart` iteration per
                            // part, before this loop could turn it into a 400 -- binding
                            // bandwidth/CPU to parse+drain every part first. Aborting the whole
                            // multipart read the moment the SECOND file part is seen bounds the
                            // work to (at most) two parts, not however many thousand a malicious
                            // request contains. `part.release()` first so this part's own
                            // resources are still freed before the parser is abandoned.
                            part.release()
                            throw MultipleFilePartsRejected
                        }
                    }
                    else -> part.release()
                }
            }
        } catch (e: MultipleFilePartsRejected) {
            fileItemCount = 2 // exact count no longer matters -- the check below only asks ">1".
        } catch (e: Exception) {
            // Security-Audit fix (2026-09-12, MAJOR "Orphaned Beleg-Dateien"): ANY failure during
            // the streaming phase -- an aborted TCP connection, an IOException from the channel,
            // anything else `forEachPart`/the write loop can throw -- used to leave `targetFile` on
            // disk forever: there was no `finally`, no orphan sweeper, and a fresh random
            // `receiptId` every request means a retry never reuses (and so never cleans up) the
            // previous attempt's path. Mirrors the "generate outside, finalize in a short
            // transaction, clean up on ANY failure" doctrine `SepaService.generateBatchFile`'s own
            // KDoc documents for the identical class of bug.
            targetFile.delete()
            throw e
        }

        if (tooLarge) {
            targetFile.delete()
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is $MAX_RECEIPT_BYTES bytes")
            return@post
        }
        if (fileItemCount > 1) {
            targetFile.delete()
            call.respond(HttpStatusCode.BadRequest, "Request must contain exactly one file part")
            return@post
        }
        val fileName = uploadedFileName
        if (fileName == null) {
            targetFile.delete()
            call.respond(HttpStatusCode.BadRequest, "No file part in request")
            return@post
        }

        // The client-declared Content-Type is DISCARDED entirely -- see class KDoc "MIME allowlist".
        val mimeType = sniffMimeType(buffer = sniffBuffer, filled = sniffBufferFilled)
        if (mimeType == null) {
            targetFile.delete()
            call.respond(HttpStatusCode.UnsupportedMediaType, "Unsupported or unrecognized file type")
            return@post
        }

        val sanitizedFileName = sanitizeReceiptFilename(fileName)
        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
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
        // IDOR-Gate: Subjekt ODER Antragsteller ODER BOARD/ADMIN. Bewusst NICHT TREASURER, siehe
        // Datei-KDoc.
        val isSelfService = current.memberId == subjectId || current.memberId == requestedById
        if (!isSelfService) {
            // LOW hardening (2026-09-12 security audit): a DRAFT is private -- same hard rule
            // ITravelExpenseService.listReports enforces (submittedAt.isNotNull() always, status =
            // DRAFT as an argument even throws BadRequestException). Before this fix, BOARD/ADMIN's
            // privileged bypass ignored report status entirely, so a still-private, never-submitted
            // draft's receipt was downloadable by anyone privileged who happened to know/guess its
            // UUID -- the status check that stops a draft from ever reaching a BOARD-visible surface
            // is exactly what closes that gap here too.
            if (!current.isPrivileged || reportStatus == TravelExpenseReportStatus.DRAFT) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
        }

        val file = storageRoot.resolve(receiptRow[TravelExpenseReceiptTable.storageKey])
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Stored file missing")
            return@get
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            contentDispositionHeader(receiptRow[TravelExpenseReceiptTable.originalFilename]),
        )
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respond(LocalFileContent(file, ContentType.parse(receiptRow[TravelExpenseReceiptTable.mimeType])))
    }
}

/**
 * Pure control-flow signal for aborting `forEachPart` the instant a second file part is seen --
 * never surfaced to a caller, always caught within the same route handler. A singleton with
 * stack-trace capture suppressed via the 4-arg [RuntimeException] constructor: this is thrown on
 * the hot path of every multi-file-part rejection and nobody ever reads its stack trace, so
 * capturing one on every throw would be pure waste.
 */
private object MultipleFilePartsRejected : RuntimeException(null, null, false, false)

private sealed interface ReceiptUploadGate {
    data object NotFound : ReceiptUploadGate

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

/**
 * Derives a MIME type from magic bytes ONLY -- the client-declared `Content-Type` never reaches
 * this function. Returns `null` for anything unrecognized (including a truncated/empty upload),
 * which the caller turns into 415. Deliberately no `image/svg+xml` -- an XSS vector, see class
 * KDoc.
 */
internal fun sniffMimeType(
    buffer: ByteArray,
    filled: Int,
): String? =
    when {
        filled >= 4 &&
            buffer[0] == 0x25.toByte() &&
            buffer[1] == 0x50.toByte() &&
            buffer[2] == 0x44.toByte() &&
            buffer[3] == 0x46.toByte()
        -> "application/pdf"
        filled >= 3 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xD8.toByte() && buffer[2] == 0xFF.toByte() -> "image/jpeg"
        filled >= 8 &&
            buffer[0] == 0x89.toByte() &&
            buffer[1] == 0x50.toByte() &&
            buffer[2] == 0x4E.toByte() &&
            buffer[3] == 0x47.toByte() &&
            buffer[4] == 0x0D.toByte() &&
            buffer[5] == 0x0A.toByte() &&
            buffer[6] == 0x1A.toByte() &&
            buffer[7] == 0x0A.toByte() ->
            "image/png"
        else -> null
    }

/**
 * Strips any path separator and control character, keeps at most 255 characters, falls back to
 * "beleg" for an empty result -- pure display metadata, never used to build a filesystem path
 * (the `storageKey` is always server-generated, see this file's own KDoc).
 */
internal fun sanitizeReceiptFilename(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.filterNot { it.code in 0x00..0x1F || it.code == 0x7F }
    val truncated = cleaned.take(255)
    return truncated.ifBlank { "beleg" }
}

/** RFC 5987 `filename*=UTF-8''...` plus an ASCII `filename=` fallback -- see class KDoc "Header injection". */
internal fun contentDispositionHeader(originalFilename: String): String {
    val asciiFallback =
        originalFilename
            .filter { it.code in 0x20..0x7E && it != '"' && it != '\\' }
            .ifBlank { "beleg" }
    val encoded =
        java.net.URLEncoder
            .encode(originalFilename, "UTF-8")
            .replace("+", "%20")
    return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}
