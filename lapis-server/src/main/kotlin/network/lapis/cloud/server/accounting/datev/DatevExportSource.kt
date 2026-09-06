package network.lapis.cloud.server.accounting.datev

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Batch size for the `PostingTable.journalEntryId inList <chunk>` query in
 * [buildDatevExportRequest] -- comfortably under PostgreSQL's 65 535 bound-parameter limit, exactly
 * like `network.lapis.cloud.server.routes.SocialPublicSitemap.ROOT_ID_QUERY_CHUNK_SIZE`. Kept as a
 * default constructor parameter (not a hardcoded literal in the query) so tests can drive the
 * multi-chunk path with a handful of rows instead of tens of thousands.
 */
internal const val POSTING_QUERY_CHUNK_SIZE = 10_000

/**
 * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
 * MINOR, DoS/Heap): the `MAX_ROWS + 1` entry cap above bounds the number of journal ENTRIES this
 * function loads, never the number of POSTINGS -- a single Sammelbuchung entry can carry an
 * arbitrary number of postings (nothing in `AccountingService.postJournalEntry`/`saveDraftEntry`
 * caps postings-per-entry), so one pathological/compromised-account entry with, say, a million
 * postings would defeat the entry cap entirely and still pull a million [DatevSourcePosting]
 * objects into the JVM heap before [DatevBuchungsstapelWriter.plan] ever gets a chance to refuse
 * the export. [MAX_TOTAL_POSTINGS] is a hard, purely technical backstop -- chosen far above any
 * realistic legitimate period (even a very large Sammelbuchung has at most a few thousand postings)
 * but bounded well below a size that could exhaust heap -- checked as a running total ACROSS every
 * chunk, so it catches both "many entries with a few postings each" (already covered by the entry
 * cap) and "one entry with an enormous number of postings" (which the entry cap alone cannot see).
 * Exceeding it throws [ConflictException] rather than silently truncating the postings loaded for
 * an entry -- a truncated posting list would silently corrupt that entry's DATEV row grouping
 * (missing debit/credit postings), which is worse than refusing the export outright. Kept as a
 * default constructor parameter (not a literal in the loop) for the identical testability reason
 * [postingQueryChunkSize] already is -- see [buildDatevExportRequest].
 *
 * **Wiedervorlage-Fix (Review-Runde 2026-09, Runde 1 nur teilweise behoben):** the running-total
 * check alone is NOT the enforcement point -- it used to run only AFTER `.toList()` had already
 * materialized an entire chunk, so a single chunk containing one pathological Sammelbuchung (or a
 * few) still paid the full heap cost of every one of its postings before the counter below ever
 * got a chance to throw, since [postingQueryChunkSize]/[DatevBuchungsstapelWriter.MAX_ROWS] leave
 * comfortable room for an attacker to keep an arbitrarily posting-heavy period inside ONE chunk.
 * The postings query in [buildDatevExportRequest] now also carries a `.limit(remaining + 1)` sized
 * off the running total, so this constant bounds actual ResultRow materialization, not just the
 * counter that reacts to it after the fact.
 */
internal const val MAX_TOTAL_POSTINGS = 200_000

/**
 * Welle V1.4.5.2 "DATEV-Format-Export". THE single place a [DatevExportRequest] is assembled from
 * the database -- both `AccountingService.previewDatevExport` and
 * `network.lapis.cloud.server.routes.registerDatevRoutes`'s file route call this SAME function
 * (each inside its own `transaction {}`), so the preview and the real file can never see a
 * different world. `internal` top-level (not a private method on either caller) specifically so
 * neither caller has to "similarly" rebuild it -- see [DatevBuchungsstapelWriter] class KDoc.
 *
 * Reads `[from, to]` in exactly TWO query SHAPES (journal_entry, then posting `innerJoin`
 * ledger_account over `journalEntryId inList …`, batched -- see [postingQueryChunkSize]) -- NEVER
 * one `loadJournalEntry(id)` call per entry (that idiom is established elsewhere in
 * `AccountingService` for a single-entry lookup, but N+1 over a full calendar year's journal does
 * not scale). Sort order is `entryDate ASC, id ASC` -- `id`, not `createdAt`, is the deliberately
 * stable, deterministic tie-breaker a golden-file test can pin.
 *
 * **Review-Runde finding (2026-09): entry count capped at [DatevBuchungsstapelWriter.MAX_ROWS] + 1
 * BEFORE the postings are ever loaded.** [DatevBuchungsstapelWriter.plan]'s `TOO_MANY_ROWS` blocker
 * used to be the only DoS guard, but it only fires AFTER every posting for every entry in the
 * period has already been loaded and every row materialized -- for an org whose accounting
 * generates roughly one journal entry per posting (see `ContributionPostingBridge`), a large
 * period's true entry count is a reliable proxy for the eventual row count, so capping the entry
 * query itself at `MAX_ROWS + 1` (deterministic `orderBy` + `limit`, never an unordered `LIMIT`)
 * bounds the memory this function can ever pull into the JVM heap for one export, no matter how
 * many POSTED entries exist in `[from, to]`. `+ 1` (not `MAX_ROWS` itself) so a period with EXACTLY
 * `MAX_ROWS` mappable 1:1 entries still round-trips its true row count into the blocker's message
 * instead of silently looking like it was truncated.
 *
 * **Review-Runde finding (2026-09): batched `inList`, independent of the cap above.** Even under
 * the `MAX_ROWS + 1` entry cap, a single Sammelbuchung-heavy period does not change the NUMBER of
 * entry ids bound into the postings query -- but capping entries alone would still leave a future
 * increase of [DatevBuchungsstapelWriter.MAX_ROWS] free to reintroduce PostgreSQL's 65 535
 * bound-parameter ceiling. [postingQueryChunkSize] batches the `inList` exactly like
 * `network.lapis.cloud.server.routes.SocialPublicSitemap.ROOT_ID_QUERY_CHUNK_SIZE` does, so the two
 * guards are independent defences against the same underlying failure mode rather than one relying
 * on the other's current constant.
 */
internal fun buildDatevExportRequest(
    from: LocalDate,
    to: LocalDate,
    exportedBy: String,
    postingQueryChunkSize: Int = POSTING_QUERY_CHUNK_SIZE,
    maxTotalPostings: Int = MAX_TOTAL_POSTINGS,
): DatevExportRequest {
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
            .limit(DatevBuchungsstapelWriter.MAX_ROWS + 1)
            .toList()
    val entryIds = entryRows.map { it[JournalEntryTable.id] }

    val postingsByEntry: MutableMap<Uuid, List<DatevSourcePosting>> = mutableMapOf()
    var totalPostingsLoaded = 0
    entryIds.chunked(postingQueryChunkSize).forEach { chunk ->
        // Security finding fix (Review-Runde 2026-09, Wiedervorlage aus Runde 1): the
        // MAX_TOTAL_POSTINGS check below used to run AFTER `.toList()` had already
        // materialized the full chunk -- for a single pathological Sammelbuchung entry
        // (or a handful of them) landing in ONE chunk, that meant paying the full heap
        // cost of e.g. a million ResultRow objects before the guard ever got a chance to
        // throw. `.limit(remaining + 1)` pulls the cap INTO the query itself: at most
        // `maxTotalPostings - totalPostingsLoaded + 1` rows are ever fetched for this
        // chunk, so the running total (and therefore the JVM heap) can never exceed
        // `maxTotalPostings + 1` rows across the whole function, regardless of how many
        // postings a single entry or chunk actually has in the database. The `+ 1` keeps
        // the exact-boundary case (period has precisely `maxTotalPostings` postings)
        // correctly NOT throwing, while any true excess is still detected and still
        // throws below -- since this loop always throws as soon as the running total
        // exceeds the cap, `remaining` can never go negative on a later iteration.
        val remaining = maxTotalPostings - totalPostingsLoaded
        val chunkRows =
            (PostingTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { PostingTable.journalEntryId inList chunk }
                .limit(remaining + 1)
                .toList()
        totalPostingsLoaded += chunkRows.size
        if (totalPostingsLoaded > maxTotalPostings) {
            // See MAX_TOTAL_POSTINGS KDoc -- fail loud rather than silently return a truncated,
            // wrongly-grouped posting list for whichever entry happened to be mid-chunk.
            throw ConflictException(
                "DATEV-Export: Zeitraum enthält mehr als $maxTotalPostings Buchungszeilen (Postings) -- Zeitraum eingrenzen.",
            )
        }
        chunkRows
            .groupBy({ it[PostingTable.journalEntryId] }) { row ->
                DatevSourcePosting(
                    side = row[PostingTable.side],
                    amount = row[PostingTable.amount],
                    accountNumber = row[LedgerAccountTable.accountNumber],
                )
            }.forEach { (entryId, postings) -> postingsByEntry[entryId] = postings }
    }

    val entries =
        entryRows.map { row ->
            DatevSourceEntry(
                entryDate = row[JournalEntryTable.entryDate],
                description = row[JournalEntryTable.description],
                voucherReference = row[JournalEntryTable.voucherReference],
                postings = postingsByEntry[row[JournalEntryTable.id]].orEmpty(),
            )
        }

    return DatevExportRequest(
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
