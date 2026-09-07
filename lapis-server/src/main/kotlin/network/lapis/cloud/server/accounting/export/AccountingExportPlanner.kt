package network.lapis.cloud.server.accounting.export

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountingExportBlockerDto
import network.lapis.cloud.shared.domain.AccountingExportBlockerKind
import network.lapis.cloud.shared.domain.AccountingExportDirection
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/** The provider's own booking-category assignment for one ledger account -- see
 * [AccountingExportPlanner.plan] `categoryByLedgerAccount` parameter. */
internal data class MappedCategory(
    val externalCategoryId: String,
    val externalCategoryName: String?,
)

/** One entry resolved to a single voucher -- [alreadyExported] entries are still included (never
 * silently dropped) so the preview/run history can show them as skipped rather than making them
 * disappear. */
internal data class PlannedVoucher(
    val journalEntryId: Uuid,
    val entryDate: LocalDate,
    val voucherNumber: String,
    val direction: AccountingExportDirection,
    val grossAmount: BigDecimal,
    val ledgerAccountId: Uuid,
    val externalCategoryId: String?,
    val externalCategoryName: String?,
    val description: String,
    val voucherReference: String?,
    val alreadyExported: Boolean,
)

internal data class AccountingExportPlan(
    val blockers: List<AccountingExportBlockerDto>,
    /** Every entry that resolved to a determinable, non-n:m voucher -- INCLUDING already-exported
     * ones (see [PlannedVoucher.alreadyExported]) and including ones still blocked by
     * [AccountingExportBlockerKind.UNMAPPED_ACCOUNT] (their [PlannedVoucher.externalCategoryId] is
     * `null` in that case -- callers must check [exportable] before sending anything). */
    val vouchers: List<PlannedVoucher>,
    val unmappedAccounts: List<UnmappedAccountForPlan>,
    val entryCount: Int,
) {
    val alreadyExportedCount: Int get() = vouchers.count { it.alreadyExported }
    val toSendCount: Int get() = vouchers.count { !it.alreadyExported && it.externalCategoryId != null }
    val totalGross: BigDecimal get() = vouchers.fold(BigDecimal.ZERO) { acc, v -> acc + v.grossAmount }
    val exportable: Boolean get() = blockers.isEmpty()
}

internal data class UnmappedAccountForPlan(
    val ledgerAccountId: Uuid,
    val accountNumber: String,
    val accountType: LedgerAccountType,
    val entryCount: Int,
)

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung". Pure, DB-free planner -- same "pure logic extracted to
 * a sibling file, unit-testable without a database" idiom as
 * [network.lapis.cloud.server.accounting.datev.DatevBuchungsstapelWriter]. [plan] is THE authority
 * on whether a period can be exported and what it would contain -- both `previewExport` and
 * `startExport` call it via the SAME [buildJournalExportRequest] snapshot, so a preview can never
 * promise something the real send would not also produce.
 *
 * ## The one subtle divergence from the DATEV mapping this wave's plan calls out explicitly
 *
 * DATEV's `n:1`/`1:n` Sammelbuchung handling ([network.lapis.cloud.server.accounting.datev
 * .DatevBuchungsstapelWriter.plan]) expands such an entry into MULTIPLE rows, one per account on
 * the "many" side. **This planner does the OPPOSITE: every non-n:m entry becomes EXACTLY ONE
 * voucher**, because a lexoffice voucher carries exactly one `categoryId` for its total amount --
 * it cannot itemize per underlying ledger account the way a DATEV data row (one row per account)
 * can. Treating `n:1`/`1:n` as "N vouchers" the way DATEV treats "N rows" would silently multiply
 * that entry's amount into the external books N-fold.
 *
 * ## Direction derivation (see [AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE])
 *
 * After the n:m check (which runs FIRST, unconditionally -- an entry with more than one account on
 * BOTH sides is rejected before direction is ever considered, see [AccountingExportBlockerKind
 * .UNMAPPABLE_MANY_TO_MANY_ENTRY]), exactly one side of the remaining `1:1`/`n:1`/`1:n` shapes is
 * guaranteed to hold a single distinct account -- call it the "singular side". The direction is
 * derived from THAT side's [LedgerAccountType]:
 * - the singular side is CREDIT and its one account is [LedgerAccountType.INCOME], AND no DEBIT
 *   account is [LedgerAccountType.EXPENSE] -> `INCOME` (lexoffice `salesinvoice`), category =
 *   that CREDIT account's mapping.
 * - the singular side is DEBIT and its one account is [LedgerAccountType.EXPENSE], AND no CREDIT
 *   account is [LedgerAccountType.INCOME] -> `EXPENSE` (lexoffice `purchaseinvoice`), category =
 *   that DEBIT account's mapping.
 * - neither (a pure Umbuchung between two balance-sheet accounts, or both an income AND an expense
 *   account are implicated) -> [AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE].
 *
 * This only ever needs the RESOLVED income/expense account's own mapping -- a money account
 * (Bank/Kasse, [LedgerAccountType.ASSET]) on the other side never needs a
 * `accounting_export_category_map` row, deliberately narrower than "every account touched in the
 * period" (that account is never sent to lexoffice as a `categoryId` at all).
 *
 * ## Voucher number
 *
 * See [voucherNumber] -- deterministic, derived only from immutable inputs (entry date + entry
 * id), so re-planning the identical entry always yields the identical reference. Without a stable,
 * predictable reference a `UNKNOWN`-status item (see `AccountingExportPoller`) would be
 * unresolvable by a treasurer looking it up inside lexoffice itself.
 */
internal object AccountingExportPlanner {
    /** DoS backstop -- see [network.lapis.cloud.server.accounting.export.DEFAULT_MAX_ENTRIES],
     * which [buildJournalExportRequest] already caps the ENTRY QUERY at; this constant additionally
     * turns "the query silently returned the capped amount" into an explicit, user-visible blocker
     * rather than a quietly-truncated preview. */
    const val MAX_ENTRIES = DEFAULT_MAX_ENTRIES

    /** First N lines surfaced in [network.lapis.cloud.shared.domain.AccountingExportPreviewDto
     * .sampleLines] -- `totalLineCount` carries the true count for an "alle anzeigen" UI. */
    const val MAX_LISTED_LINES_IN_PREVIEW = 20

    fun plan(
        request: JournalExportRequest,
        alreadyExportedJournalEntryIds: Set<Uuid>,
        categoryByLedgerAccount: Map<Uuid, MappedCategory>,
    ): AccountingExportPlan {
        val blockers = mutableListOf<AccountingExportBlockerDto>()

        if (request.entries.isEmpty()) {
            blockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.EMPTY_PERIOD,
                    detail = "Keine gebuchten (POSTED) Buchungen im Zeitraum ${request.from} bis ${request.to}.",
                )
        }
        if (request.entries.size > MAX_ENTRIES) {
            blockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.TOO_MANY_ENTRIES,
                    detail = "Der Zeitraum enthält ${request.entries.size} Buchungen, das Limit liegt bei $MAX_ENTRIES.",
                )
        }

        val vouchers = mutableListOf<PlannedVoucher>()
        val manyToManyEntries = mutableListOf<JournalExportEntry>()
        val undeterminableEntries = mutableListOf<JournalExportEntry>()
        val unmappedByAccount = linkedMapOf<Uuid, UnmappedAccountForPlan>()

        for (entry in request.entries) {
            val debitByAccount = entry.postings.filter { it.side == PostingSide.DEBIT }.groupAmountsByAccount()
            val creditByAccount = entry.postings.filter { it.side == PostingSide.CREDIT }.groupAmountsByAccount()

            if (debitByAccount.size > 1 && creditByAccount.size > 1) {
                manyToManyEntries += entry
                continue
            }

            val creditSingular = creditByAccount.entries.singleOrNull()
            val debitSingular = debitByAccount.entries.singleOrNull()
            val debitHasExpense = debitByAccount.values.any { it.accountType == LedgerAccountType.EXPENSE }
            val creditHasIncome = creditByAccount.values.any { it.accountType == LedgerAccountType.INCOME }

            val resolved: AccountResolution? =
                when {
                    creditSingular != null && creditSingular.value.accountType == LedgerAccountType.INCOME && !debitHasExpense ->
                        AccountResolution(
                            direction = AccountingExportDirection.INCOME,
                            ledgerAccountId = creditSingular.value.ledgerAccountId,
                            accountNumber = creditSingular.key,
                            accountType = creditSingular.value.accountType,
                        )
                    debitSingular != null && debitSingular.value.accountType == LedgerAccountType.EXPENSE && !creditHasIncome ->
                        AccountResolution(
                            direction = AccountingExportDirection.EXPENSE,
                            ledgerAccountId = debitSingular.value.ledgerAccountId,
                            accountNumber = debitSingular.key,
                            accountType = debitSingular.value.accountType,
                        )
                    else -> null
                }

            if (resolved == null) {
                undeterminableEntries += entry
                continue
            }

            val grossAmount =
                entry.postings
                    .filter { it.side == PostingSide.DEBIT }
                    .fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }
                    .abs()
                    // DECIMAL(14,2) is this amount's maximum DB precision -- UNNECESSARY throws
                    // rather than silently rounding, same posture
                    // DatevBuchungsstapelWriter.formatAmount already establishes.
                    .setScale(2, RoundingMode.UNNECESSARY)

            val mapping = categoryByLedgerAccount[resolved.ledgerAccountId]
            if (mapping == null) {
                unmappedByAccount.merge(
                    resolved.ledgerAccountId,
                    UnmappedAccountForPlan(
                        ledgerAccountId = resolved.ledgerAccountId,
                        accountNumber = resolved.accountNumber,
                        accountType = resolved.accountType,
                        entryCount = 1,
                    ),
                ) { existing, addition -> existing.copy(entryCount = existing.entryCount + addition.entryCount) }
            }

            vouchers +=
                PlannedVoucher(
                    journalEntryId = entry.id,
                    entryDate = entry.entryDate,
                    voucherNumber = voucherNumber(entryDate = entry.entryDate, journalEntryId = entry.id),
                    direction = resolved.direction,
                    grossAmount = grossAmount,
                    ledgerAccountId = resolved.ledgerAccountId,
                    externalCategoryId = mapping?.externalCategoryId,
                    externalCategoryName = mapping?.externalCategoryName,
                    description = entry.description,
                    voucherReference = entry.voucherReference,
                    alreadyExported = entry.id in alreadyExportedJournalEntryIds,
                )
        }

        if (manyToManyEntries.isNotEmpty()) {
            blockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY,
                    detail = describeEntries(manyToManyEntries),
                )
        }
        if (undeterminableEntries.isNotEmpty()) {
            blockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE,
                    detail = describeEntries(undeterminableEntries),
                )
        }
        if (unmappedByAccount.isNotEmpty()) {
            blockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.UNMAPPED_ACCOUNT,
                    detail =
                        "Konten ohne Kategorie-Zuordnung: " +
                            unmappedByAccount.values.joinToString(", ") { "${it.accountNumber} (${it.entryCount}x)" },
                )
        }

        return AccountingExportPlan(
            blockers = blockers,
            vouchers = vouchers,
            unmappedAccounts = unmappedByAccount.values.toList(),
            entryCount = request.entries.size,
        )
    }

    /**
     * Deterministic, locally-assigned voucher reference -- `"LAPIS-" + entryDate(YYYYMMDD) + "-" +`
     * the first 8 hex characters of [journalEntryId]. 23 characters total. Purely a function of
     * immutable inputs, so replanning the SAME entry (a re-run preview, or a retry after a failed
     * send) always produces the SAME reference -- the number a treasurer can search for inside
     * lexoffice itself if an item ever ends up [network.lapis.cloud.shared.domain
     * .AccountingExportItemStatus.UNKNOWN].
     *
     * Collision risk: 8 hex digits is ~4.3 billion values; the birthday bound only becomes material
     * around ~65,000 vouchers issued on the SAME calendar day, far above [MAX_ENTRIES].
     */
    fun voucherNumber(
        entryDate: LocalDate,
        journalEntryId: Uuid,
    ): String =
        "LAPIS-%04d%02d%02d-%s".format(
            entryDate.year,
            entryDate.monthNumber,
            entryDate.dayOfMonth,
            journalEntryId.toHexString().take(8),
        )

    private data class AccountResolution(
        val direction: AccountingExportDirection,
        val ledgerAccountId: Uuid,
        val accountNumber: String,
        val accountType: LedgerAccountType,
    )

    private data class AccountAmount(
        val ledgerAccountId: Uuid,
        val accountType: LedgerAccountType,
        val amount: BigDecimal,
    )

    private fun List<JournalExportPosting>.groupAmountsByAccount(): Map<String, AccountAmount> =
        groupBy { it.accountNumber }.mapValues { (_, postings) ->
            AccountAmount(
                ledgerAccountId = postings.first().ledgerAccountId,
                accountType = postings.first().accountType,
                amount = postings.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount },
            )
        }

    private fun describeEntries(entries: List<JournalExportEntry>): String {
        val listed =
            entries.take(MAX_LISTED_LINES_IN_PREVIEW).joinToString("; ") { entry ->
                "Belegdatum ${entry.entryDate}" + (entry.voucherReference?.let { ", Beleg $it" } ?: "")
            }
        val more = entries.size - MAX_LISTED_LINES_IN_PREVIEW
        return if (more > 0) "$listed; … und $more weitere" else listed
    }
}
