package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import network.lapis.cloud.server.conference.ConferenceHandQueueState
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
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.MemberStatus
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
import kotlin.uuid.Uuid

private val HAND_CONFIG =
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

private fun generous() = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)

/** Process-wide singletons of one test, exactly like `Application.module` builds them. */
private class HandRig(
    val liveKit: ModerationFakeLiveKit = ModerationFakeLiveKit(),
    val state: ConferenceHandQueueState = ConferenceHandQueueState(),
    val own: FederationInboxRateLimiter = generous(),
    val read: FederationInboxRateLimiter = generous(),
    val lowerOne: FederationInboxRateLimiter = generous(),
    val lowerAll: FederationInboxRateLimiter = generous(),
    val config: ConferenceConfig = HAND_CONFIG,
)

private class HandRoom(
    val id: Uuid,
    val name: String,
    val creator: Uuid,
)

private fun Route.registerHandRoutes(rig: HandRig) {
    fun service(call: ApplicationCall) =
        ConferenceHandService(
            call = call,
            liveKitAdminClient = rig.liveKit,
            config = rig.config,
            state = rig.state,
            ownHandRateLimiter = rig.own,
            readRateLimiter = rig.read,
            lowerOneRateLimiter = rig.lowerOne,
            lowerAllRateLimiter = rig.lowerAll,
        )

    fun render(dto: network.lapis.cloud.shared.domain.ConferenceHandQueueDto) = "ok|${dto.seq}|${dto.identities.joinToString(",")}"
    post("/test/raise") { call.respondText(render(service(call).raiseHand(call.request.queryParameters["room"]!!))) }
    post("/test/lower") { call.respondText(render(service(call).lowerHand(call.request.queryParameters["room"]!!))) }
    post("/test/queue") { call.respondText(render(service(call).handQueue(call.request.queryParameters["room"]!!))) }
    post("/test/lower-of") {
        val q = call.request.queryParameters
        call.respondText(render(service(call).lowerHandOf(roomId = q["room"]!!, identity = q["identity"]!!)))
    }
    post("/test/lower-all") { call.respondText(render(service(call).lowerAllHands(call.request.queryParameters["room"]!!))) }
}

private suspend fun ApplicationTestBuilder.act(
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

private fun handApp(
    rig: HandRig,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        install(StatusPages) {
            exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
            exception<ConflictException> { call, cause -> call.respondText("conflict|${cause.message}", status = HttpStatusCode.Conflict) }
        }
        routing { registerHandRoutes(rig) }
    }
    block()
}

/** V1.9.127 -- [ConferenceHandService]: presence, authority, uniform refusals, broadcast, rate limits, event rooms, no audit. */
class ConferenceHandServiceTest :
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
                    it[displayName] = "Hand Test ${id.toString().take(8)}"
                    it[email] = "hand-$id@example.org"
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
        ): HandRoom {
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
                    it[title] = "Handraum"
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
            return HandRoom(id = id, name = name, creator = creator)
        }

        fun present(
            rig: HandRig,
            room: HandRoom,
            who: Uuid,
        ) = rig.liveKit.add(room = room.name, participant = liveParticipant(identity = who.toString()))

        fun handsPackets(rig: HandRig) = rig.liveKit.packets.filter { it.topic == CONFERENCE_HANDS_TOPIC }

        fun auditCount(room: HandRoom): Int =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq room.id }
                    .count()
                    .toInt()
            }

        test("a present member raises and lowers the own hand; the answer is the snapshot and the room gets the broadcast") {
            val rig = HandRig()
            val creator = newMember()
            val a = newMember()
            val b = newMember()
            val room = newRoom(creator)
            present(rig, room, a)
            present(rig, room, b)
            handApp(rig = rig) {
                val r = room.id.toString()
                act(path = "/test/raise", caller = a, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|$a")
                act(path = "/test/raise", caller = b, "room" to r) shouldBe (HttpStatusCode.OK to "ok|2|$a,$b")
                // idempotent: position kept, no new sequence number, no new packet
                act(path = "/test/raise", caller = a, "room" to r) shouldBe (HttpStatusCode.OK to "ok|2|$a,$b")
                handsPackets(rig).size shouldBe 2
                handsPackets(rig).last().payload shouldBe """{"v":1,"e":"${rig.state.epoch}","seq":2,"ids":["$a","$b"]}"""
                handsPackets(rig).forEach {
                    it.destinations shouldBe null
                    it.room shouldBe room.name
                    (it.payload.length <= CONFERENCE_HANDS_MAX_PAYLOAD_BYTES) shouldBe true
                }
                act(path = "/test/lower", caller = a, "room" to r) shouldBe (HttpStatusCode.OK to "ok|3|$b")
                act(path = "/test/raise", caller = a, "room" to r) shouldBe (HttpStatusCode.OK to "ok|4|$b,$a")
                // lowering a hand that is not up changes nothing
                act(path = "/test/lower", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|4|$b,$a")
                handsPackets(rig).size shouldBe 4
            }
        }

        test("raising needs presence in LiveKit; a person who is not in the room can neither raise nor read the queue") {
            val rig = HandRig()
            val creator = newMember()
            val outsider = newMember()
            val room = newRoom(creator)
            handApp(rig = rig) {
                val denied = HttpStatusCode.Forbidden to "forbidden"
                act(path = "/test/raise", caller = outsider, "room" to room.id.toString()) shouldBe denied
                act(path = "/test/queue", caller = outsider, "room" to room.id.toString()) shouldBe denied
                rig.state.contains(roomId = room.id, identity = outsider.toString()) shouldBe false
                // a LiveKit outage is a Conflict, never a granted hand
                rig.liveKit.failList = true
                act(path = "/test/raise", caller = outsider, "room" to room.id.toString()).first shouldBe HttpStatusCode.Conflict
                rig.state
                    .snapshot(room.id)
                    .identities
                    .shouldBeEmpty()
            }
        }

        test("a moderator reads the queue without being present; a present participant reads it too") {
            val rig = HandRig()
            val creator = newMember()
            val board = newMember(AccountRole.BOARD)
            val a = newMember()
            val room = newRoom(creator)
            present(rig, room, a)
            handApp(rig = rig) {
                val r = room.id.toString()
                act(path = "/test/raise", caller = a, "room" to r)
                act(path = "/test/queue", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|$a")
                act(path = "/test/queue", caller = board, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|$a")
                act(path = "/test/queue", caller = a, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|$a")
            }
        }

        test("moderation: the creator and BOARD/ADMIN lower one or all hands; an ordinary member cannot, whatever the identity") {
            val rig = HandRig()
            val creator = newMember()
            val admin = newMember(AccountRole.ADMIN)
            val ordinary = newMember()
            val a = newMember()
            val b = newMember()
            val c = newMember()
            val room = newRoom(creator)
            listOf(a, b, c, ordinary).forEach { present(rig, room, it) }
            handApp(rig = rig) {
                val r = room.id.toString()
                listOf(a, b, c).forEach { act(path = "/test/raise", caller = it, "room" to r) }
                val denied = HttpStatusCode.Forbidden to "forbidden"
                act(path = "/test/lower-of", caller = ordinary, "room" to r, "identity" to a.toString()) shouldBe denied
                act(path = "/test/lower-all", caller = ordinary, "room" to r) shouldBe denied
                // lowerHand binds to the caller: there is no way to lower somebody else's hand through it
                act(path = "/test/lower", caller = ordinary, "room" to r).first shouldBe HttpStatusCode.OK
                rig.state.snapshot(room.id).identities shouldContainExactly listOf(a.toString(), b.toString(), c.toString())
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to a.toString()) shouldBe
                    (HttpStatusCode.OK to "ok|4|$b,$c")
                // a target that is not in the queue: silent no-op, same snapshot, no packet
                val before = handsPackets(rig).size
                act(path = "/test/lower-of", caller = admin, "room" to r, "identity" to a.toString()) shouldBe
                    (HttpStatusCode.OK to "ok|4|$b,$c")
                handsPackets(rig).size shouldBe before
                // an event guest identity is a valid target (no-op here), garbage is refused uniformly
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to "evg-${Uuid.random()}").first shouldBe
                    HttpStatusCode.OK
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to "not an identity") shouldBe denied
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to "\",\"x") shouldBe denied
                act(path = "/test/lower-all", caller = admin, "room" to r) shouldBe (HttpStatusCode.OK to "ok|5|")
                handsPackets(rig).last().payload shouldBe """{"v":1,"e":"${rig.state.epoch}","seq":5,"ids":[]}"""
            }
        }

        test("every refusal is the identical Forbidden -- unknown, ended, encounter room, bad id, applicant/withdrawn status") {
            val rig = HandRig()
            val creator = newMember()
            val applicant = newMember(status = MemberStatus.APPLICATION)
            val withdrawn = newMember(status = MemberStatus.WITHDRAWN)
            val member = newMember()
            val ended = newRoom(creator, ended = true)
            val encounter = newRoom(creator, encounter = true)
            val room = newRoom(creator)
            present(rig, room, applicant)
            present(rig, room, withdrawn)
            listOf(ended, encounter).forEach { present(rig, it, member) }
            handApp(rig = rig) {
                val denied = HttpStatusCode.Forbidden to "forbidden"
                listOf("/test/raise", "/test/lower", "/test/queue", "/test/lower-all").forEach { path ->
                    act(path = path, caller = creator, "room" to Uuid.random().toString()) shouldBe denied
                    act(path = path, caller = creator, "room" to "not-a-uuid") shouldBe denied
                    act(path = path, caller = creator, "room" to ended.id.toString()) shouldBe denied
                    act(path = path, caller = creator, "room" to encounter.id.toString()) shouldBe denied
                    act(path = path, caller = member, "room" to ended.id.toString()) shouldBe denied
                    act(path = path, caller = member, "room" to encounter.id.toString()) shouldBe denied
                    act(path = path, caller = applicant, "room" to room.id.toString()) shouldBe denied
                    act(path = path, caller = withdrawn, "room" to room.id.toString()) shouldBe denied
                }
                act(path = "/test/lower-of", caller = creator, "room" to Uuid.random().toString(), "identity" to member.toString()) shouldBe
                    denied
                handsPackets(rig).shouldBeEmpty()
            }
        }

        test("GUEST and FRIEND members who are in the room may raise a hand") {
            val rig = HandRig()
            val creator = newMember()
            val guest = newMember(status = MemberStatus.GUEST)
            val friend = newMember(status = MemberStatus.FRIEND)
            val room = newRoom(creator)
            present(rig, room, guest)
            present(rig, room, friend)
            handApp(rig = rig) {
                act(path = "/test/raise", caller = guest, "room" to room.id.toString()) shouldBe (HttpStatusCode.OK to "ok|1|$guest")
                act(path = "/test/raise", caller = friend, "room" to room.id.toString()) shouldBe
                    (HttpStatusCode.OK to "ok|2|$guest,$friend")
            }
        }

        test("an event room: a participant with an open participation may raise, a stranger and a registered-but-absent member may not") {
            val rig = HandRig()
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
            handApp(rig = rig) {
                val r = room.id.toString()
                act(path = "/test/raise", caller = stranger, "room" to r) shouldBe (HttpStatusCode.Forbidden to "forbidden")
                act(path = "/test/queue", caller = stranger, "room" to r) shouldBe (HttpStatusCode.Forbidden to "forbidden")
                act(path = "/test/raise", caller = inEvent, "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|$inEvent")
                act(path = "/test/lower-all", caller = creator, "room" to r) shouldBe (HttpStatusCode.OK to "ok|2|")
            }
        }

        test("a failed broadcast does not fail the call (the answer carries the snapshot); the queue is full at the cap") {
            val rig = HandRig(state = ConferenceHandQueueState(maxPerRoom = 2))
            val creator = newMember()
            val people = List(3) { newMember() }
            val room = newRoom(creator)
            people.forEach { present(rig, room, it) }
            rig.liveKit.failSend = true
            handApp(rig = rig) {
                val r = room.id.toString()
                act(path = "/test/raise", caller = people[0], "room" to r) shouldBe (HttpStatusCode.OK to "ok|1|${people[0]}")
                act(path = "/test/raise", caller = people[1], "room" to r).first shouldBe HttpStatusCode.OK
                val full = act(path = "/test/raise", caller = people[2], "room" to r)
                full.first shouldBe HttpStatusCode.Conflict
                rig.state.contains(roomId = room.id, identity = people[2].toString()) shouldBe false
            }
        }

        test("rate limits are Conflicts, keyed per person+room / per room") {
            val rig =
                HandRig(
                    own = FederationInboxRateLimiter(maxRequests = 2, window = 10.minutes),
                    lowerOne = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                    lowerAll = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                    read = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                )
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator)
            val other = newRoom(creator)
            present(rig, room, a)
            present(rig, other, a)
            handApp(rig = rig) {
                val r = room.id.toString()
                act(path = "/test/raise", caller = a, "room" to r).first shouldBe HttpStatusCode.OK
                act(path = "/test/lower", caller = a, "room" to r).first shouldBe HttpStatusCode.OK
                act(path = "/test/raise", caller = a, "room" to r).first shouldBe HttpStatusCode.Conflict
                // another room has its own budget
                act(path = "/test/raise", caller = a, "room" to other.id.toString()).first shouldBe HttpStatusCode.OK
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to a.toString()).first shouldBe HttpStatusCode.OK
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to a.toString()).first shouldBe
                    HttpStatusCode.Conflict
                act(path = "/test/lower-all", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
                act(path = "/test/lower-all", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                act(path = "/test/queue", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
                act(path = "/test/queue", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
            }
        }

        test("a server without conference configuration refuses every call with a Conflict") {
            val rig = HandRig(config = ConferenceConfig.load { null })
            val creator = newMember()
            val room = newRoom(creator)
            handApp(rig = rig) {
                listOf("/test/raise", "/test/lower", "/test/queue", "/test/lower-all").forEach {
                    act(path = it, caller = creator, "room" to room.id.toString()).first shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("no audit entry is written -- not for raising, not for a moderator lowering a hand") {
            val rig = HandRig()
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator)
            present(rig, room, a)
            handApp(rig = rig) {
                val r = room.id.toString()
                act(path = "/test/raise", caller = a, "room" to r)
                act(path = "/test/lower-of", caller = creator, "room" to r, "identity" to a.toString())
                act(path = "/test/raise", caller = a, "room" to r)
                act(path = "/test/lower-all", caller = creator, "room" to r)
                auditCount(room) shouldBe 0
                // and the packets carry identities only, never a display name
                handsPackets(rig).forEach { it.payload shouldNotContain "Hand Test" }
            }
        }
    })
