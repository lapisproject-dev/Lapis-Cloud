@file:JsModule("maplibre-gl")
@file:JsNonModule
@file:Suppress("unused")

package network.lapis.cloud.client.maplibre

import org.w3c.dom.Node

/**
 * Welle V1.9.6 "Vorstands-Karte" (member map, client half) -- minimal `maplibre-gl` 5.24.0 externals,
 * same `@JsModule`/`@JsNonModule` discipline as [network.lapis.cloud.client.livekit.LiveKitJs] (see
 * that file's KDoc for why BOTH annotations are required against this codebase's UMD Kotlin/JS
 * bundle output). **5.24.0, not 6.x**: `maplibre-gl` went ESM-only starting with 6.0, which this
 * webpack/Kotlin-JS interop cannot currently consume without extra build-side work -- see Q4 in
 * `docs/architecture/member-map.adoc`.
 *
 * **Deliberately minimal**, same discipline as [network.lapis.cloud.client.livekit.LiveKitJs]: only
 * what [network.lapis.cloud.client.MemberMapMapController] actually calls. No `LngLatBounds` type
 * (bounds are passed as plain `[west, south, east, north]` number arrays, exactly what the JS API
 * itself accepts), no `GeoJSONSource`-specific typed interface (source/layer/style JSON is built as
 * plain JSON strings in `MemberMapBasemapStyle.kt` and parsed via `JSON.parse` at the call site, then
 * passed through as `dynamic`), no click/mouse event typed interfaces (event payloads are read
 * field-by-field off `dynamic` at the call site, same posture as `TrackPublication`/`RemoteParticipant`
 * in `LiveKitJs.kt`).
 *
 * A field or method missing here is a deliberate omission, not an oversight -- add only what a
 * concrete call site in `MemberMapMapController.kt` actually needs.
 */
external class Map(
    options: dynamic,
) {
    fun addSource(
        id: String,
        source: dynamic,
    )

    /** Returns `dynamic` (a GeoJSON source instance) -- callers use `.setData(geojson)` on the result. */
    fun getSource(id: String): dynamic

    fun addLayer(
        layer: dynamic,
        beforeId: String = definedExternally,
    )

    fun addControl(
        control: dynamic,
        position: String = definedExternally,
    )

    fun on(
        type: String,
        listener: (dynamic) -> Unit,
    )

    fun on(
        type: String,
        layerId: String,
        listener: (dynamic) -> Unit,
    )

    fun off(
        type: String,
        listener: (dynamic) -> Unit,
    )

    fun setPaintProperty(
        layerId: String,
        name: String,
        value: dynamic,
    )

    fun queryRenderedFeatures(
        pointOrBox: dynamic,
        options: dynamic = definedExternally,
    ): Array<dynamic>

    fun easeTo(options: dynamic)

    fun flyTo(options: dynamic)

    fun jumpTo(options: dynamic)

    fun getZoom(): Double

    fun resize()

    fun remove()

    /** `dynamic` -- callers reach `.style.cursor` off the result (`getCanvasContainer()` similarly). */
    fun getCanvas(): dynamic

    fun getCanvasContainer(): dynamic
}

external class NavigationControl(
    options: dynamic = definedExternally,
)

/**
 * V1.9.8 "Vorstands-Karte: Orientierung" -- a plain DOM-element marker, used for the 24 static
 * Bundesland-/Nachbarland-labels ([network.lapis.cloud.client.MEMBER_MAP_LABELS]). Deliberately
 * minimal, same discipline as [Map]/[Popup]: only `setLngLat`/`addTo`/`remove`, nothing this
 * codebase's call site does not use (no drag, no offset, no rotation).
 */
external class Marker(
    options: dynamic = definedExternally,
) {
    fun setLngLat(lngLat: dynamic): Marker

    fun addTo(map: Map): Marker

    fun remove(): Marker
}

external class AttributionControl(
    options: dynamic = definedExternally,
)

/**
 * `setDOMContent` -- never `setHTML` -- is the only content-setting method declared here (Security-
 * Checkliste: `placeName`, a GeoNames free-text field, must never reach MapLibre as a markup string;
 * see `MemberMapMapController.kt` KDoc "XSS").
 */
external class Popup(
    options: dynamic = definedExternally,
) {
    fun setLngLat(lngLat: dynamic): Popup

    fun setDOMContent(node: Node): Popup

    fun addTo(map: Map): Popup

    fun remove(): Popup

    fun isOpen(): Boolean

    fun on(
        type: String,
        listener: () -> Unit,
    ): Popup
}

external fun addProtocol(
    name: String,
    handler: dynamic,
)
