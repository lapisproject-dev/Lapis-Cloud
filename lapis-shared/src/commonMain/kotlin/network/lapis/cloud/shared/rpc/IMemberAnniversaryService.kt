package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MemberAnniversaryOverviewDto

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- reine Lese-/Reporting-Ansicht für den Vorstand, siehe
 * [MemberAnniversaryOverviewDto] KDoc. BOARD/ADMIN-only, KEINE Selbstauskunft (anders als
 * [IMemberFinancialHistoryService]) -- es gibt keinen fachlichen Grund, einem Mitglied die
 * GESAMMELTE Geburtstagsliste aller anderen Mitglieder zu zeigen.
 */
@RpcService
interface IMemberAnniversaryService {
    /**
     * [windowDays] muss in 1..[network.lapis.cloud.shared.domain.AnniversaryCalendar.MAX_WINDOW_DAYS]
     * liegen, sonst [BadRequestException] -- eine Serverinvariante, kein Client-Komfort (Kay/Zhuo im
     * Design-Review: der Deckel ist eine Sicherheitsgrenze gegen unbegrenzten PII-Massenabzug, nicht
     * nur eine Dropdown-Empfehlung).
     */
    suspend fun getUpcomingAnniversaries(windowDays: Int): MemberAnniversaryOverviewDto
}
