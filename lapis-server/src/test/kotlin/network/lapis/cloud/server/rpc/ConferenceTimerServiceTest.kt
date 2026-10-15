package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceTimerState
import network.lapis.cloud.server.conference.ModerationFakeLiveKit
import network.lapis.cloud.server.conference.liveParticipant
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.encounter.reactionSetCsv
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_TOPIC
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

private val TIMER_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

private fun timerGenerous() = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)

@OptIn(ExperimentalTime::class)
private class TimerRig(
    val liveKit: ModerationFakeLiveKit = ModerationFakeLiveKit(),
    val clock: TestTimeSource = TestTimeSource(),
    val state: ConferenceTimerState = ConferenceTimerState(timeSource = clock, wallNowMillis = { 1_760_000_000_000L }),
    val control: FederationInboxRateLimiter = timerGenerous(),
    val read: FederationInboxRateLimiter = timerGenerous(),
    val config: ConferenceConfig = TIMER_CONFIG,
)

private class TimerRoom(
    val id: Uuid,
    val name: String,
)

private fun Route.registerTimerRoutes(rig: TimerRig) {
    fun service(call: ApplicationCall) =
        ConferenceTimerService(
            call = call,
            liveKitAdminClient = rig.liveKit,
            config = rig.config,
            state = rig.state,
            controlRateLimiter = rig.control,
            readRateLimiter = rig.read,
        )

    fun render(dto: ConferenceTimerDto) = "ok|${dto.seq}|${dto.status}|${dto.initialDurationMs}|${dto.remainingMs}"
    post("/test/start") {
        val q = call.request.queryParameters
        call.respondText(render(service(call).startTimer(roomId = q["room"]!!, durationSeconds = q["sec"]!!.toInt())))
    }
    post("/test/pause") { call.respondText(render(service(call).pauseTimer(call.request.queryParameters["room"]!!))) }
    post("/test/resume") { call.respondText(render(service(call).resumeTimer(call.request.queryParameters["room"]!!))) }
    post("/test/stop") { call.respondText(render(service(call).stopTimer(call.request.queryParameters["room"]!!))) }
    post("/test/extend") {
        val q = call.request.queryParameters
        call.respondText(render(service(call).extendTimer(roomId = q["room"]!!, extensionSeconds = q["sec"]!!.toInt())))
    }
    post("/test/state") { call.respondText(render(service(call).timerState(call.request.queryParameters["room"]!!))) }
}

private suspend fun ApplicationTestBuilder.timerAct(
    path: String,
    caller: Uuid,
    vararg params: Pair<String, String>,
): Pair<HttpStatusCode, String> {
    val response =
        client.post(path) {
            header("X-Member-Id", caller.toString())
            params.forEach { url.parameters.append(it.first, it.second) }
        }
    return response.status to response.bodyAsText()
}

private fun timerApp(
    rig: TimerRig,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        install(StatusPages) {
            exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
            exception<ConflictException> { call, cause -> call.respondText("conflict|${cause.message}", status = HttpStatusCode.Conflict) }
            exception<BadRequestException> { call, cause -> call.respondText("bad|${cause.message}", status = HttpStatusCode.BadRequest) }
        }
        routing { registerTimerRoutes(rig) }
    }
    block()
}

/** V1.9.133 -- [ConferenceTimerService]: authority, uniform refusals, state machine, broadcast, rate limits, event rooms, no audit. */
@OptIn(ExperimentalTime::class)
class ConferenceTimerServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdSpaceIds = mutableListOf<Uuid>()
        val eventFixtures = EventOnlineFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            eventFixtures.cleanUp()
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                val roomIds =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.createdByMemberId inList createdMemberIds }.map {
                        it[ConferenceRoomTable.id]
                    }
                ConferenceParticipationTable.deleteWhere {
                    (ConferenceParticipationTable.memberId inList createdMemberIds) or (ConferenceParticipationTable.roomId inList roomIds)
                }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.createdByMemberId inList createdMemberIds }
                EncounterSpaceTable.deleteWhere { EncounterSpaceTable.id inList createdSpaceIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Timer Test ${id.toString().take(8)}"
                    it[email] = "timer-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun newRoom(
            creator: Uuid,
            ended: Boolean = false,
            encounter: Boolean = false,
        ): TimerRoom {
            val id = Uuid.random()
            val name = "lc-$id"
            val spaceId =
                if (encounter) {
                    Uuid.random().also { space ->
                        transaction {
                            EncounterSpaceTable.insert {
                                it[EncounterSpaceTable.id] = space
                                it[title] = "Test"
                                it[description] = ""
                                it[themeKey] = "CHURCH"
                                it[profile] = "CHURCH_SERVICE"
                                it[reactionSet] = reactionSetCsv(EncounterReactionOption.defaultsFor(EncounterProfile.CHURCH_SERVICE))
                                it[notifyMode] = "NONE"
                                it[tablesEnabled] = false
                                it[tableCount] = 4.toShort()
                                it[tableSeats] = 6.toShort()
                                it[mode] = "SERVICE"
                                it[guestPolicy] = "MEMBERS_ONLY"
                                it[maxParticipants] = null
                                it[closedNotice] = null
                                it[createdAt] = DbClock.nowLocalDateTime()
                                it[createdByMemberId] = creator
                                it[updatedAt] = null
                                it[archivedAt] = null
                            }
                        }
                        createdSpaceIds += space
                    }
                } else {
                    null
                }
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Timerraum"
                    it[description] = ""
                    it[livekitRoomName] = name
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                    it[encounterSpaceId] = spaceId
                }
            }
            return TimerRoom(id = id, name = name)
        }

        fun present(
            rig: TimerRig,
            room: TimerRoom,
            who: Uuid,
        ) = rig.liveKit.add(room = room.name, participant = liveParticipant(identity = who.toString()))

        fun timerPackets(rig: TimerRig) = rig.liveKit.packets.filter { it.topic == CONFERENCE_TIMER_TOPIC }

        fun auditCount(room: TimerRoom): Int =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq room.id }
                    .count()
                    .toInt()
            }

        test(
            "the room creator, BOARD and ADMIN start, pause, resume, extend and stop; every success is broadcast and answered with the snapshot",
        ) {
            val rig = TimerRig()
            val creator = newMember()
            val room = newRoom(creator)
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "120") shouldBe
                    (HttpStatusCode.OK to "ok|1|RUNNING|120000|120000")
                rig.clock += 20.seconds
                timerAct(path = "/test/pause", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|2|PAUSED|120000|100000")
                timerAct(path = "/test/resume", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|3|RUNNING|120000|100000")
                timerAct(path = "/test/extend", caller = creator, "room" to r, "sec" to "60") shouldBe
                    (HttpStatusCode.OK to "ok|4|RUNNING|180000|160000")
                timerAct(path = "/test/stop", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|5|OFF|0|0")
                timerPackets(rig).size shouldBe 5
                timerPackets(rig).forEach {
                    it.destinations shouldBe null
                    it.room shouldBe room.name
                    (it.payload.length <= CONFERENCE_TIMER_MAX_PAYLOAD_BYTES) shouldBe true
                }
                timerPackets(rig)[0].payload shouldBe
                    "{\"v\":1,\"e\":\"${rig.state.epoch}\",\"seq\":1,\"s\":\"R\",\"d\":120000,\"rem\":120000,\"t\":1760000000000}"
                val board = newMember(AccountRole.BOARD)
                val admin = newMember(AccountRole.ADMIN)
                timerAct(path = "/test/start", caller = board, "room" to r, "sec" to "30").first shouldBe HttpStatusCode.OK
                timerAct(path = "/test/stop", caller = admin, "room" to r).first shouldBe HttpStatusCode.OK
            }
        }

        test("an ordinary member and a guest are refused every control call with the identical Forbidden, even when present") {
            val rig = TimerRig()
            val creator = newMember()
            val ordinary = newMember()
            val guest = newMember(status = MemberStatus.GUEST)
            val room = newRoom(creator)
            present(rig, room, ordinary)
            present(rig, room, guest)
            timerApp(rig = rig) {
                val r = room.id.toString()
                val denied = HttpStatusCode.Forbidden to "forbidden"
                listOf(ordinary, guest).forEach { who ->
                    timerAct(path = "/test/start", caller = who, "room" to r, "sec" to "60") shouldBe denied
                    timerAct(path = "/test/pause", caller = who, "room" to r) shouldBe denied
                    timerAct(path = "/test/resume", caller = who, "room" to r) shouldBe denied
                    timerAct(path = "/test/stop", caller = who, "room" to r) shouldBe denied
                    timerAct(path = "/test/extend", caller = who, "room" to r, "sec" to "30") shouldBe denied
                    // out-of-range input from a non-moderator is still the same refusal: the authority check comes first
                    timerAct(path = "/test/start", caller = who, "room" to r, "sec" to "5") shouldBe denied
                }
                rig.state.snapshot(room.id).seq shouldBe 0L
                timerPackets(rig).shouldBeEmpty()
            }
        }

        test(
            "every refusal is the identical Forbidden -- unknown, ended, encounter room, breakout id, bad id, applicant/withdrawn status",
        ) {
            val rig = TimerRig()
            val creator = newMember()
            val applicant = newMember(status = MemberStatus.APPLICATION)
            val withdrawn = newMember(status = MemberStatus.WITHDRAWN)
            val ended = newRoom(creator, ended = true)
            val encounter = newRoom(creator, encounter = true)
            val room = newRoom(creator)
            timerApp(rig = rig) {
                val denied = HttpStatusCode.Forbidden to "forbidden"
                listOf("/test/start", "/test/pause", "/test/resume", "/test/stop", "/test/extend", "/test/state").forEach { path ->
                    val extra = arrayOf("sec" to "60")
                    // a breakout room id is not a row of the conference-room table: it is "unknown" like any other id
                    timerAct(path = path, caller = creator, "room" to Uuid.random().toString(), *extra) shouldBe denied
                    timerAct(path = path, caller = creator, "room" to "not-a-uuid", *extra) shouldBe denied
                    timerAct(path = path, caller = creator, "room" to ended.id.toString(), *extra) shouldBe denied
                    timerAct(path = path, caller = creator, "room" to encounter.id.toString(), *extra) shouldBe denied
                    timerAct(path = path, caller = applicant, "room" to room.id.toString(), *extra) shouldBe denied
                    timerAct(path = path, caller = withdrawn, "room" to room.id.toString(), *extra) shouldBe denied
                }
                timerPackets(rig).shouldBeEmpty()
            }
        }

        test(
            "timerState: a moderator reads without being present, a present member reads, an absent member is refused; the snapshot is fresh",
        ) {
            val rig = TimerRig()
            val creator = newMember()
            val a = newMember()
            val outsider = newMember()
            val room = newRoom(creator)
            present(rig, room, a)
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/state", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|0|OFF|0|0")
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "60")
                rig.clock += 15.seconds
                timerAct(path = "/test/state", caller = a, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|RUNNING|60000|45000")
                timerAct(path = "/test/state", caller = outsider, "room" to r) shouldBe (HttpStatusCode.Forbidden to "forbidden")
                rig.liveKit.failList = true
                timerAct(path = "/test/state", caller = a, "room" to r).first shouldBe HttpStatusCode.Conflict
                // a moderator does not need LiveKit for a read
                timerAct(path = "/test/state", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
            }
        }

        test("state-machine errors are Conflicts, range errors are BadRequests, and neither echoes the input") {
            val rig = TimerRig()
            val creator = newMember()
            val room = newRoom(creator)
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/pause", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                timerAct(path = "/test/resume", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                timerAct(path = "/test/extend", caller = creator, "room" to r, "sec" to "30").first shouldBe HttpStatusCode.Conflict
                listOf("9", "3601", "-5", "0").forEach { sec ->
                    val (status, body) = timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to sec)
                    status shouldBe HttpStatusCode.BadRequest
                    body shouldNotContain sec
                }
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "10").first shouldBe HttpStatusCode.OK
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "60").first shouldBe HttpStatusCode.Conflict
                timerAct(path = "/test/stop", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "3600").first shouldBe HttpStatusCode.OK
                // only 30 / 60 / 300 are extension steps; and the remaining time may not pass 60 minutes
                listOf("45", "0", "-30", "3600").forEach { sec ->
                    timerAct(path = "/test/extend", caller = creator, "room" to r, "sec" to sec).first shouldBe HttpStatusCode.BadRequest
                }
                timerAct(path = "/test/extend", caller = creator, "room" to r, "sec" to "30").first shouldBe HttpStatusCode.BadRequest
                // nothing of this changed the state
                rig.state.snapshot(room.id).remainingMs shouldBe 3_600_000L
            }
        }

        test("an expired timer can be extended into a new run or started again") {
            val rig = TimerRig()
            val creator = newMember()
            val room = newRoom(creator)
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "10")
                rig.clock += 11.seconds
                timerAct(path = "/test/state", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|EXPIRED|10000|0")
                timerAct(path = "/test/pause", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                timerAct(path = "/test/extend", caller = creator, "room" to r, "sec" to "60") shouldBe
                    (HttpStatusCode.OK to "ok|2|RUNNING|60000|60000")
                rig.clock += 61.seconds
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "30") shouldBe
                    (HttpStatusCode.OK to "ok|3|RUNNING|30000|30000")
            }
        }

        test("a failed broadcast does not fail the call; no-ops neither change nor broadcast") {
            val rig = TimerRig()
            val creator = newMember()
            val room = newRoom(creator)
            rig.liveKit.failSend = true
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "60") shouldBe
                    (HttpStatusCode.OK to "ok|1|RUNNING|60000|60000")
                rig.liveKit.failSend = false
                timerAct(path = "/test/resume", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|RUNNING|60000|60000")
                timerAct(path = "/test/stop", caller = creator, "room" to newRoom(creator).id.toString()) shouldBe
                    (HttpStatusCode.OK to "ok|0|OFF|0|0")
                timerPackets(rig).shouldBeEmpty()
            }
        }

        test("rate limits are Conflicts: control per room (the 21st action), reading per member (the 31st read)") {
            val rig =
                TimerRig(
                    control = FederationInboxRateLimiter(maxRequests = 20, window = 10.minutes),
                    read = FederationInboxRateLimiter(maxRequests = 30, window = 10.minutes),
                )
            val creator = newMember()
            val room = newRoom(creator)
            val other = newRoom(creator)
            timerApp(rig = rig) {
                val r = room.id.toString()
                repeat(20) { timerAct(path = "/test/stop", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK }
                timerAct(path = "/test/stop", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                // another room has its own budget
                timerAct(path = "/test/stop", caller = creator, "room" to other.id.toString()).first shouldBe HttpStatusCode.OK
                repeat(30) { timerAct(path = "/test/state", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK }
                timerAct(path = "/test/state", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
            }
        }

        test("a non-moderator is refused BEFORE the rate limit is consumed (no oracle through the limiter)") {
            val rig = TimerRig(control = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes))
            val creator = newMember()
            val ordinary = newMember()
            val room = newRoom(creator)
            timerApp(rig = rig) {
                val r = room.id.toString()
                repeat(
                    5,
                ) { timerAct(path = "/test/stop", caller = ordinary, "room" to r) shouldBe (HttpStatusCode.Forbidden to "forbidden") }
                timerAct(path = "/test/stop", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
            }
        }

        test("a server without conference configuration refuses every call with a Conflict") {
            val rig = TimerRig(config = ConferenceConfig.load { null })
            val creator = newMember()
            val room = newRoom(creator)
            timerApp(rig = rig) {
                listOf("/test/start", "/test/pause", "/test/resume", "/test/stop", "/test/extend", "/test/state").forEach {
                    timerAct(path = it, caller = creator, "room" to room.id.toString(), "sec" to "60").first shouldBe
                        HttpStatusCode.Conflict
                }
            }
        }

        test("an event room: a moderator controls it, an event participant reads it, a stranger gets the uniform refusal") {
            val rig = TimerRig()
            val creator = eventFixtures.member(role = AccountRole.ADMIN)
            val inEvent = eventFixtures.member()
            val stranger = eventFixtures.member()
            val eventId = eventFixtures.event(createdBy = creator)
            val room = newRoom(creator)
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventConferenceTable.insert {
                    it[EventConferenceTable.eventId] = eventId
                    it[conferenceRoomId] = room.id
                    it[livekitRoomName] = room.name
                    it[status] = EventConferenceStatus.ACTIVE
                    it[claimToken] = Uuid.random()
                    it[claimedAt] = now
                    it[windowOpensAt] = LocalDateTime(2030, 6, 15, 17, 45)
                    it[windowClosesAt] = LocalDateTime(2030, 6, 15, 21, 0)
                    it[createdAt] = now
                    it[endedAt] = null
                }
                ConferenceParticipationTable.insert {
                    it[ConferenceParticipationTable.id] = Uuid.random()
                    it[roomId] = room.id
                    it[memberId] = inEvent
                    it[role] = ConferenceRole.PARTICIPANT
                    it[joinedAt] = now
                    it[leftAt] = null
                }
            }
            present(rig, room, inEvent)
            present(rig, room, stranger)
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "60").first shouldBe HttpStatusCode.OK
                timerAct(path = "/test/state", caller = inEvent, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|RUNNING|60000|60000")
                timerAct(path = "/test/state", caller = stranger, "room" to r) shouldBe (HttpStatusCode.Forbidden to "forbidden")
                timerAct(path = "/test/start", caller = stranger, "room" to r, "sec" to "60") shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                timerAct(path = "/test/stop", caller = inEvent, "room" to r) shouldBe (HttpStatusCode.Forbidden to "forbidden")
            }
        }

        test("no audit entry is written and the packets never carry a name or an id") {
            val rig = TimerRig()
            val creator = newMember()
            val room = newRoom(creator)
            timerApp(rig = rig) {
                val r = room.id.toString()
                timerAct(path = "/test/start", caller = creator, "room" to r, "sec" to "60")
                timerAct(path = "/test/pause", caller = creator, "room" to r)
                timerAct(path = "/test/stop", caller = creator, "room" to r)
                auditCount(room) shouldBe 0
                timerPackets(rig).forEach {
                    it.payload shouldNotContain "Timer Test"
                    it.payload shouldNotContain creator.toString()
                    it.payload shouldNotContain room.id.toString()
                }
            }
        }
    })
