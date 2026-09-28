package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import network.lapis.cloud.shared.domain.MemberMapRules
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.6 "Vorstands-Karte" -- [MemberMapMapController] with FAKED [MemberMapDeps], never a real
 * `maplibre-gl` `Map`/WebGL context (see that class's own KDoc "Stolperfalle" #2: a real map would try
 * to load `/api/board/member-map/basemap.pmtiles`, which does not exist under Karma, and could hang).
 * A plain detached `div` (never mounted into a KVision `Root`) is enough here -- the controller only
 * ever touches the element it is given directly, it does not rely on snabbdom lifecycle hooks itself.
 */
class MemberMapMapControllerTest {
    private fun detachedDiv(): HTMLElement = document.createElement("div") as HTMLElement

    /**
     * A minimal fake "map" object carrying only the methods [MemberMapMapController] calls -- see
     * [MemberMapDeps.createMap]. `getZoom` defaults to `0.0` (V1.9.8: [MemberMapMapController.updateZoomBand]
     * calls it unconditionally once `wireLayers` runs, i.e. on every test that fires `"load"`).
     */
    private fun fakeMap(): dynamic {
        val calls = js("({})")
        calls.removed = false
        val map = js("({})")
        map.calls = calls
        map.addSource = { _: dynamic, _: dynamic -> }
        map.addLayer = { _: dynamic -> }
        map.addControl = { _: dynamic, _: dynamic -> }
        map.on = { _: dynamic, second: dynamic, third: dynamic -> }
        map.off = { _: dynamic, _: dynamic -> }
        map.getCanvas = { js("({style: {}})") }
        map.getZoom = { 0.0 }
        map.resize = {}
        map.remove = { calls.removed = true }
        return map
    }

    /**
     * A minimal fake `Marker` -- see [MemberMapDeps.createMarker]. Counts `remove()` calls so
     * [destroy_removesAllTwentyFourLabelMarkersPlusSixteenCapitalMarkers] can assert every marker
     * (24 [MEMBER_MAP_LABELS] + 16 [MEMBER_MAP_CAPITALS], V1.9.9) was torn down.
     */
    private fun fakeMarker(onRemove: () -> Unit = {}): dynamic {
        val marker = js("({})")
        marker.addTo = { _: dynamic -> marker }
        marker.setLngLat = { _: dynamic -> marker }
        marker.remove = { onRemove() }
        return marker
    }

    /**
     * A minimal fake theme observer -- see [MemberMapDeps.createThemeObserver]. `wireLayers` calls
     * `setupThemeObserver` UNCONDITIONALLY on every `"load"` fire, so every test in this file that
     * fires `"load"` must pass `createThemeObserver = { _ -> fakeThemeObserver() }`, never letting the
     * default (production) implementation run: that default calls `observer.observe(...)` on the REAL,
     * SUITE-SHARED `document.documentElement` (Karma runs every test in one browser tab), and this
     * class's tests never mount into a real KVision `Root`, so nothing else ever calls
     * `document.documentElement.setAttribute("data-theme", ...)` to trigger it during THIS test -- but
     * a completely unrelated LATER test that changes `data-theme` (e.g. a theme-toggle test elsewhere
     * in the suite) fires the callback anyway, against this test's already-finished `newMap`/`applyColors`
     * closure. Bug found 2026-09-28 in a full `clean check --no-build-cache --rerun-tasks` run: a stray,
     * never-`disconnect()`ed real observer from an earlier `MemberMapMapControllerTest` made
     * `ReportScreensDomTest.financialReports_failedLoadIsAnErrorStateWithRetry`'s `data-theme` change
     * throw `TypeError: newMap.setPaintProperty is not a function` -- a fake `map` from a LONG-FINISHED
     * test has no such method. Using this fake instead (never touching the real DOM at all) makes that
     * class of cross-test leak structurally impossible rather than merely disciplined-away by
     * `controller.destroy()` in every test.
     */
    private fun fakeThemeObserver(onDisconnect: () -> Unit = {}): dynamic {
        val observer = js("({})")
        observer.disconnect = { onDisconnect() }
        return observer
    }

    /**
     * A minimal fake `Popup` -- see [MemberMapDeps.createPopup]. Counts `setDOMContent`/`remove()` calls
     * for the hover-tooltip tests. `on("close", ...)`/`remove()` mirror real `maplibre-gl`: `Popup.remove()`
     * ALWAYS fires a `"close"` event, whether it was called programmatically (`clearSearchPin`) or by the
     * popup's own native "×" button internally calling `remove()` on itself -- this fake's `remove()`
     * therefore also invokes every registered `"close"` listener, so a test can simulate "the user clicked
     * the native ×" simply by calling `.remove()` directly on the captured popup, bypassing the controller
     * entirely (see `showSearchPin_thenNativePopupClose_alsoRemovesTheMarker`).
     */
    private fun fakePopup(
        onSetDomContent: () -> Unit = {},
        onRemove: () -> Unit = {},
    ): dynamic {
        val popup = js("({})")
        val closeListeners = mutableListOf<() -> Unit>()
        popup.setLngLat = { _: dynamic -> popup }
        popup.setDOMContent = { _: dynamic ->
            onSetDomContent()
            popup
        }
        popup.addTo = { _: dynamic -> popup }
        popup.on = { type: dynamic, listener: dynamic ->
            if (type == "close") {
                @Suppress("UNCHECKED_CAST")
                closeListeners.add(listener as () -> Unit)
            }
            popup
        }
        popup.remove = {
            onRemove()
            closeListeners.forEach { it() }
            popup
        }
        return popup
    }

    /**
     * A fake map's event registry -- tracks every `on(...)` call MapLibre's real `Map` accepts, BOTH
     * the 2-arg `(type, listener)` overload (`"load"`/`"zoom"`) and the 3-arg `(type, layerId,
     * listener)` overload (`"click"`/`"mousemove"`/`"mouseleave"` on a specific layer), distinguished
     * by `jsTypeOf` on the second argument (a function for the 2-arg form, a layer-id string for the
     * 3-arg form). Lets a test both COUNT registrations for an event type (e.g. "exactly two
     * `mousemove` listeners when hover-capable") and FIRE one specific `(event, layer)` pair.
     */
    private class FakeEventRegistry {
        private data class Reg(
            val event: String,
            val layer: String?,
            val callback: (dynamic) -> Unit,
        )

        private val regs = mutableListOf<Reg>()

        fun on(
            event: dynamic,
            a: dynamic,
            b: dynamic,
        ) {
            val ev = event as String
            @Suppress("UNCHECKED_CAST")
            if (jsTypeOf(a) == "function") {
                regs.add(Reg(ev, null, a as (dynamic) -> Unit))
            } else {
                regs.add(Reg(ev, a as String, b as (dynamic) -> Unit))
            }
        }

        fun count(event: String): Int = regs.count { it.event == event }

        fun fire(
            event: String,
            layer: String?,
            payload: dynamic = null,
        ) {
            regs.filter { it.event == event && it.layer == layer }.forEach { it.callback(payload) }
        }
    }

    /** [fakeMap] wired so every `on(...)` call lands in a fresh [FakeEventRegistry] instead of being dropped. */
    private fun fakeMapWithRegistry(): Pair<dynamic, FakeEventRegistry> {
        val registry = FakeEventRegistry()
        val map = fakeMap()
        map.on = { event: dynamic, a: dynamic, b: dynamic -> registry.on(event, a, b) }
        // NOT `map to registry` -- `to` is an infix call, and on a `dynamic` receiver Kotlin/JS
        // compiles ANY member-looking call (including a stdlib infix extension) as a dynamic
        // property/method lookup on the underlying JS object, not as `kotlin.to`. `Pair(...)` is an
        // ordinary constructor call, unaffected by the dynamic receiver.
        return Pair(map, registry)
    }

    /** Builds a `mousemove`/`click`-shaped fake MapLibre feature event: one feature with [properties] set on it. */
    private fun fakeFeatureEvent(coordinates: Array<Double> = arrayOf(10.0, 52.0)): Pair<dynamic, dynamic> {
        val feature = js("({})")
        feature.properties = js("({})")
        feature.geometry = js("({})")
        feature.geometry.coordinates = coordinates
        val event = js("({})")
        event.features = arrayOf(feature)
        return Pair(event, feature) // not `event to feature` -- see fakeMapWithRegistry's own KDoc on dynamic + infix `to`
    }

    @Test
    fun init_webglMissing_neverCreatesAMap() {
        var createCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ ->
                    createCalls++
                    fakeMap()
                },
                webglAvailable = { false },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        val result = controller.init()
        assertEquals(MemberMapInitResult.WEBGL_MISSING, result)
        assertEquals(0, createCalls, "no map may be constructed when WebGL is unavailable")
    }

    @Test
    fun init_webglAvailable_createsExactlyOneMap() {
        var createCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ ->
                    createCalls++
                    fakeMap()
                },
                webglAvailable = { true },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        val result = controller.init()
        assertEquals(MemberMapInitResult.CREATED, result)
        assertEquals(1, createCalls)
    }

    @Test
    fun destroy_beforeLoadFired_neverThrows_andRemovesTheMap() {
        var removed = false
        val deps =
            MemberMapDeps(
                createMap = { _ ->
                    val map = fakeMap()
                    map.remove = { removed = true }
                    map
                },
                webglAvailable = { true },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        controller.destroy()
        assertTrue(removed, "destroy() must call map.remove() even if the map's own 'load' event never fired")
    }

    @Test
    fun setPoints_beforeMapExists_neverThrows() {
        val deps = MemberMapDeps(createMap = { _ -> fakeMap() }, webglAvailable = { false })
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init() // WEBGL_MISSING -- no map was ever created
        // Must be a no-op, not a NullPointerException/dynamic-call-on-undefined crash.
        controller.setPoints(
            listOf(MemberMapEntryDto(postalCode = "38100", placeName = "Braunschweig", lat = 52.27, lon = 10.52, count = 3)),
        )
    }

    /**
     * Regression test for the bug found live on PdV 2026-09-28: the table showed real data, the map
     * showed no circles at all. Root cause -- [MemberMapScreen.kt] calls [MemberMapMapController.setPoints]
     * synchronously right after [MemberMapMapController.init] returns, but the source is only added
     * once the map's `"load"` event fires, which is genuinely async (WebGL context + style loading).
     * The old code's `map?.getSource(...) ?: return` silently dropped the data in that race, every
     * single time in practice -- this test reproduces exactly that ordering (`setPoints` BEFORE
     * `"load"` fires) and asserts the data still reaches the source once `"load"` does fire.
     */
    @Test
    fun setPoints_calledBeforeLoadFires_stillReachesTheSourceOnceLoadFires() {
        var loadCallback: (() -> Unit)? = null
        var addSourceCalls = 0
        val setDataCalls = mutableListOf<String>()
        val fakeSource = js("({})")
        fakeSource.setData = { data: dynamic -> setDataCalls.add(JSON.stringify(data)) }
        val deps =
            MemberMapDeps(
                createMap = { _ ->
                    val map = fakeMap()
                    map.on = { event: dynamic, callback: dynamic, _: dynamic ->
                        if (event == "load") loadCallback = { (callback as (dynamic) -> Unit)(null) }
                    }
                    map.addSource = { _: dynamic, _: dynamic -> addSourceCalls++ }
                    // Only resolves once "load" has actually run -- matches the real MapLibre contract
                    // (getSource on an unknown id returns undefined, never throws).
                    map.getSource = { _: dynamic -> if (addSourceCalls > 0) fakeSource else undefined }
                    map
                },
                webglAvailable = { true },
                // V1.9.8 "Orientierung": `wireLayers` now also calls `addRegionLabels`/`updateZoomBand`,
                // which by default construct a REAL `maplibre-gl` `Marker` and read `newMap.getZoom()` --
                // neither exists on this test's fake map (see `fakeMap()`'s own KDoc). Faking both keeps
                // this pinned regression test exercising exactly what it always exercised (the
                // `setPoints`/`"load"` race), without it now also being a test of the orientation layer.
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()

        val entries =
            listOf(MemberMapEntryDto(postalCode = "38100", placeName = "Braunschweig", lat = 52.27, lon = 10.52, count = 3))
        controller.setPoints(entries) // races ahead of "load" -- must not throw, must not lose the data

        assertEquals(0, setDataCalls.size, "the source cannot receive data before it exists yet")

        loadCallback?.invoke() // simulates the map's real async "load" event firing

        assertEquals(1, setDataCalls.size, "the pending entries must be applied once the source exists")
        assertTrue(setDataCalls.single().contains("38100"), "the applied data must be the entries passed to setPoints, not empty")
    }

    @Test
    fun flyToEntry_withoutCoordinates_neverThrows_andDoesNothing() {
        var flyToCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ ->
                    val map = fakeMap()
                    map.flyTo = { _: dynamic -> flyToCalls++ }
                    map.jumpTo = { _: dynamic -> flyToCalls++ }
                    map
                },
                webglAvailable = { true },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        controller.flyToEntry(MemberMapEntryDto(postalCode = "99999", placeName = null, lat = null, lon = null, count = 1))
        assertEquals(0, flyToCalls, "an unresolved entry (no lat/lon) must never move the map")
    }

    // ── V1.9.8 "Orientierung": region labels + zoom band ───────────────────────────────────────

    @Test
    fun wireLayers_createsExactlyTwentyFourLabelMarkersPlusSixteenCapitalMarkers_andSetsInitialZoomBand() {
        val (map, registry) = fakeMapWithRegistry()
        var markerCreateCalls = 0
        val container = detachedDiv()
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ ->
                    markerCreateCalls++
                    fakeMarker()
                },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = container, deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        // MEMBER_MAP_LABELS.size (24) + MEMBER_MAP_CAPITALS.size (16, V1.9.9) -- [fakeMap] has no
        // `querySourceFeatures`, so `updatePlaceLabels` guards itself out and contributes zero.
        assertEquals(40, markerCreateCalls, "MEMBER_MAP_LABELS.size + MEMBER_MAP_CAPITALS.size must equal the number of markers created")
        assertEquals("low", container.getAttribute("data-zoom-band"), "getZoom() == 0.0 (fakeMap default) must map to zoom band \"low\"")
    }

    @Test
    fun wireLayers_zoomEvent_updatesTheZoomBandAttribute() {
        val (map, registry) = fakeMapWithRegistry()
        var currentZoom = 0.0
        map.getZoom = { currentZoom }
        val container = detachedDiv()
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = container, deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)
        assertEquals("low", container.getAttribute("data-zoom-band"))

        currentZoom = 9.0
        registry.fire("zoom", null)
        assertEquals("high", container.getAttribute("data-zoom-band"))
    }

    @Test
    fun destroy_removesAllTwentyFourLabelMarkersPlusSixteenCapitalMarkers() {
        val (map, registry) = fakeMapWithRegistry()
        var markerRemoveCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker(onRemove = { markerRemoveCalls++ }) },
                hoverCapable = { false },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        controller.destroy()

        assertEquals(40, markerRemoveCalls)
    }

    // ── V1.9.8 "Orientierung": hover tooltip ────────────────────────────────────────────────────

    @Test
    fun wireLayers_hoverCapableFalse_registersNoMousemoveListeners() {
        val (map, registry) = fakeMapWithRegistry()
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        assertEquals(0, registry.count("mousemove"), "a touch device must never register a mousemove-driven tooltip")
    }

    @Test
    fun wireLayers_hoverCapableTrue_registersExactlyTwoMousemoveListeners() {
        val (map, registry) = fakeMapWithRegistry()
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { true },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        assertEquals(2, registry.count("mousemove"), "one mousemove listener per layer (points + clusters)")
    }

    @Test
    fun onPointHover_buildsContentViaTextNodes_neverSetHTML() {
        val (map, registry) = fakeMapWithRegistry()
        var setDomContentCalls = 0
        var setHtmlCalls = 0
        var lastContent: HTMLElement? = null
        val popup = js("({})")
        popup.setLngLat = { _: dynamic -> popup }
        popup.addTo = { _: dynamic -> popup }
        popup.remove = {}
        popup.setDOMContent = { node: dynamic ->
            setDomContentCalls++
            lastContent = node as HTMLElement
            popup
        }
        popup.setHTML = { _: dynamic ->
            setHtmlCalls++
            popup
        }
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                createPopup = { _ -> popup },
                hoverCapable = { true },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        val (event, feature) = fakeFeatureEvent()
        feature.properties.postalCode = "38100"
        feature.properties.placeName = "<b>x</b>"
        feature.properties.count = 3.0
        registry.fire("mousemove", MEMBER_MAP_POINTS_LAYER_ID, event)

        assertEquals(1, setDomContentCalls)
        assertEquals(0, setHtmlCalls, "the tooltip must never use setHTML -- placeName is untrusted free text")
        val text = lastContent?.textContent ?: ""
        assertTrue(text.contains("<b>x</b>"), "the raw text must be present as inert text content")
        assertFalse(
            (lastContent?.innerHTML ?: "").contains("<b>"),
            "must never be interpreted as a real markup tag (a real <b> would render unescaped in innerHTML)",
        )
    }

    @Test
    fun onPointHover_sameFeatureTwice_rebuildsContentOnlyOnce() {
        val (map, registry) = fakeMapWithRegistry()
        var setDomContentCalls = 0
        var popupRemoveCalls = 0
        val popup = js("({})")
        popup.setLngLat = { _: dynamic -> popup }
        popup.addTo = { _: dynamic -> popup }
        popup.setDOMContent = { _: dynamic ->
            setDomContentCalls++
            popup
        }
        popup.remove = { popupRemoveCalls++ }
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                createPopup = { _ -> popup },
                hoverCapable = { true },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        val (event, feature) = fakeFeatureEvent()
        feature.properties.postalCode = "38100"
        feature.properties.placeName = "Braunschweig"
        feature.properties.count = 3.0
        registry.fire("mousemove", MEMBER_MAP_POINTS_LAYER_ID, event)
        registry.fire("mousemove", MEMBER_MAP_POINTS_LAYER_ID, event)

        assertEquals(
            1,
            setDomContentCalls,
            "the SAME feature under the cursor must not rebuild the tooltip content on every pixel of movement",
        )

        registry.fire("mouseleave", MEMBER_MAP_POINTS_LAYER_ID)
        assertEquals(1, popupRemoveCalls, "mouseleave must remove the open tooltip")
    }

    @Test
    fun onClusterHover_usesMemberSum_neverPointCount() {
        val (map, registry) = fakeMapWithRegistry()
        var lastLines: List<String> = emptyList()
        val popup = js("({})")
        popup.setLngLat = { _: dynamic -> popup }
        popup.addTo = { _: dynamic -> popup }
        popup.setDOMContent = { node: dynamic ->
            val el = node as HTMLElement
            lastLines =
                (0 until el.children.length).map {
                    el.children
                        .item(it)
                        ?.textContent
                        .orEmpty()
                }
            popup
        }
        popup.remove = {}
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                createPopup = { _ -> popup },
                hoverCapable = { true },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        val (event, feature) = fakeFeatureEvent()
        feature.properties.cluster_id = 7
        feature.properties.point_count = 3.0
        feature.properties.memberSum = 42.0
        registry.fire("mousemove", MEMBER_MAP_CLUSTERS_LAYER_ID, event)

        assertTrue(lastLines[0].contains("3"), "line 1 is the point count")
        assertTrue(lastLines[1].contains("42"), "line 2 must be memberSum, never point_count")
    }

    // ── V1.9.8 bug fix: cluster-click zoom via the real Promise-returning getClusterExpansionZoom ──

    @Test
    fun onClusterClicked_resolvedPromise_easesToTheCappedZoom() =
        GlobalScope.promise {
            val (map, registry) = fakeMapWithRegistry()
            var easeToCalls = 0
            var easeZoom: Double? = null
            map.easeTo = { options: dynamic ->
                easeToCalls++
                easeZoom = options.zoom as Double
            }
            map.queryRenderedFeatures = { _: dynamic, _: dynamic ->
                val (_, feature) = fakeFeatureEvent()
                feature.properties.cluster_id = 7
                arrayOf(feature)
            }
            val source = js("({})")
            // V1.9.9: MemberMapRules.MAX_ZOOM bumped 10 -> 12 (see that constant's own KDoc) --
            // 14.0 is above the NEW cap, same test intent as before the bump.
            source.getClusterExpansionZoom = { _: dynamic -> Promise.resolve(14.0) } // above MemberMapRules.MAX_ZOOM (12)
            map.getSource = { _: dynamic -> source }
            val deps =
                MemberMapDeps(
                    createMap = { _ -> map },
                    webglAvailable = { true },
                    createMarker = { _, _ -> fakeMarker() },
                    hoverCapable = { false },
                    createThemeObserver = { _ -> fakeThemeObserver() },
                )
            val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
            controller.init()
            registry.fire("load", null)

            val clickEvent = js("({})")
            clickEvent.point = js("({})")
            clickEvent.point.x = 10.0
            clickEvent.point.y = 10.0
            registry.fire("click", MEMBER_MAP_CLUSTERS_LAYER_ID, clickEvent)

            // getClusterExpansionZoom's Promise resolves on the microtask queue -- awaiting a resolved
            // Promise here (this whole test function IS a Promise, via GlobalScope.promise) lets that
            // queue drain before the assertions run.
            Promise.resolve(Unit).await()

            assertEquals(1, easeToCalls)
            assertEquals(12.0, easeZoom, "the zoom must be capped at MemberMapRules.MAX_ZOOM, not the raw 14.0 the promise resolved with")
        }

    @Test
    fun onClusterClicked_rejectedPromise_neverThrows_neverEases() =
        GlobalScope.promise {
            val (map, registry) = fakeMapWithRegistry()
            var easeToCalls = 0
            map.easeTo = { _: dynamic -> easeToCalls++ }
            map.queryRenderedFeatures = { _: dynamic, _: dynamic ->
                val (_, feature) = fakeFeatureEvent()
                feature.properties.cluster_id = 7
                arrayOf(feature)
            }
            val source = js("({})")
            source.getClusterExpansionZoom = { _: dynamic -> Promise.reject(Exception("unknown cluster id")) }
            map.getSource = { _: dynamic -> source }
            val deps =
                MemberMapDeps(
                    createMap = { _ -> map },
                    webglAvailable = { true },
                    createMarker = { _, _ -> fakeMarker() },
                    hoverCapable = { false },
                    createThemeObserver = { _ -> fakeThemeObserver() },
                )
            val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
            controller.init()
            registry.fire("load", null)

            val clickEvent = js("({})")
            clickEvent.point = js("({})")
            clickEvent.point.x = 10.0
            clickEvent.point.y = 10.0
            registry.fire("click", MEMBER_MAP_CLUSTERS_LAYER_ID, clickEvent) // must not throw synchronously

            Promise.resolve(Unit).await()

            assertEquals(0, easeToCalls)
        }

    // ── V1.9.9 "Details & Suche" ─────────────────────────────────────────────────────────────

    @Test
    fun addCapitalMarkers_createsExactlySixteenMarkers() {
        val (map, registry) = fakeMapWithRegistry()
        val capitalMarkerCoordinates = mutableListOf<Array<Double>>()
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, lngLat ->
                    capitalMarkerCoordinates.add(lngLat)
                    fakeMarker()
                },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        // MEMBER_MAP_LABELS (24) markers come first, then MEMBER_MAP_CAPITALS (16, added by
        // `addCapitalMarkers` right after `addRegionLabels` in `wireLayers`) -- the tail 16 calls'
        // coordinates must be exactly MEMBER_MAP_CAPITALS' own lon/lat pairs, in order.
        val capitalCoordinates = capitalMarkerCoordinates.drop(24)
        assertEquals(16, capitalCoordinates.size)
        MEMBER_MAP_CAPITALS.forEachIndexed { index, capital ->
            assertEquals(capital.lon, capitalCoordinates[index][0])
            assertEquals(capital.lat, capitalCoordinates[index][1])
        }
    }

    @Test
    fun updatePlaceLabels_belowMinZoom_neverCallsQuerySourceFeatures() {
        val (map, registry) = fakeMapWithRegistry()
        map.getZoom = { 8.99 } // just below PLACE_LABELS_MIN_ZOOM (9.0)
        var queryCalls = 0
        map.querySourceFeatures = { _: dynamic, _: dynamic ->
            queryCalls++
            arrayOf<dynamic>()
        }
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        assertEquals(0, queryCalls)
    }

    @Test
    fun updatePlaceLabels_atOrAboveMinZoom_queriesThePlacesSourceLayer_andCreatesOneMarkerPerDistinctName() {
        val (map, registry) = fakeMapWithRegistry()
        map.getZoom = { 9.0 }
        var lastSourceId: String? = null
        var lastOptions: dynamic = null
        map.querySourceFeatures = { sourceId: dynamic, options: dynamic ->
            lastSourceId = sourceId as String
            lastOptions = options
            val featureA = js("({})")
            featureA.properties = js("({ name: \"Kleindorf\" })")
            featureA.geometry = js("({ coordinates: [11.0, 49.0] })")
            val featureB = js("({})")
            featureB.properties = js("({ name: \"Kleindorf\" })") // duplicate name -> deduped by selectPlaceLabels
            featureB.geometry = js("({ coordinates: [11.01, 49.01] })")
            arrayOf(featureA, featureB)
        }
        var placeMarkerCreateCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ ->
                    placeMarkerCreateCalls++
                    fakeMarker()
                },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        assertEquals("member-map-basemap", lastSourceId)
        assertEquals("places", lastOptions.sourceLayer)
        // 24 region labels + 16 capitals + exactly 1 deduped place label ("Kleindorf" appears once).
        assertEquals(41, placeMarkerCreateCalls)
    }

    @Test
    fun updatePlaceLabels_moveendEvent_rebuildsPlaceLabelMarkers() {
        val (map, registry) = fakeMapWithRegistry()
        map.getZoom = { 9.0 }
        var queryCalls = 0
        map.querySourceFeatures = { _: dynamic, _: dynamic ->
            queryCalls++
            arrayOf<dynamic>()
        }
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)
        assertEquals(1, queryCalls, "wireLayers itself calls updatePlaceLabels once")

        registry.fire("moveend", null)
        assertEquals(2, queryCalls)
    }

    @Test
    fun flyToPlace_noMap_neverThrows() {
        val controller =
            MemberMapMapController(
                container = detachedDiv(),
                deps = MemberMapDeps(webglAvailable = { false }),
                onFeatureClicked = {},
            )
        controller.init() // WEBGL_MISSING -- map stays null
        controller.flyToPlace(10.0, 50.0) // must not throw
    }

    @Test
    fun flyToPlace_flies_toThePlaceFlyToZoom() {
        val (map, registry) = fakeMapWithRegistry()
        var flyToOptions: dynamic = null
        map.flyTo = { options: dynamic -> flyToOptions = options }
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        controller.flyToPlace(11.0, 51.0)

        assertEquals(MemberMapRules.PLACE_FLY_TO_ZOOM.toDouble(), flyToOptions.zoom)
    }

    @Test
    fun showSearchPin_thenClearSearchPin_addsThenRemovesOneMarkerAndOnePopup() {
        val (map, registry) = fakeMapWithRegistry()
        var pinMarkerRemoveCalls = 0
        var pinPopupRemoveCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker(onRemove = { pinMarkerRemoveCalls++ }) },
                createPopup = { _ -> fakePopup(onRemove = { pinPopupRemoveCalls++ }) },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        controller.showSearchPin(11.0, 51.0, document.createElement("div") as HTMLElement)
        controller.clearSearchPin()

        assertEquals(1, pinMarkerRemoveCalls)
        assertEquals(1, pinPopupRemoveCalls)
    }

    /**
     * Bug fix (2026-09-28, review finding on `clearSearchPin`'s own KDoc claim): before this fix,
     * [MemberMapMapController.showSearchPin] never wired the popup's `"close"` event to
     * [MemberMapMapController.clearSearchPin] -- clicking the popup's own native "×" removed only the
     * popup, leaving [MemberMapMapController]'s `searchPinMarker` permanently on the map with no UI path
     * left to remove it (not even a new search, since [MemberMapMapController.showSearchPin]'s own
     * defensive `clearSearchPin()` call only runs when a NEW pin is about to be shown). This test calls
     * `.remove()` DIRECTLY on the captured popup -- never `controller.clearSearchPin()` -- to faithfully
     * simulate "the user closed only the popup", exactly as `fakePopup`'s own KDoc explains.
     */
    @Test
    fun showSearchPin_thenNativePopupClose_alsoRemovesTheMarker() {
        val (map, registry) = fakeMapWithRegistry()
        var pinMarkerRemoveCalls = 0
        var capturedPopup: dynamic = null
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker(onRemove = { pinMarkerRemoveCalls++ }) },
                createPopup = { _ ->
                    val created = fakePopup()
                    capturedPopup = created
                    created
                },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        controller.showSearchPin(11.0, 51.0, document.createElement("div") as HTMLElement)
        capturedPopup.remove()

        assertEquals(1, pinMarkerRemoveCalls, "closing the popup via its own native × must also remove the now-orphaned pin marker")
    }

    @Test
    fun showSearchPin_calledTwice_removesThePreviousPinFirst() {
        val (map, registry) = fakeMapWithRegistry()
        var pinMarkerRemoveCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker(onRemove = { pinMarkerRemoveCalls++ }) },
                createPopup = { _ -> fakePopup() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)

        controller.showSearchPin(11.0, 51.0, document.createElement("div") as HTMLElement)
        controller.showSearchPin(12.0, 52.0, document.createElement("div") as HTMLElement)

        assertEquals(1, pinMarkerRemoveCalls, "the first pin must be removed before the second is shown")
    }

    @Test
    fun destroy_alsoClearsTheSearchPin() {
        val (map, registry) = fakeMapWithRegistry()
        var pinMarkerRemoveCalls = 0
        val deps =
            MemberMapDeps(
                createMap = { _ -> map },
                webglAvailable = { true },
                createMarker = { _, _ -> fakeMarker(onRemove = { pinMarkerRemoveCalls++ }) },
                createPopup = { _ -> fakePopup() },
                hoverCapable = { false },
                createThemeObserver = { _ -> fakeThemeObserver() },
            )
        val controller = MemberMapMapController(container = detachedDiv(), deps = deps, onFeatureClicked = {})
        controller.init()
        registry.fire("load", null)
        controller.showSearchPin(11.0, 51.0, document.createElement("div") as HTMLElement)

        controller.destroy()

        assertTrue(pinMarkerRemoveCalls >= 1)
    }
}
