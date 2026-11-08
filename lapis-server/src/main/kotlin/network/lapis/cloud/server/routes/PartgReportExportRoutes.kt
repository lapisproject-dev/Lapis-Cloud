package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgReportSnapshotReader
import network.lapis.cloud.server.partg.PartgVersionFormat
import network.lapis.cloud.server.partg.PartgVersionStore
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.report.PartgReportDocuments
import network.lapis.cloud.server.partg.report.PartgReportPdfRenderer
import network.lapis.cloud.server.partg.report.PartgReportTexts
import network.lapis.cloud.server.partg.report.PartgReportTooLargeException
import network.lapis.cloud.server.partg.report.PartgReportXlsxWriter
import network.lapis.cloud.server.partg.requirePartyReportAccess
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.server.pdf.formatGermanDate
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import network.lapis.cloud.shared.domain.PartgReportExportAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val PDF_EXPORT_TYPE = "application/pdf"
private const val XLSX_EXPORT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * V1.9.256 "Rechenschaftsbericht (PartG), R2" -- the report draft as a PDF or XLSX download.
 *
 * EVERY handler starts with `requirePartyReportAccess(call)` (role BOARD/ADMIN first, then the organisation switch; pinned by
 * `PartyReportAccessTripwireTest`) BEFORE a query parameter is read. Order: gate, rate limit (10 per minute and member), input check, concurrency
 * permit (2 exports at a time), ONE read transaction, assemble and render OUTSIDE the transaction, audit entry (in its own transaction, BEFORE
 * anything is sent), response. The file name is fixed; nothing user-supplied reaches a header or the file system. Only format, year, scope key
 * and counters are logged: no name, no amount.
 */
fun Route.registerPartgReportExportRoutes(
    rateLimiter: FederationInboxRateLimiter,
    concurrency: Semaphore,
) {
    get("/api/partg-report/export/pdf") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        call.export(format = PartgReportFormat.PDF, current = current, rateLimiter = rateLimiter, concurrency = concurrency)
    }

    get("/api/partg-report/export/xlsx") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        call.export(format = PartgReportFormat.XLSX, current = current, rateLimiter = rateLimiter, concurrency = concurrency)
    }

    // V1.9.291 (R6): a KEPT version as PDF / XLSX, rendered from the stored document (never from a recomputation)
    get("/api/partg-report/version/pdf") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        call.exportVersion(format = PartgReportFormat.PDF, current = current, rateLimiter = rateLimiter, concurrency = concurrency)
    }

    get("/api/partg-report/version/xlsx") {
        val current =
            try {
                requirePartyReportAccess(call)
            } catch (e: ForbiddenException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.FORBIDDEN)
            } catch (e: PartyReportNotEnabledException) {
                return@get call.rejectExport(status = HttpStatusCode.Forbidden, code = PartgImportRejectionCode.NOT_ENABLED)
            }
        call.exportVersion(format = PartgReportFormat.XLSX, current = current, rateLimiter = rateLimiter, concurrency = concurrency)
    }
}

private val STRICT_UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

/** What one read transaction returns for a kept version. */
private class StoredVersion(
    val meta: PartgVersionStore.VersionMeta,
    val json: String,
    val storedHash: String,
)

/**
 * V1.9.291 (R6) -- the download of a KEPT version. Order: rate limit, strict id, concurrency permit, ONE read transaction (metadata, text,
 * checksum), integrity check (the stored text is hashed again; a mismatch is 409 `INTEGRITY` and nothing is rendered), decode, render OUTSIDE
 * the transaction, audit entry BEFORE anything is sent, response. A random or unknown id is 404. The file name is fixed
 * (`rechenschaftsbericht-<year>-version-<n>.<ext>`). The footer carries the version number, the day it was kept, the first 12 characters of the
 * checksum and the status, so a file passed on can always be matched to its version.
 */
private suspend fun ApplicationCall.exportVersion(
    format: PartgReportFormat,
    current: CurrentMember,
    rateLimiter: FederationInboxRateLimiter,
    concurrency: Semaphore,
) {
    if (!rateLimiter.checkAndRecord("partg:export:${current.memberId}")) {
        return rejectExport(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
    }
    val raw = request.queryParameters["id"]
    if (raw == null || !STRICT_UUID.matches(raw)) {
        return rejectExport(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
    }
    val versionId = Uuid.parse(raw)
    if (!concurrency.tryAcquire()) {
        return rejectExport(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
    }
    try {
        val stored =
            transaction {
                val meta = PartgVersionStore.versionMeta(versionId)
                val content = meta?.let { PartgVersionStore.content(versionId) }
                if (meta == null || content == null) null else StoredVersion(meta = meta, json = content.first, storedHash = content.second)
            } ?: return rejectExport(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
        if (PartgVersionFormat.hash(stored.json) != stored.storedHash || stored.storedHash != stored.meta.contentHash) {
            logger.warn { "partg version integrity failure version=$versionId" }
            return rejectExport(status = HttpStatusCode.Conflict, code = PartgImportRejectionCode.INTEGRITY)
        }
        val meta = stored.meta
        val footer =
            PartgReportTexts.versionFooter(
                versionNumber = meta.versionNumber,
                keptOn = formatGermanDate(OrganizationTimeZone.wallNowOf(meta.frozenAt).date),
                hash12 = meta.contentHash.take(HASH_PREFIX_CHARS),
                final = meta.finalizedAt != null,
            )
        val rendered =
            try {
                withContext(Dispatchers.Default) {
                    val document = PartgVersionFormat.decode(text = stored.json, formatVersion = meta.formatVersion)
                    val bytes =
                        when (format) {
                            PartgReportFormat.PDF -> PartgReportPdfRenderer().render(document = document, footer = footer)
                            PartgReportFormat.XLSX -> PartgReportXlsxWriter.write(document = document, footer = footer)
                        }
                    Counters(
                        bytes = bytes,
                        unitCount = document.units.size,
                        missingCount = document.completeness.units.sumOf { it.missingCurrent.size },
                        donorRowCount = document.annex.donors.size,
                        findingCount = document.findings.size,
                        sponsorRowCount = document.annex.sponsors.size,
                        youthOrgRowCount = document.annex.youthOrgs.size,
                        enterpriseRowCount = document.annex.enterprises.size,
                    )
                }
            } catch (e: PartyReportVersionIntegrityException) {
                logger.warn { "partg version integrity failure version=$versionId" }
                return rejectExport(status = HttpStatusCode.Conflict, code = PartgImportRejectionCode.INTEGRITY)
            } catch (e: PdfLimitExceeded) {
                return rejectExport(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
            } catch (e: PartgReportTooLargeException) {
                return rejectExport(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
            }
        val now = ServerClock.now()
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.PARTG_REPORT_EXPORT,
                entityId = Uuid.random(),
                action = AuditAction.CREATE,
                after =
                    Json.encodeToString(
                        PartgReportExportAuditSnapshot.serializer(),
                        PartgReportExportAuditSnapshot(
                            format = format,
                            year = meta.year,
                            scope = meta.scopeKey,
                            unitCount = rendered.unitCount,
                            missingCount = rendered.missingCount,
                            byteSize = rendered.bytes.size,
                            donorRowCount = rendered.donorRowCount,
                            findingCount = rendered.findingCount,
                            sponsorRowCount = rendered.sponsorRowCount,
                            youthOrgRowCount = rendered.youthOrgRowCount,
                            enterpriseRowCount = rendered.enterpriseRowCount,
                            versionId = meta.id.toString(),
                            versionNumber = meta.versionNumber,
                        ),
                    ),
                occurredAt = now,
            )
        }
        logger.info {
            "partg report version export format=$format year=${meta.year} version=${meta.versionNumber} bytes=${rendered.bytes.size}"
        }
        val extension = if (format == PartgReportFormat.PDF) "pdf" else "xlsx"
        response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"rechenschaftsbericht-${meta.year}-version-${meta.versionNumber}.$extension\"",
        )
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Content-Type-Options", "nosniff")
        respondBytes(rendered.bytes, ContentType.parse(if (format == PartgReportFormat.PDF) PDF_EXPORT_TYPE else XLSX_EXPORT_TYPE))
    } finally {
        concurrency.release()
    }
}

private const val HASH_PREFIX_CHARS = 12

private suspend fun ApplicationCall.export(
    format: PartgReportFormat,
    current: CurrentMember,
    rateLimiter: FederationInboxRateLimiter,
    concurrency: Semaphore,
) {
    if (!rateLimiter.checkAndRecord("partg:export:${current.memberId}")) {
        return rejectExport(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
    }
    val year = request.queryParameters["year"]?.toIntOrNull()
    val scope = request.queryParameters["scope"]?.let { PartgReportScope.parse(it) }
    if (year == null || scope == null || year < PartgYearRules.MIN_YEAR || year > PartgYearRules.maxYear()) {
        return rejectExport(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
    }
    if (!concurrency.tryAcquire()) {
        return rejectExport(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
    }
    try {
        val snapshot =
            try {
                PartgReportSnapshotReader.read(listOf(year, year - 1))
            } catch (e: PartyReportRevisionConflictException) {
                return rejectExport(status = HttpStatusCode.Conflict, code = PartgImportRejectionCode.REVISION_CONFLICT)
            }
        val rendered =
            try {
                withContext(Dispatchers.Default) {
                    val built = PartgReportDocuments.buildWithCounters(snapshot = snapshot, year = year, scope = scope)
                    val document = built.document
                    val findingCount = built.findingCount
                    val bytes =
                        when (format) {
                            PartgReportFormat.PDF -> PartgReportPdfRenderer().render(document = document)
                            PartgReportFormat.XLSX -> PartgReportXlsxWriter.write(document = document)
                        }
                    Counters(
                        bytes = bytes,
                        unitCount = document.units.size,
                        missingCount =
                            document.completeness.units.sumOf {
                                it.missingCurrent.size
                            },
                        donorRowCount = document.annex.donors.size,
                        findingCount = findingCount,
                        sponsorRowCount = document.annex.sponsors.size,
                        youthOrgRowCount = document.annex.youthOrgs.size,
                        enterpriseRowCount = document.annex.enterprises.size,
                    )
                }
            } catch (e: NotFoundException) {
                return rejectExport(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            } catch (e: PartyReportInvalidInputException) {
                return rejectExport(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
            } catch (e: PdfLimitExceeded) {
                return rejectExport(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
            } catch (e: PartgReportTooLargeException) {
                return rejectExport(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
            }
        val (bytes, unitCount, missingCount, donorRowCount, findingCount, sponsorRowCount, youthOrgRowCount, enterpriseRowCount) =
            rendered
        val now = ServerClock.now()
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.PARTG_REPORT_EXPORT,
                entityId = Uuid.random(),
                action = AuditAction.CREATE,
                after =
                    Json.encodeToString(
                        PartgReportExportAuditSnapshot.serializer(),
                        PartgReportExportAuditSnapshot(
                            format = format,
                            year = year,
                            scope = scope.key(),
                            unitCount = unitCount,
                            missingCount = missingCount,
                            byteSize = bytes.size,
                            donorRowCount = donorRowCount,
                            findingCount = findingCount,
                            sponsorRowCount = sponsorRowCount,
                            youthOrgRowCount = youthOrgRowCount,
                            enterpriseRowCount = enterpriseRowCount,
                        ),
                    ),
                occurredAt = now,
            )
        }
        logger.info { "partg report export format=$format year=$year scope=${scope.key()} units=$unitCount bytes=${bytes.size}" }
        val extension = if (format == PartgReportFormat.PDF) "pdf" else "xlsx"
        response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"rechenschaftsbericht-$year-entwurf.$extension\"")
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Content-Type-Options", "nosniff")
        respondBytes(bytes, ContentType.parse(if (format == PartgReportFormat.PDF) PDF_EXPORT_TYPE else XLSX_EXPORT_TYPE))
    } finally {
        concurrency.release()
    }
}

/** What an export reports to the audit log: counters only. */
private data class Counters(
    val bytes: ByteArray,
    val unitCount: Int,
    val missingCount: Int,
    val donorRowCount: Int,
    val findingCount: Int,
    val sponsorRowCount: Int,
    val youthOrgRowCount: Int,
    val enterpriseRowCount: Int,
)

private suspend fun ApplicationCall.rejectExport(
    status: HttpStatusCode,
    code: PartgImportRejectionCode,
) = respond(status, PartgImportRejectionDto(code = code))
