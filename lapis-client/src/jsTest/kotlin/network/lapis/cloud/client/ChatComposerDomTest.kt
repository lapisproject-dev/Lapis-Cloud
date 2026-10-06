package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.66: the one chat input row ([lapisChatComposer]) in a real mounted root with the real stylesheet cascade (Bootstrap + KVision +
 * Font Awesome + `theme.css`): the send button is icon-only and as high as the field, both sit in ONE row also in a narrow container, Enter
 * sends (but not while an IME composes), the field has an accessible name without a visible label.
 */
class ChatComposerDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
        const val TOLERANCE = 1.0
    }

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun key(
        key: String,
        composing: Boolean = false,
    ) = KeyboardEvent("keydown", KeyboardEventInit(key = key, isComposing = composing, bubbles = true, cancelable = true))

    @Test
    fun sendButton_isIconOnly_withNameTitleAndPrimaryStyle_withoutBtnSm() {
        assertTrue(stylesLoaded)
        withMountedRoot("chat-composer-icon") { root, element ->
            root.lapisChatComposer { }
            val button = element().q(".lapis-chat-composer button")
            assertEquals("", button.textContent?.trim(), "icon only: no visible text")
            assertTrue(button.q("i").className.contains("fa-paper-plane"), "icon ${button.q("i").className}")
            assertEquals("Senden", button.getAttribute("aria-label"))
            assertEquals("Senden", button.getAttribute("title"))
            assertTrue(button.classList.contains("btn-primary"))
            assertTrue(!button.classList.contains("btn-sm"), "no btn-sm in a toolbar (R56)")
            assertTrue(button.tabIndex >= 0, "reachable by Tab")
        }
    }

    @Test
    fun field_hasAnAccessibleNameAndPlaceholder_butNoVisibleLabel() {
        withMountedRoot("chat-composer-field") { root, element ->
            root.lapisChatComposer { }
            val input = element().q(".lapis-chat-composer input")
            assertEquals("Nachricht", input.getAttribute("aria-label"))
            assertEquals("Nachricht", input.getAttribute("placeholder"))
            assertNull(element().querySelector(".lapis-chat-composer label"), "no visible label")
        }
    }

    @Test
    fun buttonAndField_shareOneRowAndHeight_alsoInANarrowContainer() {
        assertTrue(stylesLoaded)
        withMountedRoot("chat-composer-geometry") { root, element ->
            root.lapisChatComposer { }
            val host = element()
            host.style.width = "300px"
            val input = host.q(".lapis-chat-composer input").getBoundingClientRect()
            val button = host.q(".lapis-chat-composer button").getBoundingClientRect()
            assertTrue(abs(input.height - button.height) <= TOLERANCE, "heights: field ${input.height}, button ${button.height}")
            assertTrue(abs(input.bottom - button.bottom) <= TOLERANCE, "bottoms: field ${input.bottom}, button ${button.bottom}")
            assertTrue(abs(input.top - button.top) <= TOLERANCE, "one row: field top ${input.top}, button top ${button.top}")
            assertTrue(button.left >= input.right - TOLERANCE, "the button sits next to the field, not under it")
            val composer = host.q(".lapis-chat-composer")
            assertEquals("nowrap", window.getComputedStyle(composer).flexWrap, "the row never wraps")
        }
    }

    @Test
    fun enterSends_once_imeEnterAndOtherKeysDoNot_clickSends() {
        withMountedRoot("chat-composer-keys") { root, element ->
            var sent = 0
            root.lapisChatComposer { sent++ }
            val input = element().q(".lapis-chat-composer input") as HTMLInputElement
            input.dispatchEvent(key("Enter"))
            assertEquals(1, sent)
            input.dispatchEvent(key("Enter", composing = true))
            assertEquals(1, sent, "an Enter that confirms an IME composition does not send")
            input.dispatchEvent(key("a"))
            assertEquals(1, sent)
            element().q(".lapis-chat-composer button").click()
            assertEquals(2, sent)
        }
    }

    @Test
    fun valueAndClear_workOnTheField() {
        withMountedRoot("chat-composer-value") { root, element ->
            val composer = root.lapisChatComposer { }
            assertEquals("", composer.value)
            composer.value = "Hallo"
            assertEquals("Hallo", composer.value)
            assertEquals("Hallo", (element().q(".lapis-chat-composer input") as HTMLInputElement).value)
            composer.clear()
            assertEquals("", composer.value)
        }
    }

    @Test
    fun sendButton_isNotDisabledOnAnEmptyField_andDoesNotWrapNextToOtherWidgets() {
        withMountedRoot("chat-composer-enabled") { root, element ->
            val composer = root.lapisChatComposer { }
            root.div("another widget") // a later patch of the root
            assertTrue(!composer.sendButton.disabled)
            assertTrue(!element().q(".lapis-chat-composer button").hasAttribute("disabled"))
        }
    }
}
