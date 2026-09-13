package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.payment.bankstatement.BankAccountStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IBankAccountService
import kotlin.uuid.Uuid

/** Read roles for `IBankAccountService` -- TREASURER/BOARD/ADMIN, same tier `BANK_STATEMENT_READ_ROLES` establishes. */
internal val BANK_ACCOUNT_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Write roles for `IBankAccountService` -- TREASURER/ADMIN, same narrower tier `BANK_STATEMENT_WRITE_ROLES` establishes (deliberately excludes BOARD). */
internal val BANK_ACCOUNT_WRITE_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/** RPC surface for `IBankAccountService` -- see that interface's KDoc. Thin role-gating wrapper around `BankAccountStore`. */
internal class BankAccountService(
    private val call: ApplicationCall,
) : IBankAccountService {
    override suspend fun listBankAccounts(): List<BankAccountDto> {
        resolveCurrentMember(call).requireRole(*BANK_ACCOUNT_READ_ROLES)
        return BankAccountStore.listDtos()
    }

    override suspend fun createBankAccount(input: BankAccountInput): BankAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        return BankAccountStore.create(input = input, actorMemberId = current.memberId, actorRole = current.role)
    }

    override suspend fun updateBankAccount(
        bankAccountId: String,
        input: BankAccountInput,
    ): BankAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        return BankAccountStore.update(
            bankAccountId = bankAccountId.toBankAccountUuid(),
            input = input,
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }

    override suspend fun deleteBankAccount(bankAccountId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        BankAccountStore.delete(
            bankAccountId = bankAccountId.toBankAccountUuid(),
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }

    override suspend fun setDefaultBankAccount(bankAccountId: String): List<BankAccountDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_ACCOUNT_WRITE_ROLES)
        return BankAccountStore.setDefault(
            bankAccountId = bankAccountId.toBankAccountUuid(),
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }

    private fun String.toBankAccountUuid(): Uuid =
        runCatching {
            Uuid.parse(this)
        }.getOrElse { throw BadRequestException("Invalid bankAccountId") }
}
