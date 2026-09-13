package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.VatComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.VatComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.VatSettingsDto

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)". The gate around the whole USt-feature -- see
 * `network.lapis.cloud.server.rpc.VatService` KDoc for the implementation and
 * `network.lapis.cloud.server.rpc.VatComplianceDisclaimer` for the legal-risk disclaimer this
 * mirrors exactly from [IDunningService]/`DunningComplianceDisclaimer`.
 */
@RpcService
interface IVatService {
    // ── Gate + Rechtshinweis ────────────────────────────────────────

    /** Role: TREASURER/BOARD/ADMIN -- Lesen darf jeder, der [getVatSettings]/den Bericht sehen darf
     *  (same "no GitHub #8 repeat" reasoning [IDunningService.getDunningComplianceDisclaimer] would
     *  apply if it were not itself ADMIN-only for a different reason -- here the disclaimer TEXT
     *  itself carries no write capability, so a wider read role is safe). */
    suspend fun getVatComplianceDisclaimer(): VatComplianceDisclaimerDto

    /** Role: ADMIN. Wirft [ConflictException], wenn version/sha256 nicht der AKTUELLEN
     *  `VatComplianceDisclaimer`-Version entsprechen -- exakt [IDunningService.enableDunning]. */
    suspend fun enableVat(input: VatComplianceAcknowledgmentInput): VatSettingsDto

    /** Role: ADMIN. Keine Quittung noetig (Abschalten ist nie das Risiko).
     *  `isKleinunternehmer` bleibt dabei UNVERAENDERT stehen -- siehe [VatSettingsDto
     *  .isKleinunternehmer] KDoc. */
    suspend fun disableVat(): VatSettingsDto

    /** Role: TREASURER/BOARD/ADMIN (Lese-Rolle -- der GitHub-#8-Fehler bei
     *  `IDunningService.getDunningSettings` wird hier nicht wiederholt). */
    suspend fun getVatSettings(): VatSettingsDto
}
