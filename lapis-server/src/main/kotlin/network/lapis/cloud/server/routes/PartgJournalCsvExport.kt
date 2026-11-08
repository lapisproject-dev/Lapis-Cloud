package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.csv.SpreadsheetCsv
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.PartyReportActor
import network.lapis.cloud.server.security.BookingScope
import network.lapis.cloud.server.security.FEDERAL_UNIT_FILTER
import network.lapis.cloud.server.security.journalCondition
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgReportExportAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val CSV_CHUNK = 10_000
private const val CSV_TYPE = "text/csv; charset=utf-8"
private const val FEDERAL_LABEL = "Bundesebene"
private const val CRLF = "\r\n"
private val CSV_HEADER = listOf("Datum", "Belegnummer", "Buchungstext", "Konto", "Kontoname", "Gegenkonto", "Soll", "Haben", "Gliederung")

/** One posting row as the CSV shows it. */
private class CsvPosting(
    val id: Uuid,
    val side: PostingSide,
    val amount: BigDecimal,
    val accountNumber: String,
    val accountName: String,
)

private class CsvEntry(
    val id: Uuid,
    val date: LocalDate,
    val description: String,
    val voucher: String?,
    val chapterName: String?,
)

private class JournalTooLargeException : RuntimeException(null, null, false, false)

/**
 * V1.9.292 (R4b) -- the posted journal of ONE unit and year as a CSV, one row per posting, with a "Gliederung" column.
 *
 * Scoping is server side and the same [BookingScope] mechanism as the booking screens: a chapter treasurer reads ONLY the chapters of their active
 * grants ([BookingScope.Chapters]) and MUST name a `unit` that is theirs -- missing, unparsable, `FEDERAL`, foreign or unknown is one uniform 404.
 * A federal role reads everything or one `unit`. A `SUB:` unit has no bookings (Q20 is open), so it is a 400 (after the 404 check). Only POSTED
 * entries; drafts never appear. Order: rate limit, year (400), unit (404/400), concurrency permit, ONE read transaction (chunked, capped at
 * [maxTotalPostings] rows, then 413), audit entry BEFORE anything is sent. The audit entry holds counters only: no name, no amount.
 *
 * CSV safety: EVERY cell goes through [SpreadsheetCsv.field] (formula guard, quoting); amounts are never negative (debit and credit are separate
 * columns), so the `-` guard never touches them. UTF-8 with BOM, CRLF, `;` separator, decimal comma.
 */
internal suspend fun ApplicationCall.exportJournalCsv(
    actor: PartyReportActor,
    rateLimiter: FederationInboxRateLimiter,
    concurrency: Semaphore,
    maxTotalPostings: Int,
) {
    val current = actor.current
    if (!rateLimiter.checkAndRecord("partg:export:${current.memberId}")) {
        return rejectExport(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
    }
    val year = request.queryParameters["year"]?.toIntOrNull()
    if (year == null || year < PartgYearRules.MIN_YEAR || year > PartgYearRules.maxYear()) {
        return rejectExport(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
    }
    val rawUnit = request.queryParameters["unit"]
    val chapterTreasurer = actor is PartyReportActor.ChapterTreasurer
    var parsedUnit: PartgUnit? = null
    if (chapterTreasurer || rawUnit != null) {
        val parsed = rawUnit?.let { PartgUnit.parse(it) }
        if (parsed == null) {
            // a chapter treasurer must name a unit of theirs (404, nothing else); a federal role naming rubbish is a 400
            return if (chapterTreasurer) {
                rejectExport(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            } else {
                rejectExport(status = HttpStatusCode.BadRequest, code = PartgImportRejectionCode.INVALID_INPUT)
            }
        }
        if (!actor.allows(parsed)) return rejectExport(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
        if (parsed is PartgUnit.Subordinate) {
            return rejectExport(
                status = HttpStatusCode.BadRequest,
                code = PartgImportRejectionCode.INVALID_INPUT,
            )
        }
        parsedUnit = parsed
    }
    val unit: PartgUnit? = parsedUnit
    val unitFilter: String? =
        when (unit) {
            null -> null
            is PartgUnit.Federal -> FEDERAL_UNIT_FILTER
            else -> unit.chapterId
        }
    val scope: BookingScope =
        when (actor) {
            is PartyReportActor.Federal -> BookingScope.Federal
            is PartyReportActor.ChapterTreasurer -> BookingScope.Chapters(actor.chapterIds)
        }
    if (!concurrency.tryAcquire()) {
        return rejectExport(status = HttpStatusCode.TooManyRequests, code = PartgImportRejectionCode.RATE_LIMITED)
    }
    try {
        val csv =
            try {
                transaction { buildCsv(scope = scope, unitFilter = unitFilter, year = year, maxTotalPostings = maxTotalPostings) }
            } catch (e: ForbiddenException) {
                return rejectExport(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            } catch (e: NotFoundException) {
                return rejectExport(status = HttpStatusCode.NotFound, code = PartgImportRejectionCode.NOT_FOUND)
            } catch (e: JournalTooLargeException) {
                return rejectExport(status = HttpStatusCode.PayloadTooLarge, code = PartgImportRejectionCode.TOO_LARGE)
            }
        val bytes = SpreadsheetCsv.UTF8_BOM + csv.text.toByteArray(Charsets.UTF_8)
        val scopeKey = unit?.key() ?: "ALL"
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
                            format = PartgReportFormat.CSV,
                            year = year,
                            scope = scopeKey,
                            unitCount = if (unit == null) 0 else 1,
                            missingCount = 0,
                            byteSize = bytes.size,
                            chapterScoped = chapterTreasurer,
                            rowCount = csv.rowCount,
                        ),
                    ),
                occurredAt = now,
            )
        }
        logger.info { "partg journal csv export year=$year scope=$scopeKey rows=${csv.rowCount} bytes=${bytes.size}" }
        val suffix = unit?.key()?.replace(':', '-') ?: "alle"
        response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"buchungsjournal-$year-$suffix.csv\"")
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Content-Type-Options", "nosniff")
        respondBytes(bytes, ContentType.parse(CSV_TYPE))
    } finally {
        concurrency.release()
    }
}

private class BuiltCsv(
    val text: String,
    val rowCount: Int,
)

/** Must run inside a transaction. Throws [ForbiddenException]/[NotFoundException] (from the scope) and [JournalTooLargeException]. */
private fun buildCsv(
    scope: BookingScope,
    unitFilter: String?,
    year: Int,
    maxTotalPostings: Int,
): BuiltCsv {
    val scopeCondition: Op<Boolean>? = scope.journalCondition(unitFilter)
    val entryRows =
        (JournalEntryTable leftJoin RegionalChapterTable)
            .selectAll()
            .where {
                var condition: Op<Boolean> =
                    (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                        (JournalEntryTable.entryDate greaterEq LocalDate(year, 1, 1)) and
                        (JournalEntryTable.entryDate lessEq LocalDate(year, 12, 31))
                if (scopeCondition != null) condition = condition and scopeCondition
                condition
            }.orderBy(
                JournalEntryTable.entryDate to SortOrder.ASC,
                JournalEntryTable.voucherReference to SortOrder.ASC_NULLS_FIRST,
                JournalEntryTable.id to SortOrder.ASC,
            ).limit(maxTotalPostings + 1)
            .toList()
    if (entryRows.size > maxTotalPostings) throw JournalTooLargeException()
    val entries =
        entryRows.map {
            CsvEntry(
                id = it[JournalEntryTable.id],
                date = it[JournalEntryTable.entryDate],
                description = it[JournalEntryTable.description],
                voucher = it[JournalEntryTable.voucherReference],
                chapterName = it.getOrNull(RegionalChapterTable.name),
            )
        }
    val postings = HashMap<Uuid, MutableList<CsvPosting>>()
    var loaded = 0
    entries.map { it.id }.chunked(CSV_CHUNK).forEach { chunk ->
        val remaining = maxTotalPostings - loaded
        val rows =
            (PostingTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { PostingTable.journalEntryId inList chunk }
                .limit(remaining + 1)
                .toList()
        loaded += rows.size
        if (loaded > maxTotalPostings) throw JournalTooLargeException()
        rows.forEach { row ->
            postings.getOrPut(row[PostingTable.journalEntryId]) { mutableListOf() } +=
                CsvPosting(
                    id = row[PostingTable.id],
                    side = row[PostingTable.side],
                    amount = row[PostingTable.amount],
                    accountNumber = row[LedgerAccountTable.accountNumber],
                    accountName = row[LedgerAccountTable.name],
                )
        }
    }
    val text = StringBuilder()
    text.append(CSV_HEADER.joinToString(";") { SpreadsheetCsv.field(value = it) }).append(CRLF)
    var rowCount = 0
    for (entry in entries) {
        val lines = postings[entry.id].orEmpty().sortedWith(compareBy({ it.accountNumber }, { it.id }))
        for (posting in lines) {
            // the counter-account is only unambiguous for a simple entry of exactly two postings; a split entry leaves it empty
            val counter = if (lines.size == 2) lines.first { it !== posting }.accountNumber else ""
            val amount =
                posting.amount
                    .setScale(2, java.math.RoundingMode.UNNECESSARY)
                    .toPlainString()
                    .replace('.', ',')
            val debit = if (posting.side == PostingSide.DEBIT) amount else ""
            val credit = if (posting.side == PostingSide.CREDIT) amount else ""
            val cells =
                listOf(
                    "%02d.%02d.%04d".format(entry.date.day, entry.date.month.ordinal + 1, entry.date.year),
                    entry.voucher.orEmpty(),
                    entry.description,
                    posting.accountNumber,
                    posting.accountName,
                    counter,
                    debit,
                    credit,
                    entry.chapterName ?: FEDERAL_LABEL,
                )
            text.append(cells.joinToString(";") { SpreadsheetCsv.field(value = it) }).append(CRLF)
            rowCount++
        }
    }
    return BuiltCsv(text = text.toString(), rowCount = rowCount)
}
