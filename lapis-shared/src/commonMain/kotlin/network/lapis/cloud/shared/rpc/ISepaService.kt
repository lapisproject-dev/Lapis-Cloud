package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.SepaComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.SepaSettingsDto

/**
 * SEPA-Lastschriftmandate -- Welle V1.2.1 "Zahlungs-Fundament" ships ONLY the disclaimer-
 * acknowledgment opt-in gate below (`OrganizationSettings.sepaDebitEnabled`), NO mandate/batch/
 * return functionality yet (mandate management, pain.008 generation, batch runs are V1.2.2 --
 * `grantMandate`/`revokeMandate`/`createDebitBatch`/etc. are added to THIS SAME interface then,
 * not a new one). See `network.lapis.cloud.server.rpc.SepaComplianceDisclaimer` KDoc for the full
 * mechanism and `11-organization-settings.kuml.kts` file header "Welle V1.2.1" for why the gate
 * exists now, ahead of any real functionality behind it.
 *
 * ## The `sepaDebitEnabled` gate
 *
 * Exact mirror of `IAuctionService`'s "The `auctionEnabled` gate" -- `sepaDebitEnabled` is
 * deliberately NOT part of `IOrganizationSettingsService.updateOrganizationSettings`'s writable
 * field set; it can only be flipped on via [enableSepaDebit] (requires the disclaimer
 * acknowledgment below) or off via [disableSepaDebit].
 *
 * ## The disclaimer-acknowledgment mechanism (auditable, not a bare boolean flip)
 *
 * Exact mirror of `IAuctionService`'s own mechanism -- [enableSepaDebit] requires the calling ADMIN
 * to first [getSepaComplianceDisclaimer] (the current, versioned+hashed legal-risk text) and echo
 * BOTH its `version` and `sha256` back unmodified. On success the acknowledgment is persisted as
 * its own append-only row (who/when/which version+hash). [disableSepaDebit] requires no such
 * acknowledgment and does not erase the acknowledgment history.
 */
@RpcService
interface ISepaService {
    /** Role: ADMIN. Not gated by `sepaDebitEnabled` (must be readable BEFORE the feature can be switched on). */
    suspend fun getSepaComplianceDisclaimer(): SepaComplianceDisclaimerDto

    /** Role: ADMIN. See class KDoc "The disclaimer-acknowledgment mechanism". */
    suspend fun enableSepaDebit(input: SepaComplianceAcknowledgmentInput): SepaSettingsDto

    /** Role: ADMIN. No acknowledgment required to turn the feature off. */
    suspend fun disableSepaDebit(): SepaSettingsDto

    /** Role: ADMIN. */
    suspend fun getSepaSettings(): SepaSettingsDto
}
