package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.js.Date

/*
 * V1.9.71 -- the conference as a free-floating window: what the person sees while a conference runs, its view is not on screen, and the
 * viewport is wide enough. Non-modal (no backdrop, the page stays operable), no scroll area, no clip, never on a viewport narrower than
 * 768 px (there the mini bar of V1.9.70 stays).
 *
 * It owns NO call and NO media element: it lends the `<video>` elements the call view already created ([ConferenceVideoLedger]) and uses
 * the same dock functions as the bar (microphone, camera, stop sharing, leave). One connection, one `attach()` per track, no second join.
 *
 * Moving and resizing have a keyboard alternative (WCAG 2.5.7): focus the window (Alt+Shift+K), arrow keys move, Shift+arrow resizes. The
 * window never dodges the focus (WCAG 2.4.11 is met by a visible ring on the window and an announcement instead): a window that jumps
 * away under the keyboard is worse than one that can be moved on purpose.
 *
 * The call view hands over ready-made label strings ([FloatMediaSource.label]); this file never reads a name, an identity or the session.
 */

/** A media query as the controller needs it; the browser adapter below, a fake in tests. */
internal interface MediaQueryLike {
    val matches: Boolean

    /** Registers [listener] for changes; the returned function unregisters it. */
    fun onChange(listener: () -> Unit): () -> Unit
}

private class BrowserMediaQuery(
    private val query: org.w3c.dom.MediaQueryList,
) : MediaQueryLike {
    override val matches: Boolean get() = query.matches

    override fun onChange(listener: () -> Unit): () -> Unit {
        val handler: (Event) -> Unit = { listener() }
        query.addEventListener("change", handler)
        return { query.removeEventListener("change", handler) }
    }
}

/** Element id the window carries (also the target of "Alt+Umschalt+K"). */
internal const val FLOAT_WINDOW_CLASS = "lapis-conference-float"

private const val FLOAT_TICK_MS = 1_000
private const val FLOAT_MAX_RETRIES = 50
private const val FLOAT_RESUME_EVERY_TICKS = 2
private const val FOCUS_AFTER_PATCH_MS = 60

// ── what the window paints ────────────────────────────────────────────────────────────────────────────────────────────────────────────

internal data class FloatWindowView(
    val statusText: String,
    /** Glyph and text, never colour alone. */
    val consentBadges: List<String>,
    val voteBadge: Boolean,
    val screenShare: Boolean,
    val showDevices: Boolean,
    val showLeave: Boolean,
    val micPressed: Boolean,
    val cameraPressed: Boolean,
    val resolving: Boolean,
)

/** Pure: the paint instructions of the window for [state] (only [DockState.Live] and [DockState.Resolving] are ever floating). */
internal fun floatWindowViewOf(state: DockState): FloatWindowView {
    val snapshot = state.snapshotOrNull
    val live = state is DockState.Live
    val status =
        when (state) {
            is DockState.Live -> if (state.snapshot.transitioning != null) tr("Verbindung wird hergestellt …") else tr("Besprechung läuft")
            is DockState.Resolving -> tr("Verbindung wird gewechselt …")
            else -> ""
        }
    val badges =
        buildList {
            if (snapshot?.recording == true) add(tr("● Aufzeichnung"))
            if (snapshot?.streaming == true) add(if (snapshot.streamPaused) tr("◆ Live-Stream pausiert") else tr("◆ Live-Stream"))
        }
    return FloatWindowView(
        statusText = status,
        consentBadges = badges,
        voteBadge = snapshot?.voteOpen == true,
        screenShare = snapshot?.screenSharing == true && live,
        showDevices = live,
        showLeave = live,
        micPressed = snapshot?.micOn == true,
        cameraPressed = snapshot?.cameraOn == true,
        resolving = state is DockState.Resolving,
    )
}

/** Pure: the size step of the window (no strip below [FLOAT_STRIP_MIN_WIDTH_PX]). */
internal fun floatSizeBucket(width: Int): FloatSize =
    when {
        width < FLOAT_STRIP_MIN_WIDTH_PX -> FloatSize.SMALL
        width < (FloatSize.MEDIUM.widthPx + FloatSize.LARGE.widthPx) / 2 -> FloatSize.MEDIUM
        else -> FloatSize.LARGE
    }

private fun sizeLabel(size: FloatSize): String =
    when (size) {
        FloatSize.SMALL -> tr("Größe: Klein")
        FloatSize.MEDIUM -> tr("Größe: Mittel")
        FloatSize.LARGE -> tr("Größe: Groß")
    }

// ── the raw stage (never a KVision child) ─────────────────────────────────────────────────────────────────────────────────────────────

private fun rawDiv(cssClass: String): HTMLElement = (document.createElement("div") as HTMLElement).also { it.className = cssClass }

private class FloatCell(
    val el: HTMLElement,
) {
    private val label = rawDiv("lapis-float-label")
    private var fallback: HTMLElement? = null
    private var shownLabel = ""
    var assignedKey: String? = null
        private set

    init {
        el.appendChild(label)
    }

    fun show(
        source: FloatMediaSource,
        ledger: ConferenceVideoLedger,
        labelText: String,
    ) {
        el.style.removeProperty("display")
        if (shownLabel != labelText) {
            label.textContent = labelText // textContent only: a name is data, never markup
            shownLabel = labelText
        }
        val video = source.video
        if (video != null) {
            fallback?.let { it.parentNode?.removeChild(it) }
            fallback = null
            if (assignedKey != source.key || !ledger.isLent(source.key) || video.parentNode !== el) {
                ledger.borrow(source.key, video, source.home, el)
            }
        } else {
            if (ledger.isLent(source.key)) ledger.returnHome(source.key)
            val tile =
                fallback ?: rawDiv("lapis-float-nametile").also {
                    el.insertBefore(it, label)
                    fallback = it
                }
            if (tile.textContent != labelText) tile.textContent = labelText
        }
        assignedKey = source.key
    }

    fun hide() {
        el.style.display = "none"
        fallback?.let { it.parentNode?.removeChild(it) }
        fallback = null
        assignedKey = null
    }
}

/** The raw DOM of the window's picture area. Built once by the controller, mounted into the window's stage host. */
internal class FloatStage {
    val root: HTMLElement = rawDiv("lapis-float-stage")
    private val mainCell = FloatCell(rawDiv("lapis-float-main"))
    private val stripWrap = rawDiv("lapis-float-strip")
    private val stripCells = List(FLOAT_STRIP_CELLS) { FloatCell(rawDiv("lapis-float-strip-cell")) }
    private val more = rawDiv("lapis-float-more")
    private val insetCell = FloatCell(rawDiv("lapis-float-inset"))
    private val resolving = rawDiv("lapis-float-resolving")
    private var shownMore = -1

    init {
        resolving.style.display = "none"
        more.style.display = "none"
        mainCell.el.appendChild(resolving)
        root.appendChild(mainCell.el)
        root.appendChild(insetCell.el)
        stripCells.forEach { stripWrap.appendChild(it.el) }
        stripWrap.appendChild(more)
        root.appendChild(stripWrap)
        insetCell.el.style.display = "none"
        stripCells.forEach { it.el.style.display = "none" }
    }

    fun setStripVisible(visible: Boolean) {
        if (visible) stripWrap.style.removeProperty("display") else stripWrap.style.display = "none"
    }

    fun render(
        selection: FloatSelection,
        sources: List<FloatMediaSource>,
        ledger: ConferenceVideoLedger,
        resolvingNow: Boolean,
    ) {
        val byKey = sources.associateBy { it.key }
        val plan = LinkedHashMap<FloatCell, FloatMediaSource?>()
        plan[mainCell] = selection.mainKey?.let { byKey[it] }
        stripCells.forEachIndexed { index, cell -> plan[cell] = selection.stripKeys.getOrNull(index)?.let { byKey[it] } }
        plan[insetCell] = selection.insetKey?.let { byKey[it] }
        val desired = plan.values.mapNotNull { it?.key }.toSet()
        // 1. what is no longer shown goes home first (a picture that only changes its cell is moved by `borrow`, atomically)
        for (key in ledger.lent().filter { it !in desired }) ledger.returnHome(key)
        // 2. paint
        for ((cell, source) in plan) {
            if (source == null) cell.hide() else cell.show(source, ledger, if (source.isLocal) gettext("Eigenbild") else source.label)
        }
        if (selection.hiddenCount != shownMore) {
            shownMore = selection.hiddenCount
            if (shownMore > 0) {
                more.textContent = "+$shownMore"
                more.setAttribute("aria-label", gettext("Weitere Teilnehmende: %1", shownMore.toString()))
                more.setAttribute("role", "img")
                more.style.removeProperty("display")
            } else {
                more.style.display = "none"
            }
        }
        resolving.style.display = if (resolvingNow) "flex" else "none"
        if (resolvingNow) {
            val text = resolvedAttributeText(tr("Verbindung wird gewechselt …"))
            if (resolving.textContent != text) resolving.textContent = text
        }
    }

    /** Idle: nothing may keep running. Every picture still in the stage is stopped and dropped. */
    fun dispose() {
        val videos = root.querySelectorAll("video")
        for (i in 0 until videos.length) {
            val video = videos.item(i) as? HTMLVideoElement ?: continue
            runCatching { video.pause() }
            video.asDynamic().srcObject = null
            video.parentNode?.removeChild(video)
        }
        mainCell.hide()
        stripCells.forEach { it.hide() }
        insetCell.hide()
        shownMore = -1
        more.style.display = "none"
        resolving.style.display = "none"
    }
}

private const val FLOAT_STRIP_CELLS = 3

// ── the controller ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

internal object ConferenceFloatController {
    /** The stored wish and anchor; loaded in [install]. */
    var preference: FloatPreference = DEFAULT_FLOAT_PREFERENCE
        private set

    var presentation: DockPresentation = DockPresentation.BAR
        private set

    /** The viewport is at least [FLOAT_MIN_VIEWPORT_PX] wide (false until [install]). */
    var wide: Boolean = false
        private set

    /** Rows of badges the window currently shows (measured by the view, drives the height estimate). */
    var badgeRows: Int = 1
        internal set

    internal val stage: FloatStage by lazy { FloatStage() }

    /** The element of the mounted window, set by the view while it is inserted. */
    internal var windowElement: HTMLElement? = null

    /** Test seam: replaces the measured viewport. */
    internal var viewportForTest: Viewport? = null

    private val presentationListeners = mutableListOf<(DockPresentation) -> Unit>()
    private val layoutListeners = mutableListOf<() -> Unit>()
    private val announceSinks = mutableListOf<(DockAnnouncementKind, String) -> Unit>()
    private var unobserveDock: (() -> Unit)? = null
    private var unobserveQuery: (() -> Unit)? = null
    private var memo = FloatSpeakerMemo(mainKey = null, since = 0L)
    private val qualityCache = mutableMapOf<String, Pair<HTMLVideoElement?, Int>>()
    private var tickHandle: Int? = null
    private var tickCount = 0
    private var installed = false
    private var lastOutsideFocus: HTMLElement? = null
    private var announcedCoverOnce = false
    private var probe: HTMLElement? = null

    private val keyListener: (Event) -> Unit = { event -> onGlobalKey(event.unsafeCast<KeyboardEvent>()) }
    private val focusListener: (Event) -> Unit = { event -> onFocusIn(event) }
    private val resizeListener: (Event) -> Unit = { notifyLayout() }

    fun observe(listener: (DockPresentation) -> Unit): () -> Unit {
        presentationListeners += listener
        return { presentationListeners.remove(listener) }
    }

    fun observeLayout(listener: () -> Unit): () -> Unit {
        layoutListeners += listener
        return { layoutListeners.remove(listener) }
    }

    fun observeAnnouncements(sink: (DockAnnouncementKind, String) -> Unit): () -> Unit {
        announceSinks += sink
        return { announceSinks.remove(sink) }
    }

    fun showsFloat(state: DockState): Boolean = dockPresentationOf(state, preference.mode, wide) == DockPresentation.FLOAT

    /** Idempotent: a second call replaces the registrations of the first (the root restarts on a language change). */
    fun install(wideQuery: MediaQueryLike = BrowserMediaQuery(window.matchMedia("(min-width: ${FLOAT_MIN_VIEWPORT_PX}px)"))) {
        uninstall()
        installed = true
        lastState = ConferenceDock.state
        preference = ConferenceFloatStore.load()
        wide = wideQuery.matches
        unobserveQuery =
            wideQuery.onChange {
                wide = wideQuery.matches
                reevaluate()
            }
        unobserveDock = ConferenceDock.observe { state -> reevaluate(state) }
        document.addEventListener("keydown", keyListener)
        document.addEventListener("focusin", focusListener)
        window.addEventListener("resize", resizeListener)
        reevaluate()
    }

    private fun uninstall() {
        unobserveDock?.invoke()
        unobserveDock = null
        unobserveQuery?.invoke()
        unobserveQuery = null
        if (installed) {
            document.removeEventListener("keydown", keyListener)
            document.removeEventListener("focusin", focusListener)
            window.removeEventListener("resize", resizeListener)
        }
        installed = false
        stopTick()
    }

    /** "Als Fenster zeigen" / "Einklappen". Stored. */
    fun setMode(mode: FloatMode) {
        if (preference.mode == mode) return
        val focusWasInWindow = windowElement?.contains(document.activeElement) == true
        val focusWasInBar = (document.querySelector(".lapis-conference-dock-bar") as? HTMLElement)?.contains(document.activeElement) == true
        preference = preference.copy(mode = mode)
        ConferenceFloatStore.save(preference)
        reevaluate()
        if (mode == FloatMode.BAR && focusWasInWindow) {
            window.setTimeout({ (document.querySelector(".lapis-dock-float-toggle") as? HTMLElement)?.focus() }, FOCUS_AFTER_PATCH_MS)
        } else if (mode == FloatMode.FLOAT && focusWasInBar) {
            window.setTimeout({ windowElement?.focus() }, FOCUS_AFTER_PATCH_MS)
        }
    }

    fun setGeometry(
        geometry: FloatGeometry,
        persist: Boolean,
    ) {
        preference = preference.copy(geometry = geometry)
        if (persist) ConferenceFloatStore.save(preference)
        notifyLayout()
    }

    /** Alt+Shift+K: the window (floating) or the bar's first control (bar). `false` if there is nothing to focus. */
    fun focusDockSurface(): Boolean {
        val state = ConferenceDock.state
        if (state is DockState.Idle || state.isAttached) return false
        if (presentation == DockPresentation.FLOAT) {
            val el = windowElement ?: return false
            el.focus()
            return true
        }
        val target =
            (document.querySelector(".lapis-conference-dock-bar .lapis-dock-return") as? HTMLElement)
                ?: (document.querySelector(".lapis-conference-dock-bar button") as? HTMLElement)
        target?.focus()
        return target != null
    }

    /** The one place that computes what this viewport looks like (size and safe areas). */
    internal fun viewport(): Viewport {
        viewportForTest?.let { return it }
        val el =
            probe ?: (document.createElement("div") as HTMLElement).also { created ->
                created.setAttribute("aria-hidden", "true")
                created.style.cssText =
                    "position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;" +
                    "padding:env(safe-area-inset-top,0px) env(safe-area-inset-right,0px) " +
                    "env(safe-area-inset-bottom,0px) env(safe-area-inset-left,0px);"
                document.body?.appendChild(created)
                probe = created
            }
        val style = window.getComputedStyle(el)

        fun px(name: String): Int =
            style
                .getPropertyValue(name)
                .removeSuffix("px")
                .toDoubleOrNull()
                ?.toInt() ?: 0
        return Viewport(
            width = window.innerWidth,
            height = window.innerHeight,
            safeTop = px("padding-top"),
            safeRight = px("padding-right"),
            safeBottom = px("padding-bottom"),
            safeLeft = px("padding-left"),
        )
    }

    fun currentRect(): FloatRect = floatRect(preference.geometry, viewport(), badgeRows)

    /** The call's media changed (tile, picture, share): repaint the pictures. */
    fun onMediaChanged() {
        if (presentation == DockPresentation.FLOAT) renderFloat()
    }

    private fun reevaluate(state: DockState = ConferenceDock.state) {
        val old = presentation
        val sameStateClass = state::class == lastState::class
        lastState = state
        val next = dockPresentationOf(state, preference.mode, wide)
        presentation = next
        if (state is DockState.Idle) {
            ConferenceDock.videoLedger.returnAll()
            if (installed || old == DockPresentation.FLOAT) stage.dispose()
            qualityCache.clear()
            memo = FloatSpeakerMemo(mainKey = null, since = 0L)
        } else if (next != DockPresentation.FLOAT && old == DockPresentation.FLOAT) {
            // leaving the window (full view, bar, narrow viewport): every picture goes home BEFORE anything else changes
            ConferenceDock.videoLedger.returnAll()
        }
        ConferenceDock.reapplyChrome()
        if (next == DockPresentation.FLOAT) startTick() else stopTick()
        // the listeners show / hide the window first, then the pictures are lent
        presentationListeners.toList().forEach { it(next) }
        if (next == DockPresentation.FLOAT) {
            renderFloat()
            if (old != DockPresentation.FLOAT) window.setTimeout({ resumeStalledVideosIn(stage.root) }, 0)
        }
        applyQuality(null)
        // only a change the person made (mode wish, viewport) is announced here: a change of the state class (call ended, re-joined) is the
        // announcer's business, and "eingeklappt" would be a false statement then
        if (sameStateClass && old != next && old != DockPresentation.FULL && next != DockPresentation.FULL && state !is DockState.Idle) {
            val text = if (next == DockPresentation.FLOAT) tr("Konferenz als schwebendes Fenster") else tr("Konferenz eingeklappt")
            announce(DockAnnouncementKind.POLITE, resolvedAttributeText(text))
        }
    }

    private var lastState: DockState = DockState.Idle
    private var retryScheduled = false
    private var retries = 0

    private fun renderFloat() {
        if (presentation != DockPresentation.FLOAT) return
        if (!stage.root.isConnected) {
            // the window is not in the document yet (first patch): never lend a picture into a detached stage. A bounded number of
            // quick retries; after that the one-second tick (or the next media change) tries again.
            if (!retryScheduled && retries < FLOAT_MAX_RETRIES) {
                retryScheduled = true
                retries++
                window.setTimeout({
                    retryScheduled = false
                    renderFloat()
                }, 0)
            }
            return
        }
        retries = 0
        val state = ConferenceDock.state
        val sources = ConferenceDock.session?.floatMedia().orEmpty()
        ConferenceDock.videoLedger.prune()
        val width = floatSizeBucket(clampWidth(preference.geometry.width, viewport(), badgeRows))
        val cameraOn = state.snapshotOrNull?.cameraOn == true
        val (selection, newMemo) = floatSelectionOf(sources.map { it.info() }, width, cameraOn, Date.now().toLong(), memo)
        memo = newMemo
        stage.setStripVisible(width != FloatSize.SMALL)
        stage.render(selection, sources, ConferenceDock.videoLedger, resolvingNow = state is DockState.Resolving)
        applyQuality(selection, sources)
    }

    private fun applyQuality(
        selection: FloatSelection?,
        sources: List<FloatMediaSource> = ConferenceDock.session?.floatMedia().orEmpty(),
    ) {
        if (ConferenceDock.state is DockState.Idle) return
        val remote = sources.filter { it.setQuality != null }
        val plan = floatQualityPlan(selection, remote.map { it.key }, presentation)
        qualityCache.keys.retainAll(remote.map { it.key }.toSet())
        for (source in remote) {
            val quality = plan[source.key] ?: continue
            val cached = qualityCache[source.key]
            if (cached != null && cached.first === source.video && cached.second == quality) continue
            qualityCache[source.key] = source.video to quality
            runCatching { source.setQuality?.invoke(quality) }
        }
    }

    private fun startTick() {
        if (tickHandle != null) return
        tickCount = 0
        tickHandle =
            window.setInterval({
                if (presentation != DockPresentation.FLOAT) return@setInterval
                tickCount++
                renderFloat()
                if (tickCount % FLOAT_RESUME_EVERY_TICKS == 0) resumeStalledVideosIn(stage.root)
            }, FLOAT_TICK_MS)
    }

    private fun stopTick() {
        tickHandle?.let { window.clearInterval(it) }
        tickHandle = null
    }

    internal fun notifyLayout() {
        layoutListeners.toList().forEach { it() }
        if (presentation == DockPresentation.FLOAT) renderFloat()
    }

    internal fun announce(
        kind: DockAnnouncementKind,
        text: String,
    ) {
        announceSinks.toList().forEach { it(kind, text) }
    }

    // ── keyboard and focus ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun onGlobalKey(event: KeyboardEvent) {
        if (!(event.altKey && event.shiftKey && event.code == "KeyK")) return
        val state = ConferenceDock.state
        if (state is DockState.Idle || state.isAttached) return
        if (focusDockSurface()) event.preventDefault()
    }

    private fun onFocusIn(event: Event) {
        val target = event.target as? HTMLElement ?: return
        val win = windowElement
        if (win == null || win.contains(target)) return
        if (target !== document.body) lastOutsideFocus = target
        if (presentation != DockPresentation.FLOAT) return
        val t = target.getBoundingClientRect()
        val w = win.getBoundingClientRect()
        val covered =
            coversFocus(
                FloatRect(t.left.toInt(), t.top.toInt(), t.width.toInt(), t.height.toInt()),
                FloatRect(w.left.toInt(), w.top.toInt(), w.width.toInt(), w.height.toInt()),
            )
        if (covered) {
            win.classList.add("is-covering-focus")
            if (!announcedCoverOnce) {
                announcedCoverOnce = true
                announce(
                    DockAnnouncementKind.POLITE,
                    gettext("Das Konferenzfenster verdeckt das ausgewählte Element. Alt+Umschalt+K, dann Pfeiltasten zum Verschieben."),
                )
            }
        } else {
            win.classList.remove("is-covering-focus")
        }
    }

    /** Escape in the window: the focus goes back to where it was on the page (or the main landmark). The mode stays. */
    internal fun returnFocusToPage() {
        val previous = lastOutsideFocus
        if (previous != null && previous.isConnected) {
            previous.focus()
        } else {
            (document.getElementById("lapis-content") as? HTMLElement)?.focus()
        }
    }

    internal fun resetForTest() {
        uninstall()
        presentationListeners.clear()
        layoutListeners.clear()
        announceSinks.clear()
        preference = DEFAULT_FLOAT_PREFERENCE
        presentation = DockPresentation.BAR
        wide = false
        badgeRows = 1
        windowElement = null
        viewportForTest = null
        memo = FloatSpeakerMemo(mainKey = null, since = 0L)
        qualityCache.clear()
        lastOutsideFocus = null
        announcedCoverOnce = false
        retryScheduled = false
        retries = 0
        stage.dispose()
        ConferenceFloatStore.storageForTest = null
    }
}

// ── the window (KVision shell + raw stage) ────────────────────────────────────────────────────────────────────────────────────────────

/** Tooltip and accessible name of an icon-only button whose text changes with the state. */
private fun Button.setTooltip(text: String) {
    val resolved = resolvedAttributeText(text)
    setAttrIfChanged("title", resolved)
    setAttrIfChanged("aria-label", resolved)
}

/**
 * The floating window, built once and mounted as a direct child of the shell (a sibling of the mini bar, never inside the dock host,
 * which is hidden while the view is away). Shown and hidden by the `d-none` class, never by `hide()`.
 */
internal fun Container.conferenceFloatWindow(): Div {
    val win =
        Div(className = "$FLOAT_WINDOW_CLASS $DOCK_HIDDEN_CLASS") {
            setAttribute("role", "region")
            setAttribute("aria-label", gettext("Konferenzfenster"))
            setAttribute("tabindex", "0")
            setAttribute(
                "aria-description",
                gettext(
                    "Alt+Umschalt+K: zum Konferenzfenster, dann Pfeiltasten zum Verschieben, Umschalt+Pfeiltasten für die Größe, " +
                        "Escape zurück zur Seite",
                ),
            )
        }
    val header = Div(className = "lapis-float-header")
    val statusSpan = Span(className = "lapis-float-status text-truncate")
    val badges = Div(className = "lapis-float-badges")
    val headerButtons = Div(className = "lapis-float-header-buttons")
    val cornerButton = headerButtons.actionButton(ActionIcon.FLOAT_CORNER, tr("In die nächste Ecke"), ButtonStyle.LIGHT, iconOnly = true)
    val sizeButton = headerButtons.actionButton(ActionIcon.FLOAT_SIZE, tr("Größe: Mittel"), ButtonStyle.LIGHT, iconOnly = true)
    val collapseButton = headerButtons.actionButton(ActionIcon.COLLAPSE, tr("Einklappen"), ButtonStyle.LIGHT, iconOnly = true)
    val toViewButton = headerButtons.actionButton(ActionIcon.ENTER, tr("Zur Konferenz"), ButtonStyle.LIGHT, iconOnly = true)
    header.add(statusSpan)
    header.add(badges)
    header.add(headerButtons)
    win.add(header)

    // an empty KVision host: the raw stage is appended by the insert hook and never touched by a patch
    val stageHost = Div(className = "lapis-float-stage-host")
    win.add(stageHost)

    val controls =
        win.conferenceDockBarControls(
            onMic = { ConferenceDock.session?.toggleMic() },
            onCamera = { ConferenceDock.session?.toggleCamera() },
            onStopShare = { ConferenceDock.session?.stopScreenShare() },
            onLeave = { conferenceDockLeaveRequested() },
        )
    val grip = Span(className = "lapis-float-grip")
    grip.setAttribute("aria-hidden", "true")
    win.add(grip)

    cornerButton.onClick {
        ConferenceFloatController.setGeometry(
            nextCorner(ConferenceFloatController.preference.geometry),
            persist = true,
        )
    }
    sizeButton.onClick {
        val g = ConferenceFloatController.preference.geometry
        ConferenceFloatController.setGeometry(g.copy(width = nextSize(g.width).widthPx), persist = true)
    }
    collapseButton.onClick { ConferenceFloatController.setMode(FloatMode.BAR) }
    toViewButton.onClick { conferenceDockReturnToView() }

    var shownBadges: List<String> = emptyList()
    var shownVote = false
    var shownShare = false
    var shownSize: FloatSize? = null

    // while a header drag is running the pointer owns the position: layout() must not reset the inline left/top
    var dragActive = false

    fun layout() {
        if (dragActive) return
        val el = win.getElement() ?: return
        val controller = ConferenceFloatController
        if (controller.presentation != DockPresentation.FLOAT) return
        val vp = controller.viewport()
        val g = controller.preference.geometry
        val rect = floatRect(g, vp, controller.badgeRows)
        val style = el.style
        style.setProperty("width", "${rect.width}px")
        if (g.corner == 0 || g.corner == 3) {
            style.removeProperty("left")
            style.setProperty("right", "${vp.width - rect.left - rect.width}px")
        } else {
            style.removeProperty("right")
            style.setProperty("left", "${rect.left}px")
        }
        if (g.corner == 0 || g.corner == 1) {
            style.removeProperty("top")
            style.setProperty("bottom", "${vp.height - rect.top - rect.height}px")
        } else {
            style.removeProperty("bottom")
            style.setProperty("top", "${rect.top}px")
        }
        val size = floatSizeBucket(rect.width)
        if (shownSize != size) {
            shownSize = size
            sizeButton.setTooltip(sizeLabel(size))
            el.classList.toggle("is-size-small", size == FloatSize.SMALL)
        }
    }

    fun paint() {
        val controller = ConferenceFloatController
        val el = win.getElement()
        val state = ConferenceDock.state
        if (controller.presentation != DockPresentation.FLOAT) {
            if (!win.hasCssClass(DOCK_HIDDEN_CLASS)) {
                val hadFocus = el?.contains(document.activeElement) == true
                win.addCssClass(DOCK_HIDDEN_CLASS)
                if (hadFocus && !ConferenceDock.pendingFocusFromBar) {
                    (document.getElementById("lapis-content") as? HTMLElement)?.focus()
                }
            }
            return
        }
        val view = floatWindowViewOf(state)
        if (win.hasCssClass(DOCK_HIDDEN_CLASS)) win.removeCssClass(DOCK_HIDDEN_CLASS)
        val statusText = view.statusText // a local, not a dotted field: the status is a static tr() text, never untrusted content
        statusSpan.content = statusText
        if (view.consentBadges != shownBadges || view.voteBadge != shownVote || view.screenShare != shownShare) {
            shownBadges = view.consentBadges
            shownVote = view.voteBadge
            shownShare = view.screenShare
            badges.removeAll()
            view.consentBadges.forEach { badges.statusBadge(it, "danger") }
            if (view.voteBadge) badges.statusBadge(tr("Abstimmung läuft"), "info")
            if (view.screenShare) badges.statusBadge(tr("▣ Sie teilen Ihren Bildschirm"), "secondary")
        }
        if (view.consentBadges.isEmpty() && !view.voteBadge && !view.screenShare) badges.hide() else badges.show()
        if (view.showDevices || view.showLeave) controls.root.show() else controls.root.hide()
        if (view.showDevices) {
            controls.mic.show()
            controls.camera.show()
            controls.applyDevices(view.micPressed, view.cameraPressed)
        } else {
            controls.mic.hide()
            controls.camera.hide()
        }
        if (view.screenShare) controls.stopShare.show() else controls.stopShare.hide()
        if (view.showLeave) controls.leave.show() else controls.leave.hide()
        layout()
        // the badge rows are measured one tick after the patch (a long locale wraps); the height estimate follows
        window.setTimeout({
            val badgesEl = badges.getElement()
            val rows =
                if (badgesEl == null ||
                    badges.hasCssClass("d-none")
                ) {
                    1
                } else {
                    ((badgesEl.offsetHeight + 4) / FLOAT_BADGE_ROW_PX).coerceAtLeast(1)
                }
            if (rows != controller.badgeRows) {
                controller.badgeRows = rows
                layout()
            }
        }, 0)
    }

    // ── drag and resize (pointer) and keyboard ────────────────────────────────────────────────────────────────────────────────

    var removeListeners: (() -> Unit)? = null

    fun mountInteractions(el: HTMLElement) {
        val controller = ConferenceFloatController
        val headerEl = el.querySelector(".lapis-float-header") as? HTMLElement
        val gripEl = el.querySelector(".lapis-float-grip") as? HTMLElement
        val cleanups = mutableListOf<() -> Unit>()

        fun <T : Event> listen(
            target: org.w3c.dom.events.EventTarget,
            type: String,
            handler: (T) -> Unit,
        ) {
            val fn: (Event) -> Unit = { e -> handler(e.unsafeCast<T>()) }
            target.addEventListener(type, fn)
            cleanups += { target.removeEventListener(type, fn) }
        }

        // keyboard: only when the window itself has the focus (a button inside keeps its own keys)
        listen<KeyboardEvent>(el, "keydown") { e ->
            if (e.key == "Escape") {
                e.preventDefault()
                controller.returnFocusToPage()
                return@listen
            }
            if (e.target !== el) return@listen
            val key =
                when (e.key) {
                    "ArrowLeft" -> FloatKey.LEFT
                    "ArrowRight" -> FloatKey.RIGHT
                    "ArrowUp" -> FloatKey.UP
                    "ArrowDown" -> FloatKey.DOWN
                    "Home" -> FloatKey.HOME
                    else -> return@listen
                }
            e.preventDefault()
            val next = applyFloatKey(controller.preference.geometry, key, e.shiftKey, controller.viewport(), controller.badgeRows)
            controller.setGeometry(next, persist = false)
        }
        listen<KeyboardEvent>(el, "keyup") { e ->
            if (e.target === el && e.key in setOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home")) {
                controller.setGeometry(controller.preference.geometry, persist = true)
            }
        }

        // drag by the header (never from one of its buttons)
        if (headerEl != null) {
            var startX = 0
            var startY = 0
            var startRect: FloatRect? = null
            var dragging = false
            var pointerId = -1
            var before: FloatGeometry? = null
            listen<PointerEvent>(headerEl, "pointerdown") { e ->
                val targetEl = e.target as? HTMLElement
                if (e.button.toInt() != 0 || targetEl?.closest("button") != null) return@listen
                startX = e.clientX.toInt()
                startY = e.clientY.toInt()
                startRect = controller.currentRect()
                before = controller.preference.geometry
                pointerId = e.pointerId
                dragging = false
                runCatching { headerEl.asDynamic().setPointerCapture(e.pointerId) }
            }
            listen<PointerEvent>(headerEl, "pointermove") { e ->
                val origin = startRect ?: return@listen
                if (e.pointerId != pointerId) return@listen
                val dx = e.clientX.toInt() - startX
                val dy = e.clientY.toInt() - startY
                if (!dragging &&
                    kotlin.math.abs(dx) < FLOAT_DRAG_THRESHOLD_PX &&
                    kotlin.math.abs(dy) < FLOAT_DRAG_THRESHOLD_PX
                ) {
                    return@listen
                }
                if (!dragging) {
                    dragging = true
                    dragActive = true
                    el.style.setProperty("transition", "none")
                }
                val vp = controller.viewport()
                // the raw position while dragging; the anchor is recomputed on release
                val minLeft = vp.safeLeft + FLOAT_MARGIN_PX
                val minTop = vp.safeTop + FLOAT_MARGIN_PX
                val left = (origin.left + dx).coerceIn(minLeft, maxOf(minLeft, vp.width - vp.safeRight - FLOAT_MARGIN_PX - origin.width))
                val top = (origin.top + dy).coerceIn(minTop, maxOf(minTop, vp.height - vp.safeBottom - FLOAT_MARGIN_PX - origin.height))
                el.style.removeProperty("right")
                el.style.removeProperty("bottom")
                el.style.setProperty("left", "${left}px")
                el.style.setProperty("top", "${top}px")
            }
            listen<PointerEvent>(headerEl, "pointerup") { e ->
                val origin = startRect ?: return@listen
                if (e.pointerId != pointerId) return@listen
                runCatching { headerEl.asDynamic().releasePointerCapture(e.pointerId) }
                startRect = null
                el.style.removeProperty("transition")
                if (!dragging) return@listen
                dragging = false
                dragActive = false
                val vp = controller.viewport()
                val dropped =
                    origin.copy(
                        left =
                            (
                                el.style.left
                                    .removeSuffix("px")
                                    .toDoubleOrNull() ?: origin.left.toDouble()
                            ).toInt(),
                        top =
                            (
                                el.style.top
                                    .removeSuffix("px")
                                    .toDoubleOrNull() ?: origin.top.toDouble()
                            ).toInt(),
                    )
                controller.setGeometry(geometryFromRect(snapToEdges(dropped, vp), vp), persist = true)
                layout()
            }
            listen<PointerEvent>(headerEl, "pointercancel") { _ ->
                startRect = null
                dragging = false
                dragActive = false
                el.style.removeProperty("transition")
                before?.let { controller.setGeometry(it, persist = false) }
                layout()
            }
        }

        // resize by the grip (width only; the opposite corner stays)
        if (gripEl != null) {
            var startX = 0
            var startWidth = 0
            var gripLeft = false
            var active = false
            var pid = -1
            listen<PointerEvent>(gripEl, "pointerdown") { e ->
                if (e.button.toInt() != 0) return@listen
                val vp = controller.viewport()
                val rect = controller.currentRect()
                val corner = gripCorner(rect, vp)
                gripLeft = corner == 1 || corner == 2
                startX = e.clientX.toInt()
                startWidth = rect.width
                active = true
                pid = e.pointerId
                el.style.setProperty("transition", "none")
                runCatching { gripEl.asDynamic().setPointerCapture(e.pointerId) }
                e.preventDefault()
            }
            listen<PointerEvent>(gripEl, "pointermove") { e ->
                if (!active || e.pointerId != pid) return@listen
                val delta = e.clientX.toInt() - startX
                val width = startWidth + if (gripLeft) -delta else delta
                val vp = controller.viewport()
                val g = controller.preference.geometry
                controller.setGeometry(g.copy(width = snapWidth(clampWidth(width, vp, controller.badgeRows))), persist = false)
            }
            val finish: (PointerEvent) -> Unit = { e ->
                if (active && e.pointerId == pid) {
                    active = false
                    runCatching { gripEl.asDynamic().releasePointerCapture(e.pointerId) }
                    el.style.removeProperty("transition")
                    controller.setGeometry(controller.preference.geometry, persist = true)
                }
            }
            listen<PointerEvent>(gripEl, "pointerup") { e -> finish(e) }
            listen<PointerEvent>(gripEl, "pointercancel") { e -> finish(e) }
        }
        removeListeners = {
            dragActive = false
            cleanups.forEach { it() }
        }
    }

    var unobservePresentation: (() -> Unit)? = null
    var unobserveLayout: (() -> Unit)? = null
    return addWithLifecycle(
        win,
        onInsert = { vnode ->
            val el = vnode.elm as? HTMLElement
            if (el != null) {
                ConferenceFloatController.windowElement = el
                // the raw stage moves into the (possibly replaced) host in one step; a picture on it never leaves the document
                val host = el.querySelector(".lapis-float-stage-host")
                val stageRoot = ConferenceFloatController.stage.root
                if (host != null && stageRoot.parentNode !== host) host.appendChild(stageRoot)
                resumeStalledVideosIn(stageRoot)
                removeListeners?.invoke()
                mountInteractions(el)
            }
            unobservePresentation?.invoke()
            unobserveLayout?.invoke()
            unobservePresentation = ConferenceFloatController.observe { paint() }
            unobserveLayout = ConferenceFloatController.observeLayout { layout() }
            // NOT synchronously: changing the window's own classes inside its insert hook re-patches the vnode that is still being inserted
            window.setTimeout({ paint() }, 0)
        },
        onDestroy = {
            unobservePresentation?.invoke()
            unobservePresentation = null
            unobserveLayout?.invoke()
            unobserveLayout = null
            removeListeners?.invoke()
            removeListeners = null
            ConferenceFloatController.windowElement = null
        },
    )
}

// ── the announcer (never hidden, never inside the bar or the window) ──────────────────────────────────────────────────────────────────

/**
 * The two visually hidden live regions of the dock (polite for status, alert for consent changes and involuntary ends). They live in
 * their own element -- a sibling of the bar and the window -- because the bar is `display: none` while the window floats, and a region
 * in `display: none` is not read out. The consent display (recording, live stream) must be announced in BOTH presentations.
 */
internal fun Container.conferenceDockAnnouncer(): Div {
    val root = Div(className = "lapis-conference-dock-announcer")
    val politeRegion =
        Div(className = "visually-hidden") {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            setAttribute("aria-atomic", "true")
        }
    val alertRegion =
        Div(className = "visually-hidden") {
            setAttribute("role", "alert")
            setAttribute("aria-atomic", "true")
        }
    root.add(politeRegion)
    root.add(alertRegion)

    var previous: DockState = ConferenceDock.state
    var lastPolite = ""
    var lastAlert = ""

    fun say(
        kind: DockAnnouncementKind,
        text: String,
    ) {
        // an identical text twice in a row would not be read again: the content really written last (per region) decides, and the
        // zero-width marker toggles against it
        val last = if (kind == DockAnnouncementKind.ALERT) lastAlert else lastPolite
        val written = if (last == text) text + "\u200B" else text
        if (kind == DockAnnouncementKind.ALERT) {
            lastAlert = written
            alertRegion.content = written
        } else {
            lastPolite = written
            politeRegion.content = written
        }
    }

    fun onState(state: DockState) {
        val announcement =
            dockBarAnnouncement(
                previous,
                state,
                ConferenceDock.announcedFirstDetach,
                floating = ConferenceFloatController.showsFloat(state),
            )
        if (announcement != null) {
            say(announcement.kind, announcement.text)
            if (announcement.kind == DockAnnouncementKind.POLITE &&
                previous.isAttached &&
                previous !is DockState.Idle &&
                !state.isAttached &&
                state !is DockState.Stopped
            ) {
                ConferenceDock.announcedFirstDetach = true
            }
        }
        previous = state
    }

    var unobserveDock: (() -> Unit)? = null
    var unobserveSink: (() -> Unit)? = null
    return addWithLifecycle(
        root,
        onInsert = {
            unobserveDock?.invoke()
            unobserveSink?.invoke()
            previous = ConferenceDock.state
            unobserveDock = ConferenceDock.observe { state -> onState(state) }
            unobserveSink = ConferenceFloatController.observeAnnouncements { kind, text -> say(kind, text) }
        },
        onDestroy = {
            unobserveDock?.invoke()
            unobserveDock = null
            unobserveSink?.invoke()
            unobserveSink = null
        },
    )
}
