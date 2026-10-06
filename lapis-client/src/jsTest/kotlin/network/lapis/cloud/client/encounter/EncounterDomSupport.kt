package network.lapis.cloud.client.encounter

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.livekit.ConferenceConnectFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.withFetchStub
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterTheme
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement

/** Shared fixtures of the encounter DOM tests (V1.9.62). */
internal fun testSpace(
    id: String = "space-1",
    title: String = "Sonntagsgottesdienst",
    open: Boolean = true,
    myRole: EncounterSpaceRole? = null,
    canModerate: Boolean = myRole != null,
    closedNotice: String? = null,
    archived: Boolean = false,
    presentCount: Int = 0,
    pulpitNames: List<String> = emptyList(),
) = EncounterSpaceDto(
    id = id,
    title = title,
    description = "",
    theme = EncounterTheme.CHURCH,
    mode = EncounterSpaceMode.SERVICE,
    guestPolicy = EncounterGuestPolicy.MEMBERS_ONLY,
    closedNotice = closedNotice,
    open = open,
    openedAt = null,
    presentCount = presentCount,
    maxParticipants = 100,
    pulpitDisplayNames = pulpitNames,
    myRole = myRole,
    canModerate = canModerate,
    archived = archived,
)

internal fun testEntry(
    role: EncounterPresenceRole = EncounterPresenceRole.CONGREGATION,
    canPublishData: Boolean = true,
    identity: String = "me",
    roomId: String = "room-1",
) = EncounterEntryDto(
    join =
        ConferenceJoinTokenDto(
            roomId = roomId,
            livekitRoomName = "lk-$roomId",
            serverUrl = "ws://127.0.0.1:9",
            token = "not-a-real-token",
            identity = identity,
            displayName = "Ich Selbst",
            role = if (role == EncounterPresenceRole.CONGREGATION) ConferenceRole.PARTICIPANT else ConferenceRole.MODERATOR,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        ),
    presenceRole = role,
    canPublish = role != EncounterPresenceRole.CONGREGATION,
    canPublishData = canPublishData,
)

internal fun testPerson(
    id: String,
    role: EncounterPresenceRole = EncounterPresenceRole.CONGREGATION,
    name: String = "Person $id",
    isGuest: Boolean = false,
) = EncounterPresentDto(memberId = id, displayName = name, role = role, isGuest = isGuest)

internal fun testRights(
    entry: EncounterEntryDto,
    privileged: Boolean = false,
) = EncounterViewerRights(
    presenceRole = entry.presenceRole,
    canPublish = entry.canPublish,
    canPublishData = entry.canPublishData,
    selfIdentity = entry.join.identity,
    isPrivileged = privileged,
)

/** A listener session that records what the room asks of it. It has NO media method (the type is the lock). */
internal open class FakeListenerSession(
    private val connectFailure: ConferenceConnectFailure? = null,
    private val dataAllowed: Boolean = true,
) : EncounterListenerSession {
    val reactions = mutableListOf<EncounterReaction>()
    val chats = mutableListOf<String>()
    var connects = 0
    var disconnects = 0
    var audioStarts = 0

    override suspend fun connect(): ConferenceConnectFailure? {
        connects++
        return connectFailure
    }

    override suspend fun sendReaction(reaction: EncounterReaction): Boolean {
        if (!dataAllowed) return false
        reactions += reaction
        return true
    }

    override suspend fun sendChat(text: String): Boolean {
        if (!dataAllowed) return false
        chats += text
        return true
    }

    override suspend fun startAudio() {
        audioStarts++
    }

    override suspend fun disconnect() {
        disconnects++
    }
}

internal class FakeSpeakerSession :
    FakeListenerSession(),
    EncounterSpeakerSession {
    var cameraCalls = mutableListOf<Boolean>()
    var microphoneCalls = mutableListOf<Boolean>()

    override suspend fun setCamera(enabled: Boolean): ConferenceDeviceFailure? {
        cameraCalls += enabled
        return null
    }

    override suspend fun setMicrophone(enabled: Boolean): ConferenceDeviceFailure? {
        microphoneCalls += enabled
        return null
    }
}

/** The seats of the pews in DOM order (index = seat index). */
internal fun HTMLElement.seats(): List<HTMLElement> =
    querySelectorAll(".lapis-encounter-seat").let { list ->
        (0 until list.length).map { list.item(it) as HTMLElement }
    }

/** The accessible names of the OCCUPIED seats, `null` for an empty seat, in DOM order. */
internal fun HTMLElement.seatNames(): List<String?> =
    seats().map { seat ->
        if (seat.getAttribute("role") ==
            "listitem"
        ) {
            seat.getAttribute("title")
        } else {
            null
        }
    }

/** Visible in the layout: an element (or an ancestor) with `display: none` has an empty box. */
internal fun HTMLElement.isShown(): Boolean = getBoundingClientRect().let { it.width > 0 || it.height > 0 }

/** A running encounter room under test: the room, its fake session and every request seen so far. */
internal class EncounterRoomRig(
    val room: EncounterRoom,
    val session: FakeListenerSession,
    val requests: List<RecordedRequest>,
)

/**
 * Mounts an [EncounterRoom] for [entry] in a real root, answers `listPresent` with [peopleOf] (read at every request, so a test can change
 * the roster), lets [extraRespond] answer other routes (everything else gets an empty answer), waits for the first roster and runs [block].
 */
internal suspend fun withEncounterRoom(
    entry: EncounterEntryDto,
    peopleOf: () -> List<EncounterPresentDto>,
    clock: () -> Double,
    privileged: Boolean = false,
    session: FakeListenerSession = FakeListenerSession(dataAllowed = entry.canPublishData),
    extraRespond: (RecordedRequest) -> StubResponse? = { null },
    block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
) {
    val presentRoute = routeOf { rpcService<IEncounterSpaceService>().listPresent("space-1") }
    withFetchStub(
        respond = { request ->
            when {
                request.isRpc && request.rpcRoute == presentRoute ->
                    request.answerWith(jsonOf(ListSerializer(EncounterPresentDto.serializer()), peopleOf()))
                request.isRpc -> extraRespond(request) ?: StubResponse()
                else -> StubResponse()
            }
        },
    ) { requests ->
        mountedForm("encounter-room-${entry.presenceRole}-$privileged") { root, element ->
            val room =
                EncounterRoom(
                    parent = root,
                    space = testSpace(),
                    entry = entry,
                    viewer = testRights(entry = entry, privileged = privileged),
                    clock = clock,
                    onDoorsClosed = {},
                    onConnectionLost = {},
                )
            room.bind(session)
            room.afterConnected()
            awaitUntil("the first roster arrived") { room.rosterReady }
            block(EncounterRoomRig(room, session, requests), element())
        }
    }
}
