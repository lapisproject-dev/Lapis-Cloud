package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.chart.MutationObserver
import network.lapis.cloud.client.maplibre.AttributionControl
import network.lapis.cloud.client.maplibre.Map
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
 * The two side-effecting collaborators of [MemberMapMapController], injectable so a test can fake both
 * without ever constructing a real `maplibre-gl` `Map`/WebGL context -- see this class's own KDoc
 * "Stolperfalle" #2. Production always uses the defaults.
 */
internal class MemberMapDeps(
    val createMap: (dynamic) -> dynamic = { options -> Map(options) },
    val webglAvailable: () -> Boolean = ::detectWebglAvailable,
)

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
 * (`source.setData(...)`), it never rebuilds the style or the layers.
 *
 * **Theme sync**: a [MutationObserver] on `data-theme` re-reads [readMemberMapColors] and calls
 * `map.setPaintProperty(...)` for the map's nine color paint properties -- the exact same pattern
 * `PriceOracleScreen.kt` already uses for Chart.js, just `setPaintProperty` instead of `chart.update()`.
 * The style itself is never rebuilt on a theme change (Design-Team decision, see
 * `MemberMapBasemapStyle.buildBasemapStyleJson` KDoc "Q Duarte").
 *
 * **Cleanup**: [destroy] removes any open popup, disconnects both observers and removes the map --
 * called from `MemberMapScreen.kt`'s `addAfterDestroyHook`, same idiom as `PriceOracleScreen.kt`'s
 * `teardownChart`.
 */
internal class MemberMapMapController(
    private val container: HTMLElement,
    private val deps: MemberMapDeps = MemberMapDeps(),
    private val onFeatureClicked: (postalCode: String) -> Unit,
) {
    private var map: dynamic = null
    private var popup: Popup? = null
    private var themeObserver: MutationObserver? = null
    private var resizeObserver: ResizeObserver? = null

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
        setupThemeObserver(newMap)
        setupResizeObserver(newMap)
    }

    private fun wireInteraction(newMap: dynamic) {
        newMap.on("mouseenter", MEMBER_MAP_CLUSTERS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "pointer" }
        newMap.on("mouseleave", MEMBER_MAP_CLUSTERS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "" }
        newMap.on("mouseenter", MEMBER_MAP_POINTS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "pointer" }
        newMap.on("mouseleave", MEMBER_MAP_POINTS_LAYER_ID) { _: dynamic -> newMap.getCanvas().style.cursor = "" }

        newMap.on("click", MEMBER_MAP_CLUSTERS_LAYER_ID) { e: dynamic -> onClusterClicked(newMap, e) }
        newMap.on("click", MEMBER_MAP_POINTS_LAYER_ID) { e: dynamic -> onPointClicked(newMap, e) }
    }

    /** ±12px box around the click point, exactly matching a fingertip's imprecision -- a single-pixel hit test misses on touch devices. */
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
        source.getClusterExpansionZoom(clusterId) { _: dynamic, zoom: dynamic ->
            if (zoom == null) return@getClusterExpansionZoom
            val cappedZoom = kotlin.math.min((zoom as Double), MemberMapRules.MAX_ZOOM.toDouble())
            val easeOptions = js("({})")
            easeOptions.center = feature.geometry.coordinates
            easeOptions.zoom = cappedZoom
            newMap.easeTo(easeOptions)
        }
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
        val newPopup = Popup(popupOptions)
        newPopup.setLngLat(coordinates)
        newPopup.setDOMContent(content)
        newPopup.addTo(newMap)
        popup = newPopup
    }

    /** Feeds fresh RPC data into the already-built source (never rebuilds the style/layers, see class KDoc). */
    fun setPoints(entries: List<MemberMapEntryDto>) {
        val source = map?.getSource(MEMBER_MAP_SOURCE_ID) ?: return
        source.setData(JSON.parse(buildPointsGeoJson(entries)))
    }

    /** A table-row click: centers the map on the entry at [MemberMapRules.FLY_TO_ZOOM] -- `jumpTo` (no animation) under `prefers-reduced-motion`. */
    fun flyToEntry(entry: MemberMapEntryDto) {
        val lat = entry.lat ?: return
        val lon = entry.lon ?: return
        val currentMap = map ?: return
        val options = js("({})")
        options.center = arrayOf(lon, lat)
        options.zoom = MemberMapRules.FLY_TO_ZOOM
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
            currentMap.jumpTo(options)
        } else {
            currentMap.flyTo(options)
        }
    }

    private fun setupThemeObserver(newMap: dynamic) {
        val observer =
            MutationObserver { _, _ ->
                applyColors(newMap, readMemberMapColors())
            }
        val observerOptions = js("({})")
        observerOptions.attributes = true
        observerOptions.attributeFilter = arrayOf("data-theme")
        observer.observe(document.documentElement!!, observerOptions)
        themeObserver = observer
    }

    private fun setupResizeObserver(newMap: dynamic) {
        val observer = ResizeObserver { _, _ -> newMap.resize() }
        observer.observe(container)
        resizeObserver = observer
    }

    /** The map's nine color paint properties -- see [MemberMapColors] KDoc. */
    private fun applyColors(
        newMap: dynamic,
        colors: MemberMapColors,
    ) {
        newMap.setPaintProperty("member-map-land", "fill-color", colors.land)
        newMap.setPaintProperty("member-map-water", "fill-color", colors.water)
        newMap.setPaintProperty("member-map-state-borders", "line-color", colors.stateBorder)
        newMap.setPaintProperty("member-map-country-borders", "line-color", colors.countryBorder)
        newMap.setPaintProperty("member-map-highways", "line-color", colors.highway)
        newMap.setPaintProperty(MEMBER_MAP_POINTS_LAYER_ID, "circle-color", colors.pointFill)
        newMap.setPaintProperty(MEMBER_MAP_POINTS_LAYER_ID, "circle-stroke-color", colors.pointStroke)
        newMap.setPaintProperty(MEMBER_MAP_CLUSTERS_LAYER_ID, "circle-color", colors.clusterFill)
        newMap.setPaintProperty(MEMBER_MAP_CLUSTERS_LAYER_ID, "circle-stroke-color", colors.clusterStroke)
    }

    fun destroy() {
        popup?.remove()
        popup = null
        themeObserver?.disconnect()
        themeObserver = null
        resizeObserver?.disconnect()
        resizeObserver = null
        map?.remove()
        map = null
    }
}
