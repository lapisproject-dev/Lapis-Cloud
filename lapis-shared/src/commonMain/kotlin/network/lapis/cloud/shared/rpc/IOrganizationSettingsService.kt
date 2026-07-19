package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput

/**
 * The issuing association's own letterhead/bank/Gemeinnuetzigkeit settings (V0.4.1 Serienbrief/
 * PDF engine) -- see [OrganizationSettingsDto] KDoc for the full field rationale and the
 * exactly-one-row convention. Read is available to the treasury/board tier that also reads
 * [IAccountingService] ([network.lapis.cloud.shared.domain.AccountRole.TREASURER]/
 * [network.lapis.cloud.shared.domain.AccountRole.BOARD]/
 * [network.lapis.cloud.shared.domain.AccountRole.ADMIN]); write is ADMIN-only, since letterhead/
 * bank/tax-exemption data is org-wide configuration, not routine treasury business.
 */
@RpcService
interface IOrganizationSettingsService {
    /** Role: TREASURER/BOARD/ADMIN. Always returns the single seeded row. */
    suspend fun getOrganizationSettings(): OrganizationSettingsDto

    /** Role: ADMIN. Replaces every field of the single seeded row wholesale. */
    suspend fun updateOrganizationSettings(input: OrganizationSettingsInput): OrganizationSettingsDto
}
