@file:Suppress("unused")

package network.lapis.cloud.client.chart

import org.w3c.dom.Node

/**
 * Price-Oracle Kursverlauf-Diagramm -- minimale Browser-global `MutationObserver`-External-
 * Deklaration (kein `@JsModule`/`@JsNonModule` hier, anders als [Chart] in `ChartJs.kt`: dies ist
 * eine echte Web-Platform-API, kein npm-Paket-Export). Wird verwendet, um `data-theme`-Wechsel auf
 * `document.documentElement` zu beobachten (siehe `ThemeToggle.kt`, das dieses Attribut setzt) und
 * die Chart.js-Farben nachzuziehen, ohne dass `ThemeToggle.kt` selbst einen Callback-Hook anbieten
 * muss. Deliberately minimal wie [Chart]: nur `observe`/`disconnect`, kein `takeRecords`.
 */
external class MutationObserver(
    callback: (dynamic, dynamic) -> Unit,
) {
    fun observe(
        target: Node,
        options: dynamic,
    )

    fun disconnect()
}
