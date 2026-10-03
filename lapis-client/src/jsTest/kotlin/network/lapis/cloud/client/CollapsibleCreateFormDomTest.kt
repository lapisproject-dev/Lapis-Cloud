package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.40 -- the collapsible create form (R36B), driven like a person does: a real mounted header with its action slot, a real form
 * from the form grammar, real clicks and `Escape` keydowns. The component is tested on its own here; the four pilot screens are in
 * [CollapsibleCreateFormScreensDomTest].
 */
class CollapsibleCreateFormDomTest {
    private val formId = "lapis-create-test"

    /** What the little test form saved, in order. */
    private val saved = mutableListOf<String>()

    private class Harness(
        val controller: CollapsibleCreateFormController<String>,
        val host: SimplePanel,
    )

    private fun SimplePanel.testForm(
        prefill: String?,
        close: (Boolean) -> Unit,
    ): FormSnapshot {
        val form = lapisForm()
        val nameField = form.textField(label = "Name", value = prefill.orEmpty(), required = true)
        val saveButton = Button("Anlegen", style = ButtonStyle.PRIMARY)
        form.buttons(primary = saveButton, cancel = collapseCancelButton(close))
        saveButton.onClick {
            form.submit(saveButton) {
                saved += nameField.value.trim()
                close(true)
            }
        }
        return form.snapshot()
    }

    private fun mountHarness(root: io.kvision.panel.Root): Harness {
        val header = root.pageHeader("Testseite")
        val host = root.vPanel(spacing = 6)
        val controller =
            collapsibleCreateForm<String>(
                actionSlot = header.actionSlot,
                formHost = host,
                buttonLabel = "Neues Ding",
                formId = formId,
            ) { prefill, close -> testForm(prefill, close) }
        return Harness(controller, host)
    }

    private fun HTMLElement.host(): HTMLElement = assertNotNull(querySelector("[id='$formId']") as? HTMLElement, "no form host")

    private fun HTMLElement.hasForm(): Boolean = querySelector("[id='$formId'] .lapis-form") != null

    private fun keydown(
        target: HTMLElement,
        key: String,
    ) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
    }

    private fun HTMLElement.input(): HTMLInputElement = controlOf("Name") as HTMLInputElement

    private suspend fun awaitClosed(root: HTMLElement) = awaitUntil("the form is closed again") { !root.hasForm() }

    private suspend fun awaitDialog(): HTMLElement {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        return lastOpenModal()
    }

    @Test
    fun afterTheLoad_itIsCollapsed_andTheButtonSitsInTheTitleRowsActionSlot(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-collapsed") { root, element ->
                mountHarness(root)
                val screen = element()
                val button = createFormButton(screen, formId)
                assertEquals("Neues Ding", button.textContent?.trim())
                assertNotNull(button.closest(".lapis-page-header .lapis-page-action"), "the button is the title row's action")
                assertEquals("false", button.getAttribute("aria-expanded"))
                assertNotNull(document.getElementById(formId), "aria-controls points at a real element, also while collapsed")
                assertFalse(screen.hasForm(), "no form is built before the button is pressed")
                assertEquals("true", button.querySelector(".fa-plus")?.getAttribute("aria-hidden"), "the plus glyph is decoration")
            }
        }

    @Test
    fun opening_hidesTheButtonInPlace_andPutsTheFocusIntoTheFirstField(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-open") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                val button = createFormButton(screen, formId)
                awaitUntil("the button is hidden but keeps its place") { button.style.visibility == "hidden" }
                assertEquals("true", button.getAttribute("aria-hidden"))
                assertEquals("-1", button.getAttribute("tabindex"), "a hidden button is not a tab stop")
                assertEquals("true", button.getAttribute("aria-expanded"))
                awaitUntil("the first field holds the focus") { document.activeElement == screen.input() }
            }
        }

    @Test
    fun cancelOnAnUnchangedForm_closesAtOnce_andTheFocusReturnsToTheButton(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-cancel") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                screen.buttonNamed("Abbrechen").click()
                awaitClosed(screen)
                assertTrue(document.querySelector(".modal.show") == null, "an unchanged form is closed without asking")
                val button = createFormButton(screen, formId)
                awaitUntil("the button is back") { button.style.visibility != "hidden" && button.getAttribute("aria-hidden") == null }
                assertEquals("false", button.getAttribute("aria-expanded"))
                assertEquals(null, button.getAttribute("tabindex"))
                awaitUntil("the focus returned to the button") { document.activeElement == button }
            }
        }

    @Test
    fun aDoubleClick_buildsExactlyOneForm(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-double") { root, element ->
                mountHarness(root)
                val screen = element()
                val button = createFormButton(screen, formId)
                button.click()
                button.click()
                awaitUntil("a form is built") { screen.hasForm() }
                delay(150)
                assertEquals(1, screen.allOf("[id='$formId'] .lapis-form").size)
                assertEquals(1, screen.allOf("[id='$formId'] input[type=text]").size, "one set of fields, not two")
            }
        }

    @Test
    fun escapeOnAnUnchangedForm_closesAtOnce(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-escape-clean") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                keydown(screen.input(), "Escape")
                awaitClosed(screen)
                assertTrue(document.querySelector(".modal.show") == null)
            }
        }

    @Test
    fun escapeOnAChangedForm_asksFirst_keepEditingKeepsTheInput_discardClosesAndEmpties(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-escape-dirty") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                screen.typeInto("Name", "Halbfertig")
                keydown(screen.input(), "Escape")
                val dialog = awaitDialog()
                assertEquals("Eingaben verwerfen?", dialog.querySelector(".modal-title")?.textContent?.trim())
                dialog.buttonNamed("Weiter bearbeiten").click()
                awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                assertTrue(screen.hasForm(), "keep editing leaves the form open")
                assertEquals("Halbfertig", screen.input().value, "and the typed text intact")

                screen.buttonNamed("Abbrechen").click()
                awaitDialog().buttonNamed("Verwerfen").click()
                awaitClosed(screen)
                openCreateForm(screen, formId)
                assertEquals("", screen.input().value, "a discarded input does not come back")
            }
        }

    @Test
    fun typingAndDeletingTheSameTextAgain_isUnchanged(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-revert") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                screen.typeInto("Name", "abc")
                screen.typeInto("Name", "")
                keydown(screen.input(), "Escape")
                awaitClosed(screen)
                assertTrue(document.querySelector(".modal.show") == null, "the comparison is by value, not by a dirty flag")
            }
        }

    @Test
    fun open_withAPrefill_fillsTheForm_andTheGuardAppliesToAnOpenChangedForm(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-prefill") { root, element ->
                val harness = mountHarness(root)
                val screen = element()
                harness.controller.open("Vorlage")
                awaitUntil("the prefilled form is built") { screen.hasForm() }
                assertEquals("Vorlage", screen.input().value)
                assertTrue(harness.controller.isOpen)
                // An unchanged prefilled form closes without a question.
                screen.buttonNamed("Abbrechen").click()
                awaitClosed(screen)
                assertTrue(document.querySelector(".modal.show") == null, "the prefill itself is not a change")

                harness.controller.open("Eins")
                awaitUntil("built") { screen.hasForm() }
                // open() without a prefill on an open form does nothing.
                harness.controller.open()
                assertEquals("Eins", screen.input().value)
                screen.typeInto("Name", "Eins geändert")
                harness.controller.open("Zwei")
                awaitDialog().buttonNamed("Verwerfen").click()
                awaitUntil("rebuilt with the new prefill") { screen.hasForm() && screen.input().value == "Zwei" }
                assertEquals(1, screen.allOf("[id='$formId'] .lapis-form").size)
            }
        }

    @Test
    fun saving_closesWithoutAsking_andTheScreenIsReadyForTheNextOne(): Promise<Unit> =
        formTest {
            saved.clear()
            mountedForm("collapsible-save") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                screen.typeInto("Name", "  Neu  ")
                screen.buttonNamed("Anlegen").click()
                awaitUntil("saved") { saved == listOf("Neu") }
                awaitClosed(screen)
                assertTrue(document.querySelector(".modal.show") == null, "no question after a save")
                assertEquals("false", createFormButton(screen, formId).getAttribute("aria-expanded"))
            }
        }

    @Test
    fun anInvalidSave_leavesTheFormOpen(): Promise<Unit> =
        formTest {
            saved.clear()
            mountedForm("collapsible-invalid") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                screen.buttonNamed("Anlegen").click()
                delay(200)
                assertTrue(screen.hasForm(), "the required name is missing: the form stays open")
                assertTrue(saved.isEmpty())
            }
        }

    @Test
    fun rebaseline_makesALateProgrammaticValueTheUnchangedState(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-rebaseline") { root, element ->
                val header = root.pageHeader("Testseite")
                val host = root.vPanel(spacing = 6)
                collapsibleCreateForm<Unit>(header.actionSlot, host, "Neu", formId) { _, close ->
                    val form = lapisForm()
                    val field = form.textField(label = "Name")
                    form.buttons(primary = Button("Anlegen", style = ButtonStyle.PRIMARY), cancel = collapseCancelButton(close))
                    val snapshot = form.snapshot()
                    // The late fill of a picker (options arrive from the server and the first one is preselected).
                    field.setValue("vorbelegt")
                    snapshot.rebaseline()
                    snapshot
                }
                val screen = element()
                openCreateForm(screen, formId)
                keydown(screen.input(), "Escape")
                awaitClosed(screen)
                assertTrue(document.querySelector(".modal.show") == null, "a preselected value is not an edit")
            }
        }

    @Test
    fun whatIsTyped_neverLandsInADataAttributeOrInStorage(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-privacy") { root, element ->
                mountHarness(root)
                val screen = element()
                openCreateForm(screen, formId)
                val secret = "Geheimer-Entwurf-4711"
                screen.typeInto("Name", secret)
                delay(100)
                val inDataAttributes =
                    document.body!!.allOf("*").any { node ->
                        (0 until node.attributes.length).any { i ->
                            val attribute = node.attributes.item(i)!!
                            attribute.name.startsWith("data-") && attribute.value.contains(secret)
                        }
                    }
                assertFalse(inDataAttributes, "no data-* attribute holds the typed text")
                val inStorage =
                    (0 until localStorage.length).any { localStorage.getItem(localStorage.key(it)!!)?.contains(secret) == true } ||
                        (0 until sessionStorage.length).any { sessionStorage.getItem(sessionStorage.key(it)!!)?.contains(secret) == true }
                assertFalse(inStorage, "nothing is stored")
            }
        }

    @Test
    fun atPhoneWidth_theFormCausesNoHorizontalOverflow(): Promise<Unit> =
        formTest {
            mountedForm("collapsible-narrow") { root, element ->
                mountHarness(root)
                val screen = element()
                screen.style.width = "360px"
                screen.style.boxSizing = "border-box"
                openCreateForm(screen, formId)
                awaitUntil("laid out") { screen.host().offsetHeight > 0 }
                assertTrue(screen.scrollWidth <= 360, "scrollWidth ${screen.scrollWidth} exceeds 360 px")
            }
        }
}
