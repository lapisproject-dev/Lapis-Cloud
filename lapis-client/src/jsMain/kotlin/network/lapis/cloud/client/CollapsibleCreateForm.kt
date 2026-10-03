package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * V1.9.40 -- rule R36B: a "create" form is collapsed by default; one button in the title row (the single primary action of R36) opens it.
 *
 * ## What it is
 * [collapsibleCreateForm] hangs ONE outline-primary button into the page header's action slot ([PageHeader.actionSlot]) and keeps the form
 * in its own host container directly under the header. Closed, the host is empty (it still exists and carries [formId], so the button's
 * `aria-controls` always points at something real). The existing `render...Form` functions of the screens are NOT changed in behaviour --
 * they build the form into the host exactly as before, take an optional `collapse` callback (the Cancel button and the "saved" signal) and
 * return a [FormSnapshot].
 *
 * ## Behaviour (binding, from the design review)
 *  - **Open**: the button stays in its place but becomes `visibility:hidden` + `aria-hidden` + `tabindex=-1` (nothing jumps, no hidden tab
 *    stop); the form appears at once (no animation); the focus moves to the first field; the host scrolls into view (`block: nearest`,
 *    smooth only without `prefers-reduced-motion`). Focus is only ever moved on a user action, never on page build ([PageFocus]).
 *  - **Idempotent**: `open()` on an open form without a prefill does nothing; the host is emptied before every build, so a double click can
 *    never produce two forms; `open(prefill)` on an open form that was changed asks first.
 *  - **Close**: Cancel and Escape (a keydown listener on the form host, NOT on the document, so it dies with the page) compare the form's
 *    current values with the snapshot taken at open. Equal: close at once. Different: [confirmDialog] "Eingaben verwerfen?" with the focus
 *    on "Weiter bearbeiten". There is no "dirty" flag: typing and deleting the same text again is "unchanged".
 *  - **After saving** the screen calls `close(true)`: closes without asking, the focus returns to the button, `aria-expanded=false`. A failed
 *    save (409, validation) never calls it -- the form stays open with its banner.
 *
 * ## V1.9.48 additions
 * [CollapsibleCreateFormController.requestClose] closes the form on behalf of a caller that is about to remove it from the screen (asks when
 * changed); [sectionTitleRow] gives a sub-area of a page its own title row with an action slot for the area's create button.
 *
 * ## Privacy
 * The snapshot lives in a closure only: never in a `data-*` attribute, storage, the console or the URL. The only text this component renders is
 * `tr()` constants.
 */
class FormSnapshot(
    val values: () -> List<String>,
) {
    /** Set by the controller; see [rebaseline]. */
    internal var onRebaseline: (() -> Unit)? = null

    /** Set by the controller; true while the form this snapshot belongs to is open and unchanged. See [applyProgrammatic]. */
    internal var isUnchangedProbe: (() -> Boolean)? = null

    /**
     * Takes the current values as the new "unchanged" state. For a form that fills a field programmatically AFTER it was built (a picker
     * whose options arrive from the server and which preselects the first one): that is not an edit by the person, so it must not make
     * the form look changed.
     */
    fun rebaseline() {
        onRebaseline?.invoke()
    }

    /**
     * A programmatic change (server-loaded preselection). It becomes the new "unchanged" state ONLY if the form was unchanged before it;
     * typed input is never swallowed. Before the controller attaches (still inside the build lambda) it simply applies the change.
     */
    fun applyProgrammatic(change: () -> Unit) {
        val wasUnchanged = isUnchangedProbe?.invoke() ?: false
        change()
        if (wasUnchanged) rebaseline()
    }
}

/** The values of every field of [form], in order (what [FormSnapshot] compares). */
internal fun LapisForm.snapshot(): FormSnapshot = FormSnapshot { fields.map { it.value } }

class CollapsibleCreateFormController<P> internal constructor(
    private val button: Button,
    private val formHost: SimplePanel,
    private val formId: String,
    private val build: SimplePanel.(prefill: P?, close: (saved: Boolean) -> Unit) -> FormSnapshot,
    private val askDiscard: (onDiscard: () -> Unit) -> Unit = ::confirmDiscardInputs,
    private val onOpenChange: (open: Boolean) -> Unit = {},
) {
    private var snapshot: FormSnapshot? = null
    private var baseline: List<String>? = null

    val isOpen: Boolean get() = snapshot != null

    init {
        button.setAttribute("aria-expanded", "false")
        button.setAttribute("aria-controls", formId)
        button.onClick { open() }
        formHost.onEvent {
            keydown = { event ->
                if (event.key == "Escape" &&
                    !event.defaultPrevented &&
                    !event.isComposing &&
                    document.querySelector(".modal.show") == null
                ) {
                    event.preventDefault()
                    close()
                }
            }
        }
    }

    /** Opens the form, optionally pre-filled with [prefill] (duplicate/edit flows). */
    fun open(prefill: P? = null) {
        if (isOpen) {
            if (prefill == null) return
            if (isDirty()) askDiscard { rebuild(prefill) } else rebuild(prefill)
            return
        }
        rebuild(prefill)
    }

    /** Closes the form; asks first when it was changed, unless [force] (after a successful save). */
    fun close(force: Boolean = false) {
        if (!isOpen) return
        if (!force && isDirty()) {
            askDiscard { closeNow() }
            return
        }
        closeNow()
    }

    /**
     * Runs [then] once the form is out of the way (V1.9.48): at once when it is closed or unchanged (closing it first), after the
     * discard confirmation when it was changed. "Weiter bearbeiten" never runs [then]. For a caller that is about to remove the form
     * from the screen (collapsing a detail panel that hosts it) and must not lose typed input silently.
     */
    fun requestClose(then: () -> Unit) {
        if (!isOpen) {
            then()
            return
        }
        if (isDirty()) {
            askDiscard {
                closeNow()
                then()
            }
        } else {
            closeNow()
            then()
        }
    }

    private fun isDirty(): Boolean {
        val current = snapshot ?: return false
        return current.values() != baseline
    }

    private fun rebuild(prefill: P?) {
        formHost.removeAll()
        val created = formHost.build(prefill) { saved -> close(force = saved) }
        snapshot = created
        baseline = created.values()
        created.onRebaseline = { if (snapshot === created) baseline = created.values() }
        created.isUnchangedProbe = { snapshot === created && !isDirty() }
        paintButton(open = true)
        onOpenChange(true)
        whenRendered({ formHost.getElement()?.querySelector(FIRST_FIELD) != null }) {
            val host = formHost.getElement() ?: return@whenRendered
            (host.querySelector(FIRST_FIELD) as? HTMLElement)?.focus()
            scrollNearest(host)
        }
    }

    private fun closeNow() {
        formHost.removeAll()
        snapshot = null
        baseline = null
        paintButton(open = false)
        onOpenChange(false)
        whenRendered({ button.getElement()?.style?.visibility != "hidden" }) { button.getElement()?.focus() }
    }

    private fun paintButton(open: Boolean) {
        button.setAttr("aria-expanded", open.toString())
        if (open) {
            button.setStyle("visibility", "hidden")
            button.setAttr("aria-hidden", "true")
            button.setAttr("tabindex", "-1")
        } else {
            button.removeStyle("visibility")
            button.removeAttribute("aria-hidden")
            button.removeAttribute("tabindex")
        }
    }
}

/**
 * Builds the collapsed create form of a screen.
 *
 *  - [actionSlot]: the page header's action slot ([PageHeader.actionSlot]); the button goes in there (R36).
 *  - [formHost]: where the form is built, directly under the header; it receives [formId].
 *  - [buttonLabel]: a `tr()` constant ("Neues Gremium"), never a data value.
 *  - [icon]: the verb icon of the button ([ActionIcon.ADD] by default; [ActionIcon.UPLOAD] for "Kontoauszug hochladen").
 *  - [onOpenChange]: called with `true` after every (re)build of the form and with `false` after it closed (e.g. to lock a picker the form depends on).
 *  - [build]: builds the form into the host and returns its [FormSnapshot] (see [LapisForm.snapshot]); it calls `close(true)` after a
 *    successful save and `close(false)` for Cancel.
 */
fun <P> collapsibleCreateForm(
    actionSlot: Container,
    formHost: SimplePanel,
    buttonLabel: String,
    formId: String,
    icon: ActionIcon = ActionIcon.ADD,
    onOpenChange: (open: Boolean) -> Unit = {},
    build: SimplePanel.(prefill: P?, close: (saved: Boolean) -> Unit) -> FormSnapshot,
): CollapsibleCreateFormController<P> {
    formHost.id = formId
    // The plus glyph is decoration (`aria-hidden`), the label carries the meaning; both are children of the button.
    val button =
        Button("", style = ButtonStyle.OUTLINEPRIMARY).apply {
            icon(icon.css) { setAttribute("aria-hidden", "true") }
            span(buttonLabel, className = "ms-1")
        }
    actionSlot.add(button)
    return CollapsibleCreateFormController(button, formHost, formId, build, onOpenChange = onOpenChange)
}

/**
 * Title row of a sub-area of a page (V1.9.48, R36B): the heading on the left, an action slot on the right -- where the collapsible
 * create button of that area goes when it belongs to a list inside the page rather than to the page itself. [title] is a `tr()`
 * constant. Returns the action slot. A plain `div`, not a panel, so the toolbar rule R56 does not apply.
 */
internal fun Container.sectionTitleRow(title: String): Container {
    val row = div(className = "d-flex flex-wrap align-items-center gap-2")
    // The size class is spelled out on the call line: tripwire R7 reads it there.
    row.h2(title) { addCssClasses("h5 flex-grow-1 mb-0") }
    return row.div(className = "lapis-page-action")
}

/** The Cancel button of a collapsible create form: "Abbrechen" asks for confirmation when the form was changed ([CollapsibleCreateFormController.close]). */
internal fun collapseCancelButton(collapse: (saved: Boolean) -> Unit): Button =
    newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY).apply { onClick { collapse(false) } }

/** The confirmation of [CollapsibleCreateFormController]: the safe answer ("Weiter bearbeiten") holds the focus and is what Escape does. */
internal fun confirmDiscardInputs(onDiscard: () -> Unit) {
    confirmDialog(
        title = tr("Eingaben verwerfen?"),
        message = tr("Die bisher eingegebenen Daten sind noch nicht gespeichert und gehen verloren."),
        confirmLabel = tr("Verwerfen"),
        confirmStyle = ButtonStyle.PRIMARY,
        focusCancel = true,
        cancelLabel = tr("Weiter bearbeiten"),
        onConfirm = onDiscard,
    )
}

private const val FIRST_FIELD = "input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea:not([disabled])"
private const val RENDER_RETRIES = 30
private const val RENDER_RETRY_MS = 16

/** KVision patches the DOM asynchronously: runs [action] once [ready] holds (polled for about half a second), or never if it does not. */
private fun whenRendered(
    ready: () -> Boolean,
    action: () -> Unit,
) {
    var attempt = 0

    fun tick() {
        if (ready()) {
            action()
        } else if (attempt++ < RENDER_RETRIES) {
            window.setTimeout({ tick() }, RENDER_RETRY_MS)
        }
    }
    tick()
}

private fun scrollNearest(element: HTMLElement) {
    val reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    val options = js("({ block: 'nearest' })")
    options.behavior = if (reduced) "auto" else "smooth"
    element.asDynamic().scrollIntoView(options)
}
