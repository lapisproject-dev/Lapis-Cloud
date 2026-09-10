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
)

/**
 * Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2) -- machine-readable counterpart of the three fixed
 * [BankStatementImportResultDto.warnings] strings `BankStatementImportService` can produce, same
 * "code instead of German prose" idiom [BankStatementRejectionCode] already established for
 * rejections. Unlike [BankStatementLineDto.matchExplanation] (which embeds per-line dynamic data --
 * member names, amounts, dates -- and is persisted, so a structured replacement needs its own
 * migration and is a deliberately deferred, documented gap, see README.adoc "What doesn't work yet
 * (this wave)"), these three warnings are always the exact same fixed German sentence with no
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
 */
@Serializable
enum class BankStatementRejectionCode {
    FILE_TOO_LARGE, // 413, BankStatementRoutes.kt
    NO_FILE_PART, // 400, BankStatementRoutes.kt
    RATE_LIMITED, // 429, BankStatementRoutes.kt
    FORMAT_UNRECOGNIZED, // 422, BankStatementImportService.kt
    PARSE_FAILED, // 422, throwAsRejection (BankStatementImportService.kt)
    MT940_BALANCE_MISMATCH, // 422, Mt940Parser.kt -> throwAsRejection
    FOREIGN_ACCOUNT, // 422, BankStatementImportService.kt
    TOO_MANY_LINES, // 422, BankStatementImportService.kt
    CONTROL_CHARACTER, // 422, BankStatementImportService.kt
    ALREADY_IMPORTED, // 409, BankStatementImportService.kt
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
