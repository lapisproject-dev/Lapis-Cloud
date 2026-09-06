package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.5.2 "DATEV-Format-Export". The reasons
 * `network.lapis.cloud.server.accounting.datev.DatevBuchungsstapelWriter.plan` can refuse to
 * export a period -- see that object's KDoc for the full derivation of each case. Always collected
 * as a `List<DatevExportBlockerDto>` (never short-circuited on the first hit): a treasurer should
 * see everything wrong with a period at once, not fix one blocker only to be told about the next.
 */
@Serializable
enum class DatevExportBlockerKind {
    /** The DATEV `Belegdatum` field is TTMM only (no year) -- a period spanning two calendar years
     * would make every posting's day/month ambiguous as to which year it belongs to. */
    PERIOD_CROSSES_CALENDAR_YEAR,

    /** [OrganizationSettingsDto.datevBeraterNummer]/[OrganizationSettingsDto.datevMandantNummer]
     * are both required header fields (11/12) -- unset until the Steuerberater assigns them. */
    BERATER_MANDANT_NOT_CONFIGURED,

    /** Every `Konto`/`Gegenkonto` in the period must share the SAME digit length (the DATEV header's
     * own `Sachkontenlaenge` field is a single number, not a range). */
    MIXED_ACCOUNT_NUMBER_LENGTHS,

    /** The one shared account-number length found is outside DATEV's accepted 4-8 digit range. */
    ACCOUNT_NUMBER_LENGTH_OUT_OF_RANGE,

    /**
     * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
     * MINOR): at least one `Konto`/`Gegenkonto` in the period contains a character other than an
     * ASCII digit -- `Konto`/`Gegenkonto` (Feld 7/8) are rendered UNQUOTED, so a `;`, `"`, CR or LF
     * would silently shift every following field in that data row. New accounts are rejected at
     * creation time by `network.lapis.cloud.server.rpc.AccountingService
     * .requireValidAccountNumberFormat`, but that guard did not always exist -- this blocker catches
     * an account number that predates it.
     */
    ACCOUNT_NUMBER_CONTAINS_INVALID_CHARACTERS,

    /** A journal entry has more than one DEBIT account AND more than one CREDIT account -- the
     * DATEV Buchungsstapel format has exactly one `Konto`/one `Gegenkonto` per row and cannot
     * express a genuine many-to-many split. */
    UNMAPPABLE_MANY_TO_MANY_ENTRY,

    /** No POSTED journal entries fall inside `[from, to]` -- nothing to export. */
    EMPTY_PERIOD,

    /** More rows than [network.lapis.cloud.server.accounting.datev.DatevBuchungsstapelWriter.MAX_ROWS]
     * would result -- a DoS backstop against an unbounded export, not a realistic real-world case. */
    TOO_MANY_ROWS,
}

/**
 * One blocker instance, with a human-readable [detail] naming the specific journal entries/
 * accounts/lengths involved. [detail] NEVER carries a journal entry's `description` -- this DTO is
 * returned by [network.lapis.cloud.shared.rpc.IAccountingService.previewDatevExport], which is
 * BOARD-readable (TREASURER/BOARD/ADMIN), while the actual booking text of a donation can name a
 * donor. See that method's own KDoc for the full BOARD-vs-file role split this protects.
 */
@Serializable
data class DatevExportBlockerDto(
    val kind: DatevExportBlockerKind,
    val detail: String,
)

/**
 * Dry-run summary of what a DATEV-EXTF-Buchungsstapel export for `[from, to]` would contain --
 * returned by [network.lapis.cloud.shared.rpc.IAccountingService.previewDatevExport]. Computed by
 * the EXACT same [network.lapis.cloud.server.accounting.datev.DatevBuchungsstapelWriter.plan] call
 * the real file-download route (`GET /api/accounting/datev/buchungsstapel.csv`) uses -- this DTO
 * cannot structurally claim anything the file itself would not also produce, see that route's KDoc.
 */
@Serializable
data class DatevExportPreviewDto(
    val from: LocalDate,
    val to: LocalDate,
    val entryCount: Int,
    val rowCount: Int,
    val debitTotal: Decimal,
    val creditTotal: Decimal,
    val derivedSachkontenlaenge: Int?,
    val transliteratedEntryCount: Int,
    val leadingZeroAccountCount: Int,
    val blockers: List<DatevExportBlockerDto>,
    /** `blockers.isEmpty()` -- computed server-side so the client never re-implements the rule. */
    val exportable: Boolean,
)
