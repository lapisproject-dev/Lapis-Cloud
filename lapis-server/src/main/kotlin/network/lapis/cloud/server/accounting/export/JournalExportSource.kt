package network.lapis.cloud.server.accounting.export

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.accounting.datev.DatevBuchungsstapelWriter
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Batch size for the `PostingTable.journalEntryId inList <chunk>` query in
 * [buildJournalExportRequest] -- see the former `network.lapis.cloud.server.accounting.datev
 * .POSTING_QUERY_CHUNK_SIZE` KDoc (Welle V1.4.5.2) this constant continues verbatim.
 */
internal const val POSTING_QUERY_CHUNK_SIZE = 10_000

/** DoS backstop across every chunk -- see the former `datev.MAX_TOTAL_POSTINGS` KDoc (Welle
 * V1.4.5.2) this constant continues verbatim; both guards apply to every provider that reads
 * through [buildJournalExportRequest], DATEV and lexoffice alike. */
internal const val MAX_TOTAL_POSTINGS = 200_000

/** Default entry cap -- see [buildJournalExportRequest] `maxEntries` parameter KDoc. */
internal const val DEFAULT_MAX_ENTRIES = DatevBuchungsstapelWriter.MAX_ROWS

/** One posting line, provider-neutral -- the shared successor of the former
 * `network.lapis.cloud.server.accounting.datev.DatevSourcePosting` (Welle V1.4.5.2), now carrying
 * [ledgerAccountId]/[accountType] in ADDITION to [accountNumber] (see [JournalExportEntry] KDoc for
 * why: lexoffice needs the ledger account's real id for category-mapping lookups and its
 * [LedgerAccountType] for the sales/purchase direction decision, neither of which DATEV's own
 * Buchungsstapel format needed). */
internal data class JournalExportPosting(
    val side: PostingSide,
    val amount: BigDecimal,
    val accountNumber: String,
    val ledgerAccountId: Uuid,
    val accountType: LedgerAccountType,
)

/**
 * One journal entry, provider-neutral -- the shared successor of the former
 * `network.lapis.cloud.server.accounting.datev.DatevSourceEntry` (Welle V1.4.5.2).
 *
 * **Welle V1.4.5.3 addition: [id].** A "pure move" of the DATEV-era `buildDatevExportRequest` was
 * NOT possible without it: `DatevSourceEntry` carried no `journal_entry.id` at all (DATEV's own
 * Buchungsstapel format never references it), but lexoffice's idempotency anchor
 * (`accounting_export_item.exported_key`) and this entry's own deterministic voucher number (see
 * `network.lapis.cloud.server.accounting.export.voucherNumber`) both need it. Added here, in the
 * SAME query shape as before (three more already-loaded `ResultRow` columns, no new join, no new
 * query) -- see [buildJournalExportRequest] KDoc "Query shape unchanged".
 */
internal data class JournalExportEntry(
    val id: Uuid,
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String?,
    val postings: List<JournalExportPosting>,
)

/** Everything a provider-specific planner needs -- built once by [buildJournalExportRequest] and
 * shared verbatim between a preview RPC and the actual send/render path, so neither can ever see a
 * different world than the other (same "one assembly function, N consumers" discipline the former
 * `DatevExportRequest` already established). [beraterNummer]/[mandantNummer] are DATEV-only fields
 * (kept here so `DatevBuchungsstapelWriter.plan` needs no separate request shape of its own) --
 * `AccountingExportPlanner` (lexoffice/sevDesk) ignores both. */
internal data class JournalExportRequest(
    val from: LocalDate,
    val to: LocalDate,
    val beraterNummer: Int?,
    val mandantNummer: Int?,
    val organizationName: String,
    val exportedBy: String,
    val generatedAt: LocalDateTime,
    val entries: List<JournalExportEntry>,
)

/**
 * Welle V1.4.5.2 "DATEV-Format-Export" (originally `network.lapis.cloud.server.accounting.datev
 * .buildDatevExportRequest`), moved and extended in Welle V1.4.5.3 "lexoffice-Live-Anbindung" into
 * this provider-neutral package -- THE single place a [JournalExportRequest] is assembled from the
 * database, for EVERY export provider (DATEV file, lexoffice live push, and the sevDesk adapter
 * V1.4.5.4 plans to add). Both `AccountingService.previewDatevExport`/the DATEV file route AND
 * `AccountingExportService`/`AccountingExportPoller` call this SAME function (each inside its own
 * `transaction {}`), so no two providers -- nor a provider's own preview and its real send -- can
 * ever see a different world.
 *
 * **Query shape unchanged from the DATEV-only era.** Reads `[from, to]` in exactly the same TWO
 * query shapes the original `buildDatevExportRequest` used (journal_entry, then posting
 * `innerJoin` ledger_account over `journalEntryId inList …`, batched -- see
 * [postingQueryChunkSize]) -- the Welle V1.4.5.3 additions ([JournalExportEntry.id],
 * [JournalExportPosting.ledgerAccountId], [JournalExportPosting.accountType]) are three more
 * columns read off the SAME already-materialized `ResultRow`s, never a new join or a second query.
 * Sort order is `entryDate ASC, id ASC` -- deterministic, golden-file-test-pinnable (DATEV) and
 * gives lexoffice a stable, reproducible send order too.
 *
 * The two DoS guards from the DATEV-only era carry over UNCHANGED and apply to every provider: the
 * entry query is capped at `maxEntries + 1` (deterministic `orderBy` + `limit`, see [maxEntries]),
 * and the running posting total across every chunk is capped at [maxTotalPostings] with the cap
 * pulled INTO the query itself via `.limit(remaining + 1)` (see the original `MAX_TOTAL_POSTINGS`
 * KDoc, continued verbatim above on [MAX_TOTAL_POSTINGS]).
 *
 * @param maxEntries entry-count cap, deliberately a PARAMETER (not silently reusing
 *   [DatevBuchungsstapelWriter.MAX_ROWS] as a literal) so this function's own semantics do not
 *   implicitly depend on a DATEV-specific CSV-row limit -- [DEFAULT_MAX_ENTRIES] happens to equal
 *   that same constant today (same numeric backstop is reasonable for every provider), but the two
 *   can diverge in the future without either caller's behaviour silently changing.
 */
internal fun buildJournalExportRequest(
    from: LocalDate,
    to: LocalDate,
    exportedBy: String,
    postingQueryChunkSize: Int = POSTING_QUERY_CHUNK_SIZE,
    maxTotalPostings: Int = MAX_TOTAL_POSTINGS,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
): JournalExportRequest {
    val orgRow =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?: throw NotFoundException("OrganizationSettings row $ORGANIZATION_SETTINGS_ID not found -- baseline seed missing?")

    val entryRows =
        JournalEntryTable
            .selectAll()
            .where {
                (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                    (JournalEntryTable.entryDate greaterEq from) and
                    (JournalEntryTable.entryDate lessEq to)
            }.orderBy(JournalEntryTable.entryDate to SortOrder.ASC, JournalEntryTable.id to SortOrder.ASC)
            .limit(maxEntries + 1)
            .toList()
    val entryIds = entryRows.map { it[JournalEntryTable.id] }

    val postingsByEntry: MutableMap<Uuid, List<JournalExportPosting>> = mutableMapOf()
    var totalPostingsLoaded = 0
    entryIds.chunked(postingQueryChunkSize).forEach { chunk ->
        val remaining = maxTotalPostings - totalPostingsLoaded
        val chunkRows =
            (PostingTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { PostingTable.journalEntryId inList chunk }
                .limit(remaining + 1)
                .toList()
        totalPostingsLoaded += chunkRows.size
        if (totalPostingsLoaded > maxTotalPostings) {
            throw ConflictException(
                "Export: Zeitraum enthält mehr als $maxTotalPostings Buchungszeilen (Postings) -- Zeitraum eingrenzen.",
            )
        }
        chunkRows
            .groupBy({ it[PostingTable.journalEntryId] }) { row ->
                JournalExportPosting(
                    side = row[PostingTable.side],
                    amount = row[PostingTable.amount],
                    accountNumber = row[LedgerAccountTable.accountNumber],
                    ledgerAccountId = row[LedgerAccountTable.id],
                    accountType = row[LedgerAccountTable.type],
                )
            }.forEach { (entryId, postings) -> postingsByEntry[entryId] = postings }
    }

    val entries =
        entryRows.map { row ->
            JournalExportEntry(
                id = row[JournalEntryTable.id],
                entryDate = row[JournalEntryTable.entryDate],
                description = row[JournalEntryTable.description],
                voucherReference = row[JournalEntryTable.voucherReference],
                postings = postingsByEntry[row[JournalEntryTable.id]].orEmpty(),
            )
        }

    return JournalExportRequest(
        from = from,
        to = to,
        beraterNummer = orgRow[OrganizationSettingsTable.datevBeraterNummer],
        mandantNummer = orgRow[OrganizationSettingsTable.datevMandantNummer],
        organizationName = orgRow[OrganizationSettingsTable.name],
        exportedBy = exportedBy,
        generatedAt = DbClock.nowLocalDateTime(),
        entries = entries,
    )
}
