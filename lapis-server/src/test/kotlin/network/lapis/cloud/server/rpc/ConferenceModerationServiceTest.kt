package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceConfig
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
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.encounter.reactionSetCsv
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_TOPIC
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
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

private val MODERATION_CONFIG =
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

/** Process-wide singletons of one test, exactly like `Application.module` builds them. */
private class ModerationRig(
    val liveKit: ModerationFakeLiveKit = ModerationFakeLiveKit(),
    val grants: ConferenceAudioGrantState = ConferenceAudioGrantState(),
    val muteAll: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
    val muteOne: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
    val requestUnmute: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
    val policy: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
    val preview: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
)

private class RoomFx(
    val id: Uuid,
    val name: String,
    val creator: Uuid,
)

private fun Route.registerModerationRoutes(rig: ModerationRig) {
    fun service(call: io.ktor.server.application.ApplicationCall) =
        ConferenceModerationService(
            call = call,
            liveKitAdminClient = rig.liveKit,
            config = MODERATION_CONFIG,
            audioGrantState = rig.grants,
            muteAllRateLimiter = rig.muteAll,
            muteOneRateLimiter = rig.muteOne,
            requestUnmuteRateLimiter = rig.requestUnmute,
            policyRateLimiter = rig.policy,
            previewRateLimiter = rig.preview,
        )

    fun conference(call: io.ktor.server.application.ApplicationCall) =
        ConferenceService(
            call = call,
            liveKitAdminClient = rig.liveKit,
            createRoomRateLimiter = LoginRateLimiter(),
            config = MODERATION_CONFIG,
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
            roomVotingStateRateLimiter = FederationInboxRateLimiter(maxRequests = 90, window = 1.minutes),
            audioGrantState = rig.grants,
        )
    post("/test/mute") {
        service(call).muteParticipant(roomId = call.request.queryParameters["room"]!!, memberId = call.request.queryParameters["member"]!!)
        call.respondText("ok")
    }
    post("/test/mute-all") {
        call.respondText("ok|${service(call).muteAll(call.request.queryParameters["room"]!!)}")
    }
    post("/test/preview") {
        call.respondText("ok|${service(call).estimateMuteAll(call.request.queryParameters["room"]!!)}")
    }
    post("/test/request-unmute") {
        service(call).requestUnmute(roomId = call.request.queryParameters["room"]!!, memberId = call.request.queryParameters["member"]!!)
        call.respondText("ok")
    }
    post("/test/policy") {
        val q = call.request.queryParameters
        val dto =
            service(call).setRoomAudioPolicy(
                roomId = q["room"]!!,
                muteOnJoin = q["muteOnJoin"]!!.toBoolean(),
                participantsMayUnmute = q["mayUnmute"]!!.toBoolean(),
            )
        call.respondText("ok|${dto.muteOnJoin}|${dto.participantsMayUnmute}|${dto.myRole}")
    }
    post("/test/join") {
        call.respondText(conference(call).joinRoom(roomId = call.request.queryParameters["room"]!!, guestConsent = null).token)
    }
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

private fun moderationApp(
    rig: ModerationRig,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        install(StatusPages) {
            exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
            exception<ConflictException> { call, cause -> call.respondText("conflict|${cause.message}", status = HttpStatusCode.Conflict) }
        }
        routing { registerModerationRoutes(rig) }
    }
    block()
}

/** Decodes the claim map `video` of a LiveKit participant JWT. */
private fun videoGrantOf(jwt: String): Map<String, Any?> {
    val payload =
        String(
            java.util.Base64
                .getUrlDecoder()
                .decode(jwt.split('.')[1]),
        )
    val video =
        kotlinx.serialization.json.Json
            .parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
    val grant = video["video"] as kotlinx.serialization.json.JsonObject
    return grant.mapValues { (_, v) ->
        when (v) {
            is kotlinx.serialization.json.JsonArray -> v.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            is kotlinx.serialization.json.JsonPrimitive -> v.content
            else -> v.toString()
        }
    }
}

class ConferenceModerationServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdSpaceIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                val roomIds =
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.createdByMemberId inList createdMemberIds
                        }.map { it[ConferenceRoomTable.id] }
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
                    it[displayName] = "Moderation Test ${id.toString().take(8)}"
                    it[email] = "mod-$id@example.org"
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
            locked: Boolean = false,
            muteOnJoin: Boolean = false,
            ended: Boolean = false,
            encounter: Boolean = false,
        ): RoomFx {
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
                    it[title] = "Moderationsraum"
                    it[description] = ""
                    it[livekitRoomName] = name
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                    it[ConferenceRoomTable.muteOnJoin] = muteOnJoin
                    it[participantsMayUnmute] = !locked
                    it[encounterSpaceId] = spaceId
                }
            }
            return RoomFx(id = id, name = name, creator = creator)
        }

        fun participate(
            room: RoomFx,
            member: Uuid,
            open: Boolean = true,
        ) {
            transaction {
                ConferenceParticipationTable.insert {
                    it[ConferenceParticipationTable.id] = Uuid.random()
                    it[roomId] = room.id
                    it[memberId] = member
                    it[role] = if (member == room.creator) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT
                    it[joinedAt] = DbClock.nowLocalDateTime()
                    it[leftAt] = if (open) null else DbClock.nowLocalDateTime()
                }
            }
        }

        fun live(
            rig: ModerationRig,
            room: RoomFx,
            member: Uuid,
            microphoneOpen: Boolean = true,
            cameraOpen: Boolean = false,
        ) {
            participate(room, member)
            rig.liveKit.add(
                room = room.name,
                participant = liveParticipant(identity = member.toString(), microphoneOpen = microphoneOpen, cameraOpen = cameraOpen),
            )
        }

        fun audits(room: RoomFx): List<String> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq room.id) and (AuditLogEntryTable.entityType eq AuditEntityType.CONFERENCE_ROOM)
                    }.map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            }

        fun roomRow(room: RoomFx) = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.id }.single() }

        // -- Authority ------------------------------------------------------------------------------------------------------------

        test("the room creator and BOARD/ADMIN may mute; an ordinary member, a FRIEND and a GUEST all get the identical refusal") {
            val rig = ModerationRig()
            val creator = newMember()
            val board = newMember(AccountRole.BOARD)
            val admin = newMember(AccountRole.ADMIN)
            val ordinary = newMember()
            val friend = newMember(status = MemberStatus.FRIEND)
            val guest = newMember(status = MemberStatus.GUEST)
            val target = newMember()
            val room = newRoom(creator)
            live(rig, room, target)
            moderationApp(rig = rig) {
                listOf(creator, board, admin).forEach { moderator ->
                    rig.liveKit.roster(room.name).forEach { rig.liveKit.participants[room.name]!!.remove(it) }
                    rig.liveKit.add(room = room.name, participant = liveParticipant(identity = target.toString()))
                    act(path = "/test/mute", caller = moderator, "room" to room.id.toString(), "member" to target.toString()) shouldBe
                        (HttpStatusCode.OK to "ok")
                }
                rig.liveKit.mutes.size shouldBe 3
                val refusals =
                    listOf(ordinary, friend, guest).map {
                        act(path = "/test/mute", caller = it, "room" to room.id.toString(), "member" to target.toString())
                    }
                refusals.forEach { it shouldBe (HttpStatusCode.Forbidden to "forbidden") }
                rig.liveKit.mutes.size shouldBe 3
            }
        }

        test(
            "every other refusal is the same forbidden answer: unknown, ended or encounter room, bad ids, target not in room, creator, oneself",
        ) {
            val rig = ModerationRig()
            val creator = newMember()
            val target = newMember()
            val outsider = newMember()
            val room = newRoom(creator)
            live(rig, room, target)
            val ended = newRoom(creator, ended = true)
            participate(ended, target)
            val encounter = newRoom(creator, encounter = true)
            participate(encounter, target)
            val left = newMember()
            participate(room, left, open = false)
            moderationApp(rig = rig) {
                val cases =
                    listOf(
                        Uuid.random().toString() to target.toString(),
                        "not-a-uuid" to target.toString(),
                        room.id.toString() to "not-a-uuid",
                        ended.id.toString() to target.toString(),
                        encounter.id.toString() to target.toString(),
                        room.id.toString() to outsider.toString(),
                        room.id.toString() to left.toString(),
                        room.id.toString() to creator.toString(),
                    )
                cases.forEach { (roomId, member) ->
                    act(path = "/test/mute", caller = creator, "room" to roomId, "member" to member) shouldBe
                        (HttpStatusCode.Forbidden to "forbidden")
                }
                // a creator cannot mute themselves either (checked above as the creator target) -- and the room-level calls refuse the same way
                act(path = "/test/mute-all", caller = creator, "room" to ended.id.toString()) shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                act(path = "/test/mute-all", caller = creator, "room" to encounter.id.toString()) shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to encounter.id.toString(),
                    "muteOnJoin" to "true",
                    "mayUnmute" to "false",
                ) shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                rig.liveKit.mutes.shouldBeEmpty()
                audits(ended).shouldBeEmpty()
            }
        }

        test("peer rule: an ordinary room creator cannot mute BOARD or ADMIN; BOARD may mute ADMIN and ADMIN may mute ADMIN") {
            val rig = ModerationRig()
            val creator = newMember()
            val board = newMember(AccountRole.BOARD)
            val admin = newMember(AccountRole.ADMIN)
            val otherAdmin = newMember(AccountRole.ADMIN)
            val room = newRoom(creator)
            live(rig, room, board)
            live(rig, room, admin)
            live(rig, room, otherAdmin)
            moderationApp(rig = rig) {
                act(path = "/test/mute", caller = creator, "room" to room.id.toString(), "member" to board.toString()) shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                act(path = "/test/mute", caller = creator, "room" to room.id.toString(), "member" to admin.toString()) shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                act(path = "/test/request-unmute", caller = creator, "room" to room.id.toString(), "member" to admin.toString()) shouldBe
                    (HttpStatusCode.Forbidden to "forbidden")
                rig.liveKit.mutes.shouldBeEmpty()
                act(path = "/test/mute", caller = board, "room" to room.id.toString(), "member" to admin.toString()).first shouldBe
                    HttpStatusCode.OK
                act(path = "/test/mute", caller = admin, "room" to room.id.toString(), "member" to otherAdmin.toString()).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.mutes.map { it.identity } shouldContainExactly listOf(admin.toString(), otherAdmin.toString())
            }
        }

        // -- Single and all -------------------------------------------------------------------------------------------------------

        test(
            "muteParticipant mutes only the open MICROPHONE track, never the camera; a person without open microphone is a no-op without audit or packet",
        ) {
            val rig = ModerationRig()
            val creator = newMember()
            val speaker = newMember()
            val quiet = newMember()
            val room = newRoom(creator)
            live(rig, room, speaker, microphoneOpen = true, cameraOpen = true)
            live(rig, room, quiet, microphoneOpen = false)
            live(rig, room, creator)
            moderationApp(rig = rig) {
                act(path = "/test/mute", caller = creator, "room" to room.id.toString(), "member" to speaker.toString()).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.mutes.map { it.trackSid } shouldContainExactly listOf("TR_mic_$speaker")
                audits(room) shouldContainExactly listOf("""{"moderation":"mute_one","count":1}""")
                // the muted person gets "muted", the moderators present get "muted_peer" naming the person
                val toTarget = rig.liveKit.packets.single { it.destinations == listOf(speaker.toString()) }
                toTarget.topic shouldBe CONFERENCE_MODERATION_TOPIC
                toTarget.payload shouldBe """{"v":1,"k":"muted"}"""
                val toModerators = rig.liveKit.packets.single { it.destinations == listOf(creator.toString()) }
                toModerators.payload shouldBe """{"v":1,"k":"muted_peer","id":"$speaker"}"""
                rig.liveKit.packets.forEach { (it.payload.length <= CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES) shouldBe true }
                // already quiet: nothing more happens
                val mutesBefore = rig.liveKit.mutes.size
                val packetsBefore = rig.liveKit.packets.size
                act(path = "/test/mute", caller = creator, "room" to room.id.toString(), "member" to quiet.toString()).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.mutes.size shouldBe mutesBefore
                rig.liveKit.packets.size shouldBe packetsBefore
                audits(room).size shouldBe 1
            }
        }

        test(
            "muteAll skips creator, caller, BOARD/ADMIN and non-member identities; returns the real count; one audit entry without any person",
        ) {
            val rig = ModerationRig()
            val creator = newMember()
            val board = newMember(AccountRole.BOARD)
            val admin = newMember(AccountRole.ADMIN)
            val a = newMember()
            val b = newMember()
            val c = newMember()
            val room = newRoom(creator)
            listOf(creator, board, admin, a, b).forEach { live(rig, room, it) }
            live(rig, room, c, microphoneOpen = false)
            rig.liveKit.add(room = room.name, participant = liveParticipant(identity = "EG_egress-recorder"))
            moderationApp(rig = rig) {
                val preview = act(path = "/test/preview", caller = creator, "room" to room.id.toString())
                preview shouldBe (HttpStatusCode.OK to "ok|2")
                val result = act(path = "/test/mute-all", caller = creator, "room" to room.id.toString())
                result shouldBe (HttpStatusCode.OK to "ok|2")
                rig.liveKit.mutes.map { it.identity } shouldContainExactlyInAnyOrder listOf(a.toString(), b.toString())
                val entries = audits(room)
                entries shouldContainExactly listOf("""{"moderation":"mute_all","count":2}""")
                listOf(a, b, c, creator, board, admin).forEach { entries.single() shouldNotContain it.toString() }
                // the second call finds nobody with an open microphone: no mute, no new audit entry
                act(path = "/test/mute-all", caller = creator, "room" to room.id.toString()) shouldBe (HttpStatusCode.OK to "ok|0")
                audits(room).size shouldBe 1
            }
        }

        test("muteAll reports only the people actually muted when LiveKit fails for one of them, and fails loud when it fails for all") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val b = newMember()
            val room = newRoom(creator)
            live(rig, room, a)
            live(rig, room, b)
            rig.liveKit.failMuteFor += a.toString()
            moderationApp(rig = rig) {
                act(path = "/test/mute-all", caller = creator, "room" to room.id.toString()) shouldBe (HttpStatusCode.OK to "ok|1")
                audits(room) shouldContainExactly listOf("""{"moderation":"mute_all","count":1}""")
                rig.liveKit.failMuteFor += b.toString()
                rig.liveKit.add(room = room.name, participant = liveParticipant(identity = a.toString())) // a is still open
                rig.liveKit.participants[room.name]!!.removeIf { it.identity == b.toString() }
                rig.liveKit.add(room = room.name, participant = liveParticipant(identity = b.toString()))
                act(path = "/test/mute-all", caller = creator, "room" to room.id.toString()).first shouldBe HttpStatusCode.Conflict
            }
        }

        // -- Rate limits ----------------------------------------------------------------------------------------------------------

        test(
            "each action is throttled: the second call in the window is a conflict, and the limiters are shared between service instances",
        ) {
            val rig =
                ModerationRig(
                    muteAll = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                    muteOne = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                    requestUnmute = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                    policy = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                    preview = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes),
                )
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator)
            live(rig, room, a, microphoneOpen = false)
            moderationApp(rig = rig) {
                // every request builds a NEW service, so the second request can only be refused if the budget lives in the shared limiter
                val r = room.id.toString()
                act(path = "/test/preview", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
                act(path = "/test/preview", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                act(path = "/test/mute-all", caller = creator, "room" to r).first shouldBe HttpStatusCode.OK
                act(path = "/test/mute-all", caller = creator, "room" to r).first shouldBe HttpStatusCode.Conflict
                act(path = "/test/mute", caller = creator, "room" to r, "member" to a.toString()).first shouldBe HttpStatusCode.OK
                act(path = "/test/mute", caller = creator, "room" to r, "member" to a.toString()).first shouldBe HttpStatusCode.Conflict
                act(path = "/test/request-unmute", caller = creator, "room" to r, "member" to a.toString()).first shouldBe HttpStatusCode.OK
                act(path = "/test/request-unmute", caller = creator, "room" to r, "member" to a.toString()).first shouldBe
                    HttpStatusCode.Conflict
                act(path = "/test/policy", caller = creator, "room" to r, "muteOnJoin" to "true", "mayUnmute" to "true").first shouldBe
                    HttpStatusCode.OK
                act(path = "/test/policy", caller = creator, "room" to r, "muteOnJoin" to "false", "mayUnmute" to "true").first shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("an outsider cannot use up a moderator's per-room budget") {
            val rig = ModerationRig(muteAll = FederationInboxRateLimiter(maxRequests = 1, window = 10.minutes))
            val creator = newMember()
            val outsider = newMember()
            val room = newRoom(creator)
            moderationApp(rig = rig) {
                repeat(3) {
                    act(path = "/test/mute-all", caller = outsider, "room" to room.id.toString()).first shouldBe HttpStatusCode.Forbidden
                }
                act(path = "/test/mute-all", caller = creator, "room" to room.id.toString()).first shouldBe HttpStatusCode.OK
            }
        }

        // -- Lock -----------------------------------------------------------------------------------------------------------------

        test(
            "locking applies the permission WITHOUT the microphone to every non-moderator (complete permission), mutes open microphones and broadcasts the lock",
        ) {
            val rig = ModerationRig()
            val creator = newMember()
            val board = newMember(AccountRole.BOARD)
            val a = newMember()
            val b = newMember()
            val room = newRoom(creator)
            live(rig, room, creator)
            live(rig, room, board)
            live(rig, room, a, microphoneOpen = true)
            live(rig, room, b, microphoneOpen = false)
            moderationApp(rig = rig) {
                val result =
                    act(
                        path = "/test/policy",
                        caller = creator,
                        "room" to room.id.toString(),
                        "muteOnJoin" to "false",
                        "mayUnmute" to "false",
                    )
                result shouldBe (HttpStatusCode.OK to "ok|false|false|MODERATOR")
                rig.liveKit.permissionUpdates.map { it.identity } shouldContainExactlyInAnyOrder listOf(a.toString(), b.toString())
                rig.liveKit.permissionUpdates.forEach {
                    it.permission.canSubscribe shouldBe true
                    it.permission.canPublish shouldBe true
                    it.permission.canPublishData shouldBe true
                    it.permission.canPublishSources shouldContainExactly listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO")
                }
                rig.liveKit.mutes.map { it.identity } shouldContainExactly listOf(a.toString())
                rig.liveKit.packets
                    .single { it.destinations == null }
                    .payload shouldBe """{"v":1,"k":"lock"}"""
                audits(room) shouldContainExactly
                    listOf("""{"moderation":"audio_policy","muteOnJoin":false,"participantsMayUnmute":false}""")
                roomRow(room)[ConferenceRoomTable.participantsMayUnmute] shouldBe false

                // unlocking: permission WITH the microphone, broadcast "unlock", releases dropped
                rig.liveKit.permissionUpdates.clear()
                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to room.id.toString(),
                    "muteOnJoin" to "false",
                    "mayUnmute" to "true",
                ).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.permissionUpdates.forEach {
                    it.permission.canPublishSources shouldBe
                        listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO", "MICROPHONE")
                }
                rig.liveKit.permissionUpdates.map { it.identity } shouldContainExactlyInAnyOrder listOf(a.toString(), b.toString())
                rig.liveKit.packets
                    .last { it.destinations == null }
                    .payload shouldBe """{"v":1,"k":"unlock"}"""
            }
        }

        test("an account-less event guest (evg- identity) is locked and muted with everybody else, and counted by muteAll") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator)
            val guest = "evg-${Uuid.random()}"
            listOf(creator, a).forEach { live(rig, room, it) }
            rig.liveKit.add(room = room.name, participant = liveParticipant(identity = guest))
            moderationApp(rig = rig) {
                act(path = "/test/preview", caller = creator, "room" to room.id.toString()) shouldBe (HttpStatusCode.OK to "ok|2")
                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to room.id.toString(),
                    "muteOnJoin" to "false",
                    "mayUnmute" to "false",
                ).first shouldBe HttpStatusCode.OK
                rig.liveKit.permissionUpdates.map { it.identity } shouldContainExactlyInAnyOrder listOf(a.toString(), guest)
                rig.liveKit.mutes.map { it.identity } shouldContainExactlyInAnyOrder listOf(a.toString(), guest)
            }
        }

        test("muteAll mutes an open microphone of an account-less event guest") {
            val rig = ModerationRig()
            val creator = newMember()
            val room = newRoom(creator)
            val guest = "evg-${Uuid.random()}"
            live(rig, room, creator)
            rig.liveKit.add(room = room.name, participant = liveParticipant(identity = guest))
            moderationApp(rig = rig) {
                act(path = "/test/mute-all", caller = creator, "room" to room.id.toString()) shouldBe (HttpStatusCode.OK to "ok|1")
                rig.liveKit.mutes.map { it.identity } shouldContainExactly listOf(guest)
            }
        }

        test("changing only muteOnJoin stores it and makes no LiveKit call; an unchanged lock value touches nobody") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator)
            live(rig, room, a)
            moderationApp(rig = rig) {
                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to room.id.toString(),
                    "muteOnJoin" to "true",
                    "mayUnmute" to "true",
                ).first shouldBe
                    HttpStatusCode.OK
                roomRow(room)[ConferenceRoomTable.muteOnJoin] shouldBe true
                rig.liveKit.permissionUpdates.shouldBeEmpty()
                rig.liveKit.mutes.shouldBeEmpty()
                rig.liveKit.packets.shouldBeEmpty()
            }
        }

        test("Bitten in an unlocked room only sends the request; in a locked room it also releases exactly that one person") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val b = newMember()
            val open = newMember()
            val room = newRoom(creator)
            live(rig, room, a, microphoneOpen = false)
            live(rig, room, b, microphoneOpen = false)
            live(rig, room, open, microphoneOpen = true)
            moderationApp(rig = rig) {
                act(path = "/test/request-unmute", caller = creator, "room" to room.id.toString(), "member" to a.toString()).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.permissionUpdates.shouldBeEmpty()
                rig.liveKit.packets
                    .single()
                    .payload shouldBe """{"v":1,"k":"unmute_request"}"""
                rig.liveKit.packets
                    .single()
                    .destinations shouldBe listOf(a.toString())

                // somebody with an open microphone is not asked at all
                act(
                    path = "/test/request-unmute",
                    caller = creator,
                    "room" to room.id.toString(),
                    "member" to open.toString(),
                ).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.packets.size shouldBe 1

                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to room.id.toString(),
                    "muteOnJoin" to "false",
                    "mayUnmute" to "false",
                ).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.permissionUpdates.clear()
                act(path = "/test/request-unmute", caller = creator, "room" to room.id.toString(), "member" to b.toString()).first shouldBe
                    HttpStatusCode.OK
                rig.liveKit.permissionUpdates
                    .single()
                    .identity shouldBe b.toString()
                rig.liveKit.permissionUpdates
                    .single()
                    .permission.canPublishSources
                    .contains("MICROPHONE") shouldBe true
                rig.grants.isGranted(roomId = room.id, memberId = b) shouldBe true
                rig.grants.isGranted(roomId = room.id, memberId = a) shouldBe false
                audits(room).count { it.contains("unmute_request") } shouldBe 2

                // changing the lock drops every release
                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to room.id.toString(),
                    "muteOnJoin" to "false",
                    "mayUnmute" to "true",
                ).first shouldBe
                    HttpStatusCode.OK
                rig.grants.isGranted(roomId = room.id, memberId = b) shouldBe false
            }
        }

        test("a failed release in a locked room leaves no stale release behind") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator, locked = true)
            live(rig, room, a, microphoneOpen = false)
            rig.liveKit.failPermission = true
            moderationApp(rig = rig) {
                act(path = "/test/request-unmute", caller = creator, "room" to room.id.toString(), "member" to a.toString()).first shouldBe
                    HttpStatusCode.Conflict
                rig.grants.isGranted(roomId = room.id, memberId = a) shouldBe false
            }
        }

        test("a LiveKit failure while applying a lock still stores the policy and audits it, then reports the failure") {
            val rig = ModerationRig()
            val creator = newMember()
            val room = newRoom(creator)
            rig.liveKit.failList = true
            moderationApp(rig = rig) {
                act(
                    path = "/test/policy",
                    caller = creator,
                    "room" to room.id.toString(),
                    "muteOnJoin" to "false",
                    "mayUnmute" to "false",
                ).first shouldBe
                    HttpStatusCode.Conflict
                roomRow(room)[ConferenceRoomTable.participantsMayUnmute] shouldBe false
                audits(room).size shouldBe 1
            }
        }

        test("a LiveKit failure while UNLOCKING is remembered, and asking for 'unlocked' again repairs it") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val room = newRoom(creator, locked = true)
            rig.liveKit.add(
                room = room.name,
                participant =
                    liveParticipant(
                        identity = a.toString(),
                        microphoneOpen = false,
                        permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                    ),
            )
            rig.liveKit.failList = true
            moderationApp(rig = rig) {
                val unlock = arrayOf("room" to room.id.toString(), "muteOnJoin" to "false", "mayUnmute" to "true")
                act(path = "/test/policy", caller = creator, *unlock).first shouldBe HttpStatusCode.Conflict
                roomRow(room)[ConferenceRoomTable.participantsMayUnmute] shouldBe true
                rig.grants.isUnlockPending(room.id) shouldBe true
                rig.liveKit.permissionUpdates.shouldBeEmpty()

                rig.liveKit.failList = false
                act(path = "/test/policy", caller = creator, *unlock).first shouldBe HttpStatusCode.OK
                rig.liveKit.permissionUpdates
                    .single()
                    .permission.canPublishSources
                    .contains("MICROPHONE") shouldBe true
                rig.grants.isUnlockPending(room.id) shouldBe false
            }
        }

        test("a PARTIAL unlock failure marks the room, and the retry reaches the person that was missed") {
            val rig = ModerationRig()
            val creator = newMember()
            val a = newMember()
            val b = newMember()
            val room = newRoom(creator, locked = true)
            listOf(a, b).forEach {
                rig.liveKit.add(
                    room = room.name,
                    participant =
                        liveParticipant(
                            identity = it.toString(),
                            microphoneOpen = false,
                            permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                        ),
                )
            }
            rig.liveKit.failPermissionFor += a.toString()
            moderationApp(rig = rig) {
                val unlock = arrayOf("room" to room.id.toString(), "muteOnJoin" to "false", "mayUnmute" to "true")
                act(path = "/test/policy", caller = creator, *unlock).first shouldBe HttpStatusCode.OK
                rig.grants.isUnlockPending(room.id) shouldBe true
                rig.liveKit.permissionUpdates.map { it.identity } shouldContainExactly listOf(b.toString())

                rig.liveKit.failPermissionFor.clear()
                act(path = "/test/policy", caller = creator, *unlock).first shouldBe HttpStatusCode.OK
                rig.liveKit
                    .find(room = room.name, identity = a.toString())!!
                    .permission!!
                    .canPublishSources
                    .contains("MICROPHONE") shouldBe true
                rig.grants.isUnlockPending(room.id) shouldBe false
            }
        }

        // -- Join tokens ----------------------------------------------------------------------------------------------------------

        test(
            "join token: a locked room withholds the microphone source from a participant, never from a moderator; an unlocked token has no source claim",
        ) {
            val rig = ModerationRig()
            val creator = newMember()
            val board = newMember(AccountRole.BOARD)
            val participant = newMember()
            val released = newMember()
            val locked = newRoom(creator, locked = true)
            val open = newRoom(creator)
            rig.grants.grant(roomId = locked.id, memberId = released)
            moderationApp(rig = rig) {
                val lockedParticipant = videoGrantOf(act(path = "/test/join", caller = participant, "room" to locked.id.toString()).second)
                lockedParticipant["canPublishSources"] shouldBe listOf("camera", "screen_share", "screen_share_audio")
                lockedParticipant["canPublish"] shouldBe "true"
                lockedParticipant["canPublishData"] shouldBe "true"
                videoGrantOf(
                    act(path = "/test/join", caller = creator, "room" to locked.id.toString()).second,
                ).containsKey("canPublishSources") shouldBe
                    false
                videoGrantOf(
                    act(path = "/test/join", caller = board, "room" to locked.id.toString()).second,
                ).containsKey("canPublishSources") shouldBe
                    false
                videoGrantOf(
                    act(path = "/test/join", caller = released, "room" to locked.id.toString()).second,
                ).containsKey("canPublishSources") shouldBe
                    false
                videoGrantOf(
                    act(path = "/test/join", caller = participant, "room" to open.id.toString()).second,
                ).containsKey("canPublishSources") shouldBe
                    false
            }
        }

        test("muteOnJoin without a lock is stored and returned but never enforced by the server (it is only a client default)") {
            val rig = ModerationRig()
            val creator = newMember()
            val participant = newMember()
            val room = newRoom(creator, muteOnJoin = true)
            moderationApp(rig = rig) {
                roomRow(room)[ConferenceRoomTable.muteOnJoin] shouldBe true
                videoGrantOf(
                    act(path = "/test/join", caller = participant, "room" to room.id.toString()).second,
                ).containsKey("canPublishSources") shouldBe
                    false
            }
        }

        test("a fresh room row defaults to unlocked and not muted on join") {
            val creator = newMember()
            val room = newRoom(creator)
            val row = roomRow(room)
            row[ConferenceRoomTable.muteOnJoin] shouldBe false
            row[ConferenceRoomTable.participantsMayUnmute] shouldBe true
        }
    })
