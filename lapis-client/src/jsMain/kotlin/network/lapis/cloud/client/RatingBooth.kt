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
import org.w3c.dom.HTMLInputElement

/*
 * V1.9.41 -- the shared resistance booth, extracted from the V1.9.28 consensus booth so the consensus screen AND the consensus polls use
 * ONE flow: rate every item -> review -> submit, ending in a state that explains itself. An irreversible, single-shot action, so it
 * REPLACES the whole view (the route does not change).
 *
 * Secrecy rules (guarded by `ConsensusSecrecyTripwireTest` and `PollSecrecyTripwireTest`): no `console`/`println`, no
 * `localStorage`/`sessionStorage`, no `history.pushState`, no field in `AppState`, no server message, no `data-*` attribute, no `value`
 * or `checked` ATTRIBUTE (attributes end up in `outerHTML`; the choice is restored as a DOM property only), no toast, no `navigateTo`.
 * The ratings live in this controller's one private map (and in the radio buttons' own checked state); the map is emptied the moment
 * the request has been sent and in every terminal state, and the form DOM is thrown away with it.
 *
 * The radio groups are named by a running group index and the radio buttons carry a running index as id -- never an item key and never
 * the value that button stands for. What each item is and what a rating means exists only in this class's closure.
 *
 * This file knows no service and no DTO (the adapters [renderConsensusBooth] / [renderPollRatingBooth] hold those). A conflict or a
 * network failure never claims more than is known: the booth asks the adapter's [RatingBoothSpec.probe] and tells the member what
 * actually happened. `e.message` is never read -- Kilua RPC does not transmit it and the server's text can carry member UUIDs.
 */

/** One thing to rate. [key] is whatever the adapter needs back in [RatingBoothSpec.cast]; [text] is already sanitized, [explanationRaw] is UNTRUSTED. */
internal class RatingItem(
    val key: String,
    val number: String,
    val text: String,
    val explanationRaw: String?,
    /** Optional small second line next to the text (e.g. "Passivlösung"). */
    val subtitle: String? = null,
)

internal class RatingAnchors(
    val low: String,
    val middle: String,
    val high: String,
)

/** One paragraph of the booth's head. */
internal class RatingHeadLine(
    val text: String,
    val className: String,
)

internal class RatingBoothTexts(
    val heading: String,
    /** The title/question: UNTRUSTED, rendered through the sanitizing text helpers. */
    val subjectRaw: String,
    val headLines: List<RatingHeadLine>,
    val anchors: RatingAnchors,
    val explanationClosedLabel: String,
    val reviewLine: (itemText: String, value: Int) -> String,
    val finalNote: String,
    val exitLabel: String,
    val doneHeading: String,
    val doneText: String,
    val forbiddenHeading: String,
    val forbiddenText: String,
    val alreadyHeading: String,
    val alreadyLines: List<String>,
    val alreadyLostLines: List<String>,
    val closedHeading: String,
    val closedText: String,
    val noConnectionHeading: String,
    val noConnectionLostText: String,
    val noConnectionConflictText: String,
)

internal sealed interface RatingCastResult {
    /** [finish] paints the end state into a fresh booth ([leave] closes the booth and refreshes); `null` = the default "done" state. */
    class Ok(
        val finish: ((booth: SimplePanel, leave: () -> Unit) -> Unit)? = null,
    ) : RatingCastResult

    data object Conflict : RatingCastResult

    data object Forbidden : RatingCastResult

    data object Failed : RatingCastResult
}

/** What reading the state again found out: only facts that were actually established are `true`. */
internal class RatingProbe(
    val alreadyRated: Boolean,
    val closed: Boolean,
    val unknown: Boolean,
)

internal class RatingBoothSpec(
    val items: List<RatingItem>,
    val scaleMax: Int,
    val texts: RatingBoothTexts,
    val cast: suspend (Map<String, Int>) -> RatingCastResult,
    val probe: suspend () -> RatingProbe,
    val extraCssClass: String? = null,
)

/**
 * What the conference room panel adds to the booth. `null` leaves the booth exactly as it is on the screens. [exitLabel] is the label of
 * the way out of a terminal state; [ballotLock] is the stream lock of an ANONYMOUS consensus; [onBusyChanged] is `true` while the request
 * is in flight; [compact] is the narrow grid of the side panel. The hook never sees a rating or a receipt.
 */
internal class RatingBoothRoomHost(
    val exitLabel: String,
    val ballotLock: BallotLockHook?,
    val onBusyChanged: (Boolean) -> Unit,
    val compact: Boolean,
)

internal fun renderRatingBooth(
    host: SimplePanel,
    spec: RatingBoothSpec,
    roomHost: RatingBoothRoomHost? = null,
    onReview: (Boolean) -> Unit = {},
    onExit: (refresh: Boolean) -> Unit,
) {
    RatingBooth(host, spec, roomHost, onReview, onExit).showSelect()
}

private const val RATING_LOCK_REASON_ID = "lapis-consensus-lock-reason"

/** The three anchors of the scale, under 0, the middle and the top. */
private const val SCALE_START = 0

private class RatingBooth(
    private val host: SimplePanel,
    private val spec: RatingBoothSpec,
    private val roomHost: RatingBoothRoomHost?,
    private val onReview: (Boolean) -> Unit,
    private val onExit: (Boolean) -> Unit,
) {
    private val texts = spec.texts

    // the stream lock of the review step: only subscriptions, never a rating or a receipt.
    private val lockSubscriptions = mutableListOf<() -> Unit>()
    private var paintLock: (() -> Unit)? = null

    /** item key -> rating, filled when the member goes to the review step and emptied once the request has been sent. */
    private val ratings = linkedMapOf<String, Int>()
    private var inFlight = false

    private fun releaseLockWatchers() {
        lockSubscriptions.forEach { it() }
        lockSubscriptions.clear()
        paintLock = null
    }

    private fun exit(refresh: Boolean) {
        ratings.clear()
        releaseLockWatchers()
        onReview(false)
        onExit(refresh)
    }

    private fun fresh(): SimplePanel {
        releaseLockWatchers()
        host.removeAll()
        return host.vPanel(spacing = 10) {
            addCssClasses("lapis-booth")
            if (roomHost?.compact == true) addCssClass("lapis-booth-compact")
            spec.extraCssClass?.let { addCssClass(it) }
        }
    }

    fun showSelect(notice: String? = null) {
        onReview(false)
        val booth = fresh()
        val head = booth.div { addCssClasses("sticky-top bg-body py-2 border-bottom") }
        val heading = texts.heading
        head.h2(heading) { addCssClass("h5") }
        head.untrustedP(texts.subjectRaw, className = "fw-bold mb-1")
        texts.headLines.forEach { line ->
            val lineText = line.text
            head.p(lineText) { addCssClasses(line.className) }
        }

        fun newCounter(parent: Container): Div =
            parent.div("") {
                addCssClasses("small fw-bold")
                setAttribute("role", "status")
                setAttribute("aria-live", "polite")
            }
        // In the room the counter sits next to "Prüfen" (the head stays short in a narrow panel); on the screens it stays in the head.
        var counter: Div? = if (roomHost == null) newCounter(head) else null
        if (notice != null) booth.p(notice) { addCssClasses("alert alert-warning mb-0") }

        // (item key, its radio buttons in the order 0..scaleMax) -- never written into the DOM.
        val groups = mutableListOf<Pair<String, List<Tag>>>()
        var radioIndex = 0
        spec.items.forEachIndexed { groupIndex, item ->
            val fieldset = booth.tag(TAG.FIELDSET, className = "lapis-sk-option")
            val legend = fieldset.tag(TAG.LEGEND)
            legend.consensusNumberPlaque(item.number)
            legend.consensusNumberSrPrefix(item.number)
            legend.span(item.text, className = "lapis-sk-option__text")
            item.subtitle?.let { legend.span(it, className = "text-muted small ms-2") }
            val scale = fieldset.div(className = "lapis-sk-scale")
            val radios =
                (SCALE_START..spec.scaleMax).map { digit ->
                    val id = "sk-r-$radioIndex"
                    radioIndex++
                    val radio = scale.tag(TAG.INPUT)
                    radio.setAttribute("type", "radio")
                    radio.setAttribute("name", "sk-g-$groupIndex")
                    radio.setAttribute("id", id)
                    scale.tag(TAG.LABEL, content = digit.toString()) { setAttribute("for", id) }
                    radio
                }
            groups += item.key to radios
            val anchors = fieldset.div(className = "lapis-sk-anchors")
            val (low, middle, high) = texts.anchors.let { Triple(it.low, it.middle, it.high) }
            anchors.span(low)
            anchors.span(middle)
            anchors.span(high)
            // The creator's explanation -- clamped on the page, behind a switch in the narrow room panel. Never in the review step.
            renderUntrustedExplanation(
                parent = fieldset,
                raw = item.explanationRaw,
                mode = if (roomHost?.compact == true) RationaleMode.Collapsed else RationaleMode.Clamped,
                toggleIdPrefix = "sk-why",
                index = groupIndex,
                closedLabel = texts.explanationClosedLabel,
            )
        }

        fun chosen(radios: List<Tag>): Int? =
            radios.indexOfFirst { (it.getElement() as? HTMLInputElement)?.checked == true }.takeIf { it >= 0 }

        val actions = booth.hPanel(spacing = 8)
        val cancel = actions.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)
        val next = actions.button(tr("Prüfen"), style = ButtonStyle.PRIMARY)
        if (roomHost != null) counter = newCounter(actions)

        fun update() {
            val done = groups.count { (_, radios) -> chosen(radios) != null }
            counter?.content =
                if (groups.size == 1) {
                    gettext("%1 von 1 Option bewertet", done)
                } else {
                    gettext("%1 von %2 Optionen bewertet", done, groups.size)
                }
            next.disabled = done < groups.size
        }
        groups.forEach { (_, radios) -> radios.forEach { radio -> radio.onEvent { change = { update() } } } }
        update()
        // Coming back from the review step restores what was chosen as a PROPERTY of the element, never as an attribute -- and only once
        // the element is in the document, hence after the first render.
        val restore = ratings.toMap()
        kotlinx.browser.window.setTimeout(
            {
                groups.forEach { (key, radios) ->
                    val digit = restore[key] ?: return@forEach
                    (radios.getOrNull(digit - SCALE_START)?.getElement() as? HTMLInputElement)?.checked = true
                }
                update()
            },
            0,
        )

        cancel.onClick { exit(false) }
        next.onClick {
            val picked = groups.associate { (key, radios) -> key to chosen(radios) }
            if (picked.values.any { it == null }) {
                update()
                return@onClick
            }
            ratings.clear()
            picked.forEach { (key, value) -> ratings[key] = checkNotNull(value) }
            showReview()
        }
    }

    private fun showReview(notice: String? = null) {
        onReview(true)
        val booth = fresh()
        booth.h2(tr("Bewertung prüfen")) { addCssClass("h5") }
        booth.untrustedP(texts.subjectRaw, className = "fw-bold mb-0")
        if (notice != null) booth.p(notice) { addCssClasses("alert alert-warning mb-0") }
        spec.items.forEach { item ->
            val value = ratings[item.key] ?: return@forEach
            booth.div { addCssClasses("lapis-booth-review fw-bold text-break") }.apply {
                consensusNumberPlaque(item.number)
                span(texts.reviewLine(item.text, value))
            }
        }
        val finalNote = texts.finalNote
        booth.p(finalNote) { addCssClasses("text-muted mb-0") }
        val row = booth.hPanel(spacing = 8)
        val back = newActionButton(ActionIcon.BACK, tr("Zurück"), ButtonStyle.OUTLINESECONDARY)
        val submit = Button(tr("Endgültig abgeben"), style = ButtonStyle.PRIMARY)
        row.add(back)
        row.add(submit)
        back.onClick { if (!inFlight) showSelect() }
        submit.onClick { if (roomHost?.ballotLock?.lockedReason() == null) submitNow(submit) }
        roomHost?.ballotLock?.let { watchLock(booth, submit, it) }
    }

    /** The visible reason next to a locked "Endgültig abgeben" while the stream of an anonymous consensus is still being paused. */
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
        note.id = RATING_LOCK_REASON_ID

        fun paint() {
            val reason = hook.lockedReason()
            submit.disabled = reason != null
            if (reason == null) {
                note.hide()
                submit.removeAttribute("aria-describedby")
            } else {
                note.content = reason
                note.show()
                submit.setAttr("aria-describedby", RATING_LOCK_REASON_ID)
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
                val outcome = spec.cast(ratings.toMap())
                if (outcome is RatingCastResult.Ok) {
                    succeeded = true
                    ratings.clear()
                    val finish = outcome.finish
                    if (finish == null) {
                        showDone()
                    } else {
                        finish(fresh()) {
                            host.removeAll()
                            exit(true)
                        }
                    }
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
    private suspend fun explainFailure(outcome: RatingCastResult) {
        if (outcome is RatingCastResult.Forbidden) {
            ratings.clear()
            showTerminal(texts.forbiddenHeading, listOf(texts.forbiddenText))
            return
        }
        val probed = spec.probe()
        val connectionLost = outcome is RatingCastResult.Failed
        when {
            probed.alreadyRated -> {
                ratings.clear()
                showTerminal(texts.alreadyHeading, if (connectionLost) texts.alreadyLostLines else texts.alreadyLines)
            }
            probed.closed -> {
                ratings.clear()
                showTerminal(texts.closedHeading, listOf(texts.closedText))
            }
            probed.unknown -> {
                ratings.clear()
                showTerminal(
                    texts.noConnectionHeading,
                    listOf(if (connectionLost) texts.noConnectionLostText else texts.noConnectionConflictText),
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
        lines: List<String>,
    ) {
        onReview(false)
        val booth = fresh()
        booth.h2(title) { addCssClass("h5") }
        lines.forEach { booth.p(it) }
        booth.button(roomHost?.exitLabel ?: texts.exitLabel, style = ButtonStyle.OUTLINESECONDARY).onClick { exit(true) }
    }

    private fun showDone() {
        onReview(false)
        val booth = fresh()
        val doneHeading = texts.doneHeading
        val doneText = texts.doneText
        booth.h2(doneHeading) { addCssClass("h5") }
        booth.p(doneText)
        booth.button(tr("Fertig"), style = ButtonStyle.PRIMARY).onClick { exit(true) }
    }
}
