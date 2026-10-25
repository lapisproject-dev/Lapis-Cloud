package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.Document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.HTMLStyleElement

/** The real cascade (Bootstrap + KVision + Font Awesome + `theme.css`), loaded once so a test needs no stylesheet knowledge of its own. */
private val cascadeLoaded: Boolean =
    run {
        js("require('bootstrap/dist/css/bootstrap.min.css')")
        js("require('zzz-kvision-assets/css/kv-style.css')")
        js("require('@fortawesome/fontawesome-free/css/all.css')")
        js("require('./theme.css')")
        true
    }

/**
 * V1.9.208: measures rendered DOM at a given viewport width. Karma's browser window width cannot be set, so media queries
 * (`d-sm-inline`, `max-width: 575.98px`) cannot be evaluated against the real page. This copies the page's style elements and the
 * rendered markup of [source] into an off-screen `<iframe>` of exactly [widthPx] CSS pixels and runs [block] against it: the media
 * queries then see that width.
 *
 * Only the markup is cloned (no event handlers, no KVision state): use it for geometry and computed style, not for behaviour. The frame
 * is removed afterwards. `<link rel=stylesheet>` elements are copied too but load asynchronously -- the project's stylesheets arrive as
 * inline `<style>` elements (webpack style-loader), which are synchronous.
 */
internal fun <T> measureAtWidth(
    source: HTMLElement,
    widthPx: Int,
    block: (Document, HTMLElement) -> T,
): T {
    check(cascadeLoaded)
    val frame = document.createElement("iframe") as HTMLIFrameElement
    frame.style.cssText = "position:absolute;left:-9999px;top:0;border:0;height:900px;width:${widthPx}px"
    document.body!!.appendChild(frame)
    try {
        val doc = frame.contentDocument!!
        doc.open()
        doc.write("<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>")
        doc.close()
        val head = document.head!!
        for (i in 0 until head.children.length) {
            val node = head.children.item(i) ?: continue
            if (node is HTMLStyleElement || node.tagName.equals("LINK", ignoreCase = true)) {
                doc.head!!.appendChild(node.cloneNode(true))
            }
        }
        // unsafeCast: elements of the frame belong to another realm, `as HTMLElement` would fail its instanceof check
        val host = doc.createElement("div").unsafeCast<HTMLElement>()
        host.innerHTML = source.innerHTML
        doc.body!!.appendChild(host)
        return block(doc, host)
    } finally {
        frame.parentNode?.removeChild(frame)
    }
}

/** All elements below this one matching [selector]. */
internal fun HTMLElement.queryAll(selector: String): List<HTMLElement> =
    (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it).unsafeCast<HTMLElement>() }

/** The first element below this one matching [selector]; safe for elements of a [measureAtWidth] frame (no realm-bound cast). */
internal fun HTMLElement.queryOne(selector: String): HTMLElement = querySelector(selector).unsafeCast<HTMLElement>()
