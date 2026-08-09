package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.rpc.IConferenceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.time.Clock

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- LiveKit-backed room list, plain create-room form,
 * and the real, working in-call view: a responsive CSS-grid video tile grid (name badge + inferred
 * mic/camera state per tile, a dedicated full-width stage for a `screen_share`-sourced track), a
 * persistent control bar (Mikrofon/Kamera/Bildschirm teilen/Chat/Verlassen), a collapsible chat side
 * panel, a live participant roster, and moderator-only "Für alle beenden"/"Entfernen" actions. See
 * [IConferenceService] class KDoc for the full role/gating model this screen surfaces, and
 * `network.lapis.cloud.client.livekit.LiveKitRoomSession` for the LiveKit interop this screen drives.
 *
 * **Still open from the Wave 1 design review** (root `CLAUDE.md` "UI/UX-Design-Team", decisions
 * D1-D11) -- this step builds out the IN-CALL view, not the lobby/pre-flight: D1's single-button
 * no-form creation flow ([renderLobby] below still asks for a title), D2's first-class camera/
 * microphone permission interstitial (device errors currently surface as a plain `guarded {}` toast,
 * see [enterCall]), D3's speaking-priority reflow for 13-25 participants (needs
 * `RoomEvent.ActiveSpeakersChanged`, not present in `LiveKitJs.kt`'s deliberately minimal external
 * surface), and D10's fully named `Idle -> RequestingMedia -> Connecting -> Connected -> Reconnecting
 * -> Disconnected/Ended` state machine (this step only distinguishes "not yet connected" and "ended",
 * see [enterCall]'s `guarded { session.connect(...) }` call -- `Reconnecting` has no event source yet,
 * since `RoomEvent.Reconnecting`/`.Reconnected` are not wired into `LiveKitRoomSession`'s callback
 * constructor). Each remains open work for a later step of this wave.
 *
 * **Per-tile mic/camera state is an honest approximation, not an exact LiveKit mute signal**: this
 * step infers "muted"/"camera off" purely from whether a subscribed audio/video track currently
 * exists for that identity ([ConferenceTileEntry.hasMic]/[ConferenceTileEntry.hasCamera], updated
 * from [LiveKitRoomSession]'s `onRemoteTrack`/`onRemoteTrackGone` callbacks) -- LiveKit's own
 * `TrackMuted`/`TrackUnmuted` events (which fire without unpublishing) are not wired into this
 * screen's [LiveKitRoomSession] callback surface. A participant who published-then-muted their mic
 * (rather than unpublishing) will still show as "Mikro an" here until a later step adds those events.
 * The LOCAL tile is exact, not an approximation -- its mic/camera badges are driven directly from the
 * `micEnabled`/`cameraEnabled` toggle state this screen itself owns.
 *
 * **Route/nav posture**: [Routes.CONFERENCE] uses `requireAuth`, not `requireRole` -- every method a
 * plain member needs to *reach* this screen at all ([IConferenceService.getAvailability]/
 * [IConferenceService.listActiveRooms]/[IConferenceService.createRoom]/[IConferenceService.joinRoom])
 * only calls `resolveCurrentMember(call)` + `requireActiveMembership` server-side, no `requireRole` --
 * same posture as [Routes.LTR_LEDGER]/[Routes.CROWDFUNDING]/[Routes.AUCTION]/[Routes.POLITICIANS], NOT
 * the Accounting UI wave's route-level `requireRole`. The narrower moderator-or-BOARD/ADMIN tier
 * ([IConferenceService.endRoom]/[IConferenceService.removeParticipant]) is gated inside this screen as
 * `canModerate`, exactly like those routes' own in-screen `canTreasury`/`canBoard`/`canAdmin` splits --
 * see [Routes.CONFERENCE] KDoc. This is a UX nicety on top of the server's real authority (the server
 * re-checks both calls independently) -- the same posture `DocumentsScreen.canManage` already
 * documents for its own domain.
 *
 * **The `getAvailability` gate**: unlike [IAuctionService.listAuctions] (which signals "disabled" via
 * a thrown [ConflictException] this screen would need to special-case, see `AuctionScreen.kt`'s own
 * file KDoc), [IConferenceService.getAvailability] never throws -- it is the ONE call this screen
 * makes outside a room, and an `enabled == false` result renders an explicit German notice panel in
 * place of the whole lobby ([renderDisabledPanel]), never a toast and never a failing call.
 *
 * **Chat's trust boundary is enforced entirely by [LiveKitRoomSession]**, not here -- by the time
 * [renderConferenceScreen] receives an inbound chat message via its `onChat` callback, the sender
 * identity already came from LiveKit's own SDK-verified `RemoteParticipant.identity`/`.name`, never
 * from the untrusted payload (see [ConferenceChatMessage] KDoc and `LiveKitRoomSession` KDoc "Chat
 * trust boundary"). Messages are rendered via KVision's default escaped `content` (never `rich =
 * true`), same posture every other screen in this app already takes.
 *
 * **Cleanup**: [Room.addAfterDestroyHook]/`window.addEventListener("beforeunload", ...)` are the ONLY
 * two hooks this codebase has for "the user is leaving this screen/tab" -- `Routing.kt`'s `show()`
 * just calls `pageContainer.removeAll()`, with no unmount callback of its own (see that file's KDoc).
 * Without one of these, the camera/microphone stay active after navigating away. The `beforeunload`
 * listener is explicitly removed again inside the destroy hook so repeated visits to this screen in
 * one page session don't accumulate stale listeners.
 */
fun renderConferenceScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 960.px
            marginTop = 24.px
        }
    root.h1("Videokonferenz")
    val statusLine = root.div("Wird geladen …") { addCssClasses("text-muted small") }
    val lobbyPanel = root.vPanel(spacing = 10)
    val callPanel = root.vPanel(spacing = 10)
    callPanel.hide()

    var activeSession: LiveKitRoomSession? = null
    val setActiveSession: (LiveKitRoomSession?) -> Unit = { activeSession = it }

    val beforeUnloadListener: (Event) -> Unit = {
        AppScope.launch { runCatching { activeSession?.disconnect() } }
    }
    window.addEventListener("beforeunload", beforeUnloadListener)
    root.addAfterDestroyHook {
        window.removeEventListener("beforeunload", beforeUnloadListener)
        AppScope.launch { runCatching { activeSession?.disconnect() } }
    }

    AppScope.launch {
        val availability =
            guarded { rpcService<IConferenceService>().getAvailability() }
                ?: run {
                    statusLine.content = "Videokonferenzen konnten nicht geladen werden."
                    return@launch
                }
        statusLine.hide()
        if (!availability.enabled) {
            renderDisabledPanel(lobbyPanel)
            return@launch
        }
        renderLobby(lobbyPanel, callPanel, setActiveSession)
    }
}

/**
 * Jobs' design-review verdict, D-item "Handle the getAvailability enabled=false case": an explicit
 * German notice panel, not a failing call and not a bare muted text line -- see file KDoc "The
 * `getAvailability` gate". Text matches the wave plan's own literal copy.
 */
private fun renderDisabledPanel(panel: SimplePanel) {
    panel.removeAll()
    panel.show()
    val alert = panel.div { addCssClasses("alert alert-secondary") }
    alert.div("Videokonferenz ist auf diesem Server nicht konfiguriert.") { addCssClass("fw-bold") }
    alert.div("Bitte wenden Sie sich an Ihre Administration, falls Sie diese Funktion benötigen.") {
        addCssClasses("small text-muted mb-0")
    }
}

// ================================================================================================
// Lobby: create-room form + active-room list
// ================================================================================================

private fun renderLobby(
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
) {
    lobbyPanel.removeAll()
    lobbyPanel.show()

    lobbyPanel.h2("Neue Besprechung starten")
    val createPanel = lobbyPanel.vPanel(spacing = 6)
    val titleInput = createPanel.text(label = "Titel (optional)")
    val createButton = createPanel.button("Besprechung erstellen", style = ButtonStyle.PRIMARY)

    lobbyPanel.h2("Aktive Besprechungen")
    val refreshRow = lobbyPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val refreshButton = refreshRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val roomsPanel = lobbyPanel.vPanel(spacing = 8)

    fun loadRooms() {
        roomsPanel.removeAll()
        roomsPanel.div("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val rooms = guarded { rpcService<IConferenceService>().listActiveRooms() } ?: return@launch
            roomsPanel.removeAll()
            if (rooms.isEmpty()) {
                roomsPanel.div("Derzeit keine aktive Besprechung.") { addCssClasses("text-muted small") }
            } else {
                rooms.forEach { room ->
                    renderRoomCard(roomsPanel, room, lobbyPanel, callPanel, setActiveSession) { loadRooms() }
                }
            }
        }
    }

    refreshButton.onClick { loadRooms() }

    createButton.onClick {
        val title =
            titleInput.value
                .orEmpty()
                .trim()
                .ifBlank { "Besprechung" }
        createButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IConferenceService>().createRoom(ConferenceRoomInput(title = title)) }
            createButton.disabled = false
            if (result != null) {
                notifySuccess("Besprechung \"${result.title}\" erstellt.")
                titleInput.value = null
                loadRooms()
            }
        }
    }

    loadRooms()
}

private fun renderRoomCard(
    panel: SimplePanel,
    room: ConferenceRoomDto,
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
) {
    val card = panel.hPanel(spacing = 8) { addCssClasses("border rounded p-2 align-items-center flex-wrap") }
    val infoCell = card.vPanel(spacing = 2) { addCssClasses("flex-grow-1") }
    infoCell.div(room.title) { addCssClasses("fw-bold") }
    infoCell.div("Erstellt von ${room.createdByDisplayName} · ${room.liveParticipantCount} Teilnehmende") {
        addCssClasses("text-muted small")
    }
    if (room.myRole == ConferenceRole.MODERATOR) {
        infoCell.statusBadge("Sie sind Moderator", "primary")
    }
    val joinButton = card.button("Beitreten", style = ButtonStyle.PRIMARY)
    joinButton.onClick {
        joinButton.disabled = true
        AppScope.launch {
            val token = guarded { rpcService<IConferenceService>().joinRoom(room.id) }
            joinButton.disabled = false
            if (token != null) {
                enterCall(room, token, lobbyPanel, callPanel, setActiveSession, onReturnedToLobby)
            }
        }
    }
}

// ================================================================================================
// In-call view: video grid + control bar + chat + roster + moderator actions
// ================================================================================================

/** One video tile's raw-DOM structure -- see [enterCall] "Raw DOM for the tile grid" for why this
 * is plain `HTMLElement` manipulation rather than KVision widgets. */
private class ConferenceTileEntry(
    val identity: String,
    var displayName: String,
    val isLocal: Boolean,
    val element: HTMLElement,
    val mediaSlot: HTMLElement,
    val nameBadge: HTMLElement,
    val micBadge: HTMLElement,
    var hasCamera: Boolean = false,
    var hasMic: Boolean = false,
)

private fun enterCall(
    room: ConferenceRoomDto,
    joinToken: ConferenceJoinTokenDto,
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
) {
    lobbyPanel.hide()
    callPanel.removeAll()
    callPanel.show()

    val localMemberId = AppState.session?.memberId
    // D-item "moderator-only actions": a UX nicety over the server's own re-checked authority (see
    // file KDoc "Route/nav posture") -- compares the CALLER's own member id to the room's creator,
    // OR a global BOARD/ADMIN escalation, exactly matching `IConferenceService.endRoom`/
    // `.removeParticipant`'s server-side gate (see [IConferenceService] KDoc "Two-tier role model").
    val canModerate =
        conferenceIsModerator(
            localMemberId = localMemberId,
            creatorMemberId = room.createdByMemberId,
            isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN),
        )

    // Raw DOM for the tile grid + screen-share stage: KVision renders through snabbdom, which
    // discards `<video>`/`<audio>` elements appended manually into a widget it later re-renders (see
    // this file's original plan). The fix, used throughout this function: grab each KVision `Div`
    // container's real element ONCE via `addAfterInsertHook`, then manage every child of THAT element
    // with plain `document.createElement`/`appendChild`/`removeChild` -- never call `removeAll()` or
    // re-render on `gridElement`/`stageElement` themselves.
    val tiles = LinkedHashMap<String, ConferenceTileEntry>()
    var gridElement: HTMLElement? = null
    var stageElement: HTMLElement? = null
    var activeScreenShare: Pair<String, Track>? = null
    var leftCall = false
    var micEnabled = true
    var cameraEnabled = true
    var screenShareEnabled = false
    var chatOpen = false
    var unreadChatCount = 0

    callPanel.h2(room.title)
    callPanel.div(
        if (canModerate) "Sie sind Moderator dieser Besprechung." else "Sie nehmen als Teilnehmer teil.",
    ) { addCssClasses("text-muted small") }

    // --- Control bar (persistent, D5: never hover-only) -----------------------------------------
    val controlsRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val micButton = controlsRow.button("Mikrofon aus", style = ButtonStyle.OUTLINESECONDARY)
    val cameraButton = controlsRow.button("Kamera aus", style = ButtonStyle.OUTLINESECONDARY)
    val screenShareButton = controlsRow.button("Bildschirm teilen", style = ButtonStyle.OUTLINESECONDARY)
    val chatToggleButton = controlsRow.button("Chat", style = ButtonStyle.OUTLINESECONDARY)
    val leaveButton = controlsRow.button("Verlassen", style = ButtonStyle.SECONDARY)
    leaveButton.addCssClass("ms-2")

    // D5/D6: "end for everyone" gets its own, spatially separate row -- never adjacent to "Verlassen"
    // (Tesler: near-identical destructive actions placed next to each other is a classic slip-inducing
    // layout). Not rendered at all for a plain participant, same "don't tease an action the server
    // will reject" posture `AuctionScreen.kt`'s own ADMIN-only Verwaltung panel documents.
    val endButton =
        if (canModerate) {
            val moderatorRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            moderatorRow.div("Moderator:") { addCssClasses("text-muted small") }
            moderatorRow.button("Für alle beenden", style = ButtonStyle.OUTLINEDANGER)
        } else {
            null
        }

    // --- Screen-share stage (hidden until a "screen_share"-sourced track subscribes) --------------
    val stageDiv =
        callPanel.div {
            addCssClasses("border rounded p-2 text-center")
            display = io.kvision.core.Display.NONE
        }
    stageDiv.addAfterInsertHook { vnode -> stageElement = vnode.elm as? HTMLElement }

    // --- Video tile grid (responsive CSS grid, D3) -------------------------------------------------
    val gridDiv =
        callPanel.div {
            addCssClasses("border rounded p-2")
            minHeight = 220.px
            maxHeight = 480.px
            overflow = Overflow.AUTO
        }
    gridDiv.addAfterInsertHook { vnode ->
        gridElement =
            (vnode.elm as? HTMLElement)?.also {
                it.style.cssText +=
                    "display:grid;grid-template-columns:repeat(auto-fit, minmax(200px, 1fr));gap:8px;"
            }
    }

    // --- Participant roster (live, driven by the same RoomEvent stream as the tiles) --------------
    callPanel.h2("Teilnehmende") { addCssClasses("h6 mt-2") }
    val rosterList = callPanel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }

    // --- Collapsible chat side panel (D7: off by default) ------------------------------------------
    val chatPanel = callPanel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    chatPanel.hide()
    chatPanel.h2("Chat") { addCssClass("h6") }
    val chatLog =
        chatPanel.div {
            addCssClasses("small")
            height = 160.px
            overflow = Overflow.AUTO
        }
    val chatRow = chatPanel.hPanel(spacing = 6)
    val chatInput = chatRow.text(label = "Nachricht") { addCssClasses("flex-grow-1") }
    val chatSendButton = chatRow.button("Senden", style = ButtonStyle.OUTLINEPRIMARY)

    fun updateChatToggleLabel() {
        chatToggleButton.text = if (unreadChatCount > 0) "Chat ($unreadChatCount)" else "Chat"
    }

    fun refreshRoster() {
        rosterList.removeAll()
        if (tiles.isEmpty()) {
            rosterList.div("Noch niemand verbunden.") { addCssClasses("text-muted small") }
            return
        }
        tiles.values.sortedWith(compareBy({ !it.isLocal }, { it.displayName })).forEach { entry ->
            val row = rosterList.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
            row.div(entry.displayName + if (entry.isLocal) " (Sie)" else "") { addCssClasses("flex-grow-1 small") }
            if (entry.identity == room.createdByMemberId) {
                row.statusBadge("Moderator", "primary")
            }
            row.div(if (entry.hasMic) "Mikro an" else "Stumm") { addCssClasses("text-muted small") }
            if (conferenceCanRemove(entry.identity, localMemberId, room.createdByMemberId, canModerate)) {
                val removeButton = row.button("Entfernen", style = ButtonStyle.OUTLINEDANGER)
                removeButton.addCssClass("btn-sm")
                removeButton.onClick {
                    removeParticipantConfirmDialog(entry.displayName) {
                        removeButton.disabled = true
                        AppScope.launch {
                            guarded { rpcService<IConferenceService>().removeParticipant(room.id, entry.identity) }
                            removeButton.disabled = false
                        }
                    }
                }
            }
        }
    }

    fun setTileVideo(
        entry: ConferenceTileEntry,
        mediaElement: org.w3c.dom.HTMLMediaElement?,
    ) {
        clearElement(entry.mediaSlot)
        entry.hasCamera = mediaElement != null
        if (mediaElement != null) {
            mediaElement.style.cssText = "width:100%;height:100%;object-fit:cover;"
            entry.mediaSlot.appendChild(mediaElement)
        } else {
            // D4: camera off shows an avatar/initials placeholder, never a black rectangle -- this
            // distinction matters for a first-time user's trust in the tool (design review D4).
            entry.mediaSlot.textContent = conferenceInitials(entry.displayName)
        }
    }

    fun setTileMic(
        entry: ConferenceTileEntry,
        hasMic: Boolean,
    ) {
        entry.hasMic = hasMic
        entry.micBadge.style.display = if (hasMic) "none" else "block"
        refreshRoster()
    }

    fun buildTile(
        identity: String,
        displayName: String,
        isLocal: Boolean,
    ): ConferenceTileEntry {
        val tile = document.createElement("div") as HTMLElement
        tile.style.cssText =
            "position:relative;background:#111;border:1px solid #444;border-radius:6px;" +
            "overflow:hidden;min-height:150px;display:flex;align-items:center;justify-content:center;"

        val mediaSlot = document.createElement("div") as HTMLElement
        mediaSlot.style.cssText =
            "width:100%;height:100%;display:flex;align-items:center;justify-content:center;" +
            "color:#eee;font-size:28px;font-weight:600;"
        mediaSlot.textContent = conferenceInitials(displayName)
        tile.appendChild(mediaSlot)

        val nameBadge = document.createElement("div") as HTMLElement
        nameBadge.style.cssText =
            "position:absolute;left:6px;bottom:6px;background:rgba(0,0,0,0.55);color:#fff;" +
            "font-size:12px;padding:2px 6px;border-radius:3px;max-width:85%;overflow:hidden;" +
            "text-overflow:ellipsis;white-space:nowrap;"
        tile.appendChild(nameBadge)

        val micBadge = document.createElement("div") as HTMLElement
        micBadge.style.cssText =
            "position:absolute;right:6px;top:6px;background:rgba(0,0,0,0.55);color:#fff;" +
            "font-size:11px;padding:2px 5px;border-radius:3px;display:none;"
        micBadge.textContent = "Stumm"
        tile.appendChild(micBadge)

        gridElement?.appendChild(tile)
        return ConferenceTileEntry(identity, displayName, isLocal, tile, mediaSlot, nameBadge, micBadge)
    }

    // Tile/roster label composition lives HERE, not baked into `displayName` at call sites -- every
    // caller passes the RAW `joinToken.displayName`/`RemoteParticipant.name`, never a pre-suffixed
    // string, so [ConferenceTileEntry.displayName] stays the single source of truth for
    // [conferenceInitials] and never accumulates a repeated "(Sie)" suffix (a real bug an earlier
    // version of this function had -- see the wave's own testing routine).
    fun tileLabel(entry: ConferenceTileEntry): String {
        val suffix = if (entry.isLocal) " (Sie)" else ""
        val moderatorSuffix = if (entry.identity == room.createdByMemberId) " · Moderator" else ""
        return entry.displayName + suffix + moderatorSuffix
    }

    fun ensureTile(
        identity: String,
        displayName: String,
        isLocal: Boolean = false,
    ): ConferenceTileEntry {
        tiles[identity]?.let { existing ->
            existing.displayName = displayName
            existing.nameBadge.textContent = tileLabel(existing)
            return existing
        }
        val entry = buildTile(identity, displayName, isLocal)
        entry.nameBadge.textContent = tileLabel(entry)
        tiles[identity] = entry
        refreshRoster()
        return entry
    }

    fun removeTile(identity: String) {
        val entry = tiles.remove(identity) ?: return
        entry.element.parentNode?.removeChild(entry.element)
        refreshRoster()
    }

    fun showScreenShareStage(
        identity: String,
        displayName: String,
        track: Track,
    ) {
        val stage = stageElement ?: return
        // Wave 1 keeps only the most-recently-started share on stage -- multiple simultaneous shares
        // stacking/tabbing is out of scope (design review D4 "follow the standard convention, don't
        // invent a novel layout").
        clearElement(stage)
        val mediaElement = track.attach()
        mediaElement.style.cssText = "width:100%;max-height:60vh;border-radius:6px;background:#000;"
        stage.appendChild(mediaElement)
        val label = document.createElement("div") as HTMLElement
        label.style.cssText = "font-size:12px;color:#666;margin-top:4px;"
        label.textContent = "$displayName teilt den Bildschirm"
        stage.appendChild(label)
        stage.style.display = "block"
        activeScreenShare = identity to track
    }

    fun hideScreenShareStageIfCurrent(
        identity: String,
        track: Track,
    ) {
        val current = activeScreenShare ?: return
        if (current.first != identity) return
        track.detach().forEach { el -> el.parentNode?.removeChild(el) }
        stageElement?.let {
            clearElement(it)
            it.style.display = "none"
        }
        activeScreenShare = null
    }

    fun appendChatLine(
        sender: String,
        text: String,
        isOwn: Boolean,
    ) {
        chatLog.div("$sender: $text") {
            if (isOwn) addCssClasses("fw-bold text-end")
        }
        chatLog.getElement()?.let { el -> el.scrollTop = el.scrollHeight.toDouble() }
        if (!chatOpen && !isOwn) {
            unreadChatCount += 1
            updateChatToggleLabel()
        }
    }

    lateinit var session: LiveKitRoomSession
    session =
        LiveKitRoomSession(
            onRemoteTrack = { identity, displayName, track, publication ->
                when (conferenceTileKind(publication.source)) {
                    ConferenceTileKind.SCREEN_SHARE -> showScreenShareStage(identity, displayName, track)
                    ConferenceTileKind.CAMERA, ConferenceTileKind.OTHER -> {
                        val entry = ensureTile(identity, displayName)
                        val mediaElement = track.attach()
                        if (track.kind == "video") {
                            setTileVideo(entry, mediaElement)
                        } else {
                            mediaElement.style.cssText = "display:none;"
                            entry.element.appendChild(mediaElement)
                            setTileMic(entry, true)
                        }
                    }
                }
            },
            onRemoteTrackGone = { identity, track, publication ->
                when (conferenceTileKind(publication.source)) {
                    ConferenceTileKind.SCREEN_SHARE -> hideScreenShareStageIfCurrent(identity, track)
                    ConferenceTileKind.CAMERA, ConferenceTileKind.OTHER -> {
                        track.detach().forEach { el -> el.parentNode?.removeChild(el) }
                        val entry = tiles[identity]
                        if (entry != null) {
                            if (track.kind == "video") setTileVideo(entry, null)
                            if (track.kind == "audio") setTileMic(entry, false)
                        }
                    }
                }
            },
            onParticipantJoined = { identity, displayName -> ensureTile(identity, displayName) },
            onParticipantLeft = { identity -> removeTile(identity) },
            onLocalVideoTrack = { track ->
                val entry = ensureTile(joinToken.identity, joinToken.displayName, isLocal = true)
                setTileVideo(entry, track?.attach())
            },
            onChat = { message -> appendChatLine(message.senderDisplayName, message.text, isOwn = false) },
            onDisconnected = {
                if (!leftCall) {
                    leftCall = true
                    notifyInfo("Die Besprechung wurde beendet oder die Verbindung getrennt.")
                    returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby)
                }
            },
        )
    setActiveSession(session)

    // Seed the local tile immediately (D8: the initial roster must render instantaneously and
    // completely, not "populate" one-by-one after landing) so the caller sees their own tile even
    // before the camera/microphone requests below resolve.
    ensureTile(joinToken.identity, joinToken.displayName, isLocal = true)
    setTileMic(tiles.getValue(joinToken.identity), micEnabled)

    AppScope.launch {
        val connected = guarded { session.connect(joinToken.serverUrl, joinToken.token, joinToken.turnServers) }
        if (connected == null) {
            setActiveSession(null)
            returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby)
            return@launch
        }
        // Each device independently, each wrapped in its own `guarded {}` -- a missing/denied camera
        // or microphone must not abort the whole call (a member with no working device can still
        // join and watch/listen). The full non-technical D2 permission-preflight UI is explicitly
        // deferred to this wave's next, polish-focused step -- see file KDoc.
        //
        // Bug found and fixed during this wave's own live-browser verification (2026-08-09,
        // reproduced against a real getUserMedia rejection, not merely reasoned about): both
        // `micEnabled`/`cameraEnabled` above default to `true` and the buttons below are created
        // with that same "on" label BEFORE this block ever runs -- if the initial publish attempt
        // fails (denied permission, no device), that default stays wrong forever unless corrected
        // here. `guarded {}` returning `null` is exactly the "it failed" signal; only a non-null
        // result means the track is actually publishing.
        val micOk = guarded { session.setMicrophone(true) } != null
        val cameraOk = guarded { session.setCamera(true) } != null
        if (!micOk) {
            micEnabled = false
            setTileMic(tiles.getValue(joinToken.identity), micEnabled)
            micButton.text = "Mikrofon an"
        }
        if (!cameraOk) {
            cameraEnabled = false
            cameraButton.text = "Kamera an"
        }
    }

    micButton.onClick {
        micButton.disabled = true
        AppScope.launch {
            val desired = !micEnabled
            // Bug found and fixed during this wave's own live-browser verification (2026-08-09): the
            // previous version flipped `micEnabled`/the button label BEFORE awaiting `setMicrophone`
            // and never checked its result -- a permission-denied click made the button claim "on"
            // with nothing actually publishing, no error visible anywhere. `guarded {}` already shows
            // a toast on failure (see AppState.kt); this handler now additionally reverts to the
            // truthful state instead of pretending the click succeeded.
            val result = guarded { session.setMicrophone(desired) }
            micButton.disabled = false
            if (result != null) {
                micEnabled = desired
                setTileMic(tiles.getValue(joinToken.identity), micEnabled)
                micButton.text = if (micEnabled) "Mikrofon aus" else "Mikrofon an"
            }
        }
    }
    cameraButton.onClick {
        cameraButton.disabled = true
        AppScope.launch {
            val desired = !cameraEnabled
            // Same fix as micButton.onClick above -- see that handler's comment.
            val result = guarded { session.setCamera(desired) }
            cameraButton.disabled = false
            if (result != null) {
                cameraEnabled = desired
                cameraButton.text = if (cameraEnabled) "Kamera aus" else "Kamera an"
            }
        }
    }
    screenShareButton.onClick {
        screenShareButton.disabled = true
        AppScope.launch {
            val desired = !screenShareEnabled
            // Same fix as micButton.onClick above -- see that handler's comment.
            val result = guarded { session.setScreenShare(desired) }
            screenShareButton.disabled = false
            if (result != null) {
                screenShareEnabled = desired
                screenShareButton.text = if (screenShareEnabled) "Bildschirm-Teilen beenden" else "Bildschirm teilen"
            }
        }
    }
    chatToggleButton.onClick {
        chatOpen = !chatOpen
        if (chatOpen) {
            chatPanel.show()
            unreadChatCount = 0
            updateChatToggleLabel()
        } else {
            chatPanel.hide()
        }
    }

    fun sendChatMessage() {
        val text = chatInput.value.orEmpty().trim()
        if (text.isBlank()) return
        val ownMessage =
            ConferenceChatMessage(
                senderMemberId = joinToken.identity,
                senderDisplayName = joinToken.displayName,
                text = text,
                sentAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        chatInput.value = null
        AppScope.launch {
            // LiveKit never echoes a locally published data message back to its own sender (see
            // LiveKitRoomSession KDoc "Chat trust boundary") -- this screen renders its own outgoing
            // line immediately, rather than waiting for onChat.
            guarded { session.sendChat(ownMessage) }
            appendChatLine("Sie", text, isOwn = true)
        }
    }
    chatSendButton.onClick { sendChatMessage() }
    // D7 "Enter-to-send": KVision's own event-binding surface is not used here -- the chat row's
    // real `<input>` element is grabbed once via `addAfterInsertHook` (same raw-DOM posture the
    // video grid/stage above already use) and wired with a plain `keydown` listener, mirroring this
    // file's own `beforeunload` listener pattern.
    chatRow.addAfterInsertHook { vnode ->
        val rowElement = vnode.elm as? HTMLElement
        val inputElement = rowElement?.querySelector("input") as? HTMLInputElement
        inputElement?.addEventListener("keydown", { event ->
            val keyEvent = event as? KeyboardEvent
            if (keyEvent?.key == "Enter") {
                keyEvent.preventDefault()
                sendChatMessage()
            }
        })
    }

    leaveButton.onClick {
        leaveButton.disabled = true
        leftCall = true
        AppScope.launch {
            guarded { session.disconnect() }
            guarded { rpcService<IConferenceService>().leaveRoom(room.id) }
            setActiveSession(null)
            returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby)
        }
    }

    endButton?.onClick {
        endRoomConfirmDialog(room.title) {
            endButton.disabled = true
            leftCall = true
            AppScope.launch {
                guarded { session.disconnect() }
                val result = guarded { rpcService<IConferenceService>().endRoom(room.id) }
                if (result != null) {
                    notifySuccess("Besprechung \"${result.title}\" wurde für alle beendet.")
                }
                setActiveSession(null)
                returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby)
            }
        }
    }
}

private fun clearElement(element: HTMLElement) {
    while (element.firstChild != null) {
        element.removeChild(element.firstChild!!)
    }
}

private fun returnToLobby(
    callPanel: SimplePanel,
    lobbyPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
) {
    setActiveSession(null)
    callPanel.removeAll()
    callPanel.hide()
    lobbyPanel.show()
    onReturnedToLobby()
}

/** Rule 3 (irreversible action -> bespoke confirm modal): matches `AuctionScreen.kt`'s own
 * `auctionDisableConfirmDialog` tier -- danger-framed, states the concrete consequence in plain
 * language (design review D6's "Diese Besprechung wird für alle Teilnehmenden beendet. Fortfahren?"
 * copy). */
private fun endRoomConfirmDialog(
    roomTitle: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Besprechung für alle beenden")
    modal.div("Diese Besprechung wird für alle Teilnehmenden beendet. Fortfahren?") { addCssClasses("fw-bold text-danger") }
    modal.div("\"$roomTitle\" wird sofort geschlossen -- alle Verbindungen werden getrennt.") { addCssClasses("text-muted small") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Für alle beenden", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** Design review D9: removing a participant is an action taken against another person without their
 * consent -- not the heavy end-room dialog, but not a silent one-click either. Lighter framing than
 * [endRoomConfirmDialog] (no danger-red body text), still a genuine confirm step. */
private fun removeParticipantConfirmDialog(
    displayName: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Teilnehmer entfernen")
    modal.div("\"$displayName\" aus der Besprechung entfernen?") { addCssClass("fw-bold") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Entfernen", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Pure, DOM-independent logic -- see [ConferenceScreenTest] for coverage (no rendering harness
// exists in this module, same posture every other screen's `*ScreenTest.kt` documents).
// ================================================================================================

/** Mirrors `LiveKitJs.kt`'s file KDoc: `"screen_share"` is discriminated by string comparison,
 * deliberately no dedicated enum type on the JS-interop side -- this enum exists purely on the
 * screen's OWN side, to decide grid-tile vs. full-width-stage placement. */
internal enum class ConferenceTileKind { CAMERA, SCREEN_SHARE, OTHER }

internal fun conferenceTileKind(source: String): ConferenceTileKind =
    when (source) {
        "screen_share" -> ConferenceTileKind.SCREEN_SHARE
        "camera" -> ConferenceTileKind.CAMERA
        else -> ConferenceTileKind.OTHER
    }

/** Pure client-side mirror of `IConferenceService.endRoom`/`.removeParticipant`'s server-side
 * "creator OR global BOARD/ADMIN" gate (see that interface's class KDoc "Two-tier role model") --
 * a UX nicety, not the real authority (the server re-checks independently). `localMemberId == null`
 * (session not yet loaded) never grants moderator standing. */
internal fun conferenceIsModerator(
    localMemberId: String?,
    creatorMemberId: String,
    isBoardOrAdmin: Boolean,
): Boolean = localMemberId != null && (localMemberId == creatorMemberId || isBoardOrAdmin)

/** A moderator can remove anyone in the roster except the room's own creator (mirrors
 * `IConferenceService.removeParticipant`'s own server-side refusal, [IConferenceService] KDoc) and
 * except themselves (no self-removal button -- use "Verlassen" instead). */
internal fun conferenceCanRemove(
    targetMemberId: String,
    localMemberId: String?,
    creatorMemberId: String,
    canModerate: Boolean,
): Boolean =
    canModerate &&
        targetMemberId != creatorMemberId &&
        targetMemberId != localMemberId

/** Two-letter initials for the D4 camera-off avatar placeholder -- "Anna Muster" -> "AM", a single
 * name "Anna" -> "AN", blank/whitespace-only -> "?". */
internal fun conferenceInitials(displayName: String): String {
    val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
