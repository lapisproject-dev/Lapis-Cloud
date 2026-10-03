package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import org.w3c.dom.HTMLInputElement

/*
 * V1.9.28 -- the resistance booth. An irreversible, single-shot action, so it is its own mode: it REPLACES the whole detail view (the
 * route does not change) and offers exactly three steps (rate every option -> review -> submit), ending in a state that explains itself.
 *
 * Secrecy rules (guarded by `ConsensusSecrecyTripwireTest`): no `console`/`println`, no `localStorage`/`sessionStorage`, no
 * `history.pushState`, no field in `AppState`, no server message, no `data-*` attribute, and neither the ratings nor the receipt code
 * ever appear in a toast. The ratings live in this controller's one private map (and in the radio buttons' own checked state); the map is
 * emptied the moment the request has been sent, and the form DOM is thrown away with it.
 *
 * The radio groups are named by a running group index and the radio buttons carry a running index as id -- never an option id and never the
 * value that button stands for. What each option is and what a rating means exists only in this class's closure.
 *
 * A conflict or a network failure never claims more than is known: the booth reads the participation state again and tells the member what
 * actually happened. `e.message` is never read -- Kilua RPC does not transmit it and the server's text can carry member UUIDs.
 */
internal fun renderConsensusBooth(
    panel: SimplePanel,
    consensus: SystemicConsensusDto,
    roomHost: ConsensusBoothRoomHost? = null,
    onExit: (refresh: Boolean) -> Unit,
) {
    ConsensusBooth(panel, consensus, onExit, roomHost).showSelect()
}

/**
 * V1.9.32 -- what the conference room panel adds to the booth. `null` (the consensus screen) leaves the booth exactly as it was.
 * [exitLabel] is the label of the way out of a terminal state; [ballotLock] is the stream lock of an ANONYMOUS consensus (`null` for an open
 * one, which never pauses a stream); [onBusyChanged] is `true` while the rating request is in flight (the panel locks its close button and
 * asks before the page is left); [compact] is the narrow grid of the side panel. The hook never sees a rating or a receipt.
 */
internal class ConsensusBoothRoomHost(
    val exitLabel: String,
    val ballotLock: BallotLockHook?,
    val onBusyChanged: (Boolean) -> Unit,
    val compact: Boolean,
)

private const val CONSENSUS_LOCK_REASON_ID = "lapis-consensus-lock-reason"

/** The three anchors of the scale, under 0, 5 and 10. */
private const val SCALE_START = 0
private const val SCALE_MIDDLE = 5

private class ConsensusBooth(
    private val host: SimplePanel,
    private val consensus: SystemicConsensusDto,
    private val onExit: (Boolean) -> Unit,
    private val roomHost: ConsensusBoothRoomHost?,
) {
    // V1.9.32 -- the stream lock of the review step: only subscriptions, never a rating or a receipt.
    private val lockSubscriptions = mutableListOf<() -> Unit>()
    private var paintLock: (() -> Unit)? = null

    /** option id -> rating, filled when the member goes to the review step and emptied once the request has been sent. */
    private val ratings = linkedMapOf<String, Int>()
    private var inFlight = false

    /** The status quo option (P) first, then the real proposals -- the same order and numbers as the options list (V1.9.39). */
    private val options: List<SystemicConsensusOptionDto> = consensusOrderedOptions(consensus.options)
    private val numbers: Map<String, String> = consensusOptionNumbers(consensus.options)

    private fun releaseLockWatchers() {
        lockSubscriptions.forEach { it() }
        lockSubscriptions.clear()
        paintLock = null
    }

    private fun exit(refresh: Boolean) {
        ratings.clear()
        releaseLockWatchers()
        onExit(refresh)
    }

    private fun fresh(): SimplePanel {
        releaseLockWatchers()
        host.removeAll()
        return host.vPanel(spacing = 10) {
            addCssClasses("lapis-booth")
            if (roomHost?.compact == true) addCssClass("lapis-booth-compact")
        }
    }

    fun showSelect(notice: String? = null) {
        val booth = fresh()
        val head = booth.div { addCssClasses("sticky-top bg-body py-2 border-bottom") }
        head.h2(tr("Bewertung")) { addCssClass("h5") }
        head.untrustedP(consensus.title, className = "fw-bold mb-1")
        head.p(tr("Bewerten Sie jede Option: Wie groß ist Ihr Widerstand?")) { addCssClasses("mb-0") }
        head.p(
            gettext("0 = kein Widerstand, ich kann gut damit leben · %1 = für mich nicht tragbar.", consensus.scaleMax),
        ) { addCssClasses("text-muted small mb-1") }
        if (roomHost != null) {
            head.p(tr("Gewählt wird die Option mit dem geringsten Gesamtwiderstand.")) { addCssClasses("text-muted small mb-1") }
        }
        head.p(
            if (consensus.secret) {
                tr("Ihre Bewertung ist anonym: Es wird gespeichert, dass Sie bewertet haben, aber nicht, wie.")
            } else {
                tr("Dieses Konsensieren ist offen: Ihre Bewertung wird mit Ihrem Namen gespeichert.")
            },
        ) { addCssClasses("text-muted small mb-1") }

        fun newCounter(parent: Container): Div =
            parent.div("") {
                addCssClasses("small fw-bold")
                setAttribute("role", "status")
                setAttribute("aria-live", "polite")
            }
        // In the room the counter sits next to "Prüfen" (the head stays short in a narrow panel); on the consensus screen it stays in the head.
        var counter: Div? = if (roomHost == null) newCounter(head) else null
        if (notice != null) booth.p(notice) { addCssClasses("alert alert-warning mb-0") }

        // (option id, its eleven radio buttons in the order 0..scaleMax) -- never written into the DOM.
        val groups = mutableListOf<Pair<String, List<Tag>>>()
        var radioIndex = 0
        options.forEachIndexed { groupIndex, option ->
            val fieldset = booth.tag(TAG.FIELDSET, className = "lapis-sk-option")
            val legend = fieldset.tag(TAG.LEGEND)
            val number = numbers.getValue(option.id)
            legend.consensusNumberPlaque(number)
            legend.consensusNumberSrPrefix(number)
            legend.span(consensusOptionText(option), className = "lapis-sk-option__text")
            val scale = fieldset.div(className = "lapis-sk-scale")
            val radios =
                (SCALE_START..consensus.scaleMax).map { digit ->
                    val id = "sk-r-$radioIndex"
                    radioIndex++
                    val radio = scale.tag(TAG.INPUT)
                    radio.setAttribute("type", "radio")
                    radio.setAttribute("name", "sk-g-$groupIndex")
                    radio.setAttribute("id", id)
                    // Coming back from the review step keeps what was chosen.
                    if (ratings[option.id] == digit) radio.setAttribute("checked", "checked")
                    scale.tag(TAG.LABEL, content = digit.toString()) { setAttribute("for", id) }
                    radio
                }
            groups += option.id to radios
            val anchors = fieldset.div(className = "lapis-sk-anchors")
            anchors.span(tr("kein"))
            anchors.span(tr("deutliche Bedenken"))
            anchors.span(tr("nicht tragbar"))
            // V1.9.39: the proposer's reasoning -- clamped on the page, behind a switch in the narrow room panel. Never in the review step.
            renderOptionRationale(
                parent = fieldset,
                option = option,
                mode = if (roomHost?.compact == true) RationaleMode.Collapsed else RationaleMode.Clamped,
                toggleIdPrefix = "sk-why",
                index = groupIndex,
                closedLabel = tr("Begründung"),
            )
        }

        fun chosen(radios: List<Tag>): Int? =
            radios.indexOfFirst { (it.getElement() as? HTMLInputElement)?.checked == true }.takeIf { it >= 0 }

        val actions = booth.hPanel(spacing = 8)
        val cancel = actions.button(tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)
        val next = actions.button(tr("Prüfen"), style = ButtonStyle.PRIMARY)
        if (roomHost != null) counter = newCounter(actions)

        fun update() {
            val done = groups.count { (_, radios) -> chosen(radios) != null }
            counter?.content =
                if (groups.size ==
                    1
                ) {
                    gettext("%1 von 1 Option bewertet", done)
                } else {
                    gettext("%1 von %2 Optionen bewertet", done, groups.size)
                }
            next.disabled = done < groups.size
        }
        groups.forEach { (_, radios) -> radios.forEach { radio -> radio.onEvent { change = { update() } } } }
        // The restored radio buttons are only checked once they are in the document: count after the first render.
        update()
        kotlinx.browser.window.setTimeout({ update() }, 0)

        cancel.onClick { exit(false) }
        next.onClick {
            val picked = groups.associate { (optionId, radios) -> optionId to chosen(radios) }
            if (picked.values.any { it == null }) {
                update()
                return@onClick
            }
            ratings.clear()
            picked.forEach { (optionId, value) -> ratings[optionId] = checkNotNull(value) }
            showReview()
        }
    }

    private fun showReview(notice: String? = null) {
        val booth = fresh()
        booth.h2(tr("Bewertung prüfen")) { addCssClass("h5") }
        booth.untrustedP(consensus.title, className = "fw-bold mb-0")
        if (notice != null) booth.p(notice) { addCssClasses("alert alert-warning mb-0") }
        options.forEach { option ->
            val value = ratings[option.id] ?: return@forEach
            booth.div { addCssClasses("lapis-booth-review fw-bold text-break") }.apply {
                consensusNumberPlaque(numbers.getValue(option.id))
                span(gettext("%1: Widerstand %2 von %3", consensusOptionText(option), value, consensus.scaleMax))
            }
        }
        booth.p(tr("Nach der Abgabe kann Ihre Bewertung nicht mehr geändert werden.")) { addCssClasses("text-muted mb-0") }
        val row = booth.hPanel(spacing = 8)
        val back = Button(tr("Zurück"), style = ButtonStyle.OUTLINESECONDARY)
        val submit = Button(tr("Endgültig abgeben"), style = ButtonStyle.PRIMARY)
        row.add(back)
        row.add(submit)
        back.onClick { if (!inFlight) showSelect() }
        submit.onClick { if (roomHost?.ballotLock?.lockedReason() == null) submitNow(submit) }
        roomHost?.ballotLock?.let { watchLock(booth, submit, it) }
    }

    /** V1.9.32 -- the visible reason next to a locked "Endgültig abgeben" while the stream of an anonymous consensus is still being paused. */
    private fun watchLock(
        booth: SimplePanel,
        submit: Button,
        hook: BallotLockHook,
    ) {
        val note =
            booth.div("") {
                addCssClasses("text-muted small")
                setAttribute("role", "status")
            }
        note.id = CONSENSUS_LOCK_REASON_ID

        fun paint() {
            val reason = hook.lockedReason()
            submit.disabled = reason != null
            if (reason == null) {
                note.hide()
                submit.removeAttribute("aria-describedby")
            } else {
                note.content = reason
                note.show()
                submit.setAttr("aria-describedby", CONSENSUS_LOCK_REASON_ID)
            }
        }
        paint()
        paintLock = { if (!inFlight) paint() }
        lockSubscriptions += hook.subscribe { paintLock?.invoke() }
    }

    private fun submitNow(button: Button) {
        if (inFlight) return
        inFlight = true
        roomHost?.onBusyChanged?.invoke(true)
        var succeeded = false
        runGuardedAction(button, restoreDisabled = { succeeded }) {
            try {
                val outcome = castConsensusBallotGuarded(SystemicConsensusBallotInput(consensus.id, ratings.toMap()))
                if (outcome is ConsensusCastOutcome.Ok) {
                    succeeded = true
                    ratings.clear()
                    val receipt = outcome.result.receiptCode
                    if (consensus.secret && receipt != null && isConsensusReceiptCode(receipt)) showReceipt(receipt) else showDone()
                } else {
                    explainFailure(outcome)
                }
            } finally {
                inFlight = false
                roomHost?.onBusyChanged?.invoke(false)
                // a lock change that came in while the request ran was skipped (the button is guarded then): catch up now
                paintLock?.invoke()
            }
        }
    }

    /** What really happened after a refusal or a lost connection: found out by reading the state, never by reading an error. */
    private suspend fun explainFailure(outcome: ConsensusCastOutcome) {
        if (outcome is ConsensusCastOutcome.Forbidden) {
            ratings.clear()
            showTerminal(tr("Keine Stimmberechtigung"), tr("Sie sind für diese Runde nicht stimmberechtigt."))
            return
        }
        val probed = probeConsensusState(consensus.id)
        val connectionLost = outcome is ConsensusCastOutcome.Failed
        when {
            probed.participation?.hasRated == true -> {
                ratings.clear()
                showTerminal(
                    tr("Bereits bewertet"),
                    if (connectionLost) {
                        tr("Ihre Bewertung wurde gezählt. Die Bestätigung ist wegen eines Verbindungsabbruchs nicht bei Ihnen angekommen.")
                    } else {
                        tr("Ihre Bewertung ist bereits eingegangen.")
                    },
                )
            }
            probed.consensus != null && probed.consensus.status != SystemicConsensusStatus.RATING -> {
                ratings.clear()
                showTerminal(tr("Bewertung beendet"), tr("Die Bewertung ist geschlossen."))
            }
            probed.consensus == null && probed.participation == null -> {
                ratings.clear()
                showTerminal(
                    tr("Keine Verbindung"),
                    if (connectionLost) {
                        tr(
                            "Die Verbindung wurde unterbrochen. Ob Ihre Bewertung gezählt wurde, konnte nicht geprüft werden. Bitte laden Sie die Seite neu.",
                        )
                    } else {
                        tr("Der Stand hat sich geändert. Bitte erneut prüfen.")
                    },
                )
            }
            // Still open and not rated: usually "the conference stream is not paused yet". The ratings are kept and nothing is sent again by
            // itself -- the member decides.
            else -> {
                // inside the room this usually means "the stream is not paused yet": the host refreshes its room state
                roomHost?.ballotLock?.onConflict()
                showReview(notice = gettext("Bitte kurz warten und erneut versuchen."))
            }
        }
    }

    private fun showTerminal(
        title: String,
        text: String,
    ) {
        val booth = fresh()
        booth.h2(title) { addCssClass("h5") }
        booth.p(text)
        booth.button(roomHost?.exitLabel ?: tr("Zurück zum Konsensieren"), style = ButtonStyle.OUTLINESECONDARY).onClick { exit(true) }
    }

    private fun showDone() {
        val booth = fresh()
        booth.h2(tr("Ihre Bewertung wurde gezählt")) { addCssClass("h5") }
        booth.p(tr("Danke, Ihre Bewertung ist eingegangen."))
        booth.button(tr("Fertig"), style = ButtonStyle.PRIMARY).onClick { exit(true) }
    }

    private fun showReceipt(code: String) {
        val booth = fresh()
        renderConsensusReceipt(booth = booth, title = consensus.title, code = code) {
            host.removeAll()
            exit(true)
        }
    }
}
