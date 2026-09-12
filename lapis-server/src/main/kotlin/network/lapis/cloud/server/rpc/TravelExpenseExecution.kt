package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- outcome of a booking
 * attempt, shared by [TravelExpenseExecution] and [TravelExpensePostingBridge] so
 * `TravelExpenseService` (the only caller of either) sees exactly one result type. [Failed.reason]
 * is a short, technical wire code (never derived from member-authored free text) --
 * `TravelExpenseLabels.travelExpensePostingErrorMessage` in the client mirrors these literals
 * wortgleich.
 */
internal sealed interface TravelExpensePostingOutcome {
    data class Posted(
        val journalEntryId: Uuid,
    ) : TravelExpensePostingOutcome

    data class Failed(
        val reason: String,
    ) : TravelExpensePostingOutcome
}

/**
 * Welle V1.4.11 -- runs inside the CALLER's already-open `transaction {}`
 * (`TravelExpenseService.decideReport`/`.retryPosting`), exactly like
 * `ContributionReliefExecution`/[network.lapis.cloud.server.dsgvo.PersonalDataContributor] --
 * never opens its own.
 *
 * **A throw here would roll back the caller's WHOLE transaction -- including the APPROVED
 * decision that was just written.** Re-checks, under the report row lock the caller already
 * holds, that the report is STILL internally consistent (every RECEIPTED line still has >= 1
 * receipt, every line amount is still positive, `total_amount` still equals the sum of the
 * lines) -- days can pass between `submitReport` and a board decision, and although
 * `TravelExpenseReportStatusSets.EDITABLE` blocks line/receipt mutation once a report leaves
 * DRAFT, this recheck is the same "never trust the pre-check performed at request time again
 * here" discipline `ContributionReliefExecution`'s own KDoc documents. Any inconsistency returns
 * [TravelExpensePostingOutcome.Failed] with the generic `report_no_longer_consistent` code rather
 * than throwing -- the caller persists that as `status = APPROVED, execution_error = <reason>`, a
 * safe, retryable resting state.
 */
internal object TravelExpenseExecution {
    fun execute(
        report: ResultRow,
        actor: CurrentMember,
        now: LocalDateTime,
    ): TravelExpensePostingOutcome {
        val reportId = report[TravelExpenseReportTable.id]
        val subjectMemberId = report[TravelExpenseReportTable.subjectMemberId]
        val totalAmount = report[TravelExpenseReportTable.totalAmount]

        val lineRows =
            TravelExpenseLineTable
                .selectAll()
                .where { TravelExpenseLineTable.reportId eq reportId }
                .toList()
        if (lineRows.isEmpty()) return TravelExpensePostingOutcome.Failed(TRAVEL_EXPENSE_REASON_INCONSISTENT)

        val lineIds = lineRows.map { it[TravelExpenseLineTable.id] }
        val receiptCounts =
            TravelExpenseReceiptTable
                .selectAll()
                .where { TravelExpenseReceiptTable.lineId inList lineIds }
                .toList()
                .groupingBy { it[TravelExpenseReceiptTable.lineId] }
                .eachCount()

        var sum = BigDecimal.ZERO
        for (row in lineRows) {
            val amount = row[TravelExpenseLineTable.amount]
            if (amount <= BigDecimal.ZERO) return TravelExpensePostingOutcome.Failed(TRAVEL_EXPENSE_REASON_INCONSISTENT)
            val kind = row[TravelExpenseLineTable.kind]
            if (kind == TravelExpenseLineKind.RECEIPTED && (receiptCounts[row[TravelExpenseLineTable.id]] ?: 0) == 0) {
                return TravelExpensePostingOutcome.Failed(TRAVEL_EXPENSE_REASON_INCONSISTENT)
            }
            sum += amount
        }
        if (sum.compareTo(totalAmount) != 0) return TravelExpensePostingOutcome.Failed(TRAVEL_EXPENSE_REASON_INCONSISTENT)

        val postingLines =
            lineRows.map { row ->
                TravelExpensePostingBridge.PostingLine(
                    kind = row[TravelExpenseLineTable.kind],
                    amount = row[TravelExpenseLineTable.amount],
                )
            }
        // Digest, not the raw purpose -- kept short and defensively truncated; the bridge's own
        // journal_entry.description carries it alongside the report id, never the raw member-
        // authored purpose beyond this excerpt.
        val purposeDigest = report[TravelExpenseReportTable.purpose].take(PURPOSE_DIGEST_MAX_LENGTH)

        return TravelExpensePostingBridge.postTravelExpenseReimbursement(
            reportId = reportId,
            subjectMemberId = subjectMemberId,
            purposeDigest = purposeDigest,
            lines = postingLines,
            totalAmount = totalAmount,
            decidedAt = now,
            actorMemberId = actor.memberId,
            actorRole = actor.role,
        )
    }
}

/** Re-check failure -- deliberately generic (never leaks WHICH invariant broke to a wire code the client parses). */
internal const val TRAVEL_EXPENSE_REASON_INCONSISTENT = "report_no_longer_consistent"

private const val PURPOSE_DIGEST_MAX_LENGTH = 120
