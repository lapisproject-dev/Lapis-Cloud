package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonSize
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.HPanel
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MailingPreviewDto
import network.lapis.cloud.shared.rpc.IMailingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.Range
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

private var editorIdCounter = 0

/**
 * Welle V1.9.15 "SuperMailer" Teil A -- a small WYSIWYG editor for mailing-message bodies, built on a
 * `contenteditable` region and [RichTextCommands] (no editor library, no new dependency).
 *
 * **Structure** (top to bottom): a label, a `role="toolbar"` with exactly nine buttons (bold, italic,
 * heading 1, heading 2, quote, bullet list, numbered list, add link, remove link), an inline link
 * form (opened by the link button or Ctrl/Cmd+K), the editable region itself, and a "Vorschau"
 * action that renders the server's own preview of what would be sent.
 *
 * **Accessibility**: the toolbar is a single tab stop (roving tabindex, arrow keys/Home/End move
 * between buttons), every button carries `title` + `aria-label` + `aria-pressed` (pressed state
 * follows the caret through `selectionchange`), and the editable region is a labelled, multiline
 * `role="textbox"`. Toolbar buttons swallow `mousedown` so a click never steals the selection from
 * the text.
 *
 * **Input hygiene**: paste is always plain text (the clipboard's `text/plain`, HTML-escaped by
 * [RichTextCommands.insertPlainText]; foreign markup and styles never enter the document), drag and
 * drop are refused. [html] returns the normalised serialisation ([serializeNormalized]) of a CLONE,
 * so the live document is never rewritten under the cursor. The server-side sanitizer remains the
 * authority over what is stored and sent.
 *
 * **Lifecycle**: the `selectionchange` listener lives on `document`, so it is registered when the
 * element is inserted and removed when it is destroyed (via [addWithLifecycle]) -- otherwise every
 * "Verwalten" click would leave another handler behind. A re-attach after a language switch rebuilds
 * the element from this same object; the text typed so far is kept in [lastHtml] and restored.
 */
internal class MailingHtmlEditor(
    host: Container,
    labelText: String,
    private val subjectProvider: () -> String,
    required: Boolean = true,
    /** Called after every edit of the text (the compose form uses it to clear a stale "enter a text" message). */
    private val onEdited: () -> Unit = {},
) {
    private class ToolButton(
        val button: Button,
        val isPressed: () -> Boolean,
    )

    private val labelId = "lapis-mailing-editor-label-${editorIdCounter++}"
    private var savedRange: Range? = null
    private var lastHtml: String = ""
    private var selectionListener: ((Event) -> Unit)? = null
    private val tools = mutableListOf<ToolButton>()
    private var focusableToolIndex = 0

    private val toolbar: HPanel
    private val linkPanel: VPanel
    private val linkForm: LapisForm
    private val linkField: LapisField
    private val previewBox: VPanel

    val editable: Div

    init {
        host.div {
            id = labelId
            addCssClass("form-label")
            span(labelText)
            if (required) {
                span(" *") {
                    addCssClass("lapis-required-mark")
                    setAttribute("aria-hidden", "true")
                }
            }
        }

        toolbar =
            host.addWithLifecycle(
                HPanel(spacing = 4) {
                    addCssClasses("flex-wrap lapis-mailing-toolbar")
                    setAttribute("role", "toolbar")
                    setAttribute("aria-label", gettext("Textformat"))
                },
                onInsert = { vnode -> (vnode.elm as? HTMLElement)?.let(::wireToolbarElement) },
            )
        buildToolbarButtons()

        linkPanel = host.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
        linkPanel.hide()
        linkForm = linkPanel.lapisForm()
        linkField =
            linkForm.textField(
                label = tr("Adresse"),
                required = true,
                rule = { value ->
                    if (isValidLinkUrl(value.trim())) {
                        FieldCheck.Ok
                    } else {
                        FieldCheck.Invalid(gettext("Nur Adressen mit https://, http:// oder mailto: sind erlaubt."))
                    }
                },
            )
        val applyButton = Button(tr("Übernehmen"), style = ButtonStyle.PRIMARY)
        val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.PRIMARY)
        linkForm.buttons(primary = applyButton, cancel = cancelButton)
        applyButton.onClick { applyLink() }
        cancelButton.onClick { closeLinkDialog(restoreSelection = true) }

        editable =
            host.addWithLifecycle(
                Div {
                    addCssClass("lapis-mailing-editor")
                    setAttribute("contenteditable", "true")
                    setAttribute("role", "textbox")
                    setAttribute("aria-multiline", "true")
                    setAttribute("aria-labelledby", labelId)
                },
                onInsert = { vnode -> (vnode.elm as? HTMLElement)?.let(::wireEditableElement) },
                onDestroy = ::detachSelectionListener,
            )

        val previewButton = host.actionButton(ActionIcon.VIEW, tr("Vorschau"), style = ButtonStyle.OUTLINESECONDARY)
        previewBox = host.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
        previewBox.hide()
        previewButton.onClick {
            runGuardedAction(previewButton) {
                val preview =
                    guarded { rpcService<IMailingService>().previewMailingHtml(subjectProvider().trim(), html()) }
                        ?: return@runGuardedAction
                renderPreview(preview)
            }
        }
    }

    /** The normalised HTML of the current text (from a clone; the live DOM is untouched). Empty until the editor is mounted. */
    fun html(): String = element()?.let(::serializeNormalized) ?: lastHtml

    /** `true` when the region holds no visible text -- whitespace and empty blocks do not count. */
    fun isBlank(): Boolean = element()?.textContent?.trim().isNullOrEmpty()

    fun clear() {
        element()?.innerHTML = ""
        lastHtml = ""
        savedRange = null
        refreshToolbarState()
    }

    private fun element(): HTMLElement? = editable.getElement() as? HTMLElement

    // ---- toolbar -----------------------------------------------------------------------------

    private fun buildToolbarButtons() {
        addTool(icon = "fas fa-bold", label = tr("Fett"), pressed = { RichTextCommands.isActive("bold") }) { RichTextCommands.toggleBold() }
        addTool(icon = "fas fa-italic", label = tr("Kursiv"), pressed = { RichTextCommands.isActive("italic") }) {
            RichTextCommands.toggleItalic()
        }
        addTool(glyph = tr("Ü1"), label = tr("Überschrift 1"), pressed = { RichTextCommands.currentBlock() == "h2" }) {
            RichTextCommands.toggleBlock("h2")
        }
        addTool(glyph = tr("Ü2"), label = tr("Überschrift 2"), pressed = { RichTextCommands.currentBlock() == "h3" }) {
            RichTextCommands.toggleBlock("h3")
        }
        addTool(icon = "fas fa-quote-right", label = tr("Zitat"), pressed = { RichTextCommands.currentBlock() == "blockquote" }) {
            RichTextCommands.toggleBlock("blockquote")
        }
        addTool(icon = "fas fa-list-ul", label = tr("Aufzählung"), pressed = { RichTextCommands.isActive("insertUnorderedList") }) {
            RichTextCommands.toggleList(ordered = false)
        }
        addTool(icon = "fas fa-list-ol", label = tr("Nummerierte Liste"), pressed = { RichTextCommands.isActive("insertOrderedList") }) {
            RichTextCommands.toggleList(ordered = true)
        }
        addTool(icon = "fas fa-link", label = tr("Link einfügen"), pressed = { RichTextCommands.isInsideLink() }, direct = true) {
            openLinkDialog()
        }
        // "Link entfernen" is an action, not a state: it carries aria-pressed like its siblings (a uniform toolbar), permanently false.
        addTool(icon = "fas fa-link-slash", label = tr("Link entfernen"), pressed = { false }) { RichTextCommands.unlink() }
        refreshToolbarState()
    }

    /** [direct] actions (opening the link form) must not refocus the text first -- focus is about to move to the form. */
    private fun addTool(
        label: String,
        icon: String? = null,
        glyph: String? = null,
        pressed: () -> Boolean,
        direct: Boolean = false,
        action: () -> Unit,
    ) {
        val index = tools.size
        val tool =
            toolbar.button(text = glyph ?: "", icon = icon, style = ButtonStyle.OUTLINESECONDARY) {
                size = ButtonSize.SMALL
                tableActionTooltip(label)
                setAttribute("aria-pressed", "false")
                setAttribute("tabindex", if (index == 0) "0" else "-1")
            }
        tool.onClick { if (direct) action() else runCommand(action) }
        tools += ToolButton(tool, pressed)
    }

    private fun wireToolbarElement(toolbarElement: HTMLElement) {
        // A click must not steal the selection from the text: suppress the focus change of mousedown.
        toolbarElement.addEventListener(
            "mousedown",
            { event -> if ((event.target as? HTMLElement)?.closest("button") != null) event.preventDefault() },
        )
        toolbarElement.addEventListener("keydown", { event -> (event as? KeyboardEvent)?.let(::onToolbarKey) })
    }

    private fun onToolbarKey(event: KeyboardEvent) {
        val last = tools.size - 1
        val next =
            when (event.key) {
                "ArrowRight" -> if (focusableToolIndex >= last) 0 else focusableToolIndex + 1
                "ArrowLeft" -> if (focusableToolIndex <= 0) last else focusableToolIndex - 1
                "Home" -> 0
                "End" -> last
                else -> return
            }
        event.preventDefault()
        focusTool(next)
    }

    private fun focusTool(index: Int) {
        focusableToolIndex = index
        tools.forEachIndexed { i, tool -> tool.button.setAttribute("tabindex", if (i == index) "0" else "-1") }
        (tools[index].button.getElement() as? HTMLElement)?.focus()
    }

    private fun refreshToolbarState() {
        tools.forEach { tool -> tool.button.setAttribute("aria-pressed", if (tool.isPressed()) "true" else "false") }
    }

    // ---- editable region -----------------------------------------------------------------------

    private fun wireEditableElement(el: HTMLElement) {
        // Re-attach after a language switch rebuilt the element: put the text typed so far back (our own normalised output).
        if (lastHtml.isNotEmpty() && el.innerHTML.isEmpty()) el.innerHTML = lastHtml
        el.addEventListener("focus", { RichTextCommands.init() })
        el.addEventListener(
            "input",
            {
                lastHtml = serializeNormalized(el)
                onEdited()
            },
        )
        el.addEventListener("keydown", { event -> (event as? KeyboardEvent)?.let(::onEditorKey) })
        el.addEventListener(
            "paste",
            { event ->
                event.preventDefault()
                val text = (event as? ClipboardEvent)?.clipboardData?.getData("text/plain").orEmpty()
                if (text.isNotEmpty()) RichTextCommands.insertPlainText(text)
            },
        )
        el.addEventListener("drop", { event -> event.preventDefault() })
        el.addEventListener("dragover", { event -> event.preventDefault() })
        attachSelectionListener(el)
    }

    private fun onEditorKey(event: KeyboardEvent) {
        if (!(event.ctrlKey || event.metaKey) || event.altKey) return
        when (event.key.lowercase()) {
            "b" -> {
                event.preventDefault()
                RichTextCommands.toggleBold()
            }
            "i" -> {
                event.preventDefault()
                RichTextCommands.toggleItalic()
            }
            "k" -> {
                event.preventDefault()
                openLinkDialog()
            }
        }
    }

    private fun attachSelectionListener(el: HTMLElement) {
        detachSelectionListener()
        val listener: (Event) -> Unit = {
            val range = RichTextCommands.rangeWithin(el)
            if (range != null) {
                savedRange = range.cloneRange()
                refreshToolbarState()
            }
        }
        selectionListener = listener
        document.addEventListener("selectionchange", listener)
    }

    private fun detachSelectionListener() {
        selectionListener?.let { document.removeEventListener("selectionchange", it) }
        selectionListener = null
    }

    /** Puts focus and the last known selection back into the text, then runs [action] and re-syncs toolbar and buffer. */
    private fun runCommand(action: () -> Unit) {
        val el = element() ?: return
        el.focus()
        // Focusing the region may collapse the caret to its start (after the address field had focus, say), so the
        // last selection seen inside the text is restored unconditionally. `savedRange` is a live Range: it follows edits.
        savedRange?.let { RichTextCommands.selectRange(it) }
        action()
        lastHtml = serializeNormalized(el)
        refreshToolbarState()
    }

    // ---- link form -------------------------------------------------------------------------------

    private fun openLinkDialog() {
        // Focus is about to move into the address field, which clears the selection: keep the range.
        element()?.let { el -> RichTextCommands.rangeWithin(el)?.let { savedRange = it.cloneRange() } }
        linkPanel.show()
        linkField.focus()
    }

    private fun applyLink() {
        if (!linkForm.validateAndReport()) return
        val url = linkField.value.trim()
        closeLinkDialog(restoreSelection = false)
        runCommand { RichTextCommands.createLink(url) }
    }

    private fun closeLinkDialog(restoreSelection: Boolean) {
        linkPanel.hide()
        linkField.reset()
        linkForm.clearFormError()
        if (restoreSelection) runCommand { }
    }

    // ---- preview ---------------------------------------------------------------------------------

    private fun renderPreview(preview: MailingPreviewDto) {
        previewBox.removeAll()
        previewBox.div(tr("Vorschau der Nachricht")) { addCssClasses("fw-bold") }
        // `rich = true` is the same trust boundary `articlePreviewFrame` uses: this HTML is the server's own
        // MailingMailRenderer output over the SANITIZED body, never author input.
        previewBox.div(content = preview.html, rich = true)
        val plainBox = previewBox.vPanel(spacing = 2)
        plainBox.hide()
        val toggle = previewBox.button(tr("Textversion anzeigen"), style = ButtonStyle.OUTLINESECONDARY) { size = ButtonSize.SMALL }
        plainBox.untrustedP(preview.plainText, className = "lapis-mailing-plaintext")
        toggle.onClick {
            if (plainBox.visible) {
                plainBox.hide()
                toggle.text = tr("Textversion anzeigen")
            } else {
                plainBox.show()
                toggle.text = tr("Textversion ausblenden")
            }
        }
        previewBox.show()
    }
}
