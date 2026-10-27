package network.lapis.cloud.server.partg

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.225 -- the ONE gate of the party report; the FIRST statement of every RPC method of `PartyReportService` and of every handler of
 * `PartgReportRoutes` (pinned by `PartyReportAccessTripwireTest`).
 *
 * The order is fixed: first the role (BOARD or ADMIN; TREASURER, MEMBER and a guest get [network.lapis.cloud.shared.rpc.ForbiddenException]),
 * only then the organisation switch (`isPoliticalParty`, else [PartyReportNotEnabledException]). So this gate itself never reveals whether the
 * organisation is a party to anyone below the board. (The fact is NOT secret otherwise: `SessionInfoDto.politicalParty` is sent to every
 * logged-in member and the donor-category flow reveals it too.) The stored data stays untouched when the switch is turned off and is readable again after turning it on.
 */
internal fun requirePartyReportAccess(call: ApplicationCall): CurrentMember {
    val current = resolveCurrentMember(call)
    current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
    val isParty =
        transaction {
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.isPoliticalParty) ?: false
        }
    if (!isParty) throw PartyReportNotEnabledException()
    return current
}
