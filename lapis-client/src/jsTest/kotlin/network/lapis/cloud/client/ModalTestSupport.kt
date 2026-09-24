package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement

/**
 * Makes Bootstrap's modal show/hide synchronous: without a CSS transition Bootstrap runs its `shown`/`hidden` handling at
 * once. With the default fade, `hide()` is IGNORED while the ~300 ms show transition still runs (a test clicks much faster
 * than a person), so the modal would stay open and keep its focus trap. Idempotent; call BEFORE the modal is opened. The style
 * stays for the rest of the run, which only makes later modal tests more deterministic.
 */
internal fun disableModalTransitions() {
    if (document.getElementById("test-no-modal-transitions") != null) return
    val style = document.createElement("style")
    style.id = "test-no-modal-transitions"
    style.textContent = ".modal, .modal *, .modal-backdrop { transition: none !important; animation: none !important; }"
    document.head?.appendChild(style)
}

/**
 * Closes every open Bootstrap modal the way a person would (a dismissing footer button, else the header close button), so
 * Bootstrap runs its own `hidden` handling and deactivates its focus trap. A modal that is merely left in the document, or
 * torn out of it while its transition is still running, keeps a live focus trap that pulls `document.activeElement` onto one of
 * ITS buttons -- which broke the focus assertions of `LateHookAuditDomTest` in whichever test class ran after it.
 *
 * Clicks are repeated: Bootstrap ignores `hide()` while a show transition is still running (about 300 ms). What is still in
 * the document afterwards (a test that failed midway) is removed as a safety net.
 */
internal suspend fun closeOpenModals(timeoutMs: Int = 3000) {
    var waited = 0
    while (document.querySelectorAll(".modal.show").length > 0 && waited < timeoutMs) {
        val open = document.querySelectorAll(".modal.show")
        for (index in 0 until open.length) {
            val modal = open.item(index) as HTMLElement
            // The footer's own dismissing button first (it calls the KVision modal's `hide()`); the header close button
            // relies on Bootstrap's data-api and is only the fallback.
            val closer =
                (0 until modal.querySelectorAll(".modal-footer button").length)
                    .map { modal.querySelectorAll(".modal-footer button").item(it) as HTMLElement }
                    .firstOrNull { it.textContent?.trim() in setOf("Abbrechen", "Schließen", "Fertig") }
                    ?: modal.querySelector(".btn-close") as? HTMLElement
            closer?.click()
        }
        delay(100)
        waited += 100
    }
    // The element removal below is the safety net. Blurring has to happen BEFORE it, while the focused modal button is
    // still in the document -- afterwards `document.activeElement` is already `<body>` and there is nothing left to blur.
    hardResetModalState()
}

/**
 * The synchronous last resort, run by [withMountedRoot] after EVERY mounted test -- not just in the classes that
 * remembered to call [closeOpenModals]. A modal left open by one test class covers the page for every class that runs
 * after it in the same Karma browser page: its backdrop wins the pixel hit test, and Bootstrap's focus trap pulls
 * `document.activeElement` onto one of ITS buttons, so a later `assertEquals(input, document.activeElement)` sees a
 * button. That is the whole of "Cluster A" (`FormGrammarDomTest`, `FormGrammarAuditDomTest`): both classes pass in
 * isolation and fail behind `AuditFixesMinorDomTest`/`AuditFixesM1M2M3DomTest`, which open modals and never close them.
 *
 * Synchronous on purpose: [closeOpenModals] is `suspend`, and only 9 of the 44 files using [withMountedRoot] call it
 * from a suspending context -- making the shared teardown `suspend` would break the other 35. This one cannot wait out
 * a running fade, so it does the two things that actually matter and can be done at once:
 *   1. Blur first, WHILE the modal is still in the document -- afterwards `document.activeElement` is already `<body>`
 *      and there is nothing left to blur.
 *   2. Then remove the modal and backdrop elements, leaving Bootstrap's document-level focus trap pointing at a
 *      detached element, where `focus()` does nothing.
 *
 * [closeOpenModals] stays the better tool where a test can await it: it closes the modal the way a person would and
 * lets Bootstrap run its own `hidden` handling. This is the safety net under it, not a replacement.
 */
internal fun hardResetModalState() {
    val active = document.activeElement as? HTMLElement
    if (active != null && active.asDynamic().closest(".modal") != null) active.blur()
    val leftovers = document.querySelectorAll(".modal, .modal-backdrop")
    for (index in 0 until leftovers.length) (leftovers.item(index) as HTMLElement).remove()
    document.body?.classList?.remove("modal-open")
    document.body?.style?.removeProperty("overflow")
    document.body?.style?.removeProperty("padding-right")
}
