package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Fixtures of the member-statistics tests (Welle V1.9.59). The history rows of the fixtures live in the 1950s, so every assertion
 * can be an ABSOLUTE number for that window even though the shared H2 database also holds the seed members' rows of 2026.
 */
internal class MemberStatisticsFixtures {
    val memberIds = mutableListOf<Uuid>()

    fun member(
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Statistik-Test ${Uuid.random().toString().take(6)}"
                it[email] = "member-statistics-${Uuid.random()}@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2020, 1, 1)
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        memberIds += id
        return id
    }

    @Suppress("LongParameterList")
    fun history(
        memberId: Uuid,
        at: String,
        status: MemberStatus,
        previous: MemberStatus?,
        source: String = "LIVE",
        recordedAt: String? = at,
    ) {
        transaction {
            MemberStatusHistoryTable.insert {
                it[MemberStatusHistoryTable.memberId] = memberId
                it[effectiveFrom] = LocalDateTime.parse(at)
                it[MemberStatusHistoryTable.status] = status.name
                it[previousStatus] = previous?.name
                it[sourceKind] = source
                it[MemberStatusHistoryTable.recordedAt] = recordedAt?.let { r -> LocalDateTime.parse(r) }
            }
        }
    }

    /** The 1950 fixture: M1 APPLICATION -> ACTIVE -> WITHDRAWN, M2 ACTIVE. */
    fun window1950(): Pair<Uuid, Uuid> {
        val m1 = member(role = AccountRole.MEMBER)
        val m2 = member(role = AccountRole.MEMBER)
        history(memberId = m1, at = "1950-02-10T10:00:00", status = MemberStatus.APPLICATION, previous = null)
        history(memberId = m1, at = "1950-03-05T10:00:00", status = MemberStatus.ACTIVE, previous = MemberStatus.APPLICATION)
        history(memberId = m1, at = "1951-06-01T10:00:00", status = MemberStatus.WITHDRAWN, previous = MemberStatus.ACTIVE)
        history(memberId = m2, at = "1950-03-20T10:00:00", status = MemberStatus.ACTIVE, previous = null)
        return m1 to m2
    }

    fun cleanUp() {
        if (memberIds.isEmpty()) return
        transaction {
            MemberStatusHistoryTable.deleteWhere { memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
        memberIds.clear()
    }
}
