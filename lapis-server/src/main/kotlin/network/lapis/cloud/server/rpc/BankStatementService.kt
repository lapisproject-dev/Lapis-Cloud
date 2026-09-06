package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.payment.bankstatement.BankStatementStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput
import network.lapis.cloud.shared.domain.BankStatementImportPageDto
import network.lapis.cloud.shared.domain.BankStatementLineDto
import network.lapis.cloud.shared.domain.BankStatementLinePageDto
import network.lapis.cloud.shared.domain.BankStatementLineQuery
import network.lapis.cloud.shared.domain.BankStatementMatchCandidateDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IBankStatementService
import kotlin.uuid.Uuid

/** Read roles for `IBankStatementService` -- TREASURER/BOARD/ADMIN. */
internal val BANK_STATEMENT_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Write (booking-capable) roles -- deliberately narrower than [BANK_STATEMENT_READ_ROLES]
 * (excludes BOARD). Plan OF-1: the upload/assignment path books money via
 * `ContributionPostingBridge`/`DonationPostingBridge`, and every OTHER writer of those bridges in
 * this codebase (`AccountingService.postJournalEntry`, `ContributionService
 * .generateContributionsForPeriod`) is already gated on TREASURER/ADMIN, never BOARD -- widening
 * the booking-capable role set here would be a real, unreviewed expansion of who may post to the
 * general ledger.
 */
internal val BANK_STATEMENT_WRITE_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/** RPC surface for `IBankStatementService` -- see that interface's KDoc. Thin role-gating wrapper around [BankStatementStore]; the upload itself is a Ktor route, see `network.lapis.cloud.server.routes.BankStatementRoutes`. */
internal class BankStatementService(
    private val call: ApplicationCall,
    private val secretBox: SecretBox?,
) : IBankStatementService {
    override suspend fun listImports(
        limit: Int,
        offset: Int,
    ): BankStatementImportPageDto {
        resolveCurrentMember(call).requireRole(*BANK_STATEMENT_READ_ROLES)
        return BankStatementStore.listImports(limit = limit, offset = offset)
    }

    override suspend fun listLines(query: BankStatementLineQuery): BankStatementLinePageDto {
        resolveCurrentMember(call).requireRole(*BANK_STATEMENT_READ_ROLES)
        return BankStatementStore.listLines(query)
    }

    override suspend fun suggestMatches(lineId: String): List<BankStatementMatchCandidateDto> {
        resolveCurrentMember(call).requireRole(*BANK_STATEMENT_READ_ROLES)
        return BankStatementStore.suggestMatches(lineId = lineId.toLineUuid(), secretBox = secretBox)
    }

    override suspend fun searchAssignmentTargets(
        term: String,
        limit: Int,
    ): List<BankStatementMatchCandidateDto> {
        resolveCurrentMember(call).requireRole(*BANK_STATEMENT_READ_ROLES)
        return BankStatementStore.searchAssignmentTargets(term = term, limit = limit)
    }

    override suspend fun assignLineToContribution(
        lineId: String,
        contributionId: String,
        note: String?,
    ): BankStatementLineDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_STATEMENT_WRITE_ROLES)
        return BankStatementStore.assignLineToContribution(
            lineId = lineId.toLineUuid(),
            contributionId = runCatching { Uuid.parse(contributionId) }.getOrElse { throw BadRequestException("Invalid contributionId") },
            note = note,
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }

    override suspend fun assignLineToDonation(
        lineId: String,
        input: BankStatementDonationAssignmentInput,
    ): BankStatementLineDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_STATEMENT_WRITE_ROLES)
        return BankStatementStore.assignLineToDonation(
            lineId = lineId.toLineUuid(),
            input = input,
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }

    override suspend fun ignoreLine(
        lineId: String,
        reason: String,
    ): BankStatementLineDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BANK_STATEMENT_WRITE_ROLES)
        return BankStatementStore.ignoreLine(lineId = lineId.toLineUuid(), reason = reason, actorMemberId = current.memberId)
    }

    private fun String.toLineUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw BadRequestException("Invalid lineId") }
}
