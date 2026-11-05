package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.DeviceEnumeration
import network.lapis.cloud.client.livekit.HandAnnouncementThrottle
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.shared.conference.orderTilesByRaisedHands
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import org.w3c.dom.HTMLElement

/** The phases of the guest call the state line tells apart. */
internal enum class GuestCallPhase { CONNECTING, CONNECTED, RECONNECTING, ENDED, UNAVAILABLE, FAILED }

/**
 * V1.9.272 -- the ONE source of the state sentence at the top of the guest call view. Connected, it names the real state of microphone and
 * camera (the old static "Mikrofon und Kamera sind aus." contradicted the buttons as soon as one was switched on).
 */
internal fun guestStateLine(
    phase: GuestCallPhase,
    micOn: Boolean,
    cameraOn: Boolean,
): String =
    when (phase) {
        GuestCallPhase.CONNECTING -> gettext("Verbindung wird aufgebaut …")
        GuestCallPhase.CONNECTED ->
            listOf(
                gettext("Sie sind in der Videokonferenz."),
                if (micOn) gettext("Ihr Mikrofon ist an.") else gettext("Ihr Mikrofon ist aus."),
                if (cameraOn) gettext("Ihre Kamera ist an.") else gettext("Ihre Kamera ist aus."),
            ).joinToString(" ")
        GuestCallPhase.RECONNECTING -> gettext("Die Verbindung wird wiederhergestellt …")
        GuestCallPhase.ENDED -> gettext("Die Verbindung wurde beendet.")
        GuestCallPhase.UNAVAILABLE -> gettext("Die Teilnahme ist gerade nicht möglich. Bitte versuchen Sie es erneut.")
        GuestCallPhase.FAILED -> gettext("Die Verbindung konnte nicht aufgebaut werden.")
    }

/** The bold state line: a polite live region of its own (the grey event line below it only carries notes and errors). */
internal class GuestStateLine(
    parent: Container,
) {
    val element: Div =
        parent.div {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            setAttribute("aria-atomic", "true")
            addCssClasses("fw-bold")
        }

    fun render(
        phase: GuestCallPhase,
        micOn: Boolean,
        cameraOn: Boolean,
    ) {
        element.content = guestStateLine(phase, micOn, cameraOn)
    }
}

/** Entry used by the join screen: builds the lean call view of a person without an account. */
internal fun startEventGuestCall(
    host: SimplePanel,
    response: EventGuestJoinResponseDto,
    onLeft: () -> Unit,
    // V1.9.280 -- the slug of the event: needed to acknowledge a capture consent that is asked for after the join (null = no in-room question).
    slug: String? = null,
    api: EventGuestApi = EventGuestHttp,
) {
    EventGuestCallView(host = host, response = response, onLeft = onLeft, slug = slug, api = api).show()
}

/**
 * Welle V1.9.113 -- the lean call view of a person who joined an event WITHOUT an account: the tiles of the participants, the own
 * microphone and camera (both OFF at the start), and "Verlassen". Deliberately nothing else -- no whiteboard, no notes, no roster, no chat,
 * no recording: the person has no identity the room's other features could attach to, and every such call would need a member session.
 *
 * It uses the same [LiveKitRoomSession] as the member call (the token is a normal LiveKit join token pinned to this one room). The tiles
 * are plain DOM (a media element appended into a KVision-managed element would be discarded on re-render, see the notes in
 * `ConferenceScreen.kt`); participant names come from the LiveKit token and are rendered as text only. A lost connection ends in a notice
 * with "Erneut beitreten" (back to the join form -- the name has to be entered again, nothing was kept). The LiveKit server refreshes the
 * token of a connected client on its own, so a call is not cut short by the (short) token lifetime.
 */
internal class EventGuestCallView(
    private val host: SimplePanel,
    private val response: EventGuestJoinResponseDto,
    private val onLeft: () -> Unit,
    private val slug: String? = null,
    private val api: EventGuestApi = EventGuestHttp,
    /** V1.9.282: whether this device can share a screen (`getDisplayMedia`); a seam for the DOM test. */
    private val screenShareSupported: () -> Boolean = { screenShareAvailable() },
) {
    private var gridElement: HTMLElement? = null
    private val tiles = LinkedHashMap<String, HTMLElement>()
    private var micOn = false
    private var cameraOn = false
    private var left = false
    private lateinit var micButton: Button
    private lateinit var cameraButton: Button

    /** V1.9.282: built ONCE after the camera button and only shown / hidden afterwards. */
    private var shareButton: Button? = null
    private var shareAllowed = false
    private var sharing = false
    private var shareLiveRegion: Div? = null
    private var shareNoticeTimer: Int? = null
    private val shareThrottle = GuestNoticeThrottle({ window.performance.now() })

    /** Seam for the DOM test: replaces the real [LiveKitRoomSession.setScreenShare]. */
    internal var screenShareStarter: (suspend (Boolean) -> Unit)? = null
    private lateinit var statusLine: Div
    private lateinit var stateLine: GuestStateLine
    private var phase = GuestCallPhase.CONNECTING
    private var devicePanel: EventGuestDevicePanel? = null

    /** The current local camera track, kept so the preview can be attached again after a camera switch. */
    private var localVideoTrack: Track? = null

    /** V1.9.156: the capture line; built in [show] before the connection starts and only ever fed by the server's packets. */
    private var captureBanner: EventCaptureBanner? = null

    /** V1.9.280: the open in-room consent dialog, if any (at most one). */
    private var consentDialog: InRoomConsentHandle? = null

    /**
     * V1.9.281: the raised-hand logic of this guest (the OWN hand through [GuestHandGateway], the others' hands from the server's packets). Built in
     * [show] once the identity is known; `null` before and after the call. Declared BEFORE [session], whose packet callback reaches it.
     */
    private var handController: ConferenceHandController? = null
    private var handButton: Button? = null
    private val handHintId = "guest-hand-hint-${nextHandHintId++}"
    private val session: LiveKitRoomSession =
        LiveKitRoomSession(
            onRemoteTrack = { identity, displayName, track, _ -> attachRemote(identity, displayName, track) },
            onRemoteTrackGone = { identity, track, _ -> detachRemote(identity, track) },
            onParticipantJoined = { identity, displayName -> ensureTile(identity, displayName) },
            onParticipantLeft = { identity -> removeTile(identity) },
            onLocalVideoTrack = { track ->
                localVideoTrack = track
                showLocalVideo(track)
            },
            onLocalTrackMuteChanged = { source, muted ->
                if (source == "microphone") micOn = !muted
                if (source == "camera") cameraOn = !muted
                syncButtons()
            },
            onRecordingStatusChanged = { _ -> },
            onLocalScreenSharePermission = { allowed -> onScreenSharePermission(allowed) },
            onLocalScreenShareEnded = { onScreenShareEnded() },
            onEventCapture = { live -> captureBanner?.render(live) },
            onEventCaptureAsk = { askCaptureConsent() },
            onActiveSpeakersChanged = { _ -> },
            // V1.9.281: the server-ordered queue; the sender check (never from a participant) stays in LiveKitRoomSession
            onHandQueue = { dto -> onHandBroadcast(dto) },
            onActiveDeviceChanged = { kind, deviceId -> devicePanel?.syncActive(kind, deviceId) },
            onMediaDevicesChanged = { devicePanel?.let { panel -> AppScope.launch { panel.refresh(preserveFocus = true) } } },
            // Fires for the "enable" paths (microphone / camera switched on), so the "aktiviert" wording is the right one.
            onMediaDevicesError = { kind, failure ->
                kind?.let { statusLine.content = conferenceDeviceEnableErrorMessage(it, failure) }
            },
            onChat = { _ -> },
            onWhiteboardPreview = { _, _, _ -> },
            onWhiteboardCommit = { _, _, _ -> },
            onNotesCommit = { _, _, _ -> },
            onReconnecting = { setPhase(GuestCallPhase.RECONNECTING) },
            onReconnected = { setPhase(GuestCallPhase.CONNECTED) },
            onDisconnected = { _ -> onConnectionEnded() },
        )

    fun show() {
        // First in the picture: a person who joined an event that may be recorded sees, from the first frame on, whether it is (the join answer
        // carries the state at that moment; the server's packets update it).
        captureBanner = EventCaptureBanner(host).also { it.render(response.captureLive) }
        stateLine = GuestStateLine(host)
        stateLine.render(phase, micOn, cameraOn)
        statusLine =
            host.div {
                setAttribute("role", "status")
                setAttribute("aria-live", "polite")
                addCssClasses("text-muted small")
            }
        // Hook registered BEFORE the grid is added (addWithLifecycle): the connection starts when the tile container really is in the document.
        host.addWithLifecycle(
            Div { addCssClasses("d-flex flex-wrap gap-2") },
            onInsert = { vnode ->
                if (gridElement == null) {
                    gridElement = vnode.elm as? HTMLElement
                    connect()
                }
            },
        )
        // The row of buttons wraps (a fourth button must not push the page wider than a 390 px phone); its buttons are added below, once the
        // panel they point at exists.
        val controls = host.div(className = "d-flex flex-wrap gap-2 my-2")
        val open = runCatching { window.matchMedia("(min-width: 768px)").matches }.getOrDefault(false)
        val panel =
            EventGuestDevicePanel(
                parent = host,
                port = devicePort,
                onNote = { note -> statusLine.content = note },
                onCameraSwitched = { showLocalVideo(localVideoTrack) },
                initiallyOpen = open,
            )
        devicePanel = panel
        micButton =
            controls.button(tr("Mikrofon einschalten"), style = ButtonStyle.OUTLINESECONDARY) {
                setStyle("min-height", "44px")
                onClick { toggleMicrophone() }
            }
        cameraButton =
            controls.button(tr("Kamera einschalten"), style = ButtonStyle.OUTLINESECONDARY) {
                setStyle("min-height", "44px")
                onClick { toggleCamera() }
            }
        buildShareControl(controls)
        buildHandControl(controls)
        controls.button(tr("Geräte"), style = ButtonStyle.OUTLINESECONDARY) {
            setStyle("min-height", "44px")
            setAttribute("aria-controls", panel.panelId)
            setAttribute("aria-expanded", panel.isOpen.toString())
            onClick { setAttribute("aria-expanded", panel.toggle().toString()) }
        }
        controls.button(tr("Verlassen"), style = ButtonStyle.OUTLINEDANGER) {
            setStyle("min-height", "44px")
            onClick { leave() }
        }
    }

    // ── screen share (V1.9.282) ───────────────────────────────────────────────────────────────

    /** Built once, hidden until a moderator allows the share. The live region is a sibling, also built once. */
    private fun buildShareControl(controls: Container) {
        shareButton =
            controls.actionButton(ActionIcon.SCREEN_SHARE, tr("Bildschirm teilen"), style = ButtonStyle.OUTLINESECONDARY) {
                setStyle("min-height", "44px")
                visible = false
                onClick { toggleScreenShare() }
            }
        shareLiveRegion =
            host.div {
                setAttribute("role", "status")
                setAttribute("aria-live", "polite")
                addCssClasses("text-muted small")
            }
    }

    private fun syncShareButton() {
        val button = shareButton ?: return
        button.text = if (sharing) tr("Teilen beenden") else tr("Bildschirm teilen")
        button.visible = guestShareButtonVisible(allowed = shareAllowed, supported = screenShareSupported(), sharing = sharing)
    }

    private fun toggleScreenShare() {
        if (left) return
        val start = !sharing
        // never start without the permission; ending is always possible
        if (start && !shareAllowed) return
        AppScope.launch {
            try {
                screenShareStarter?.invoke(start) ?: session.setScreenShare(start)
                sharing = start
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // the person closed the picker or the browser refused: no sentence, the state stays as it was
            }
            syncShareButton()
        }
    }

    /** The own permission changed (or was read after the connect). The server's grant is the only truth; nothing is cached. */
    internal fun onScreenSharePermission(allowed: Boolean) {
        val notice = guestShareNoticeFor(previous = shareAllowed, allowed = allowed, sharing = sharing, supported = screenShareSupported())
        shareAllowed = allowed
        if (!allowed && sharing) {
            // the permission is gone while sharing: stop the share on this side at once
            AppScope.launch {
                runCatching { screenShareStarter?.invoke(false) ?: session.setScreenShare(false) }
                sharing = false
                syncShareButton()
            }
            sharing = false
        }
        syncShareButton()
        if (notice != null && !left) announceShare(guestShareNoticeText(notice))
    }

    /** The own share ended by itself (the browser's "stop sharing"): the button goes back to its resting state. */
    internal fun onScreenShareEnded() {
        sharing = false
        syncShareButton()
    }

    private fun announceShare(text: String) {
        val region = shareLiveRegion ?: return
        shareThrottle.offer(text)?.let {
            region.content = it
            return
        }
        if (shareNoticeTimer != null) return
        shareNoticeTimer =
            window.setTimeout({
                shareNoticeTimer = null
                if (!left) shareThrottle.flush()?.let { shareLiveRegion?.content = it }
            }, shareThrottle.waitMs())
    }

    internal fun disposeShare() {
        shareNoticeTimer?.let { window.clearTimeout(it) }
        shareNoticeTimer = null
        shareThrottle.clear()
        sharing = false
        shareAllowed = false
        shareLiveRegion?.content = ""
        shareButton?.visible = false
    }

    private val devicePort: GuestDevicePort =
        object : GuestDevicePort {
            override val publishesMedia: Boolean get() = session.publishesMedia

            override suspend fun list(kind: ConferenceDeviceKind): DeviceEnumeration = session.listDevicesWithRawCount(kind)

            override fun active(kind: ConferenceDeviceKind): String? = session.activeDeviceId(kind)

            override suspend fun switch(
                kind: ConferenceDeviceKind,
                deviceId: String,
            ): ConferenceDeviceFailure? = session.switchDevice(kind, deviceId)
        }

    internal fun setPhase(next: GuestCallPhase) {
        phase = next
        renderState()
        syncHandButton()
    }

    private fun renderState() {
        stateLine.render(phase, micOn, cameraOn)
    }

    private fun connect() {
        AppScope.launch {
            val url = response.serverUrl
            val token = response.token
            if (url == null || token == null) {
                setPhase(GuestCallPhase.UNAVAILABLE)
                return@launch
            }
            setPhase(GuestCallPhase.CONNECTING)
            val failure = session.connect(serverUrl = url, token = token, turnServers = response.turnServers)
            if (failure != null) {
                onConnectionEnded(GuestCallPhase.FAILED)
            } else {
                setPhase(GuestCallPhase.CONNECTED)
                devicePanel?.onConnected()
            }
        }
    }

    private fun toggleMicrophone() {
        AppScope.launch {
            val failure = session.setMicrophone(!micOn)
            if (failure != null) {
                statusLine.content = conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.MICROPHONE, failure)
            } else {
                micOn = !micOn
                statusLine.content = ""
                syncButtons()
                // The labels of the devices are known only after the browser let the page use one: list them again.
                devicePanel?.refresh(preserveFocus = false)
            }
        }
    }

    private fun toggleCamera() {
        AppScope.launch {
            val failure = session.setCamera(!cameraOn)
            if (failure != null) {
                statusLine.content = conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.CAMERA, failure)
            } else {
                cameraOn = !cameraOn
                statusLine.content = ""
                syncButtons()
                devicePanel?.refresh(preserveFocus = false)
            }
        }
    }

    private fun syncButtons() {
        micButton.text = if (micOn) tr("Mikrofon ausschalten") else tr("Mikrofon einschalten")
        cameraButton.text = if (cameraOn) tr("Kamera ausschalten") else tr("Kamera einschalten")
        renderState()
    }

    /**
     * V1.9.280 -- the server asks this guest to acknowledge the capture consent that was switched on after they entered. One dialog at a time; it closes
     * only when the server accepted. "Ablehnen" leaves the room (LiveKit is disconnected, nothing is stored about the refusal).
     */
    private fun askCaptureConsent() {
        val eventSlug = slug ?: return
        val token = response.token ?: return
        if (left) return
        // one dialog at a time; a repeated ask never moves the focus of a person who is reading or ticking
        if (consentDialog?.isOpen == true) return
        AppScope.launch {
            val notice = api.state(eventSlug)?.captureNotice ?: return@launch
            if (left || consentDialog?.isOpen == true) return@launch
            consentDialog =
                showInRoomCaptureConsent(
                    parent = host,
                    notice = notice,
                    onAccept = { input ->
                        when (api.acknowledgeCapture(slug = eventSlug, token = token, consent = input)) {
                            EventGuestAckHttpResult.ACCEPTED -> InRoomAcceptResult.ACCEPTED
                            EventGuestAckHttpResult.OUTDATED -> InRoomAcceptResult.OUTDATED
                            EventGuestAckHttpResult.FAILED -> {
                                notifyError(tr("Das hat nicht geklappt. Bitte versuchen Sie es noch einmal."))
                                InRoomAcceptResult.FAILED
                            }
                        }
                    },
                    onDecline = {
                        notifyInfo(
                            tr(
                                "Sie haben den Raum verlassen. Solange Aufzeichnung und Übertragung erlaubt sind, ist eine Teilnahme nur mit Zustimmung möglich.",
                            ),
                        )
                        leave()
                    },
                    reloadNotice = { api.state(eventSlug)?.captureNotice },
                )
        }
    }

    private fun leave() {
        if (left) return
        left = true
        endHands()
        disposeShare()
        consentDialog?.dispose()
        consentDialog = null
        devicePanel?.dispose()
        AppScope.launch {
            runCatching { session.disconnect() }
            onLeft()
        }
    }

    private fun onConnectionEnded(ended: GuestCallPhase = GuestCallPhase.ENDED) {
        if (left) return
        left = true
        endHands()
        disposeShare()
        consentDialog?.dispose()
        consentDialog = null
        devicePanel?.dispose()
        statusLine.content = ""
        setPhase(ended)
        host.button(tr("Erneut beitreten"), style = ButtonStyle.PRIMARY) {
            onClick { onLeft() }
        }
    }

    // ── tiles (plain DOM) ─────────────────────────────────────────────────────────────────────

    internal fun ensureTile(
        identity: String,
        displayName: String,
    ): HTMLElement {
        tiles[identity]?.let { return it }
        val grid = gridElement ?: return document.createElement("div") as HTMLElement
        val tile = document.createElement("div") as HTMLElement
        // position:relative: the hand badge and the hand ring of the tile are positioned against it
        tile.style.cssText =
            "width:240px;max-width:100%;border:1px solid var(--bs-border-color);border-radius:6px;overflow:hidden;position:relative;"
        val media = document.createElement("div") as HTMLElement
        media.setAttribute("data-role", "media")
        media.style.cssText = "background:var(--bs-tertiary-bg);min-height:135px;"
        val label = document.createElement("div") as HTMLElement
        label.style.cssText = "padding:4px 8px;font-size:0.85rem;"
        // The name is rendered as TEXT only -- never as HTML or as a widget content that KVision would resolve.
        label.textContent = displayName
        tile.appendChild(media)
        tile.appendChild(label)
        // V1.9.281: built ONCE with the tile and only shown / hidden afterwards (no node is added, removed or moved by a hand change)
        tile.appendChild(createHandBadge().also { it.setAttribute("data-role", "hand-badge") })
        grid.appendChild(tile)
        tiles[identity] = tile
        refreshHand()
        return tile
    }

    private fun removeTile(identity: String) {
        tiles.remove(identity)?.let { it.parentNode?.removeChild(it) }
        refreshHand()
    }

    // ── raised hands (V1.9.281) ───────────────────────────────────────────────────────────────

    /** The LiveKit identity a tile stands for: the own tile is keyed [LOCAL_TILE_ID], not by the identity. */
    private fun identityOfTile(key: String): String = if (key == LOCAL_TILE_ID) response.identity ?: key else key

    private fun buildHandControl(controls: Container) {
        val ownIdentity = response.identity
        val ownSlug = slug
        val token = response.handToken
        if (ownIdentity != null && ownSlug != null && token != null) {
            handController =
                ConferenceHandController(
                    self = ownIdentity,
                    gateway = GuestHandGateway(api = api, slug = ownSlug, handToken = token),
                    scope = AppScope,
                    onChanged = { refreshHand() },
                    announceSelf = { statusLine.content = it },
                    announceModerators = {},
                    notifyError = { statusLine.content = it },
                    isModerator = false,
                    announceEnabled = { false },
                    nameOf = { it },
                    throttle = HandAnnouncementThrottle({ window.performance.now() }),
                )
        }
        // The hint is visually hidden text the button points at while it cannot be used yet (a title alone is no accessible description on touch).
        controls.div(className = "visually-hidden") {
            id = handHintId
            content = tr("Die Hand kann erst nach dem Verbinden gehoben werden.")
        }
        handButton =
            controls.actionButton(ActionIcon.HAND, tr("Hand heben"), style = ButtonStyle.OUTLINESECONDARY) {
                setStyle("min-height", "44px")
                onClick { AppScope.launch { handController?.toggleSelf() } }
            }
        syncHandButton()
    }

    /** The button is usable only while connected, with a token and while the call is on; before that it says why (title + described-by). */
    private fun syncHandButton() {
        val button = handButton ?: return
        val controller = handController
        val connected = phase == GuestCallPhase.CONNECTED
        button.text = if (controller?.selfRaised == true) tr("Hand senken") else tr("Hand heben")
        button.disabled = !connected || controller == null || left || controller.busy
        if (!connected && phase != GuestCallPhase.ENDED && !left) {
            val hint = resolvedAttributeText(tr("Die Hand kann erst nach dem Verbinden gehoben werden."))
            button.setAttribute("title", hint)
            button.setAttribute("aria-describedby", handHintId)
        } else {
            button.removeAttribute("title")
            button.removeAttribute("aria-describedby")
        }
    }

    /** A hands packet of the server (already validated by the session); also the seam of the DOM test. */
    internal fun onHandBroadcast(dto: ConferenceHandQueueDto) {
        handController?.onBroadcast(dto)
    }

    /**
     * Redraws everything the hands show: badge and ring per tile, the visual order (CSS `order`, the DOM is NOT reordered -- a `<video>` must
     * never be re-attached for a hand), and the button.
     */
    internal fun refreshHand() {
        val raised = handController?.order.orEmpty()
        val raisedSet = raised.toSet()
        val byIdentity = LinkedHashMap<String, HTMLElement>()
        for ((key, tile) in tiles) byIdentity[identityOfTile(key)] = tile
        for ((identity, tile) in byIdentity) {
            val up = identity in raisedSet
            (tile.querySelector("[data-role=hand-badge]") as? HTMLElement)?.let { toggleHandBadge(it, up) }
            tile.classList.toggle(CONFERENCE_TILE_HAND_CLASS, up)
        }
        if (raised.isEmpty()) {
            byIdentity.values.forEach { it.style.removeProperty("order") }
        } else {
            val ordered = orderTilesByRaisedHands(base = byIdentity.keys.toList(), raisedInOrder = raised, selfId = response.identity)
            ordered.forEachIndexed { index, identity -> byIdentity[identity]?.style?.setProperty("order", index.toString()) }
        }
        syncHandButton()
    }

    /** The call is over: nothing may announce or ask any more, the button is off. */
    internal fun endHands() {
        handController?.dispose()
        handController = null
        syncHandButton()
    }

    private fun attachRemote(
        identity: String,
        displayName: String,
        track: Track,
    ) {
        // `attach()` without an argument creates a NEW element every call: drop what an earlier call (a resync) made for this track first.
        track.detach().forEach { el -> el.parentNode?.removeChild(el) }
        val tile = ensureTile(identity, displayName)
        val element = track.attach()
        if (track.kind == "video") {
            element.style.cssText = "width:100%;display:block;"
            (tile.querySelector("[data-role=media]") as? HTMLElement)?.appendChild(element)
        } else {
            element.style.cssText = "display:none;"
            tile.appendChild(element)
        }
    }

    private fun detachRemote(
        identity: String,
        track: Track,
    ) {
        track.detach().forEach { el -> el.parentNode?.removeChild(el) }
        if (tiles[identity] == null) return
    }

    private fun showLocalVideo(track: Track?) {
        val tile = ensureTile(LOCAL_TILE_ID, response.displayName ?: resolvedAttributeText(tr("Sie")))
        val media = tile.querySelector("[data-role=media]") as? HTMLElement ?: return
        while (media.firstChild != null) media.removeChild(media.firstChild!!)
        if (track != null) {
            val element = track.attach()
            element.style.cssText = "width:100%;display:block;"
            media.appendChild(element)
        }
    }

    private companion object {
        const val LOCAL_TILE_ID = "local"

        /** Distinguishes the hint element of several views in one document (an id must be unique). */
        var nextHandHintId = 0
    }
}
