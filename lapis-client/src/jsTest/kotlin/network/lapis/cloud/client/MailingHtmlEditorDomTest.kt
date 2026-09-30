package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.Root
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.15 Teil A -- the WYSIWYG editor in a real mounted tree (Karma/Chrome): ARIA structure, roving toolbar, plain-text
 * paste, refused drops, the link form, text that survives a patch of the root, and an empty editor blocking a form submit.
 */
class MailingHtmlEditorDomTest {
    private fun HTMLElement.toolbar(): HTMLElement = assertNotNull(querySelector("[role=toolbar]") as? HTMLElement, "no toolbar")

    private fun HTMLElement.toolButtons(): List<HTMLElement> = toolbar().allOf("button")

    private fun HTMLElement.textbox(): HTMLElement = assertNotNull(querySelector("[role=textbox]") as? HTMLElement, "no textbox")

    private fun mountedEditor(
        id: String,
        block: suspend (MailingHtmlEditor, Root, () -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            mountedForm(id) { root, element ->
                val editor = MailingHtmlEditor(root, tr("Text"), subjectProvider = { "Betreff" })
                block(editor, root, element)
            }
        }

    private fun select(
        element: HTMLElement,
        textNodeContent: String,
    ) {
        val selection = document.asDynamic().getSelection()
        val range = document.createRange()
        var node: dynamic = element.asDynamic().firstChild
        while (node != null && node.nodeType != 3.toShort() && node.firstChild != null) node = node.firstChild
        assertNotNull(node, "no text node to select in '$textNodeContent'")
        range.selectNodeContents(node.unsafeCast<org.w3c.dom.Node>())
        selection.removeAllRanges()
        selection.addRange(range)
    }

    @Test
    fun toolbar_hasNineButtons_eachWithTitleAriaLabelAndAriaPressed(): Promise<Unit> =
        mountedEditor("mhe-toolbar") { _, _, element ->
            val toolbar = element().toolbar()
            assertEquals("Textformat", toolbar.getAttribute("aria-label"))
            val buttons = element().toolButtons()
            assertEquals(9, buttons.size)
            buttons.forEach { button ->
                assertFalse(button.getAttribute("title").isNullOrBlank(), "title: ${button.outerHTML}")
                assertFalse(button.getAttribute("aria-label").isNullOrBlank(), "aria-label: ${button.outerHTML}")
                assertNotNull(button.getAttribute("aria-pressed"), "aria-pressed: ${button.outerHTML}")
                assertFalse(button.getAttribute("title")!!.contains("###"), "a tr() marker leaked into the title")
                assertFalse(button.getAttribute("aria-label")!!.contains("###"), "a tr() marker leaked into the aria-label")
            }
        }

    @Test
    fun toolbar_isASingleTabStop_andArrowKeysMoveFocus(): Promise<Unit> =
        mountedEditor("mhe-roving") { _, _, element ->
            val buttons = element().toolButtons()
            assertEquals(listOf("0") + List(8) { "-1" }, buttons.map { it.getAttribute("tabindex") })
            buttons[0].focus()
            buttons[0].dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowRight", bubbles = true, cancelable = true)))
            assertEquals("0", element().toolButtons()[1].getAttribute("tabindex"))
            assertEquals("-1", element().toolButtons()[0].getAttribute("tabindex"))
            assertEquals(element().toolButtons()[1], document.activeElement)
            element().toolButtons()[1].dispatchEvent(
                KeyboardEvent("keydown", KeyboardEventInit(key = "End", bubbles = true, cancelable = true)),
            )
            assertEquals(element().toolButtons()[8], document.activeElement)
            element().toolButtons()[8].dispatchEvent(
                KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowRight", bubbles = true, cancelable = true)),
            )
            assertEquals(element().toolButtons()[0], document.activeElement, "wraps around")
            element().toolButtons()[0].dispatchEvent(
                KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowLeft", bubbles = true, cancelable = true)),
            )
            assertEquals(element().toolButtons()[8], document.activeElement)
        }

    @Test
    fun textbox_isALabelledMultilineTextbox(): Promise<Unit> =
        mountedEditor("mhe-aria") { _, _, element ->
            val box = element().textbox()
            assertEquals("true", box.getAttribute("contenteditable"))
            assertEquals("true", box.getAttribute("aria-multiline"))
            val labelId = assertNotNull(box.getAttribute("aria-labelledby"))
            val label = assertNotNull(document.getElementById(labelId), "label element")
            assertTrue(label.textContent.orEmpty().contains("Text"))
        }

    @Test
    fun boldButton_wrapsTheSelection_andSetsAriaPressed(): Promise<Unit> =
        mountedEditor("mhe-bold") { editor, _, element ->
            val box = element().textbox()
            box.innerHTML = "<p>Hallo</p>"
            box.focus()
            select(box, "Hallo")
            element().toolButtons()[0].click()
            assertTrue(
                box.innerHTML.contains("<b>") || box.innerHTML.contains("<strong>"),
                "bold markup expected, got: ${box.innerHTML}",
            )
            assertEquals("true", element().toolButtons()[0].getAttribute("aria-pressed"))
            // The stored form is the server's tag, whatever this engine emitted.
            assertTrue(editor.html().contains("<strong>Hallo</strong>"), editor.html())
            assertFalse(editor.html().contains("<b>"))
        }

    @Test
    fun paste_insertsPlainTextOnly_neverForeignMarkup(): Promise<Unit> =
        mountedEditor("mhe-paste") { editor, _, element ->
            val box = element().textbox()
            box.innerHTML = "<p>x</p>"
            box.focus()
            select(box, "x")
            val transfer = js("new DataTransfer()")
            transfer.setData("text/html", "<b style=\"color:red\">FREMD</b><script>alert(1)</script>")
            transfer.setData("text/plain", "Hallo <i>Welt</i>")
            val init = js("({})")
            init.clipboardData = transfer
            init.bubbles = true
            init.cancelable = true
            val event = js("new ClipboardEvent('paste', init)").unsafeCast<Event>()
            box.dispatchEvent(event)
            assertTrue(event.defaultPrevented, "the browser's own paste must be cancelled")
            val html = box.innerHTML
            assertTrue(html.contains("Hallo &lt;i&gt;Welt&lt;/i&gt;"), "plain text inserted escaped: $html")
            assertFalse(html.contains("FREMD"), "the html flavour must be ignored: $html")
            assertFalse(html.contains("<script"), html)
            assertFalse(html.contains("style="), html)
            assertFalse(editor.html().contains("<i>"), "pasted angle brackets are text, not markup: ${editor.html()}")
        }

    @Test
    fun drop_isRefused(): Promise<Unit> =
        mountedEditor("mhe-drop") { _, _, element ->
            val box = element().textbox()
            val drop = Event("drop", js("({bubbles: true, cancelable: true})").unsafeCast<dynamic>())
            box.dispatchEvent(drop)
            assertTrue(drop.defaultPrevented)
            val over = Event("dragover", js("({bubbles: true, cancelable: true})").unsafeCast<dynamic>())
            box.dispatchEvent(over)
            assertTrue(over.defaultPrevented)
        }

    @Test
    fun linkForm_rejectsAnUnsafeScheme_andCreatesNoLink(): Promise<Unit> =
        mountedEditor("mhe-link") { editor, _, element ->
            val box = element().textbox()
            box.innerHTML = "<p>Hallo</p>"
            box.focus()
            select(box, "Hallo")
            // button 8 of 9 = "Link einfügen"
            element().toolButtons()[7].click()
            val address = element().controlOf("Adresse")
            (address.asDynamic()).value = "ftp://x.example"
            address.dispatchEvent(Event("input"))
            element().buttonNamed("Übernehmen").click()
            assertTrue(
                element().shownErrors().any { it.contains("Nur Adressen mit https://, http:// oder mailto: sind erlaubt.") },
                "error expected, got ${element().shownErrors()}",
            )
            assertFalse(box.innerHTML.contains("<a"), box.innerHTML)
            assertFalse(editor.html().contains("<a"))
        }

    @Test
    fun linkForm_createsALinkForAValidAddress(): Promise<Unit> =
        mountedEditor("mhe-link-ok") { editor, _, element ->
            val box = element().textbox()
            box.innerHTML = "<p>Hallo</p>"
            box.focus()
            select(box, "Hallo")
            element().toolButtons()[7].click()
            val address = element().controlOf("Adresse")
            (address.asDynamic()).value = "https://example.org/ziel"
            address.dispatchEvent(Event("input"))
            element().buttonNamed("Übernehmen").click()
            assertTrue(editor.html().contains("<a href=\"https://example.org/ziel\">Hallo</a>"), editor.html())
        }

    @Test
    fun ctrlK_opensTheLinkForm(): Promise<Unit> =
        mountedEditor("mhe-ctrlk") { _, _, element ->
            val box = element().textbox()
            val linkPanelBefore = element().allOf("label").any { it.textContent.orEmpty().contains("Adresse") && it.offsetParent != null }
            assertFalse(linkPanelBefore, "the link form starts hidden")
            box.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "k", ctrlKey = true, bubbles = true, cancelable = true)))
            val visible = element().allOf("label").any { it.textContent.orEmpty().contains("Adresse") && it.offsetParent != null }
            assertTrue(visible, "Ctrl+K shows the address field")
        }

    @Test
    fun typedText_survivesAPatchOfTheRoot(): Promise<Unit> =
        mountedEditor("mhe-patch") { editor, root, element ->
            val box = element().textbox()
            box.innerHTML = "<p>Bleibt erhalten</p>"
            root.add(io.kvision.html.Div("Noch ein Element"))
            assertEquals("<p>Bleibt erhalten</p>", element().textbox().innerHTML)
            assertTrue(editor.html().contains("Bleibt erhalten"))
            assertFalse(editor.isBlank())
        }

    @Test
    fun emptyEditor_blocksASubmit_withTheEnterATextMessage(): Promise<Unit> =
        formTest {
            mountedForm("mhe-submit") { root, element ->
                val form = root.lapisForm()
                val subject = form.textField(label = tr("Betreff"), required = true)
                val editor = MailingHtmlEditor(form.panel, tr("Text"), subjectProvider = { subject.value })
                form.crossFieldRule(focusOn = editor.editable) {
                    if (editor.isBlank()) FieldCheck.Invalid(gettext("Bitte einen Text eingeben.")) else FieldCheck.Ok
                }
                form.buttons(primary = io.kvision.html.Button(tr("Speichern")))
                element().typeInto("Betreff", "Hallo")
                assertFalse(form.validateAndReport(), "an empty editor must block the submit")
                assertTrue(element().textContent.orEmpty().contains("Bitte einen Text eingeben."))
                element().textbox().innerHTML = "<p>Text</p>"
                assertTrue(form.validateAndReport())
            }
        }
}
