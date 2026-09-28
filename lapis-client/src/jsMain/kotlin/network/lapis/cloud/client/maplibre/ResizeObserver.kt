@file:Suppress("unused")

package network.lapis.cloud.client.maplibre

import org.w3c.dom.Element

/**
 * Welle V1.9.6 "Vorstands-Karte" -- minimal browser-global `ResizeObserver` external declaration
 * (no `@JsModule`/`@JsNonModule` here, same reasoning as `chart/MutationObserver.kt`: a real web-
 * platform API, not an npm-package export). Used by [network.lapis.cloud.client.MemberMapMapController]
 * to call `map.resize()` when the map panel's own size changes (a sidebar collapse, a viewport
 * rotation) -- MapLibre does not observe its container's size on its own.
 */
external class ResizeObserver(
    callback: (dynamic, dynamic) -> Unit,
) {
    fun observe(target: Element)

    fun disconnect()
}
