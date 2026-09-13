package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.14 "Mehrere Bankkonten" -- multi-account foundation, see
 * `network.lapis.cloud.server.payment.bankstatement.BankAccountStore` KDoc for the full behaviour
 * (exactly one row is always [BankAccountDto.isDefault], mirrored into
 * [OrganizationSettingsDto.bankIban]/[OrganizationSettingsDto.bankBic]) and
 * `docs/architecture/bank-account.adoc` for the deliberate scope cut ("Scope-Cut this wave: no
 * FinTS/HBCI live retrieval, only several accounts + file-import attribution").
 *
 * [iban] is returned in full (not masked) -- unlike a FinTS PIN or a counterparty's IBAN on a bank
 * statement line, an organization's OWN account IBAN is not a secret (it appears on every invoice
 * letterhead and every SEPA mandate a member signs) and an ADMIN/TREASURER editing this account
 * needs the real value to correct a typo. [ibanMasked] is kept alongside for read-only surfaces
 * that only ever need the `"DE...4711"` display form, same convention
 * [BankStatementImportDto.accountIbanMasked] already establishes.
 */
@Serializable
data class BankAccountDto(
    val id: String,
    val label: String,
    val iban: String,
    val ibanMasked: String,
    val bic: String?,
    val bankName: String?,
    val isDefault: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Role: TREASURER/ADMIN (write). [isDefault] is deliberately absent -- see [IBankAccountService.setDefaultBankAccount]. */
@Serializable
data class BankAccountInput(
    val label: String,
    val iban: String,
    val bic: String? = null,
    val bankName: String? = null,
)

/**
 * Audit-Snapshot fuer `AuditEntityType.BANK_ACCOUNT` -- traegt NIEMALS die volle IBAN, nur die
 * maskierte Form (same "before/after never repeats the sensitive value in full" convention every
 * other `*Snapshot` in this codebase already applies, e.g. `ApiKeySnapshot` never carrying the
 * token hash).
 */
@Serializable
data class BankAccountSnapshot(
    val label: String,
    val ibanMasked: String,
    val bic: String?,
    val bankName: String?,
    val isDefault: Boolean,
)
