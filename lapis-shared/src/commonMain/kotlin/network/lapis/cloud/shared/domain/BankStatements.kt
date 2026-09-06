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
    val warnings: List<String> = emptyList(),
)

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
