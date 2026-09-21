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
    val leftovers = document.querySelectorAll(".modal, .modal-backdrop")
    for (index in 0 until leftovers.length) (leftovers.item(index) as HTMLElement).remove()
    document.body?.classList?.remove("modal-open")
    document.body?.style?.removeProperty("overflow")
    document.body?.style?.removeProperty("padding-right")
}
