package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.text.TextArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.CarpoolMessageDto
import network.lapis.cloud.shared.domain.CarpoolMessageLimits
import network.lapis.cloud.shared.domain.CarpoolMessagePageDto
import network.lapis.cloud.shared.rpc.ICarpoolService

/**
 * Welle V1.9.139 "Mitfahr-Nachrichten" -- the short message history of ONE confirmed ride, folded away inline under the ride (driver's passenger
 * row and the person's "Meine Mitfahrten" row). Not a chat: no polling, no unread counter, no live reload -- "Aktualisieren" reads the history again.
 *
 * **The text of a message is somebody else's free text**: it is rendered ONLY as a text node ([untrustedDiv] with the `lapis-pre-wrap` class that keeps
 * the line breaks) -- never as HTML, never with link detection. The sender is "Sie" or the other party's display name ([untrustedSpan]).
 *
 * The server's own error texts never reach the client (see [guarded]); the state of the history comes from the page DTO: [CarpoolMessagePageDto.ended],
 * [CarpoolMessagePageDto.writable]. A failed send re-reads the history quietly: it shows "ended" or "full" if that was the reason.
 * A history that cannot be read any more (the ride is gone) folds the lists back through [CarpoolUi.onChanged].
 */
internal fun carpoolMessageCounterText(length: Int): String = gettext("%1 / %2", length, CarpoolMessageLimits.MAX_BODY_LENGTH)

/** The line that replaces the input once the history has ended; `null` while it is open. */
internal fun carpoolThreadEndedText(page: CarpoolMessagePageDto): String? =
    when {
        !page.ended -> null
        page.endedByMe -> gettext("Sie haben den Verlauf beendet.")
        else -> gettext("Der Verlauf wurde beendet.")
    }

/** The line that replaces the input once 200 messages are reached; `null` otherwise. */
internal fun carpoolThreadFullText(page: CarpoolMessagePageDto): String? =
    if (!page.ended && !page.writable) {
        gettext("Dieser Verlauf hat die Höchstzahl von %1 Nachrichten erreicht.", CarpoolMessageLimits.MAX_MESSAGES)
    } else {
        null
    }

/** Strg+Enter (Windows/Linux) or Cmd+Enter (macOS) sends; Enter alone is a new line. Pure. */
internal fun isSendShortcut(
    ctrl: Boolean,
    meta: Boolean,
    key: String,
): Boolean = (ctrl || meta) && key == "Enter"

/** The messages shown so far, newest first, merged from the first page and the "older" pages already loaded. */
internal fun mergeCarpoolMessages(
    shown: List<CarpoolMessageDto>,
    firstPage: CarpoolMessagePageDto,
): List<CarpoolMessageDto> {
    val ids = firstPage.items.map { it.id }.toSet()
    // Whatever the first page does not hold is OLDER than it (the page is the newest 50).
    return firstPage.items + shown.filter { it.id !in ids }
}

/**
 * The "Nachrichten" button into [actions] and the folded history under it into [host]. The history loads on the FIRST unfold only.
 * Fokus: into the text field, or onto the heading when the history has ended.
 */
internal fun carpoolMessagesToggle(
    actions: SimplePanel,
    host: SimplePanel,
    participationId: String,
    ui: CarpoolUi,
) {
    val toggle = actions.actionButton(ActionIcon.MESSAGES, tr("Nachrichten"), style = ButtonStyle.OUTLINESECONDARY)
    val areaId = "carpool-messages-$participationId"
    toggle.setAttribute("aria-expanded", "false")
    toggle.setAttribute("aria-controls", areaId)
    val area = host.vPanel(spacing = 6) { addCssClasses("border rounded p-2 mt-1") }
    area.id = areaId
    area.hide()
    var view: CarpoolThreadView? = null
    toggle.onClick {
        val open = !area.visible
        if (open) area.show() else area.hide()
        toggle.setAttribute("aria-expanded", open.toString())
        if (open) {
            val shown = view ?: CarpoolThreadView(participationId = participationId, ui = ui, area = area).also { view = it }
            shown.load()
        }
    }
}

/** One open history: builds its skeleton once, then only redraws the list and the state line (the input keeps its text and its focus). */
private class CarpoolThreadView(
    private val participationId: String,
    private val ui: CarpoolUi,
    private val area: SimplePanel,
) {
    private var shown: List<CarpoolMessageDto> = emptyList()
    private var olderAvailable = false
    private var loadedOnce = false
    private lateinit var latest: CarpoolMessagePageDto

    private val heading = area.div(tr("Nachrichten")) { addCssClass("fw-bold") }
    private val list: SimplePanel
    private val olderBox: SimplePanel
    private val stateBox: SimplePanel
    private val composerBox: SimplePanel
    private val endBox: SimplePanel
    private val form: LapisForm
    private val field: LapisField
    private val sendButton: Button
    private val counter: io.kvision.html.Span

    init {
        heading.setAttribute("tabindex", "-1")
        heading.setAttribute("role", "heading")
        heading.setAttribute("aria-level", "3")
        area.div(tr("Nur Sie beide sehen diesen Verlauf; er wird 8 Tage nach der Fahrt gelöscht.")) { addCssClasses("text-muted small") }
        olderBox = area.vPanel(spacing = 4)
        list = area.vPanel(spacing = 4)
        stateBox = area.vPanel(spacing = 4)
        composerBox = area.vPanel(spacing = 4)
        form = composerBox.lapisForm()
        field =
            form.textAreaField(
                label = tr("Nachricht"),
                rows = 3,
                required = true,
                hint = tr("Strg+Eingabe sendet. Eingabe allein beginnt eine neue Zeile."),
                rule = { value ->
                    val length = value.trim().length
                    if (length > CarpoolMessageLimits.MAX_BODY_LENGTH) {
                        FieldCheck.Invalid(gettext("Höchstens %1 Zeichen.", CarpoolMessageLimits.MAX_BODY_LENGTH))
                    } else {
                        FieldCheck.Ok
                    }
                },
                // The attribute belongs on the <textarea> itself, not on the form-group wrapper the widget renders around it.
                init = { (it.input as? Widget)?.setAttribute("maxlength", CarpoolMessageLimits.MAX_BODY_LENGTH.toString()) },
            )
        counter = composerBox.span(carpoolMessageCounterText(0)) { addCssClasses("text-muted small") }
        sendButton = newActionButton(ActionIcon.SEND, tr("Senden"), ButtonStyle.PRIMARY)
        val refreshButton = newActionButton(ActionIcon.REFRESH, tr("Aktualisieren"), ButtonStyle.OUTLINESECONDARY)
        form.buttons(primary = sendButton)
        endBox = area.hPanel(spacing = 8)
        endBox.add(refreshButton)
        sendButton.disabled = true
        field.subscribe { value ->
            counter.content = carpoolMessageCounterText(value.length)
            sendButton.disabled = value.isBlank()
        }
        sendButton.onClick { submit() }
        refreshButton.onClick {
            runGuardedAction(refreshButton) { reload(quiet = false) }
        }
        (field.control as? TextArea)?.onEvent {
            keydown = { event ->
                if (isSendShortcut(ctrl = event.ctrlKey, meta = event.metaKey, key = event.key) && !event.isComposing) {
                    event.preventDefault()
                    if (!sendButton.disabled) submit()
                }
            }
        }
        composerBox.hide()
        endBox.hide()
    }

    /** First unfold (and every later one): reads the history; focus goes to the text field, or to the heading once it has ended. */
    fun load() {
        if (loadedOnce) {
            focusEntry()
            return
        }
        runGuardedAction(null) {
            if (reload(quiet = false)) {
                loadedOnce = true
                focusEntry()
            }
        }
    }

    private fun focusEntry() {
        if (latest.writable) field.focus() else heading.focus()
    }

    private fun submit() {
        form.submit(sendButton, restoreDisabled = { field.value.isBlank() }) {
            val body = field.value.trim()
            val page = guarded { rpcService<ICarpoolService>().sendCarpoolMessage(participationId, body) }
            if (page != null) {
                apply(page)
                field.reset()
                counter.content = carpoolMessageCounterText(0)
                sendButton.disabled = true
                ui.announce(gettext("Nachricht gesendet."))
                if (page.writable) field.focus()
            } else {
                // The toast of `guarded` is generic: find out WHY quietly (ended, full, gone) and show it in place. The text stays in the field.
                reload(quiet = true)
            }
        }
    }

    /** Reads the newest page. `true` on success; on failure the ride is gone for this person: the lists are reloaded. */
    private suspend fun reload(quiet: Boolean): Boolean {
        val page =
            if (quiet) {
                runCatching { rpcService<ICarpoolService>().carpoolMessages(participationId, null) }.getOrNull()
            } else {
                guarded { rpcService<ICarpoolService>().carpoolMessages(participationId, null) }
            }
        if (page == null) {
            ui.announce(gettext("Diese Mitfahrt ist nicht mehr verfügbar."))
            ui.onChanged()
            return false
        }
        apply(page)
        return true
    }

    private fun loadOlder(button: Button) {
        val before = shown.lastOrNull()?.id ?: return
        runGuardedAction(button) {
            val page = guarded { rpcService<ICarpoolService>().carpoolMessages(participationId, before) } ?: return@runGuardedAction
            shown = shown + page.items
            olderAvailable = page.hasMore
            latest = page
            draw()
        }
    }

    /** Takes a NEWEST page (after a read, a send or the end of the history): merges it into what is shown and redraws. */
    private fun apply(page: CarpoolMessagePageDto) {
        val hadOlder = shown.size > page.items.size
        shown = mergeCarpoolMessages(shown, page)
        // Older pages were already loaded: what is still older than the oldest of them is unknown until the next "older" request.
        if (!hadOlder) olderAvailable = page.hasMore
        latest = page
        draw()
    }

    private fun draw() {
        val page = latest
        list.removeAll()
        olderBox.removeAll()
        stateBox.removeAll()
        if (shown.isEmpty()) list.div(tr("Noch keine Nachrichten.")) { addCssClasses("text-muted") }
        if (olderAvailable) {
            val older = olderBox.actionButton(ActionIcon.BACK, tr("Ältere Nachrichten anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
            older.onClick { loadOlder(older) }
        }
        // Oldest on top: the server sends newest first.
        shown.asReversed().forEach { message -> renderMessage(list, message) }
        (carpoolThreadEndedText(page) ?: carpoolThreadFullText(page))?.let { stateBox.div(it) { addCssClasses("text-muted") } }
        if (page.writable) composerBox.show() else composerBox.hide()
        endBox.show()
        if (page.ended) {
            // Nothing left to end: the control (and its open confirmation) goes away, exactly as in a history that is opened already ended.
            endBox.removeAll()
            endControlBuilt = false
        } else {
            addEndControl()
        }
    }

    private var endControlBuilt = false

    private fun addEndControl() {
        if (endControlBuilt) return
        endControlBuilt = true
        confirmedActionButton(
            endBox,
            ActionIcon.CLOSE,
            tr("Verlauf beenden"),
            tr("Wirklich beenden? Danach kann niemand mehr schreiben."),
            tr("Endgültig beenden"),
            // Ending redraws THIS history only: a reload of the whole screen would fold it away under the person's hands.
            CarpoolUi(announce = ui.announce, onChanged = {}),
            gettext("Verlauf beendet."),
        ) {
            val page = rpcService<ICarpoolService>().endCarpoolThread(participationId)
            apply(page)
            page
        }
    }
}

private fun renderMessage(
    list: SimplePanel,
    message: CarpoolMessageDto,
) {
    val box = list.vPanel(spacing = 2) { addCssClasses(if (message.mine) "border rounded p-2 bg-body-secondary" else "border rounded p-2") }
    val meta = box.hPanel(spacing = 8) { addCssClass("align-items-baseline") }
    if (message.mine) {
        meta.span(tr("Sie")) { addCssClasses("fw-bold small") }
    } else {
        meta.untrustedSpan(message.senderLabel, className = "fw-bold small")
    }
    meta.systemDateTimeSpan(message.sentAt).addCssClasses("text-muted small")
    box.untrustedDiv(message.body, className = "lapis-pre-wrap")
}
