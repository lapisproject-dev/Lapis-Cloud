package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Welle V1.9.15 "SuperMailer" Teil A -- the editor's own DOM normaliser. A reiner DOM-Walk, no
 * `execCommand`, no library: Chrome emits `<b>`/`<i>`/`<div>`, Firefox `<strong>`/`<em>`, Safari
 * `<span style>`, and the same allowlist and renames the server uses
 * ([MailingHtmlPolicy.ALLOWED_TAGS]/[MailingHtmlPolicy.TAG_RENAMES]/[MailingHtmlPolicy.ALLOWED_HREF_SCHEMES])
 * are applied here so a draft looks the same in the editor, in the preview and in the stored body.
 *
 * **Not a security boundary** -- `MailingHtmlSanitizer` on the server is the authority (DOMPurify was
 * considered and rejected: one more dependency for a check the server repeats anyway). What this file
 * guarantees is tidiness, and that nothing the normaliser lets through is wider than what the server
 * accepts.
 */
private val DROP_WITH_CONTENT = setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "head", "title")
private val BLOCK_TAGS = setOf("p", "h2", "h3", "ul", "ol", "li", "blockquote")
private const val MAX_LINK_LENGTH = 2048

/**
 * `https://`, `http://` or `mailto:` only (case-insensitive), no whitespace or control characters,
 * at most 2048 characters, and something after the scheme.
 */
internal fun isValidLinkUrl(url: String): Boolean {
    if (url.length > MAX_LINK_LENGTH) return false
    if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return false
    val lower = url.lowercase()
    val scheme = MailingHtmlPolicy.ALLOWED_HREF_SCHEMES.firstOrNull { lower.startsWith(if (it == "mailto") "mailto:" else "$it://") }
    if (scheme == null) return false
    return lower.length > (if (scheme == "mailto") "mailto:".length else "$scheme://".length)
}

/** Normalises [root]'s subtree IN PLACE (the root itself is never replaced). */
internal fun normalizeMailingHtml(root: Element) {
    normalizeChildren(root)
}

private fun normalizeChildren(parent: Node) {
    val snapshot = (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) }
    snapshot.forEach { child ->
        when (child.nodeType) {
            Node.TEXT_NODE -> Unit
            Node.ELEMENT_NODE -> normalizeElement(parent, child as Element)
            else -> parent.removeChild(child)
        }
    }
}

private fun normalizeElement(
    parent: Node,
    element: Element,
) {
    val tag = element.tagName.lowercase()
    if (tag in DROP_WITH_CONTENT) {
        parent.removeChild(element)
        return
    }
    normalizeChildren(element)
    val renamed = MailingHtmlPolicy.TAG_RENAMES[tag] ?: tag
    when {
        // A div that wraps block content would become a <p> containing blocks: unwrap instead.
        tag == "div" && element.hasBlockChild() -> unwrap(parent, element)
        renamed !in MailingHtmlPolicy.ALLOWED_TAGS -> unwrap(parent, element)
        renamed == "a" -> normalizeAnchor(parent, element)
        else -> replaceWith(parent, element, renamed, keepHref = false)
    }
}

private fun normalizeAnchor(
    parent: Node,
    element: Element,
) {
    val href = element.getAttribute("href")
    if (href == null || !isValidLinkUrl(href)) {
        unwrap(parent, element)
    } else {
        replaceWith(parent, element, "a", keepHref = true)
    }
}

private fun Element.hasBlockChild(): Boolean = (0 until children.length).any { children.item(it)?.tagName?.lowercase() in BLOCK_TAGS }

/** Replaces [element] with a fresh `<[newTag]>` carrying the same children and -- for links only -- the `href`; no other attribute survives. */
private fun replaceWith(
    parent: Node,
    element: Element,
    newTag: String,
    keepHref: Boolean,
) {
    val replacement = document.createElement(newTag)
    if (keepHref) element.getAttribute("href")?.let { replacement.setAttribute("href", it) }
    while (element.firstChild != null) replacement.appendChild(element.firstChild!!)
    parent.replaceChild(replacement, element)
}

private fun unwrap(
    parent: Node,
    element: Element,
) {
    while (element.firstChild != null) parent.insertBefore(element.firstChild!!, element)
    parent.removeChild(element)
}

/** Clones [root], normalises the clone and returns its `innerHTML` -- the live editor DOM is never touched. */
internal fun serializeNormalized(root: Element): String {
    val clone = root.cloneNode(true) as Element
    normalizeMailingHtml(clone)
    return clone.innerHTML
}

internal fun escapeHtmlText(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/** Plain text -> inline HTML: escaped, every line break a `<br>`. No block structure. */
internal fun plainTextToInlineHtml(text: String): String =
    escapeHtmlText(text.replace("\r\n", "\n").replace('\r', '\n')).replace("\n", "<br>")

/** Plain text -> paragraphs: blank lines separate `<p>` blocks, single line breaks inside one become `<br>`. */
internal fun plainTextToParagraphHtml(text: String): String =
    text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n\\s*\n"))
        .map { it.trim('\n') }
        .filter { it.isNotBlank() }
        .joinToString("") { "<p>${plainTextToInlineHtml(it)}</p>" }
