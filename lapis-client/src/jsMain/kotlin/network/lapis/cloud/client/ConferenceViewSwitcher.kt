package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Display
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.conference.ConferenceView
import org.w3c.dom.events.KeyboardEvent

/*
 * V1.9.115 -- the view switcher of the live conference: a radio group ("Ansicht") with the four views, shown as a popover over the bar and,
 * for a bar that is too narrow, again in the "Mehr" sheet (same group, same handler).
 *
 * No `addAfterInsertHook`, no raw-DOM element: the group is built from KVision widgets, every attribute (`aria-checked`, `tabindex`, the
 * disabled reason) goes through the KVision patch cycle, so the late-hook ratchet does not grow. Keyboard: arrow keys move, Enter / Space
 * choose (a native `<button>`), Escape closes and gives the focus back to the bar button. No global shortcut.
 */

/** The four views in the order of the switcher. */
internal val CONFERENCE_VIEW_ORDER: List<ConferenceView> =
    listOf(ConferenceView.GALLERY, ConferenceView.SPEAKER, ConferenceView.PINNED, ConferenceView.SCREEN_SHARE)

/** The icon of a view (the face of the radio and, for the current view, of the bar button). */
internal fun conferenceViewIcon(view: ConferenceView): ActionIcon =
    when (view) {
        ConferenceView.GALLERY -> ActionIcon.VIEW_GALLERY
        ConferenceView.SPEAKER -> ActionIcon.VIEW_SPEAKER
        ConferenceView.PINNED -> ActionIcon.PIN
        ConferenceView.SCREEN_SHARE -> ActionIcon.SCREEN_SHARE
    }

/** The (translated) name of a view -- for the radios, the accessible name of the bar button and the announcements. */
internal fun conferenceViewLabel(view: ConferenceView): String =
    when (view) {
        ConferenceView.GALLERY -> gettext("Galerie")
        ConferenceView.SPEAKER -> gettext("Sprecher groß")
        ConferenceView.PINNED -> gettext("Person anheften")
        ConferenceView.SCREEN_SHARE -> gettext("Bildschirmfreigabe groß")
    }

private fun conferenceViewRadioText(view: ConferenceView): String =
    when (view) {
        ConferenceView.GALLERY -> tr("Galerie")
        ConferenceView.SPEAKER -> tr("Sprecher groß")
        ConferenceView.PINNED -> tr("Person anheften")
        ConferenceView.SCREEN_SHARE -> tr("Bildschirmfreigabe groß")
    }

/** Pure: the radio the arrow key [key] moves to from [from], over the [enabledOrder] (wrapping); `null` for any other key. */
internal fun conferenceViewRadioTarget(
    from: ConferenceView,
    key: String,
    order: List<ConferenceView> = CONFERENCE_VIEW_ORDER,
): ConferenceView? {
    val index = order.indexOf(from).takeIf { it >= 0 } ?: return null
    return when (key) {
        "ArrowRight", "ArrowDown" -> order[(index + 1) % order.size]
        "ArrowLeft", "ArrowUp" -> order[(index - 1 + order.size) % order.size]
        "Home" -> order.first()
        "End" -> order.last()
        else -> null
    }
}

internal class ConferenceViewRadioGroup internal constructor(
    val root: Div,
    private val radios: Map<ConferenceView, Button>,
    private val checks: Map<ConferenceView, io.kvision.html.Span>,
    private val shareHint: Div,
) {
    private var current: ConferenceView = ConferenceView.GALLERY

    /** Whether somebody shares the screen right now (the share radio can only be chosen then). */
    var shareActive: Boolean = false
        private set

    /** Marks [view] as the current one and enables / disables the share radio (it can only be chosen while somebody shares). */
    fun update(
        view: ConferenceView,
        shareActive: Boolean,
    ) {
        current = view
        this.shareActive = shareActive
        for ((v, button) in radios) {
            val selected = v == view
            button.setAttribute("aria-checked", selected.toString())
            button.setAttribute("tabindex", if (selected) "0" else "-1")
            checks.getValue(v).display = if (selected) null else Display.NONE
            if (v == ConferenceView.SCREEN_SHARE) {
                if (shareActive) button.removeAttribute("aria-disabled") else button.setAttribute("aria-disabled", "true")
                if (shareActive) button.removeCssClass("disabled") else button.addCssClass("disabled")
            }
        }
        shareHint.display = if (shareActive) Display.NONE else null
    }

    fun current(): ConferenceView = current

    /** Focuses the radio of the current view (roving focus). */
    fun focusCurrent() {
        radios[current]?.getElement()?.let { (it as? org.w3c.dom.HTMLElement)?.focus() }
    }

    internal fun focus(view: ConferenceView) {
        radios[view]?.getElement()?.let { (it as? org.w3c.dom.HTMLElement)?.focus() }
    }
}

/**
 * Builds the radio group into [this]. [onChoose] is called for a click / Enter / Space on a radio (a disabled share radio is refused here),
 * [onEscape] for Escape. [idPrefix] keeps the ids of the two instances (popover, sheet) apart.
 */
internal fun Container.conferenceViewRadioGroup(
    idPrefix: String,
    onChoose: (ConferenceView) -> Unit,
    onEscape: () -> Unit,
): ConferenceViewRadioGroup {
    val group = div(className = "lapis-conference-view-group")
    group.setAttribute("role", "radiogroup")
    group.setAttribute("aria-label", gettext("Ansicht"))
    val radios = LinkedHashMap<ConferenceView, Button>()
    val checks = LinkedHashMap<ConferenceView, io.kvision.html.Span>()
    val hintId = "$idPrefix-share-hint"
    val hint =
        group.div(tr("Niemand teilt gerade den Bildschirm"), className = "lapis-conference-view-hint small text-muted") {
            setAttribute("id", hintId)
        }
    lateinit var result: ConferenceViewRadioGroup

    fun handle(view: ConferenceView) {
        if (view == ConferenceView.SCREEN_SHARE && !result.shareActive) return
        onChoose(view)
    }
    for (view in CONFERENCE_VIEW_ORDER) {
        val button = newActionButton(conferenceViewIcon(view), conferenceViewRadioText(view), ButtonStyle.OUTLINESECONDARY)
        button.addCssClass("lapis-conference-view-radio")
        button.setAttribute("role", "radio")
        button.setAttribute("aria-checked", "false")
        button.setAttribute("tabindex", "-1")
        if (view == ConferenceView.SCREEN_SHARE) button.setAttribute("aria-describedby", hintId)
        val check = button.span("✓", className = "lapis-conference-view-check")
        check.setAttribute("aria-hidden", "true")
        check.display = Display.NONE
        button.onClick { handle(view) }
        button.onEvent {
            keydown = { e ->
                val key = (e as? KeyboardEvent)?.key.orEmpty()
                when {
                    key == "Escape" -> {
                        e.preventDefault()
                        onEscape()
                    }
                    else -> {
                        val target = conferenceViewRadioTarget(view, key)
                        if (target != null) {
                            e.preventDefault()
                            result.focus(target)
                        }
                    }
                }
            }
        }
        group.add(button)
        radios[view] = button
        checks[view] = check
    }
    // the hint stands below the radios
    group.remove(hint)
    group.add(hint)
    result = ConferenceViewRadioGroup(group, radios, checks, hint)
    result.update(ConferenceView.GALLERY, shareActive = false)
    return result
}

/**
 * The popover over the bar: the radio group in a small panel above the bar button. Opens on the button, closes on Escape (the focus goes
 * back to the button), on a click outside, and after a choice. [dispose] removes the one window listener.
 */
internal class ConferenceViewPopover(
    callPanel: Container,
    private val trigger: Button,
    onChoose: (ConferenceView) -> Unit,
) {
    private val panel: Div = callPanel.div(className = "lapis-conference-view-popover")
    val group: ConferenceViewRadioGroup
    var isOpen: Boolean = false
        private set

    /** Handle of the deferred listener registration (see [open]); `null` when none is pending. */
    private var pendingListener: Int? = null

    private val outsideClick: (org.w3c.dom.events.Event) -> Unit = { event ->
        val target = event.target as? org.w3c.dom.Node
        val inside = target != null && (panel.getElement()?.contains(target) == true || trigger.getElement()?.contains(target) == true)
        if (!inside) close(restoreFocus = false)
    }

    init {
        panel.hide()
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-label", gettext("Ansicht"))
        group =
            panel.conferenceViewRadioGroup(
                idPrefix = "lapis-view-popover",
                onChoose = { view ->
                    onChoose(view)
                    close(restoreFocus = true)
                },
                onEscape = { close(restoreFocus = true) },
            )
        trigger.setAttribute("aria-haspopup", "true")
        trigger.setAttribute("aria-expanded", "false")
    }

    fun toggle() {
        if (isOpen) close(restoreFocus = true) else open()
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        panel.show()
        trigger.setAttribute("aria-expanded", "true")
        // Registered one task later: when the open is triggered from inside a click (the "Mehr" sheet twin clicks the primary button
        // synchronously), the very click that is still bubbling would otherwise reach this window listener and close the popover again.
        pendingListener =
            kotlinx.browser.window.setTimeout({
                pendingListener = null
                if (isOpen) kotlinx.browser.window.addEventListener("click", outsideClick)
            }, 0)
        // the group is rendered by the next patch; focus the current radio then
        kotlinx.browser.window.setTimeout({ group.focusCurrent() }, 0)
    }

    fun close(restoreFocus: Boolean) {
        if (!isOpen) return
        isOpen = false
        panel.hide()
        trigger.setAttribute("aria-expanded", "false")
        cancelListener()
        if (restoreFocus) (trigger.getElement() as? org.w3c.dom.HTMLElement)?.focus()
    }

    fun dispose() {
        cancelListener()
    }

    private fun cancelListener() {
        pendingListener?.let { kotlinx.browser.window.clearTimeout(it) }
        pendingListener = null
        kotlinx.browser.window.removeEventListener("click", outsideClick)
    }
}
