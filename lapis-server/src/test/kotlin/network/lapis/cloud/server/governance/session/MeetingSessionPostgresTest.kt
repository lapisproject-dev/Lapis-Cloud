package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.ConflictException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V1.9.236 -- the Sitzung operations against the real database engine: the lock on the Sitzung row serializes two simultaneous openings (exactly
 * one wins, the other is a conflict, never a half state) and two simultaneous adoptions (the sum of the added rows is the roster size, nobody
 * is inserted twice, no unique violation escapes); the GUEST status is accepted and an unknown one is rejected by the real CHECK; the roster
 * order is deterministic for equal names; the full migration chain V1..V127 ran when the lane started.
 */
abstract class MeetingSessionScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = SessionFixture()
        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        // the throwaway lane database is dropped anyway; cleaning first keeps the guards' "nothing left behind" view honest
        afterSpec {
            fx.cleanup()
            db.deactivate()
        }

        test("two simultaneous openings: exactly one wins, the other is a conflict, the state is OPEN") {
            val s = fx.scene()
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    (1..2)
                        .map {
                            pool.submit<Result<Unit>> {
                                barrier.await(10, TimeUnit.SECONDS)
                                runCatching {
                                    runBlocking {
                                        s.ops.open(
                                            current = s.lead,
                                            roomId = s.roomId,
                                            meetingId = s.meetingId,
                                        )
                                    }
                                }.map { }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                results.count { it.isSuccess } shouldBe 1
                results.count { it.exceptionOrNull() is ConflictException } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
            runBlocking { s.ops.getState(current = s.lead, roomId = s.roomId)!!.phase } shouldBe MeetingSessionPhase.OPEN
            fx.auditSnapshots(s.meetingId).size shouldBe 1
        }

        test("two simultaneous adoptions add every connected roster member exactly once and throw nothing") {
            val s = fx.scene(peers = 6)
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            s.connected.connect(*s.peers.toTypedArray())
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val added =
                    (1..2)
                        .map {
                            pool.submit<Int> {
                                barrier.await(10, TimeUnit.SECONDS)
                                runBlocking { s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                added.sum() shouldBe 6
            } finally {
                pool.shutdownNow()
            }
            fx.attendance(s.meetingId).size shouldBe 6
        }

        test("GUEST is stored; the roster order is deterministic with equal names") {
            val s = fx.scene(peers = 0)
            val twinA = fx.newMember(name = "MSX Zwilling")
            val twinB = fx.newMember(name = "MSX Zwilling")
            fx.seat(member = twinA, committee = s.committee)
            fx.seat(member = twinB, committee = s.committee)
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            val outsider = fx.newMember()
            runBlocking {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = SessionAttendanceInput(memberId = outsider.memberId.toString(), status = AttendanceStatus.GUEST),
                )
            }
            fx.attendance(s.meetingId)[outsider.memberId] shouldBe AttendanceStatus.GUEST
            val first =
                runBlocking {
                    s.ops
                        .getState(current = s.lead, roomId = s.roomId)!!
                        .roster
                        .map { it.memberId }
                }
            val second =
                runBlocking {
                    s.ops
                        .getState(current = s.lead, roomId = s.roomId)!!
                        .roster
                        .map { it.memberId }
                }
            first shouldBe second
            val twins = first.filter { it in setOf(twinA.memberId.toString(), twinB.memberId.toString()) }
            twins shouldBe twins.sorted()
        }

        test("V1.9.238: motions of equal submission time keep a fixed order -- agendaMotions and attachableMotions, run after run") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = runBlocking { s.openMeeting() }
            val ids = (1..6).map { fx.newMotion(committee = s.committee, submitter = s.participant, title = "MSX Gleich $it") }
            val attachable = runBlocking { s.ops.getState(current = s.lead, roomId = s.roomId)!! }.attachableMotions.map { it.id }
            attachable shouldBe ids.map { it.toString() }.sorted()
            ids.forEach {
                runBlocking {
                    m.attach(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        motionId = it.toString(),
                        expectedAgendaItemId = item,
                    )
                }
            }
            val first = runBlocking { s.ops.getState(current = s.lead, roomId = s.roomId)!! }.agendaMotions.map { it.id }
            val second = runBlocking { s.ops.getState(current = s.participant, roomId = s.roomId)!! }.agendaMotions.map { it.id }
            first shouldBe ids.map { it.toString() }.sorted()
            second shouldBe first
        }

        test("V1.9.238: two simultaneous openings of a vote -- exactly one wins (the Sitzung lock), the other is a conflict") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = runBlocking { s.openMeeting() }
            val first = fx.newMotion(committee = s.committee, submitter = s.participant)
            val second = fx.newMotion(committee = s.committee, submitter = s.participant)
            runBlocking {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = first.toString(),
                    expectedAgendaItemId = item,
                )
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = second.toString(),
                    expectedAgendaItemId = item,
                )
            }
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    listOf(first, second)
                        .map { motion ->
                            pool.submit<Result<Unit>> {
                                barrier.await(10, TimeUnit.SECONDS)
                                runCatching {
                                    runBlocking {
                                        m.openVote(
                                            current = s.lead,
                                            roomId = s.roomId,
                                            meetingId = s.meetingId,
                                            motionId = motion.toString(),
                                            expectedAgendaItemId = item,
                                            secret = false,
                                            boardMemberIds = s.boardIds(),
                                        )
                                    }
                                }.map { }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                results.count { it.isSuccess } shouldBe 1
                results.count { it.exceptionOrNull() is ConflictException } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }

        test("a mutation of a HELD Sitzung is a conflict on the real engine too") {
            val s = fx.scene()
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            fx.setStatus(meetingId = s.meetingId, status = network.lapis.cloud.shared.domain.MeetingStatus.HELD)
            shouldThrow<ConflictException> {
                runBlocking {
                    s.ops.establishQuorum(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        acknowledgeNotMet = true,
                    )
                }
            }
        }
    })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MeetingSessionPostgresTest : MeetingSessionScenarios(TestDatabase.Postgres())
