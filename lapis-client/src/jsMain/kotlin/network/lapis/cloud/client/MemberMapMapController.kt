package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.chart.MutationObserver
import network.lapis.cloud.client.maplibre.AttributionControl
import network.lapis.cloud.client.maplibre.Map
import network.lapis.cloud.client.maplibre.Marker
import network.lapis.cloud.client.maplibre.NavigationControl
import network.lapis.cloud.client.maplibre.Popup
import network.lapis.cloud.client.maplibre.Protocol
import network.lapis.cloud.client.maplibre.ResizeObserver
import network.lapis.cloud.client.maplibre.addProtocol
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import network.lapis.cloud.shared.domain.MemberMapRules
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.js.JSON
import kotlin.js.jsTypeOf

/**
 * Welle V1.9.6 "Vorstands-Karte" -- registers the JS `pmtiles://` protocol handler EXACTLY ONCE per
 * page load (Spezifikationspunkt 57), not once per [MemberMapMapController] instance: a second
 * `addProtocol("pmtiles", ...)` call on every re-visit of the `/member-map` route would silently
 * shadow the first registration with a fresh `Protocol` instance carrying its own (empty) tile cache.
 */
internal object MemberMapPmtilesProtocol {
    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        registered = true
        addProtocol("pmtiles", Protocol().tile)
    }
}

/** `init()`'s outcome: whether a real map was created, or the browser cannot render WebGL at all (Q6). */
internal enum class MemberMapInitResult { WEBGL_MISSING, CREATED }

/**
 * The side-effecting collaborators of [MemberMapMapController], injectable so a test can fake all of
 * them without ever constructing a real `maplibre-gl` `Map`/WebGL context -- see this class's own KDoc
 * "Stolperfalle" #2. Production always uses the defaults.
 *
 * **V1.9.8 addition**: [createMarker]/[createPopup]/[hoverCapable] back the orientation labels and
 * hover tooltip ([MemberMapMapController.addRegionLabels]/`onPointHover`/`onClusterHover`) the same
 * way [createMap]/[webglAvailable] already back the map itself -- so `MemberMapMapControllerTest` can
 * fake a `Marker`/`Popup` without a real WebGL canvas, exactly as it already fakes `Map`.
 */
internal class MemberMapDeps(
    val createMap: (dynamic) -> dynamic = { options -> Map(options) },
    val webglAvailable: () -> Boolean = ::detectWebglAvailable,
    val createMarker: (element: HTMLElement, lngLat: Array<Double>) -> dynamic =
        { element, lngLat ->
            val options = js("({})")
            options.element = element
            options.anchor = "center"
            Marker(options).setLngLat(lngLat)
        },
    val createPopup: (dynamic) -> dynamic = { options -> Popup(options) },
    /** `false` on a coarse-pointer (touch) device -- a `mousemove`-driven tooltip would otherwise fire from an emulated hover right before the tap's own click, showing then immediately hiding a popup under the finger. */
    val hoverCapable: () -> Boolean = { !window.matchMedia("(pointer: coarse)").matches },
    /**
     * V1.9.8 fix: builds AND STARTS the `data-theme` [MutationObserver] -- injectable (not just the
     * [MutationObserver] construction itself), because `observe()` targets `document.documentElement`,
     * the SAME real, shared element across every test in one Karma/Chrome run. A test that lets
     * `wireLayers` -> `setupThemeObserver` run this production default registers a REAL observer on
     * that shared element; unless the test disconnects it (`controller.destroy()`), the observer
     * outlives the test and its callback closure keeps referencing that test's now-irrelevant `newMap`
     * -- a LATER, unrelated test's `data-theme` change then fires it, calling `setPaintProperty` (or
     * whatever that stale fake lacked) on an object that was never meant to see this event again. See
     * `MemberMapMapControllerTest`'s `fakeThemeObserver` KDoc for the concrete failure this caused.
     * [onChange] is invoked with no arguments on every `data-theme` mutation; production ignores the
     * mutation records themselves, exactly as the old inline `MutationObserver { _, _ -> ... }` did.
     */
    val createThemeObserver: (onChange: () -> Unit) -> dynamic =
        { onChange ->
            val observer = MutationObserver { _, _ -> onChange() }
            val observerOptions = js("({})")
            observerOptions.attributes = true
            observerOptions.attributeFilter = arrayOf("data-theme")
            observer.observe(document.documentElement!!, observerOptions)
            observer
        },
)

/** V1.9.9 -- [MemberMapMapController.updatePlaceLabels] only queries/renders small-locality labels from this zoom onward; below it the map is still at a Bundesland-wide overview where individual village names would just be clutter. */
private const val PLACE_LABELS_MIN_ZOOM = 9.0

/** V1.9.9 -- hard cap on simultaneously rendered small-locality DOM markers, see [MemberMapMapController.updatePlaceLabels]. */
private const val PLACE_LABELS_MAX_COUNT = 40

/** `try { canvas.getContext("webgl2") ?: getContext("webgl") } catch { null }` -- some browsers throw rather than return `null` for a disabled/blocklisted GPU. */
private fun detectWebglAvailable(): Boolean =
    try {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.getContext("webgl2") != null || canvas.getContext("webgl") != null
    } catch (e: Throwable) {
        false
    }

/**
 * Owns the entire MapLibre map lifecycle for one mount of `MemberMapScreen.kt`. Deliberately holds the
 * underlying map/source/popup objects as `dynamic` (never the typed `network.lapis.cloud.client.maplibre.Map`)
 * so [MemberMapDeps.createMap] can be faked with a plain JS object literal in tests -- **never construct
 * a real `maplibre-gl` `Map`/WebGL context in a jsTest** (see this class's KDoc "Stolperfalle" #2).
 *
 * **Style/data flow**: [init] builds the static basemap style ([buildBasemapStyleJson]) and, once the
 * map's `"load"` event fires, adds the clustered points source ([buildPointsSourceJson]) and its two
 * layers ([buildPointsLayerJson]/[buildClustersLayerJson]) -- MapLibre rejects `addSource`/`addLayer`
 * calls before `"load"`. [setPoints] only ever touches the SOURCE's data afterwards
 * (`source.setData(...)`), it never rebuilds the style or the layers. **`"load"` is genuinely async**
 * (WebGL context + style loading), so the caller's very first [setPoints] call -- right after [init]
 * returns -- almost always races ahead of it; see [pendingEntries]'s own KDoc for the fix.
 *
 * **Theme sync**: a [MutationObserver] on `data-theme` re-reads [readMemberMapColors] and calls
 * `map.setPaintProperty(...)` for the map's ten color paint properties (including the V1.9.9
 * `member-map-country-borders-halo` layer) -- the exact same pattern
 * `PriceOracleScreen.kt` already uses for Chart.js, just `setPaintProperty` instead of `chart.update()`.
 * The style itself is never rebuilt on a theme change (Design-Team decision, see
 * `MemberMapBasemapStyle.buildBasemapStyleJson` KDoc "Q Duarte").
 *
 * **Cleanup**: [destroy] removes any open popup, disconnects both observers and removes the map --
 * called from `MemberMapScreen.kt`'s `addAfterDestroyHook`, same idiom as `PriceOracleScreen.kt`'s
 * `teardownChart`.
 *
 * **V1.9.8 "Orientierung"**: 24 static Bundesland-/Nachbarland-labels ([addRegionLabels], see
 * `MemberMapLabels.kt`), a `data-zoom-band` attribute on [container] the CSS uses to fade the
 * smaller labels in from zoom band "mid" ([updateZoomBand]), and a hover tooltip on points/clusters
 * (`onPointHover`/`onClusterHover`) closing the "which circle is this row?" gap the table alone left
 * open. Same wave: the cluster-click zoom bug fix, see [onClusterClicked]'s own KDoc.
 *
 * **V1.9.9 "Details & Suche"**: 16 Landeshauptstadt markers ([addCapitalMarkers]), dynamically
 * viewport-queried small-locality markers once zoomed in past [PLACE_LABELS_MIN_ZOOM]
 * ([updatePlaceLabels], see its own KDoc for how this avoids a glyph-serving pipeline entirely), and
 * the Ortssuche pin/popup lifecycle ([flyToPlace]/[showSearchPin]/[clearSearchPin]) `MemberMapScreen.kt`'s
 * new map-panel search overlay drives.
 */
internal class MemberMapMapController(
    private val container: HTMLElement,
    private val deps: MemberMapDeps = MemberMapDeps(),
    private val onFeatureClicked: (postalCode: String) -> Unit,
) {
    private var map: dynamic = null
    private var popup: Popup? = null

    /** `dynamic`, not the typed [MutationObserver] -- built via [MemberMapDeps.createThemeObserver], which a test fakes with a plain object exposing only `disconnect()` (see that dep's own KDoc). */
    private var themeObserver: dynamic = null
    private var resizeObserver: ResizeObserver? = null

    /** The 24 static orientation labels ([MEMBER_MAP_LABELS]), created once in [addRegionLabels]. */
    private val labelMarkers = mutableListOf<dynamic>()

    /** V1.9.9: the 16 [MEMBER_MAP_CAPITALS] markers, created once in [addCapitalMarkers] (never rebuilt -- fixed list, unlike [placeLabelMarkers]). */
    private val capitalMarkers = mutableListOf<dynamic>()

    /** V1.9.9: the current, viewport-dependent set of small-locality markers -- fully rebuilt on every [updatePlaceLabels] call (see that method's own KDoc), never just appended to. */
    private val placeLabelMarkers = mutableListOf<dynamic>()

    /** V1.9.9: the one Ortssuche pin marker currently shown (or `null`) -- see [showSearchPin]/[clearSearchPin]. */
    private var searchPinMarker: dynamic = null

    /** V1.9.9: the Ortssuche pin's own popup (separate from [popup]/[hoverPopup] -- a search result and a clicked postal-code point can be shown at the same time). */
    private var searchPinPopup: dynamic = null

    /** The one hover tooltip currently shown (or `null`) -- see `onPointHover`/`onClusterHover`/`clearHover`. */
    private var hoverPopup: dynamic = null

    /** `"p:<postalCode>"` or `"c:<clusterId>"` of the feature [hoverPopup] currently describes -- lets a `mousemove` over the SAME feature just reposition the popup instead of rebuilding its content on every pixel of movement. */
    private var hoverKey: String? = null

    /**
     * Bug fix (2026-09-28, found live on PdV -- table populated, map empty): [MemberMapScreen.kt]
     * calls [setPoints] synchronously right after [init] returns, but [init] only *schedules* the
     * source/layers to be added on the map's async `"load"` event -- WebGL context creation and style
     * loading take real time, so [setPoints] almost always runs before [wireLayers] has ever added
     * [MEMBER_MAP_SOURCE_ID]. The old code's `map?.getSource(...) ?: return` silently dropped the data
     * in that race, every single time in practice. [setPoints] now always remembers the latest entries
     * here; [wireLayers] applies them once the source actually exists, and a later [setPoints] call
     * (source already present) still applies immediately as before, this field just also gets updated.
     */
    private var pendingEntries: List<MemberMapEntryDto>? = null

    fun init(): MemberMapInitResult {
        if (!deps.webglAvailable()) return MemberMapInitResult.WEBGL_MISSING
        MemberMapPmtilesProtocol.ensureRegistered()

        val colors = readMemberMapColors()
        val options = js("({})")
        options.container = container
        options.style = JSON.parse(buildBasemapStyleJson(colors))
        options.bounds = MemberMapRules.START_BOUNDS.toTypedArray()
        options.maxBounds = MemberMapRules.MAX_BOUNDS.toTypedArray()
        options.minZoom = MemberMapRules.MIN_ZOOM
        options.maxZoom = MemberMapRules.MAX_ZOOM
        options.dragRotate = false
        options.pitchWithRotate = false
        options.touchPitch = false
        options.attributionControl = false
        options.cooperativeGestures = window.matchMedia("(pointer: coarse)").matches

        val newMap = deps.createMap(options)
        map = newMap
        newMap.on("load") { _: dynamic -> wireLayers(newMap) }
        return MemberMapInitResult.CREATED
    }

    private fun wireLayers(newMap: dynamic) {
        newMap.addSource(MEMBER_MAP_SOURCE_ID, JSON.parse(buildPointsSourceJson()))
        val colors = readMemberMapColors()
        newMap.addLayer(JSON.parse(buildClustersLayerJson(colors)))
        newMap.addLayer(JSON.parse(buildPointsLayerJson(colors)))

        val navOptions = js("({})")
        navOptions.showCompass = false
        newMap.addControl(NavigationControl(navOptions), "top-right")

        val attributionOptions = js("({})")
        attributionOptions.compact = true
        attributionOptions.customAttribution =
            "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a>"
        newMap.addControl(AttributionControl(attributionOptions), "bottom-right")

        wireInteraction(newMap)
        addRegionLabels(newMap)
        addCapitalMarkers(newMap)
        updateZoomBand(newMap)
        newMap.on("zoom") { _: dynamic -> updateZoomBand(newMap) }
        // V1.9.9: "moveend" (fires after BOTH a drag-move and a zoom settle, unlike "zoom"/"zoomend" alone)
        // -- querying `places` on every intermediate drag/zoom frame would be wasted work MapLibre's own
        // gesture animation already coalesces away by the time the user stops interacting.
        newMap.on("moveend") { _: dynamic -> updatePlaceLabels(newMap) }
        updatePlaceLabels(newMap)
        setupThemeObserver(newMap)
        setupResizeObserver(newMap)
        applyPendingEntries()
    }

    /**
     * V1.9.8 "Orientierung" -- places [MEMBER_MAP_LABELS] as plain DOM-element [Marker]s (see that
     * file's KDoc "why DOM markers instead of a PMTiles text layer"). Translates each label's raw
     * German [MemberMapLabel.name] at THIS call site, not in [MEMBER_MAP_LABELS] itself (see that
     * property's own KDoc). `labelMarkers.forEach { it.remove() }` first is defensive only --
     * `wireLayers` fires once per `"load"` event in practice, but nothing prevents a future caller
     * from invoking it twice.
     */
    private fun addRegionLabels(newMap: dynamic) {
        labelMarkers.forEach { it.remove() }
        labelMarkers.clear()
        MEMBER_MAP_LABELS.forEach { label ->
            val element = document.createElement("div") as HTMLElement
            element.className = "lapis-member-map-label lapis-member-map-label--${label.kind.cssSuffix()}"
            element.setAttribute("aria-hidden", "true")
            element.appendChild(document.createTextNode(memberMapLabelText(gettext(label.name))))
            val marker = deps.createMarker(element, arrayOf(label.lon, label.lat))
            marker.addTo(newMap)
            labelMarkers.add(marker)
        }
    }

    /**
     * V1.9.9 "Details & Suche" -- places [MEMBER_MAP_CAPITALS] as DOM [Marker]s (dot + name), the same
     * technique [addRegionLabels] already uses and for the same reason (see that method's KDoc and
     * `MemberMapBasemapStyle.kt`'s class KDoc "no glyph pipeline"). Unlike the region labels, capitals
     * are NOT zoom-band-gated in CSS -- knowing which city is a Bundesland's capital is useful context
     * at every zoom level, not just once zoomed in. Called once per `"load"`, defensive `remove()`
     * sweep first for the same reason [addRegionLabels] has one.
     */
    private fun addCapitalMarkers(newMap: dynamic) {
        capitalMarkers.forEach { it.remove() }
        capitalMarkers.clear()
        MEMBER_MAP_CAPITALS.forEach { capital ->
            val element = document.createElement("div") as HTMLElement
            element.className = "lapis-member-map-capital"
            element.setAttribute("aria-hidden", "true")
            val dot = document.createElement("span") as HTMLElement
            dot.className = "lapis-member-map-capital-dot"
            val name = document.createElement("span") as HTMLElement
            name.className = "lapis-member-map-capital-name"
            name.appendChild(document.createTextNode(gettext(capital.name)))
            element.appendChild(dot)
            element.appendChild(name)
            val marker = deps.createMarker(element, arrayOf(capital.lon, capital.lat))
            marker.addTo(newMap)
            capitalMarkers.add(marker)
        }
    }

    /**
     * V1.9.9 "Details & Suche" -- small-locality labels sourced from the bundled PMTiles basemap's own
     * `places` vector source-layer, WITHOUT ever adding a MapLibre style/symbol layer for it (see
     * `MemberMapBasemapStyle.kt`'s class KDoc for why: no `glyphs` URL, no new server route, no
     * vendored font assets). `map.querySourceFeatures(sourceId, {sourceLayer})` reads already-fetched
     * vector-tile bytes directly -- it works whether or not a style layer renders that source-layer at
     * all, which is exactly the gap this method exploits.
     *
     * **Guarded, not assumed, on two fronts**: `querySourceFeatures` itself may not exist on whatever
     * `deps.createMap` produced (every jsTest fakes [MemberMapDeps.createMap] with a plain object that
     * does NOT define it -- see `MemberMapMapControllerTest`'s `fakeMap()` -- deliberately, so existing
     * tests never need to know about this V1.9.9 addition unless they opt in); and the zoom gate
     * ([PLACE_LABELS_MIN_ZOOM]) avoids even attempting the query while zoomed out to the Bundesland
     * overview, where the underlying z10 tile's `places` features would mostly still be off-screen
     * clutter-in-waiting rather than a locality actually near the visible area.
     *
     * Always fully clears and rebuilds [placeLabelMarkers] rather than diffing -- this runs at most
     * once per `"moveend"`, a user-paced event, so the DOM churn is negligible and a diff would be
     * meaningfully more code for no measurable benefit.
     */
    private fun updatePlaceLabels(newMap: dynamic) {
        placeLabelMarkers.forEach { it.remove() }
        placeLabelMarkers.clear()
        if ((newMap.getZoom() as Double) < PLACE_LABELS_MIN_ZOOM) return
        val queryFn = newMap.querySourceFeatures
        if (jsTypeOf(queryFn) != "function") return

        val options = js("({})")
        options.sourceLayer = "places"
        val rawFeatures = newMap.querySourceFeatures("member-map-basemap", options)
        val length = (rawFeatures?.length as? Int) ?: 0
        val candidates = mutableListOf<PlaceLabelCandidate>()
        for (i in 0 until length) {
            val feature = rawFeatures[i]
            val props = feature.properties
            // `props["name:de"]` (bracket indexing), not `.name:de` -- a colon is not a legal Kotlin
            // identifier character, and `dynamic` indexing compiles to plain JS bracket access.
            val name = (props.name as? String) ?: (props["name:de"] as? String) ?: continue
            val coordinates = feature.geometry.coordinates
            val lon = (coordinates[0] as? Double) ?: continue
            val lat = (coordinates[1] as? Double) ?: continue
            candidates += PlaceLabelCandidate(name = name, lon = lon, lat = lat)
        }

        val excluded = (MEMBER_MAP_LABELS.map { it.name } + MEMBER_MAP_CAPITALS.map { it.name }).toSet()
        val selected = selectPlaceLabels(candidates, excluded, PLACE_LABELS_MAX_COUNT)
        selected.forEach { place ->
            val element = document.createElement("div") as HTMLElement
            element.className = "lapis-member-map-place-label"
            element.setAttribute("aria-hidden", "true")
            element.appendChild(document.createTextNode(place.name))
            val marker = deps.createMarker(element, arrayOf(place.lon, place.lat))
            marker.addTo(newMap)
            placeLabelMarkers.add(marker)
        }
    }

    /** Writes the current [memberMapZoomBand] onto `data-zoom-band` -- `theme.css` does the actual per-band label visibility. */
    private fun updateZoomBand(newMap: dynamic) {
        container.setAttribute("data-zoom-band", memberMapZoomBand(newMap.getZoom() as Double))
    }

    /** Applies [pendingEntries] to the source if both exist yet -- see that field's own KDoc for why this is needed at all. */
    private fun applyPendingEntries() {
        val entries = pendingEntries ?: return
        val source = map?.getSource(MEMBER_MAP_SOURCE_ID) ?: return
        source.setData(JSON.parse(buildPointsGeoJson(entries)))
        pendingEntries = null
    }

    private fun wireInteraction(newMap: dynamic) {
        newMap.on("mouseenter", MEMBER_MAP_CLUSTERS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "pointer" }
        newMap.on("mouseleave", MEMBER_MAP_CLUSTERS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "" }
        newMap.on("mouseenter", MEMBER_MAP_POINTS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "pointer" }
        newMap.on("mouseleave", MEMBER_MAP_POINTS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "" }

        newMap.on("click", MEMBER_MAP_CLUSTERS_LAYER_ID) { e: dynamic -> onClusterClicked(newMap, e) }
        newMap.on("click", MEMBER_MAP_POINTS_LAYER_ID) { e: dynamic -> onPointClicked(newMap, e) }

        // V1.9.8 "Orientierung" -- hover tooltip, only on devices with an actual pointer (see
        // MemberMapDeps.hoverCapable KDoc). Checked ONCE here, before registering any listener, not
        // inside the handlers themselves -- registering a no-op `mousemove` handler on a touch device
        // would still cost MapLibre a hit-test on every emulated move event for nothing.
        if (deps.hoverCapable()) {
            newMap.on("mousemove", MEMBER_MAP_POINTS_LAYER_ID) { e: dynamic -> onPointHover(newMap, e) }
            newMap.on("mousemove", MEMBER_MAP_CLUSTERS_LAYER_ID) { e: dynamic -> onClusterHover(newMap, e) }
            newMap.on("mouseleave", MEMBER_MAP_POINTS_LAYER_ID) { _: dynamic -> clearHover() }
            newMap.on("mouseleave", MEMBER_MAP_CLUSTERS_LAYER_ID) { _: dynamic -> clearHover() }
        }
    }

    /** Builds a multi-line tooltip `<div>` via `createTextNode` per line -- never `innerHTML`/`setHTML` (Security-Checkliste "XSS", same discipline as [showPopup]). */
    private fun buildTooltipContent(lines: List<String>): HTMLElement {
        val content = document.createElement("div") as HTMLElement
        content.className = "lapis-member-map-tooltip"
        lines.forEach { line ->
            val row = document.createElement("div") as HTMLElement
            row.appendChild(document.createTextNode(line))
            content.appendChild(row)
        }
        return content
    }

    /** Shows/repositions [hoverPopup] for [key] -- rebuilds its content only when [key] changed since the last call (a `mousemove` fires on nearly every pixel while the cursor stays over the same feature). */
    private fun showHoverPopup(
        newMap: dynamic,
        key: String,
        coordinates: dynamic,
        lines: List<String>,
    ) {
        if (key == hoverKey) {
            hoverPopup?.setLngLat(coordinates)
            return
        }
        hoverKey = key
        val existing = hoverPopup
        val popupToUse: dynamic =
            if (existing != null) {
                existing
            } else {
                val options = js("({})")
                options.closeButton = false
                options.closeOnClick = false
                val created = deps.createPopup(options)
                hoverPopup = created
                created
            }
        popupToUse.setDOMContent(buildTooltipContent(lines))
        popupToUse.setLngLat(coordinates)
        popupToUse.addTo(newMap)
    }

    private fun clearHover() {
        hoverPopup?.remove()
        hoverKey = null
    }

    private fun onPointHover(
        newMap: dynamic,
        e: dynamic,
    ) {
        val features = e.features
        if (features == null || (features.length as Int) == 0) return
        val feature = features[0]
        val postalCode = feature.properties.postalCode as? String ?: return
        val placeName = feature.properties.placeName as? String
        val count = (feature.properties.count as? Double)?.toInt() ?: 0
        showHoverPopup(
            newMap,
            key = "p:$postalCode",
            coordinates = feature.geometry.coordinates,
            lines = memberMapPointTooltipLines(placeName, postalCode, count),
        )
    }

    private fun onClusterHover(
        newMap: dynamic,
        e: dynamic,
    ) {
        val features = e.features
        if (features == null || (features.length as Int) == 0) return
        val feature = features[0]
        val clusterId = feature.properties.cluster_id ?: return
        val pointCount = (feature.properties.point_count as? Double)?.toInt() ?: 0
        // `memberSum`, never `point_count` -- same distinction as `clusterRadiusExpression`'s own
        // KDoc "Pflichttest #6": `point_count` counts grouped POINTS, not the members they carry.
        val memberSum = (feature.properties.memberSum as? Double)?.toInt() ?: 0
        showHoverPopup(
            newMap,
            key = "c:$clusterId",
            coordinates = feature.geometry.coordinates,
            lines = memberMapClusterTooltipLines(pointCount, memberSum),
        )
    }

    /**
     * ±12px box around the click point, exactly matching a fingertip's imprecision -- a single-pixel
     * hit test misses on touch devices.
     *
     * **Bug fix (V1.9.8)**: `getClusterExpansionZoom` is `Promise<number>`-returning in the pinned
     * `maplibre-gl` 5.24.0 (`getClusterExpansionZoom(clusterId: number): Promise<number>`), NOT the
     * Node-style `(error, zoom) => void` callback the old code passed -- that callback was simply
     * never invoked, so a cluster click never zoomed in. `.then`/`.catch` on the `dynamic` promise
     * work as ordinary dynamic calls; a rejection (malformed/unknown cluster id) is swallowed rather
     * than left as an unhandled-rejection console error, same posture as the rest of this class's
     * defensive early-returns.
     */
    private fun onClusterClicked(
        newMap: dynamic,
        e: dynamic,
    ) {
        val point = e.point
        val box = arrayOf(arrayOf(point.x - 12, point.y - 12), arrayOf(point.x + 12, point.y + 12))
        val queryOptions = js("({})")
        queryOptions.layers = arrayOf(MEMBER_MAP_CLUSTERS_LAYER_ID)
        val features = newMap.queryRenderedFeatures(box, queryOptions)
        if ((features.length as Int) == 0) return
        val feature = features[0]
        val clusterId = feature.properties.cluster_id
        val source = newMap.getSource(MEMBER_MAP_SOURCE_ID)
        // clearHover(), not just `hoverPopup?.remove()` -- see onPointClicked's own comment on the
        // same call below for why leaving `hoverKey` stale here strands the tooltip invisible.
        clearHover()
        val expansion =
            source.getClusterExpansionZoom(clusterId).then { zoom: dynamic ->
                val cappedZoom = kotlin.math.min((zoom as Double), MemberMapRules.MAX_ZOOM.toDouble())
                val easeOptions = js("({})")
                easeOptions.center = feature.geometry.coordinates
                easeOptions.zoom = cappedZoom
                newMap.easeTo(easeOptions)
            }
        expansion.catch { _: dynamic -> Unit }
    }

    private fun onPointClicked(
        newMap: dynamic,
        e: dynamic,
    ) {
        val features = e.features
        if (features == null || (features.length as Int) == 0) return
        val feature = features[0]
        val postalCode = feature.properties.postalCode as? String ?: return
        val placeName = feature.properties.placeName as? String ?: postalCode
        val count = (feature.properties.count as? Double)?.toInt() ?: 0
        // clearHover(), not just `hoverPopup?.remove()` -- `remove()` alone left `hoverKey` pointing at
        // this feature, so a later `mousemove` still over it (no genuine `mouseleave` in between, just
        // MapLibre's own tiny-jitter re-fire) took `showHoverPopup`'s `key == hoverKey` fast path, which
        // only calls `setLngLat` and never `addTo(newMap)` again -- the already-`remove()`d popup stayed
        // detached/invisible until an actual `mouseleave` or a hover over a DIFFERENT feature happened.
        clearHover()
        showPopup(newMap, feature.geometry.coordinates, placeName, count)
        onFeatureClicked(postalCode)
    }

    /** [Popup.setDOMContent], never `setHTML` -- [placeName] is GeoNames free text (Security-Checkliste "XSS"). */
    private fun showPopup(
        newMap: dynamic,
        coordinates: dynamic,
        placeName: String,
        count: Int,
    ) {
        popup?.remove()
        val content = document.createElement("div") as HTMLElement
        val title = document.createElement("div") as HTMLElement
        title.appendChild(document.createTextNode(placeName))
        val countLine = document.createElement("div") as HTMLElement
        countLine.appendChild(document.createTextNode(memberMapPopupCountText(count)))
        content.appendChild(title)
        content.appendChild(countLine)

        val popupOptions = js("({})")
        popupOptions.closeButton = true
        val newPopup = deps.createPopup(popupOptions)
        newPopup.setLngLat(coordinates)
        newPopup.setDOMContent(content)
        newPopup.addTo(newMap)
        popup = newPopup
    }

    /**
     * Feeds fresh RPC data into the source (never rebuilds the style/layers, see class KDoc). Always
     * remembers [entries] in [pendingEntries] first -- the source may not exist yet (see that field's
     * KDoc), in which case [wireLayers] applies it once `"load"` fires instead of this call doing it.
     */
    fun setPoints(entries: List<MemberMapEntryDto>) {
        pendingEntries = entries
        applyPendingEntries()
    }

    /** A table-row click: centers the map on the entry at [MemberMapRules.FLY_TO_ZOOM] -- `jumpTo` (no animation) under `prefers-reduced-motion`. */
    fun flyToEntry(entry: MemberMapEntryDto) {
        flyTo(entry.lon ?: return, entry.lat ?: return, MemberMapRules.FLY_TO_ZOOM.toDouble())
    }

    /** V1.9.9 Ortssuche: an accepted search result -- same `prefers-reduced-motion` branch as [flyToEntry], but [MemberMapRules.PLACE_FLY_TO_ZOOM] (deeper, see that constant's own KDoc). */
    fun flyToPlace(
        lon: Double,
        lat: Double,
    ) {
        flyTo(lon, lat, MemberMapRules.PLACE_FLY_TO_ZOOM.toDouble())
    }

    private fun flyTo(
        lon: Double,
        lat: Double,
        zoom: Double,
    ) {
        val currentMap = map ?: return
        val options = js("({})")
        options.center = arrayOf(lon, lat)
        options.zoom = zoom
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
            currentMap.jumpTo(options)
        } else {
            currentMap.flyTo(options)
        }
    }

    /**
     * V1.9.9 Ortssuche: shows (or replaces) the one search-result pin, plus a popup with [content] --
     * `setDOMContent`, never `setHTML` (same XSS discipline as [showPopup]/[buildTooltipContent];
     * [content] is built by the caller, [MemberMapScreen.kt], from server-returned place names/counts).
     * Only ONE pin at a time (a new search clears the old one first), same "one thing shown" posture
     * as [popup]/[hoverPopup].
     */
    fun showSearchPin(
        lon: Double,
        lat: Double,
        content: HTMLElement,
    ) {
        val currentMap = map ?: return
        clearSearchPin()
        val pinElement = document.createElement("div") as HTMLElement
        pinElement.className = "lapis-member-map-search-pin"
        val marker = deps.createMarker(pinElement, arrayOf(lon, lat))
        marker.addTo(currentMap)
        searchPinMarker = marker

        val popupOptions = js("({})")
        popupOptions.closeButton = true
        popupOptions.className = "lapis-member-map-search-pin-popup"
        val newPopup = deps.createPopup(popupOptions)
        newPopup.setLngLat(arrayOf(lon, lat))
        newPopup.setDOMContent(content)
        newPopup.addTo(currentMap)
        // `maplibre-gl`'s `Popup.remove()` ALWAYS fires `"close"`, whether triggered programmatically
        // (this class's own `clearSearchPin()`) or by the popup's own native "×" button (`closeButton =
        // true` above) calling `remove()` on itself internally -- listening here is what makes the pin
        // MARKER disappear together with the popup when the board member uses that native "×", instead
        // of being left stranded on the map with no way to remove it (bug found 2026-09-28: this listener
        // was missing even though [clearSearchPin]'s own KDoc already claimed the "×" cleared the pin).
        newPopup.on("close") { clearSearchPin() }
        searchPinPopup = newPopup
    }

    /**
     * Removes the Ortssuche pin/popup, if any -- called on a new search ([showSearchPin]'s own
     * defensive call before showing the next pin), the popup's own native "×" (via the `"close"` event
     * [showSearchPin] wires, see its KDoc), or [destroy].
     *
     * Nulls both fields out FIRST, then calls `.remove()` on the captured locals -- not the other way
     * around. `Popup.remove()` fires `"close"` synchronously, which re-enters this very function (the
     * listener [showSearchPin] wires); nulling first makes that reentrant call see already-`null` fields
     * and no-op, instead of calling `.remove()` a second time on an object mid-removal.
     */
    fun clearSearchPin() {
        val marker = searchPinMarker
        val markerPopup = searchPinPopup
        searchPinMarker = null
        searchPinPopup = null
        marker?.remove()
        markerPopup?.remove()
    }

    private fun setupThemeObserver(newMap: dynamic) {
        themeObserver = deps.createThemeObserver { applyColors(newMap, readMemberMapColors()) }
    }

    private fun setupResizeObserver(newMap: dynamic) {
        val observer = ResizeObserver { _, _ -> newMap.resize() }
        observer.observe(container)
        resizeObserver = observer
    }

    /** The map's ten color paint properties -- see [MemberMapColors] KDoc. */
    private fun applyColors(
        newMap: dynamic,
        colors: MemberMapColors,
    ) {
        newMap.setPaintProperty("member-map-land", "fill-color", colors.land)
        newMap.setPaintProperty("member-map-water", "fill-color", colors.water)
        newMap.setPaintProperty("member-map-state-borders", "line-color", colors.stateBorder)
        newMap.setPaintProperty("member-map-country-borders", "line-color", colors.countryBorder)
        // The halo sits UNDER "member-map-country-borders" (MemberMapBasemapStyle.kt) and is initially
        // painted with the same colors.countryBorder -- it must be kept in sync here too, or a theme
        // switch leaves the halo showing the OLD color while the solid border on top switches to the new
        // one (visibly wrong two-tone border around the whole country).
        newMap.setPaintProperty("member-map-country-borders-halo", "line-color", colors.countryBorder)
        newMap.setPaintProperty("member-map-highways", "line-color", colors.highway)
        newMap.setPaintProperty(MEMBER_MAP_POINTS_LAYER_ID, "circle-color", colors.pointFill)
        newMap.setPaintProperty(MEMBER_MAP_POINTS_LAYER_ID, "circle-stroke-color", colors.pointStroke)
        newMap.setPaintProperty(MEMBER_MAP_CLUSTERS_LAYER_ID, "circle-color", colors.clusterFill)
        newMap.setPaintProperty(MEMBER_MAP_CLUSTERS_LAYER_ID, "circle-stroke-color", colors.clusterStroke)
    }

    fun destroy() {
        popup?.remove()
        popup = null
        hoverPopup?.remove()
        hoverPopup = null
        hoverKey = null
        clearSearchPin()
        labelMarkers.forEach { it.remove() }
        labelMarkers.clear()
        capitalMarkers.forEach { it.remove() }
        capitalMarkers.clear()
        placeLabelMarkers.forEach { it.remove() }
        placeLabelMarkers.clear()
        themeObserver?.disconnect()
        themeObserver = null
        resizeObserver?.disconnect()
        resizeObserver = null
        map?.remove()
        map = null
    }
}
