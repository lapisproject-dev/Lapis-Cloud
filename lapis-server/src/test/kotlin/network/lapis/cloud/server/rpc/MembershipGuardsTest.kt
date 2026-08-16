package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V0.11.0 -- an exhaustive allow/deny matrix for every [MemberStatus] against every status-gate
 * primitive in [MembershipGuards], calling the (package-internal, non-`private`) functions
 * directly rather than through an HTTP layer -- this is the regression net the wave's own plan
 * calls "what makes future widening safe": if [FRIEND][MemberStatus.FRIEND] or a future status
 * ever silently slips into a set it should not be in, exactly one row of this matrix goes red.
 *
 * [afterSpec] hard-deletes every member row this file created.
 */
class MembershipGuardsTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ConferenceParticipationTable.deleteWhere { ConferenceParticipationTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(status: MemberStatus): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Guard-Matrix Testmitglied ${status.name}"
                    it[email] = "guard-matrix-${status.name.lowercase()}-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        val membersByStatus = MemberStatus.entries.associateWith { createTestMember(it) }

        /**
         * Runs [block] inside a fresh transaction and reports whether it was allowed (no
         * exception) or denied (threw [ForbiddenException]) -- any OTHER exception is a genuine
         * test-setup bug and is rethrown rather than swallowed as a false "denied".
         */
        fun allowed(
            block: (Uuid) -> Unit,
            memberId: Uuid,
        ): Boolean {
            val result = runCatching { transaction { block(memberId) } }
            return when {
                result.isSuccess -> true
                result.exceptionOrNull() is ForbiddenException -> false
                else -> throw result.exceptionOrNull()!!
            }
        }

        test("requireActiveMembership: allows ONLY ACTIVE, denies every other status (FRIEND included)") {
            MemberStatus.entries.forEach { status ->
                val memberId = membersByStatus.getValue(status)
                val isAllowed = allowed(block = { id -> requireActiveMembership(memberId = id) }, memberId = memberId)
                withClueStatus(status = status) { isAllowed shouldBe (status == MemberStatus.ACTIVE) }
            }
        }

        test("requirePoliticianRaterMembership: allows ACTIVE and GUEST, denies FRIEND and everything else") {
            MemberStatus.entries.forEach { status ->
                val memberId = membersByStatus.getValue(status)
                val isAllowed = allowed(block = { id -> requirePoliticianRaterMembership(memberId = id) }, memberId = memberId)
                val expected = status == MemberStatus.ACTIVE || status == MemberStatus.GUEST
                withClueStatus(status = status) { isAllowed shouldBe expected }
            }
        }

        test("requireConferenceEligibleMembership: allows ACTIVE, GUEST, AND FRIEND, denies APPLICATION/WITHDRAWN/REJECTED") {
            MemberStatus.entries.forEach { status ->
                val memberId = membersByStatus.getValue(status)
                val isAllowed = allowed(block = { id -> requireConferenceEligibleMembership(memberId = id) }, memberId = memberId)
                val expected = status == MemberStatus.ACTIVE || status == MemberStatus.GUEST || status == MemberStatus.FRIEND
                withClueStatus(status = status) { isAllowed shouldBe expected }
            }
        }

        test(
            "requireMembershipStatusIn: the raw primitive matches MemberStatusSets.CONFERENCE_ELIGIBLE exactly for an arbitrary custom set",
        ) {
            val customSet = setOf(MemberStatus.GUEST, MemberStatus.FRIEND)
            MemberStatus.entries.forEach { status ->
                val memberId = membersByStatus.getValue(status)
                val isAllowed =
                    allowed(block = { id ->
                        requireMembershipStatusIn(memberId = id, allowed = customSet)
                    }, memberId = memberId)
                withClueStatus(status = status) { isAllowed shouldBe (status in customSet) }
            }
        }

        test("requireMembershipStatusIn: an unknown memberId is denied (ForbiddenException), never NotFound-leaking") {
            val isAllowed =
                allowed(block = { id ->
                    requireMembershipStatusIn(memberId = id, allowed = MemberStatus.entries.toSet())
                }, memberId = Uuid.random())
            isAllowed shouldBe false
        }

        // ── requireRoomEntryAuthorization / requireGuestHasJoinedRoom ────────────────────────

        test(
            "requireRoomEntryAuthorization: FRIEND is admitted to an opted-in room, denied a non-opted-in room -- same as GUEST, wider than APPLICATION",
        ) {
            val creator = membersByStatus.getValue(MemberStatus.ACTIVE)
            val openRoomId = createGuardTestRoom(creatorId = creator, allowFederationGuests = true)
            val closedRoomId = createGuardTestRoom(creatorId = creator, allowFederationGuests = false)

            val friendId = membersByStatus.getValue(MemberStatus.FRIEND)
            val current = CurrentMember(memberId = friendId, role = AccountRole.MEMBER, status = MemberStatus.FRIEND)

            transaction {
                val openRoomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq openRoomId }.single()
                requireRoomEntryAuthorization(roomRow = openRoomRow, current = current) shouldBe MemberStatus.FRIEND
            }
            transaction {
                val closedRoomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq closedRoomId }.single()
                val threw =
                    runCatching { requireRoomEntryAuthorization(roomRow = closedRoomRow, current = current) }
                        .exceptionOrNull()
                (threw is ForbiddenException) shouldBe true
            }

            transaction {
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq openRoomId }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq closedRoomId }
            }
        }
    })

private inline fun withClueStatus(
    status: MemberStatus,
    block: () -> Unit,
) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError("Guard matrix mismatch for status=$status: ${t.message}", t)
    }
}

private fun createGuardTestRoom(
    creatorId: Uuid,
    allowFederationGuests: Boolean,
): Uuid {
    val roomId = Uuid.random()
    transaction {
        ConferenceRoomTable.insert {
            it[id] = roomId
            it[title] = "Guard-Matrix-Raum"
            it[description] = ""
            it[livekitRoomName] = "lc-guard-$roomId"
            it[createdByMemberId] = creatorId
            it[createdAt] =
                network.lapis.cloud.server.db.DbClock
                    .nowLocalDateTime()
            it[endedAt] = null
            it[maxParticipants] = 25
            it[ConferenceRoomTable.allowFederationGuests] = allowFederationGuests
        }
    }
    return roomId
}
