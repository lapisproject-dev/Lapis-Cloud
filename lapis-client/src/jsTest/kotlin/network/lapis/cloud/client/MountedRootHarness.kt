package network.lapis.cloud.client

import io.kvision.panel.ContainerType
import io.kvision.panel.Root
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Mounts a real KVision [Root] on a fresh `div` in the document, runs [block], and disposes both again.
 * Karma runs the tests in a real Chrome, so every `add` into the mounted tree really patches the document
 * and the snabbdom create/insert/destroy hooks really fire -- which a bare `SimplePanel()` (whose
 * `getRoot()` is `null`) never does.
 *
 * The element handed to [block] is resolved lazily on every call: KVision's `Root` constructor renders
 * its vnode with the element name as `nodeName` gives it (`DIV`, upper case) while snabbdom's
 * `emptyNodeAt` derives `div` from the existing element -- the selectors differ, so the very first patch
 * REPLACES the container element. A handle captured before that is detached and finds nothing.
 *
 * [block] is inlined, so a test may suspend inside it (see the async price-oracle tests).
 *
 * [id] must be unique per test class: the element is looked up by id, and a leaked element of another
 * test would otherwise be found.
 */
internal inline fun <T> withMountedRoot(
    id: String,
    block: (Root, () -> HTMLElement) -> T,
): T {
    val container = document.createElement("div") as HTMLElement
    container.id = id
    document.body!!.appendChild(container)
    val root = Root(id = id, containerType = ContainerType.NONE, addRow = false)
    try {
        return block(root) { document.getElementById(id) as HTMLElement }
    } finally {
        // BEFORE `dispose()`: disposing the root tears an open modal out of the document while Bootstrap still holds its
        // focus trap, which then pulls `document.activeElement` onto a modal button in whichever test class runs next.
        // See [hardResetModalState] for the full mechanism ("Cluster A").
        hardResetModalState()
        root.dispose()
        document.getElementById(id)?.remove()
    }
}
