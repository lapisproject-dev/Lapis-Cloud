package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MemberFinancialHistoryDto

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- read-only per-member financial history. See
 * [MemberFinancialHistoryDto] KDoc for the four-amount / no-total / exclusions design.
 */
@RpcService
interface IMemberFinancialHistoryService {
    /**
     * Ein Mitglied darf nur die eigene Historie abrufen; TREASURER/BOARD/ADMIN jedes Mitglied.
     * Anders als [IContributionService.getMemberContributionSummary] wirft diese Methode
     * [NotFoundException] für eine wohlgeformte, aber unbekannte Mitglieds-Id -- sie liest
     * `member` selbst (Name/Beitrittsdatum), nicht nur `contribution`.
     *
     * Bewusst NICHT auf [IContributionService]: die Sicht verlässt die Beitrags-Domäne
     * (journal_entry/posting/ledger_account).
     */
    suspend fun getMemberFinancialHistory(memberId: String): MemberFinancialHistoryDto
}
