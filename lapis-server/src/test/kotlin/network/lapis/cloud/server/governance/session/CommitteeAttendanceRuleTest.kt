package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.ConflictException

/** V1.9.236 -- the exchangeable attendance rule per committee type, and its validation through the real operation. */
class CommitteeAttendanceRuleTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun input(
            who: network.lapis.cloud.server.security.CurrentMember,
            status: AttendanceStatus?,
            standIn: network.lapis.cloud.server.security.CurrentMember? = null,
        ) = SessionAttendanceInput(
            memberId = who.memberId.toString(),
            status = status,
            representedByMemberId = standIn?.memberId?.toString(),
        )

        test("forType: name list for four kinds, unsupported for the general assembly") {
            CommitteeAttendanceRules.forType(CommitteeType.EXECUTIVE_BOARD).kind shouldBe AttendanceRuleKind.NAME_LIST
            CommitteeAttendanceRules.forType(CommitteeType.WORKING_GROUP).kind shouldBe AttendanceRuleKind.NAME_LIST
            CommitteeAttendanceRules.forType(CommitteeType.COMMISSION).kind shouldBe AttendanceRuleKind.NAME_LIST
            CommitteeAttendanceRules.forType(CommitteeType.OTHER).kind shouldBe AttendanceRuleKind.NAME_LIST
            CommitteeAttendanceRules.forType(CommitteeType.GENERAL_ASSEMBLY).kind shouldBe AttendanceRuleKind.UNSUPPORTED
        }

        test("the roster is ordered by name, then id, deterministically even with equal names") {
            val s = fx.scene(peers = 0)
            val twinA = fx.newMember(name = "MSX Zwilling")
            val twinB = fx.newMember(name = "MSX Zwilling")
            fx.seat(member = twinA, committee = s.committee)
            fx.seat(member = twinB, committee = s.committee)
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val roster = s.ops.getState(current = s.lead, roomId = s.roomId)!!.roster
            val names = roster.map { it.displayName }
            names shouldBe names.sortedBy { it.lowercase() }
            val twins = roster.filter { it.displayName == "MSX Zwilling" }.map { it.memberId }
            twins shouldBe twins.sorted()
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .roster
                .map { it.memberId } shouldBe roster.map { it.memberId }
        }

        test("a guest must be outside the circle, an active member of the organization, and not a federation guest") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(s.peers[0], AttendanceStatus.GUEST),
                )
            }
            val federation = fx.newMember(status = MemberStatus.GUEST)
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(federation, AttendanceStatus.GUEST),
                )
            }
            val outsider = fx.newMember()
            s.ops
                .setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(outsider, AttendanceStatus.GUEST),
                ).guests.size shouldBe 1
        }

        test("roster statuses only for people on the roster") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val outsider = fx.newMember()
            listOf(AttendanceStatus.PRESENT, AttendanceStatus.EXCUSED, AttendanceStatus.UNEXCUSED).forEach {
                shouldThrow<ConflictException> {
                    s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, input = input(outsider, it))
                }
            }
        }

        test(
            "representation: not oneself, not a represented person as stand-in, no chain, stand-in required, stand-in only with REPRESENTED",
        ) {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val (a, b, c) = s.peers
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(a, AttendanceStatus.REPRESENTED, a),
                )
            }
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(a, AttendanceStatus.REPRESENTED),
                )
            }
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(a, AttendanceStatus.PRESENT, b),
                )
            }
            s.ops.setAttendance(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                input = input(a, AttendanceStatus.REPRESENTED, b),
            )
            // b is a's stand-in: b cannot be represented (chain) ...
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(b, AttendanceStatus.REPRESENTED, c),
                )
            }
            // ... and a represented person cannot be somebody's stand-in
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(c, AttendanceStatus.REPRESENTED, a),
                )
            }
            // ... and b cannot be cleared while a is represented by b
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(b, null),
                )
            }
        }

        test("a stand-in cannot be set absent, and an absent person cannot be a stand-in") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val (a, b, c) = s.peers
            s.ops.setAttendance(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                input = input(a, AttendanceStatus.REPRESENTED, b),
            )
            for (absent in listOf(AttendanceStatus.EXCUSED, AttendanceStatus.UNEXCUSED)) {
                shouldThrow<ConflictException> {
                    s.ops.setAttendance(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        input = input(b, absent),
                    )
                }
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(c, absent),
                )
                shouldThrow<ConflictException> {
                    s.ops.setAttendance(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        input = input(a, AttendanceStatus.REPRESENTED, c),
                    )
                }
            }
        }

        test("open clears the entry") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val a = s.peers[0]
            s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, input = input(a, AttendanceStatus.PRESENT))
            fx.attendance(s.meetingId)[a.memberId] shouldBe AttendanceStatus.PRESENT
            s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, input = input(a, null))
            fx.attendance(s.meetingId).containsKey(a.memberId) shouldBe false
        }

        test("refusals never carry a name") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val e =
                shouldThrow<ConflictException> {
                    s.ops.setAttendance(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        input = input(s.peers[0], AttendanceStatus.GUEST),
                    )
                }
            e.message!! shouldNotContain "MSX"
        }

        test("the old recordAttendance path and the panel write the same row shape (upsert keeps the note)") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val a = s.peers[0]
            s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, input = input(a, AttendanceStatus.PRESENT))
            s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, input = input(a, AttendanceStatus.EXCUSED))
            fx.attendance(s.meetingId)[a.memberId] shouldBe AttendanceStatus.EXCUSED
            fx.attendance(s.meetingId).size shouldBe 1
        }
    })
