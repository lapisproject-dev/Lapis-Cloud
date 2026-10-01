package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.radioGroup
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import org.w3c.dom.events.Event
import kotlin.js.Promise

/*
 * V1.9.22 -- the voting booth. An irreversible, single-shot action, so it is its own mode: it REPLACES the whole detail view (the
 * route does not change), offers exactly three steps (choose -> review -> cast) and ends in a state that explains itself.
 *
 * Secrecy rules (guarded by `ElectionSecrecyTripwireTest`): no `console`/`println`, no `localStorage`/`sessionStorage`, no
 * `history.pushState`, no field in `AppState`, no server message, and neither the selection nor the receipt code ever appears
 * in a toast. The selection lives in this controller's two private fields and is cleared the moment the request has been sent;
 * the receipt code exists in exactly two places -- the DOM node that shows it (and its print twin) and the closure of the "copy"
 * button -- and both are gone after "Fertig".
 *
 * A conflict or a network failure never claims more than is known: the booth reloads the participation state and tells the member
 * what actually happened ("already voted", "voting closed", "not possible right now"). `e.message` is never read -- Kilua RPC does
 * not transmit it and the server's text can carry member UUIDs.
 */

/** The receipt code is 20 random bytes as unpadded Base64url: exactly 27 characters of `[A-Za-z0-9_-]`. */
private val RECEIPT_PATTERN = Regex("^[A-Za-z0-9_-]{27}$")

private const val RECEIPT_GROUP_SIZE = 4

/**
 * Set by the elections screen: called with `true` while a receipt is on screen (so the "back to overview" button can be hidden --
 * `beforeunload` does not fire on in-app navigation) and with `false` once the receipt is gone.
 */
internal var electionReceiptVisibilityHook: ((Boolean) -> Unit)? = null

internal fun renderElectionBooth(
    panel: SimplePanel,
    election: ElectionDto,
    onExit: (refresh: Boolean) -> Unit,
) {
    ElectionBooth(panel, election, onExit).showSelect()
}

private class ElectionBooth(
    private val host: SimplePanel,
    private val election: ElectionDto,
    private val onExit: (Boolean) -> Unit,
) {
    private var answer: ElectionAnswer? = null
    private var selectedIds: List<String> = emptyList()
    private var inFlight = false

    private fun fresh(): SimplePanel {
        host.removeAll()
        return host.vPanel(spacing = 10) { addCssClasses("lapis-booth") }
    }

    fun showSelect() {
        val booth = fresh()
        booth.h2(tr("Stimmabgabe")) { addCssClass("h5") }
        booth.untrustedP(election.title, className = "fw-bold mb-0")
        booth.p(
            if (election.secret) {
                tr("Ihre Stimme ist geheim: Es wird gespeichert, dass Sie abgestimmt haben, aber nicht, wie.")
            } else {
                tr("Diese Wahl ist offen: Ihre Stimme wird mit Ihrem Namen gespeichert.")
            },
        ) { addCssClasses("text-muted small mb-0") }

        val form = booth.lapisForm()
        var readSelection: () -> Boolean = { false }
        when (election.electionType) {
            ElectionType.YES_NO -> {
                val radio =
                    form.panel.radioGroup(
                        options = ElectionAnswer.entries.map { it.name to answerLabel(it) },
                        value = answer?.name,
                        label = tr("Ihre Stimme"),
                    )
                radio.addCssClass("lapis-booth-tiles")
                val field =
                    form.register(
                        control = radio,
                        label = tr("Ihre Stimme"),
                        required = true,
                        requiredMessage = gettext("Bitte treffen Sie eine Auswahl."),
                    )
                readSelection = {
                    answer = field.value.takeIf { it.isNotBlank() }?.let { ElectionAnswer.valueOf(it) }
                    selectedIds = emptyList()
                    answer != null
                }
            }
            ElectionType.SINGLE_CHOICE -> {
                val options = election.options.sortedBy { it.position }
                val radio =
                    form.panel.radioGroup(
                        options = untrustedOptions(options.map { it.id to it.label }),
                        value = selectedIds.firstOrNull(),
                        label = tr("Ihre Auswahl"),
                    )
                radio.addCssClass("lapis-booth-tiles")
                val field =
                    form.register(
                        control = radio,
                        label = tr("Ihre Auswahl"),
                        required = true,
                        requiredMessage = gettext("Bitte treffen Sie eine Auswahl."),
                    )
                readSelection = {
                    selectedIds = field.value.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                    answer = null
                    selectedIds.isNotEmpty()
                }
            }
            else -> {
                val seats = election.seatCount
                val fields =
                    election.options.sortedBy { it.position }.map { option ->
                        // The label is a candidate's display name: untrusted free text, sanitized before it reaches the widget.
                        option.id to
                            form.checkField(
                                label = sanitizeUntrustedI18nText(option.label),
                                value = option.id in selectedIds,
                            )
                    }
                val counter =
                    form.panel.div("") {
                        addCssClasses("small")
                        setAttribute("role", "status")
                        setAttribute("aria-live", "polite")
                    }

                fun checkedCount() = fields.count { (_, f) -> (f.control as CheckBox).value }

                fun update() {
                    val k = checkedCount()
                    counter.content =
                        if (k >= seats) {
                            gettext("%1 von höchstens %2 gewählt. Die übrigen Optionen sind gesperrt.", k, seats)
                        } else {
                            gettext("%1 von höchstens %2 gewählt", k, seats)
                        }
                    fields.forEach { (_, f) ->
                        val box = f.control as CheckBox
                        box.disabled = k >= seats && !box.value
                    }
                }
                fields.forEach { (_, f) -> f.subscribe { update() } }
                update()
                form.crossFieldRule {
                    if (checkedCount() in 1..seats) {
                        FieldCheck.Ok
                    } else {
                        FieldCheck.Invalid(gettext("Bitte wählen Sie mindestens eine und höchstens %1 Optionen.", seats))
                    }
                }
                readSelection = {
                    selectedIds = fields.filter { (_, f) -> (f.control as CheckBox).value }.map { it.first }
                    answer = null
                    selectedIds.isNotEmpty()
                }
            }
        }
        val next = Button(tr("Weiter zur Prüfung"), style = ButtonStyle.PRIMARY)
        val cancel = Button(tr("Abbrechen"))
        form.buttons(primary = next, cancel = cancel)
        cancel.onClick {
            answer = null
            selectedIds = emptyList()
            onExit(false)
        }
        next.onClick {
            if (form.validateAndReport() && readSelection()) showReview()
        }
    }

    private fun showReview() {
        val booth = fresh()
        booth.h2(tr("Auswahl prüfen")) { addCssClass("h5") }
        booth.untrustedP(election.title, className = "fw-bold mb-0")
        val lines =
            if (election.electionType == ElectionType.YES_NO) {
                listOfNotNull(answer?.let { answerLabel(it) })
            } else {
                selectedIds.mapNotNull { id ->
                    election.options.firstOrNull { it.id == id }?.let { displayOptionLabel(election, it.label) }
                }
            }
        lines.forEach { line -> booth.div(line) { addCssClasses("lapis-booth-review fw-bold") } }
        booth.p(tr("Nach der Abgabe kann Ihre Stimme nicht mehr geändert werden.")) { addCssClasses("text-muted mb-0") }
        val row = booth.hPanel(spacing = 8)
        val back = Button(tr("Zurück"), style = ButtonStyle.OUTLINESECONDARY)
        val cast = Button(tr("Stimme endgültig abgeben"), style = ButtonStyle.PRIMARY)
        row.add(back)
        row.add(cast)
        back.onClick { if (!inFlight) showSelect() }
        cast.onClick { castNow(cast) }
    }

    private fun castNow(button: Button) {
        if (inFlight) return
        inFlight = true
        runGuardedAction(button) {
            try {
                val input = ElectionBallotInput(electionId = election.id, answer = answer, selectedOptionIds = selectedIds)
                val outcome = castBallotGuarded(input)
                // The selection is not needed any more the moment the request has been sent.
                answer = null
                selectedIds = emptyList()
                when (outcome) {
                    is CastOutcome.Ok -> {
                        val receipt = outcome.result.receiptCode
                        if (election.secret && receipt != null) showReceipt(receipt) else showDone()
                    }
                    CastOutcome.Forbidden ->
                        showTerminal(
                            tr("Keine Stimmberechtigung"),
                            tr("Sie stehen nicht im Wählerverzeichnis dieser Wahl und können nicht abstimmen."),
                        )
                    CastOutcome.Conflict -> explainFailure(connectionLost = false)
                    CastOutcome.Failed -> explainFailure(connectionLost = true)
                }
            } finally {
                inFlight = false
            }
        }
    }

    /** What really happened after a conflict or a lost connection: found out by reading the state, never by reading an error. */
    private suspend fun explainFailure(connectionLost: Boolean) {
        val probed = probeElectionState(election.id)
        if (probed.participation?.hasVoted == true) {
            showTerminal(
                tr("Bereits abgestimmt"),
                if (connectionLost) {
                    tr("Ihre Stimme wurde gezählt. Die Bestätigung ist wegen eines Verbindungsabbruchs nicht bei Ihnen angekommen.")
                } else {
                    tr("Sie haben bereits abgestimmt. Ihre Stimme wurde gezählt.")
                },
            )
            return
        }
        val current = probed.election
        when {
            current == null && connectionLost ->
                showTerminal(
                    tr("Keine Verbindung"),
                    tr(
                        "Die Verbindung wurde unterbrochen. Ob Ihre Stimme gezählt wurde, konnte nicht geprüft werden. Bitte laden Sie die Seite neu.",
                    ),
                )
            current != null && current.status != ElectionStatus.OPEN ->
                showTerminal(tr("Abstimmung beendet"), tr("Die Abstimmung ist nicht mehr offen."))
            else ->
                showTerminal(
                    tr("Stimmabgabe gerade nicht möglich"),
                    tr(
                        "Die Stimmabgabe ist gerade nicht möglich, etwa weil eine Übertragung noch pausiert wird. " +
                            "Bitte warten Sie kurz und versuchen Sie es erneut.",
                    ),
                    retry = true,
                )
        }
    }

    private fun showTerminal(
        title: String,
        text: String,
        retry: Boolean = false,
    ) {
        val booth = fresh()
        booth.h2(title) { addCssClass("h5") }
        booth.p(text)
        val row = booth.hPanel(spacing = 8)
        row.button(tr("Zurück zur Wahl"), style = ButtonStyle.OUTLINESECONDARY).onClick { onExit(true) }
        if (retry) row.button(tr("Erneut abstimmen"), style = ButtonStyle.PRIMARY).onClick { showSelect() }
    }

    private fun showDone() {
        val booth = fresh()
        booth.h2(tr("Ihre Stimme wurde gezählt")) { addCssClass("h5") }
        booth.p(tr("Vielen Dank. Ihre Stimme wurde mit Ihrem Namen gespeichert."))
        booth.button(tr("Fertig"), style = ButtonStyle.PRIMARY).onClick { onExit(true) }
    }

    /**
     * The receipt of a secret ballot. [code] is a Base64url value from the server, validated against its exact alphabet before it is
     * drawn, so it is rendered character by character WITHOUT the untrusted-text sanitizer (which could change characters and so
     * corrupt a code the member copies): the alphabet cannot contain the i18n marker, so the sanitizer has nothing to do.
     */
    private fun showReceipt(code: String) {
        val booth = fresh()
        booth.h2(tr("Ihre Stimme wurde gezählt")) { addCssClass("h5") }
        if (!RECEIPT_PATTERN.matches(code)) {
            booth.p(tr("Die Quittung konnte nicht angezeigt werden. Ihre Stimme ist trotzdem gezählt."))
            booth.button(tr("Fertig"), style = ButtonStyle.PRIMARY).onClick { onExit(true) }
            return
        }
        booth.p(
            tr(
                "Dies ist Ihre Quittung. Sie wird nur jetzt angezeigt und nirgends gespeichert. " +
                    "Notieren oder drucken Sie sie, wenn Sie später prüfen möchten, dass Ihre Stimme gezählt wurde.",
            ),
        ) { addCssClasses("alert alert-warning mb-0") }
        var raw: String? = code
        val groups = code.chunked(RECEIPT_GROUP_SIZE)
        val codeBox = booth.div(className = "lapis-receipt-code")
        groups.forEach { group -> codeBox.span(group) }
        // The print twin: hidden on screen, the only thing visible when printing (see `theme.css`, `lapis-printing-receipt`).
        val printTwin = booth.div(className = "lapis-receipt-print")
        printTwin.untrustedP(election.title, className = "fw-bold")
        printTwin.p(tr("Quittung"))
        val twinCode = printTwin.div(className = "lapis-receipt-code")
        groups.forEach { group -> twinCode.span(group) }

        val copyStatus =
            booth.div("") {
                addCssClasses("small")
                setAttribute("role", "status")
                setAttribute("aria-live", "polite")
            }
        val actions = booth.hPanel(spacing = 8)
        val copy = actions.button(tr("Kopieren"), style = ButtonStyle.OUTLINESECONDARY)
        val print = actions.button(tr("Drucken"), style = ButtonStyle.OUTLINESECONDARY)
        copy.onClick {
            val failed = gettext("Kopieren nicht möglich. Bitte schreiben Sie den Code ab.")
            val clipboard = window.navigator.asDynamic().clipboard
            val value = raw
            if (clipboard == null || value == null) {
                copyStatus.content = failed
            } else {
                val written = clipboard.writeText(value).unsafeCast<Promise<Any?>>()
                written.then({ copyStatus.content = gettext("Kopiert.") }, { copyStatus.content = failed })
            }
        }
        val afterPrint: (Event) -> Unit = { document.body?.classList?.remove("lapis-printing-receipt") }
        print.onClick {
            document.body?.classList?.add("lapis-printing-receipt")
            window.print()
        }

        // Reload/tab close with an unsaved receipt asks first (beforeunload); in-app navigation is blocked by hiding the overview
        // button via [electionReceiptVisibilityHook]. The listeners go away with "Fertig" and with the panel itself.
        val leaveGuard: (Event) -> Unit = { event ->
            event.preventDefault()
            event.asDynamic().returnValue = ""
        }

        fun removeListeners() {
            window.removeEventListener("beforeunload", leaveGuard)
            electionReceiptVisibilityHook?.invoke(false)
            window.removeEventListener("afterprint", afterPrint)
            document.body?.classList?.remove("lapis-printing-receipt")
        }
        booth.addWithLifecycle(
            Div(),
            onInsert = {
                window.addEventListener("beforeunload", leaveGuard)
                electionReceiptVisibilityHook?.invoke(true)
                window.addEventListener("afterprint", afterPrint)
            },
            onDestroy = { removeListeners() },
        )

        val form = booth.lapisForm()
        val noted =
            form.checkField(
                label = tr("Ich habe mir die Quittung notiert."),
                required = true,
                requiredMessage = gettext("Bitte bestätigen Sie, dass Sie die Quittung notiert haben."),
            )
        val done = Button(tr("Fertig"), style = ButtonStyle.PRIMARY)
        form.buttons(primary = done)
        done.disabled = true
        noted.subscribe { done.disabled = it != "true" }
        done.onClick {
            if (!form.validateAndReport()) return@onClick
            raw = null
            removeListeners()
            host.removeAll()
            onExit(true)
        }
    }
}
