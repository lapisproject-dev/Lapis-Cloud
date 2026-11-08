package network.lapis.cloud.server.partg

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.activeChapterTreasurerIds
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

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

/**
 * V1.9.289 (R4a) -- who is calling a METHOD OF THE SCOPED SET (`PartyReportAccessTripwireTest` pins which methods use this gate; every other
 * method still uses [requirePartyReportAccess] and therefore refuses a chapter treasurer).
 */
internal sealed interface PartyReportActor {
    val current: CurrentMember

    /** BOARD or ADMIN: all units. */
    data class Federal(
        override val current: CurrentMember,
    ) : PartyReportActor

    /** A plain organization member with at least one ACTIVE chapter-treasurer grant: only the units `STATE:<id>` and `SUB:<id>` of their chapters. */
    data class ChapterTreasurer(
        override val current: CurrentMember,
        val chapterIds: Set<Uuid>,
    ) : PartyReportActor

    /** `true` iff the actor may see and (for the scoped writes) change [unit]. */
    fun allows(unit: PartgUnit): Boolean =
        when (this) {
            is Federal -> true
            is ChapterTreasurer -> unit.chapterId?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() in chapterIds } == true
        }
}

/**
 * The gate of the scoped methods. Order, fixed: first the caller (BOARD/ADMIN, or a member with an active chapter-treasurer grant -- re-read
 * fresh, so a revoke is effective at once), anything else is [ForbiddenException]; only then the organisation switch. So a plain member without a
 * grant gets 403 before the party check, exactly like the federal gate.
 */
internal fun requirePartyReportScopedAccess(call: ApplicationCall): PartyReportActor {
    val current = resolveCurrentMember(call)
    val actor: PartyReportActor =
        if (current.role == AccountRole.BOARD || current.role == AccountRole.ADMIN) {
            PartyReportActor.Federal(current)
        } else {
            val ids = transaction { current.activeChapterTreasurerIds() }
            if (ids.isEmpty()) throw ForbiddenException()
            PartyReportActor.ChapterTreasurer(current = current, chapterIds = ids)
        }
    val isParty =
        transaction {
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.isPoliticalParty) ?: false
        }
    if (!isParty) throw PartyReportNotEnabledException()
    return actor
}
