package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStartInput
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IConferenceRoomPollService
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * V1.9.304 "Raum-Umfragen" -- the panel next to "Abstimmen" and "Sitzung". It is a non-binding mood picture, never a vote: the badge "Unverbindlich"
 * says so on every card. Three things on one panel, each built once and filled afterwards (a poll that brings nothing new moves nothing under a
 * hand):
 *  - the leadership's form ("Neue Umfrage": question, answer set, counting mode, "Starten"), shown while no poll runs;
 *  - the running poll: for everybody the answer card (one tap, one answer, not changeable), for the leadership the exact number of answers, the
 *    interim result (closed by default, a snapshot at every fifth answer -- never live) and "Umfrage beenden" with an inline question;
 *  - the result of the ended poll (three columns, see [roomPollResultView]); "Ausblenden" hides it in memory only.
 * Every text of the leadership reaches the DOM through `textContent` and [sanitizeUntrustedI18nText], never as HTML.
 */
internal class ConferenceRoomPollPanelHandle(
    val panel: SimplePanel,
    val apply: (RoomPollStateDto) -> Unit,
    val applyFailure: (Int) -> Unit,
    val setOpen: (Boolean) -> Unit,
    val isOpen: () -> Boolean,
    val dispose: () -> Unit,
)

/** The three writes of the panel behind one thin seam, so a test replaces them. Every write answers with the fresh state. */
internal class ConferenceRoomPollRpc(
    val start: suspend (RoomPollStartInput) -> RoomPollStateDto = { rpcService<IConferenceRoomPollService>().startRoomPoll(it) },
    val close: suspend (roomId: String, pollId: String) -> RoomPollStateDto = { room, poll ->
        rpcService<IConferenceRoomPollService>().closeRoomPoll(room, poll)
    },
    val answer: suspend (roomId: String, pollId: String, optionId: String) -> RoomPollStateDto = { room, poll, option ->
        rpcService<IConferenceRoomPollService>().answerRoomPoll(room, poll, option)
    },
)

private const val PANEL_CSS = "border rounded p-2 lapis-conference-voting lapis-room-poll-panel"

internal fun renderConferenceRoomPollPanel(
    parent: Container,
    roomId: String,
    rpc: ConferenceRoomPollRpc = ConferenceRoomPollRpc(),
    onCloseRequested: () -> Unit = {},
    onFreshState: (RoomPollStateDto) -> Unit = {},
    onRefreshRequested: () -> Unit = {},
): ConferenceRoomPollPanelHandle {
    // The live region sits OUTSIDE the panel (like the voting panel's): a hidden panel still announces a new poll.
    val liveRegion =
        parent.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val panel = parent.vPanel(spacing = 6) { addCssClasses(PANEL_CSS) }
    panel.hide()
    panel.setAttribute("role", "region")
    panel.setAttribute("aria-label", gettext("Umfrage"))

    val header = panel.hPanel(spacing = 6) { addCssClasses("align-items-center justify-content-between") }
    header.h2(tr("Umfrage")) { addCssClasses("h6 mb-0") }
    header.actionButton(ActionIcon.CLOSE, tr("Umfrage schließen"), style = ButtonStyle.OUTLINESECONDARY) {
        addCssClass("btn-sm")
        onClick { onCloseRequested() }
    }
    val note = panel.div("") { addCssClasses("text-danger small lapis-room-poll-note") }
    note.setAttribute("role", "alert")
    note.hide()
    val refreshHint = panel.div(tr("Status wird aktualisiert …")) { addCssClasses("text-muted small") }
    refreshHint.hide()
    val formHost = panel.vPanel(spacing = 6) { addCssClass("lapis-room-poll-form") }
    formHost.hide()
    val runningHost = panel.vPanel(spacing = 6) { addCssClass("lapis-room-poll-running") }
    val resultHost = panel.vPanel(spacing = 6) { addCssClass("lapis-room-poll-result-host") }

    var current: RoomPollStateDto? = null
    var open = false
    var busy = false
    var disposed = false
    var form = RoomPollForm()
    var runningSlice: Any? = null
    var resultSlice: Any? = null
    var hiddenResultPollId: String? = null

    // -- announcements ------------------------------------------------------------------------------------------------------------------
    fun announce(text: String) {
        // clear first: the same sentence twice in a row (two polls with the same question) is still read out
        liveRegion.content = ""
        liveRegion.content = text
    }

    fun showNote(text: String?) {
        if (text == null) {
            note.hide()
        } else {
            note.content = text
            note.show()
        }
    }

    // -- the leadership form ------------------------------------------------------------------------------------------------------------
    lateinit var questionInput: Tag
    lateinit var counter: Div
    lateinit var customHost: Div
    lateinit var formError: Div
    lateinit var startButton: Button
    val customInputs = mutableListOf<Tag>()
    val templateRadios = mutableMapOf<RoomPollTemplate, Tag>()
    val modeRadios = mutableMapOf<RoomPollCountingMode, Tag>()
    var addRowButton: Button? = null

    fun valueOf(tag: Tag): String =
        when (val element = tag.getElement()) {
            is HTMLTextAreaElement -> element.value
            is HTMLInputElement -> element.value
            else -> ""
        }

    fun readForm() {
        form =
            form.copy(
                question = valueOf(questionInput),
                custom = if (customInputs.isEmpty()) form.custom else customInputs.map { valueOf(it) },
            )
    }

    fun refreshFormState() {
        readForm()
        counter.content = gettext("%1 / %2", form.question.length, RoomPollRules.MAX_QUESTION)
        val problem = roomPollFormProblem(form)
        val running = current.isOpen()
        val touched = form.question.isNotEmpty()
        val message =
            when {
                !touched -> null
                problem == RoomPollFormProblem.QUESTION_LENGTH ->
                    gettext("Bitte geben Sie eine Frage mit %1 bis %2 Zeichen ein.", RoomPollRules.MIN_QUESTION, RoomPollRules.MAX_QUESTION)
                problem == RoomPollFormProblem.OPTIONS ->
                    gettext(
                        "Bitte geben Sie %1 bis %2 verschiedene Antworten ein (je höchstens %3 Zeichen).",
                        RoomPollRules.MIN_OPTIONS,
                        RoomPollRules.MAX_OPTIONS,
                        RoomPollRules.MAX_OPTION_LENGTH,
                    )
                problem == RoomPollFormProblem.FORBIDDEN_CHARACTERS ->
                    gettext("Frage und Antworten dürfen kein < oder > und keine Steuerzeichen enthalten.")
                else -> null
            }
        if (message == null) {
            formError.hide()
        } else {
            formError.content = message
            formError.show()
        }
        startButton.disabled = problem != null || running || busy
        customHost.visible = form.template == RoomPollTemplate.CUSTOM
        addRowButton?.visible = form.template == RoomPollTemplate.CUSTOM && form.custom.size < RoomPollRules.MAX_OPTIONS
    }

    fun rebuildCustomRows() {
        customHost.removeAll()
        customInputs.clear()
        form.custom.forEachIndexed { index, text ->
            val row = customHost.div(className = "d-flex align-items-center gap-2 mb-1")
            val input = row.tag(TAG.INPUT, className = "form-control form-control-sm lapis-room-poll-custom")
            input.setAttribute("type", "text")
            input.setAttribute("maxlength", RoomPollRules.MAX_OPTION_LENGTH.toString())
            input.setAttribute("value", text)
            input.setAttribute("aria-label", gettext("Antwort %1", index + 1))
            input.onEvent { this.input = { refreshFormState() } }
            customInputs += input
            if (form.custom.size > RoomPollRules.MIN_OPTIONS) {
                row.actionButton(ActionIcon.REMOVE, tr("Antwort entfernen"), style = ButtonStyle.OUTLINESECONDARY, iconOnly = true) {
                    addCssClass("btn-sm")
                    onClick {
                        readForm()
                        form = form.copy(custom = form.custom.filterIndexed { i, _ -> i != index })
                        rebuildCustomRows()
                        refreshFormState()
                    }
                }
            }
        }
    }

    fun buildForm() {
        formHost.tag(TAG.H3, content = gettext("Neue Umfrage"), className = "h6 mb-0")
        formHost
            .tag(
                TAG.LABEL,
                content = gettext("Frage"),
                className = "form-label small mb-0",
            ).setAttribute("for", "lapis-room-poll-question")
        questionInput = formHost.tag(TAG.TEXTAREA, className = "form-control form-control-sm")
        questionInput.setAttribute("id", "lapis-room-poll-question")
        questionInput.setAttribute("rows", "2")
        questionInput.setAttribute("maxlength", RoomPollRules.MAX_QUESTION.toString())
        questionInput.onEvent { this.input = { refreshFormState() } }
        counter = formHost.div("", className = "text-muted small text-end")

        val templates = formHost.tag(TAG.FIELDSET, className = "lapis-room-poll-templates")
        templates.tag(TAG.LEGEND, content = gettext("Antworten"), className = "fs-6 mb-1")
        listOf(
            RoomPollTemplate.YES_NO_ABSTAIN to gettext("Ja / Nein / Enthaltung"),
            RoomPollTemplate.YES_NO to gettext("Ja / Nein"),
            RoomPollTemplate.CUSTOM to gettext("Eigene Antworten"),
        ).forEach { (template, label) ->
            val rowDiv = templates.div(className = "form-check")
            val radio = rowDiv.tag(TAG.INPUT, className = "form-check-input")
            radio.setAttribute("type", "radio")
            radio.setAttribute("name", "lapis-room-poll-template")
            radio.setAttribute("id", "lapis-room-poll-template-${template.name}")
            if (template == form.template) radio.setAttribute("checked", "")
            rowDiv
                .tag(
                    TAG.LABEL,
                    content = label,
                    className = "form-check-label",
                ).setAttribute("for", "lapis-room-poll-template-${template.name}")
            radio.onEvent {
                change = {
                    readForm()
                    form = form.copy(template = template)
                    if (template == RoomPollTemplate.CUSTOM && customInputs.isEmpty()) rebuildCustomRows()
                    refreshFormState()
                }
            }
            templateRadios[template] = radio
        }
        customHost = templates.div(className = "lapis-room-poll-custom-host mt-1")
        customHost.hide()
        rebuildCustomRows()
        addRowButton =
            templates.actionButton(ActionIcon.ADD, tr("Antwort hinzufügen"), style = ButtonStyle.OUTLINESECONDARY) {
                addCssClass("btn-sm")
                onClick {
                    readForm()
                    if (form.custom.size < RoomPollRules.MAX_OPTIONS) form = form.copy(custom = form.custom + "")
                    rebuildCustomRows()
                    refreshFormState()
                }
            }
        addRowButton?.hide()

        val modes = formHost.tag(TAG.FIELDSET, className = "lapis-room-poll-modes")
        modes.tag(TAG.LEGEND, content = gettext("Zählweise"), className = "fs-6 mb-1")
        listOf(
            RoomPollCountingMode.HEAD_COUNT to gettext("Köpfe (demokratisch)"),
            RoomPollCountingMode.WEIGHTED to gettext("LTR-gewichtet (meritokratisch)"),
            RoomPollCountingMode.BOTH to gettext("Beides nebeneinander"),
        ).forEach { (mode, label) ->
            val rowDiv = modes.div(className = "form-check")
            val radio = rowDiv.tag(TAG.INPUT, className = "form-check-input")
            radio.setAttribute("type", "radio")
            radio.setAttribute("name", "lapis-room-poll-mode")
            radio.setAttribute("id", "lapis-room-poll-mode-${mode.name}")
            if (mode == form.mode) radio.setAttribute("checked", "")
            rowDiv.tag(TAG.LABEL, content = label, className = "form-check-label").setAttribute("for", "lapis-room-poll-mode-${mode.name}")
            radio.onEvent {
                change = {
                    readForm()
                    form = form.copy(mode = mode)
                    refreshFormState()
                }
            }
            modeRadios[mode] = radio
        }
        formError = formHost.div("", className = "text-danger small")
        formError.hide()
        startButton =
            formHost.actionButton(ActionIcon.NEXT, tr("Starten"), style = ButtonStyle.PRIMARY) {
                onClick {
                    readForm()
                    if (roomPollFormProblem(form) != null || busy || current.isOpen()) return@onClick
                    busy = true
                    refreshFormState()
                    showNote(null)
                    AppScope.launch {
                        try {
                            val fresh = rpc.start(roomPollFormInput(roomId, form))
                            form = RoomPollForm()
                            (questionInput.getElement() as? HTMLTextAreaElement)?.value = ""
                            onFreshState(fresh)
                        } catch (e: ConflictException) {
                            showNote(gettext("Es läuft bereits eine Umfrage."))
                            onRefreshRequested()
                        } catch (e: Throwable) {
                            showNote(gettext("Die Umfrage konnte nicht gestartet werden. Bitte versuchen Sie es erneut."))
                        } finally {
                            busy = false
                            if (!disposed) refreshFormState()
                        }
                    }
                }
            }
        refreshFormState()
    }
    buildForm()

    // -- the running poll ---------------------------------------------------------------------------------------------------------------
    var paintRunningRef: (RoomPollStateDto) -> Unit = {}

    fun unbindingBadge(host: Container) {
        host.div(gettext("Unverbindlich"), className = "badge text-bg-secondary align-self-start lapis-room-poll-badge")
    }

    fun answer(
        state: RoomPollStateDto,
        optionId: String,
    ) {
        val poll = state.poll ?: return
        if (busy) return
        busy = true
        showNote(null)
        runningHost.getChildren().forEach { child ->
            (child as? Container)?.getChildren()?.forEach {
                (it as? Tag)
                    ?.takeIf { t ->
                        t.getAttribute("data-room-poll-option") != null
                    }?.setAttribute("disabled", "")
            }
        }
        AppScope.launch {
            try {
                onFreshState(rpc.answer(roomId, poll.id, optionId))
            } catch (e: ConflictException) {
                // already answered (or the poll just ended): not an error, the next state tells which
                onRefreshRequested()
            } catch (e: Throwable) {
                showNote(gettext("Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut."))
                runningSlice = null
                current?.let { paintRunningRef(it) }
            } finally {
                busy = false
            }
        }
    }

    fun paintRunningImpl(state: RoomPollStateDto) {
        runningHost.removeAll()
        val poll = state.poll
        if (poll == null || poll.status != RoomPollStatus.OPEN) return
        val card = runningHost.vPanel(spacing = 6) { addCssClass("lapis-room-poll-card") }
        unbindingBadge(card)
        card.div(sanitizeUntrustedI18nText(poll.question), className = "fw-semibold lapis-room-poll-question")
        if (state.canAnswer) {
            card.div(gettext("Eine Antwort, nicht änderbar."), className = "text-muted small")
            poll.options.sortedBy { it.position }.forEach { option ->
                val button =
                    card.tag(
                        TAG.BUTTON,
                        content = sanitizeUntrustedI18nText(option.text),
                        className = "btn btn-outline-primary w-100 text-start lapis-room-poll-option",
                    )
                button.setAttribute("type", "button")
                button.setAttribute("style", "min-height: 48px; margin-bottom: 8px")
                button.setAttribute("data-room-poll-option", option.id)
                button.onClick { answer(state, option.id) }
            }
        } else if (state.voted) {
            card.div(gettext("Ihre Stimme wurde gezählt."), className = "lapis-room-poll-counted")
        }
        if (state.isLeadership) {
            val count = state.answerCount ?: 0
            card.div(pollAnswersLabel(count), className = "small lapis-room-poll-count")
            val confirmRow = card.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
            val endButton =
                card.actionButton(
                    ActionIcon.ROOM_POLL_END,
                    tr("Umfrage beenden"),
                    style = ButtonStyle.OUTLINEDANGER,
                ) { addCssClass("btn-sm") }
            confirmRow.hide()
            confirmRow.div(gettext("Umfrage beenden?"), className = "small fw-semibold")
            confirmRow.actionButton(ActionIcon.APPROVE, tr("Beenden"), style = ButtonStyle.DANGER) {
                addCssClass("btn-sm")
                onClick {
                    if (busy) return@onClick
                    busy = true
                    AppScope.launch {
                        try {
                            onFreshState(rpc.close(roomId, poll.id))
                        } catch (e: Throwable) {
                            showNote(gettext("Die Umfrage konnte nicht beendet werden. Bitte versuchen Sie es erneut."))
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            confirmRow.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY) {
                addCssClass("btn-sm")
                onClick {
                    confirmRow.hide()
                    endButton.show()
                }
            }
            endButton.onClick {
                endButton.hide()
                confirmRow.show()
            }
        }
    }
    paintRunningRef = ::paintRunningImpl

    return ConferenceRoomPollPanelHandle(
        panel = panel,
        apply = { next ->
            if (!disposed) {
                val previous = current
                current = next
                refreshHint.hide()
                val poll = next.poll
                val question = poll?.let { sanitizeUntrustedI18nText(it.question) }.orEmpty()
                roomPollAnnouncement(previous, next, question)?.let { announce(it) }
                // the leadership form: only while no poll runs
                if (next.isLeadership && !next.isOpen()) formHost.show() else formHost.hide()
                refreshFormState()
                // the running poll: rebuilt only when what it shows changed; the answer count and the interim result of the leadership are
                // part of the slice (they ARE what changes), nothing else is
                val slice =
                    listOf(poll?.id, poll?.status, next.voted, next.canAnswer, next.isLeadership, next.answerCount, next.interimAnswerCount)
                if (slice != runningSlice) {
                    runningSlice = slice
                    paintRunningImpl(next)
                }
                val resultKey = listOf(poll?.id, poll?.status, next.result?.totalAnswerCount, hiddenResultPollId)
                if (resultKey != resultSlice) {
                    resultSlice = resultKey
                    resultHost.removeAll()
                    val result = next.result
                    if (poll != null && poll.status == RoomPollStatus.CLOSED && result != null && hiddenResultPollId != poll.id) {
                        resultHost.tag(TAG.H3, content = gettext("Ergebnis der Umfrage"), className = "h6 mb-0")
                        unbindingBadge(resultHost)
                        resultHost.div(question, className = "fw-semibold")
                        resultHost.roomPollResultView(poll, result)
                        resultHost.div(
                            when (poll.closeReason) {
                                network.lapis.cloud.shared.domain.RoomPollCloseReason.ROOM_ENDED ->
                                    gettext(
                                        "Die Umfrage endete mit dem Raum.",
                                    )
                                network.lapis.cloud.shared.domain.RoomPollCloseReason.TIMEOUT ->
                                    gettext(
                                        "Die Umfrage endete nach %1 Minuten.",
                                        RoomPollRules.MAX_OPEN_MINUTES,
                                    )
                                else -> gettext("Die Umfrage wurde beendet.")
                            },
                            className = "text-muted small",
                        )
                        resultHost.actionButton(ActionIcon.VIEW, tr("Ausblenden"), style = ButtonStyle.OUTLINESECONDARY) {
                            addCssClass("btn-sm")
                            onClick {
                                hiddenResultPollId = poll.id
                                resultSlice = null
                                current?.let { s ->
                                    resultHost.removeAll()
                                    resultSlice = listOf(s.poll?.id, s.poll?.status, s.result?.totalAnswerCount, hiddenResultPollId)
                                }
                            }
                        }
                    }
                }
            }
        },
        applyFailure = { failures -> if (failures >= 3) refreshHint.show() },
        setOpen = { wanted ->
            open = wanted
            if (wanted) panel.show() else panel.hide()
        },
        isOpen = { open },
        dispose = { disposed = true },
    )
}

/**
 * The toolbar button of the panel, painted from the state: hidden until the server has answered once, pressed while the panel is open, and with a
 * dot (`lapis-room-poll-new`) and a longer accessible name while a poll waits for this person's answer. Nothing but the state is read.
 */
internal fun paintRoomPollToggle(
    button: Button,
    state: RoomPollStateDto?,
    pressed: Boolean,
) {
    val label = if (roomPollBadgeVisible(state)) gettext("Umfrage – offen, noch nicht beantwortet") else gettext("Umfrage")
    if (button.title != label) button.title = label
    button.setAttr("aria-label", label)
    button.setAttr("aria-pressed", pressed.toString())
    if (pressed) button.addCssClass("active") else button.removeCssClass("active")
    if (roomPollBadgeVisible(state)) button.addCssClass("lapis-room-poll-new") else button.removeCssClass("lapis-room-poll-new")
    if (state != null) button.show() else button.hide()
}
