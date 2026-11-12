package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.rpc.ServiceCaller
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ROOM_POLL_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.RoomPollCloseReason
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private suspend fun <T> ServiceCaller.asUser(
    rig: PollRig,
    who: Uuid,
    action: suspend (network.lapis.cloud.server.rpc.ConferenceRoomPollService) -> T,
): Result<T> = call(caller = who) { action(rig.service(it)) }

private fun optionIds(poll: Uuid): List<Uuid> =
    transaction {
        ConferenceRoomPollOptionTable
            .selectAll()
            .where { ConferenceRoomPollOptionTable.pollId eq poll }
            .orderBy(ConferenceRoomPollOptionTable.position)
            .map { it[ConferenceRoomPollOptionTable.id] }
    }

/**
 * V1.9.304 -- [network.lapis.cloud.server.rpc.ConferenceRoomPollService] on H2 AND PostgreSQL with the same assertions: lifecycle, uniform
 * refusals, anonymity of the stored answers, weights, snapshots, version tokens, automatic ending, nudges, rate limits, concurrency.
 */
abstract class ConferenceRoomPollServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = RoomPollFixtures()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            try {
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun newRoomWithLeader(): Pair<PollRoom, Uuid> {
            val leader = fx.member()
            val room = fx.room(creator = leader)
            fx.join(room = room, who = leader)
            return room to leader
        }

        fun activeIn(room: PollRoom): Uuid = fx.member().also { fx.join(room = room, who = it) }

        fun guestIn(
            room: PollRoom,
            status: MemberStatus = MemberStatus.GUEST,
        ): Uuid = fx.member(status = status).also { fx.join(room = room, who = it) }

        suspend fun ServiceCaller.start(
            rig: PollRig,
            room: PollRoom,
            leader: Uuid,
            mode: RoomPollCountingMode = RoomPollCountingMode.BOTH,
        ): RoomPollStateDto = asUser(rig = rig, who = leader) { it.startRoomPoll(pollInput(room = room, mode = mode)) }.getOrThrow()

        test("happy path: start, answers from members and guests, end, the result is visible to everybody present") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val members = (1..5).map { activeIn(room) }
            val guests = (1..5).map { guestIn(room) }
            withServiceCaller {
                val started = start(rig, room, leader)
                started.poll!!.status shouldBe RoomPollStatus.OPEN
                started.poll!!.binding shouldBe false
                started.isLeadership shouldBe true
                started.canAnswer shouldBe true
                started.answerCount shouldBe 0
                val poll = started.poll!!.id
                val options = optionIds(Uuid.parse(poll))
                members.forEachIndexed { i, m ->
                    val s =
                        asUser(rig = rig, who = m) {
                            it.answerRoomPoll(
                                roomId = room.id.toString(),
                                pollId = poll,
                                optionId =
                                    options[
                                        i %
                                            2,
                                    ].toString(),
                            )
                        }.getOrThrow()
                    s.voted shouldBe true
                    s.canAnswer shouldBe false
                    s.answerCount.shouldBeNull()
                    s.interim.shouldBeNull()
                }
                guests.forEach { g ->
                    asUser(
                        rig = rig,
                        who = g,
                    ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = options[1].toString()) }.getOrThrow()
                }
                // the nudge went out once, at the start, and NEVER because of an answer
                rig.nudges() shouldHaveSize 1
                val ended = asUser(rig = rig, who = leader) { it.closeRoomPoll(roomId = room.id.toString(), pollId = poll) }.getOrThrow()
                ended.poll!!.status shouldBe RoomPollStatus.CLOSED
                ended.poll!!.closeReason shouldBe RoomPollCloseReason.MANUAL
                rig.nudges() shouldHaveSize 2
                rig.nudges().forEach {
                    it.payload shouldBe "{}"
                    (it.payload.length <= ROOM_POLL_MAX_PAYLOAD_BYTES) shouldBe true
                    it.destinations.shouldBeNull()
                    it.room shouldBe room.name
                }
                val result = ended.result!!
                result.totalAnswerCount shouldBe 10
                result.splitAvailable shouldBe true
                result.members!!.answerCount shouldBe 5
                result.guestsAndFriends!!.answerCount shouldBe 5
                result.all!!.answerCount shouldBe 10
                // every person present sees the same result
                listOf(members[0], guests[0]).forEach { who ->
                    val s = asUser(rig = rig, who = who) { it.getRoomPollState(room.id.toString()) }.getOrThrow()
                    s.result shouldBe result
                    s.canAnswer shouldBe false
                    s.answerCount.shouldBeNull()
                }
                // the markers of who answered are gone once the poll has ended
                fx.markers(Uuid.parse(poll)).shouldBeEmpty()
                fx.answers(Uuid.parse(poll)) shouldHaveSize 10
            }
        }

        test("an answer can be given once; a second one is a conflict and changes nothing") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voter = activeIn(room)
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                asUser(rig = rig, who = voter) {
                    it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString())
                }.isSuccess shouldBe
                    true
                val second =
                    asUser(
                        rig = rig,
                        who = voter,
                    ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[1].toString()) }
                second.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                fx.answers(Uuid.parse(poll)) shouldHaveSize 1
                fx.markers(Uuid.parse(poll)) shouldHaveSize 1
            }
        }

        test("a second poll while one is open is a conflict; after the end a new one starts") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            withServiceCaller {
                val first = start(rig, room, leader).poll!!.id
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                asUser(rig = rig, who = leader) { it.closeRoomPoll(roomId = room.id.toString(), pollId = first) }.getOrThrow()
                // closing again is an idempotent success with the current state
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.closeRoomPoll(roomId = room.id.toString(), pollId = first) }.getOrThrow().poll!!.status shouldBe
                    RoomPollStatus.CLOSED
                val second = start(rig, room, leader).poll!!
                (second.id != first) shouldBe true
                fx.polls(room) shouldHaveSize 2
            }
        }

        test("parallel double answer of the same person leaves exactly one marker and one answer") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voter = activeIn(room)
            var pollId = ""
            var optionId = ""
            withServiceCaller {
                pollId = start(rig, room, leader).poll!!.id
                optionId = optionIds(Uuid.parse(pollId))[0].toString()
            }
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, _ -> call.respondText("conflict", status = HttpStatusCode.Conflict) }
                        exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                    }
                    routing {
                        get("/test/answer") {
                            rig.service(call).answerRoomPoll(roomId = room.id.toString(), pollId = pollId, optionId = optionId)
                            call.respondText("ok")
                        }
                    }
                }
                val codes =
                    coroutineScope {
                        (1..8)
                            .map { async { client.get("/test/answer") { header("X-Member-Id", voter.toString()) }.status } }
                            .awaitAll()
                    }
                codes.count { it == HttpStatusCode.OK } shouldBe 1
                codes.count { it == HttpStatusCode.Conflict } shouldBe 7
            }
            fx.answers(Uuid.parse(pollId)) shouldHaveSize 1
            fx.markers(Uuid.parse(pollId)) shouldHaveSize 1
        }

        test("parallel double start leaves exactly one open poll") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, _ -> call.respondText("conflict", status = HttpStatusCode.Conflict) }
                    }
                    routing {
                        get("/test/start") {
                            rig.service(call).startRoomPoll(pollInput(room = room))
                            call.respondText("ok")
                        }
                    }
                }
                val codes =
                    coroutineScope {
                        (1..6)
                            .map { async { client.get("/test/start") { header("X-Member-Id", leader.toString()) }.status } }
                            .awaitAll()
                    }
                codes.count { it == HttpStatusCode.OK } shouldBe 1
                codes.count { it == HttpStatusCode.Conflict } shouldBe 5
            }
            fx.polls(room).count { it[ConferenceRoomPollTable.status] == RoomPollStatus.OPEN } shouldBe 1
        }

        test("a poll id of another room, an option of another poll and malformed ids are refused; nothing is written") {
            val rig = PollRig()
            val (roomA, leaderA) = newRoomWithLeader()
            val (roomB, leaderB) = newRoomWithLeader()
            val voterA = activeIn(roomA)
            withServiceCaller {
                val pollA = start(rig, roomA, leaderA).poll!!.id
                val pollB = start(rig, roomB, leaderB).poll!!.id
                val optionB = optionIds(Uuid.parse(pollB))[0].toString()
                val optionA = optionIds(Uuid.parse(pollA))[0].toString()
                // poll of room B with room A
                asUser(rig = rig, who = voterA) { it.answerRoomPoll(roomId = roomA.id.toString(), pollId = pollB, optionId = optionB) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                asUser(
                    rig = rig,
                    who = leaderA,
                ) {
                    it.closeRoomPoll(
                        roomId = roomA.id.toString(),
                        pollId = pollB,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                // an option of another poll
                asUser(rig = rig, who = voterA) { it.answerRoomPoll(roomId = roomA.id.toString(), pollId = pollA, optionId = optionB) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                asUser(
                    rig = rig,
                    who = voterA,
                ) {
                    it.answerRoomPoll(
                        roomId = "nonsense",
                        pollId = pollA,
                        optionId = optionA,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asUser(rig = rig, who = voterA) { it.answerRoomPoll(roomId = roomA.id.toString(), pollId = pollA, optionId = "nonsense") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                fx.answers(Uuid.parse(pollA)).shouldBeEmpty()
                fx.markers(Uuid.parse(pollA)).shouldBeEmpty()
            }
        }

        test("not present, a withdrawn status and an unknown room are all the identical Forbidden") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val outsider = fx.member()
            val withdrawn = activeIn(room)
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val option = optionIds(Uuid.parse(poll))[0].toString()
                fx.setStatus(member = withdrawn, status = MemberStatus.WITHDRAWN)
                val results =
                    listOf(
                        asUser(
                            rig = rig,
                            who = outsider,
                        ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = option) },
                        asUser(
                            rig = rig,
                            who = withdrawn,
                        ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = option) },
                        asUser(rig = rig, who = outsider) { it.getRoomPollState(room.id.toString()) },
                        asUser(rig = rig, who = outsider) { it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = null) },
                        asUser(rig = rig, who = outsider) { it.getRoomPollState(Uuid.random().toString()) },
                        asUser(rig = rig, who = outsider) { it.startRoomPoll(pollInput(room = room)) },
                    )
                results.forEach { it.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>() }
                results.map { it.exceptionOrNull()!!.message }.toSet() shouldHaveSize 1
            }
        }

        test("a creator who became a donor is no leadership; an ordinary member never starts or ends; BOARD does") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val ordinary = activeIn(room)
            val board = fx.member(role = AccountRole.BOARD).also { fx.join(room = room, who = it) }
            withServiceCaller {
                asUser(
                    rig = rig,
                    who = ordinary,
                ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                val poll = asUser(rig = rig, who = board) { it.startRoomPoll(pollInput(room = room)) }.getOrThrow().poll!!.id
                asUser(
                    rig = rig,
                    who = ordinary,
                ) {
                    it.closeRoomPoll(
                        roomId = room.id.toString(),
                        pollId = poll,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                fx.setStatus(member = leader, status = MemberStatus.DONOR)
                asUser(
                    rig = rig,
                    who = leader,
                ) {
                    it.closeRoomPoll(
                        roomId = room.id.toString(),
                        pollId = poll,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asUser(
                    rig = rig,
                    who = board,
                ) { it.closeRoomPoll(roomId = room.id.toString(), pollId = poll) }.getOrThrow().poll!!.status shouldBe
                    RoomPollStatus.CLOSED
            }
        }

        test("leadership who is not present cannot start but can end a poll") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                transaction {
                    network.lapis.cloud.server.db.generated.ConferenceParticipationTable.deleteWhere {
                        network.lapis.cloud.server.db.generated.ConferenceParticipationTable.memberId eq leader
                    }
                }
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.closeRoomPoll(roomId = room.id.toString(), pollId = poll) }.getOrThrow().poll!!.status shouldBe
                    RoomPollStatus.CLOSED
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("only the leadership sees the answer count; nobody gets an interim result at any step") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voters = (1..11).map { activeIn(room) }
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                voters.forEachIndexed { i, v ->
                    asUser(
                        rig = rig,
                        who = v,
                    ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[i % 3].toString()) }.getOrThrow()
                    val lead = asUser(rig = rig, who = leader) { it.getRoomPollState(room.id.toString()) }.getOrThrow()
                    lead.answerCount shouldBe i + 1
                    // regression (security): no snapshot at 5, 10, ... -- "snapshot at 10, close at 11" must not isolate the 11th answer
                    lead.interim.shouldBeNull()
                    lead.interimAnswerCount.shouldBeNull()
                    val other = asUser(rig = rig, who = voters[0]) { it.getRoomPollState(room.id.toString()) }.getOrThrow()
                    other.answerCount.shouldBeNull()
                    other.interim.shouldBeNull()
                    other.interimAnswerCount.shouldBeNull()
                    other.result.shouldBeNull()
                }
            }
        }

        test("the version token: an answer changes the token for nobody, not even at every fifth answer") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voters = (1..6).map { activeIn(room) }
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                val memberToken =
                    asUser(rig = rig, who = voters[5]) {
                        it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = null)
                    }.getOrThrow().version!!
                val leaderToken =
                    asUser(rig = rig, who = leader) {
                        it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = null)
                    }.getOrThrow().version!!
                (0 until 4).forEach { i ->
                    asUser(rig = rig, who = voters[i]) {
                        it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString())
                    }.getOrThrow()
                    asUser(rig = rig, who = voters[5]) {
                        it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = memberToken)
                    }.getOrThrow().changed shouldBe
                        false
                    asUser(rig = rig, who = leader) {
                        it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = leaderToken)
                    }.getOrThrow().changed shouldBe
                        false
                }
                // the fifth answer takes no snapshot any more: neither members nor leadership see a change
                asUser(rig = rig, who = voters[4]) {
                    it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[1].toString())
                }.getOrThrow()
                asUser(rig = rig, who = voters[5]) {
                    it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = memberToken)
                }.getOrThrow().changed shouldBe
                    false
                asUser(rig = rig, who = leader) {
                    it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = leaderToken)
                }.getOrThrow().changed shouldBe
                    false
                // the end changes the token for everybody
                asUser(rig = rig, who = leader) { it.closeRoomPoll(roomId = room.id.toString(), pollId = poll) }.getOrThrow()
                asUser(rig = rig, who = voters[5]) {
                    it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = memberToken)
                }.getOrThrow().changed shouldBe
                    true
                asUser(rig = rig, who = leader) {
                    it.getRoomPollVersion(roomId = room.id.toString(), knownVersion = leaderToken)
                }.getOrThrow().changed shouldBe
                    true
            }
        }

        test("the end of the room closes the poll (ROOM_ENDED), stores the result, deletes the markers and audits the end") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voters = (1..3).map { activeIn(room) }
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                voters.forEach { v ->
                    asUser(
                        rig = rig,
                        who = v,
                    ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString()) }.getOrThrow()
                }
                fx.endRoom(room)
                // a person who may still read the room gets the closed poll with the reason (the 12 hours of visibility)
                val s = asUser(rig = rig, who = voters[0]) { it.getRoomPollState(room.id.toString()) }.getOrThrow()
                s.poll!!.status shouldBe RoomPollStatus.CLOSED
                s.poll!!.closeReason shouldBe RoomPollCloseReason.ROOM_ENDED
                s.result!!.totalAnswerCount shouldBe 3
                s.result!!.resultAvailable shouldBe false
                fx.markers(Uuid.parse(poll)).shouldBeEmpty()
                val audits = fx.audits(Uuid.parse(poll))
                audits.map { it[network.lapis.cloud.server.db.generated.AuditLogEntryTable.action] } shouldBe
                    listOf(AuditAction.CREATE, AuditAction.UPDATE)
                audits.forEach {
                    it[network.lapis.cloud.server.db.generated.AuditLogEntryTable.entityType] shouldBe AuditEntityType.CONFERENCE_ROOM_POLL
                    val after = it[network.lapis.cloud.server.db.generated.AuditLogEntryTable.afterSnapshot].orEmpty()
                    after shouldNotContain "Sollen wir"
                    after shouldNotContain "Enthaltung"
                }
                audits[1][network.lapis.cloud.server.db.generated.AuditLogEntryTable.afterSnapshot]!! shouldNotContain "\"answerCount\""
                // a closed room takes neither a start nor an answer
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asUser(
                    rig = rig,
                    who = voters[1],
                ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("a poll older than 240 minutes ends by itself (TIMEOUT) on the next touch; a new one can start") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voter = activeIn(room)
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                fx.backdate(poll = Uuid.parse(poll), minutes = 241)
                asUser(rig = rig, who = voter) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                val s = asUser(rig = rig, who = voter) { it.getRoomPollState(room.id.toString()) }.getOrThrow()
                s.poll!!.closeReason shouldBe RoomPollCloseReason.TIMEOUT
                // a poll just under the limit is still open
                val again = start(rig, room, leader).poll!!.id
                fx.backdate(poll = Uuid.parse(again), minutes = 239)
                asUser(rig = rig, who = voter) { it.getRoomPollState(room.id.toString()) }.getOrThrow().poll!!.status shouldBe
                    RoomPollStatus.OPEN
            }
        }

        test("the result of an ended poll stays visible for 12 hours, then the room shows no poll") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voter = activeIn(room)
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                asUser(rig = rig, who = leader) { it.closeRoomPoll(roomId = room.id.toString(), pollId = poll) }.getOrThrow()
                asUser(rig = rig, who = voter) { it.getRoomPollState(room.id.toString()) }.getOrThrow().poll.shouldNotBeNull()
                val old = kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0, 0)
                transaction {
                    ConferenceRoomPollTable.update({ ConferenceRoomPollTable.id eq Uuid.parse(poll) }) { it[closedAt] = old }
                }
                val s = asUser(rig = rig, who = voter) { it.getRoomPollState(room.id.toString()) }.getOrThrow()
                s.poll.shouldBeNull()
                s.version shouldBe "none"
            }
        }

        test("the eager close of the room (ConferenceRoomCloser) closes the poll, stores the result and audits it once") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voters = (1..3).map { activeIn(room) }
            withServiceCaller {
                val poll = Uuid.parse(start(rig, room, leader).poll!!.id)
                val o = optionIds(poll)
                voters.forEach { v ->
                    asUser(
                        rig = rig,
                        who = v,
                    ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll.toString(), optionId = o[0].toString()) }.getOrThrow()
                }
                kotlinx.coroutines.runBlocking {
                    network.lapis.cloud.server.rpc
                        .ConferenceRoomCloser(
                            liveKitAdminClient = rig.liveKit,
                            whiteboardState =
                                network.lapis.cloud.server.conference
                                    .ConferenceWhiteboardState(),
                            notesState =
                                network.lapis.cloud.server.conference
                                    .ConferenceNotesState(),
                            audioGrantState =
                                network.lapis.cloud.server.conference
                                    .ConferenceAudioGrantState(),
                        ).close(
                            roomId = room.id,
                            livekitRoomName = room.name,
                            now =
                                network.lapis.cloud.server.db.DbClock
                                    .nowLocalDateTime(),
                            actorMemberId = leader,
                            actorRole = AccountRole.MEMBER,
                        )
                }
                val row = fx.polls(room).single()
                row[ConferenceRoomPollTable.status] shouldBe RoomPollStatus.CLOSED
                row[ConferenceRoomPollTable.closeReason] shouldBe RoomPollCloseReason.ROOM_ENDED
                row[ConferenceRoomPollTable.resultJson].shouldNotBeNull()
                fx.markers(poll).shouldBeEmpty()
                fx.audits(poll).map { it[network.lapis.cloud.server.db.generated.AuditLogEntryTable.action] } shouldBe
                    listOf(AuditAction.CREATE, AuditAction.UPDATE)
            }
        }

        test("a member erased during the poll (markers deleted, status kept) cannot answer a second time") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val voter = activeIn(room)
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                asUser(rig = rig, who = voter) {
                    it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString())
                }.getOrThrow()
                transaction {
                    network.lapis.cloud.server.dsgvo.RoomPollPersonalData.eraseMember(
                        memberId = voter,
                        mode = network.lapis.cloud.shared.domain.ErasureMode.ANONYMIZE,
                    )
                    network.lapis.cloud.server.db.generated.MemberTable.update(
                        { network.lapis.cloud.server.db.generated.MemberTable.id eq voter },
                    ) {
                        it[anonymizedAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                fx.markers(Uuid.parse(poll)).shouldBeEmpty()
                asUser(rig = rig, who = voter) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[1].toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                fx.answers(Uuid.parse(poll)).shouldHaveSize(1)
            }
        }

        test("an event room and an encounter room are refused for every call, the poll never starts there") {
            val rig = PollRig()
            val leader = fx.member(role = AccountRole.ADMIN)
            val rooms = listOf(fx.eventRoom(creator = leader), fx.encounterRoom(creator = leader))
            withServiceCaller {
                rooms.forEach { room ->
                    fx.join(room = room, who = leader)
                    asUser(
                        rig = rig,
                        who = leader,
                    ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asUser(
                        rig = rig,
                        who = leader,
                    ) { it.getRoomPollState(room.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    fx.polls(room).shouldBeEmpty()
                }
            }
        }

        test("an ended room (to start) and a room that is not there are refused") {
            val rig = PollRig()
            val leader = fx.member()
            val ended = fx.room(creator = leader, ended = true)
            fx.join(room = ended, who = leader)
            withServiceCaller {
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.startRoomPoll(pollInput(room = ended)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.getRoomPollState(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("the input is validated: length, number, duplicates and forbidden characters give a BadRequest") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            withServiceCaller {
                listOf(
                    pollInput(room = room, question = "ab"),
                    pollInput(room = room, options = listOf("Ja")),
                    pollInput(room = room, options = listOf("Ja", "ja")),
                    pollInput(room = room, question = "Frage <b>fett</b>"),
                    pollInput(room = room, options = listOf("Ja", "Ne‮in")),
                    pollInput(room = room, options = listOf("Ja", "Ne\u0007in")),
                ).forEach { bad ->
                    asUser(rig = rig, who = leader) { it.startRoomPoll(bad) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                }
                fx.polls(room).shouldBeEmpty()
            }
        }

        test("the rate limit: the 11th write of a minute is a conflict, per call kind") {
            val rig =
                PollRig(
                    write =
                        network.lapis.cloud.server.federation.FederationInboxRateLimiter(
                            maxRequests = 2,
                            window = kotlin.time.Duration.parse("1m"),
                        ),
                )
            val (room, leader) = newRoomWithLeader()
            withServiceCaller {
                start(rig, room, leader)
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                asUser(
                    rig = rig,
                    who = leader,
                ) { it.startRoomPoll(pollInput(room = room)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                // the other limiters are separate
                asUser(rig = rig, who = leader) { it.getRoomPollState(room.id.toString()) }.isSuccess shouldBe true
            }
        }

        test(
            "weights: members carry the LTR balance of the START of the poll, guests and friends and donors none, no ledger row is written",
        ) {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val member = activeIn(room)
            val guest = guestIn(room, MemberStatus.GUEST)
            val friend = guestIn(room, MemberStatus.FRIEND)
            val donor = guestIn(room, MemberStatus.DONOR)
            fx.grant(member = member, amount = "12.50")
            fx.grant(member = guest, amount = "99.00")
            fx.grant(member = donor, amount = "7.00")
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val o = optionIds(Uuid.parse(poll))
                // LTR received AFTER the start does not count
                fx.grant(
                    member = member,
                    amount = "100.00",
                    at =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime(),
                )
                val ledgerBefore = fx.ledgerRowCount(member)
                listOf(member, guest, friend, donor).forEach { who ->
                    asUser(
                        rig = rig,
                        who = who,
                    ) { it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = o[0].toString()) }.getOrThrow()
                }
                fx.ledgerRowCount(member) shouldBe ledgerBefore
                val rows = fx.answers(Uuid.parse(poll))
                rows shouldHaveSize 4
                val byGroup = rows.groupBy { it[ConferenceRoomPollAnswerTable.voterGroup] }
                byGroup.getValue(RoomPollVoterGroup.GUESTS_AND_FRIENDS).forEach {
                    it[ConferenceRoomPollAnswerTable.weightLtr].compareTo(BigDecimal.ZERO) shouldBe 0
                }
                byGroup.getValue(RoomPollVoterGroup.GUESTS_AND_FRIENDS) shouldHaveSize 3
                byGroup.getValue(RoomPollVoterGroup.MEMBERS).single()[ConferenceRoomPollAnswerTable.weightLtr].toPlainString() shouldBe
                    "12.50"
            }
        }

        test("a viewer of a running stream may answer without being in the room; without a stream he may not") {
            val rig = PollRig()
            val (room, leader) = newRoomWithLeader()
            val viewer = fx.member()
            withServiceCaller {
                val poll = start(rig, room, leader).poll!!.id
                val option = optionIds(Uuid.parse(poll))[0].toString()
                asUser(
                    rig = rig,
                    who = viewer,
                ) { it.getRoomPollState(room.id.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                fx.stream(room = room, starter = leader)
                asUser(rig = rig, who = viewer) { it.getRoomPollState(room.id.toString()) }.getOrThrow().canAnswer shouldBe true
                asUser(rig = rig, who = viewer) {
                    it.answerRoomPoll(roomId = room.id.toString(), pollId = poll, optionId = option)
                }.getOrThrow().voted shouldBe
                    true
            }
        }

        test("the stored answers carry neither a member nor a time (structure of the table)") {
            val columns = ConferenceRoomPollAnswerTable.columns.map { it.name }
            columns.toSet() shouldBe setOf("id", "poll_id", "option_id", "voter_group", "weight_ltr")
        }
    })
