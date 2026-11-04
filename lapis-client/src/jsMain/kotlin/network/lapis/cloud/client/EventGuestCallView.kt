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
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Track
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
) {
    private var gridElement: HTMLElement? = null
    private val tiles = LinkedHashMap<String, HTMLElement>()
    private var micOn = false
    private var cameraOn = false
    private var left = false
    private lateinit var micButton: Button
    private lateinit var cameraButton: Button
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
            onEventCapture = { live -> captureBanner?.render(live) },
            onEventCaptureAsk = { askCaptureConsent() },
            onActiveSpeakersChanged = { _ -> },
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

    private fun setPhase(next: GuestCallPhase) {
        phase = next
        renderState()
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

    private fun ensureTile(
        identity: String,
        displayName: String,
    ): HTMLElement {
        tiles[identity]?.let { return it }
        val grid = gridElement ?: return document.createElement("div") as HTMLElement
        val tile = document.createElement("div") as HTMLElement
        tile.style.cssText = "width:240px;max-width:100%;border:1px solid var(--bs-border-color);border-radius:6px;overflow:hidden;"
        val media = document.createElement("div") as HTMLElement
        media.setAttribute("data-role", "media")
        media.style.cssText = "background:var(--bs-tertiary-bg);min-height:135px;"
        val label = document.createElement("div") as HTMLElement
        label.style.cssText = "padding:4px 8px;font-size:0.85rem;"
        // The name is rendered as TEXT only -- never as HTML or as a widget content that KVision would resolve.
        label.textContent = displayName
        tile.appendChild(media)
        tile.appendChild(label)
        grid.appendChild(tile)
        tiles[identity] = tile
        return tile
    }

    private fun removeTile(identity: String) {
        tiles.remove(identity)?.let { it.parentNode?.removeChild(it) }
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
    }
}
