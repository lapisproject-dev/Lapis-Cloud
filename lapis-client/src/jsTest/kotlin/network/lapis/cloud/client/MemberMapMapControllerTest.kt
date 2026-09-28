package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** A minimal fake "map" object carrying only the methods [MemberMapMapController] calls -- see [MemberMapDeps.createMap]. */
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
        map.resize = {}
        map.remove = { calls.removed = true }
        return map
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
}
