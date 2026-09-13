package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatusSets
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val AMOUNT_SCALE = 2

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- mirrors `priorPostedDonationTotalThisYear`
 * (`AccountingService.kt`), but SIMPLER: aggregates directly over `volunteer_allowance_payment`
 * itself, not over `posting`/`ledger_account` -- the payment row is the source of truth for
 * cap-consumption, not the general ledger (a storno in the ledger would not undo the fact that the
 * member already received the money).
 *
 * **Year key is `payment_date.year` (Zuflussprinzip), NOT the booking/decision date** -- this is
 * the one deliberate difference from `priorPostedDonationTotalThisYear`, which uses
 * `journal_entry.entry_date`. A payment made on 31 December but booked in January must consume the
 * OLD year's cap, not the new one's.
 */
internal fun priorPostedAllowanceTotalThisYear(
    memberId: Uuid,
    category: VolunteerAllowanceCategory,
    year: Int,
    excludePaymentId: Uuid?,
): BigDecimal {
    val yearStart = LocalDate(year, 1, 1)
    val yearEnd = LocalDate(year, 12, 31)
    val conditions =
        mutableListOf<Op<Boolean>>(
            VolunteerAllowancePaymentTable.subjectMemberId eq memberId,
            VolunteerAllowancePaymentTable.category eq category,
            VolunteerAllowancePaymentTable.status inList VolunteerAllowancePaymentStatusSets.COUNTS_TOWARD_CAP,
            VolunteerAllowancePaymentTable.paymentDate greaterEq yearStart,
            VolunteerAllowancePaymentTable.paymentDate lessEq yearEnd,
        )
    if (excludePaymentId != null) conditions += (VolunteerAllowancePaymentTable.id neq excludePaymentId)
    return VolunteerAllowancePaymentTable
        .selectAll()
        .where { conditions.reduce { a, b -> a and b } }
        .toList()
        .fold(BigDecimal.ZERO.setScale(AMOUNT_SCALE)) { acc, row -> acc + row[VolunteerAllowancePaymentTable.amount] }
}

/**
 * Security-Fund (Welle V1.4.12, TOCTOU-Race auf dem Jahresfreibetrag): serializing region lock
 * for EVERY `volunteer_allowance_payment` row of `(memberId, category, calendar year of
 * payment_date)` -- deliberately regardless of status, not just [VolunteerAllowancePaymentStatusSets
 * .COUNTS_TOWARD_CAP] -- so two decisions about TWO DIFFERENT payments of the SAME
 * subject/category/year can never both read [priorPostedAllowanceTotalThisYear] before either
 * one's EXECUTED transition is visible to the other.
 *
 * **Why not `pg_advisory_xact_lock`**: a single advisory-lock key would avoid the multi-row
 * lock-ordering question entirely, but this codebase's test suite runs against H2
 * (`DatabaseConfig`, `MODE=PostgreSQL`), which has no such function -- a Postgres-only call here
 * would make the fix untestable outside a real Postgres. A plain `SELECT ... FOR UPDATE` is
 * portable to both.
 *
 * **Deadlock safety is why callers MUST NOT lock their own payment row individually before
 * calling this** (`VolunteerAllowanceService.decidePayment`/`.retryPosting` do an UNLOCKED peek
 * first, purely to learn `memberId`/`category`/the year, then call this, then pull their own row
 * out of the returned list): a deterministic `ORDER BY id ASC` inside ONE multi-row statement
 * means two overlapping transactions always request the same rows in the same order, so the
 * second one simply queues behind the first instead of a cycle. If either caller pre-locked its
 * OWN row (e.g. via a plain `.forUpdate()` on `id eq id`) before reaching this call, two
 * transactions targeting different payments of the same partition could each already hold the
 * row the other is about to request next -- a classic AB/BA deadlock. Must run inside an
 * already-open transaction, before the first read of [priorPostedAllowanceTotalThisYear] for this
 * `(memberId, category, year)` in that transaction -- the lock (like `.forUpdate()`) is held
 * until COMMIT/ROLLBACK, so acquiring it once, early, also protects [VolunteerAllowanceExecution]
 * .execute's own re-check later in the SAME transaction.
 *
 * Returns the locked rows so the caller can pull its own target row out of them without a
 * redundant second query on the common path (the exact `(memberId, category, year)` triple never
 * changes for a REQUESTED/APPROVED payment -- only [network.lapis.cloud.server.rpc
 * .VolunteerAllowanceService.updateDraft] can touch `paymentDate`/`category`, and only while the
 * row is still DRAFT, a status [network.lapis.cloud.server.rpc.VolunteerAllowanceService
 * .decidePayment]/`.retryPosting` never act on). Callers must still treat "the target id is
 * absent from the result" as a possibility (fall back to a direct, now-deadlock-safe single-row
 * `.forUpdate()` fetch) rather than assuming `.single { it[id] == id }` can never throw.
 */
internal fun lockAllowanceYearRows(
    memberId: Uuid,
    category: VolunteerAllowanceCategory,
    year: Int,
): List<ResultRow> {
    val yearStart = LocalDate(year, 1, 1)
    val yearEnd = LocalDate(year, 12, 31)
    return VolunteerAllowancePaymentTable
        .selectAll()
        .where {
            (VolunteerAllowancePaymentTable.subjectMemberId eq memberId) and
                (VolunteerAllowancePaymentTable.category eq category) and
                (VolunteerAllowancePaymentTable.paymentDate greaterEq yearStart) and
                (VolunteerAllowancePaymentTable.paymentDate lessEq yearEnd)
        }.orderBy(VolunteerAllowancePaymentTable.id to SortOrder.ASC)
        .forUpdate()
        .toList()
}
