package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.71 -- the floating conference window in a REAL mounted root with the real stylesheets, driven through the dock and the controller
 * like the app does (the viewport is a fake media query, the session a fake that hands over real `<video>` elements). Real LiveKit video,
 * the real bandwidth effect of `setVideoQuality` and Safari/iOS cannot run in Karma (see the CHANGELOG).
 */
class ConferenceFloatWindowDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                // Karma's iframe is narrower than 768 px, where theme.css hides the window as a second guard (the controller's own
                // media query is faked in these tests). That CSS guard is pinned as text by ClientConferenceDockTripwireTest.
                val style = document.createElement("style")
                style.textContent = ".lapis-conference-float:not(.d-none) { display: flex !important; }"
                document.head!!.appendChild(style)
                true
            }
    }

    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = true,
            screenSharing = false,
            recording = false,
            streaming = false,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    /** A call that hands over pictures that live in test-owned home slots. */
    private class FloatFake : DockableSession {
        override val kind = DockSessionKind.CONFERENCE
        var sources: List<FloatMediaSource> = emptyList()
        var mic = 0
        var camera = 0
        var stopShare = 0
        var leaves = 0
        var attaches = 0
        val order = mutableListOf<String>()

        override fun attach() {
            attaches++
        }

        override fun detach() = Unit

        override fun leave() {
            leaves++
        }

        override suspend fun terminate(reason: DockTerminateReason) {
            // what the ledger still holds at the moment the call view's own teardown starts
            order += "terminate:lent=${ConferenceDock.videoLedger.sizeForTest()}"
        }

        override fun toggleMic() {
            mic++
        }

        override fun toggleCamera() {
            camera++
        }

        override fun stopScreenShare() {
            stopShare++
        }

        override fun floatMedia(): List<FloatMediaSource> = sources
    }

    private lateinit var homes: HTMLElement
    private lateinit var query: FakeMediaQuery
    private val bigViewport = Viewport(1280, 800)

    @BeforeTest
    fun reset() {
        ConferenceDock.resetForTest()
        ConferenceFloatStore.storageForTest = MemoryStorage()
        ConferenceReceiptGate.visible = false
        ConferenceReceiptGate.casting = false
        homes = document.createElement("div") as HTMLElement
        document.body!!.appendChild(homes)
        query = FakeMediaQuery(true)
    }

    @AfterTest
    fun cleanup() {
        ConferenceDock.resetForTest()
        ConferenceReceiptGate.visible = false
        ConferenceReceiptGate.casting = false
        homes.remove()
    }

    private fun source(
        key: String,
        label: String = key,
        isLocal: Boolean = false,
        withVideo: Boolean = true,
        spoke: Long = 0L,
    ): FloatMediaSource {
        val home = testSlot(homes)
        val video = if (withVideo) liveTestVideo().also { home.appendChild(it) } else null
        return FloatMediaSource(
            key,
            label,
            isLocal,
            isScreenShare = false,
            video = video,
            home = home,
            lastSpokeAtMs = spoke,
            setQuality = null,
        )
    }

    private fun startFloatingCall(fake: FloatFake): FloatFake {
        ConferenceFloatController.viewportForTest = bigViewport
        assertTrue(ConferenceDock.beginJoin("room-1"))
        ConferenceDock.register(fake)
        ConferenceDock.dispatch(DockEvent.Connected(snap))
        ConferenceDock.dispatch(DockEvent.ViewDetached)
        return fake
    }

    private fun mount(
        root: Root,
        fake: FloatFake? = null,
    ) {
        root.div(className = "lapis-content") { setAttribute("role", "main") }
        root.conferenceDockBar()
        root.conferenceFloatWindow()
        root.conferenceDockAnnouncer()
        ConferenceFloatController.install(query)
        if (fake != null) startFloatingCall(fake)
    }

    private fun HTMLElement.floatWindow() = assertNotNull(querySelector(".lapis-conference-float") as? HTMLElement, "no window")

    private fun HTMLElement.bar() = assertNotNull(querySelector(".lapis-conference-dock-bar") as? HTMLElement, "no bar")

    private fun HTMLElement.button(label: String): HTMLElement =
        assertNotNull(
            (0 until querySelectorAll("button").length)
                .map { querySelectorAll("button").item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("aria-label") == label || it.textContent?.trim() == label },
            "no button '$label'",
        )

    private fun key(
        target: HTMLElement,
        key: String,
        shift: Boolean = false,
        alt: Boolean = false,
        code: String = "",
    ) {
        target.dispatchEvent(
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(key = key, code = code, shiftKey = shift, altKey = alt, bubbles = true, cancelable = true),
            ),
        )
        target.dispatchEvent(
            KeyboardEvent(
                "keyup",
                KeyboardEventInit(key = key, code = code, shiftKey = shift, altKey = alt, bubbles = true, cancelable = true),
            ),
        )
    }

    private fun shown(el: HTMLElement) = el.offsetWidth > 0

    private suspend fun awaitWindow(element: () -> HTMLElement) =
        awaitUntil(
            "the window shows",
            detail = {
                val w = element().floatWindow()
                "innerWidth=${window.innerWidth} presentation=${ConferenceFloatController.presentation} class='${w.className}' " +
                    "offsetWidth=${w.offsetWidth} display=${window.getComputedStyle(
                        w,
                    ).display} state=${ConferenceDock.state::class.simpleName}"
            },
        ) { shown(element().floatWindow()) }

    /** `focus()` plus the `focusin` a focused page would send (a headless page may not report focus events). */
    private fun focusAndNotify(el: HTMLElement) {
        el.focus()
        el.dispatchEvent(
            org.w3c.dom.events
                .FocusEvent(
                    "focusin",
                    org.w3c.dom.events
                        .FocusEventInit(bubbles = true),
                ),
        )
    }

    @Test
    fun presentation_barFloatAndFull_followTheDockAndTheViewport(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withMountedRoot("float-modes") { root, element ->
                val fake = FloatFake()
                mount(root, fake)
                val generation = ConferenceDock.generation
                awaitWindow(element)
                assertFalse(shown(element().bar()), "floating: the bar gives way")
                val win = element().floatWindow()
                assertEquals("region", win.getAttribute("role"))
                assertEquals("0", win.getAttribute("tabindex"))
                assertTrue(win.getAttribute("aria-label").orEmpty().isNotBlank())
                assertTrue(win.getAttribute("aria-description").orEmpty().contains("Alt"))
                assertFalse(document.body!!.classList.contains(DOCK_BODY_CLASS), "no space is reserved for a bar that is not shown")

                // the route opens: the full view, no window, no bar
                ConferenceDock.dispatch(DockEvent.ViewAttached)
                awaitUntil("the window is gone in the full view") { !shown(element().floatWindow()) }
                assertFalse(shown(element().bar()))

                // back to the background: the window again
                ConferenceDock.dispatch(DockEvent.ViewDetached)
                awaitUntil("the window shows again") { shown(element().floatWindow()) }

                // the viewport gets narrow: only the mini bar
                query.set(false)
                awaitUntil("the bar takes over") { shown(element().bar()) && !shown(element().floatWindow()) }
                assertTrue(document.body!!.classList.contains(DOCK_BODY_CLASS), "the bar reserves its space again")
                val toggle = element().bar().querySelector(".lapis-dock-float-toggle") as? HTMLElement
                assertTrue(toggle == null || !shown(toggle), "a narrow viewport has no 'Als Fenster zeigen'")

                // wide again: the stored wish (window) is honoured
                query.set(true)
                awaitUntil("the window is back") { shown(element().floatWindow()) && !shown(element().bar()) }
                assertEquals(generation, ConferenceDock.generation, "switching the presentation never joins or ends a call")
                assertSame(fake, ConferenceDock.session, "still the one session")
                assertEquals(0, fake.attaches, "the window never calls attach() -- one attach per track, made by the call view")
            }
        }

    @Test
    fun collapsingAndShowingAgain_isStored_andTheBarOffersTheWindow(): Promise<Unit> =
        formTest {
            withMountedRoot("float-collapse") { root, element ->
                val memory = MemoryStorage()
                ConferenceFloatStore.storageForTest = memory
                val storage = memory.values
                mount(root, FloatFake())
                awaitWindow(element)
                element().floatWindow().button("Einklappen").click()
                awaitUntil("the bar shows") { shown(element().bar()) && !shown(element().floatWindow()) }
                assertTrue(storage.getValue(FLOAT_STORAGE_KEY).startsWith("v1|bar|"), "the wish is stored")
                element().bar().button("Als Fenster zeigen").click()
                awaitUntil("the window is back") { shown(element().floatWindow()) && !shown(element().bar()) }
                assertTrue(storage.getValue(FLOAT_STORAGE_KEY).startsWith("v1|float|"))
            }
        }

    @Test
    fun pictures_areLent_neverDuplicated_andGoHomeWhenTheWindowGoes(): Promise<Unit> =
        formTest {
            withMountedRoot("float-lend") { root, element ->
                val fake = FloatFake()
                val a = source("a", "Anna")
                val b = source("b", "Bernd")
                fake.sources = listOf(a, b)
                val before = videoCount()
                mount(root)
                startFloatingCall(fake)
                awaitUntil("the big picture is lent") { a.video!!.parentElement?.closest(".lapis-conference-float") != null }
                assertEquals(before + 0, videoCount(), "no <video> was created")
                assertTrue(a.video!!.isConnected)
                assertSame(a.video, element().floatWindow().querySelector(".lapis-float-main video"))
                assertTrue(b.video!!.closest(".lapis-float-strip") != null, "the second person is in the strip")

                ConferenceDock.dispatch(DockEvent.ViewAttached)
                awaitUntil("everything went home") { a.video?.parentElement === a.home && b.video?.parentElement === b.home }
                assertEquals(before, videoCount())
                assertTrue(hasLiveSource(a.video!!), "the stream keeps running in the call view")
            }
        }

    @Test
    fun aNarrowViewport_sendsThePicturesHome_andShowsTheBar(): Promise<Unit> =
        formTest {
            withMountedRoot("float-narrow") { root, element ->
                val fake = FloatFake()
                val a = source("a")
                fake.sources = listOf(a)
                mount(root)
                startFloatingCall(fake)
                awaitUntil("lent") { a.video!!.closest(".lapis-conference-float") != null }
                query.set(false)
                awaitUntil("home again") { a.video!!.parentElement === a.home }
                assertNull(element().floatWindow().querySelector("video"))
            }
        }

    @Test
    fun aNameIsText_neverMarkup(): Promise<Unit> =
        formTest {
            withMountedRoot("float-xss") { root, element ->
                val fake = FloatFake()
                val evil = "<img src=x onerror=window.__floatXss=1>"
                fake.sources = listOf(source("a", evil, withVideo = false))
                mount(root, fake)
                awaitUntil("the name tile shows") {
                    element()
                        .floatWindow()
                        .textContent
                        .orEmpty()
                        .contains(evil)
                }
                assertNull(element().floatWindow().querySelector("img"), "a name never becomes an element")
                assertNull(window.asDynamic().__floatXss)
            }
        }

    @Test
    fun theDeviceButtons_haveAriaPressed_inEverySize_andTheOwnPictureFollowsTheCamera(): Promise<Unit> =
        formTest {
            withMountedRoot("float-devices") { root, element ->
                val fake = FloatFake()
                fake.sources = listOf(source("a"), source("me", "Ich", isLocal = true))
                mount(root, fake)
                awaitWindow(element)
                val win = element().floatWindow()
                for (width in listOf(256, 352, 480)) {
                    ConferenceFloatController.setGeometry(
                        ConferenceFloatController.preference.geometry.copy(width = width),
                        persist = false,
                    )
                    assertEquals("true", win.button("Mikrofon ausschalten").getAttribute("aria-pressed"), "mic at $width")
                    assertEquals("true", win.button("Kamera ausschalten").getAttribute("aria-pressed"), "camera at $width")
                }
                ConferenceFloatController.setGeometry(DEFAULT_FLOAT_GEOMETRY, persist = false)
                awaitUntil("the own picture is in the strip") { win.querySelector(".lapis-float-strip-cell video") != null }
                // the camera goes off: no stale own picture
                ConferenceDock.publish(snap.copy(cameraOn = false))
                fake.sources = listOf(fake.sources[0], fake.sources[1].copy(video = null))
                ConferenceDock.notifyMediaChanged()
                awaitUntil("no own picture") { win.querySelector(".lapis-float-strip-cell video") == null }
                awaitUntil("the mic button reflects the snapshot") {
                    ConferenceDock.publish(snap.copy(cameraOn = false, micOn = false))
                    win.button("Mikrofon einschalten").getAttribute("aria-pressed") == "false"
                }
                win.button("Mikrofon einschalten").click()
                assertEquals(1, fake.mic)
                win.button("Kamera einschalten").click()
                assertEquals(1, fake.camera)
            }
        }

    @Test
    fun consentBadges_andTheShareMarker_areText_inTheWindow(): Promise<Unit> =
        formTest {
            withMountedRoot("float-badges") { root, element ->
                val fake = FloatFake()
                mount(root, fake)
                ConferenceDock.publish(
                    snap.copy(recording = true, streaming = true, streamPaused = true, voteOpen = true, screenSharing = true),
                )
                awaitUntil("all badges show") {
                    val text = element().floatWindow().textContent.orEmpty()
                    text.contains("● Aufzeichnung") &&
                        text.contains("◆ Live-Stream pausiert") &&
                        text.contains("Abstimmung läuft") &&
                        text.contains("▣ Sie teilen Ihren Bildschirm")
                }
                val win = element().floatWindow()
                assertTrue(shown(win.button("Bildschirmfreigabe beenden")), "the own share can be stopped from the window")
                win.button("Bildschirmfreigabe beenden").click()
                assertEquals(1, fake.stopShare)
            }
        }

    @Test
    fun theHeaderButtons_cycleCornerAndSize_andReturnToTheView(): Promise<Unit> =
        formTest {
            withMountedRoot("float-header") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                val win = element().floatWindow()
                win.button("In die nächste Ecke").click()
                assertEquals(1, ConferenceFloatController.preference.geometry.corner)
                win.button("Größe: Mittel").click()
                assertEquals(480, ConferenceFloatController.preference.geometry.width)
                awaitUntil("the size button names the new size") { win.querySelector("[aria-label='Größe: Groß']") != null }
                win.button("Zur Konferenz").click()
                assertTrue(ConferenceDock.pendingFocusFromBar, "the person is taken to the view")
                ConferenceDock.pendingFocusFromBar = false
            }
        }

    @Test
    fun leave_usesTheSharedPath_andTheReceiptGateTakesThePersonToTheView(): Promise<Unit> =
        formTest {
            withMountedRoot("float-leave") { root, element ->
                val fake = FloatFake()
                mount(root, fake)
                awaitWindow(element)
                ConferenceReceiptGate.visible = true
                element().floatWindow().button("Verlassen").click()
                assertEquals(0, fake.leaves, "a secret-ballot receipt exists only in the view")
                ConferenceDock.pendingFocusFromBar = false
                ConferenceReceiptGate.visible = false
                element().floatWindow().button("Verlassen").click()
                assertEquals(1, fake.leaves)
            }
        }

    @Test
    fun keyboard_arrowsMove_shiftArrowsResize_homeResets_escapeGivesTheFocusBack(): Promise<Unit> =
        formTest {
            withMountedRoot("float-keys") { root, element ->
                val outside = document.createElement("button") as HTMLButtonElement
                outside.textContent = "Außerhalb"
                document.body!!.appendChild(outside)
                try {
                    mount(root, FloatFake())
                    awaitWindow(element)
                    val win = element().floatWindow()
                    focusAndNotify(outside)
                    // the window is reached with the global shortcut
                    key(document.body!!, "K", shift = true, alt = true, code = "KeyK")
                    assertSame(win, document.activeElement, "Alt+Umschalt+K focuses the window")

                    key(win, "ArrowLeft")
                    assertEquals(32, ConferenceFloatController.preference.geometry.dx, "16 px to the left")
                    key(win, "ArrowUp")
                    assertEquals(32, ConferenceFloatController.preference.geometry.dy)
                    key(win, "ArrowRight", shift = true)
                    assertEquals(384, ConferenceFloatController.preference.geometry.width, "shift + arrow: +32 px")
                    key(win, "ArrowDown", shift = true)
                    assertEquals(352, ConferenceFloatController.preference.geometry.width)
                    key(win, "Home")
                    assertEquals(DEFAULT_FLOAT_GEOMETRY.dx, ConferenceFloatController.preference.geometry.dx)

                    key(win, "Escape")
                    assertSame(outside, document.activeElement, "Escape gives the focus back to the page")
                    assertTrue(shown(win), "the mode stays")
                    assertEquals(FloatMode.FLOAT, ConferenceFloatController.preference.mode)
                } finally {
                    outside.remove()
                }
            }
        }

    @Test
    fun theShortcut_doesNothingWithoutARunningCall_andFocusesTheBarWhenFolded(): Promise<Unit> =
        formTest {
            withMountedRoot("float-shortcut") { root, element ->
                val outside = document.createElement("button") as HTMLButtonElement
                document.body!!.appendChild(outside)
                try {
                    mount(root)
                    outside.focus()
                    key(document.body!!, "K", shift = true, alt = true, code = "KeyK")
                    assertSame(outside, document.activeElement, "no call: nothing happens")
                    startFloatingCall(FloatFake())
                    awaitWindow(element)
                    ConferenceFloatController.setMode(FloatMode.BAR)
                    awaitUntil("the bar shows") { shown(element().bar()) }
                    outside.focus()
                    key(document.body!!, "K", shift = true, alt = true, code = "KeyK")
                    assertTrue(element().bar().contains(document.activeElement), "the folded bar is reached too")
                } finally {
                    outside.remove()
                }
            }
        }

    @Test
    fun theWindow_isNonModal_andSitsBelowEveryDialog(): Promise<Unit> =
        formTest {
            withMountedRoot("float-modal") { root, element ->
                val page = document.createElement("button") as HTMLButtonElement
                page.style.cssText = "position:fixed;left:8px;top:8px;width:40px;height:40px;"
                var clicks = 0
                page.addEventListener("click", { clicks++ })
                document.body!!.appendChild(page)
                try {
                    mount(root, FloatFake())
                    awaitWindow(element)
                    val hit = document.elementFromPoint(28.0, 28.0)
                    assertSame(page, hit, "nothing covers the page: no backdrop")
                    page.click()
                    assertEquals(1, clicks, "the page stays operable")
                    val z = window.getComputedStyle(element().floatWindow()).zIndex.toInt()
                    assertEquals(1034, z)
                    for (above in listOf(1035, 1040, 1045, 1055, 1060, 2000)) assertTrue(z < above)
                    assertEquals("fixed", window.getComputedStyle(element().floatWindow()).position)
                } finally {
                    page.remove()
                }
            }
        }

    @Test
    fun aFocusedElementUnderTheWindow_getsARing_andOneAnnouncement(): Promise<Unit> =
        formTest {
            withMountedRoot("float-cover") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                val win = element().floatWindow()
                val rect = win.getBoundingClientRect()
                val covered = document.createElement("button") as HTMLButtonElement
                covered.textContent = "verdeckt"
                covered.style.cssText =
                    "position:fixed;left:${rect.left + 10}px;top:${rect.top + 80}px;width:60px;height:30px;z-index:1;"
                document.body!!.appendChild(covered)
                try {
                    focusAndNotify(covered)
                    assertTrue(win.classList.contains("is-covering-focus"))
                    awaitUntil("announced once") {
                        element()
                            .querySelector(
                                ".lapis-conference-dock-announcer [role=status]",
                            )?.textContent
                            .orEmpty()
                            .contains("verdeckt")
                    }
                    val clear = document.createElement("button") as HTMLButtonElement
                    clear.style.cssText = "position:fixed;left:2px;top:2px;width:20px;height:20px;"
                    document.body!!.appendChild(clear)
                    focusAndNotify(clear)
                    assertFalse(win.classList.contains("is-covering-focus"), "the ring goes when the focus is elsewhere")
                    clear.remove()
                } finally {
                    covered.remove()
                }
            }
        }

    @Test
    fun consentChanges_areAnnounced_alsoWhileTheWindowFloats(): Promise<Unit> =
        formTest {
            withMountedRoot("float-announce") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                assertFalse(shown(element().bar()), "the bar is display:none now -- the regions must not live in it")
                ConferenceDock.publish(snap.copy(recording = true))
                awaitUntil("the alert region speaks") {
                    (element().querySelector("[role=alert]") as? HTMLElement)?.textContent.orEmpty().contains("Aufzeichnung läuft")
                }
                assertNull(element().bar().querySelector("[role=alert]"), "the bar has no live region of its own any more")
            }
        }

    @Test
    fun theControllerAnnouncesOnlyWhatThePersonDid_notAnEndedOrReEnteredCall(): Promise<Unit> =
        formTest {
            withMountedRoot("float-announce-controller") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                val heard = mutableListOf<String>()
                val off = ConferenceFloatController.observeAnnouncements { _, text -> heard += text }
                try {
                    // the call ends involuntarily: FLOAT -> BAR, but nobody collapsed anything
                    ConferenceDock.dispatch(DockEvent.Stopped(DockStopReason.DUPLICATE_IDENTITY))
                    awaitUntil("the bar shows after the stop") { shown(element().bar()) }
                    assertTrue(heard.none { it.contains("eingeklappt") || it.contains("schwebendes") }, heard.toString())
                    // automatic re-entry: BAR -> FLOAT, again nothing the person did
                    ConferenceDock.dispatch(DockEvent.ReEntered)
                    ConferenceDock.dispatch(DockEvent.Connected(snap))
                    awaitUntil("the window is back") { shown(element().floatWindow()) }
                    assertTrue(heard.none { it.contains("eingeklappt") || it.contains("schwebendes") }, heard.toString())
                    // the person collapses: that IS announced
                    ConferenceFloatController.setMode(FloatMode.BAR)
                    awaitUntil("collapsed") { shown(element().bar()) }
                    assertTrue(heard.any { it.contains("eingeklappt") }, heard.toString())
                } finally {
                    off()
                }
            }
        }

    @Test
    fun theAnnouncer_alternatesTheMarker_soAnIdenticalTextIsReadEveryTime(): Promise<Unit> =
        formTest {
            withMountedRoot("float-announce-marker") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                val polite = element().querySelector(".lapis-conference-dock-announcer [role=status]") as HTMLElement
                val seen = mutableListOf<String>()
                repeat(3) {
                    ConferenceFloatController.announce(DockAnnouncementKind.POLITE, "Gleicher Text")
                    seen += polite.textContent.orEmpty()
                }
                assertTrue(seen.zipWithNext().all { (a, b) -> a != b }, "consecutive identical texts must differ in the DOM: $seen")
                assertTrue(seen.all { it.startsWith("Gleicher Text") })
            }
        }

    @Test
    fun aDockUpdateDuringTheDrag_doesNotThrowTheWindowBack(): Promise<Unit> =
        formTest {
            withMountedRoot("float-drag-update") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                val win = element().floatWindow()
                val header = win.querySelector(".lapis-float-header") as HTMLElement

                fun fire(
                    type: String,
                    x: Int,
                    y: Int,
                ) {
                    val init = js("{}")
                    init.bubbles = true
                    init.pointerId = 7
                    init.button = 0
                    init.clientX = x
                    init.clientY = y
                    header.dispatchEvent(js("new PointerEvent(type, init)").unsafeCast<org.w3c.dom.events.Event>())
                }
                fire("pointerdown", 600, 400)
                fire("pointermove", 500, 300)
                val during = win.style.left + "|" + win.style.top
                // a dock update repaints the window mid-drag
                ConferenceDock.publish(snap.copy(micOn = false))
                awaitUntil("the repaint ran") { true }
                assertEquals(during, win.style.left + "|" + win.style.top, "the repaint must not reset the dragged position")
                fire("pointerup", 500, 300)
            }
        }

    @Test
    fun moving_toAnotherViewportSize_keepsTheWindowInsideTheViewport(): Promise<Unit> =
        formTest {
            withMountedRoot("float-clamp") { root, element ->
                mount(root, FloatFake())
                awaitWindow(element)
                ConferenceFloatController.setGeometry(FloatGeometry(0, 9000, 9000, 640), persist = false)
                val rect = ConferenceFloatController.currentRect()
                assertTrue(rect.left >= FLOAT_MARGIN_PX && rect.top >= FLOAT_MARGIN_PX)
                assertTrue(rect.left + rect.width <= bigViewport.width - FLOAT_MARGIN_PX)
            }
        }

    @Test
    fun terminate_sendsEveryPictureHomeBeforeTheSessionEnds_andNoLiveVideoRemains(): Promise<Unit> =
        formTest {
            withMountedRoot("float-terminate") { root, element ->
                val fake = FloatFake()
                val a = source("a")
                val b = source("b")
                fake.sources = listOf(a, b)
                mount(root)
                startFloatingCall(fake)
                awaitUntil("lent") { a.video!!.closest(".lapis-conference-float") != null }
                ConferenceDock.terminate(DockTerminateReason.LOGOUT)
                assertEquals(listOf("terminate:lent=0"), fake.order, "every picture is home BEFORE the call view's own teardown")
                assertEquals(DockState.Idle, ConferenceDock.state)
                assertEquals(0, element().floatWindow().querySelectorAll("video").length, "nothing stays in the window")
                // what the (fake) call view would still own is stopped by its own teardown; the stage holds nothing live
                val stageVideos = element().floatWindow().querySelectorAll("video")
                for (i in 0 until stageVideos.length) assertFalse(hasLiveSource(stageVideos.item(i) as HTMLVideoElement))
            }
        }
}
