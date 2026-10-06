package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.61 -- [ConferenceModeratorAuthority] is the single answer to "may this caller moderate this room" for the six conference
 * services. An ORDINARY room keeps exactly the pre-V1.9.61 rule (creator, or BOARD/ADMIN); an encounter session answers from the
 * office table, re-derived on every call.
 */
class ConferenceModeratorAuthorityTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun current(
            memberId: Uuid,
            role: AccountRole = AccountRole.MEMBER,
        ) = CurrentMember(memberId = memberId, role = role, status = MemberStatus.ACTIVE)

        fun isModerator(
            room: Uuid,
            who: CurrentMember,
        ): Boolean =
            transaction {
                ConferenceModeratorAuthority.isModerator(
                    row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single(),
                    current = who,
                )
            }

        /** An ORDINARY room (no encounter_space_id), inserted directly. */
        fun ordinaryRoom(creator: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Ordentlich"
                    it[description] = ""
                    it[livekitRoomName] = "lc-$id"
                    it[createdByMemberId] = creator
                    it[createdAt] = kotlinx.datetime.LocalDateTime.parse("2026-10-06T10:00:00")
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[encounterSpaceId] = null
                }
            }
            return id
        }

        test(
            "ordinary room: exactly the old rule -- the creator and BOARD/ADMIN moderate, everybody else (also an office holder elsewhere) does not",
        ) {
            val creator = fx.createMember()
            val other = fx.createMember()
            val board = fx.createMember(role = AccountRole.BOARD)
            val admin = fx.createMember(role = AccountRole.ADMIN)
            val officeHolderElsewhere = fx.createMember()
            fx.setRole(spaceId = fx.createSpace(createdBy = board), memberId = officeHolderElsewhere, role = EncounterSpaceRole.STEWARD)
            val room = ordinaryRoom(creator)
            isModerator(room, current(creator)) shouldBe true
            isModerator(room, current(board, AccountRole.BOARD)) shouldBe true
            isModerator(room, current(admin, AccountRole.ADMIN)) shouldBe true
            isModerator(room, current(other)) shouldBe false
            isModerator(room, current(officeHolderElsewhere)) shouldBe false
            // TREASURER is not privileged
            isModerator(room, current(other, AccountRole.TREASURER)) shouldBe false
        }

        test(
            "encounter session: ACTIVE PULPIT/STEWARD and BOARD/ADMIN moderate; the one who opened it does NOT by that fact; a withdrawn office holder does not",
        ) {
            val board = fx.createMember(role = AccountRole.BOARD)
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val opener = fx.createMember()
            val withdrawn = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            fx.setRole(spaceId = space, memberId = withdrawn, role = EncounterSpaceRole.STEWARD)
            // the opener is the creator of the room row but holds no office
            val room = fx.insertSession(spaceId = space, openedBy = opener)
            isModerator(room, current(pulpit)) shouldBe true
            isModerator(room, current(steward)) shouldBe true
            isModerator(room, current(board, AccountRole.BOARD)) shouldBe true
            isModerator(room, current(opener)) shouldBe false
            isModerator(room, current(member)) shouldBe false
            // status changes take effect immediately, without any cleanup of the office row
            isModerator(room, current(withdrawn)) shouldBe true
            fx.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
            isModerator(room, current(withdrawn)) shouldBe false
            // an office of ANOTHER space grants nothing here
            val otherSpace = fx.createSpace(createdBy = board)
            val strangerOffice = fx.createMember()
            fx.setRole(spaceId = otherSpace, memberId = strangerOffice, role = EncounterSpaceRole.PULPIT)
            isModerator(room, current(strangerOffice)) shouldBe false
        }

        test("requireModerator throws ForbiddenException exactly when isModerator is false") {
            val board = fx.createMember(role = AccountRole.BOARD)
            val stranger = fx.createMember()
            val room = ordinaryRoom(board)
            val row = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single() }
            transaction { ConferenceModeratorAuthority.requireModerator(row = row, current = current(board, AccountRole.BOARD)) }
            val failure =
                runCatching {
                    transaction {
                        ConferenceModeratorAuthority.requireModerator(
                            row = row,
                            current = current(stranger),
                        )
                    }
                }.exceptionOrNull()
            (failure is ForbiddenException) shouldBe true
        }
    })
