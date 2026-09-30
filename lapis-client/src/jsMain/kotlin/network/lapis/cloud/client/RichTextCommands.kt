package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Node
import org.w3c.dom.Range

/**
 * Welle V1.9.15 "SuperMailer" Teil A -- the ONE file that talks to the browser's editing commands
 * (`document.execCommand`, `queryCommandState`, `queryCommandValue`).
 *
 * `execCommand` is deprecated in the specification but remains the only cross-browser way to edit a
 * `contenteditable` region without a rich-text library, and it is implemented by every engine this
 * client supports. It is deliberately isolated here: if a browser ever drops it, this file (and only
 * this file) is what has to be replaced by a real editing model. Everything goes through
 * `document.asDynamic()` because the Kotlin/JS DOM typings no longer declare these members.
 *
 * Nothing here is a security boundary. The HTML an editing command produces is cleaned twice
 * afterwards: client-side by [normalizeMailingHtml] (so the preview and the stored draft are
 * tidy) and -- authoritatively -- server-side by `MailingHtmlSanitizer`.
 */
internal object RichTextCommands {
    private fun exec(
        command: String,
        value: String? = null,
    ): Boolean = document.asDynamic().execCommand(command, false, value) as? Boolean ?: false

    /** Paragraphs as `<p>` (not `<div>`), semantic tags instead of inline `style` spans. Safe to call repeatedly. */
    fun init() {
        exec("defaultParagraphSeparator", "p")
        exec("styleWithCSS", "false")
    }

    fun toggleBold() {
        exec("bold")
    }

    fun toggleItalic() {
        exec("italic")
    }

    /** Switches the current block to [tag] (`h2`/`h3`/`blockquote`); if it already is that block, back to a paragraph. */
    fun toggleBlock(tag: String) {
        val target = if (currentBlock() == tag) "p" else tag
        exec("formatBlock", "<$target>")
    }

    fun toggleList(ordered: Boolean) {
        exec(if (ordered) "insertOrderedList" else "insertUnorderedList")
    }

    /**
     * Turns the selection into a link to [url]. With a collapsed selection (nothing selected) `createLink` does nothing in
     * most engines, so the address itself is inserted as the link text instead.
     */
    fun createLink(url: String) {
        val collapsed = window.asDynamic().getSelection()?.isCollapsed as? Boolean ?: true
        if (collapsed) {
            exec("insertHTML", "<a href=\"${escapeHtmlText(url)}\">${escapeHtmlText(url)}</a>")
        } else {
            exec("createLink", url)
        }
    }

    fun unlink() {
        exec("unlink")
    }

    /** Inserts [text] as plain text: HTML-escaped, blank lines become paragraphs, single line breaks become `<br>`. */
    fun insertPlainText(text: String) {
        val html = if (text.contains(Regex("\n\\s*\n"))) plainTextToParagraphHtml(text) else plainTextToInlineHtml(text)
        if (html.isNotEmpty()) exec("insertHTML", html)
    }

    /** Is the editing command [command] (`bold`, `italic`, `insertUnorderedList`, `insertOrderedList`) active at the caret/selection? */
    fun isActive(command: String): Boolean =
        runCatching { document.asDynamic().queryCommandState(command) as? Boolean }.getOrNull() ?: false

    /** Lower-case block tag at the caret (`p`, `h2`, `h3`, `blockquote`, ...), normalised across engines ("Heading 2" -> `h2`). */
    fun currentBlock(): String {
        val raw =
            runCatching {
                document.asDynamic().queryCommandValue(
                    "formatBlock",
                ) as? String
            }.getOrNull()?.lowercase()?.trim().orEmpty()
        return when {
            raw.startsWith("heading ") -> "h" + raw.removePrefix("heading ").trim()
            raw == "quote" -> "blockquote"
            else -> raw.removePrefix("<").removeSuffix(">")
        }
    }

    /** Is the selection's anchor inside an `<a>`? Used for the link button's pressed state. */
    fun isInsideLink(): Boolean {
        var node: dynamic = window.asDynamic().getSelection()?.anchorNode
        while (node != null && node != undefined) {
            if ((node.nodeName as String).equals("A", ignoreCase = true)) return true
            node = node.parentNode
        }
        return false
    }

    /** The selection's first range, if it lies inside [container]; otherwise `null`. (`Selection` has no Kotlin/JS typing, hence `dynamic`.) */
    fun rangeWithin(container: Node): Range? {
        val selection = window.asDynamic().getSelection()
        if (selection == null || selection == undefined || (selection.rangeCount as Int) == 0) return null
        val range = selection.getRangeAt(0) as Range
        return if (container.contains(range.commonAncestorContainer)) range else null
    }

    /** Makes [range] the document's only selection range. */
    fun selectRange(range: Range) {
        val selection = window.asDynamic().getSelection()
        selection.removeAllRanges()
        selection.addRange(range)
    }
}
