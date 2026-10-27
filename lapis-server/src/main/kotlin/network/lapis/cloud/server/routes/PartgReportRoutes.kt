package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mcp.transport.receiveCappedTextOrNull
import network.lapis.cloud.server.partg.PartgImportPreviewStore
import network.lapis.cloud.server.partg.PartgReportStore
import network.lapis.cloud.server.partg.PartgSheetWriter
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.requirePartyReportAccess
import network.lapis.cloud.server.partg.xlsx.PartgXlsxReader
import network.lapis.cloud.server.partg.xlsx.PartgXlsxWriter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgImportApplyDto
import network.lapis.cloud.shared.domain.PartgImportApplyResultDto
import network.lapis.cloud.shared.domain.PartgImportAuditSnapshot
import network.lapis.cloud.shared.domain.PartgImportChangeDto
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgImportLimits
import network.lapis.cloud.shared.domain.PartgImportPreviewDto
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

private const val XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MAX_APPLY_BODY_BYTES = 4096L

/**
 * V1.9.225 "Rechenschaftsbericht (PartG), R1" -- template download and the two-step Excel import. File bytes travel over these Ktor routes, not
 * Kilua RPC (same reasoning as `registerBankStatementRoutes`).
 *
 * EVERY handler starts with `requirePartyReportAccess(call)` (role BOARD/ADMIN first, then the organisation switch; pinned by
 * `PartyReportAccessTripwireTest`) BEFORE the body is read. Rate limit 10 per minute and client address, upload cap 2 MB counted WHILE
 * streaming, `year` and `unit` form fields are validated before the file is read. A refusal is always the JSON [PartgImportRejectionDto]
 * with a stable code; nothing echoes cell text. Only counts and ids are logged; no amount, no file name.
 */
fun Route.registerPartgReportRoutes(
    previewStore: PartgImportPreviewStore,
    rateLimiter: FederationInboxRateLimiter,
) {
    get("/api/partg-report/template") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@get call.reject(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@get call.reject(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        if (!rateLimiter.checkAndRecord("partg:template:${current.memberId}")) {
            return@get call.reject(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
        }
        val year = call.request.queryParameters["year"]?.toIntOrNull()
        val unit = call.request.queryParameters["unit"]?.let { PartgUnit.parse(it) }
        if (year == null || unit == null || !validYear(year)) {
            return@get call.reject(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
        }
        val generated =
            try {
                transaction {
                    val dto = PartgReportStore.unitDto(unit)
                    val sheet = PartgReportStore.findSheet(year = year, unit = unit)
                    val prior = PartgReportStore.findSheet(year = year - 1, unit = unit)
                    val organization =
                        OrganizationSettingsTable
                            .selectAll()
                            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                            .singleOrNull()
                            ?.get(OrganizationSettingsTable.name) ?: ""
                    val unitName =
                        dto.chapterName?.let { name -> if (unit is PartgUnit.State) name else "$name (nachgeordnete Gebietsverbände)" }
                            ?: "Bundesverband"
                    Triple(
                        PartgXlsxWriter.write(
                            organizationName = organization,
                            unit = unit,
                            unitName = unitName,
                            year = year,
                            values = sheet?.let { PartgReportStore.values(it.id) }.orEmpty(),
                            priorValues = prior?.let { PartgReportStore.values(it.id) }.orEmpty(),
                        ),
                        dto.chapterName ?: "Bundesverband",
                        unit.level,
                    )
                }
            } catch (e: NotFoundException) {
                return@get call.reject(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"Rechenschaftsbericht_${year}_${asciiSlug(generated.second)}.xlsx\"",
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(generated.first, ContentType.parse(XLSX_CONTENT_TYPE))
    }

    post("/api/partg-report/import/preview") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@post call.reject(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@post call.reject(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        if (!rateLimiter.checkAndRecord("partg:import:${call.request.local.remoteHost}")) {
            return@post call.reject(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
        }
        var yearField: Int? = null
        var unitField: PartgUnit? = null
        var fileName: String? = null
        var refusal: PartgImportRejectionCode? = null
        val buffer = ByteArrayOutputStream()
        var total = 0L
        call.receiveMultipart().forEachPart { part ->
            if (refusal == null) {
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "year" ->
                                yearField =
                                    part.value
                                        .trim()
                                        .toIntOrNull()
                                        ?.takeIf { validYear(it) }
                            "unit" -> unitField = PartgUnit.parse(part.value.trim())
                        }
                    }
                    is PartData.FileItem -> {
                        if (fileName != null) {
                            refusal = PartgImportRejectionCode.INVALID_INPUT
                        } else if (yearField == null || unitField == null) {
                            // the fields must come BEFORE the file and be valid
                            refusal = PartgImportRejectionCode.INVALID_INPUT
                        } else {
                            fileName = sanitizeFileName(part.originalFileName)
                            val channel: ByteReadChannel = part.provider()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val read = channel.readAvailable(chunk)
                                if (read == -1) break
                                total += read
                                if (total > PartgImportLimits.MAX_UPLOAD_BYTES) {
                                    refusal = PartgImportRejectionCode.TOO_LARGE
                                    break
                                }
                                buffer.write(chunk, 0, read)
                            }
                        }
                    }
                    else -> Unit
                }
            }
            part.release()
        }
        refusal?.let {
            val status = if (it == PartgImportRejectionCode.TOO_LARGE) HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadRequest
            return@post call.reject(status = status, code = it)
        }
        val year = yearField
        val unit = unitField
        if (year == null || unit == null || fileName == null) {
            return@post call.reject(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
        }
        val bytes = buffer.toByteArray()
        val parsed = PartgXlsxReader.parse(bytes = bytes, expectedUnit = unit, expectedYear = year)
        val state =
            try {
                transaction {
                    PartgReportStore.unitDto(unit)
                    val sheet = PartgReportStore.findSheet(year = year, unit = unit)
                    val prior = PartgReportStore.findSheet(year = year - 1, unit = unit)
                    Quad(
                        a = sheet?.let { PartgReportStore.values(it.id) }.orEmpty(),
                        b = prior?.let { PartgReportStore.values(it.id) }.orEmpty(),
                        c = sheet?.revision ?: 0L,
                        d = prior?.revision ?: 0L,
                    )
                }
            } catch (e: NotFoundException) {
                return@post call.reject(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            }
        if (parsed.errors.isNotEmpty()) {
            return@post call.respond(
                HttpStatusCode.OK,
                PartgImportPreviewDto(
                    recognized = parsed.recognizedRows,
                    errors = parsed.errors,
                    sheetRevision = state.c,
                    priorRevision = state.d,
                ),
            )
        }
        val changes = mutableListOf<PartgImportChangeDto>()
        val newCurrent = LinkedHashMap<String, Long>()
        val newPrior = LinkedHashMap<String, Long>()
        parsed.values.entries.sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second })).forEach { (key, cents) ->
            val (column, code) = key
            val old = (if (column == PartgImportColumn.CURRENT) state.a else state.b)[code]
            if (old != cents) {
                changes +=
                    PartgImportChangeDto(
                        positionCode = code,
                        column = column,
                        oldCents = old,
                        newCents = cents,
                        fromFormula =
                            key in parsed.fromFormula,
                    )
                (if (column == PartgImportColumn.CURRENT) newCurrent else newPrior)[code] = cents
            }
        }
        val token =
            if (changes.isEmpty()) {
                null
            } else {
                previewStore.put(
                    memberId = current.memberId,
                    year = year,
                    unit = unit,
                    current = newCurrent,
                    prior = newPrior,
                    sha256 = partgSha256Hex(bytes),
                    fileName = fileName!!,
                    revision = state.c,
                    priorRevision = state.d,
                )
            }
        call.respond(
            HttpStatusCode.OK,
            PartgImportPreviewDto(
                token = token,
                recognized = parsed.recognizedRows,
                changes = changes,
                sheetRevision = state.c,
                priorRevision = state.d,
            ),
        )
    }

    post("/api/partg-report/import/apply") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@post call.reject(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@post call.reject(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        if (!rateLimiter.checkAndRecord("partg:import:${call.request.local.remoteHost}")) {
            return@post call.reject(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
        }
        val declared = call.request.contentLength()
        if (declared != null && declared > MAX_APPLY_BODY_BYTES) {
            return@post call.reject(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
        }
        val applyText =
            call.receiveCappedTextOrNull(MAX_APPLY_BODY_BYTES.toInt())
                ?: return@post call.reject(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
        val body =
            try {
                Json.decodeFromString<PartgImportApplyDto>(applyText)
            } catch (e: Exception) {
                return@post call.reject(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
            }
        val entry = previewStore.take(token = body.token, memberId = current.memberId)
        if (entry == null) return@post call.reject(status = HttpStatusCode.Conflict, code = PartgImportRejectionCode.TOKEN_INVALID)
        if (body.expectedRevision != entry.revision || body.expectedPriorRevision != entry.priorRevision) {
            return@post call.reject(status = HttpStatusCode.Conflict, code = PartgImportRejectionCode.REVISION_CONFLICT)
        }
        if (!validYear(
                entry.year,
            )
        ) {
            return@post call.reject(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
        }
        val result =
            try {
                PartgSheetWriter.write(
                    current = current,
                    year = entry.year,
                    unit = entry.unit,
                    expectedRevision = entry.revision,
                    expectedPriorRevision = entry.priorRevision,
                    currentChanges = entry.current,
                    priorChanges = entry.prior,
                    source = PartgValueSource.IMPORT,
                    now = ServerClock.now(),
                    collectiveAudit = { sheetId, now, count ->
                        AuditLogRecorder.record(
                            actorMemberId = current.memberId,
                            actorRole = current.role,
                            entityType = AuditEntityType.PARTG_REPORT_SHEET,
                            entityId = sheetId,
                            action = AuditAction.UPDATE,
                            after =
                                Json.encodeToString(
                                    PartgImportAuditSnapshot.serializer(),
                                    PartgImportAuditSnapshot(
                                        sha256 = entry.sha256,
                                        fileName = entry.fileName.take(120),
                                        changeCount = count,
                                        year = entry.year,
                                        unitKey = entry.unit.key(),
                                    ),
                                ),
                            occurredAt = now,
                        )
                    },
                )
            } catch (e: PartyReportRevisionConflictException) {
                return@post call.reject(status = HttpStatusCode.Conflict, code = PartgImportRejectionCode.REVISION_CONFLICT)
            } catch (e: NotFoundException) {
                return@post call.reject(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            } catch (e: PartyReportInvalidInputException) {
                return@post call.reject(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
            }
        call.respond(
            HttpStatusCode.OK,
            PartgImportApplyResultDto(changed = result.changed, revision = result.revision, priorRevision = result.priorRevision),
        )
    }
}

private data class Quad(
    val a: Map<String, Long>,
    val b: Map<String, Long>,
    val c: Long,
    val d: Long,
)

private fun validYear(year: Int): Boolean = year >= PartgYearRules.MIN_YEAR && year <= PartgYearRules.maxYear()

private suspend fun ApplicationCall.reject(
    status: HttpStatusCode,
    code: PartgImportRejectionCode,
) = respond(status, PartgImportRejectionDto(code = code))

private fun partgSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Printable characters only, no path parts, at most 120 characters; the name is shown in the audit entry and nowhere else. */
private fun sanitizeFileName(raw: String?): String =
    (raw ?: "import.xlsx")
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { it.code in 0x20..0x7E || it.code > 0xA0 }
        .take(120)
        .ifBlank { "import.xlsx" }

/** `[A-Za-z0-9_-]`, German umlauts transcribed, at most 60 characters. */
internal fun asciiSlug(name: String): String {
    val transcribed =
        name
            .replace(
                "ä",
                "ae",
            ).replace("ö", "oe")
            .replace("ü", "ue")
            .replace("Ä", "Ae")
            .replace("Ö", "Oe")
            .replace("Ü", "Ue")
            .replace("ß", "ss")
    return transcribed
        .map {
            if ((it.isLetterOrDigit() && it.code < 128) ||
                it == '-'
            ) {
                it
            } else {
                '_'
            }
        }.joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(60)
        .ifEmpty { "Bericht" }
}
