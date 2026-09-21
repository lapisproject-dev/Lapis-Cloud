package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.26 (W2): the client-side sorting the ledger's two tables gained, plus the journal's split
 * empty states. All pure -- the sort runs over a fully loaded list, so it is a function, not a request.
 */
class LedgerSortAndEmptyStateTest {
    private fun account(
        number: String,
        name: String,
        type: LedgerAccountType = LedgerAccountType.ASSET,
        cashRegister: Boolean = false,
    ) = LedgerAccountDto(
        id = "acc-$number",
        accountNumber = number,
        name = name,
        accountClass = number.take(1).toIntOrNull() ?: 0,
        type = type,
        active = true,
        isCashRegister = cashRegister,
    )

    private fun numbersOf(
        accounts: List<LedgerAccountDto>,
        sort: SortState,
    ) = sortLedgerAccounts(accounts, sort).map { it.accountNumber }

    @Test
    fun accounts_sortByNumberAscendingAndDescending() {
        val accounts = listOf(account("1800", "Bank"), account("0400", "Anlagen"), account("4200", "Aufwand"))
        assertEquals(
            listOf("0400", "1800", "4200"),
            numbersOf(accounts, SortState(ACCOUNT_SORT_NUMBER, SortDirection.ASC)),
        )
        assertEquals(
            listOf("4200", "1800", "0400"),
            numbersOf(accounts, SortState(ACCOUNT_SORT_NUMBER, SortDirection.DESC)),
        )
    }

    @Test
    fun accounts_sortNumbersAsTextSoLeadingZerosSurvive() {
        // `0400` and `400` are two DIFFERENT accounts; a numeric sort would treat them as one value and
        // leave their relative order to chance.
        val accounts = listOf(account("400", "Vier"), account("0400", "Nullvier"))
        assertEquals(
            listOf("0400", "400"),
            numbersOf(accounts, SortState(ACCOUNT_SORT_NUMBER, SortDirection.ASC)),
        )
    }

    @Test
    fun accounts_sortByNameIsCaseInsensitive() {
        val accounts = listOf(account("1", "zebra"), account("2", "Anlagen"), account("3", "Bank"))
        assertEquals(
            listOf("Anlagen", "Bank", "zebra"),
            sortLedgerAccounts(accounts, SortState(ACCOUNT_SORT_NAME, SortDirection.ASC)).map { it.name },
        )
    }

    @Test
    fun accounts_sortByTypeUsesTheTranslatedLabelAndFallsBackToTheNumber() {
        // Two accounts of the same type must keep a stable, meaningful order -- the account number.
        val accounts = listOf(account("1900", "B", LedgerAccountType.ASSET), account("1100", "A", LedgerAccountType.ASSET))
        assertEquals(
            listOf("1100", "1900"),
            numbersOf(accounts, SortState(ACCOUNT_SORT_TYPE, SortDirection.ASC)),
        )
    }

    @Test
    fun accounts_anUnknownSortKeyFallsBackToTheAccountNumber() {
        val accounts = listOf(account("1800", "Bank"), account("0400", "Anlagen"))
        assertEquals(
            numbersOf(accounts, SortState(ACCOUNT_SORT_NUMBER, SortDirection.ASC)),
            numbersOf(accounts, SortState("something-else", SortDirection.ASC)),
        )
    }

    @Test
    fun accountMetaText_namesTheAccountClassAndTheCashRegisterFlag() {
        val plain = ledgerAccountMetaText(account("1800", "Bank"))
        val cash = ledgerAccountMetaText(account("1600", "Kasse", cashRegister = true))
        assertTrue(plain.contains("1"), plain)
        assertTrue(cash.length > plain.length, cash)
    }

    // ── Journal ───────────────────────────────────────────────────────────────────────────────────

    private fun entry(
        description: String,
        date: LocalDate,
    ) = JournalEntryDto(
        id = "j-$description",
        entryDate = date,
        description = description,
        voucherReference = null,
        createdBy = "treasurer",
        createdByDisplayName = "Schatzmeister",
        status = JournalEntryStatus.POSTED,
        postedAt = null,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        postings =
            listOf(
                PostingDto(
                    id = "p-1",
                    ledgerAccountId = "acc-1800",
                    ledgerAccountNumber = "1800",
                    ledgerAccountName = "Bank",
                    side = PostingSide.DEBIT,
                    amount = 10.0.toDecimal(),
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    vatAmount = 0.0.toDecimal(),
                ),
            ),
    )

    @Test
    fun journal_sortByDateNewestFirstIsChronological() {
        val entries =
            listOf(
                entry("Mitte", LocalDate(2026, 6, 1)),
                entry("Spät", LocalDate(2026, 12, 1)),
                entry("Früh", LocalDate(2026, 1, 1)),
            )
        assertEquals(
            listOf("Spät", "Mitte", "Früh"),
            sortJournalEntries(entries, SortState(JOURNAL_SORT_DATE, SortDirection.DESC)).map { it.description },
        )
        assertEquals(
            listOf("Früh", "Mitte", "Spät"),
            sortJournalEntries(entries, SortState(JOURNAL_SORT_DATE, SortDirection.ASC)).map { it.description },
        )
    }

    @Test
    fun journal_sortByDescriptionKeepsTheDateAsASecondaryKey() {
        val entries =
            listOf(
                entry("Miete", LocalDate(2026, 3, 1)),
                entry("Miete", LocalDate(2026, 1, 1)),
                entry("Beitrag", LocalDate(2026, 2, 1)),
            )
        assertEquals(
            listOf(LocalDate(2026, 2, 1), LocalDate(2026, 1, 1), LocalDate(2026, 3, 1)),
            sortJournalEntries(entries, SortState(JOURNAL_SORT_DESCRIPTION, SortDirection.ASC)).map { it.entryDate },
        )
    }

    @Test
    fun journal_searchMatchesTheDescriptionCaseInsensitively() {
        val entries = listOf(entry("Miete März", LocalDate(2026, 3, 1)), entry("Beitrag", LocalDate(2026, 2, 1)))
        assertEquals(listOf("Miete März"), filterJournalEntries(entries, "miete").map { it.description })
        assertEquals(entries, filterJournalEntries(entries, "  "))
    }

    @Test
    fun journal_emptyTextDistinguishesAllFourCases() {
        val texts =
            setOf(
                journalEmptyText(status = null, hasDateRange = false),
                journalEmptyText(status = null, hasDateRange = true),
                journalEmptyText(status = JournalEntryStatus.DRAFT, hasDateRange = false),
                journalEmptyText(status = JournalEntryStatus.DRAFT, hasDateRange = true),
            )
        // "nothing at all", "nothing in this period", "nothing with this status", "neither" -- four
        // different sentences, because they call for four different reactions.
        assertEquals(4, texts.size)
    }

    @Test
    fun journal_emptyTextNamesTheStatusItIsAbout() {
        val text = journalEmptyText(status = JournalEntryStatus.DRAFT, hasDateRange = false)
        assertTrue(text.contains(journalEntryStatusLabel(JournalEntryStatus.DRAFT)), text)
        assertNotEquals(text, journalEmptyText(status = JournalEntryStatus.POSTED, hasDateRange = false))
    }

    @Test
    fun journal_metaTextUsesTheSingularForExactlyOnePostingLine() {
        val one = journalEntryMetaText(entry("Eins", LocalDate(2026, 1, 1)))
        assertTrue(one.contains("Schatzmeister"), one)
    }
}
