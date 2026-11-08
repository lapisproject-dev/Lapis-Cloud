package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgLedgerRules
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Debit and credit sums of one ledger account in whole cents, plus the number of postings that make them up. */
internal data class PartgDebitCredit(
    val debitCents: Long,
    val creditCents: Long,
    val postingCount: Int,
)

/**
 * V1.9.289 (R4a) -- the ONLY reader of the booking tables (`journal_entry`, `posting`) in the party-report code. The package `partg/report` touches
 * no table at all. Reads POSTED entries only; "federal" is `regional_chapter_id IS NULL`, a state unit is its chapter.
 *
 * Amounts are converted with `movePointRight(2).longValueExact()` (the column is DECIMAL(15,2), so nothing is rounded) and every sum is kept
 * within [PartgLedgerRules.MAX_CENTS]; a sum beyond it is a clean [PartyReportInvalidInputException] -- never a silent cap and never a 500.
 */
internal object PartgLedgerReader {
    private fun chapterCondition(chapterId: Uuid?): Op<Boolean> =
        if (chapterId == null) JournalEntryTable.regionalChapterId.isNull() else JournalEntryTable.regionalChapterId eq chapterId

    private fun toCents(amount: BigDecimal): Long {
        val cents =
            try {
                amount.movePointRight(2).longValueExact()
            } catch (e: ArithmeticException) {
                throw PartyReportInvalidInputException("Booking amount out of range")
            }
        if (cents < 0L || cents > PartgLedgerRules.MAX_CENTS) throw PartyReportInvalidInputException("Booking amount out of range")
        return cents
    }

    private fun sums(
        from: LocalDate?,
        to: LocalDate,
        chapterId: Uuid?,
    ): Map<Uuid, PartgDebitCredit> {
        val amountSum = PostingTable.amount.sum()
        val postingCount = PostingTable.id.count()
        val debit = HashMap<Uuid, Long>()
        val credit = HashMap<Uuid, Long>()
        val counts = HashMap<Uuid, Int>()
        (PostingTable innerJoin JournalEntryTable)
            .select(PostingTable.ledgerAccountId, PostingTable.side, amountSum, postingCount)
            .where {
                var condition: Op<Boolean> =
                    (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                        (JournalEntryTable.entryDate lessEq to) and
                        chapterCondition(chapterId)
                if (from != null) condition = condition and (JournalEntryTable.entryDate greaterEq from)
                condition
            }.groupBy(PostingTable.ledgerAccountId, PostingTable.side)
            .forEach { row ->
                val accountId = row[PostingTable.ledgerAccountId]
                val cents = toCents(row[amountSum] ?: BigDecimal.ZERO)
                val target = if (row[PostingTable.side] == PostingSide.DEBIT) debit else credit
                target[accountId] = Math.addExact(target[accountId] ?: 0L, cents)
                counts[accountId] = (counts[accountId] ?: 0) + row[postingCount].toInt()
                if (target.getValue(accountId) >
                    PartgLedgerRules.MAX_CENTS
                ) {
                    throw PartyReportInvalidInputException("Booking sum out of range")
                }
            }
        return (debit.keys + credit.keys).associateWith {
            PartgDebitCredit(debitCents = debit[it] ?: 0L, creditCents = credit[it] ?: 0L, postingCount = counts[it] ?: 0)
        }
    }

    /** The flows of [year] per account (the year's postings only), for the INCOME and EXPENSE positions. */
    fun flowsByAccount(
        year: Int,
        chapterId: Uuid?,
    ): Map<Uuid, PartgDebitCredit> = sums(from = LocalDate(year, 1, 1), to = LocalDate(year, 12, 31), chapterId = chapterId)

    /** The cumulative balances per account up to and including [asOf], for the ASSET and LIABILITY positions. */
    fun balancesByAccount(
        asOf: LocalDate,
        chapterId: Uuid?,
    ): Map<Uuid, PartgDebitCredit> = sums(from = null, to = asOf, chapterId = chapterId)

    /** One ledger account as the report code needs it. */
    data class AccountRow(
        val id: Uuid,
        val number: String,
        val name: String,
        val type: LedgerAccountType,
        val active: Boolean,
    )

    fun accounts(): List<AccountRow> =
        LedgerAccountTable.selectAll().map {
            AccountRow(
                id = it[LedgerAccountTable.id],
                number = it[LedgerAccountTable.accountNumber],
                name = it[LedgerAccountTable.name],
                type = it[LedgerAccountTable.type],
                active = it[LedgerAccountTable.active],
            )
        }

    /** The number of POSTED entries of the unit in [year]. */
    fun postedCount(
        year: Int,
        chapterId: Uuid?,
    ): Int = postedCountsByChapter(year)[chapterId] ?: 0

    /** The number of POSTED entries per chapter in [year]; the federal level is the key `null`. */
    fun postedCountsByChapter(year: Int): Map<Uuid?, Int> {
        val entryCount = JournalEntryTable.id.count()
        return JournalEntryTable
            .select(JournalEntryTable.regionalChapterId, entryCount)
            .where {
                (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                    (JournalEntryTable.entryDate greaterEq LocalDate(year, 1, 1)) and
                    (JournalEntryTable.entryDate lessEq LocalDate(year, 12, 31))
            }.groupBy(JournalEntryTable.regionalChapterId)
            .associate { it[JournalEntryTable.regionalChapterId] to it[entryCount].toInt() }
    }
}
