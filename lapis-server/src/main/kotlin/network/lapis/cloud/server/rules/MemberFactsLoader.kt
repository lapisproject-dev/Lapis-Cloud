package network.lapis.cloud.server.rules

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.rules.MemberFacts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** A member as the rule engine and the recipient guard see them. [email] is for the delivery path only -- never log or audit it. */
internal data class LoadedMember(
    val id: Uuid,
    val facts: MemberFacts,
    val anonymised: Boolean,
    val deceased: Boolean,
    val email: String,
)

/**
 * Welle V1.9.111 -- loads the [MemberFacts] a [network.lapis.cloud.shared.domain.rules.MemberRule] is evaluated against. Every function
 * runs INSIDE a caller's `transaction {}` and reads only (never locks a `member` row).
 *
 * The bulk form makes three queries (members, accounts with their roles, active committee seats of active committees) independent of the
 * number of members -- no N+1. An "active seat" is `since <= today AND (until IS NULL OR until >= today)` (the same definition the
 * governance authorization uses) in a committee whose `active` flag is set. [today] is the organisation's calendar day
 * (`OrganizationTimeZone.today()`), never the process zone.
 */
internal object MemberFactsLoader {
    /** [statuses] `null` = every member (for later waves), otherwise only members in one of these statuses. */
    fun loadAll(
        today: LocalDate,
        statuses: Set<MemberStatus>?,
    ): List<LoadedMember> {
        val memberRows =
            MemberTable
                .selectAll()
                .let { query -> if (statuses == null) query else query.where { MemberTable.status inList statuses } }
                .toList()
        if (memberRows.isEmpty()) return emptyList()
        val rolesByMember = loadRoles()
        val seatsByMember = loadActiveSeats(today)
        return memberRows.map {
            it.toLoaded(roles = rolesByMember[it[MemberTable.id]].orEmpty(), committeeIds = seatsByMember[it[MemberTable.id]].orEmpty())
        }
    }

    /** One member (a member may have several accounts, so the roles are a set); `null` if there is no such member. */
    fun loadOne(
        memberId: Uuid,
        today: LocalDate,
    ): LoadedMember? {
        val row = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() ?: return null
        val roles =
            AccountTable
                .selectAll()
                .where { AccountTable.memberId eq memberId }
                .map { it[AccountTable.role] }
                .toSet()
        val seats =
            (CommitteeMembershipTable innerJoin CommitteeTable)
                .selectAll()
                .where {
                    (CommitteeMembershipTable.memberId eq memberId) and activeSeat(today) and (CommitteeTable.active eq true)
                }.map { it[CommitteeMembershipTable.committeeId].toString() }
                .toSet()
        return row.toLoaded(roles = roles, committeeIds = seats)
    }

    private fun loadRoles(): Map<Uuid, Set<AccountRole>> =
        AccountTable
            .selectAll()
            .map { it[AccountTable.memberId] to it[AccountTable.role] }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun loadActiveSeats(today: LocalDate): Map<Uuid, Set<String>> =
        (CommitteeMembershipTable innerJoin CommitteeTable)
            .selectAll()
            .where { activeSeat(today) and (CommitteeTable.active eq true) }
            .map { it[CommitteeMembershipTable.memberId] to it[CommitteeMembershipTable.committeeId].toString() }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun activeSeat(today: LocalDate) =
        (CommitteeMembershipTable.since lessEq today) and
            (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq today))

    private fun ResultRow.toLoaded(
        roles: Set<AccountRole>,
        committeeIds: Set<String>,
    ): LoadedMember {
        val status = this[MemberTable.status]
        return LoadedMember(
            id = this[MemberTable.id],
            facts =
                MemberFacts(
                    status = status,
                    roles = roles,
                    joinedOn = this[MemberTable.joinedAt],
                    activeCommitteeIds = committeeIds,
                    tierId = this[MemberTable.membershipTierId]?.toString(),
                ),
            anonymised = this[MemberTable.anonymizedAt] != null,
            deceased = this[MemberTable.dateOfDeath] != null || status == MemberStatus.DECEASED,
            email = this[MemberTable.email],
        )
    }
}
