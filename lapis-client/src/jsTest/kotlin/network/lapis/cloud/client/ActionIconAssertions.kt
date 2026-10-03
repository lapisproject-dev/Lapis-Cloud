package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * V1.9.50: shared assertions of the R57 icon tests. A verb button keeps its visible text (the accessible name does not change), carries ONE
 * decorative icon of its verb (`aria-hidden`, the class of the `ActionIcon`), and is a button that has the icon as its first child.
 */

/** The `ActionIcon` class (`fa-floppy-disk`, ...) is on the decorative icon of [button]; the button's text is exactly [name]. */
internal fun assertActionIcon(
    button: HTMLElement,
    name: String,
    iconClass: String,
) {
    assertEquals(name, button.textContent?.trim(), "the accessible name of '$name' must not change")
    val icon = assertNotNull(button.querySelector("i") as? HTMLElement, "'$name' has no icon")
    assertEquals("true", icon.getAttribute("aria-hidden"), "the icon of '$name' is decoration")
    assertTrue(icon.classList.contains(iconClass), "'$name': expected $iconClass, got ${icon.className}")
}

/** [assertActionIcon] for the button of [root] named [name]. */
internal fun assertButtonIcon(
    root: HTMLElement,
    name: String,
    iconClass: String,
) = assertActionIcon(root.buttonNamed(name), name, iconClass)
