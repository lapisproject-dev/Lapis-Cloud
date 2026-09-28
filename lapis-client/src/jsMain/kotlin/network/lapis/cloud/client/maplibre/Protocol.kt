@file:JsModule("pmtiles")
@file:JsNonModule
@file:Suppress("unused")

package network.lapis.cloud.client.maplibre

/**
 * Welle V1.9.6 "Vorstands-Karte" -- minimal `pmtiles` 4.5.0 externals, same `@JsModule`/`@JsNonModule`
 * discipline as [MapLibreJs]. [Protocol.tile] is the bound function [network.lapis.cloud.client.MemberMapPmtilesProtocol]
 * hands to `maplibregl.addProtocol("pmtiles", ...)` -- see that object's KDoc for why registration
 * happens exactly once per page load, not once per screen mount.
 */
external class Protocol(
    options: dynamic = definedExternally,
) {
    fun add(p: dynamic)

    val tile: dynamic
}
