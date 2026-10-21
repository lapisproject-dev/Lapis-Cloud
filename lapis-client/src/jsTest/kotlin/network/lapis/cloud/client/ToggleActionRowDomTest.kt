package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.180 -- the row helper: status text left, exactly the applicable button (or none) right. */
class ToggleActionRowDomTest {
    private fun HTMLElement.buttonTexts(): List<String> = allOf("button").map { it.textContent?.trim().orEmpty() }

    private suspend fun render(
        id: String,
        state: ToggleLoadState,
        check: (HTMLElement, io.kvision.panel.SimplePanel) -> Unit,
    ) {
        mountedForm(id) { root, element ->
            val host = root.div()
            renderToggleActionRow(
                container = host,
                state = state,
                enable = { it.button("Foo aktivieren", style = ButtonStyle.PRIMARY) },
                disable = { it.button("Foo deaktivieren", style = ButtonStyle.OUTLINEDANGER) },
            )
            awaitUntil("the row is rendered") { element().querySelector(".toggle-status") != null }
            check(element(), host)
        }
    }

    @Test
    fun enabled_hasTheDisableButtonOnly(): Promise<Unit> =
        formTest {
            render("toggle-row-enabled", ToggleLoadState.ENABLED) { el, _ ->
                assertEquals(listOf("Foo deaktivieren"), el.buttonTexts())
                assertEquals("Status: Aktiviert", el.querySelector(".toggle-status")?.textContent?.trim())
            }
        }

    @Test
    fun disabled_hasTheEnableButtonOnly(): Promise<Unit> =
        formTest {
            render("toggle-row-disabled", ToggleLoadState.DISABLED) { el, _ ->
                assertEquals(listOf("Foo aktivieren"), el.buttonTexts())
                assertEquals("Status: Deaktiviert", el.querySelector(".toggle-status")?.textContent?.trim())
            }
        }

    @Test
    fun loading_hasNoButton(): Promise<Unit> =
        formTest {
            render("toggle-row-loading", ToggleLoadState.LOADING) { el, _ ->
                assertEquals(emptyList(), el.buttonTexts())
                assertEquals("Wird geladen …", el.querySelector(".toggle-status")?.textContent?.trim())
            }
        }

    @Test
    fun failed_hasNoButtonAndADangerLine(): Promise<Unit> =
        formTest {
            render("toggle-row-failed", ToggleLoadState.FAILED) { el, _ ->
                assertEquals(emptyList(), el.buttonTexts())
                val status = assertNotNull(el.querySelector(".toggle-status") as? HTMLElement)
                assertEquals("Einstellungen konnten nicht geladen werden.", status.textContent?.trim())
                assertTrue(status.classList.contains("text-danger"))
            }
        }

    @Test
    fun theOtherButtonIsNotInTheDom_notHiddenNotDisabled(): Promise<Unit> =
        formTest {
            render("toggle-row-absent", ToggleLoadState.ENABLED) { el, _ ->
                assertTrue(!el.textContent.orEmpty().contains("Foo aktivieren"), "the enable button must not exist at all")
                assertEquals(1, el.allOf("button").size)
            }
        }

    @Test
    fun statusIsALiveRegionAndProgrammaticallyFocusable(): Promise<Unit> =
        formTest {
            render("toggle-row-focus", ToggleLoadState.ENABLED) { el, _ ->
                val status = assertNotNull(el.querySelector(".toggle-status") as? HTMLElement)
                assertEquals("status", status.getAttribute("role"))
                assertEquals("-1", status.getAttribute("tabindex"))
            }
        }

    @Test
    fun focusToggleStatus_movesTheFocusToTheStatusText(): Promise<Unit> =
        formTest {
            mountedForm("toggle-row-focus-move") { root, element ->
                val host = root.div()
                renderToggleActionRow(host, ToggleLoadState.DISABLED, { it.button("A") }, { it.button("B") })
                awaitUntil("rendered") { element().querySelector(".toggle-status") != null }
                focusToggleStatus(host)
                awaitUntil("the status text has the focus") {
                    (document.activeElement as? HTMLElement)?.classList?.contains("toggle-status") == true
                }
            }
        }

    @Test
    fun rerender_replacesTheRowInsteadOfAddingOne(): Promise<Unit> =
        formTest {
            mountedForm("toggle-row-rerender") { root, element ->
                val host = root.div()
                renderToggleActionRow(host, ToggleLoadState.ENABLED, { it.button("Foo aktivieren") }, { it.button("Foo deaktivieren") })
                renderToggleActionRow(host, ToggleLoadState.DISABLED, { it.button("Foo aktivieren") }, { it.button("Foo deaktivieren") })
                awaitUntil("the second row") { element().buttonTexts() == listOf("Foo aktivieren") }
                assertEquals(1, element().allOf(".toggle-status").size)
            }
        }
}
