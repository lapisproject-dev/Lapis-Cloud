package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput
import network.lapis.cloud.shared.domain.BankStatementImportPageDto
import network.lapis.cloud.shared.domain.BankStatementLineDto
import network.lapis.cloud.shared.domain.BankStatementLinePageDto
import network.lapis.cloud.shared.domain.BankStatementLineQuery
import network.lapis.cloud.shared.domain.BankStatementMatchCandidateDto

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)" -- the READ + disposition surface for
 * `bank_statement_import`/`bank_statement_line`, backed by
 * `network.lapis.cloud.server.payment.bankstatement.BankStatementStore`. The upload itself is NOT
 * part of this RPC surface -- file bytes travel over the dedicated
 * `POST /api/bank-statements/import` Ktor route (see `network.lapis.cloud.server.routes
 * .BankStatementRoutes` KDoc), same "large binary payload, Kilua RPC is tuned for small typed
 * payloads" reasoning [network.lapis.cloud.shared.rpc.IDocumentService]/postal-mail routes already
 * establish.
 *
 * **Rollen-Asymmetrie, bewusst**: every read here is TREASURER/BOARD/ADMIN; every disposition write
 * ([assignLineToContribution]/[assignLineToDonation]/[ignoreLine]) is TREASURER/ADMIN -- same
 * narrower "who may actually book money" role set `AccountingService.postJournalEntry`/
 * `ContributionService.generateContributionsForPeriod` already apply (see the plan's OF-1
 * resolution).
 */
@RpcService
interface IBankStatementService {
    /** Role: TREASURER/BOARD/ADMIN. Newest-first, [limit] server-capped at 200. */
    suspend fun listImports(
        limit: Int = 20,
        offset: Int = 0,
    ): BankStatementImportPageDto

    /** Role: TREASURER/BOARD/ADMIN. [BankStatementLineQuery.limit] server-capped at 200. */
    suspend fun listLines(query: BankStatementLineQuery): BankStatementLinePageDto

    /** Role: TREASURER/BOARD/ADMIN. Candidate contributions for the (Referenz/IBAN/Name) rules -- same logic [network.lapis.cloud.server.payment.bankstatement.BankStatementMatcher] applies at import time, re-run on demand for a line an operator wants to re-check. */
    suspend fun suggestMatches(lineId: String): List<BankStatementMatchCandidateDto>

    /** Role: TREASURER/BOARD/ADMIN. Free-text search (member display name, membership tier) over open/overdue contributions -- the manual fallback when no automatic suggestion exists. [limit] server-capped at 200. */
    suspend fun searchAssignmentTargets(
        term: String,
        limit: Int = 20,
    ): List<BankStatementMatchCandidateDto>

    /** Role: TREASURER/ADMIN. Books the line's amount against [contributionId] via `ContributionPostingBridge` -- see `BankStatementImportService` KDoc "Phase 2" for the transaction shape this reuses. */
    suspend fun assignLineToContribution(
        lineId: String,
        contributionId: String,
        note: String? = null,
    ): BankStatementLineDto

    /** Role: TREASURER/ADMIN. Books the line's amount as a donation via `DonationPostingBridge` -- §25 PartG compliance still applies, see [BankStatementDonationAssignmentInput] KDoc. */
    suspend fun assignLineToDonation(
        lineId: String,
        input: BankStatementDonationAssignmentInput,
    ): BankStatementLineDto

    /** Role: TREASURER/ADMIN. Marks the line [BankStatementLineStatus.IGNORED][network.lapis.cloud.shared.domain.BankStatementLineStatus] with a mandatory [reason] -- never a booking, never reversible via this RPC ("Korrektur nur per Storno in der Buchhaltung"). */
    suspend fun ignoreLine(
        lineId: String,
        reason: String,
    ): BankStatementLineDto
}
