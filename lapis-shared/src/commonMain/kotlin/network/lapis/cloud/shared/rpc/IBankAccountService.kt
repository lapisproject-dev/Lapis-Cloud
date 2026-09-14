package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.FinTsComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.FinTsSetupInput
import network.lapis.cloud.shared.domain.FinTsSetupResultDto

/**
 * Welle V1.4.14 "Mehrere Bankkonten" (Wave 1) + Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- CRUD +
 * default-account surface for `bank_account`, backed by
 * `network.lapis.cloud.server.payment.bankstatement.BankAccountStore`, plus the five FinTS
 * live-retrieval methods added in Wave 2 (backed by
 * `network.lapis.cloud.server.payment.fints.FinTsSetupClient`/`FinTsComplianceDisclaimer`).
 *
 * **Rollen (Wave 1 methods)**: every read is TREASURER/BOARD/ADMIN (same read tier
 * `IBankStatementService` establishes); every write reaches this RPC surface at TREASURER/ADMIN
 * (same tier `BANK_STATEMENT_WRITE_ROLES` already applies). That is deliberately COARSER than
 * `IOrganizationSettingsService.updateOrganizationSettings`, which is ADMIN-only -- a bank
 * account's default status feeds directly into `organization_settings.bank_iban`/`bank_bic`, so
 * `network.lapis.cloud.server.payment.bankstatement.BankAccountStore` (not this RPC layer) adds a
 * SECOND, narrower ADMIN-only gate for whichever specific mutation actually touches that mirror
 * (the very first account created, editing/deleting the CURRENT default, or [setDefaultBankAccount]
 * itself) -- see that store's KDoc "Autorisierung des Spiegels" (review fix, MAJOR security
 * finding: a TREASURER must not be able to repoint the ADMIN-only SEPA-creditor IBAN through this
 * side door). Ordinary multi-account bookkeeping that never touches the mirror (a second/third
 * non-default account, editing a non-default account, deleting a non-default account) stays
 * TREASURER-reachable end to end.
 *
 * **Rollen (Wave 2 FinTS methods)**: [getFinTsComplianceDisclaimer] alone stays on the Wave 1 READ
 * tier (the disclaimer text is not a secret). All FOUR FinTS-MUTATING methods
 * ([beginFinTsSetup]/[submitFinTsTan]/[cancelFinTsSetup]/[disableFinTs]) are **ADMIN-only** --
 * strictly narrower than `BANK_ACCOUNT_WRITE_ROLES` (TREASURER/ADMIN). A banking PIN outweighs the
 * IBAN mirror, for which Wave 1 already made the analogous "ADMIN, not merely TREASURER" call
 * (`requireAdminForMirrorChange`) -- see `docs/architecture/bank-account.adoc` "FinTS/HBCI live
 * retrieval (Wave 2)" for the full rationale.
 *
 * **Kein `retryFinTs`.** Reactivating an account stuck in `FinTsStatus.REAUTH_REQUIRED` *is*
 * [beginFinTsSetup] -- there is deliberately only ONE door through which FinTS credentials are ever
 * written, never a second "just retry with the stored credentials" path (which would need to
 * decrypt and reuse a PIN the ADMIN never re-confirmed).
 */
@RpcService
interface IBankAccountService {
    /** Role: TREASURER/BOARD/ADMIN. Default account first, then by label. */
    suspend fun listBankAccounts(): List<BankAccountDto>

    /** Role: TREASURER/ADMIN at this RPC gate, but ADMIN-only in the store if this is the very first account (it becomes the default automatically -- there is never zero accounts with none default). */
    suspend fun createBankAccount(input: BankAccountInput): BankAccountDto

    /** Role: TREASURER/ADMIN at this RPC gate, but ADMIN-only in the store if [bankAccountId] is the CURRENT default. Stammdaten only -- does not change [BankAccountDto.isDefault], see [setDefaultBankAccount]. */
    suspend fun updateBankAccount(
        bankAccountId: String,
        input: BankAccountInput,
    ): BankAccountDto

    /** Role: TREASURER/ADMIN at this RPC gate, but ADMIN-only in the store if [bankAccountId] is the CURRENT default. Refused (409) while at least one `bank_statement_import` still references this account. */
    suspend fun deleteBankAccount(bankAccountId: String)

    /** Role: ADMIN-only in the store (always -- this call exists solely to repoint the default). Unsets the previous default, sets this one, and mirrors iban/bic into `organization_settings`. Returns the full, freshly ordered list. */
    suspend fun setDefaultBankAccount(bankAccountId: String): List<BankAccountDto>

    /** Role: TREASURER/BOARD/ADMIN (the Wave 1 read tier -- the text is not a secret). */
    suspend fun getFinTsComplianceDisclaimer(): FinTsComplianceDisclaimerDto

    /**
     * Role: ADMIN-only. Also the reactivation path out of [network.lapis.cloud.shared.domain.FinTsStatus.REAUTH_REQUIRED]
     * -- see class KDoc "Kein retryFinTs". Returns [FinTsSetupResultDto.TanRequested] when the bank
     * needs a TAN before verification completes -- follow up with [submitFinTsTan].
     */
    suspend fun beginFinTsSetup(input: FinTsSetupInput): FinTsSetupResultDto

    /** Role: ADMIN-only. [handle] is the one [FinTsSetupResultDto.TanRequested.handle] returned from [beginFinTsSetup]; single-use, ~300s TTL. */
    suspend fun submitFinTsTan(
        handle: String,
        tan: String,
    ): FinTsSetupResultDto

    /** Role: ADMIN-only. Serverside cancel of an open TAN dialog (UI "Abbrechen") -- invalidates [handle] and discards any plaintext credentials still held for it. Idempotent. */
    suspend fun cancelFinTsSetup(handle: String)

    /** Role: ADMIN-only. Clears all four credential columns and resets status to `NOT_CONFIGURED` -- there is no fourth "disabled with credentials still around" state. */
    suspend fun disableFinTs(bankAccountId: String): BankAccountDto
}
