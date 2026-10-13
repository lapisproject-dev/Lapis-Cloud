package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitParticipantPermissionUpdate
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val PARITY_CONFIG =
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

private val PARITY_RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }
private val PARITY_STREAMING_KEY_B64 =
    java.util.Base64
        .getEncoder()
        .encodeToString(ByteArray(32) { it.toByte() })
private val PARITY_STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> PARITY_STREAMING_KEY_B64
            else -> null
        }
    }

private enum class Outcome { OK, FORBIDDEN, CONFLICT, NOT_FOUND }

/** One step of the matrix: its [name], its [outcome] and the (status-neutral) [detail] the route reported. */
private data class Step(
    val name: String,
    val outcome: Outcome,
    val detail: String,
)

/**
 * V1.9.126 -- parity matrix: a [MemberStatus.FRIEND] and a federated [MemberStatus.GUEST] must get EXACTLY the same result from every
 * RPC a participant of a conference uses (`NON_MEMBER` is one capability class for conferences). The two documented differences
 * are checked explicitly and nowhere else: `getGuestJoinInfo.callerIsGuest` (true only for GUEST) and `listParticipants`'
 * `homeserverUrl` (only a GUEST has a home server).
 *
 * NOT covered here, because it never goes through an RPC: chat, moderation hints and join/leave tones travel over the LiveKit data
 * channel. Noise suppression arrives with the join token (`ConferenceJoinTokenDto.noiseSuppressionDefault`), checked in step 5.
 * Step 16 (event path `IEventService.joinEventOnline` + `getRoom` of the event room) is covered by [EventOnlineServiceRpcTest]
 * ("joinEventOnline: a GUEST/FRIEND account needs the notice ..." and "getRoom: a FRIEND who joined an event room can read it ...").
 */
class FriendGuestConferenceParityTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val results = mutableMapOf<MemberStatus, List<Step>>()
        val storageRoot = Files.createTempDirectory("lapis-parity").toFile()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                ConferenceGuestConsentAcknowledgmentTable.deleteWhere {
                    (ConferenceGuestConsentAcknowledgmentTable.roomId inList createdRoomIds) or
                        (ConferenceGuestConsentAcknowledgmentTable.memberId inList createdMemberIds)
                }
                ConferenceParticipationTable.deleteWhere {
                    (ConferenceParticipationTable.roomId inList createdRoomIds) or
                        (ConferenceParticipationTable.memberId inList createdMemberIds)
                }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList createdMemberIds }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            storageRoot.deleteRecursively()
        }

        fun createMember(
            email: String,
            status: MemberStatus,
        ): Uuid {
            val id = Uuid.random()
            val today = ServerClock.todayIn(ServerClock.zone)
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Parity Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = today
                    it[membershipTierId] = null
                    if (status == MemberStatus.FRIEND) it[friendSince] = today
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        /** A real GUEST via the production [OidcGuestMemberStore] (creates the `oidc_guest_profile` row `joinRoom` needs). */
        fun createGuest(suffix: String): Uuid {
            val issuer = "https://parity-home-$suffix.example"
            val id =
                OidcGuestMemberStore.resolveOrCreateGuestMember(
                    claims =
                        OidcGuestClaims(
                            issuer = issuer,
                            subject = "parity-subject-$suffix",
                            name = "Parity Testgast $suffix",
                            picture = null,
                            preferredUsername = null,
                            homeserverUrl = issuer,
                            membershipStatus = "AKTIV",
                        ),
                    grantedScope = "openid profile_basic",
                )
            createdMemberIds += id
            return id
        }

        fun createRoom(creatorId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Parity-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-parity-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
            }
            createdRoomIds += id
            return id
        }

        suspend fun HttpClient.step(
            name: String,
            path: String,
            memberId: Uuid,
            roomId: Uuid,
            extra: String = "",
        ): Step {
            val response = post("/t/$path?roomId=$roomId$extra") { header("X-Member-Id", memberId.toString()) }
            val outcome =
                when (response.status) {
                    HttpStatusCode.OK -> Outcome.OK
                    HttpStatusCode.Forbidden -> Outcome.FORBIDDEN
                    HttpStatusCode.Conflict -> Outcome.CONFLICT
                    HttpStatusCode.NotFound -> Outcome.NOT_FOUND
                    else -> error("step '$name' ($path) answered ${response.status}: ${response.bodyAsText()}")
                }
            return Step(name = name, outcome = outcome, detail = if (outcome == Outcome.OK) response.bodyAsText() else "")
        }

        /** Runs the full matrix for one [status]; returns the steps in order and records the fake LiveKit removals. */
        suspend fun runMatrix(
            status: MemberStatus,
            removed: MutableList<String>,
            fake: ParityLiveKitAdminClient,
        ): List<Step> {
            val steps = mutableListOf<Step>()
            val tag = status.name.lowercase()
            val moderator = createMember("parity-$tag-moderator-${Uuid.random()}@example.org", MemberStatus.ACTIVE)
            val subject =
                if (status == MemberStatus.GUEST) {
                    createGuest("${Uuid.random()}")
                } else {
                    createMember("parity-$tag-${Uuid.random()}@example.org", status)
                }
            val roomId = createRoom(moderator)
            val consent =
                "&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                    "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}"
            testApplication {
                application {
                    install(StatusPages) { installParityExceptionHandlers() }
                    routing { registerParityRoutes(fake = fake, storageRoot = storageRoot) }
                }

                suspend fun s(
                    name: String,
                    path: String,
                    extra: String = "",
                ) = client.step(name, path, subject, roomId, extra).also { steps += it }

                s("1 availability", "availability")
                s("2 joinInfo (closed room)", "joinInfo")
                s("3 join without opt-in", "join", consent)
                steps += client.step("4a moderator opens guest access", "guestAccess", moderator, roomId, "&allow=true")
                s("4b join without consent", "join")
                s("5 join with consent", "join", consent)
                s("6 listParticipants", "listParticipants")
                s("7 votingState", "votingState")
                s("8a recordingAvailability", "recordingAvailability")
                s("8b activeRecording", "activeRecording")
                s("9a streamingAvailability", "streamingAvailability")
                s("9b activeStream", "activeStream")
                s("10a whiteboardState", "whiteboardState")
                s("10b notesState", "notesState")
                s("11a myBreakoutAssignment", "breakoutAssignment")
                s("11b rejoinMainRoomToken", "rejoinMain")
                s("12 backgrounds listMine", "backgrounds")
                s("13a getRoom", "getRoom")
                s("13b listActiveRooms", "listActiveRooms")
                s("14a leaveRoom", "leave")
                s("14b join again with consent", "join", consent)
                steps += client.step("15 moderator closes guest access", "guestAccess", moderator, roomId, "&allow=false")
                steps +=
                    Step(
                        name = "15 open participations after revoke",
                        outcome = Outcome.OK,
                        detail = openParticipations(memberId = subject, roomId = roomId).toString(),
                    )
                removed += fake.removed.toList()
            }
            subjectOf[status] = subject
            return steps
        }

        fun List<Step>.byName(prefix: String) = first { it.name.startsWith(prefix) }

        listOf(MemberStatus.GUEST, MemberStatus.FRIEND).forEach { status ->
            test("$status: every conference RPC behaves as documented (hard expectations)") {
                val removed = CopyOnWriteArrayList<String>()
                val fake = ParityLiveKitAdminClient()
                val steps = runMatrix(status, removed, fake)
                results[status] = steps
                val isGuest = status == MemberStatus.GUEST

                steps.byName("1 ").outcome shouldBe Outcome.OK
                steps.byName("2 ").let {
                    it.outcome shouldBe Outcome.OK
                    it.detail shouldBe "allows=false|nonMember=true|callerIsGuest=$isGuest"
                }
                steps.byName("3 ").outcome shouldBe Outcome.FORBIDDEN
                steps.byName("4b").outcome shouldBe Outcome.CONFLICT
                steps.byName("5 ").let {
                    it.outcome shouldBe Outcome.OK
                    it.detail shouldBe "role=${ConferenceRole.PARTICIPANT}|noise=false"
                }
                steps.byName("6 ").let {
                    it.outcome shouldBe Outcome.OK
                    // The one documented difference besides callerIsGuest: only a GUEST has a home server to show.
                    it.detail shouldBe "homeserverInRows=$isGuest"
                }
                steps.byName("7 ").let {
                    it.outcome shouldBe Outcome.OK
                    it.detail shouldBe "bound=false"
                }
                listOf("8a", "8b", "9a", "9b", "10a", "10b", "11a", "11b", "12").forEach { steps.byName(it).outcome shouldBe Outcome.OK }
                steps.byName("13a").outcome shouldBe Outcome.FORBIDDEN
                steps.byName("13b").outcome shouldBe Outcome.FORBIDDEN
                steps.byName("14a").outcome shouldBe Outcome.OK
                steps.byName("14b").outcome shouldBe Outcome.OK
                steps.byName("15 open").detail shouldBe "0"
                removed.shouldNotBeEmpty()
                removed shouldContain subjectOf.getValue(status).toString()
            }
        }

        test("FRIEND and GUEST get exactly the same outcome from every step (parity), except the two documented differences") {
            val guest = results.getValue(MemberStatus.GUEST)
            val friend = results.getValue(MemberStatus.FRIEND)
            guest.map { it.name } shouldBe friend.map { it.name }
            guest.map { it.name to it.outcome } shouldBe friend.map { it.name to it.outcome }
            // The only allowed differences, spelled out: callerIsGuest in step 2 ...
            guest.byName("2 ").detail shouldBe "allows=false|nonMember=true|callerIsGuest=true"
            friend.byName("2 ").detail shouldBe "allows=false|nonMember=true|callerIsGuest=false"
            // ... and homeserverUrl in step 6 ...
            guest.byName("6 ").detail shouldBe "homeserverInRows=true"
            friend.byName("6 ").detail shouldBe "homeserverInRows=false"
            // ... every other detail is identical.
            val documented = listOf("2 ", "6 ")
            guest.filterNot { s -> documented.any { s.name.startsWith(it) } }.map { it.name to it.detail } shouldBe
                friend.filterNot { s -> documented.any { s.name.startsWith(it) } }.map { it.name to it.detail }
        }
    })

/** Filled by [runMatrix] so the removal check can name the member. (Spec-global because FunSpec lambdas cannot share locals across `test`s.) */
private val subjectOf = mutableMapOf<MemberStatus, Uuid>()

private fun openParticipations(
    memberId: Uuid,
    roomId: Uuid,
): Long =
    transaction {
        ConferenceParticipationTable
            .selectAll()
            .where {
                (ConferenceParticipationTable.memberId eq memberId) and
                    (ConferenceParticipationTable.roomId eq roomId) and
                    ConferenceParticipationTable.leftAt.isNull()
            }.count()
    }

private class ParityLiveKitAdminClient : LiveKitAdminClient {
    val removed = CopyOnWriteArrayList<String>()
    private val rooms = mutableMapOf<String, LiveKitRoomInfo>()

    override suspend fun muteMicrophone(
        room: String,
        identity: String,
        trackSid: String,
    ): Unit = throw UnsupportedOperationException("not used")

    override suspend fun updateParticipantPermission(
        room: String,
        identity: String,
        permission: LiveKitParticipantPermissionUpdate,
    ): Unit = throw UnsupportedOperationException("not used")

    override suspend fun sendDataTo(
        room: String,
        topic: String,
        payload: ByteArray,
        destinationIdentities: List<String>,
    ): Unit = throw UnsupportedOperationException("not used")

    override suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    ): Unit = throw UnsupportedOperationException("not used")

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
        departureTimeoutSeconds: Int?,
    ): LiveKitRoomInfo =
        LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0).also {
            rooms[name] =
                it
        }

    override suspend fun deleteRoom(name: String) {
        rooms.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> = rooms.values.toList()

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = emptyList()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        removed += identity
    }
}

/** Egress is never reached by the read-only calls of the matrix: any use fails loudly. */
private val NO_EGRESS: LiveKitEgressClient =
    Proxy.newProxyInstance(LiveKitEgressClient::class.java.classLoader, arrayOf(LiveKitEgressClient::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("egress must not be used by the parity matrix: ${method.name}")
    } as LiveKitEgressClient

private fun StatusPagesConfig.installParityExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
    exception<LiveKitAdminException> {
        call,
        cause,
        ->
        call.respondText(cause.message ?: "livekit error", status = HttpStatusCode.BadGateway)
    }
}

private fun Route.registerParityRoutes(
    fake: ParityLiveKitAdminClient,
    storageRoot: java.io.File,
) {
    val whiteboardState = ConferenceWhiteboardState()
    val notesState = ConferenceNotesState()

    fun conference(call: ApplicationCall) =
        ConferenceService(
            call = call,
            liveKitAdminClient = fake,
            createRoomRateLimiter = LoginRateLimiter(),
            config = PARITY_CONFIG,
            joinRoomRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            leaveRoomRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            listRateLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes),
            guestAccessRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
            roomVotingStateRateLimiter = FederationInboxRateLimiter(),
        )

    fun recording(call: ApplicationCall) =
        ConferenceRecordingService(
            call = call,
            ffmpegAvailable = true,
            config = PARITY_CONFIG,
            recordingConfig = PARITY_RECORDING_CONFIG,
        )

    fun streaming(call: ApplicationCall) =
        ConferenceStreamingService(
            call = call,
            liveKitEgressClient = NO_EGRESS,
            config = PARITY_CONFIG,
            streamingConfig = PARITY_STREAMING_CONFIG,
        )

    fun breakout(call: ApplicationCall) = ConferenceBreakoutService(call = call, liveKitAdminClient = fake, config = PARITY_CONFIG)

    post("/t/{step}") {
        val step = call.parameters["step"]!!
        val q = call.request.queryParameters
        val roomId = q["roomId"]!!
        val consent =
            q["consentVersion"]?.let {
                ConferenceGuestConsentAcknowledgmentInput(consentVersion = it, consentSha256 = q["consentSha256"] ?: "")
            }
        val detail: String =
            when (step) {
                "availability" -> conference(call).getAvailability().enabled.toString()
                "joinInfo" ->
                    conference(call).getGuestJoinInfo(roomId).let {
                        "allows=${it.allowsFederationGuests}|nonMember=${it.callerIsNonMember}|callerIsGuest=${it.callerIsGuest}"
                    }
                "join" ->
                    conference(call).joinRoom(roomId = roomId, guestConsent = consent).let {
                        "role=${it.role}|noise=${it.noiseSuppressionDefault}"
                    }
                "listParticipants" ->
                    conference(call).listParticipants(roomId).let { rows -> "homeserverInRows=${rows.any { it.homeserverUrl != null }}" }
                "votingState" -> conference(call).getRoomVotingState(roomId).let { "bound=${it.bound}" }
                "recordingAvailability" -> recording(call).getRecordingAvailability().let { "enabled=${it.enabled}" }
                "activeRecording" -> recording(call).getActiveRecording(roomId).let { "n=${it.size}" }
                "streamingAvailability" -> streaming(call).getStreamingAvailability().let { "enabled=${it.enabled}" }
                "activeStream" -> streaming(call).getActiveStream(roomId).let { "n=${it.size}" }
                "whiteboardState" ->
                    ConferenceWhiteboardService(
                        call = call,
                        documentStorageRoot = storageRoot,
                        whiteboardState = whiteboardState,
                        config = PARITY_CONFIG,
                    ).getWhiteboardState(roomId).let { "strokes=${it.strokes.size}" }
                "notesState" ->
                    ConferenceNotesService(
                        call = call,
                        documentStorageRoot = storageRoot,
                        notesState = notesState,
                        config = PARITY_CONFIG,
                    ).getNotesState(roomId).let { "blocks=${it.blocks.size}" }
                "breakoutAssignment" -> breakout(call).getMyBreakoutAssignment(roomId).let { "n=${it.size}" }
                "rejoinMain" -> breakout(call).rejoinMainRoomToken(roomId).let { "role=${it.role}" }
                "backgrounds" ->
                    ConferenceBackgroundService(call = call, storageRoot = storageRoot).listMine().let { "n=${it.size}" }
                "getRoom" -> conference(call).getRoom(roomId).let { "ok" }
                "listActiveRooms" -> conference(call).listActiveRooms().let { "n=${it.size}" }
                "leave" -> conference(call).leaveRoom(roomId).let { "ok" }
                "guestAccess" ->
                    conference(call)
                        .setRoomGuestAccess(roomId = roomId, allowFederationGuests = q["allow"]!!.toBoolean())
                        .let { "allow=${it.allowFederationGuests}" }
                else -> error("unknown step $step")
            }
        call.respondText(detail)
    }
}
