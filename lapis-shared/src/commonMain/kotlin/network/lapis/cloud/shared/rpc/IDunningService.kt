package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.DunningCaseDetailDto
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.DunningComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.DunningLevelDto
import network.lapis.cloud.shared.domain.DunningLevelInput
import network.lapis.cloud.shared.domain.DunningSettingsDto

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen" -- configurable dunning-level ladder, an automated
 * poller-driven escalation run (see `network.lapis.cloud.server.payment.dunning.DunningPoller`),
 * manual treasurer-driven overrides, and a PDF-generation/optional-postal-dispatch path (see
 * `network.lapis.cloud.server.pdf.MahnungPdfGenerator`).
 *
 * ## The `dunningEnabled` gate (exact mirror of `ISepaService`'s own gate)
 *
 * `dunningEnabled` is deliberately NOT part of `IOrganizationSettingsService
 * .updateOrganizationSettings`'s writable field set -- it can only be flipped on via
 * [enableDunning] (requires the disclaimer acknowledgment below) or off via [disableDunning]. See
 * `network.lapis.cloud.server.rpc.DunningComplianceDisclaimer` KDoc for the legal-risk areas named.
 *
 * ## Five independent safeguards before a real letter ever leaves the house
 *
 * (1) `LAPIS_DUNNING_POLLER_ENABLED != true` -> the poller never starts. (2) `dunningEnabled ==
 * false` -> every poller tick and every write method here is a no-op/rejects. (3) the disclaimer
 * has not been acknowledged -> every write method rejects. (4) `dunning_level` has zero active
 * rows -> nothing to escalate to. (5) postal dispatch additionally needs
 * `LAPIS_DUNNING_POSTAL_DISPATCH_ENABLED` AND `OrganizationSettingsDto.postalMailEnabled`. See
 * `network.lapis.cloud.server.payment.dunning.DunningConfig`/`DunningPoller` KDoc for the full
 * mechanism.
 *
 * ## Out of scope this wave (see CHANGELOG "Known gaps")
 *
 * No accounting booking of the dunning fee (mirrors `sepa_return.return_fee`'s own "recorded, not
 * booked" precedent). No interest-on-arrears calculation, no collections/Inkasso escalation beyond
 * the configured ladder. No KVision admin UI -- backend-only this wave, same staged rollout
 * `ISepaService`'s own V1.2.1/V1.2.2 split already established.
 */
@RpcService
interface IDunningService {
    // ── Gate + Rechtshinweis (ADMIN) ───────────────────────────────
    suspend fun getDunningComplianceDisclaimer(): DunningComplianceDisclaimerDto

    suspend fun enableDunning(input: DunningComplianceAcknowledgmentInput): DunningSettingsDto

    suspend fun disableDunning(): DunningSettingsDto

    suspend fun getDunningSettings(): DunningSettingsDto

    // ── Mahnstufen-Konfiguration (ADMIN) ───────────────────────────
    suspend fun listDunningLevels(includeInactive: Boolean = false): List<DunningLevelDto>

    suspend fun createDunningLevel(input: DunningLevelInput): DunningLevelDto

    suspend fun updateDunningLevel(
        levelId: String,
        input: DunningLevelInput,
    ): DunningLevelDto

    suspend fun deactivateDunningLevel(levelId: String): DunningLevelDto

    // ── Uebersicht (TREASURER/BOARD/ADMIN) ──────────────────────────
    suspend fun listDunningCases(
        onlyOpen: Boolean = true,
        limit: Int = 50,
        beforeDueDate: LocalDate? = null,
    ): List<DunningCaseDto>

    /** Empty list instead of a nullable DTO return -- kilua-rpc-KSP cannot generate a nullable DTO return. */
    suspend fun getDunningCase(contributionId: String): List<DunningCaseDetailDto>

    // ── Manuelle Steuerung (TREASURER/ADMIN) ───────────────────────
    suspend fun issueDunningNotice(contributionId: String): DunningCaseDetailDto

    suspend fun skipDunningLevel(
        contributionId: String,
        reason: String,
    ): DunningCaseDetailDto

    suspend fun resetDunning(
        contributionId: String,
        reason: String,
    ): DunningCaseDetailDto

    suspend fun cancelDunningNotice(
        noticeId: String,
        reason: String,
    ): DunningCaseDetailDto
}
