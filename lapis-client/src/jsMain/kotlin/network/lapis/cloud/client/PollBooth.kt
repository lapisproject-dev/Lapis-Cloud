package network.lapis.cloud.client

import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollStatus
import org.w3c.dom.HTMLInputElement

/*
 * V1.9.31 -- the answer booth. An irreversible, single-shot action, so it is its own mode with exactly three steps (choose -> check ->
 * submit), ending in a state that explains itself. An answer is anonymous: the screen must leave no trace of it.
 *
 * Secrecy rules (guarded by `PollSecrecyTripwireTest`): no `console`/`println`, no `localStorage`/`sessionStorage`, no `history.pushState`,
 * no field in `AppState`, no server message, no `data-*` attribute, no `value` attribute, no toast. The choice lives in ONE place: the
 * private `chosenIndex` of this controller (an index into the poll's options) and the radio buttons' own checked STATE. It is set as a
 * DOM property only, never as an attribute (attributes end up in `outerHTML`), and `chosenIndex` is emptied in every end state.
 *
 * The radio buttons are named by one constant group name and carry a running index as id -- never an option id and never the value that
 * button stands for. What each option is exists only in this class's closure.
 *
 * The check step names the chosen option on screen, on purpose: only then does a member really check what is about to be sent. That one
 * text node is thrown away with the step; nothing is left behind after the submit.
 *
 * A conflict or a network failure never claims more than is known: the booth reads the participation state again and says what really
 * happened. `e.message` is never read -- Kilua RPC does not transmit it.
 */
internal fun renderPollBooth(
    panel: SimplePanel,
    poll: PollDto,
    onReview: (Boolean) -> Unit,
    onExit: (refresh: Boolean) -> Unit,
) {
    PollBooth(panel, poll, onReview, onExit).showSelect()
}

/** The group name of all radio buttons of the booth: the same for every poll and every option, so it says nothing. */
private const val RADIO_GROUP = "poll-choice"

private class PollBooth(
    private val host: SimplePanel,
    private val poll: PollDto,
    private val onReview: (Boolean) -> Unit,
    private val onExit: (Boolean) -> Unit,
) {
    /** Index into `poll.options` of the chosen option; set at the check step, emptied in every end state. */
    private var chosenIndex: Int? = null
    private var inFlight = false

    private fun exit(refresh: Boolean) {
        chosenIndex = null
        onReview(false)
        onExit(refresh)
    }

    private fun fresh(): SimplePanel {
        host.removeAll()
        return host.vPanel(spacing = 10) { addCssClasses("lapis-booth") }
    }

    fun showSelect(notice: String? = null) {
        onReview(false)
        val booth = fresh()
        val fieldset = booth.tag(TAG.FIELDSET, className = "lapis-poll-choices")
        fieldset.tag(TAG.LEGEND, content = sanitizeUntrustedI18nText(poll.question), className = "fw-bold text-break")
        if (notice != null) booth.p(notice) { addCssClasses("alert alert-warning mb-0") }

        // The radio buttons in the order of the options -- nothing about the options is written into the DOM besides the visible label.
        val radios = mutableListOf<Tag>()
        poll.options.forEachIndexed { index, option ->
            val id = "poll-r-$index"
            val tile = fieldset.div(className = "form-check")
            val radio = tile.tag(TAG.INPUT, className = "form-check-input")
            radio.setAttribute("type", "radio")
            radio.setAttribute("name", RADIO_GROUP)
            radio.setAttribute("id", id)
            val label = tile.tag(TAG.LABEL, content = pollOptionText(option), className = "form-check-label text-break")
            label.setAttribute("for", id)
            radios += radio
        }

        fun chosen(): Int? = radios.indexOfFirst { (it.getElement() as? HTMLInputElement)?.checked == true }.takeIf { it >= 0 }

        val actions = booth.hPanel(spacing = 8)
        val cancel = actions.button(tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)
        val next = actions.button(tr("Weiter"), style = ButtonStyle.PRIMARY)
        next.disabled = true

        fun update() {
            next.disabled = chosen() == null
        }
        radios.forEach { radio -> radio.onEvent { change = { update() } } }
        // Coming back from the check step restores the choice as a PROPERTY of the element, never as an attribute -- and only once the element
        // is in the document, hence after the first render.
        val restore = chosenIndex
        if (restore != null) {
            kotlinx.browser.window.setTimeout(
                {
                    (radios.getOrNull(restore)?.getElement() as? HTMLInputElement)?.checked = true
                    update()
                },
                0,
            )
        }
        kotlinx.browser.window.setTimeout({ update() }, 0)

        cancel.onClick { exit(false) }
        next.onClick {
            val picked = chosen()
            if (picked == null) {
                update()
                return@onClick
            }
            chosenIndex = picked
            showReview()
        }
    }

    private fun showReview(notice: String? = null) {
        val index = chosenIndex ?: return showSelect()
        val option = poll.options.getOrNull(index) ?: return showSelect()
        onReview(true)
        val booth = fresh()
        booth.h2(tr("Antwort prüfen")) { addCssClass("h5") }
        booth.div(gettext("Sie haben „%1“ gewählt.", pollOptionText(option))) { addCssClasses("fw-bold text-break") }
        if (notice != null) booth.p(notice) { addCssClasses("alert alert-warning mb-0") }
        booth.p(
            tr(
                "Ihre Antwort ist endgültig: einmalig, nicht änderbar und anonym. " +
                    "Ihr LTR-Stand wird für die gewichtete Auswertung festgehalten, aber nirgends angezeigt.",
            ),
        ) { addCssClasses("text-muted mb-0") }
        val row = booth.hPanel(spacing = 8)
        val back = Button(tr("Zurück"), style = ButtonStyle.OUTLINESECONDARY)
        val submit = Button(tr("Endgültig abgeben"), style = ButtonStyle.PRIMARY)
        row.add(back)
        row.add(submit)
        back.onClick { if (!inFlight) showSelect() }
        submit.onClick { submitNow(submit) }
    }

    private fun submitNow(button: Button) {
        if (inFlight) return
        val option = chosenIndex?.let { poll.options.getOrNull(it) } ?: return
        inFlight = true
        var succeeded = false
        runGuardedAction(button, restoreDisabled = { succeeded }) {
            try {
                val outcome = castPollResponseGuarded(PollResponseInput(pollId = poll.id, optionId = option.id))
                if (outcome is PollCastOutcome.Ok) {
                    succeeded = true
                    chosenIndex = null
                    showSaved()
                } else {
                    explainFailure(outcome)
                }
            } finally {
                inFlight = false
            }
        }
    }

    /** What really happened after a refusal or a lost connection: found out by reading the state, never by reading an error. */
    private suspend fun explainFailure(outcome: PollCastOutcome) {
        if (outcome is PollCastOutcome.Forbidden) {
            chosenIndex = null
            showTerminal(tr("Keine Berechtigung"), listOf(gettext("Nur aktive Mitglieder können antworten.")))
            return
        }
        val probed = probePollState(poll.id)
        val connectionLost = outcome is PollCastOutcome.Failed
        when {
            probed.participation?.hasResponded == true -> {
                chosenIndex = null
                val lines = mutableListOf(gettext("Ihre Antwort ist gespeichert."))
                if (connectionLost) lines += gettext("Die Bestätigung ist wegen eines Verbindungsabbruchs nicht angekommen.")
                showTerminal(tr("Bereits geantwortet"), lines)
            }
            probed.poll != null && probed.poll.status != PollStatus.OPEN -> {
                chosenIndex = null
                showTerminal(tr("Umfrage beendet"), listOf(gettext("Die Umfrage ist beendet.")))
            }
            probed.poll == null && probed.participation == null -> {
                chosenIndex = null
                showTerminal(
                    tr("Keine Verbindung"),
                    listOf(
                        gettext(
                            "Die Verbindung wurde unterbrochen. Ob Ihre Antwort gespeichert wurde, konnte nicht geprüft werden. " +
                                "Bitte laden Sie die Seite neu.",
                        ),
                    ),
                )
            }
            // Still open and not answered: the choice is kept and nothing is sent again by itself -- the member decides.
            else -> showReview(notice = gettext("Bitte kurz warten und erneut versuchen."))
        }
    }

    private fun showTerminal(
        title: String,
        lines: List<String>,
    ) {
        onReview(false)
        val booth = fresh()
        booth.h2(title) { addCssClass("h5") }
        lines.forEach { booth.p(it) }
        booth.button(tr("Zurück zur Umfrage"), style = ButtonStyle.OUTLINESECONDARY).onClick { exit(true) }
    }

    private fun showSaved() {
        onReview(false)
        val booth = fresh()
        booth.h2(tr("Danke")) { addCssClass("h5") }
        booth.p(tr("Ihre Antwort ist gespeichert."))
        booth.button(tr("Fertig"), style = ButtonStyle.PRIMARY).onClick { exit(true) }
    }
}
