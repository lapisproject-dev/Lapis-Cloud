package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)". Literal order load-bearing
 * (`BankStatementSchemaDriftTest` pins it against `40-bank-statement.kuml.kts`'s
 * `bankStatementFormat` enum).
 */
@Serializable
enum class BankStatementFormat { CSV, MT940 }

/**
 * Server-internal-only concept surfaced to the client purely for display (which bank export
 * shape a CSV import was recognized as) -- NOT modelled as a kUML enum (see
 * `network.lapis.cloud.server.payment.bankstatement.BankCsvDialect` KDoc for why the DB column is a
 * plain `VARCHAR(32)`, not an enum-backed one). `GENERIC` is the fallback dialect for any
 * institute whose CSV export is not (yet) catalogued by name.
 */
@Serializable
enum class BankCsvDialect { SPARKASSE_CAMT, VR_BANK, DKB, POSTBANK, COMDIRECT, GENERIC }

/**
 * Literal order load-bearing, same reason as [BankStatementFormat]. A non-positive
 * [BankStatementLineDto.amount] is always [IGNORED] (an outgoing payment is never a membership-fee
 * receipt) -- see `network.lapis.cloud.server.payment.bankstatement.BankStatementMatcher` KDoc.
 */
@Serializable
enum class BankStatementLineStatus { UNMATCHED, SUGGESTED, AMBIGUOUS, POSTED, IGNORED }

/** Role: TREASURER/ADMIN. Returned synchronously from the multipart upload route -- see `network.lapis.cloud.server.routes.BankStatementRoutes` KDoc. */
@Serializable
data class BankStatementImportResultDto(
    val importId: String,
    val format: BankStatementFormat,
    val dialect: BankCsvDialect,
    /** `"DE...4711"` -- never the full IBAN, see [BankStatementLineDto.counterpartyIbanMasked]'s own KDoc for the masking convention. */
    val accountIbanMasked: String?,
    val statementFrom: LocalDate?,
    val statementTo: LocalDate?,
    /** `true` iff an MT940 statement's own `Σ(:61:) + opening == closing` balance check passed. Always `true` for a CSV import (no statement-level balance to check). */
    val balanceChecked: Boolean,
    val lineCount: Int,
    val duplicateCount: Int,
    val autoPostedCount: Int,
    val ambiguousCount: Int,
    val unmatchedCount: Int,
    val ignoredCount: Int,
    val suggestedCount: Int,
    /**
     * Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): technical German prose, kept ONLY as the
     * server-internal/audit counterpart of [BankStatementImportRejectionDto.detail] -- NEVER
     * rendered by the client (see [BankStatementImportScreen.renderResultBanner]). The DISPLAYED
     * message always comes from [warningCodes] via `bankStatementImportWarningMessage` in
     * `BankStatementLabels.kt`, same "code travels, detail never renders" split
     * [BankStatementRejectionCode] already established for the rejection path.
     */
    val warnings: List<String> = emptyList(),
    val warningCodes: List<BankStatementImportWarningCode> = emptyList(),
    /** Welle V1.4.14 "Mehrere Bankkonten". `null` when no `bank_account` row exists yet (legacy single-account behaviour) or none could be attributed. Default value keeps this additive to existing serialization. */
    val bankAccountId: String? = null,
    val bankAccountLabel: String? = null,
)

/**
 * Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2) -- machine-readable counterpart of the fixed
 * [BankStatementImportResultDto.warnings] strings `BankStatementImportService` can produce, same
 * "code instead of German prose" idiom [BankStatementRejectionCode] already established for
 * rejections. Unlike [BankStatementLineDto.matchExplanation] (which embeds per-line dynamic data --
 * member names, amounts, dates -- and is persisted, so a structured replacement needs its own
 * migration and is a deliberately deferred, documented gap, see README.adoc "What doesn't work yet
 * (this wave)"), these warnings are always one of a small, fixed set of German sentences with no
 * dynamic parts, so a plain enum is enough here -- no follow-up wave needed.
 */
@Serializable
enum class BankStatementImportWarningCode {
    /** No `organization_settings.bank_iban` configured -- the account-ownership check (§ FOREIGN_ACCOUNT) was skipped entirely. */
    NO_BANK_ACCOUNT_CONFIGURED,

    /** The statement's own account identifier did not parse as a valid IBAN (legacy/foreign format) -- the account-ownership check was skipped for THIS import. */
    LEGACY_ACCOUNT_IBAN_FORMAT,

    /** `LAPIS_SECRET_ENCRYPTION_KEY` is not configured -- R2 (IBAN match against SEPA mandates) never runs. Deliberately code-only: the raw warning string used to name the env var in the UI, an internal detail no treasurer needs. */
    IBAN_MATCHING_UNAVAILABLE,

    /**
     * Review fix (MAJOR, Welle V1.4.14 "Mehrere Bankkonten", findings #2 + #4). At least one
     * `bank_account` row exists, but the statement carried no explicit account id and no usable
     * account IBAN to match against -- the import was silently attributed to the DEFAULT account
     * instead. Deliberately its own code rather than reusing [LEGACY_ACCOUNT_IBAN_FORMAT]: that
     * code's client-rendered label ("account check skipped") is accurate for the zero-`bank_account`
     * legacy path but was WRONG here, where an attribution actually happened. Fires whether the
     * statement carried no identifier at all (the common CSV case) or an unparseable legacy-format
     * one -- both are "silently picked a default" from the treasurer's point of view.
     */
    ATTRIBUTED_TO_DEFAULT_ACCOUNT,
}

/** Role: TREASURER/BOARD/ADMIN (read). */
@Serializable
data class BankStatementImportDto(
    val id: String,
    val format: BankStatementFormat,
    val dialect: BankCsvDialect,
    val fileName: String,
    val accountIbanMasked: String?,
    val statementFrom: LocalDate?,
    val statementTo: LocalDate?,
    val lineCount: Int,
    val duplicateCount: Int,
    val autoPostedCount: Int,
    val uploadedByDisplayName: String?,
    val uploadedAt: LocalDateTime,
    /** Welle V1.4.14 "Mehrere Bankkonten". See [BankStatementImportResultDto.bankAccountId] KDoc. */
    val bankAccountId: String? = null,
    val bankAccountLabel: String? = null,
)

@Serializable
data class BankStatementImportPageDto(
    val rows: List<BankStatementImportDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/** Role: TREASURER/BOARD/ADMIN (read). */
@Serializable
data class BankStatementLineDto(
    val id: String,
    val importId: String,
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val amount: Decimal,
    val currency: String,
    val counterpartyName: String?,
    /** `"DE...4711"` -- never the full IBAN. `null` whenever the line carries no counterparty IBAN at all. */
    val counterpartyIbanMasked: String?,
    val purpose: String?,
    val endToEndReference: String?,
    val bookingText: String?,
    val status: BankStatementLineStatus,
    val matchExplanation: String?,
    val matchedContributionId: String?,
    val paymentTransactionId: String?,
    val resolvedByDisplayName: String?,
    val resolvedAt: LocalDateTime?,
    val resolutionNote: String?,
)

/** Role: TREASURER/BOARD/ADMIN. `limit` is server-capped at 200 regardless of the requested value. */
@Serializable
data class BankStatementLineQuery(
    val importId: String? = null,
    val status: BankStatementLineStatus? = null,
    val includeNonPositiveAmounts: Boolean = false,
    val limit: Int = 50,
    val offset: Int = 0,
)

@Serializable
data class BankStatementLinePageDto(
    val rows: List<BankStatementLineDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/** One candidate the treasurer can assign a [BankStatementLineDto] to -- either a member/contribution pair or a free-text search hit. */
@Serializable
data class BankStatementMatchCandidateDto(
    val contributionId: String,
    val memberDisplayName: String,
    val membershipTierName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amountDue: Decimal,
    val explanation: String,
)

/**
 * Role: TREASURER/ADMIN. Exactly one of [donorMemberId]/[externalDonorId] must be set -- validated
 * server-side (mirrors [DonorCategory] usage at every other donation-posting call site in this
 * codebase). §25 PartG donation compliance still applies -- see
 * `network.lapis.cloud.server.rpc.DonationPostingBridge.postDonationPayment` KDoc.
 */
@Serializable
data class BankStatementDonationAssignmentInput(
    val donorMemberId: String? = null,
    val externalDonorId: String? = null,
    val donorCategory: DonorCategory,
    val note: String? = null,
)

/**
 * Welle V1.4.5.1.1 -- maschinenlesbare Ablehnungsursache der Upload-Route
 * `POST /api/bank-statements/import`. Bewusst KEIN kUML-Modell-Enum (keine DB-Spalte, reines
 * Transport-Vokabular) -- gleiche Begründung wie [BankCsvDialect].
 *
 * Existiert, weil der Server diese Zustände immer schon unterschieden hat und sie bis V1.4.5.1
 * nur als deutsche Prosa formatiert hat (`bodyParts.joinToString(" -- ")`). Ein polnischsprachiger
 * Schatzmeister bekam die einzige Meldung, die ihn interessiert, unübersetzbar.
 *
 * [INVALID_BANK_ACCOUNT_ID]/[UNKNOWN_BANK_ACCOUNT] (Review fix, MINOR, Review Round 3) were split
 * out of [FOREIGN_ACCOUNT], which all three used to share -- three fachlich distinct rejections (a
 * malformed client-sent id; an id/IBAN matching no configured account at all; a statement genuinely
 * belonging to a DIFFERENT, existing account) rendering the same "gehört zu einem anderen Konto"
 * label, which for the first two sent the Kassenwart looking for the cause in the wrong place. See
 * `network.lapis.cloud.client.bankStatementRejectionMessage` KDoc for the corrected client labels.
 */
@Serializable
enum class BankStatementRejectionCode {
    FILE_TOO_LARGE, // 413, BankStatementRoutes.kt
    NO_FILE_PART, // 400, BankStatementRoutes.kt
    RATE_LIMITED, // 429, BankStatementRoutes.kt
    FORMAT_UNRECOGNIZED, // 422, BankStatementImportService.kt
    PARSE_FAILED, // 422, throwAsRejection (BankStatementImportService.kt)
    MT940_BALANCE_MISMATCH, // 422, Mt940Parser.kt -> throwAsRejection
    FOREIGN_ACCOUNT, // 422, BankStatementImportService.kt -- statement belongs to a DIFFERENT, EXISTING account
    TOO_MANY_LINES, // 422, BankStatementImportService.kt
    CONTROL_CHARACTER, // 422, BankStatementImportService.kt
    ALREADY_IMPORTED, // 409, BankStatementImportService.kt
    INVALID_BANK_ACCOUNT_ID, // 400, BankStatementRoutes.kt -- bankAccountId is not a well-formed UUID
    UNKNOWN_BANK_ACCOUNT, // 422, BankStatementImportService.kt -- no configured account matches at all
}

/**
 * Antwortkörper JEDER Ablehnung der Upload-Route -- auch der drei Pfade, die vor V1.4.5.1.1
 * Plaintext lieferten (413/400/429), damit der Client genau EINEN Parse-Pfad hat.
 *
 * [rawLineExcerpt] ist eine echte Kontoauszugszeile (Name, Betrag, Verwendungszweck). Der Server
 * persistiert sie NIE (siehe `BankStatementParseException` KDoc "Privacy"); der Client darf sie
 * ausschliesslich transient in der Fehlerflaeche zeigen -- nie in einem Toast, nie in
 * `localStorage`, nie in der URL.
 *
 * [detail] transportiert weiterhin den deutschen Servertext als technisches Beiwerk. Die
 * ANGEZEIGTE Meldung kommt in allen Faellen aus dem Client-Katalog, nie aus diesem Feld.
 */
@Serializable
data class BankStatementImportRejectionDto(
    val code: BankStatementRejectionCode,
    val lineNumber: Int? = null,
    val rawLineExcerpt: String? = null,
    val observedHeaderFields: List<String>? = null,
    val detail: String? = null,
)
