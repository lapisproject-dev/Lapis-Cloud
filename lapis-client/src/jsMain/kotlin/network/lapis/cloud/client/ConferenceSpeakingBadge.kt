package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/*
 * V1.9.85 -- the DOM side of the active-speaker mark, shared by the grid tile and the cells of the floating window. The state itself
 * (who is marked) lives in [SpeakingMarkState]; here is only the one plaque and the one toggle.
 */

internal const val SPEAKING_TILE_CLASS = "lapis-conference-tile--speaking"
internal const val SPEAKING_BADGE_CLASS = "lapis-conference-speaking-badge"
internal const val SPEAKING_TEXT_CLASS = "lapis-conference-speaking-text"

/**
 * The plaque: symbol and the word "spricht" (never colour alone). Built once, hidden. Purely decorative for assistive technology
 * (`aria-hidden`, no role, no live region): the tile's accessible name is untouched. `gettext`, not `tr()`: a `textContent` write
 * would show the KVision i18n marker. Texts only through `textContent`.
 */
internal fun createSpeakingBadge(): HTMLElement {
    val badge = document.createElement("span") as HTMLElement
    badge.className = SPEAKING_BADGE_CLASS
    badge.setAttribute("aria-hidden", "true")
    badge.style.display = "none"
    val icon = document.createElement("i") as HTMLElement
    icon.className = ActionIcon.SPEAKING.css
    icon.setAttribute("aria-hidden", "true")
    badge.appendChild(icon)
    val text = document.createElement("span") as HTMLElement
    text.className = SPEAKING_TEXT_CLASS
    text.textContent = gettext("spricht")
    badge.appendChild(text)
    return badge
}

/** Flips only a class on [host] and the display of [badge]; no node is added, removed or moved. */
internal fun toggleSpeakingMark(
    host: HTMLElement,
    badge: HTMLElement,
    on: Boolean,
) {
    host.classList.toggle(SPEAKING_TILE_CLASS, on)
    badge.style.display = if (on) "inline-flex" else "none"
}
