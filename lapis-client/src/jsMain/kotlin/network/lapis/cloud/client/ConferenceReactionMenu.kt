package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.ConferenceReaction
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/*
 * V1.9.127 -- the reaction menu of the video conference ("Reaktion" button of the bar) and the little DOM pieces of a reaction on a tile.
 *
 * The menu is a popover over the bar like the view switcher, but a real menu: `role="menu"` with eight `role="menuitem"` buttons, each
 * showing the emoji (`aria-hidden`) and the word. Arrow keys / Home / End move, Enter / Space choose (native `<button>`), Escape closes
 * and gives the focus back to the bar button. There is NO global keyboard shortcut. No `addAfterInsertHook`: the widgets are KVision
 * widgets and every attribute goes through the patch cycle.
 *
 * On a tile a reaction is a transient child of a layer that is built ONCE with the tile (the tile element itself is never recreated, see
 * `ConferenceViewStage`): at most [CONFERENCE_REACTION_MAX_VISIBLE] at the same time, [CONFERENCE_REACTION_LIFETIME_MS] each, the word in a
 * visually hidden span, no live region -- a reaction is never announced.
 */

internal const val CONFERENCE_REACTION_MAX_VISIBLE = 3
internal const val CONFERENCE_REACTION_LIFETIME_MS = 3_000
internal const val CONFERENCE_REACTION_LAYER_CLASS = "lapis-conference-reaction-layer"
internal const val CONFERENCE_REACTION_ITEM_CLASS = "lapis-conference-reaction"
internal const val CONFERENCE_HAND_BADGE_CLASS = "lapis-conference-hand-badge"

/** The emoji of a reaction (decorative, always `aria-hidden`). */
internal fun conferenceReactionEmoji(reaction: ConferenceReaction): String =
    when (reaction) {
        ConferenceReaction.APPLAUSE -> "👏"
        ConferenceReaction.HEART -> "❤️"
        ConferenceReaction.AMEN -> "🙏"
        ConferenceReaction.THUMBS_UP -> "👍"
        ConferenceReaction.FLOWER -> "🌷"
        ConferenceReaction.LAUGH -> "😄"
        ConferenceReaction.ASTONISHED -> "😮"
        ConferenceReaction.QUESTION -> "❓"
    }

/** The word of a reaction (the accessible name of the menu item and the hidden text on a tile). */
internal fun conferenceReactionLabel(reaction: ConferenceReaction): String =
    when (reaction) {
        ConferenceReaction.APPLAUSE -> gettext("Applaus")
        ConferenceReaction.HEART -> gettext("Herz")
        ConferenceReaction.AMEN -> gettext("Amen")
        ConferenceReaction.THUMBS_UP -> gettext("Daumen hoch")
        ConferenceReaction.FLOWER -> gettext("Blume")
        ConferenceReaction.LAUGH -> gettext("Lachen")
        ConferenceReaction.ASTONISHED -> gettext("Staunen")
        ConferenceReaction.QUESTION -> gettext("Frage")
    }

private fun conferenceReactionMenuText(reaction: ConferenceReaction): String =
    when (reaction) {
        ConferenceReaction.APPLAUSE -> tr("Applaus")
        ConferenceReaction.HEART -> tr("Herz")
        ConferenceReaction.AMEN -> tr("Amen")
        ConferenceReaction.THUMBS_UP -> tr("Daumen hoch")
        ConferenceReaction.FLOWER -> tr("Blume")
        ConferenceReaction.LAUGH -> tr("Lachen")
        ConferenceReaction.ASTONISHED -> tr("Staunen")
        ConferenceReaction.QUESTION -> tr("Frage")
    }

/** Pure: the index the arrow key [key] moves to from [from] in a menu of [size] items (wrapping); `null` for any other key. */
internal fun conferenceReactionMenuTarget(
    from: Int,
    key: String,
    size: Int,
): Int? {
    if (size <= 0 || from !in 0 until size) return null
    return when (key) {
        "ArrowDown" -> (from + 1) % size
        "ArrowUp" -> (from - 1 + size) % size
        "Home" -> 0
        "End" -> size - 1
        else -> null
    }
}

/**
 * The menu in a popover above the bar. Opens on [trigger], closes on Escape (the focus goes back to [trigger]), on a click outside and
 * after a choice. While [enabled] is `false` the room's reactions are switched off: the trigger carries `aria-disabled`, a click asks
 * [onDisabledClick] (the screen announces the hint) and the menu does not open.
 */
internal class ConferenceReactionPopover(
    callPanel: Container,
    private val trigger: Button,
    private val onChoose: (ConferenceReaction) -> Unit,
    private val onDisabledClick: () -> Unit = {},
) {
    private val panel: Div = callPanel.div(className = "lapis-conference-reaction-popover")
    private val items = LinkedHashMap<ConferenceReaction, Tag>()
    private val order = ConferenceReaction.entries.toList()
    private var pendingListener: Int? = null

    var isOpen: Boolean = false
        private set

    var enabled: Boolean = true
        private set

    private val outsideClick: (org.w3c.dom.events.Event) -> Unit = { event ->
        val target = event.target as? org.w3c.dom.Node
        val inside = target != null && (panel.getElement()?.contains(target) == true || trigger.getElement()?.contains(target) == true)
        if (!inside) close(restoreFocus = false)
    }

    init {
        panel.hide()
        panel.setAttribute("role", "menu")
        panel.setAttribute("aria-label", gettext("Reaktion"))
        for ((index, reaction) in order.withIndex()) {
            val item = panel.tag(TAG.BUTTON, className = "btn btn-outline-secondary lapis-conference-reaction-menuitem")
            item.setAttribute("type", "button")
            item.setAttribute("role", "menuitem")
            item.setAttribute("tabindex", if (index == 0) "0" else "-1")
            item.span(conferenceReactionEmoji(reaction), className = "lapis-conference-reaction-emoji").setAttribute("aria-hidden", "true")
            item.span(conferenceReactionMenuText(reaction), className = "lapis-conference-reaction-word")
            item.onEvent {
                click = {
                    close(restoreFocus = true)
                    onChoose(reaction)
                }
                keydown = { e ->
                    val key = (e as? KeyboardEvent)?.key.orEmpty()
                    if (key == "Escape") {
                        e.preventDefault()
                        close(restoreFocus = true)
                    } else {
                        conferenceReactionMenuTarget(index, key, order.size)?.let { target ->
                            e.preventDefault()
                            focusItem(order[target])
                        }
                    }
                }
            }
            items[reaction] = item
        }
        trigger.setAttribute("aria-haspopup", "menu")
        trigger.setAttribute("aria-expanded", "false")
    }

    fun toggle() {
        if (!enabled) {
            onDisabledClick()
            return
        }
        if (isOpen) close(restoreFocus = true) else open()
    }

    fun open() {
        if (isOpen || !enabled) return
        isOpen = true
        panel.show()
        trigger.setAttribute("aria-expanded", "true")
        // Registered one task later: a click that is still bubbling (the "Mehr" twin clicks the primary synchronously) must not close it again.
        pendingListener =
            window.setTimeout({
                pendingListener = null
                if (isOpen) window.addEventListener("click", outsideClick)
            }, 0)
        window.setTimeout({ focusItem(order.first()) }, 0)
    }

    fun close(restoreFocus: Boolean) {
        if (!isOpen) return
        isOpen = false
        panel.hide()
        trigger.setAttribute("aria-expanded", "false")
        cancelListener()
        if (restoreFocus) (trigger.getElement() as? HTMLElement)?.focus()
    }

    /** The room's reactions were switched on / off. Closes an open menu when they go off. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) trigger.removeAttribute("aria-disabled") else trigger.setAttribute("aria-disabled", "true")
        if (!value) close(restoreFocus = false)
    }

    fun dispose() {
        cancelListener()
    }

    private fun focusItem(reaction: ConferenceReaction) {
        for ((r, button) in items) button.setAttribute("tabindex", if (r == reaction) "0" else "-1")
        val element = items[reaction]?.getElement() as? HTMLElement
        element?.focus()
        // The menu is height-limited and scrolls (V1.9.164): the focused item follows. Guarded: a headless DOM may lack the method.
        if (element != null && jsTypeOf(element.asDynamic().scrollIntoView) == "function") {
            element.asDynamic().scrollIntoView(js("({block: 'nearest'})"))
        }
    }

    private fun cancelListener() {
        pendingListener?.let { window.clearTimeout(it) }
        pendingListener = null
        window.removeEventListener("click", outsideClick)
    }
}

/** The layer of a tile that carries the reactions; built once with the tile, never moved. `aria-hidden`: decorative, no live region. */
internal fun createReactionLayer(): HTMLElement {
    val layer = document.createElement("div") as HTMLElement
    layer.className = CONFERENCE_REACTION_LAYER_CLASS
    layer.setAttribute("aria-hidden", "true")
    return layer
}

/**
 * Shows [reaction] in [layer] for [CONFERENCE_REACTION_LIFETIME_MS]: the emoji, and the word in a visually hidden span (the layer is
 * `aria-hidden`, so nothing is read out). At most [CONFERENCE_REACTION_MAX_VISIBLE] are shown at once -- the oldest makes room. Texts only
 * through `textContent`. [schedule] is the test seam of the removal timer. Returns the element.
 */
internal fun showReactionInLayer(
    layer: HTMLElement,
    reaction: ConferenceReaction,
    schedule: (delayMs: Int, block: () -> Unit) -> Unit = { delayMs, block -> window.setTimeout(block, delayMs) },
): HTMLElement {
    while (layer.childElementCount >= CONFERENCE_REACTION_MAX_VISIBLE) layer.firstElementChild?.let { layer.removeChild(it) } ?: break
    val item = document.createElement("span") as HTMLElement
    item.className = CONFERENCE_REACTION_ITEM_CLASS
    val emoji = document.createElement("span") as HTMLElement
    emoji.className = "lapis-conference-reaction-emoji"
    emoji.textContent = conferenceReactionEmoji(reaction)
    item.appendChild(emoji)
    val word = document.createElement("span") as HTMLElement
    word.className = "visually-hidden"
    word.textContent = conferenceReactionLabel(reaction)
    item.appendChild(word)
    layer.appendChild(item)
    schedule(CONFERENCE_REACTION_LIFETIME_MS) { if (item.parentNode === layer) layer.removeChild(item) }
    return item
}

/**
 * The raised-hand badge of a tile: the symbol AND the words "Hand gehoben" (never colour or symbol alone), built ONCE with the tile and only
 * shown / hidden afterwards ([toggleHandBadge]) -- like the speaking badge. Texts through `textContent`; `gettext`, not `tr()`.
 */
internal fun createHandBadge(): HTMLElement {
    val badge = document.createElement("span") as HTMLElement
    badge.className = CONFERENCE_HAND_BADGE_CLASS
    badge.style.display = "none"
    val icon = document.createElement("i") as HTMLElement
    icon.className = actionIconClasses(ActionIcon.HAND)
    icon.setAttribute("aria-hidden", "true")
    badge.appendChild(icon)
    val text = document.createElement("span") as HTMLElement
    text.className = "lapis-conference-hand-text"
    text.textContent = gettext("Hand gehoben")
    badge.appendChild(text)
    return badge
}

/** Flips only the display of [badge]; no node is added, removed or moved. */
internal fun toggleHandBadge(
    badge: HTMLElement,
    on: Boolean,
) {
    badge.style.display = if (on) "inline-flex" else "none"
}
