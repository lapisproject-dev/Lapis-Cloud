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
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgReportStore
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.report.PartgReportAssembler
import network.lapis.cloud.server.partg.report.PartgReportPdfRenderer
import network.lapis.cloud.server.partg.report.PartgReportTooLargeException
import network.lapis.cloud.server.partg.report.PartgReportXlsxWriter
import network.lapis.cloud.server.partg.requirePartyReportAccess
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
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
}

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
        val (organization, units, entered) =
            transaction {
                Triple(
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                        ?.get(OrganizationSettingsTable.name)
                        .orEmpty(),
                    PartgReportStore.units(),
                    PartgReportStore.enteredValues(listOf(year, year - 1)),
                )
            }
        val rendered =
            try {
                withContext(Dispatchers.Default) {
                    val document =
                        PartgReportAssembler.assemble(
                            organizationName = organization,
                            year = year,
                            scope = scope,
                            units = units,
                            entered = entered,
                        )
                    val bytes =
                        when (format) {
                            PartgReportFormat.PDF -> PartgReportPdfRenderer().render(document)
                            PartgReportFormat.XLSX -> PartgReportXlsxWriter.write(document)
                        }
                    Triple(bytes, document.units.size, document.completeness.units.sumOf { it.missingCurrent.size })
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
        val (bytes, unitCount, missingCount) = rendered
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

private suspend fun ApplicationCall.rejectExport(
    status: HttpStatusCode,
    code: PartgImportRejectionCode,
) = respond(status, PartgImportRejectionDto(code = code))
