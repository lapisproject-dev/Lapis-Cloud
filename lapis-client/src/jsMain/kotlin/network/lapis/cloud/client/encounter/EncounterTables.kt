package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.shared.domain.EncounterTableTokenDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

// ── The model: what one render of the tables shows (pure, DOM-free) ───────────────────────────────────────────────────

/** One seat of a table. [initials] `null` = free. Initials only -- no name is ever part of the model. */
internal class TableSeatSlot(
    val initials: String?,
    val own: Boolean = false,
    /** Only ever true at the viewer's OWN table (LiveKit reports speakers of the room one is connected to). */
    val speaking: Boolean = false,
    /** The viewer just chose this seat and the server has not answered yet. */
    val pending: Boolean = false,
)

internal class TableCardModel(
    /** 0-based; shown as table number + 1. */
    val table: Int,
    val seats: List<TableSeatSlot>,
    val quieted: Boolean,
    val own: Boolean,
    /** A line about the viewer's OWN microphone at this table ("Mikrofon an"), `null` at every other table. */
    val micStatus: String? = null,
) {
    val freeSeats: Int get() = seats.count { it.initials == null }
}

internal class TablesModel(
    val cards: List<TableCardModel>,
    /** `false` for somebody who cannot sit (an office holder, or a second request in flight): free seats are shown, but offer no choice. */
    val choosable: Boolean,
    val canModerate: Boolean,
)

/** The table list alternative's view (side panel "Anwesende"): the viewer's own place and the tables that still have a free seat. */
internal class EncounterTableListState(
    val ownTable: Int?,
    /** table (0-based) -> number of free seats, only tables with at least one free seat. */
    val tablesWithFreeSeats: List<Pair<Int, Int>>,
)

/** "Tisch 3" -- the number is 1-based for people. */
internal fun encounterTableTitle(table: Int): String = gettext("Tisch %1", table + 1)

// ── The view ───────────────────────────────────────────────────────────────────────────────────────────────────────────

private class TableSeatCell(
    val root: Tag,
    val initials: Span,
    val speaking: Span,
    val self: Span,
) {
    var slot: TableSeatSlot = TableSeatSlot(initials = null)
    var choosable: Boolean = true
}

private class TableCardCell(
    val root: Div,
    val title: Span,
    val quietNote: Span,
    val micStatus: Span,
    val seatsRow: Div,
    val cells: List<TableSeatCell>,
    val leave: io.kvision.html.Button,
    val quiet: io.kvision.html.Button,
    val lift: io.kvision.html.Button,
)

/**
 * V1.9.80 (stage 2b) -- the tables as widgets, between the pulpit and the pews: one card per table (`role="group"`, named "Tisch 3, 2 von 6
 * Plätzen frei"), per seat one `<button type="button">` of at least 44 x 44 px. A free seat sits the viewer down (click, Enter, Space), a
 * taken one is `aria-disabled`; the cards are updated in place (never rebuilt), so the keyboard focus survives every refresh.
 *
 * - **No name**: a seat shows initials only; its accessible name is table, seat, state and the initials spelled letter by letter, there
 *   is no `title`. The own seat says "Ihr Platz".
 * - **Keyboard**: roving tabindex per table (own seat, else first free, else the first), Arrow left/right and Home/End along the seats of
 *   one table. No focus trap.
 * - **Not only colour**: a quieted table says it in words and with a symbol; a speaking seat carries the word "spricht".
 * - Moderators get "Tisch beruhigen" / "Beruhigung aufheben" on every card; the person at the table gets "Tisch verlassen".
 */
internal class EncounterTablesView(
    parent: Container,
) {
    val root: Div = parent.div(className = "lapis-encounter-tables")
    private val heading: Tag = root.tag(TAG.H2, content = tr("Tische"), className = "h6 lapis-encounter-tables-heading")
    private val hint: Tag =
        root.tag(
            TAG.P,
            content = tr("Setzen Sie sich an einen Tisch, um dort mit wenigen Personen zu sprechen. Ihr Mikrofon ist beim Hinsetzen aus."),
            className = "lapis-encounter-tables-hint small text-muted",
        )
    private val grid: Div = root.div(className = "lapis-encounter-tables-grid")
    private val cards = mutableListOf<TableCardCell>()
    private var activeSeat = HashMap<Int, Int>()

    /** Called with (table, seat) when a FREE seat is chosen. */
    var onChoose: (table: Int, seat: Int) -> Unit = { _, _ -> }
    var onLeave: () -> Unit = {}
    var onQuiet: (table: Int, quiet: Boolean) -> Unit = { _, _ -> }

    init {
        heading.setAttribute("id", HEADING_ID)
        root.hide()
    }

    val tableCount: Int get() = cards.count { it.root.visible }

    /** Draws [model]: builds missing cards, hides cards beyond it, updates every seat in place and keeps one tab stop per table. */
    fun render(model: TablesModel) {
        if (model.cards.isEmpty()) {
            root.hide()
            return
        }
        root.show()
        hint.visible = model.choosable
        while (cards.size < model.cards.size) cards += buildCard(index = cards.size, seats = model.cards[cards.size].seats.size)
        cards.forEachIndexed { index, cell -> if (index < model.cards.size) cell.root.show() else cell.root.hide() }
        model.cards.forEach { card -> apply(cards[card.table], card, model) }
    }

    /** Moves the keyboard focus to a seat once it is in the document (KVision patches the DOM asynchronously). */
    fun focusSeat(
        table: Int,
        seat: Int,
    ) {
        activeSeat[table] = seat
        refreshTabStops(table)
        var attempts = 0

        fun attempt() {
            val element =
                cards
                    .getOrNull(table)
                    ?.cells
                    ?.getOrNull(seat)
                    ?.root
                    ?.getElement() as? HTMLElement
            if (element != null) {
                element.focus()
            } else if (attempts++ < FOCUS_RETRIES) {
                window.setTimeout({ attempt() }, FOCUS_RETRY_MS)
            }
        }
        attempt()
    }

    private fun buildCard(
        index: Int,
        seats: Int,
    ): TableCardCell {
        val card = grid.div(className = "lapis-encounter-table")
        card.setAttribute("role", "group")
        card.setAttribute("data-table", index.toString())
        val head = card.div(className = "lapis-encounter-table-head")
        val title = Span(className = "lapis-encounter-table-title")
        head.add(title)
        val quietNote = Span(className = "lapis-encounter-table-quiet")
        quietNote.icon("fas fa-volume-xmark")
        quietNote.add(Span(content = tr("Der Tisch ist beruhigt")))
        quietNote.hide()
        head.add(quietNote)
        val micStatus = Span(className = "lapis-encounter-table-mic small text-muted")
        head.add(micStatus)
        val row = card.div(className = "lapis-encounter-table-seats")
        row.onEvent { keydown = { event -> onKey(event) } }
        val cells =
            (0 until seats).map { seat ->
                val cell = row.tag(TAG.BUTTON, className = "lapis-encounter-table-seat lapis-encounter-table-seat--free")
                cell.setAttribute("type", "button")
                cell.setAttribute("data-table", index.toString())
                cell.setAttribute("data-table-seat", seat.toString())
                cell.setAttribute("tabindex", "-1")
                val initials = Span(className = "lapis-encounter-table-seat-initials")
                cell.add(initials)
                val speaking = Span(content = tr("spricht"), className = "lapis-encounter-table-seat-speaking")
                speaking.setAttribute("aria-hidden", "true")
                speaking.hide()
                cell.add(speaking)
                val self = Span(content = tr("Sie"), className = "lapis-encounter-table-seat-self")
                self.setAttribute("aria-hidden", "true")
                self.hide()
                cell.add(self)
                val created = TableSeatCell(root = cell, initials = initials, speaking = speaking, self = self)
                cell.onClick {
                    if (created.slot.initials == null && !created.slot.pending && created.choosable) onChoose(index, seat)
                }
                created
            }
        val actions = card.div(className = "lapis-encounter-table-actions")
        val leave = actions.actionButton(ActionIcon.LEAVE_TABLE, tr("Tisch verlassen"), ButtonStyle.OUTLINESECONDARY, small = true)
        leave.onClick { onLeave() }
        leave.hide()
        val quiet = actions.actionButton(ActionIcon.SILENCE, tr("Tisch beruhigen"), ButtonStyle.OUTLINESECONDARY, small = true)
        quiet.onClick { onQuiet(index, true) }
        quiet.hide()
        val lift = actions.actionButton(ActionIcon.LIFT_QUIET, tr("Beruhigung aufheben"), ButtonStyle.OUTLINESECONDARY, small = true)
        lift.onClick { onQuiet(index, false) }
        lift.hide()
        return TableCardCell(card, title, quietNote, micStatus, row, cells, leave, quiet, lift)
    }

    private fun apply(
        cell: TableCardCell,
        card: TableCardModel,
        model: TablesModel,
    ) {
        val number = card.table + 1
        val free = card.freeSeats
        val titleText = gettext("Tisch %1 · %2 von %3 Plätzen frei", number, free, card.seats.size)
        cell.title.content = titleText
        cell.root.setAttribute("aria-label", titleText + if (card.quieted) ", " + gettext("beruhigt") else "")
        if (card.quieted) cell.quietNote.show() else cell.quietNote.hide()
        // Empty = no line (`:empty` hides it in theme.css): show/hide of a widget that sits among text-bearing siblings patched the wrong node.
        untrustedContent(cell.micStatus, card.micStatus.orEmpty())
        card.seats.forEachIndexed { seat, slot ->
            val seatCell = cell.cells.getOrNull(seat) ?: return@forEachIndexed
            seatCell.slot = slot
            seatCell.choosable = model.choosable
            val isFree = slot.initials == null
            seatCell.root.setClass("lapis-encounter-table-seat--free", isFree)
            seatCell.root.setClass("lapis-encounter-table-seat--taken", !isFree)
            seatCell.root.setClass("lapis-encounter-table-seat--own", slot.own)
            seatCell.root.setClass("lapis-encounter-table-seat--pending", slot.pending)
            seatCell.root.setClass("is-speaking", slot.speaking)
            if (slot.pending) seatCell.root.setAttribute("aria-busy", "true") else seatCell.root.removeAttribute("aria-busy")
            if (isFree && !slot.pending && model.choosable) {
                seatCell.root.removeAttribute("aria-disabled")
            } else {
                seatCell.root.setAttribute("aria-disabled", "true")
            }
            untrustedContent(seatCell.initials, slot.initials.orEmpty())
            if (slot.speaking) seatCell.speaking.show() else seatCell.speaking.hide()
            if (slot.own) seatCell.self.show() else seatCell.self.hide()
            seatCell.root.setAttribute("aria-label", seatLabel(number, seat + 1, slot, model.choosable))
            seatCell.root.removeAttribute("title")
        }
        if (card.own) cell.leave.show() else cell.leave.hide()
        if (model.canModerate && !card.quieted) cell.quiet.show() else cell.quiet.hide()
        if (model.canModerate && card.quieted) cell.lift.show() else cell.lift.hide()
        activeSeat[card.table] = restingSeat(card)
        refreshTabStops(card.table)
    }

    private fun seatLabel(
        table: Int,
        position: Int,
        slot: TableSeatSlot,
        choosable: Boolean,
    ): String {
        val base =
            when {
                slot.own -> gettext("Tisch %1, Platz %2, Ihr Platz", table, position)
                slot.initials == null && choosable -> gettext("Tisch %1, Platz %2, frei, hier setzen", table, position)
                slot.initials == null -> gettext("Tisch %1, Platz %2, frei", table, position)
                else -> gettext("Tisch %1, Platz %2, besetzt, %3", table, position, slot.initials.toList().joinToString(" "))
            }
        return network.lapis.cloud.client
            .sanitizeUntrustedI18nText(base + if (slot.speaking) ", " + gettext("spricht") else "")
    }

    private fun restingSeat(card: TableCardModel): Int {
        val focusInside =
            cards
                .getOrNull(card.table)
                ?.seatsRow
                ?.getElement()
                ?.contains(document.activeElement) == true
        val current = activeSeat[card.table]
        if (focusInside && current != null && current in card.seats.indices) return current
        card.seats
            .indexOfFirst { it.own }
            .takeIf { it >= 0 }
            ?.let { return it }
        card.seats
            .indexOfFirst { it.initials == null }
            .takeIf { it >= 0 }
            ?.let { return it }
        return 0
    }

    private fun refreshTabStops(table: Int) {
        val cell = cards.getOrNull(table) ?: return
        val active = activeSeat[table] ?: 0
        cell.cells.forEachIndexed { seat, seatCell ->
            val stop = seat == active
            seatCell.root.setAttribute("tabindex", if (stop) "0" else "-1")
            seatCell.root.setAttribute("data-tab-set", if (stop) "1" else "0")
        }
    }

    private fun onKey(event: KeyboardEvent) {
        val target = (event.target as? HTMLElement)?.closest("[data-table-seat]") as? HTMLElement ?: return
        val table = target.getAttribute("data-table")?.toIntOrNull() ?: return
        val seat = target.getAttribute("data-table-seat")?.toIntOrNull() ?: return
        val last = (cards.getOrNull(table)?.cells?.size ?: return) - 1
        val next =
            when (event.key) {
                "ArrowLeft" -> if (seat > 0) seat - 1 else null
                "ArrowRight" -> if (seat < last) seat + 1 else null
                "Home" -> 0
                "End" -> last
                else -> return
            }
        event.preventDefault()
        if (next != null && next != seat) focusSeat(table, next)
    }

    private fun Tag.setClass(
        name: String,
        on: Boolean,
    ) {
        if (on) addCssClass(name) else removeCssClass(name)
    }

    companion object {
        const val HEADING_ID = "lapis-encounter-tables-heading"
        private const val FOCUS_RETRIES = 30
        private const val FOCUS_RETRY_MS = 16
    }
}

// ── The controller: sitting down, the table's audio session, rotation ──────────────────────────────────────────────────

/** What the controller needs from the room. Every sentence passed to [announce] is a fixed text (never data of a person). */
internal interface EncounterTableHost {
    /** The picture changed (position, microphone, quiet, speakers). */
    fun tablesChanged()

    /** A fixed sentence for the polite live region of the viewer's OWN table events. */
    fun announce(sentence: String)

    /** The server's picture of the tables may be stale (a seat was lost or refused): reload the list soon. */
    fun requestRefresh()

    /** Tell the others to reload the list (content-free nudge). */
    fun nudge()

    /** The viewer sits at a table (`true`) or is back in the plenum: the pulpit's sound is turned down / up accordingly. */
    fun setAtTable(atTable: Boolean)

    fun addTableAudio(element: HTMLElement)

    fun removeTableAudio(element: HTMLElement)
}

/**
 * V1.9.80 -- the viewer's own table: the RPCs, the second LiveKit session and its life cycle. Memory only: nothing is stored, the position
 * is the SERVER's (this class mirrors what `joinTable` / `tableToken` answered).
 *
 * **Rotation.** Whenever somebody leaves the table, the server gives the table a NEW room and deletes the old one -- the old room's
 * connection ends. That end is not an error: [reconnect] asks `tableToken` for the new room and connects again (the microphone comes back
 * only if it was on and the table is not quieted); `null` means "you do not sit there any more" -- back in the plenum, all local audio stopped.
 * Every disconnect that the viewer causes is silent ([closing]); callbacks of a replaced session are ignored ([serial]).
 */
internal class EncounterTableController(
    private val spaceId: String,
    private val opener: EncounterTableSessionOpener,
    private val host: EncounterTableHost,
    private val reconnectDelaysMs: List<Long> = listOf(400, 1_500, 3_000, 6_000),
) {
    var position: Pair<Int, Int>? = null
        private set
    var pending: Pair<Int, Int>? = null
        private set
    var micOn: Boolean = false
        private set
    var canPublish: Boolean = false
        private set
    var quieted: Boolean = false
        private set
    var connected: Boolean = false
        private set

    /** Counts what the viewer did (sit, leave); a roster that started under an older count must not be believed about the viewer's seat. */
    var generation: Int = 0
        private set

    private var session: EncounterTableSession? = null
    private var serial = 0
    private var closing = false
    private var busy = false
    private var speaking: Set<String> = emptySet()
    private val audio = mutableMapOf<Any, HTMLElement>()

    val seated: Boolean get() = position != null

    fun isSpeaking(identity: String): Boolean = identity in speaking

    private sealed interface JoinResult {
        class Done(
            val token: EncounterTableTokenDto,
        ) : JoinResult

        data object Taken : JoinResult

        data object Busy : JoinResult
    }

    /** Sits the viewer at [table]/[seat]. One request at a time. There is no optimistic picture: the seat shows as pending until answered. */
    suspend fun join(
        table: Int,
        seat: Int,
    ) {
        if (busy) return
        busy = true
        pending = table to seat
        host.tablesChanged()
        val outcome =
            guarded {
                try {
                    JoinResult.Done(rpcService<IEncounterSpaceService>().joinTable(spaceId, table, seat))
                } catch (e: ConflictException) {
                    JoinResult.Taken
                } catch (e: ServiceBusyException) {
                    JoinResult.Busy
                }
            }
        pending = null
        busy = false
        when (outcome) {
            is JoinResult.Done -> {
                generation++
                attach(token = outcome.token, restoreMic = false)
                host.nudge()
            }
            JoinResult.Taken -> {
                // "Conflict" is also what the server answers when the table rotated right after it had seated us (the token could not be
                // minted for the new room). Then the seat IS ours on the server: ask for the token instead of leaving a ghost seat behind.
                val recovered = fetchOwnToken()
                val recoveredPosition = recovered?.let { token -> token.table to token.tableSeat }
                if (recovered != null && recoveredPosition == (table to seat)) {
                    generation++
                    attach(token = recovered, restoreMic = false)
                    host.nudge()
                } else {
                    // The seat really is taken by somebody else: tell the viewer. Re-attach only when the viewer sits somewhere the local
                    // session does not cover; a matching session stays untouched (no audible break, the microphone stays as it is).
                    host.announce(gettext("Dieser Platz ist inzwischen besetzt. Bitte wählen Sie einen anderen."))
                    if (recovered != null && (session == null || position != recoveredPosition)) {
                        generation++
                        attach(token = recovered, restoreMic = false)
                        host.nudge()
                    }
                    host.requestRefresh()
                }
            }
            JoinResult.Busy -> host.announce(gettext("Bitte warten Sie einen Moment und versuchen Sie es erneut."))
            null -> host.requestRefresh()
        }
        host.tablesChanged()
    }

    /** Asks the server whether the viewer sits at a table and for the token of it. `null` = no (or the question failed). */
    private suspend fun fetchOwnToken(): EncounterTableTokenDto? =
        try {
            rpcService<IEncounterSpaceService>().tableToken(spaceId).token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    /** Gets up: the audio stops at once, then the server is told (it may take a moment when two requests are too close together). */
    suspend fun leave() {
        if (position == null && session == null) return
        generation++
        dropLocal()
        host.announce(gettext("Sie sind wieder im Plenum."))
        host.tablesChanged()
        tellServerLeft()
        host.nudge()
    }

    suspend fun toggleMicrophone(on: Boolean) {
        val current = session ?: return
        if (!canPublish) return
        if (on == micOn) return
        val failure = current.microphone(on)
        micOn = failure == null && on
        host.announce(
            when {
                failure != null -> gettext("Das Mikrofon konnte nicht eingeschaltet werden.")
                micOn -> gettext("Ihr Mikrofon ist an.")
                else -> gettext("Ihr Mikrofon ist aus.")
            },
        )
        host.tablesChanged()
    }

    /** The server's list says the viewer sits nowhere, although this class believes so (and nothing was done meanwhile): back to the plenum. */
    fun serverSaysPlenum() {
        if (position == null) return
        generation++
        dropLocal()
        host.announce(gettext("Sie wurden ins Plenum zurückgesetzt."))
        host.tablesChanged()
    }

    /** Ends everything without a sentence and without a request (the room is being left; the server drops the seat with the presence). */
    fun dispose() {
        dropLocal(notify = false)
    }

    private suspend fun attach(
        token: EncounterTableTokenDto,
        restoreMic: Boolean,
    ) {
        val wasQuiet = quieted
        val hadPosition = position != null
        silenceSession()
        position = token.table to token.tableSeat
        canPublish = token.canPublish
        quieted = !token.canPublish
        val mine = ++serial
        closing = false
        val callbacks =
            EncounterTableCallbacks(
                onAudioTrack = { _, track ->
                    if (mine == serial) {
                        track.detach().forEach { host.removeTableAudio(it) }
                        val element = track.attach().also { it.style.display = "none" }
                        audio[track] = element
                        host.addTableAudio(element)
                    }
                },
                onAudioTrackGone = { _, track ->
                    if (mine == serial) {
                        track.detach().forEach { host.removeTableAudio(it) }
                        audio.remove(track)?.let { host.removeTableAudio(it) }
                    }
                },
                onActiveSpeakers = { identities ->
                    if (mine == serial) {
                        speaking = identities.toSet()
                        host.tablesChanged()
                    }
                },
                onDisconnected = { cause -> if (mine == serial && !closing) reconnectLater(cause) },
            )
        val opened = opener(token, callbacks)
        session = opened
        host.setAtTable(true)
        val failure = opened.connect()
        if (mine != serial) return
        if (failure != null) {
            dropLocal()
            host.announce(gettext("Die Verbindung zum Tisch konnte nicht hergestellt werden."))
            host.tablesChanged()
            leaveQuietly()
            return
        }
        connected = true
        micOn = false
        if (restoreMic && token.canPublish) micOn = opened.microphone(true) == null
        if (!hadPosition) {
            host.announce(gettext("Sie sitzen an Tisch %1. Ihr Mikrofon ist aus.", token.table + 1))
        } else if (quieted && !wasQuiet) {
            host.announce(gettext("Der Tisch wurde beruhigt."))
        } else if (!quieted && wasQuiet) {
            host.announce(gettext("Die Beruhigung wurde aufgehoben."))
        }
        host.tablesChanged()
    }

    private var reconnecting = false

    private fun reconnectLater(cause: DisconnectCause) {
        if (reconnecting) return
        reconnecting = true
        val launched = serial
        AppScope.launch {
            try {
                reconnect(cause = cause, launchedFor = launched)
            } finally {
                reconnecting = false
            }
        }
    }

    private suspend fun reconnect(
        cause: DisconnectCause,
        launchedFor: Int,
    ) {
        val wantedMic = micOn
        connected = false
        micOn = false
        host.tablesChanged()
        if (cause == DisconnectCause.DuplicateIdentity) {
            dropLocal()
            host.announce(gettext("Die Verbindung zum Tisch wurde beendet."))
            host.tablesChanged()
            return
        }
        for (attempt in reconnectDelaysMs.indices) {
            delay(reconnectDelaysMs[attempt])
            if (launchedFor != serial || closing) return
            val token =
                try {
                    rpcService<IEncounterSpaceService>().tableToken(spaceId).token
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ServiceBusyException) {
                    continue
                } catch (e: ConflictException) {
                    // The table rotated while the token was minted: ask again for the current room (an answer of "no seat" is a `null` token).
                    continue
                } catch (e: Throwable) {
                    break
                }
            if (launchedFor != serial || closing) return
            if (token == null) {
                dropLocal()
                host.announce(gettext("Sie wurden ins Plenum zurückgesetzt."))
                host.tablesChanged()
                return
            }
            attach(token = token, restoreMic = wantedMic)
            return
        }
        dropLocal()
        host.announce(gettext("Die Verbindung zum Tisch ist abgebrochen. Sie sind wieder im Plenum."))
        host.tablesChanged()
        leaveQuietly()
    }

    /** Best effort, but with the retry of [leave]: `leaveTable` shares the 1-request-per-2-s limit with `joinTable`, and a failed connect is quick. */
    private suspend fun leaveQuietly() = tellServerLeft()

    private suspend fun tellServerLeft() {
        for (attempt in 0 until LEAVE_ATTEMPTS) {
            try {
                rpcService<IEncounterSpaceService>().leaveTable(spaceId)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServiceBusyException) {
                delay(LEAVE_RETRY_MS)
                // Sat down again (or is just doing so) while waiting: a late leave would throw the NEW seat away.
                if (seated || busy) return
            } catch (e: Throwable) {
                // The server drops the seat with the presence anyway; nothing more to do here.
                return
            }
        }
    }

    /** Ends the current audio session without touching the position (a replacement follows). */
    private fun silenceSession() {
        closing = true
        val old = session
        session = null
        clearAudio()
        if (old != null) AppScope.launch { old.disconnect() }
    }

    private fun dropLocal(notify: Boolean = true) {
        serial++
        closing = true
        val old = session
        session = null
        position = null
        pending = null
        micOn = false
        canPublish = false
        quieted = false
        connected = false
        speaking = emptySet()
        clearAudio()
        if (old != null) AppScope.launch { old.disconnect() }
        if (notify) host.setAtTable(false)
    }

    private fun clearAudio() {
        audio.values.forEach { host.removeTableAudio(it) }
        audio.clear()
    }

    private companion object {
        const val LEAVE_ATTEMPTS = 3
        const val LEAVE_RETRY_MS = 2_100L
    }
}
