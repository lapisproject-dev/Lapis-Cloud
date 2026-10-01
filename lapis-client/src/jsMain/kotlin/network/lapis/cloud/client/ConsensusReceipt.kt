package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.Autocomplete
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
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ISystemicConsensusService
import org.w3c.dom.events.Event
import kotlin.js.Promise

/*
 * V1.9.28 -- the receipt of an anonymous rating and the form that checks one. The receipt exists in exactly two places: the DOM node that
 * shows it (and its print twin) and the closure of the "copy" button; both are gone after "Fertig". It is never stored, logged or put into a
 * URL or a toast. The structure follows the elections' receipt on purpose (`ElectionBooth`), kept as its own copy so the two cannot be
 * changed by accident through each other.
 */

/** The receipt code is 20 random bytes as unpadded Base64url: exactly 27 characters of `[A-Za-z0-9_-]`. */
private val CONSENSUS_RECEIPT_PATTERN = Regex("^[A-Za-z0-9_-]{27}$")

private const val CONSENSUS_RECEIPT_GROUP_SIZE = 4

internal fun isConsensusReceiptCode(code: String): Boolean = CONSENSUS_RECEIPT_PATTERN.matches(code)

/**
 * Set by the consensus screen: called with `true` while a receipt is on screen (so the "back to overview" button can be hidden --
 * `beforeunload` does not fire on in-app navigation) and with `false` once the receipt is gone.
 */
internal var consensusReceiptVisibilityHook: ((Boolean) -> Unit)? = null

/**
 * Shows the receipt [code] in [booth]. [code] is a Base64url value from the server, validated against its exact alphabet by the caller, so
 * it is rendered character by character WITHOUT the untrusted-text sanitizer (which could change characters and so corrupt a code the member
 * copies): the alphabet cannot contain the i18n marker. [onDone] runs after "Fertig".
 */
internal fun renderConsensusReceipt(
    booth: SimplePanel,
    title: String,
    code: String,
    onDone: () -> Unit,
) {
    booth.h2(tr("Ihre Bewertung wurde gezählt")) { addCssClass("h5") }
    booth.p(
        tr(
            "Dies ist Ihre Quittung. Sie wird nur jetzt angezeigt und nirgends gespeichert. " +
                "Notieren oder drucken Sie sie, wenn Sie später prüfen möchten, dass Ihre Bewertung gezählt wurde.",
        ),
    ) { addCssClasses("alert alert-warning mb-0") }
    var raw: String? = code
    val groups = code.chunked(CONSENSUS_RECEIPT_GROUP_SIZE)
    val codeBox = booth.div(className = "lapis-receipt-code")
    groups.forEach { group -> codeBox.span(group) }
    // The print twin: hidden on screen, the only thing visible when printing (see `theme.css`, `lapis-printing-receipt`).
    val printTwin = booth.div(className = "lapis-receipt-print")
    printTwin.untrustedP(title, className = "fw-bold")
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

    // Reload/tab close with an unsaved receipt asks first (beforeunload); in-app navigation is blocked by hiding the overview button via
    // [consensusReceiptVisibilityHook]. The listeners go away with "Fertig" and with the panel itself.
    val leaveGuard: (Event) -> Unit = { event ->
        event.preventDefault()
        event.asDynamic().returnValue = ""
    }

    fun removeListeners() {
        window.removeEventListener("beforeunload", leaveGuard)
        consensusReceiptVisibilityHook?.invoke(false)
        window.removeEventListener("afterprint", afterPrint)
        document.body?.classList?.remove("lapis-printing-receipt")
    }
    booth.addWithLifecycle(
        Div(),
        onInsert = {
            window.addEventListener("beforeunload", leaveGuard)
            consensusReceiptVisibilityHook?.invoke(true)
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
        onDone()
    }
}

/** "Quittung prüfen": only for an anonymous consensus, from the moment ratings exist. */
internal fun renderConsensusReceiptCheck(
    panel: SimplePanel,
    c: SystemicConsensusDto,
) {
    if (!c.secret) return
    if (c.status != SystemicConsensusStatus.RATING &&
        c.status != SystemicConsensusStatus.CLOSED &&
        c.status != SystemicConsensusStatus.EVALUATED
    ) {
        return
    }
    panel.h2(tr("Quittung prüfen")) { addCssClass("h5") }
    panel.p(
        tr("Mit Ihrer Quittung können Sie prüfen, dass Ihre Bewertung gezählt wurde, ohne dass jemand erfährt, wie Sie bewertet haben."),
    ) { addCssClasses("text-muted small") }
    val form = panel.lapisForm()
    val codeField =
        form.textField(
            label = tr("Quittungscode"),
            required = true,
            autocomplete = Autocomplete.OFF,
            init = { text ->
                (text.input as? Widget)?.setAttribute("spellcheck", "false")
                (text.input as? Widget)?.setAttribute("autocapitalize", "off")
            },
        )
    val check = Button(tr("Prüfen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = check)
    val outcome =
        panel.div("") {
            addCssClasses("fw-bold")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    check.onClick {
        form.submit(check) {
            // Read once, then the field is cleared below: the code is not kept anywhere else.
            val code = codeField.value.filterNot { it.isWhitespace() }
            val verification = guarded { rpcService<ISystemicConsensusService>().verifySystemicConsensusReceipt(c.id, code) }
            codeField.reset()
            if (verification == null) return@submit
            outcome.removeAll()
            val round = verification.round
            val resistances = verification.resistances
            when {
                !verification.found -> outcome.content = gettext("Zu diesem Code wurde keine Bewertung gefunden.")
                round != null && round < c.round && !verification.countedInCurrentResult ->
                    outcome.content = gettext("Diese Bewertung stammt aus Runde %1 und zählt im aktuellen Ergebnis nicht mehr.", round)
                resistances == null ->
                    outcome.content = gettext("Ihre Bewertung ist gespeichert. Die Werte werden erst nach der Auswertung angezeigt.")
                else -> {
                    outcome.content = gettext("Ihre Bewertung ist gespeichert und lautet:")
                    resistances.forEach { r ->
                        outcome.div(
                            gettext(
                                "%1: Widerstand %2 von %3",
                                consensusOptionText(isStatusQuoOption = r.isStatusQuoOption, label = r.label),
                                r.resistance,
                                c.scaleMax,
                            ),
                        ) { addCssClasses("fw-normal text-break") }
                    }
                }
            }
        }
    }
}
