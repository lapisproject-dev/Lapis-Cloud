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
    // ON ENTRY, not just in `finally`: this harness is the OTHER way into a DOM test (the first is
    // `formTest`, which has cleaned up on entry since it was written -- "Leftovers of other test
    // classes ... must not be found as 'the last modal'"). Classes that mount through here with a bare
    // `promise { block() }` had no entry-side cleanup at all, so they depended on EVERY preceding class
    // in Karma's single browser page having tidied up after itself. That assumption is what kept
    // breaking: `closeOpenModals` already existed and was simply not called in ten classes.
    //
    // A test must not be able to inherit a predecessor's open modal. Both calls are synchronous and
    // idempotent, so there is no `suspend` problem here (see [hardResetModalState] on why the shared
    // teardown cannot be suspending), and `disableModalTransitions` additionally makes any modal THIS
    // test opens closable at once -- Bootstrap ignores `hide()` while the ~300 ms show transition runs,
    // and a test clicks far faster than a person.
    disableModalTransitions()
    hardResetModalState()
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
