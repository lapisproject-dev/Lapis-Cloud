package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.browser.document
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import org.w3c.dom.HTMLElement

/** Entry used by the join screen: builds the lean call view of a person without an account. */
internal fun startEventGuestCall(
    host: SimplePanel,
    response: EventGuestJoinResponseDto,
    onLeft: () -> Unit,
) {
    EventGuestCallView(host = host, response = response, onLeft = onLeft).show()
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
) {
    private var gridElement: HTMLElement? = null
    private val tiles = LinkedHashMap<String, HTMLElement>()
    private var micOn = false
    private var cameraOn = false
    private var left = false
    private lateinit var micButton: Button
    private lateinit var cameraButton: Button
    private lateinit var statusLine: Div
    private val session: LiveKitRoomSession =
        LiveKitRoomSession(
            onRemoteTrack = { identity, displayName, track, _ -> attachRemote(identity, displayName, track) },
            onRemoteTrackGone = { identity, track, _ -> detachRemote(identity, track) },
            onParticipantJoined = { identity, displayName -> ensureTile(identity, displayName) },
            onParticipantLeft = { identity -> removeTile(identity) },
            onLocalVideoTrack = { track -> showLocalVideo(track) },
            onLocalTrackMuteChanged = { source, muted ->
                if (source == "microphone") micOn = !muted
                if (source == "camera") cameraOn = !muted
                syncButtons()
            },
            onRecordingStatusChanged = { _ -> },
            onActiveSpeakersChanged = { _ -> },
            onActiveDeviceChanged = { _, _ -> },
            onMediaDevicesChanged = {},
            onMediaDevicesError = { _, _ -> },
            onChat = { _ -> },
            onWhiteboardPreview = { _, _, _ -> },
            onWhiteboardCommit = { _, _, _ -> },
            onNotesCommit = { _, _, _ -> },
            onReconnecting = { statusLine.content = tr("Die Verbindung wird wiederhergestellt …") },
            onReconnected = { statusLine.content = "" },
            onDisconnected = { _ -> onConnectionEnded() },
        )

    fun show() {
        host.div(tr("Sie sind in der Videokonferenz. Mikrofon und Kamera sind aus.")) { addCssClasses("fw-bold") }
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
        val controls = host.hPanel(spacing = 8)
        micButton =
            controls.button(tr("Mikrofon einschalten"), style = ButtonStyle.OUTLINESECONDARY) {
                onClick { toggleMicrophone() }
            }
        cameraButton =
            controls.button(tr("Kamera einschalten"), style = ButtonStyle.OUTLINESECONDARY) {
                onClick { toggleCamera() }
            }
        controls.button(tr("Verlassen"), style = ButtonStyle.OUTLINEDANGER) {
            onClick { leave() }
        }
    }

    private fun connect() {
        AppScope.launch {
            val url = response.serverUrl
            val token = response.token
            if (url == null || token == null) {
                statusLine.content = tr("Die Teilnahme ist gerade nicht möglich. Bitte versuchen Sie es erneut.")
                return@launch
            }
            statusLine.content = tr("Verbindung wird aufgebaut …")
            val failure = session.connect(serverUrl = url, token = token, turnServers = response.turnServers)
            statusLine.content = if (failure == null) "" else tr("Die Verbindung konnte nicht aufgebaut werden.")
            if (failure != null) onConnectionEnded()
        }
    }

    private fun toggleMicrophone() {
        AppScope.launch {
            val failure = session.setMicrophone(!micOn)
            if (failure != null) {
                statusLine.content = tr("Das Mikrofon konnte nicht eingeschaltet werden.")
            } else {
                micOn = !micOn
                syncButtons()
            }
        }
    }

    private fun toggleCamera() {
        AppScope.launch {
            val failure = session.setCamera(!cameraOn)
            if (failure != null) {
                statusLine.content = tr("Die Kamera konnte nicht eingeschaltet werden.")
            } else {
                cameraOn = !cameraOn
                syncButtons()
            }
        }
    }

    private fun syncButtons() {
        micButton.text = if (micOn) tr("Mikrofon ausschalten") else tr("Mikrofon einschalten")
        cameraButton.text = if (cameraOn) tr("Kamera ausschalten") else tr("Kamera einschalten")
    }

    private fun leave() {
        if (left) return
        left = true
        AppScope.launch {
            runCatching { session.disconnect() }
            onLeft()
        }
    }

    private fun onConnectionEnded() {
        if (left) return
        left = true
        statusLine.content = tr("Die Verbindung wurde beendet.")
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
