package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/**
 * The single seeded [OrganizationSettingsTable] row's fixed id -- see
 * `lapis-server/src/main/resources/db/migration/V1__baseline.sql`'s unconditional seed `INSERT`
 * (not gated behind `LAPIS_SEED_DEMO_DATA`, unlike `network.lapis.cloud.server.db.DevSeedData`'s
 * own sentinel ids -- letterhead data existing at all is a real capability precondition, not
 * demo/sample data) and `11-organization-settings.kuml.kts`'s file header for the full
 * exactly-one-row-by-convention rationale.
 */
val ORGANIZATION_SETTINGS_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f2")

/**
 * Implements [IOrganizationSettingsService] -- see that interface's KDoc. There is no create/
 * delete; both [getOrganizationSettings] and [updateOrganizationSettings] always target the one
 * row seeded at [ORGANIZATION_SETTINGS_ID].
 */
class OrganizationSettingsService(
    private val call: ApplicationCall,
) : IOrganizationSettingsService {
    override suspend fun getOrganizationSettings(): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*READ_ROLES)
        return transaction { loadOrganizationSettings() }
    }

    override suspend fun updateOrganizationSettings(input: OrganizationSettingsInput): OrganizationSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[name] = input.name
                it[street] = input.street
                it[postalCode] = input.postalCode
                it[city] = input.city
                it[country] = input.country
                it[bankIban] = input.bankIban
                it[bankBic] = input.bankBic
                it[taxExemptionAuthority] = input.taxExemptionAuthority
                it[taxExemptionDate] = input.taxExemptionDate
                it[isPoliticalParty] = input.isPoliticalParty
            }
            loadOrganizationSettings()
        }
    }
}

private fun loadOrganizationSettings(): OrganizationSettingsDto =
    OrganizationSettingsTable
        .selectAll()
        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
        .singleOrNull()
        ?.toOrganizationSettingsDto()
        ?: throw NotFoundException("OrganizationSettings row $ORGANIZATION_SETTINGS_ID not found -- baseline seed missing?")

/**
 * Single shared mapper for the whole codebase -- also reused by
 * [network.lapis.cloud.server.routes.registerMailmergeRoutes] (via its private
 * `loadOrganizationSettingsDto` wrapper) so a future field addition to [OrganizationSettingsDto]
 * only ever needs updating here, not duplicated field-by-field at every call site.
 */
fun ResultRow.toOrganizationSettingsDto(): OrganizationSettingsDto =
    OrganizationSettingsDto(
        id = this[OrganizationSettingsTable.id].toString(),
        name = this[OrganizationSettingsTable.name],
        street = this[OrganizationSettingsTable.street],
        postalCode = this[OrganizationSettingsTable.postalCode],
        city = this[OrganizationSettingsTable.city],
        country = this[OrganizationSettingsTable.country],
        bankIban = this[OrganizationSettingsTable.bankIban],
        bankBic = this[OrganizationSettingsTable.bankBic],
        taxExemptionAuthority = this[OrganizationSettingsTable.taxExemptionAuthority],
        taxExemptionDate = this[OrganizationSettingsTable.taxExemptionDate],
        isPoliticalParty = this[OrganizationSettingsTable.isPoliticalParty],
    )
