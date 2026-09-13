package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput

/**
 * Welle V1.4.14 "Mehrere Bankkonten" -- CRUD + default-account surface for `bank_account`, backed
 * by `network.lapis.cloud.server.payment.bankstatement.BankAccountStore`.
 *
 * **Scope-Cut this wave**: FinTS/HBCI live retrieval is deliberately NOT part of this interface --
 * see `docs/architecture/bank-account.adoc` "Scope" for the license-decision (LGPL-2.1
 * hbci4j-core) this codebase has not yet made. Every account created through [createBankAccount]
 * is file-import-only; a follow-up wave adds the live-fetch surface once that decision is made.
 *
 * **Rollen**: every read is TREASURER/BOARD/ADMIN (same read tier `IBankStatementService`
 * establishes); every write reaches this RPC surface at TREASURER/ADMIN (same tier
 * `BANK_STATEMENT_WRITE_ROLES` already applies). That is deliberately COARSER than
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
}
