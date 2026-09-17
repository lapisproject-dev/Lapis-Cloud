@file:JsModule("chart.js/auto")
@file:JsNonModule
@file:Suppress("unused")

package network.lapis.cloud.client.chart

import org.w3c.dom.HTMLCanvasElement

/**
 * Price-Oracle Kursverlauf-Diagramm -- minimale `chart.js` 4.5.0 Externals, zweite hand-
 * deklarierte npm()-Abhaengigkeit nach `livekit-client` (siehe deren `LiveKitJs.kt` KDoc fuer die
 * `@JsModule`/`@JsNonModule`-Begruendung, identisch hier: UMD-Bundle-Ausgabe dieses Moduls).
 *
 * Deliberately minimal, wie `LiveKitJs.kt`: kein typisiertes Options-Interface -- die Konfiguration
 * wird als `dynamic`-Objektliteral am Call-Site gebaut (`PriceOracleScreen.kt`), nicht hier
 * nachmodelliert. `chart.js/auto` (statt `chart.js`) registriert Line-Controller/Linear- und
 * Kategorie-Skalen/Tooltip-Plugin automatisch -- kein manuelles `Chart.register(...)` noetig.
 */
external class Chart(
    canvas: HTMLCanvasElement,
    config: dynamic,
) {
    fun update()

    fun destroy()

    var data: dynamic
    var options: dynamic
}
