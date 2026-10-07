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
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.AppState
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.actionIconClasses
import network.lapis.cloud.client.confirmDialog
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.client.livekit.TrackPublication
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.option
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLMediaElement
import kotlin.js.Date

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
 * - The pews: [EncounterSeating], per device; people from `listPresent` only (a LiveKit participant that is not listed -- an egress
 *   bot, a stranger -- never gets a seat). A seat never moves.
 * - Reactions: a hand is a state (renewed every 30 s by its owner, lapses after 90 s without a renewal); amen, applause and heart are
 *   events shown for 3 s at the seat and announced at most every 10 s as a fixed sentence, never as a count. The sender is always the SDK
 *   identity. Only the reactions the room allows ([EncounterSpaceDto.reactions], V1.9.67) get a button and are admitted when they arrive.
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
    private val onDoorsClosed: () -> Unit,
    private val onConnectionLost: (DisconnectCause) -> Unit,
) {
    private val terms: EncounterTerms = termsFor(space.profile)
    private val allowedReactions: Set<EncounterReactionOption> = EncounterReactionOption.normalize(space.reactions).toSet()
    private val root = parent.vPanel(spacing = 10) { addCssClass("lapis-encounter-room") }
    private val infoRow: Div = root.div(className = "lapis-encounter-info d-flex flex-wrap align-items-center gap-3")
    private val countText: Span = infoRow.span(className = "text-muted")
    private val liveBadge = EncounterLiveBadge(infoRow, terms, entry.join.roomId)
    private val eventLive: Div = root.div(className = "visually-hidden")
    private val handLive: Div? = if (viewer.canModerate) root.div(className = "visually-hidden") else null
    private val bands: Div = root.div(className = "lapis-encounter-bands")
    private val main: Div = root.div(className = "lapis-encounter-main")
    private val layout = EncounterSceneLayout(main, terms)
    private val tabs =
        buildList {
            add(EncounterSideTab.CHAT)
            add(EncounterSideTab.PRESENT)
            if (viewer.canModerate) add(EncounterSideTab.STREAM)
        }
    private val side = EncounterSidePanel(main, tabs) { tab -> onTabShown(tab) }
    private val controlBar = EncounterControlBar(root)
    private val controls: Div get() = controlBar.root

    private val seating = EncounterSeating()
    private val seated = mutableSetOf<String>()
    private val hands = EncounterRaisedHands(clock)
    private val sendThrottle = EncounterReactionSendThrottle(clock)
    private val receiveGuard = EncounterReactionReceiveGuard(clock)
    private val eventAnnouncer = EncounterEventAnnouncer(clock)
    private val refreshPlanner = EncounterRefreshPlanner(clock)
    private val audioSink = EncounterMediaHost("lapis-encounter-audio-sink")
    private val tiles = LinkedHashMap<String, EncounterTile>()
    private val videoElements = HashMap<String, HTMLMediaElement>()
    private var present: Map<String, EncounterPresentDto> = emptyMap()
    private var rosterLoaded = false
    private var lastPulpitIds: List<String> = emptyList()
    private var lastStewardIds: List<String> = emptyList()
    private var ownHandUp = false
    private var disposed = false
    private var session: EncounterListenerSession? = null
    private var pulpitControls: EncounterPulpitControls? = null
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
    private lateinit var sceneButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var eventWaitNote: Span
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
        handLive?.setAttribute("role", "status")
        handLive?.setAttribute("aria-live", "polite")
        buildBands()
        buildControls()
        chat.setSendingEnabled(entry.canPublishData)
        audioSink.attachTo(root)
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

    private fun buildControls() {
        val reactions = controlBar.group(EncounterControlGroup.REACTIONS)
        handButton =
            reactions.actionButton(reactionActionIcon(EncounterReactionOption.HAND), reactionLabelContent(EncounterReactionOption.HAND))
        handButton.setAttribute("aria-pressed", "false")
        handButton.disabled = !entry.canPublishData
        handButton.onClick { toggleHand() }
        // The event reactions of the room in canonical order (HAND is the button above, always present).
        EncounterReactionOption.entries.filter { it != EncounterReactionOption.ALWAYS_ON && it in allowedReactions }.forEach { option ->
            val button = reactions.actionButton(reactionActionIcon(option), reactionLabelContent(option))
            button.disabled = !entry.canPublishData
            button.onClick { sendEvent(option) }
            eventButtons[option] = button
        }
        eventWaitNote = reactions.span(tr("Bitte einen Moment warten."), className = "text-muted small")
        eventWaitNote.hide()
        val panels = controlBar.group(EncounterControlGroup.PANELS)
        chatButton = panels.actionButton(ActionIcon.CHAT, tr("Chat"))
        chatButton.setAttribute("aria-expanded", "false")
        chatButton.setAttribute("aria-controls", ENCOUNTER_SIDE_PANEL_ID)
        chatButton.onClick { toggleSide(EncounterSideTab.CHAT) }
        // A persistent polite status region (a region that is created together with its text is not announced): the dot shows through the
        // `is-on` class, the words "Neue Nachrichten" are written into it when a line arrives while the chat is not in view.
        unreadDot = panels.span(className = "lapis-encounter-unread")
        unreadDot.setAttribute("role", "status")
        unreadText = unreadDot.span(className = "visually-hidden")
        val view = controlBar.group(EncounterControlGroup.VIEW)
        sceneButton = view.actionButton(ActionIcon.SCENE, tr("Szene aus"))
        sceneButton.onClick { toggleScene() }
        fullscreenButton = view.actionButton(ActionIcon.FULLSCREEN, tr("Vollbild"))
        fullscreenButton.setAttribute("aria-pressed", "false")
        fullscreenButton.onClick { fullscreen.toggle() }
        if (viewer.canModerate) {
            val moderation = controlBar.group(EncounterControlGroup.MODERATION)
            moderation.actionButton(ActionIcon.BROADCAST, tr("Übertragung"), style = ButtonStyle.OUTLINESECONDARY).onClick {
                toggleSide(EncounterSideTab.STREAM)
            }
            moderation
                .actionButton(
                    ActionIcon.CLOSE_DOORS,
                    tr("Türen schließen"),
                    style = ButtonStyle.OUTLINEDANGER,
                ).onClick { askCloseDoors() }
        }
    }

    private fun refreshFullscreenButton(active: Boolean) {
        fullscreenButton.setAttribute("aria-pressed", active.toString())
        fullscreenButton.text = if (active) tr("Vollbild beenden") else tr("Vollbild")
        fullscreenButton.icon = actionIconClasses(if (active) ActionIcon.FULLSCREEN_EXIT else ActionIcon.FULLSCREEN)
    }

    // ── wiring to the session ───────────────────────────────────────────────

    /** Gives the room its session (a speaker session builds the office holder's device controls). */
    fun bind(session: EncounterListenerSession) {
        this.session = session
        if (session is EncounterSpeakerSession && entry.canPublish) {
            pulpitControls =
                EncounterPulpitControls(toolbar = controlBar.group(EncounterControlGroup.DEVICES), band = bands, session = session)
        }
    }

    /**
     * Runs after `connect()` succeeded, in the same coroutine as the entry click: a pulpit person's camera goes on first (the browser's
     * permission prompt belongs to that click), then focus, first presence load, polls and timers.
     */
    suspend fun afterConnected() {
        if (disposed) return
        if (viewer.presenceRole == EncounterPresenceRole.PULPIT) pulpitControls?.startCamera()
        if (disposed) return
        layout.focusPulpit()
        presentPanel.load()
        if (disposed) return
        startTimers()
        liveBadge.poll()
    }

    // ── presence, tiles, pews ───────────────────────────────────────────────

    private fun onRoster(people: List<EncounterPresentDto>) {
        if (disposed) return
        present = people.associateBy { it.memberId }
        rosterLoaded = true
        countText.content = gettext("%1 anwesend", people.size)
        syncSeats()
        syncTiles()
        presentPanel.rerender()
    }

    private fun onParticipantLeft(identity: String) {
        if (disposed) return
        present = present - identity
        hands.lower(identity)
        if (seated.remove(identity)) seating.release(identity)
        renderSeats()
        requestPresentRefresh()
    }

    private fun syncSeats() {
        val congregation = present.values.filter { it.role == EncounterPresenceRole.CONGREGATION }.sortedBy { it.displayName }
        val ids = congregation.map { it.memberId }.toSet()
        seated.filter { it !in ids }.forEach {
            seating.release(it)
            seated.remove(it)
            hands.lower(it)
        }
        // The people already there sit down in NAME order (the order of arrival must not be readable from the pews); later arrivals take the
        // first free seat.
        congregation.forEach { person -> if (seated.add(person.memberId)) seating.assign(person.memberId) }
        renderSeats()
    }

    private fun renderSeats() {
        layout.seats.ensureSeats(seating.seatCount)
        for (seat in 0 until seating.seatCount) {
            val identity = seating.occupantOf(seat)
            layout.seats.update(
                seat = seat,
                name = identity?.let { present[it]?.displayName },
                handUp =
                    identity != null && hands.contains(identity),
            )
        }
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
            track.kind == "audio" -> audioSink.add(track.attach().also { it.style.display = "none" })
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
        // Only people who SIT (the congregation from `listPresent`) react: anybody else's packet is dropped unread.
        val seat = seating.seatOf(identity) ?: return
        when (reaction) {
            EncounterReaction.HAND -> {
                val wasUp = hands.contains(identity)
                hands.raise(identity)
                if (!wasUp) announceHand(identity)
            }
            EncounterReaction.HAND_LOWERED -> hands.lower(identity)
            EncounterReaction.AMEN, EncounterReaction.APPLAUSE, EncounterReaction.HEART -> showEvent(seat, reaction.option())
        }
        renderSeats()
        presentPanel.rerender()
    }

    private fun showEvent(
        seat: Int,
        option: EncounterReactionOption,
    ) {
        layout.seats.showEvent(seat, option)
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
        seating.seatOf(viewer.selfIdentity)?.let { showEvent(it, option) }
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

    // ── side panel, scene, doors ────────────────────────────────────────────

    private fun toggleSide(tab: EncounterSideTab) {
        side.toggle(tab)
        chatButton.setAttribute("aria-expanded", (side.isOpen && side.activeTab == EncounterSideTab.CHAT).toString())
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
        sceneButton.text = if (layout.isSceneOff) tr("Szene ein") else tr("Szene aus")
    }

    private fun askCloseDoors() {
        // The dialog lives at body level: in (pseudo-)full screen it would stay behind or outside the room.
        fullscreen.leaveIfActive()
        confirmDialog(
            title = tr("Türen schließen?"),
            message = tr("Alle Anwesenden verlassen den Raum."),
            confirmLabel = tr("Türen schließen"),
            confirmIcon = ActionIcon.CLOSE_DOORS,
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
            AppScope.launch { presentPanel.refresh() }
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

    /** Ends every timer and listener, drops the media elements and leaves the widgets to the owner (which removes the panel). */
    fun dispose() {
        if (disposed) return
        disposed = true
        cleanups.forEach { it() }
        cleanups.clear()
        layout.dispose()
        fullscreen.dispose()
        tiles.values.forEach { it.dispose() }
        tiles.clear()
        audioSink.clear()
        videoElements.clear()
        untrustedContent(countText, "")
    }

    // ── test access (the DOM tests drive the room through these, never through a private member) ──────

    internal val seatGrid: EncounterSeatGrid get() = layout.seats
    internal val sidePanel: EncounterSidePanel get() = side
    internal val raisedHandIds: List<String> get() = hands.ordered
    internal val sceneRoot: Div get() = layout.root
    internal val pulpitRegion: Div get() = layout.pulpit
    internal val liveBadgeView: EncounterLiveBadge get() = liveBadge
    internal val rosterReady: Boolean get() = rosterLoaded
    internal val controlBarView: EncounterControlBar get() = controlBar
    internal val fullscreenControl: EncounterFullscreen get() = fullscreen

    private companion object {
        const val HAND_EXPIRY_TICK_MS = 5_000
        const val HAND_RENEW_MS = 30_000
        const val PRESENT_POLL_MS = 20_000
        const val LIVE_POLL_MS = 30_000
        const val LIVE_TEXT_MS = 4_000
    }
}
