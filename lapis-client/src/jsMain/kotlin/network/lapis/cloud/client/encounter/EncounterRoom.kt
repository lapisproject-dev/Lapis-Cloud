package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.AppState
import network.lapis.cloud.client.CONTROL_BAR_FALLBACK_WIDTH_PX
import network.lapis.cloud.client.ControlBarOverflow
import network.lapis.cloud.client.ControlBarSlot
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.actionIconClasses
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.confirmDialog
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.client.livekit.TrackPublication
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.setAttrIfChanged
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.encounterSeatPosition
import network.lapis.cloud.shared.domain.encounterSeatRow
import network.lapis.cloud.shared.domain.option
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.Date
import kotlin.random.Random

/**
 * V1.9.62 Begegnungsraum (B2) -- the room a person is INSIDE: the stage with the pulpit and the pews, the controls, the side panel
 * (chat, presence, transmission) and every timer and listener that belongs to that visit. [dispose] ends all of them; the owner
 * ([renderEncounterServiceView]) calls it when the person leaves, the room closes or the screen goes away.
 *
 * Build order (the owner follows it): `EncounterRoom(...)` builds the widgets, [callbacks] is handed to the session factory, [bind]
 * gives the room the connected-to-be session, `session.connect()` runs, then [afterConnected].
 *
 * ## What the room holds, and what it never does
 * - The people: the list of `listPresent` (names, roles) -- refreshed at most every 5 s on a roster event and every 20 s by timer, only
 *   while the page is visible. Nothing is kept per person beyond this visit's memory: no time, nothing in storage (the only storage of
 *   the whole encounter client is the "scene off" key, [EncounterSceneToggle]).
 * - The pews (V1.9.79, stage 2a): [EncounterSeating] mirrors the `seat` field of `listPresent`; people from that list only (a LiveKit
 *   participant that is not listed -- an egress bot, a stranger -- never gets a seat). Nobody is seated automatically: a seat exists
 *   because its occupant CHOSE it ([chooseSeat] -> `selectSeat`, the server decides who wins a contested seat), and everybody else is in
 *   the row "Noch ohne Platz". A content-free data-channel nudge only makes the others reload the list sooner. A seat never moves.
 * - Reactions: a hand is a state (renewed every 30 s by its owner, lapses after 90 s without a renewal); amen, applause and heart are
 *   events shown for 3 s at the seat and announced at most every 10 s as a fixed sentence, never as a count. The sender is always the SDK
 *   identity. Only the reactions the room allows ([EncounterSpaceDto.reactions], V1.9.67) get a button and are admitted when they arrive.
 * - A reaction of any CONGREGATION person in the list is admitted (V1.9.79: sitting is no longer a precondition) and is shown at the
 *   seat, or at the symbol in the row of people without a seat.
 * - Reactions are quiet: sender-side limits (events share one 5 s budget, hand 2 s), receiver-side limits (2 per second and sender).
 * - Stage mode (V1.9.67): the room fills the screen under the header, the bar sits below the stage, the side panel is a column (wide),
 *   an overlay (medium) or a sheet (narrow), and the whole room can go to the full screen ([EncounterFullscreen]).
 */
internal class EncounterRoom(
    parent: Container,
    private val space: EncounterSpaceDto,
    private val entry: EncounterEntryDto,
    private val viewer: EncounterViewerRights,
    private val clock: () -> Double = { Date.now() },
    /** V1.9.79: random wait (0..1000 ms) before a seat nudge makes the room reload the list -- a seam for tests. */
    private val seatNudgeDelayMs: () -> Int = { Random.nextInt(0, SEAT_NUDGE_JITTER_MS + 1) },
    private val onLeave: () -> Unit,
    private val onDoorsClosed: () -> Unit,
    private val onConnectionLost: (DisconnectCause) -> Unit,
    /** V1.9.80: the factory of the table's audio session -- a seam for tests. */
    private val tableSessionOpener: EncounterTableSessionOpener = ::openEncounterTableSession,
    /** V1.9.91: the browser's device list and speaker setter -- a seam for tests (a fake never depends on the machine's real devices). */
    private val deviceEnv: EncounterDeviceEnvironment = browserDeviceEnvironment(),
    /** V1.9.95: the timers of the blessing display -- a seam for tests. */
    private val blessingScheduler: EncounterBlessingScheduler = browserBlessingScheduler,
) {
    private val terms: EncounterTerms = termsFor(space.profile)
    private val allowedReactions: Set<EncounterReactionOption> = EncounterReactionOption.normalize(space.reactions).toSet()
    private val root = parent.vPanel(spacing = 10) { addCssClass("lapis-encounter-room") }
    private val infoRow: Div = root.div(className = "lapis-encounter-info d-flex flex-wrap align-items-center gap-3")
    private val countText: Span = infoRow.span(className = "text-muted")
    private val liveBadge = EncounterLiveBadge(infoRow, terms, entry.join.roomId)
    private val eventLive: Div = root.div(className = "visually-hidden")

    /** V1.9.79: the outcome of the viewer's OWN seat choice (seated, released, taken, busy) -- a fixed sentence, polite. */
    private val seatLive: Div = root.div(className = "visually-hidden")
    private val handLive: Div? = if (viewer.canModerate) root.div(className = "visually-hidden") else null

    /** V1.9.80: the outcome of the viewer's OWN table events (sat down, got up, quieted, back in the plenum) -- fixed sentences, polite. */
    private val tableLive: Div = root.div(className = "visually-hidden")

    /** V1.9.91: "the speaker/microphone/camera is no longer available" -- a fixed sentence naming the KIND of device, never its name; polite. */
    private val deviceLive: Div = root.div(className = "visually-hidden")

    /** V1.9.95: the one fixed sentence "the blessing is spoken" -- a PERSISTENT polite region (a region created together with its text is not announced). */
    private val blessingLive: Div = root.div(className = "visually-hidden")
    private val bands: Div = root.div(className = "lapis-encounter-bands")
    private val main: Div = root.div(className = "lapis-encounter-main")
    private val layout = EncounterSceneLayout(main, terms)

    /** V1.9.95: the quiet cross over the pulpit area, visible to everybody in the room. */
    private val blessingDisplay =
        EncounterBlessingDisplay(
            host = layout.pulpit,
            terms = terms,
            live = blessingLive,
            timer = EncounterBlessingTimer(now = clock),
            scheduler = blessingScheduler,
        )

    /** V1.9.95: the label of the blessing button; `null` = the viewer gets no blessing (not a pulpit, or a room without one). The server decides again. */
    private val blessingLabel: String? = terms.blessingLabel()?.takeIf { viewer.presenceRole == EncounterPresenceRole.PULPIT }
    private var blessingInFlight = false

    // ── tables (V1.9.80, stage 2b): assembly profile with tables enabled only ──
    private val tablesOn: Boolean = space.tables.enabled // the server reports it only for the assembly profile
    private val tableView: EncounterTablesView? = if (tablesOn) EncounterTablesView(layout.tablesHost) else null

    /**
     * V1.9.91: the room's speaker choice, one sink id for both audio hosts. Declared BEFORE the hosts (they take it); the lambda reads the
     * hosts' elements only when it is called.
     */
    private val audioOutput: EncounterAudioOutput =
        EncounterAudioOutput(
            env = deviceEnv,
            elements = { audioSink.elements + tableAudioSink.elements },
            onFellBack = { devicePicker?.speakerGone() },
        )
    private val tableAudioSink = EncounterMediaHost("lapis-encounter-table-audio-sink", audioOutput)
    private val tableThrottle = EncounterSeatChoiceThrottle(clock, minGapMs = TABLE_CHOICE_GAP_MS)
    private var quietedTables: Set<Int> = emptySet()
    private var pulpitLouder = false
    private lateinit var tableMicButton: Button
    private lateinit var louderButton: Button
    private val tables: EncounterTableController =
        EncounterTableController(
            spaceId = space.id,
            opener = tableSessionOpener,
            host =
                object : EncounterTableHost {
                    override fun tablesChanged() = renderTables()

                    override fun announce(sentence: String) = announceTable(sentence)

                    override fun requestRefresh() = requestPresentRefresh()

                    override fun nudge() {
                        AppScope.launch { session?.sendSeatNudge() }
                    }

                    override fun setAtTable(atTable: Boolean) = onAtTableChanged(atTable)

                    override fun addTableAudio(element: HTMLElement) = tableAudioSink.add(element)

                    override fun removeTableAudio(element: HTMLElement) = tableAudioSink.remove(element)

                    override suspend fun tableMicrophoneOn(devices: EncounterTableMicrophoneDevices) {
                        devicePicker?.tableMicrophoneOn(devices)
                    }
                },
        )
    private val tabs =
        buildList {
            add(EncounterSideTab.CHAT)
            add(EncounterSideTab.PRESENT)
            if (viewer.canModerate) add(EncounterSideTab.STREAM)
        }
    private val side = EncounterSidePanel(main, tabs) { tab -> onTabShown(tab) }
    private val controlBar = EncounterControlBar(root, liturgy = blessingLabel != null)
    private val controls: Div get() = controlBar.root

    /**
     * V1.9.74 -- the "Mehr" sheet: a sibling of the bar (not inside it), anchored above it. Non-modal (`role="dialog"` without a focus
     * trap): Escape or a click outside closes it and the focus returns to "Mehr". It holds the labelled twins of what did not fit.
     */
    private val moreSheet: Div =
        root.div(className = "lapis-encounter-more-sheet").also {
            it.setAttribute("id", ENCOUNTER_MORE_SHEET_ID)
            it.setAttribute("role", "dialog")
            it.setAttribute("aria-label", gettext("Weitere Bedienelemente"))
        }

    private val seating = EncounterSeating()
    private val seatThrottle = EncounterSeatChoiceThrottle(clock)
    private val joinAnnouncer = EncounterEventAnnouncer(clock)
    private var pendingSeat: Int? = null
    private var seatRequestRunning = false
    private val hands = EncounterRaisedHands(clock)
    private val sendThrottle = EncounterReactionSendThrottle(clock)
    private val receiveGuard = EncounterReactionReceiveGuard(clock)
    private val eventAnnouncer = EncounterEventAnnouncer(clock)
    private val refreshPlanner = EncounterRefreshPlanner(clock)
    private val audioSink = EncounterMediaHost("lapis-encounter-audio-sink", audioOutput)
    private val tiles = LinkedHashMap<String, EncounterTile>()
    private val videoElements = HashMap<String, HTMLMediaElement>()
    private var present: Map<String, EncounterPresentDto> = emptyMap()
    private var rosterLoaded = false
    private var lastServerRoster: Set<String> = emptySet()
    private var lastPulpitIds: List<String> = emptyList()
    private var lastStewardIds: List<String> = emptyList()
    private var ownHandUp = false
    private var disposed = false
    private var session: EncounterListenerSession? = null
    private var pulpitControls: EncounterPulpitControls? = null
    private var devicePicker: EncounterDevicePicker? = null
    private val cleanups = mutableListOf<() -> Unit>()

    private val chat = EncounterChatPanel(side.hostOf(EncounterSideTab.CHAT), terms) { text -> sendChat(text) }
    private val presentPanel =
        EncounterPresentPanel(
            parent = side.hostOf(EncounterSideTab.PRESENT),
            spaceId = space.id,
            terms = terms,
            viewer = viewer,
            raisedHands = { hands.ordered },
            onRoster = { people -> onRoster(people) },
            beforeDialog = { fullscreen.leaveIfActive() },
            seatList = { seatListState() },
            onChooseSeat = { seat -> chooseSeat(seat) },
            onReleaseSeat = { chooseSeat(null) },
            tableList = { tableListState() },
            onChooseTable = { table -> chooseTableFromList(table) },
            onLeaveTable = { leaveTable() },
        )
    private val streamPanel: EncounterStreamPanel? =
        if (viewer.canModerate) {
            EncounterStreamPanel(
                parent = side.hostOf(EncounterSideTab.STREAM),
                terms = terms,
                roomId = entry.join.roomId,
                isAdmin = AppState.hasRole(AccountRole.ADMIN),
                pulpitPeople = { present.values.filter { it.role == EncounterPresenceRole.PULPIT } },
            )
        } else {
            null
        }

    private lateinit var handButton: Button
    private val eventButtons = LinkedHashMap<EncounterReactionOption, Button>()
    private lateinit var chatButton: Button
    private lateinit var moreButton: Button
    private lateinit var sceneButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var broadcastButton: Button
    private lateinit var leaveButton: Button
    private var fullscreenTwin: Button? = null
    private lateinit var eventWaitNote: Div
    private lateinit var overflow: ControlBarOverflow<EncounterControlSlot>
    private val barSlots = mutableListOf<ControlBarSlot<EncounterControlSlot>>()
    private val twinsInOrder = mutableListOf<Button>()
    private var sheetOpen = false
    private var sheetCleanup: (() -> Unit)? = null
    private val fullscreen =
        EncounterFullscreen(
            element = { root.getElement() as? HTMLElement },
            onChange = { active -> refreshFullscreenButton(active) },
        )
    private lateinit var unreadDot: Span
    private lateinit var unreadText: Span
    private lateinit var audioBand: Div
    private lateinit var reconnectBand: Div

    /** What the session reports; hand it to the session factory BEFORE the session exists. */
    val callbacks: EncounterSessionCallbacks =
        EncounterSessionCallbacks(
            onRemoteTrack = { identity, _, track, publication -> onRemoteTrack(identity, track, publication) },
            onRemoteTrackGone = { identity, track, _ -> onRemoteTrackGone(identity, track) },
            onParticipantJoined = { _, _ -> requestPresentRefresh() },
            onSeatNudge = { identity -> onSeatNudge(identity) },
            onBlessing = { blessingDisplay.onBlessing() },
            onActiveSpeakers = { identities -> onActiveSpeakers(identities) },
            onParticipantLeft = { identity -> onParticipantLeft(identity) },
            onLocalVideoTrack = { track -> onLocalVideo(track) },
            onLocalTrackMuteChanged = { source, muted -> pulpitControls?.onMuteChanged(source, muted) },
            onChat = { identity, name, text -> onChat(identity, name, text) },
            onReaction = { identity, reaction -> onReaction(identity, reaction) },
            onAudioPlaybackChanged = { canPlay -> if (canPlay) audioBand.hide() else audioBand.show() },
            onReconnecting = { reconnectBand.show() },
            onReconnected = {
                reconnectBand.hide()
                requestPresentRefresh()
            },
            onDisconnected = { cause -> if (!disposed) onConnectionLost(cause) },
        )

    init {
        eventLive.setAttribute("role", "status")
        eventLive.setAttribute("aria-live", "polite")
        seatLive.setAttribute("role", "status")
        seatLive.setAttribute("aria-live", "polite")
        layout.seats.onChoose = { seat -> chooseSeat(seat) }
        tableLive.setAttribute("role", "status")
        tableLive.setAttribute("aria-live", "polite")
        deviceLive.setAttribute("role", "status")
        deviceLive.setAttribute("aria-live", "polite")
        blessingLive.setAttribute("role", "status")
        blessingLive.setAttribute("aria-live", "polite")
        tableView?.let { view ->
            view.onChoose = { table, seat -> chooseTableSeat(table, seat) }
            view.onLeave = { leaveTable() }
            view.onQuiet = { table, quiet -> quietTable(table, quiet) }
        }
        layout.releaseSeatButton.onClick { chooseSeat(null) }
        handLive?.setAttribute("role", "status")
        handLive?.setAttribute("aria-live", "polite")
        buildBands()
        buildControls()
        chat.setSendingEnabled(entry.canPublishData)
        audioSink.attachTo(root)
        tableAudioSink.attachTo(root)
        // A person with an office has a tile of their own from the first moment (their own camera needs a place before `listPresent` answers).
        if (viewer.presenceRole != EncounterPresenceRole.CONGREGATION) syncTiles()
        val scene = !encounterSceneOffStored() && !encounterSceneForcedOff()
        layout.setSceneOff(!scene)
        refreshSceneButton()
    }

    private fun buildBands() {
        audioBand = bands.div(className = "lapis-encounter-band d-flex align-items-center gap-2")
        audioBand.setAttribute("role", "status")
        audioBand.div(tr("Der Ton ist ausgeschaltet."))
        audioBand.actionButton(ActionIcon.MICROPHONE, tr("Ton einschalten"), style = ButtonStyle.OUTLINEPRIMARY).onClick {
            AppScope.launch { session?.startAudio() }
        }
        audioBand.hide()
        reconnectBand = bands.div(tr("Die Verbindung wird wiederhergestellt …"), className = "lapis-encounter-band")
        reconnectBand.setAttribute("role", "status")
        reconnectBand.hide()
        if (!entry.canPublishData) {
            val silenced = bands.div(terms.silencedNoteContent(), className = "lapis-encounter-band")
            silenced.setAttribute("role", "status")
        }
    }

    /** A labelled twin in the "Mehr" sheet; the overflow handler shows it while its primary sits in the sheet. */
    private fun sheetTwin(
        kind: ActionIcon,
        label: String,
        style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
        action: () -> Unit,
    ): Button {
        val twin = moreSheet.actionButton(kind, label, style)
        twin.hide()
        twin.onClick {
            closeSheet(returnFocus = false)
            action()
        }
        twinsInOrder += twin
        return twin
    }

    private fun buildControls() {
        moreSheet.hide()
        val reactions = controlBar.group(EncounterControlGroup.REACTIONS)
        handButton =
            reactions.encounterControlButton(
                reactionActionIcon(EncounterReactionOption.HAND),
                reactionLabelContent(EncounterReactionOption.HAND),
            )
        handButton.setAttribute("aria-pressed", "false")
        handButton.disabled = !entry.canPublishData
        handButton.onClick { toggleHand() }
        barSlots += ControlBarSlot(EncounterControlSlot.Hand, handButton, null)
        // The event reactions of the room in canonical order (HAND is the button above, always present). Symbol only; the word lives in title/aria-label and on the sheet twin.
        EncounterReactionOption.entries.filter { it != EncounterReactionOption.ALWAYS_ON && it in allowedReactions }.forEach { option ->
            val button = reactions.encounterControlButton(reactionActionIcon(option), reactionLabelContent(option))
            button.disabled = !entry.canPublishData
            button.onClick { sendEvent(option) }
            eventButtons[option] = button
            val twin = sheetTwin(reactionActionIcon(option), reactionLabelContent(option)) { sendEvent(option) }
            barSlots += ControlBarSlot(EncounterControlSlot.Reaction(option), button, twin)
        }
        // The wait note is a status of the room, not a control: it stands in the bands (one reason less for the bar to break).
        eventWaitNote = bands.div(tr("Bitte einen Moment warten."), className = "lapis-encounter-band text-muted small")
        eventWaitNote.setAttribute("role", "status")
        eventWaitNote.hide()
        if (tablesOn && viewer.presenceRole == EncounterPresenceRole.CONGREGATION) buildTableControls()
        // V1.9.95: the blessing -- the liturgy group exists only for the pulpit of a room that has one (the server checks it again).
        blessingLabel?.let { label ->
            val icon = ActionIcon.BLESSING
            val blessingButton = controlBar.group(EncounterControlGroup.LITURGY).encounterControlButton(icon, label)
            blessingButton.onClick { bless() }
            barSlots += ControlBarSlot(EncounterControlSlot.Blessing, blessingButton, sheetTwin(icon, label) { bless() })
        }
        val panels = controlBar.group(EncounterControlGroup.PANELS)
        chatButton = panels.encounterControlButton(ActionIcon.CHAT, tr("Chat"))
        chatButton.setAttribute("aria-expanded", "false")
        chatButton.setAttribute("aria-controls", ENCOUNTER_SIDE_PANEL_ID)
        chatButton.onClick { toggleSide(EncounterSideTab.CHAT) }
        barSlots +=
            ControlBarSlot(
                EncounterControlSlot.Chat,
                chatButton,
                sheetTwin(ActionIcon.CHAT, tr("Chat")) { toggleSide(EncounterSideTab.CHAT) },
            )
        // A persistent polite status region (a region that is created together with its text is not announced): the dot shows through the
        // `is-on` class, the words "Neue Nachrichten" are written into it when a line arrives while the chat is not in view.
        unreadDot = panels.span(className = "lapis-encounter-unread")
        unreadDot.setAttribute("role", "status")
        unreadText = unreadDot.span(className = "visually-hidden")
        moreButton = panels.encounterControlButton(ActionIcon.MORE, tr("Mehr"))
        moreButton.setAttribute("aria-expanded", "false")
        moreButton.setAttribute("aria-controls", ENCOUNTER_MORE_SHEET_ID)
        moreButton.onClick { toggleSheet() }
        moreButton.hide()
        val view = controlBar.group(EncounterControlGroup.VIEW)
        // The label is stable; `aria-pressed="true"` means "the scene is hidden" (the control hides the scene).
        sceneButton = view.encounterControlButton(ActionIcon.SCENE, tr("Szene ausblenden"))
        sceneButton.onClick { toggleScene() }
        barSlots +=
            ControlBarSlot(
                EncounterControlSlot.Scene,
                sceneButton,
                sheetTwin(ActionIcon.SCENE, tr("Szene ausblenden")) { toggleScene() },
                mirrorPressed = true,
            )
        fullscreenButton = view.encounterControlButton(ActionIcon.FULLSCREEN, tr("Vollbild"))
        fullscreenButton.setAttribute("aria-pressed", "false")
        fullscreenButton.onClick { fullscreen.toggle() }
        fullscreenTwin = sheetTwin(ActionIcon.FULLSCREEN, tr("Vollbild")) { fullscreen.toggle() }
        barSlots += ControlBarSlot(EncounterControlSlot.Fullscreen, fullscreenButton, fullscreenTwin, mirrorPressed = true)
        if (viewer.canModerate) {
            val moderation = controlBar.group(EncounterControlGroup.MODERATION)
            broadcastButton = moderation.encounterControlButton(ActionIcon.BROADCAST, tr("Übertragung"))
            broadcastButton.setAttribute("aria-expanded", "false")
            broadcastButton.setAttribute("aria-controls", ENCOUNTER_SIDE_PANEL_ID)
            broadcastButton.onClick { toggleSide(EncounterSideTab.STREAM) }
            barSlots +=
                ControlBarSlot(
                    EncounterControlSlot.Broadcast,
                    broadcastButton,
                    sheetTwin(ActionIcon.BROADCAST, tr("Übertragung")) { toggleSide(EncounterSideTab.STREAM) },
                )
        }
        val exit = controlBar.group(EncounterControlGroup.EXIT)
        if (viewer.canModerate) {
            val doors = exit.encounterControlButton(ActionIcon.CLOSE_DOORS, tr("Türen schließen"), ButtonStyle.OUTLINEDANGER)
            doors.onClick { askCloseDoors() }
            // The twin stands last in the sheet, set apart by a rule; it leads to the SAME dialog (one path to closeSpace).
            val doorsTwin =
                sheetTwin(ActionIcon.CLOSE_DOORS, tr("Türen schließen"), ButtonStyle.OUTLINEDANGER) { askCloseDoors() }
            doorsTwin.addCssClasses("lapis-encounter-twin-end text-danger")
            barSlots += ControlBarSlot(EncounterControlSlot.CloseDoors, doors, doorsTwin)
        }
        leaveButton = exit.encounterControlButton(ActionIcon.LEAVE, tr("Verlassen"), ButtonStyle.DANGER)
        leaveButton.onClick {
            // A double click must not leave twice: the button is locked at once, the owner ends the visit.
            leaveButton.disabled = true
            leaveButton.setAttribute("aria-busy", "true")
            onLeave()
        }
        barSlots += ControlBarSlot(EncounterControlSlot.Leave, leaveButton, null)
        overflow =
            ControlBarOverflow(
                bar = controlBar.root,
                slots = barSlots,
                dividers = emptyList(),
                groupOf = { encounterControlGroup(it).ordinal },
                order = encounterOverflowOrder(allowedReactions, blessing = blessingLabel != null),
                exitGroup = EncounterControlGroup.EXIT.ordinal,
                overflowedClass = OVERFLOWED_CLASS,
                onChanged = { onOverflowChanged() },
                moreWidth = CONTROL_BAR_FALLBACK_WIDTH_PX,
                moreGroup = EncounterControlGroup.PANELS.ordinal,
                moreButton = moreButton,
            )
    }

    /**
     * V1.9.80: the two controls of a person who sits at a table -- the table microphone and "Kanzel lauter". Icon-only bar controls
     * (R58 named exception c) that never move into the sheet; they are shown only while the viewer sits at a table.
     */
    private fun buildTableControls() {
        val devices = controlBar.group(EncounterControlGroup.DEVICES)
        // The label is stable; the state is `aria-pressed` ("the microphone at the table is on") plus the status line in the table's card.
        tableMicButton = devices.encounterControlButton(ActionIcon.MICROPHONE, tr("Mikrofon am Tisch"))
        tableMicButton.setAttribute("aria-pressed", "false")
        tableMicButton.onClick { AppScope.launch { tables.toggleMicrophone(!tables.micOn) } }
        tableMicButton.hide()
        louderButton = devices.encounterControlButton(ActionIcon.PULPIT_LOUDER, terms.pulpitLouderLabel())
        louderButton.setAttribute("aria-pressed", "false")
        louderButton.onClick {
            pulpitLouder = !pulpitLouder
            louderButton.setAttribute("aria-pressed", pulpitLouder.toString())
            applyPlenumVolume()
        }
        louderButton.hide()
        barSlots += ControlBarSlot(EncounterControlSlot.TableMic, tableMicButton, null)
        barSlots += ControlBarSlot(EncounterControlSlot.PulpitLouder, louderButton, null)
    }

    /** A group whose controls all sit in the sheet is hidden (its wrapper would otherwise take a gap and keep its divider). */
    private fun onOverflowChanged() {
        val moved = overflow.moved()
        for (group in EncounterControlGroup.entries) {
            val members = barSlots.filter { encounterControlGroup(it.slot) == group }
            if (members.isEmpty()) continue
            val shown =
                members.any { it.primary.visible && it.slot !in moved } || (group == EncounterControlGroup.PANELS && moved.isNotEmpty())
            controlBar.setGroupSpent(group, !shown)
        }
        if (moved.isEmpty()) closeSheet(returnFocus = false)
    }

    private fun registerBarSlot(slot: ControlBarSlot<EncounterControlSlot>) {
        barSlots += slot
        overflow.register(slot)
    }

    private fun toggleSheet() {
        if (sheetOpen) closeSheet(returnFocus = true) else openSheet()
    }

    private fun openSheet() {
        if (sheetOpen || disposed) return
        // the two panels never stand open together
        devicePicker?.close(returnFocus = false)
        // The twins mirror the state of their primaries only when the overflow is recomputed: do it before the sheet is shown.
        overflow.recompute()
        sheetOpen = true
        moreSheet.show()
        moreButton.setAttrIfChanged("aria-expanded", "true")
        val onKey: (Event) -> Unit = { event -> if ((event as? KeyboardEvent)?.key == "Escape") closeSheet(returnFocus = true) }
        val onClick: (Event) -> Unit = { event ->
            val target = event.target as? Node
            val inside =
                target != null &&
                    (
                        (moreSheet.getElement()?.contains(target) == true) ||
                            (moreButton.getElement()?.contains(target) == true)
                    )
            if (!inside) closeSheet(returnFocus = false)
        }
        document.addEventListener("keydown", onKey)
        document.addEventListener("click", onClick)
        val cleanup: () -> Unit = {
            document.removeEventListener("keydown", onKey)
            document.removeEventListener("click", onClick)
        }
        sheetCleanup = cleanup
        cleanups += cleanup
        later(0) { twinsInOrder.firstOrNull { it.visible }?.getElement()?.let { (it as? HTMLElement)?.focus() } }
    }

    private fun closeSheet(returnFocus: Boolean) {
        if (!sheetOpen) return
        sheetOpen = false
        moreSheet.hide()
        moreButton.setAttrIfChanged("aria-expanded", "false")
        sheetCleanup?.let {
            it()
            cleanups.remove(it)
        }
        sheetCleanup = null
        if (returnFocus) (moreButton.getElement() as? HTMLElement)?.focus()
    }

    private fun refreshFullscreenButton(active: Boolean) {
        fullscreenButton.setAttrIfChanged("aria-pressed", active.toString())
        val icon = actionIconClasses(if (active) ActionIcon.FULLSCREEN_EXIT else ActionIcon.FULLSCREEN)
        fullscreenButton.icon = icon
        fullscreenTwin?.icon = icon
    }

    // ── wiring to the session ───────────────────────────────────────────────

    /** Gives the room its session (a speaker session builds the office holder's device controls). */
    fun bind(session: EncounterListenerSession) {
        this.session = session
        if (session is EncounterSpeakerSession && entry.canPublish) {
            val created =
                EncounterPulpitControls(
                    toolbar = controlBar.group(EncounterControlGroup.DEVICES),
                    session = session,
                    onDeviceOn = { kind -> devicePicker?.let { picker -> AppScope.launch { picker.deviceSwitchedOn(kind) } } },
                )
            pulpitControls = created
            // The device controls never move into the sheet, but they take room in the bar: they have to be measured.
            registerBarSlot(ControlBarSlot(EncounterControlSlot.Mic, created.micButton, null))
            registerBarSlot(ControlBarSlot(EncounterControlSlot.Camera, created.cameraButton, null))
        }
        // V1.9.91: the device picker is the LAST control of the devices group, for every role (the speaker can be chosen by all).
        val speakerSession = (session as? EncounterSpeakerSession)?.takeIf { entry.canPublish }
        val picker =
            EncounterDevicePicker(
                devicesGroup = controlBar.group(EncounterControlGroup.DEVICES),
                panelParent = root,
                role = {
                    when {
                        speakerSession != null -> EncounterDeviceRole.OFFICE_HOLDER
                        tables.position != null -> EncounterDeviceRole.CONGREGATION_AT_TABLE
                        else -> EncounterDeviceRole.CONGREGATION
                    }
                },
                speaker = speakerSession,
                tableMic = { tables.microphoneDevices },
                tableQuieted = { tables.quieted },
                output = audioOutput,
                env = deviceEnv,
                announce = { sentence -> deviceLive.content = sentence },
                onBeforeOpen = { closeSheet(returnFocus = false) },
                onVisibilityChanged = { if (::overflow.isInitialized) overflow.recompute() },
            )
        devicePicker = picker
        registerBarSlot(ControlBarSlot(EncounterControlSlot.AudioDevices, picker.button, null))
        overflow.recompute()
    }

    /**
     * Runs after `connect()` succeeded, in the same coroutine as the entry click: a pulpit person's camera goes on first (the browser's
     * permission prompt belongs to that click), then focus, first presence load, polls and timers.
     */
    suspend fun afterConnected() {
        if (disposed) return
        overflow.ensureObserving()
        if (viewer.presenceRole == EncounterPresenceRole.PULPIT) pulpitControls?.startCamera()
        if (disposed) return
        devicePicker?.restoreOutput()
        if (disposed) return
        layout.focusPulpit()
        announceMicOffOnEntry()
        presentPanel.load()
        if (disposed) return
        refreshTables(startedAt = tables.generation)
        if (disposed) return
        startTimers()
        liveBadge.poll()
    }

    // ── presence, tiles, pews ───────────────────────────────────────────────

    private fun onRoster(people: List<EncounterPresentDto>) {
        if (disposed) return
        // Newcomers are measured against the previous SERVER list, not `present` (which onParticipantLeft shortens locally): a roster that
        // still lists a person who just left (presence row not deleted yet) must not read as "somebody joined".
        val before = lastServerRoster
        val firstLoad = !rosterLoaded
        present = people.associateBy { it.memberId }
        lastServerRoster = present.keys
        rosterLoaded = true
        countText.content = gettext("%1 anwesend", people.size)
        // V1.9.79: the pews are the SERVER's picture; the room only mirrors it.
        seating.applyServer(people)
        hands.ordered.filter { it !in present }.forEach { hands.lower(it) }
        if (pendingSeat != null && !seatRequestRunning) pendingSeat = null
        announceNewcomers(before = before, firstLoad = firstLoad)
        renderSeats()
        renderTables()
        syncTiles()
        presentPanel.rerender()
    }

    /** A person who is new in the list after the first load: one anonymous, throttled sentence (no name, no number). */
    private fun announceNewcomers(
        before: Set<String>,
        firstLoad: Boolean,
    ) {
        if (firstLoad) return
        val newcomers = present.keys.any { it !in before && it != viewer.selfIdentity }
        if (!newcomers || !joinAnnouncer.onEvent()) return
        eventLive.content = gettext("Eine Person ist hinzugekommen.")
        later(LIVE_TEXT_MS) { eventLive.content = "" }
    }

    private fun onParticipantLeft(identity: String) {
        if (disposed) return
        present = present - identity
        hands.lower(identity)
        // Until `listPresent` answers, the person simply has no seat any more (the room never assigns one itself).
        seating.forget(identity)
        renderSeats()
        requestPresentRefresh()
    }

    private fun renderSeats() {
        val self = viewer.selfIdentity
        val canChoose = viewer.presenceRole == EncounterPresenceRole.CONGREGATION
        val slots =
            (0 until seating.gridSize).map { seat ->
                val identity = seating.occupantOf(seat)
                val name = identity?.let { present[it]?.displayName }
                SeatSlot(
                    initials = name?.let { encounterInitials(it) },
                    handUp = identity != null && hands.contains(identity),
                    own = identity != null && identity == self,
                    pending = seat == pendingSeat,
                    offered = seating.isChoosable(seat),
                )
            }
        layout.seats.render(SeatGridModel(slots = slots, choosable = canChoose))
        layout.unseated.render(seating.unseated().map { person -> person.memberId to person.displayName }) { hands.contains(it) }
        val mine = seating.seatOf(self)
        layout.setSeatHintVisible(canChoose && mine == null && !tables.seated)
        layout.setReleaseVisible(canChoose && mine != null)
    }

    /** The list alternative's view of the seats; `null` for an office holder (who cannot sit). */
    private fun seatListState(): EncounterSeatListState? {
        if (viewer.presenceRole != EncounterPresenceRole.CONGREGATION) return null
        return EncounterSeatListState(ownSeat = seating.seatOf(viewer.selfIdentity), freeSeats = seating.freeSeats())
    }

    // ── choosing a seat (V1.9.79) ───────────────────────────────────────────

    /**
     * The viewer chose [seat] (`null` = give the own seat up). One request at a time, at most one change per second. There is NO optimistic
     * picture: the seat shows as "pending" until the server answered, and the server's list is the new truth (a lost race is a fixed
     * sentence, never a jumping seat).
     */
    private fun chooseSeat(seat: Int?) {
        if (disposed || viewer.presenceRole != EncounterPresenceRole.CONGREGATION) return
        if (seatRequestRunning || !seatThrottle.tryChoose()) return
        val previous = seating.seatOf(viewer.selfIdentity)
        if (seat != null && seat == previous) return
        seatRequestRunning = true
        pendingSeat = seat
        renderSeats()
        AppScope.launch {
            val outcome =
                guarded {
                    try {
                        SeatOutcome.Done(rpcService<IEncounterSpaceService>().selectSeat(space.id, seat))
                    } catch (e: ConflictException) {
                        SeatOutcome.Taken
                    } catch (e: ServiceBusyException) {
                        SeatOutcome.Busy
                    }
                }
            seatRequestRunning = false
            pendingSeat = null
            if (disposed) return@launch
            when (outcome) {
                is SeatOutcome.Done -> onSeatChosen(outcome.people, requested = seat, previous = previous)
                SeatOutcome.Taken -> {
                    announceSeat(terms.seatTakenAnnouncement())
                    renderSeats()
                    requestPresentRefresh()
                }
                SeatOutcome.Busy -> {
                    announceSeat(gettext("Bitte warten Sie einen Moment und versuchen Sie es erneut."))
                    renderSeats()
                }
                // another failure: `guarded` already told the person; the picture goes back to what the server says
                null -> {
                    renderSeats()
                    requestPresentRefresh()
                }
            }
        }
    }

    private fun onSeatChosen(
        people: List<EncounterPresentDto>,
        requested: Int?,
        previous: Int?,
    ) {
        presentPanel.replace(people)
        val mine = seating.seatOf(viewer.selfIdentity)
        if (mine != null) {
            announceSeat(terms.seatedAnnouncement(encounterSeatRow(mine), encounterSeatPosition(mine)))
            layout.seats.focusSeat(mine)
        } else {
            announceSeat(terms.seatReleasedAnnouncement())
            if (requested == null) previous?.let { seat -> layout.seats.focusSeat(seat) }
        }
        // Tell the others to reload the list (content-free; the server stays the authority).
        AppScope.launch { session?.sendSeatNudge() }
    }

    /**
     * V1.9.90: the standing "microphone is off" band is gone; the state stays on the button (red outline), and an office holder hears it
     * ONCE on entering (fixed sentence, polite region). Not on later toggles. Delayed so it does not collide with the focus announcement.
     */
    private fun announceMicOffOnEntry() {
        val controls = pulpitControls ?: return
        if (controls.isMicOn) return
        window.setTimeout({
            if (!disposed) announceSeat(tr("Ihr Mikrofon ist aus."))
        }, MIC_OFF_ANNOUNCE_DELAY_MS)
    }

    /** A fixed sentence from the vocabulary (never data of a person) into the polite region of the viewer's own seat choice. */
    private fun announceSeat(sentence: String) {
        seatLive.content = sentence
    }

    private sealed interface SeatOutcome {
        class Done(
            val people: List<EncounterPresentDto>,
        ) : SeatOutcome

        data object Taken : SeatOutcome

        data object Busy : SeatOutcome
    }

    /**
     * A seat nudge arrived. Only a person who is in the list can nudge (anything else is dropped unread); the reload goes through the
     * refresh planner (at most every 5 s) after a random wait of up to a second, so twenty people do not all ask at the same instant.
     */
    private fun onSeatNudge(identity: String) {
        if (disposed || identity !in present) return
        later(seatNudgeDelayMs().coerceIn(0, SEAT_NUDGE_JITTER_MS)) { requestPresentRefresh() }
    }

    /** LiveKit says who speaks right now: only the tiles of office holders show it. Nothing is kept. */
    private fun onActiveSpeakers(identities: List<String>) {
        if (disposed) return
        val speaking = identities.toSet()
        tiles.forEach { (identity, tile) -> tile.setSpeaking(identity in speaking) }
    }

    private fun syncTiles() {
        val officers = LinkedHashMap<String, Pair<String, EncounterPresenceRole>>()
        if (viewer.presenceRole != EncounterPresenceRole.CONGREGATION) {
            officers[viewer.selfIdentity] = entry.join.displayName to viewer.presenceRole
        }
        present.values
            .filter { it.role != EncounterPresenceRole.CONGREGATION }
            .forEach { person -> officers[person.memberId] = person.displayName to person.role }
        tiles.keys.filter { it !in officers }.forEach { tiles.remove(it)?.dispose() }
        officers.forEach { (identity, info) ->
            val (name, role) = info
            val tile =
                tiles.getOrPut(identity) {
                    EncounterTile(identity = identity, displayName = name, large = role == EncounterPresenceRole.PULPIT).also { created ->
                        videoElements[identity]?.let { created.setVideo(it) }
                    }
                }
            tile.setName(name)
        }

        fun idsOf(role: EncounterPresenceRole) = officers.filter { it.value.second == role }.keys.sortedBy { officers.getValue(it).first }
        val pulpitIds = idsOf(EncounterPresenceRole.PULPIT)
        val stewardIds = idsOf(EncounterPresenceRole.STEWARD)
        if (pulpitIds != lastPulpitIds) {
            lastPulpitIds = pulpitIds
            layout.setPulpit(pulpitIds.mapNotNull { tiles[it] })
        }
        if (stewardIds != lastStewardIds) {
            lastStewardIds = stewardIds
            layout.setStewards(stewardIds.mapNotNull { tiles[it] })
        }
        layout.setPulpitNames(pulpitIds.map { officers.getValue(it).first })
    }

    // ── media of the office holders ─────────────────────────────────────────

    private fun onRemoteTrack(
        identity: String,
        track: Track,
        publication: TrackPublication,
    ) {
        // Idempotent like the conference: a resync may deliver the same track again; an element made for it earlier is dropped first.
        track.detach().forEach { element -> audioSink.remove(element) }
        when {
            track.kind == "audio" -> {
                audioSink.add(track.attach().also { it.style.display = "none" })
                applyPlenumVolume()
            }
            track.kind == "video" && publication.source == "camera" -> {
                val element = track.attach()
                videoElements[identity] = element
                val tile = tiles[identity]
                if (tile != null) tile.setVideo(element) else requestPresentRefresh()
            }
        }
    }

    private fun onRemoteTrackGone(
        identity: String,
        track: Track,
    ) {
        val detached = track.detach().toList()
        detached.forEach { audioSink.remove(it) }
        if (track.kind == "video") {
            videoElements.remove(identity)
            tiles[identity]?.setVideo(null)
        }
    }

    private fun onLocalVideo(track: Track?) {
        val self = viewer.selfIdentity
        if (track == null) {
            videoElements.remove(self)
            tiles[self]?.setVideo(null)
            return
        }
        val element = track.attach()
        videoElements[self] = element
        tiles[self]?.setVideo(element)
    }

    // ── reactions and chat ──────────────────────────────────────────────────

    private fun onReaction(
        identity: String,
        reaction: EncounterReaction,
    ) {
        if (disposed || !receiveGuard.admit(identity)) return
        // Only the reactions the room allows (HAND_LOWERED belongs to the hand and always passes); anything else is dropped silently.
        if (!admitReaction(reaction, allowedReactions)) return
        // Only the congregation from `listPresent` reacts (V1.9.79: sitting is no precondition -- nobody is seated automatically any
        // more): anybody else's packet is dropped unread.
        if (!seating.isCongregation(identity)) return
        when (reaction) {
            EncounterReaction.HAND -> {
                val wasUp = hands.contains(identity)
                hands.raise(identity)
                if (!wasUp) announceHand(identity)
            }
            EncounterReaction.HAND_LOWERED -> hands.lower(identity)
            EncounterReaction.AMEN, EncounterReaction.APPLAUSE, EncounterReaction.HEART -> showEvent(identity, reaction.option())
        }
        renderSeats()
        presentPanel.rerender()
    }

    /** Shows the symbol at the person's seat, or at the symbol in the row of people without a seat. */
    private fun showEvent(
        identity: String,
        option: EncounterReactionOption,
    ) {
        val seat = seating.seatOf(identity)
        if (seat != null) layout.seats.showEvent(seat, option) else layout.unseated.showEvent(identity, option)
        if (eventAnnouncer.onEvent()) {
            // a fixed sentence from the vocabulary (never data of a person)
            val sentence = terms.reactionFromAudience(option)
            eventLive.content = sentence
            later(LIVE_TEXT_MS) { eventLive.content = "" }
        }
    }

    private fun announceHand(identity: String) {
        val live = handLive ?: return
        val name = present[identity]?.displayName ?: return
        live.content = gettext("%1 hebt die Hand", sanitizeUntrustedI18nText(name))
        later(LIVE_TEXT_MS) { live.content = "" }
    }

    private fun toggleHand() {
        if (!sendThrottle.tryHandToggle()) return
        ownHandUp = !ownHandUp
        val self = viewer.selfIdentity
        if (ownHandUp) hands.raise(self) else hands.lower(self)
        handButton.setAttribute("aria-pressed", ownHandUp.toString())
        handButton.style = if (ownHandUp) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
        renderSeats()
        presentPanel.rerender()
        AppScope.launch { session?.sendReaction(if (ownHandUp) EncounterReaction.HAND else EncounterReaction.HAND_LOWERED) }
    }

    private fun sendEvent(option: EncounterReactionOption) {
        if (option !in allowedReactions || !sendThrottle.tryEvent()) return
        if (seating.isCongregation(viewer.selfIdentity)) showEvent(viewer.selfIdentity, option)
        // The three event reactions share one budget: all of them wait together.
        eventButtons.values.forEach { it.disabled = true }
        eventWaitNote.show()
        later(EncounterReactionSendThrottle.EVENT_GAP_MS.toInt()) {
            eventButtons.values.forEach { it.disabled = !entry.canPublishData }
            eventWaitNote.hide()
        }
        AppScope.launch { session?.sendReaction(option.toWire()) }
    }

    private fun onChat(
        identity: String,
        name: String,
        text: String,
    ) {
        if (disposed || identity == viewer.selfIdentity) return
        chat.add(EncounterChatEntry(senderIdentity = identity, senderName = name, text = text))
        if (!(side.isOpen && side.activeTab == EncounterSideTab.CHAT)) markUnread(true)
    }

    private suspend fun sendChat(text: String): Boolean {
        val sent = session?.sendChat(text) == true
        if (sent) chat.add(EncounterChatEntry(senderIdentity = viewer.selfIdentity, senderName = entry.join.displayName, text = text))
        return sent
    }

    // ── tables (V1.9.80) ────────────────────────────────────────────────────

    /** Draws the tables from the server's list (`present`), overlaid with what this device knows about the viewer's own place. */
    private fun renderTables() {
        val view = tableView ?: return
        if (disposed) return
        val config = space.tables
        val self = viewer.selfIdentity
        val own = tables.position
        val canSit = viewer.presenceRole == EncounterPresenceRole.CONGREGATION
        val sitters = present.values.filter { it.role == EncounterPresenceRole.CONGREGATION && it.table != null && it.tableSeat != null }
        val cards =
            (0 until config.count).map { table ->
                val ownHere = own?.first == table
                val seats =
                    (0 until config.seats).map { seat ->
                        val person = sitters.firstOrNull { it.table == table && it.tableSeat == seat && it.memberId != self }
                        when {
                            own == table to seat ->
                                TableSeatSlot(
                                    initials = encounterInitials(entry.join.displayName),
                                    own = true,
                                    speaking = tables.isSpeaking(self),
                                )
                            tables.pending == table to seat -> TableSeatSlot(initials = null, pending = true)
                            person != null ->
                                TableSeatSlot(
                                    initials = encounterInitials(person.displayName),
                                    speaking = ownHere && tables.isSpeaking(person.memberId),
                                )
                            else -> TableSeatSlot(initials = null)
                        }
                    }
                TableCardModel(
                    table = table,
                    seats = seats,
                    quieted = table in quietedTables || (ownHere && tables.quieted),
                    own = ownHere,
                    micStatus = if (ownHere) tableMicStatus() else null,
                )
            }
        view.render(TablesModel(cards = cards, choosable = canSit && tables.pending == null, canModerate = viewer.canModerate))
        if (canSit && tablesOn) refreshTableControls()
    }

    private fun tableMicStatus(): String =
        when {
            tables.quieted -> gettext("Der Tisch ist beruhigt. Ihr Mikrofon ist gesperrt.")
            tables.micOn -> gettext("Mikrofon an")
            else -> gettext("Mikrofon aus")
        }

    /** The table microphone shows its state through `aria-pressed`; both controls exist only while the viewer sits at a table. */
    private fun refreshTableControls() {
        if (!::tableMicButton.isInitialized) return
        val seated = tables.seated
        val before = tableMicButton.visible
        if (seated) {
            tableMicButton.show()
            louderButton.show()
        } else {
            tableMicButton.hide()
            louderButton.hide()
        }
        tableMicButton.setAttrIfChanged("aria-pressed", tables.micOn.toString())
        tableMicButton.disabled = !tables.canPublish || !tables.connected
        if (before != seated) overflow.recompute()
    }

    /** The viewer sat down or is back in the plenum: the pulpit becomes quieter / normal again, the seat hint follows. */
    private fun onAtTableChanged(atTable: Boolean) {
        if (disposed) return
        if (!atTable) {
            pulpitLouder = false
            if (::louderButton.isInitialized) louderButton.setAttribute("aria-pressed", "false")
        }
        applyPlenumVolume()
        renderSeats()
        presentPanel.rerender()
        // the table microphone field comes and goes with the seat
        devicePicker?.let { picker -> AppScope.launch { picker.refresh() } }
    }

    /** At a table the pulpit is turned down to 30 % (the "Kanzel lauter" control lifts it); a device that ignores `volume` (iOS Safari) keeps full sound. */
    private fun applyPlenumVolume() {
        val volume = if (tables.seated && !pulpitLouder) PLENUM_DUCKED_VOLUME else 1.0
        audioSink.elements.forEach { element -> (element as? HTMLMediaElement)?.volume = volume }
    }

    private fun announceTable(sentence: String) {
        tableLive.content = sentence
    }

    private fun chooseTableSeat(
        table: Int,
        seat: Int,
    ) {
        if (disposed || !tablesOn || viewer.presenceRole != EncounterPresenceRole.CONGREGATION) return
        if (tables.pending != null || !tableThrottle.tryChoose()) return
        if (tables.position == table to seat) return
        AppScope.launch {
            tables.join(table, seat)
            if (!disposed) tables.position?.let { (t, s) -> tableView?.focusSeat(t, s) }
        }
    }

    private fun chooseTableFromList(table: Int) {
        val occupied = sitterSeatsOf(table)
        val seat = (0 until space.tables.seats).firstOrNull { it !in occupied } ?: return
        chooseTableSeat(table, seat)
    }

    private fun sitterSeatsOf(table: Int): Set<Int> =
        present.values
            .filter { it.table == table && it.memberId != viewer.selfIdentity }
            .mapNotNull { it.tableSeat }
            .toSet()

    private fun leaveTable() {
        if (disposed || !tables.seated) return
        AppScope.launch { tables.leave() }
    }

    /** The list alternative's view of the tables; `null` for an office holder (who cannot sit) or a room without tables. */
    private fun tableListState(): EncounterTableListState? {
        if (!tablesOn || viewer.presenceRole != EncounterPresenceRole.CONGREGATION) return null
        val free =
            (0 until space.tables.count).mapNotNull { table ->
                val taken = sitterSeatsOf(table).size + (if (tables.position?.first == table) 1 else 0)
                val left = space.tables.seats - taken
                if (left > 0 && tables.position?.first != table) table to left else null
            }
        return EncounterTableListState(ownTable = tables.position?.first, tablesWithFreeSeats = free)
    }

    /** A moderator quiets or releases a table. The outcome is a fixed sentence with the table number only. */
    private fun quietTable(
        table: Int,
        quiet: Boolean,
    ) {
        if (disposed || !viewer.canModerate) return
        AppScope.launch {
            val done = guarded { rpcService<IEncounterSpaceService>().quietTable(space.id, table, quiet) }
            if (done == null || disposed) return@launch
            announceTable(
                if (quiet) {
                    gettext("Tisch %1 wurde beruhigt.", table + 1)
                } else {
                    gettext(
                        "Die Beruhigung von Tisch %1 wurde aufgehoben.",
                        table + 1,
                    )
                },
            )
            refreshTables(startedAt = tables.generation)
        }
    }

    /**
     * Reloads the table list (quiet flags) and believes the roster about the viewer's own place only when nothing was done meanwhile
     * ([startedAt] is the controller's generation when the refresh began).
     */
    private suspend fun refreshTables(startedAt: Int) {
        if (!tablesOn || disposed) return
        val listed = presentPanel.loadTables() ?: return
        if (disposed) return
        quietedTables = listed.filter { it.quieted }.map { it.table }.toSet()
        val self = viewer.selfIdentity
        if (startedAt == tables.generation && tables.seated && tables.pending == null && rosterLoaded && present[self]?.table == null) {
            tables.serverSaysPlenum()
        }
        renderTables()
    }

    // ── side panel, scene, doors ────────────────────────────────────────────

    private fun toggleSide(tab: EncounterSideTab) {
        side.toggle(tab)
        chatButton.setAttrIfChanged("aria-expanded", (side.isOpen && side.activeTab == EncounterSideTab.CHAT).toString())
        if (viewer.canModerate) {
            broadcastButton.setAttrIfChanged("aria-expanded", (side.isOpen && side.activeTab == EncounterSideTab.STREAM).toString())
        }
    }

    private fun onTabShown(tab: EncounterSideTab) {
        when (tab) {
            EncounterSideTab.CHAT -> {
                markUnread(false)
                chat.focusInput()
            }
            EncounterSideTab.PRESENT -> presentPanel.rerender()
            EncounterSideTab.STREAM -> streamPanel?.load()
        }
    }

    private fun markUnread(unread: Boolean) {
        if (unread) {
            unreadDot.addCssClass("is-on")
            unreadText.content = gettext("Neue Nachrichten")
        } else {
            unreadDot.removeCssClass("is-on")
            unreadText.content = ""
        }
    }

    private fun toggleScene() {
        val off = !layout.isSceneOff
        layout.setSceneOff(off)
        storeEncounterSceneOff(off)
        refreshSceneButton()
    }

    private fun refreshSceneButton() {
        // The label never changes; the state is `aria-pressed` ("the scene is hidden") plus the ring of theme.css.
        sceneButton.setAttrIfChanged("aria-pressed", layout.isSceneOff.toString())
    }

    private fun askCloseDoors() {
        // The dialog lives at body level: in (pseudo-)full screen it would stay behind or outside the room.
        fullscreen.leaveIfActive()
        confirmDialog(
            title = tr("Türen schließen?"),
            message = tr("Alle Anwesenden verlassen den Raum."),
            confirmLabel = tr("Türen schließen"),
            confirmIcon = ActionIcon.CLOSE_DOORS,
            focusCancel = true,
        ) {
            AppScope.launch {
                val closed = guarded { rpcService<IEncounterSpaceService>().closeSpace(space.id) }
                if (closed != null) onDoorsClosed()
            }
        }
    }

    // ── timers ──────────────────────────────────────────────────────────────

    private fun startTimers() {
        if (disposed) return
        every(HAND_EXPIRY_TICK_MS) {
            if (hands.expire().isNotEmpty()) {
                renderSeats()
                presentPanel.rerender()
            }
        }
        every(HAND_RENEW_MS) {
            if (ownHandUp) {
                // LiveKit does not echo our own packets, so refresh our own entry locally or it expires after the TTL.
                hands.raise(viewer.selfIdentity)
                AppScope.launch { session?.sendReaction(EncounterReaction.HAND) }
            }
        }
        every(PRESENT_POLL_MS) { if (pageVisible()) requestPresentRefresh() }
        every(LIVE_POLL_MS) { if (pageVisible()) AppScope.launch { liveBadge.poll() } }
        val onVisible: (org.w3c.dom.events.Event) -> Unit = {
            if (!disposed && pageVisible()) {
                requestPresentRefresh()
                AppScope.launch { liveBadge.poll() }
            }
        }
        document.addEventListener("visibilitychange", onVisible)
        cleanups += { document.removeEventListener("visibilitychange", onVisible) }
    }

    private fun requestPresentRefresh() {
        if (disposed) return
        val wait = refreshPlanner.request() ?: return
        later(wait.toInt()) {
            refreshPlanner.started()
            AppScope.launch {
                val startedAt = tables.generation
                presentPanel.refresh()
                refreshTables(startedAt)
            }
        }
    }

    private fun pageVisible(): Boolean = (document.asDynamic().visibilityState as? String) != "hidden"

    private fun every(
        ms: Int,
        block: () -> Unit,
    ) {
        val handle = window.setInterval({ if (!disposed) block() }, ms)
        cleanups += { window.clearInterval(handle) }
    }

    private fun later(
        ms: Int,
        block: () -> Unit,
    ) {
        var handle = 0
        lateinit var cleanup: () -> Unit
        handle =
            window.setTimeout({
                cleanups.remove(cleanup)
                if (!disposed) block()
            }, ms)
        cleanup = { window.clearTimeout(handle) }
        cleanups += cleanup
    }

    /**
     * V1.9.95: the pulpit speaks the blessing. One call per click (a double click while the call is out is ignored); a refusal or an error
     * is neither shown nor logged (the server swallows a second blessing within ten seconds on its own, and a failed delivery is no concern
     * of the pulpit). The text of a caught exception is never read.
     */
    private fun bless() {
        if (disposed || blessingInFlight) return
        blessingInFlight = true
        AppScope.launch {
            try {
                rpcService<IEncounterSpaceService>().blessSpace(space.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // nothing to report
            } finally {
                blessingInFlight = false
            }
        }
    }

    /** Ends every timer and listener, drops the media elements and leaves the widgets to the owner (which removes the panel). */
    fun dispose() {
        if (disposed) return
        disposed = true
        cleanups.forEach { it() }
        cleanups.clear()
        sheetCleanup = null
        overflow.dispose()
        blessingDisplay.dispose()
        layout.dispose()
        fullscreen.dispose()
        tiles.values.forEach { it.dispose() }
        tiles.clear()
        tables.dispose()
        devicePicker?.dispose()
        tableAudioSink.clear()
        audioSink.clear()
        videoElements.clear()
        untrustedContent(countText, "")
    }

    // ── test access (the DOM tests drive the room through these, never through a private member) ──────

    internal val seatGrid: EncounterSeatGrid get() = layout.seats
    internal val unseatedRow: EncounterUnseatedRow get() = layout.unseated
    internal val sidePanel: EncounterSidePanel get() = side
    internal val raisedHandIds: List<String> get() = hands.ordered
    internal val sceneRoot: Div get() = layout.root
    internal val pulpitRegion: Div get() = layout.pulpit
    internal val liveBadgeView: EncounterLiveBadge get() = liveBadge
    internal val rosterReady: Boolean get() = rosterLoaded
    internal val controlBarView: EncounterControlBar get() = controlBar
    internal val fullscreenControl: EncounterFullscreen get() = fullscreen
    internal val tablesView: EncounterTablesView? get() = tableView
    internal val tableController: EncounterTableController get() = tables

    private companion object {
        const val OVERFLOWED_CLASS = "lapis-encounter-control-overflowed"
        const val ENCOUNTER_MORE_SHEET_ID = "lapis-encounter-more-sheet"
        const val MIC_OFF_ANNOUNCE_DELAY_MS = 750
        const val HAND_EXPIRY_TICK_MS = 5_000
        const val HAND_RENEW_MS = 30_000
        const val PRESENT_POLL_MS = 20_000
        const val LIVE_POLL_MS = 30_000
        const val LIVE_TEXT_MS = 4_000
        const val SEAT_NUDGE_JITTER_MS = 1_000
        const val TABLE_CHOICE_GAP_MS = 2_000.0
        const val PLENUM_DUCKED_VOLUME = 0.3
    }
}
