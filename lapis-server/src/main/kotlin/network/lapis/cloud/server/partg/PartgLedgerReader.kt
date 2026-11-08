package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.DonorCategory
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
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

/** V1.9.293 (R5b): the yearly donation sum of ONE donor of the federal level. [donorMemberId] xor [externalDonorId] is set. */
internal data class PartgDonationTotal(
    val donorMemberId: Uuid?,
    val externalDonorId: Uuid?,
    val categories: Set<DonorCategory>,
    /** Signed: a reversal lowers the sum. */
    val totalCents: Long,
)

/** V1.9.293 (R5b): all donors of the federal level in one year, plus the number of donation bookings without any donor. */
internal data class PartgDonationYear(
    val totals: List<PartgDonationTotal>,
    val withoutDonorCount: Int,
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

    /**
     * V1.9.293 (R5b): the donation sums per donor of the FEDERAL level (`regional_chapter_id IS NULL`) in [year]: POSTED entries with a
     * `donor_category` that is not `ANONYMOUS`, each entry's amount being the signed sum of its INCOME postings (the same figure as
     * `AccountingService.getDonationDutyReport`; `PartgReportR5bScenarios` pins the parity). More than [maxEntries] entries is a clean
     * [PartyReportInvalidInputException] ("TOO_MANY_BOOKINGS"). Never logs and never puts a donor or an amount into an exception.
     */
    fun federalDonationTotals(
        year: Int,
        maxEntries: Int = 200_000,
    ): PartgDonationYear {
        val entries =
            JournalEntryTable
                .select(
                    JournalEntryTable.id,
                    JournalEntryTable.donorMemberId,
                    JournalEntryTable.externalDonorId,
                    JournalEntryTable.donorCategory,
                ).where {
                    (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                        (JournalEntryTable.entryDate greaterEq LocalDate(year, 1, 1)) and
                        (JournalEntryTable.entryDate lessEq LocalDate(year, 12, 31)) and
                        JournalEntryTable.donorCategory.isNotNull() and
                        (JournalEntryTable.donorCategory neq DonorCategory.ANONYMOUS) and
                        JournalEntryTable.regionalChapterId.isNull()
                }.limit(maxEntries + 1)
                .toList()
        if (entries.size > maxEntries) throw PartyReportInvalidInputException("TOO_MANY_BOOKINGS")
        if (entries.isEmpty()) return PartgDonationYear(totals = emptyList(), withoutDonorCount = 0)
        val incomeByEntry = HashMap<Uuid, BigDecimal>()
        entries.map { it[JournalEntryTable.id] }.chunked(ID_CHUNK).forEach { ids ->
            (PostingTable innerJoin LedgerAccountTable)
                .select(PostingTable.journalEntryId, PostingTable.side, PostingTable.amount)
                .where { (PostingTable.journalEntryId inList ids) and (LedgerAccountTable.type eq LedgerAccountType.INCOME) }
                .forEach { row ->
                    val amount = row[PostingTable.amount]
                    // INCOME is a credit-normal account: a credit adds, a debit (a reversal) subtracts
                    val signed = if (row[PostingTable.side] == PostingSide.CREDIT) amount else amount.negate()
                    incomeByEntry.merge(row[PostingTable.journalEntryId], signed, BigDecimal::add)
                }
        }

        class Acc(
            val member: Uuid?,
            val external: Uuid?,
        ) {
            var sum: BigDecimal = BigDecimal.ZERO
            val categories = HashSet<DonorCategory>()
        }
        val byDonor = LinkedHashMap<String, Acc>()
        var withoutDonor = 0
        entries.forEach { row ->
            val member = row[JournalEntryTable.donorMemberId]
            val external = row[JournalEntryTable.externalDonorId]
            if (member == null && external == null) {
                withoutDonor += 1
                return@forEach
            }
            val key = if (member != null) "M:$member" else "X:$external"
            val acc = byDonor.getOrPut(key) { Acc(member = member, external = if (member != null) null else external) }
            acc.sum = acc.sum.add(incomeByEntry[row[JournalEntryTable.id]] ?: BigDecimal.ZERO)
            acc.categories += row[JournalEntryTable.donorCategory]!!
        }
        val totals =
            byDonor.values.map { acc ->
                val cents =
                    try {
                        acc.sum.movePointRight(2).longValueExact()
                    } catch (e: ArithmeticException) {
                        throw PartyReportInvalidInputException("Booking amount out of range")
                    }
                if (cents > PartgLedgerRules.MAX_CENTS || cents < -PartgLedgerRules.MAX_CENTS) {
                    throw PartyReportInvalidInputException("Booking sum out of range")
                }
                PartgDonationTotal(
                    donorMemberId = acc.member,
                    externalDonorId = acc.external,
                    categories = acc.categories.toSet(),
                    totalCents = cents,
                )
            }
        return PartgDonationYear(totals = totals, withoutDonorCount = withoutDonor)
    }

    private const val ID_CHUNK = 1000
}
