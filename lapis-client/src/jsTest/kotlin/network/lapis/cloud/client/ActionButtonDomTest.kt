package network.lapis.cloud.client

import io.kvision.html.button
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.43 (R57/R58): the icon is decorative, the accessible name stays the label, the height does not change. */
class ActionButtonDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun HTMLElement.q(selector: String) = querySelector(selector) as HTMLElement

    @Test
    fun icon_isPresentAndHiddenFromAssistiveTechnology_textUnchanged() {
        assertTrue(stylesLoaded)
        withMountedRoot("action-button-icon") { root, element ->
            root.actionButton(ActionIcon.REFRESH, "Aktualisieren")
            val btn = element().q("button")
            val icon = btn.q("i")
            assertTrue(icon.className.contains("fa-arrows-rotate"), "icon class ${icon.className}")
            assertEquals("true", icon.getAttribute("aria-hidden"), "decorative icon must be aria-hidden; html=${btn.innerHTML}")
            assertEquals("Aktualisieren", btn.textContent?.trim())
            assertEquals(null, btn.getAttribute("aria-label"), "visible text IS the accessible name")
            root.actionButton(ActionIcon.SAVE, "Speichern") // another patch of the same root
            assertEquals("true", element().q("button i").getAttribute("aria-hidden"), "aria-hidden survives a later patch")
        }
    }

    @Test
    fun iconOnly_setsTitleAndAriaLabel() {
        withMountedRoot("action-button-icon-only") { root, element ->
            root.actionButton(ActionIcon.DELETE, "Löschen", iconOnly = true)
            val btn = element().q("button")
            assertEquals("Löschen", btn.getAttribute("aria-label"))
            assertEquals("Löschen", btn.getAttribute("title"))
            assertEquals("", btn.textContent?.trim())
            assertTrue(btn.classList.contains("btn-sm"))
        }
    }

    @Test
    fun blankLabel_isRejected() {
        withMountedRoot("action-button-blank") { root, _ ->
            assertFailsWith<IllegalArgumentException> { root.actionButton(ActionIcon.EDIT, " ", iconOnly = true) }
        }
    }

    @Test
    fun heightWithIcon_equalsHeightWithout() {
        assertTrue(stylesLoaded)
        withMountedRoot("action-button-height") { root, element ->
            root.button("Plain")
            root.actionButton(ActionIcon.SAVE, "Speichern")
            val heights =
                (0 until element().querySelectorAll("button").length).map {
                    (element().querySelectorAll("button").item(it) as HTMLElement).getBoundingClientRect().height
                }
            assertEquals(heights[0], heights[1], "icon must not change the button height")
        }
    }

    @Test
    fun disabledAndClick_behaveLikeAPlainButton() {
        withMountedRoot("action-button-click") { root, element ->
            var clicks = 0
            val b = root.actionButton(ActionIcon.ADD, "Hinzufügen")
            b.onClick { clicks++ }
            element().q("button").click()
            assertEquals(1, clicks)
            b.disabled = true
            assertTrue(element().q("button").hasAttribute("disabled"))
        }
    }

    @Test
    fun everyIconNameExistsInTheBundledFontAwesome() {
        assertTrue(stylesLoaded)
        val css =
            (0 until document.styleSheets.length).joinToString("\n") { i ->
                runCatching {
                    val rules =
                        document.styleSheets
                            .item(i)!!
                            .asDynamic()
                            .cssRules
                    (0 until (rules.length as Int)).joinToString("\n") { r -> rules[r].selectorText?.toString() ?: "" }
                }.getOrDefault("")
            }
        ActionIcon.entries.forEach { kind ->
            val name = kind.css.split(" ").first { it.startsWith("fa-") }
            assertNotNull(Regex("\\.${Regex.escape(name)}(::|:|,|\\s|$)").find(css), "Font Awesome has no $name (${kind.name})")
        }
    }
}
