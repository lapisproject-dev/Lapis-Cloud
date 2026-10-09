package network.lapis.cloud.client.encounter

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.livekit.ConferenceConnectFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.serviceExceptionResult
import network.lapis.cloud.client.withFetchStub
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterTableTokenDto
import network.lapis.cloud.shared.domain.EncounterTablesConfig
import network.lapis.cloud.shared.domain.EncounterTheme
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import kotlin.test.assertNotNull

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
    profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
    reactions: List<EncounterReactionOption> = EncounterReactionOption.defaultsFor(profile),
    notifyMode: network.lapis.cloud.shared.domain.EncounterNotifyMode = network.lapis.cloud.shared.domain.EncounterNotifyMode.NONE,
    tables: EncounterTablesConfig = EncounterTablesConfig(),
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
    profile = profile,
    reactions = reactions,
    notifyMode = notifyMode,
    tables = tables,
)

/** V1.9.80: an assembly room with 3 tables of 6 seats. */
internal fun tablesSpace(
    myRole: EncounterSpaceRole? = null,
    tables: EncounterTablesConfig = EncounterTablesConfig(enabled = true, count = 3, seats = 6),
) = testSpace(profile = EncounterProfile.ASSEMBLY, myRole = myRole, tables = tables)

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
    seat: Int? = null,
    table: Int? = null,
    tableSeat: Int? = null,
) = EncounterPresentDto(
    memberId = id,
    displayName = name,
    role = role,
    isGuest = isGuest,
    seat = seat,
    table = table,
    tableSeat = tableSeat,
)

/** V1.9.80: the join data of a table (room name `lc-et-<n>`). */
internal fun testTableToken(
    room: String = "lc-et-1",
    table: Int = 0,
    seat: Int = 0,
    canPublish: Boolean = true,
) = EncounterTableTokenDto(
    join =
        ConferenceJoinTokenDto(
            roomId = "room-1",
            livekitRoomName = room,
            serverUrl = "ws://127.0.0.1:9",
            token = "not-a-real-table-token",
            identity = "me",
            displayName = "IS",
            role = ConferenceRole.PARTICIPANT,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        ),
    canPublish = canPublish,
    table = table,
    tableSeat = seat,
)

/** V1.9.80: a table audio session that records what the room asks of it. It has NO camera, screen or data method (the type is the lock). */
internal class FakeTableSession(
    private val canPublish: Boolean = true,
    private val connectFailure: ConferenceConnectFailure? = null,
) : EncounterTableSession,
    EncounterTableMicrophoneDevices {
    var connects = 0
    var disconnects = 0
    val microphoneCalls = mutableListOf<Boolean>()

    /** V1.9.91: the microphones the table session shows (empty = none), the active one, and every switch the picker asked for. */
    var microphones: List<ConferenceDeviceOption> = emptyList()
    var activeMicrophone: String? = null
    val microphoneSwitches = mutableListOf<String>()
    var switchFailure: ConferenceDeviceFailure? = null

    override suspend fun listMicrophones(): List<ConferenceDeviceOption> = if (canPublish) microphones else emptyList()

    override suspend fun switchMicrophone(id: String): ConferenceDeviceFailure? {
        if (switchFailure != null) return switchFailure
        microphoneSwitches += id
        activeMicrophone = id
        return null
    }

    override fun activeMicrophoneId(): String? = activeMicrophone

    override suspend fun connect(): ConferenceConnectFailure? {
        connects++
        return connectFailure
    }

    override suspend fun microphone(on: Boolean): ConferenceDeviceFailure? {
        if (!canPublish) return ConferenceDeviceFailure.OTHER
        microphoneCalls += on
        return null
    }

    override suspend fun disconnect() {
        disconnects++
    }
}

/** V1.9.80: the opener of a test: hands out a [FakeTableSession] per call and remembers the callbacks of the last one. */
internal class FakeTableOpener(
    private val sessionFor: (EncounterTableTokenDto) -> FakeTableSession = { FakeTableSession(canPublish = it.canPublish) },
) {
    val opened = mutableListOf<Pair<EncounterTableTokenDto, FakeTableSession>>()
    var lastCallbacks: EncounterTableCallbacks? = null

    val opener: EncounterTableSessionOpener =
        { token, callbacks ->
            lastCallbacks = callbacks
            sessionFor(token).also { opened += token to it }
        }
}

/** V1.9.79: [count] congregation people who have already chosen the seats 0 until [count] ("Gast 1" sits at seat 0, ...). */
internal fun seatedCrowd(count: Int = 6) = (1..count).map { testPerson("c$it", name = "Gast $it", seat = it - 1) }

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
    var seatNudges = 0
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

    override suspend fun sendSeatNudge(): Boolean {
        if (!dataAllowed) return false
        seatNudges++
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

    /** V1.9.91: the devices the session shows per kind (empty = none), the active ids and every switch the picker asked for. */
    val devices = mutableMapOf<ConferenceDeviceKind, List<ConferenceDeviceOption>>()
    val activeDevices = mutableMapOf<ConferenceDeviceKind, String?>()
    val deviceSwitches = mutableListOf<Pair<ConferenceDeviceKind, String>>()
    var switchFailure: ConferenceDeviceFailure? = null

    override suspend fun listDevices(kind: ConferenceDeviceKind): List<ConferenceDeviceOption> = devices[kind].orEmpty()

    override suspend fun switchDevice(
        kind: ConferenceDeviceKind,
        id: String,
    ): ConferenceDeviceFailure? {
        if (switchFailure != null) return switchFailure
        deviceSwitches += kind to id
        activeDevices[kind] = id
        return null
    }

    override fun activeDeviceId(kind: ConferenceDeviceKind): String? = activeDevices[kind]

    override suspend fun setCamera(enabled: Boolean): ConferenceDeviceFailure? {
        cameraCalls += enabled
        return null
    }

    override suspend fun setMicrophone(enabled: Boolean): ConferenceDeviceFailure? {
        microphoneCalls += enabled
        return null
    }
}

/**
 * V1.9.91: the browser's device list and speaker setter, in the hands of the test. No device by default (so the bar of every older test
 * stays as it was); records every `setSink` call; a sink can be made to fail; `fireDeviceChange()` plays a plug/unplug event.
 */
internal class FakeDeviceEnvironment(
    var sinkApi: Boolean = true,
    var outputs: List<ConferenceDeviceOption> = emptyList(),
) : EncounterDeviceEnvironment {
    val sinkCalls = mutableListOf<Pair<HTMLElement, String>>()
    val failSinkFor = mutableSetOf<String>()

    /** When not empty, [failSinkFor] fails only for these elements (one stubborn element); empty = for every element. */
    val failElements = mutableListOf<HTMLElement>()
    val listeners = mutableListOf<() -> Unit>()
    var removedListeners = 0

    override fun sinkApiAvailable(): Boolean = sinkApi

    /** While true, [listOutputs] does not answer (a slow `enumerateDevices`): a refresh stays in flight. */
    var holdOutputs = false

    override suspend fun listOutputs(): List<ConferenceDeviceOption> {
        while (holdOutputs) kotlinx.coroutines.delay(10)
        return outputs
    }

    override suspend fun setSink(
        element: HTMLElement,
        sinkId: String,
    ): Boolean {
        if (sinkId in failSinkFor && (failElements.isEmpty() || failElements.any { it === element })) return false
        sinkCalls += element to sinkId
        return true
    }

    override fun onDeviceChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return {
            listeners.remove(listener)
            removedListeners++
        }
    }

    fun fireDeviceChange() {
        listeners.toList().forEach { it() }
    }
}

/** The seats of the pews in DOM order (index = seat index). */
internal fun HTMLElement.seats(): List<HTMLElement> =
    querySelectorAll(".lapis-encounter-seat").let { list ->
        (0 until list.length).map { list.item(it) as HTMLElement }
    }

/** V1.9.79: the accessible name of every seat, in DOM order (index = seat index). */
internal fun HTMLElement.seatLabels(): List<String> = seats().map { it.getAttribute("aria-label").orEmpty() }

/** V1.9.79: how many seats are taken (a free seat is a button too, so counting buttons says nothing). */
internal fun HTMLElement.occupied(): Int = seats().count { it.classList.contains("lapis-encounter-seat--taken") }

/** The buttons of the control bar (not of the "Mehr" sheet) in DOM order. */
internal fun HTMLElement.barButtons(): List<HTMLElement> = allOf(".lapis-encounter-controls button")

/** The accessible name of a bar control: `aria-label` of an icon-only button, the text of a reaction. */
internal fun HTMLElement.barName(): String = getAttribute("aria-label") ?: textContent.orEmpty().trim()

/** V1.9.74: the bar's controls are icon-only, so they are found by their accessible name -- and only in the bar. */
internal fun HTMLElement.barControl(name: String): HTMLElement =
    assertNotNull(barButtons().firstOrNull { it.barName() == name }, "no bar control '$name' in ${barControlNames()}")

internal fun HTMLElement.barControlNames(): List<String> = barButtons().map { it.barName() }

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
    /** V1.9.79: the server's answer to `selectSeat`; default = the roster with the viewer sitting where the request says. */
    selectSeatAnswer: (RecordedRequest) -> StubResponse = { request -> defaultSeatAnswer(request, entry, peopleOf) },
    space: EncounterSpaceDto = testSpace(),
    seatNudgeDelayMs: () -> Int = { 0 },
    /** V1.9.80: the factory of the table's audio session (a fake by default: no network). */
    tableSessionOpener: EncounterTableSessionOpener = FakeTableOpener().opener,
    /** How long the stub server takes to answer `listPresent` (the roster is read when the request ARRIVES, so a slow answer is a stale one). */
    presentDelayMs: () -> Int = { 0 },
    onLeave: () -> Unit = {},
    onDoorsClosed: () -> Unit = {},
    /** V1.9.91: the browser's devices (none by default, whatever the machine running the test has). */
    deviceEnv: EncounterDeviceEnvironment = FakeDeviceEnvironment(),
    block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
) {
    val presentRoute = routeOf { rpcService<IEncounterSpaceService>().listPresent("space-1") }
    val selectSeatRoute = routeOf { rpcService<IEncounterSpaceService>().selectSeat("space-1", 1) }
    withFetchStub(
        respond = { request ->
            when {
                request.isRpc && request.rpcRoute == presentRoute -> {
                    val answer = request.answerWith(jsonOf(ListSerializer(EncounterPresentDto.serializer()), peopleOf()))
                    val delay = presentDelayMs()
                    if (delay > 0) StubResponse(text = answer.text, delayMs = delay) else answer
                }
                request.isRpc && request.rpcRoute == selectSeatRoute -> selectSeatAnswer(request)
                request.isRpc -> extraRespond(request) ?: StubResponse()
                else -> StubResponse()
            }
        },
    ) { requests ->
        mountedForm("encounter-room-${entry.presenceRole}-$privileged") { root, element ->
            val room =
                EncounterRoom(
                    parent = root,
                    space = space,
                    entry = entry,
                    viewer = testRights(entry = entry, privileged = privileged),
                    clock = clock,
                    seatNudgeDelayMs = seatNudgeDelayMs,
                    onLeave = onLeave,
                    onDoorsClosed = onDoorsClosed,
                    onConnectionLost = {},
                    tableSessionOpener = tableSessionOpener,
                    deviceEnv = deviceEnv,
                )
            room.bind(session)
            room.afterConnected()
            awaitUntil("the first roster arrived") { room.rosterReady }
            try {
                block(EncounterRoomRig(room, session, requests), element())
            } finally {
                // A room that outlives its test keeps polling into the next test's fetch stub.
                room.dispose()
            }
        }
    }
}

/** The stub server's `selectSeat`: the roster, with the requester seated at the requested seat (or released for `null`). */
internal fun defaultSeatAnswer(
    request: RecordedRequest,
    entry: EncounterEntryDto,
    peopleOf: () -> List<EncounterPresentDto>,
): StubResponse {
    val seat = request.requestedSeat()
    val people = peopleOf().map { if (it.memberId == entry.join.identity) it.copy(seat = seat) else it }
    return request.answerWith(jsonOf(ListSerializer(EncounterPresentDto.serializer()), people))
}

/** The stub server refuses with an exception of [fqcn] (only its TYPE reaches the client). */
internal fun RecordedRequest.refusedWith(fqcn: String): StubResponse = serviceExceptionResult(json.id as Int, fqcn)

/** The seat a `selectSeat` request asks for (`null` = release). */
internal fun RecordedRequest.requestedSeat(): Int? {
    val param: dynamic = rpcParam(1)
    return if (param == null) null else (param as Number).toInt()
}
