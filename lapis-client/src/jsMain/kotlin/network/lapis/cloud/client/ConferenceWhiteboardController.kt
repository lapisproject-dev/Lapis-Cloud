package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.shared.domain.ConferenceWhiteboardStateDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.WHITEBOARD_CANVAS_HEIGHT
import network.lapis.cloud.shared.domain.WHITEBOARD_CANVAS_WIDTH
import network.lapis.cloud.shared.domain.WHITEBOARD_COLORS
import network.lapis.cloud.shared.domain.WhiteboardPointDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto
import network.lapis.cloud.shared.domain.WhiteboardTool
import network.lapis.cloud.shared.rpc.IConferenceWhiteboardService
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.PI
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val THIN_STROKE_WIDTH = 4.0
private const val THICK_STROKE_WIDTH = 12.0
private const val ERASER_STROKE_WIDTH = 24.0

/**
 * V1.0 Wave 7 "Whiteboard" -- mirrors [network.lapis.cloud.server.conference.ConferenceWhiteboardState]'s
 * own defaults EXACTLY (see that class's own KDoc) -- used both for the client-side SOFT-cap guard
 * against the LOCAL author ([ConferenceWhiteboardController.canStartNewStroke]) and, since the
 * security-audit fix, as the HARD admission cap against REMOTE-sourced strokes received over the
 * LiveKit data channel ([canAdmitRemoteWhiteboardStroke]) -- never as the authoritative enforcement
 * for the caller's own commits (that is [IConferenceWhiteboardService.commitStroke]'s job,
 * server-side). If the server-side defaults ever change, this constant must be updated to match, or
 * the soft guard drifts from the real cap -- for the LOCAL-author soft guard a stale/too-low value
 * only means the warning fires a bit early (the server re-validates regardless); for the REMOTE hard
 * cap it only ever makes the client MORE conservative, never less, so drift there is not a security
 * issue either way. `internal` (not `private`) so [canAdmitRemoteWhiteboardStroke]'s own unit tests
 * ([canAdmitRemoteWhiteboardStroke] KDoc "no rendering harness") can assert against the real values.
 */
internal const val CLIENT_MAX_STROKES_PER_ROOM = 5_000
internal const val CLIENT_MAX_TOTAL_POINTS_PER_ROOM = 50_000
private const val CLIENT_SOFT_CAP_FRACTION = 0.95

/** Client-side local rendering model -- unifies [network.lapis.cloud.shared.domain.WhiteboardStrokeDto] (from [ConferenceWhiteboardStateDto]) and [WhiteboardStrokeWireDto] (from the data channel) into the one shape actually needed to paint a stroke; author metadata is irrelevant for rendering. */
private data class RenderableStroke(
    val strokeId: String,
    val tool: WhiteboardTool,
    val color: String,
    val strokeWidth: Double,
    val points: List<WhiteboardPointDto>,
)

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- self-contained panel, mirroring
 * [renderConferenceRecordingsPanel]'s own "self-contained conference sub-feature in its own file"
 * precedent rather than growing `ConferenceScreen.kt` further. Design review verdict (root
 * `CLAUDE.md` "UI/UX-Design-Team"):
 *
 * - **Placement**: a collapsible panel stacked below the video grid/roster, same shape and
 *   toggle-button-in-`controlsRow` pattern as the chat panel -- never a modal, never a grid
 *   replacement, never an overlay (Kay/Tesler/Raskin: modeless, coexisting, a moderator can draw
 *   while still seeing and talking to faces).
 * - **Toolbar**: the 5 fixed [WHITEBOARD_COLORS] swatches + eraser toggle + exactly two stroke-width
 *   presets (thin/thick, never a slider -- this codebase has no slider precedent anywhere), every
 *   control shows a visible selected/active state (Duarte/Zhuo).
 * - **"Board leeren"**: Tier 3 (`breakoutRecallConfirmDialog`'s weight -- [ButtonStyle.WARNING], no
 *   danger-red text) -- disruptive-to-others but neither ends a connection nor destroys a durable
 *   artifact (the save action exists precisely so nothing is truly lost). Client-side hidden for
 *   non-moderators; the server RPC gate is the sole authority.
 * - **"Als Dokument speichern"**: reuses `startRecordingConfirmDialog`'s shape (a
 *   [DocumentAccessLevel] select defaulting `BOARD_ONLY` with its own explanatory line) but drops
 *   the danger-red irreversibility framing -- saving is additive and repeatable.
 * - Real KVision-focusable DOM `<button>` elements for every toolbar control (colors/eraser/width/
 *   clear/save) -- never canvas-drawn. Drawing itself is inherently pointer-only, same as any
 *   freehand tool (accepted, not a gap).
 * - `touch-action: none` on the canvas + `setPointerCapture` on pointerdown (Forstall) -- otherwise
 *   the browser scrolls the page instead of drawing on a touch/pen device, and a fast stroke that
 *   briefly exits canvas bounds drops events mid-drag.
 *
 * **Coordinate normalization** (critical, see [network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto]
 * KDoc): every pointer event is scaled from on-screen CSS-pixel space into the FIXED LOGICAL canvas
 * space ([WHITEBOARD_CANVAS_WIDTH] x [WHITEBOARD_CANVAS_HEIGHT]) before it is ever appended to a
 * stroke's point list or sent anywhere -- the on-screen `<canvas>` element's INTERNAL pixel buffer is
 * set to exactly that logical size, so once normalized, no further scaling is needed anywhere in this
 * file's own rendering code either.
 *
 * **Eraser rendering -- deliberately opaque white, not `destination-out` compositing.** The plan
 * considered a real transparency composite for a "nicer live feel", but this controller draws opaque
 * white strokes instead, for two reasons: (1) it matches
 * `network.lapis.cloud.server.conference.WhiteboardRasterizer`'s own eraser rendering exactly, so the
 * live preview and the eventual saved PNG never visually diverge, and (2) it avoids an entire class of
 * composite-operation/z-order bugs (this canvas sits over a page background that is not guaranteed to
 * be pure white) for a difference no participant would ever actually notice.
 *
 * **Live propagation of "Board leeren" -- deliberately NOT pushed to already-open peer panels this
 * wave.** [IConferenceWhiteboardService.clearBoard] is the sole authoritative action; the calling
 * moderator's own panel clears immediately on RPC success. Every OTHER already-open whiteboard panel
 * catches up the next time it re-fetches [IConferenceWhiteboardService.getWhiteboardState] (panel
 * reopen or reconnect) rather than via a live data-channel push -- a deliberate, disclosed V1 scope
 * cut (clearing is rare enough, and self-heals quickly enough on next open, that inventing a third,
 * semi-trusted data-channel signal purely for this one case was not judged worth the complexity this
 * wave).
 *
 * **Per-strokeId author binding -- second security-audit fix.** [WhiteboardStrokeWireDto] carries no
 * author field of its own (see that type's KDoc "trust boundary") -- the only trustworthy author
 * identity for a data-channel packet is the SDK-derived `RemoteParticipant.identity` [LiveKitRoomSession]
 * passes into [applyPreview]/[applyCommit], never anything inside the payload itself. Without binding a
 * `strokeId` to the FIRST author ever observed for it, any current participant could republish a
 * structurally-valid [WhiteboardStrokeWireDto] reusing a `strokeId` they merely observed on the wire
 * (every `strokeId` is broadcast in plaintext from a stroke's very first preview packet) with their own
 * attacker-chosen points/color/tool, and [applyCommit] would unconditionally overwrite the legitimate
 * stroke's RENDERED content on every other currently-connected peer's screen -- targeted, repeatable
 * defacement of another participant's specific stroke, without ever touching the moderator-gated
 * `clearBoard` RPC. [strokeAuthors] closes this: the first author ever seen for a `strokeId` (whether
 * the LOCAL user starting a stroke, a remote preview, or a remote commit) is recorded and every
 * subsequent packet under that same `strokeId` must come from that same author or is silently dropped
 * (self-heals on the next [applyState] snapshot, same posture as every other lossy/rejected data-channel
 * packet in this controller -- see [canAdmitRemoteWhiteboardStroke] KDoc). The pure predicate behind this
 * check is [canAcceptWhiteboardStrokeAuthor] (unit-tested without a rendering harness, same posture as
 * [canAdmitRemoteWhiteboardStroke]). This binding is purely a client-side RENDERING-integrity guard --
 * the server's own durable state ([network.lapis.cloud.server.conference.ConferenceWhiteboardState]) is
 * unaffected either way, since `tryCommit`'s duplicate-`strokeId` dedup is already author-agnostic and
 * keeps the first-committed version.
 */
class ConferenceWhiteboardController(
    panel: SimplePanel,
    private val roomId: String,
    private val canModerate: Boolean,
    private val localMemberId: String,
    private val session: LiveKitRoomSession,
) {
    private val committed = LinkedHashMap<String, RenderableStroke>()
    private val preview = LinkedHashMap<String, RenderableStroke>()

    /**
     * `strokeId` -> the first author ever observed for it -- see class KDoc "Per-strokeId author
     * binding". Always kept in sync with `committed`/`preview` membership: an entry exists here iff
     * the same `strokeId` exists in `committed` or `preview` (written only on successful admission,
     * cleared together with them in [applyState] and [doClearBoard]), so this map can never outgrow
     * the bounded cap already enforced on those two maps by [canAdmitRemoteWhiteboardStroke].
     */
    private val strokeAuthors = LinkedHashMap<String, String>()

    private var selectedTool = WhiteboardTool.PEN
    private var selectedColor = WHITEBOARD_COLORS.first()
    private var selectedWidth = THIN_STROKE_WIDTH

    private var currentStrokeId: String? = null
    private var currentPoints: MutableList<WhiteboardPointDto> = mutableListOf()
    private var redrawScheduled = false

    private var canvasEl: HTMLCanvasElement? = null
    private var ctx: CanvasRenderingContext2D? = null

    private val colorSwatchElements = mutableListOf<Pair<HTMLElement, String>>()
    private var eraserButtonElement: HTMLElement? = null
    private var thinButtonElement: HTMLElement? = null
    private var thickButtonElement: HTMLElement? = null
    private var clearButton: Button? = null
    private var saveButton: Button? = null

    init {
        panel.removeAll()
        panel.h2("Whiteboard") { addCssClass("h6") }

        val toolbar = panel.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap mb-2") }

        WHITEBOARD_COLORS.forEach { hex ->
            val swatch = toolbar.button("", style = ButtonStyle.OUTLINESECONDARY)
            swatch.addCssClass("btn-sm")
            swatch.title = "Farbe wählen"
            swatch.addAfterInsertHook { vnode ->
                val el = vnode.elm as? HTMLElement ?: return@addAfterInsertHook
                el.style.cssText = "width:28px;height:28px;border-radius:50%;padding:0;background-color:$hex;"
                colorSwatchElements += el to hex
                updateToolbarSelection()
            }
            swatch.onClick {
                selectedTool = WhiteboardTool.PEN
                selectedColor = hex
                updateToolbarSelection()
            }
        }

        val eraser = toolbar.button("Radierer", style = ButtonStyle.OUTLINESECONDARY)
        eraser.addCssClass("btn-sm")
        eraser.addAfterInsertHook { vnode ->
            eraserButtonElement = vnode.elm as? HTMLElement
            updateToolbarSelection()
        }
        eraser.onClick {
            selectedTool = WhiteboardTool.ERASER
            updateToolbarSelection()
        }

        val thin = toolbar.button("Dünn", style = ButtonStyle.OUTLINESECONDARY)
        thin.addCssClass("btn-sm")
        thin.addAfterInsertHook { vnode ->
            thinButtonElement = vnode.elm as? HTMLElement
            updateToolbarSelection()
        }
        thin.onClick {
            selectedWidth = THIN_STROKE_WIDTH
            updateToolbarSelection()
        }

        val thick = toolbar.button("Dick", style = ButtonStyle.OUTLINESECONDARY)
        thick.addCssClass("btn-sm")
        thick.addAfterInsertHook { vnode ->
            thickButtonElement = vnode.elm as? HTMLElement
            updateToolbarSelection()
        }
        thick.onClick {
            selectedWidth = THICK_STROKE_WIDTH
            updateToolbarSelection()
        }

        // Tier 3 (breakoutRecallConfirmDialog's weight) -- see class KDoc "Board leeren". Client-side
        // hidden for non-moderators; clearBoard's own requireModeratorOrPrivileged gate is the sole
        // authority regardless.
        if (canModerate) {
            val clear = toolbar.button("Board leeren", style = ButtonStyle.OUTLINEWARNING)
            clear.addCssClasses("btn-sm ms-2")
            clear.onClick { whiteboardClearConfirmDialog { doClearBoard() } }
            clearButton = clear
        }

        val save = toolbar.button("Als Dokument speichern", style = ButtonStyle.OUTLINEPRIMARY)
        save.addCssClasses("btn-sm ms-2")
        save.onClick { whiteboardSaveAsDocumentDialog { level -> doSaveAsDocument(level) } }
        saveButton = save

        val canvasContainer = panel.div { addCssClasses("border rounded") }
        canvasContainer.addAfterInsertHook { vnode ->
            val container = vnode.elm as? HTMLElement ?: return@addAfterInsertHook
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            canvas.width = WHITEBOARD_CANVAS_WIDTH
            canvas.height = WHITEBOARD_CANVAS_HEIGHT
            // Forstall: touch-action:none, otherwise the browser scrolls the page on a touch/pen
            // device instead of letting pointer events drive drawing.
            canvas.style.cssText = "width:100%;height:auto;display:block;touch-action:none;background:#ffffff;"
            container.appendChild(canvas)
            canvasEl = canvas
            ctx = canvas.getContext("2d") as? CanvasRenderingContext2D
            wirePointerEvents(canvas)
            scheduleRedraw()
        }
    }

    /** V1.0 Wave 7 -- called once, right after `getWhiteboardState` resolves (late-joiner/panel-reopen seed, see [IConferenceWhiteboardService.getWhiteboardState] KDoc). Replaces the local model wholesale -- authoritative, drops any stale preview. */
    fun applyState(state: ConferenceWhiteboardStateDto) {
        committed.clear()
        preview.clear()
        strokeAuthors.clear()
        state.strokes.forEach { s ->
            committed[s.strokeId] = RenderableStroke(s.strokeId, s.tool, s.color, s.strokeWidth, s.points)
            strokeAuthors[s.strokeId] = s.authorMemberId
        }
        scheduleRedraw()
    }

    /** Wired to [LiveKitRoomSession]'s `onWhiteboardPreview` callback. */
    fun applyPreview(
        authorMemberId: String,
        stroke: WhiteboardStrokeWireDto,
    ) {
        if (authorMemberId == localMemberId) return // our own preview is already rendered locally, see wirePointerEvents.
        // Reordering guard across the unreliable preview channel and the reliable commit channel --
        // see IConferenceWhiteboardService KDoc "Trust asymmetry" / this file's applyCommit.
        if (stroke.strokeId in committed) return
        // Second security-audit fix: a peer reusing an observed strokeId under a DIFFERENT author
        // identity than whoever we first saw it from is a spoofing/defacement attempt -- see class
        // KDoc "Per-strokeId author binding". Must run BEFORE the admission-cap check below so it
        // also gates UPDATES to an already-admitted strokeId (isAlreadyKnown short-circuits the cap
        // check to true, so authorship is the only remaining guard for those).
        if (!canAcceptWhiteboardStrokeAuthor(strokeAuthors[stroke.strokeId], authorMemberId)) return
        // Security-audit fix: a peer can publish an unbounded NUMBER of individually-valid (per
        // LiveKitRoomSession.isStructurallyValid) strokes under fresh strokeIds, each below the
        // per-stroke point cap but adding up over time -- see this file's own "otherwise-unbounded
        // LinkedHashMap" audit finding. Cap remote-sourced entries at the same
        // CLIENT_MAX_STROKES_PER_ROOM/CLIENT_MAX_TOTAL_POINTS_PER_ROOM ceiling already enforced
        // against the LOCAL author on [canStartNewStroke] -- a peer past that ceiling gets dropped
        // silently (self-heals on the next [applyState] snapshot, same as any other lossy preview
        // packet).
        if (!hasRoomForRemoteStroke(stroke.strokeId, stroke.points.size)) return
        strokeAuthors[stroke.strokeId] = authorMemberId
        preview[stroke.strokeId] = stroke.toRenderable()
        scheduleRedraw()
    }

    /** Wired to [LiveKitRoomSession]'s `onWhiteboardCommit` callback. */
    fun applyCommit(
        authorMemberId: String,
        stroke: WhiteboardStrokeWireDto,
    ) {
        if (authorMemberId == localMemberId) return // our own commit is already rendered locally, see finalizeCurrentStroke.
        // See [applyPreview]'s own comment "Second security-audit fix" -- same author-binding guard
        // applies to commits, and is in fact the MORE important half of that fix: this is the call
        // that actually overwrites another participant's rendered stroke content. Must run before
        // touching `preview` at all -- a rejected forged commit must not even clear the legitimate
        // preview entry it was trying to impersonate.
        if (!canAcceptWhiteboardStrokeAuthor(strokeAuthors[stroke.strokeId], authorMemberId)) return
        // See [applyPreview]'s own comment -- same remote-growth cap applies to commits.
        if (!hasRoomForRemoteStroke(stroke.strokeId, stroke.points.size)) {
            preview.remove(stroke.strokeId)
            return
        }
        strokeAuthors[stroke.strokeId] = authorMemberId
        preview.remove(stroke.strokeId)
        committed[stroke.strokeId] = stroke.toRenderable()
        scheduleRedraw()
    }

    /** See [applyPreview]/[applyCommit] "Security-audit fix". Strokes already known under [strokeId] (an update to an in-progress preview, or a re-delivered commit) never count against the cap -- only a genuinely NEW distinct strokeId can push the room over it. Thin wrapper around [canAdmitRemoteWhiteboardStroke] (the actual, unit-testable predicate) that supplies this controller's own current counts. */
    private fun hasRoomForRemoteStroke(
        strokeId: String,
        incomingPointCount: Int,
    ): Boolean =
        canAdmitRemoteWhiteboardStroke(
            isAlreadyKnown = strokeId in committed || strokeId in preview,
            currentStrokeCount = committed.size + preview.size,
            currentPointCount = committed.values.sumOf { it.points.size } + preview.values.sumOf { it.points.size },
            incomingPointCount = incomingPointCount,
        )

    // ── drawing lifecycle ──────────────────────────────────────────────────

    private fun wirePointerEvents(canvas: HTMLCanvasElement) {
        canvas.addEventListener("pointerdown", { event -> onPointerDown(canvas, event) })
        canvas.addEventListener("pointermove", { event -> onPointerMove(canvas, event) })
        canvas.addEventListener("pointerup", { event -> onPointerUp(event) })
        canvas.addEventListener("pointercancel", { event -> onPointerUp(event) })
    }

    private fun onPointerDown(
        canvas: HTMLCanvasElement,
        event: Event,
    ) {
        val e = event as? PointerEvent ?: return
        if (!canStartNewStroke()) return
        runCatching { canvas.asDynamic().setPointerCapture(e.pointerId) }
        val point = toLogicalPoint(e, canvas)
        val strokeId = "$localMemberId-${Clock.System.now().toEpochMilliseconds()}-${Uuid.random()}"
        currentStrokeId = strokeId
        currentPoints = mutableListOf(point)
        // See class KDoc "Per-strokeId author binding" -- claim ownership of our own freshly-minted
        // strokeId immediately, so a peer cannot race us and claim it first.
        strokeAuthors[strokeId] = localMemberId
        preview[strokeId] = RenderableStroke(strokeId, selectedTool, selectedColor, activeStrokeWidth(), currentPoints.toList())
        scheduleRedraw()
    }

    private fun onPointerMove(
        canvas: HTMLCanvasElement,
        event: Event,
    ) {
        val e = event as? PointerEvent ?: return
        val strokeId = currentStrokeId ?: return
        val point = toLogicalPoint(e, canvas)
        currentPoints.add(point)
        // Auto-segmentation: stop this stroke here and start a fresh one at the same point, rather
        // than exceeding the server's own MAX_POINTS_PER_STROKE cap (ConferenceWhiteboardService) --
        // see class KDoc / this wave's plan §11.
        if (currentPoints.size >= MAX_POINTS_PER_STROKE_CLIENT) {
            finalizeCurrentStroke()
            val freshId = "$localMemberId-${Clock.System.now().toEpochMilliseconds()}-${Uuid.random()}"
            currentStrokeId = freshId
            currentPoints = mutableListOf(point)
            strokeAuthors[freshId] = localMemberId
            preview[freshId] = RenderableStroke(freshId, selectedTool, selectedColor, activeStrokeWidth(), currentPoints.toList())
        } else {
            preview[strokeId] = RenderableStroke(strokeId, selectedTool, selectedColor, activeStrokeWidth(), currentPoints.toList())
        }
        scheduleRedraw()
        val activeId = currentStrokeId ?: return
        val wire = WhiteboardStrokeWireDto(activeId, selectedTool, selectedColor, activeStrokeWidth(), currentPoints.toList())
        AppScope.launch { runCatching { session.sendWhiteboardPreview(wire) } }
    }

    private fun onPointerUp(event: Event) {
        (event as? PointerEvent) ?: return
        if (currentStrokeId == null) return
        finalizeCurrentStroke()
        currentStrokeId = null
    }

    /** Renders locally IMMEDIATELY (own commit, no wait, mirrors chat's "renders its own outgoing message itself" precedent), then fires the data-channel broadcast + durability RPC both async -- see [IConferenceWhiteboardService.commitStroke] KDoc "double-write". */
    private fun finalizeCurrentStroke() {
        val strokeId = currentStrokeId ?: return
        val points = currentPoints.toList()
        preview.remove(strokeId)
        if (points.isEmpty()) return
        val wire = WhiteboardStrokeWireDto(strokeId, selectedTool, selectedColor, activeStrokeWidth(), points)
        committed[strokeId] = wire.toRenderable()
        scheduleRedraw()
        AppScope.launch {
            guarded { session.sendWhiteboardCommit(wire) }
            guarded { rpcService<IConferenceWhiteboardService>().commitStroke(roomId, wire) }
        }
    }

    private fun activeStrokeWidth(): Double = if (selectedTool == WhiteboardTool.ERASER) ERASER_STROKE_WIDTH else selectedWidth

    /** Norman: prevents the WORSE failure mode than a post-hoc rejection toast -- a participant finishing a whole stroke only to have it silently rejected at commit time. */
    private fun canStartNewStroke(): Boolean {
        val strokeCount = committed.size
        val pointCount = committed.values.sumOf { it.points.size }
        val nearStrokeCap = strokeCount >= (CLIENT_MAX_STROKES_PER_ROOM * CLIENT_SOFT_CAP_FRACTION).toInt()
        val nearPointCap = pointCount >= (CLIENT_MAX_TOTAL_POINTS_PER_ROOM * CLIENT_SOFT_CAP_FRACTION).toInt()
        if (nearStrokeCap || nearPointCap) {
            notifyError("Board ist fast voll -- bitte speichern und leeren.")
            return false
        }
        return true
    }

    private fun toLogicalPoint(
        e: PointerEvent,
        canvas: HTMLCanvasElement,
    ): WhiteboardPointDto {
        val rect = canvas.getBoundingClientRect()
        val scaleX = if (rect.width > 0) WHITEBOARD_CANVAS_WIDTH / rect.width else 1.0
        val scaleY = if (rect.height > 0) WHITEBOARD_CANVAS_HEIGHT / rect.height else 1.0
        val x = ((e.clientX - rect.left) * scaleX).coerceIn(0.0, WHITEBOARD_CANVAS_WIDTH.toDouble())
        val y = ((e.clientY - rect.top) * scaleY).coerceIn(0.0, WHITEBOARD_CANVAS_HEIGHT.toDouble())
        return WhiteboardPointDto(x, y)
    }

    private fun WhiteboardStrokeWireDto.toRenderable() = RenderableStroke(strokeId, tool, color, strokeWidth, points)

    // ── rendering ──────────────────────────────────────────────────────────

    /** `requestAnimationFrame`-coalesced -- matches the plan's "redrawn frequently, throttled" preview-layer posture without a second explicit frame-budget mechanism. */
    private fun scheduleRedraw() {
        if (redrawScheduled) return
        redrawScheduled = true
        window.requestAnimationFrame {
            redrawScheduled = false
            redraw()
        }
    }

    private fun redraw() {
        val context = ctx ?: return
        context.clearRect(0.0, 0.0, WHITEBOARD_CANVAS_WIDTH.toDouble(), WHITEBOARD_CANVAS_HEIGHT.toDouble())
        context.asDynamic().fillStyle = "#ffffff"
        context.fillRect(0.0, 0.0, WHITEBOARD_CANVAS_WIDTH.toDouble(), WHITEBOARD_CANVAS_HEIGHT.toDouble())
        context.asDynamic().lineCap = "round"
        context.asDynamic().lineJoin = "round"
        committed.values.forEach { drawStroke(context, it) }
        preview.values.forEach { drawStroke(context, it) }
    }

    /** See class KDoc "Eraser rendering" for why this draws opaque white rather than a `destination-out` composite. */
    private fun drawStroke(
        context: CanvasRenderingContext2D,
        stroke: RenderableStroke,
    ) {
        if (stroke.points.isEmpty()) return
        val color = if (stroke.tool == WhiteboardTool.ERASER) "#ffffff" else stroke.color
        context.asDynamic().strokeStyle = color
        context.asDynamic().fillStyle = color
        context.asDynamic().lineWidth = stroke.strokeWidth
        if (stroke.points.size == 1) {
            val p = stroke.points[0]
            val r = stroke.strokeWidth / 2.0
            context.beginPath()
            context.arc(p.x, p.y, r, 0.0, 2 * PI)
            context.fill()
        } else {
            context.beginPath()
            context.moveTo(stroke.points[0].x, stroke.points[0].y)
            stroke.points.drop(1).forEach { p -> context.lineTo(p.x, p.y) }
            context.stroke()
        }
    }

    // ── toolbar selected-state (Duarte/Zhuo: every control must show what is active) ────────────

    private fun updateToolbarSelection() {
        colorSwatchElements.forEach { (el, hex) ->
            val isSelected = selectedTool == WhiteboardTool.PEN && selectedColor == hex
            el.style.border = if (isSelected) "3px solid #212529" else "1px solid #ced4da"
        }
        eraserButtonElement?.let { el ->
            el.classList.toggle("active", selectedTool == WhiteboardTool.ERASER)
        }
        thinButtonElement?.let { el ->
            el.classList.toggle("active", selectedTool != WhiteboardTool.ERASER && selectedWidth == THIN_STROKE_WIDTH)
        }
        thickButtonElement?.let { el ->
            el.classList.toggle("active", selectedTool != WhiteboardTool.ERASER && selectedWidth == THICK_STROKE_WIDTH)
        }
    }

    // ── moderator/save actions ────────────────────────────────────────────

    private fun doClearBoard() {
        val button = clearButton ?: return
        button.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IConferenceWhiteboardService>().clearBoard(roomId) }
            button.disabled = false
            if (result != null) {
                committed.clear()
                preview.clear()
                strokeAuthors.clear()
                scheduleRedraw()
                notifySuccess("Whiteboard geleert.")
            }
        }
    }

    private fun doSaveAsDocument(accessLevel: DocumentAccessLevel) {
        val button = saveButton ?: return
        button.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IConferenceWhiteboardService>().saveAsDocument(roomId, accessLevel) }
            button.disabled = false
            if (result != null) {
                notifySuccess("Whiteboard als Dokument gespeichert.")
            }
        }
    }
}

/** Auto-segmentation threshold -- see [ConferenceWhiteboardController.onPointerMove] and `ConferenceWhiteboardService`'s own `MAX_POINTS_PER_STROKE` server-side cap (server-authoritative; this client-side value stays comfortably below it so the client always segments first). */
private const val MAX_POINTS_PER_STROKE_CLIENT = 1_800

/**
 * Security-audit fix -- pure predicate behind [ConferenceWhiteboardController.applyPreview]/
 * [ConferenceWhiteboardController.applyCommit]'s remote-stroke admission check, extracted to a
 * top-level `internal` function (rather than a private method on the controller) specifically so it
 * is unit-testable without this module's DOM/KVision rendering harness -- same "no rendering harness
 * exists, pure functions are tested directly" posture as [conferenceGridLayout] (see
 * [ConferenceGridLayoutTest] KDoc).
 *
 * Bounds a room's total remote-plus-local stroke/point counts at the SAME
 * [CLIENT_MAX_STROKES_PER_ROOM]/[CLIENT_MAX_TOTAL_POINTS_PER_ROOM] ceiling [canStartNewStroke]
 * already enforces client-side against the LOCAL author -- closing the residual DoS surface an
 * independent security audit flagged: `LiveKitRoomSession`'s own per-message
 * `isStructurallyValid()` check bounds any ONE published stroke's size, but a malicious/compromised
 * peer could still publish an unbounded NUMBER of individually-valid strokes under fresh
 * `strokeId`s, growing this controller's `committed`/`preview` maps without limit (they are
 * otherwise only ever reset wholesale by the next `getWhiteboardState`-driven `applyState`).
 *
 * @param isAlreadyKnown true iff a stroke with this `strokeId` already exists in `committed` or
 *   `preview` -- an UPDATE to an already-admitted stroke (a later preview frame for the same
 *   in-progress stroke, or that stroke's own eventual commit) never counts against the cap again;
 *   only a genuinely NEW distinct `strokeId` can push the room over it.
 * @param currentStrokeCount total distinct strokeIds currently held (`committed.size + preview.size`).
 * @param currentPointCount total points currently held across every held stroke.
 * @param incomingPointCount how many points the CANDIDATE stroke itself would add.
 */
internal fun canAdmitRemoteWhiteboardStroke(
    isAlreadyKnown: Boolean,
    currentStrokeCount: Int,
    currentPointCount: Int,
    incomingPointCount: Int,
): Boolean {
    if (isAlreadyKnown) return true
    return currentStrokeCount < CLIENT_MAX_STROKES_PER_ROOM &&
        currentPointCount + incomingPointCount <= CLIENT_MAX_TOTAL_POINTS_PER_ROOM
}

/**
 * Second security-audit fix -- pure predicate behind [ConferenceWhiteboardController.applyPreview]/
 * [ConferenceWhiteboardController.applyCommit]'s per-strokeId author-binding guard, extracted `internal`
 * for the same DOM-free unit-testing reason as [canAdmitRemoteWhiteboardStroke] (see that function's own
 * KDoc, and [ConferenceWhiteboardController]'s class KDoc "Per-strokeId author binding" for the full
 * attack this closes: any current participant republishing an observed `strokeId` under attacker-chosen
 * content to deface another participant's rendered stroke on every OTHER peer's screen).
 *
 * @param recordedAuthorMemberId the author this controller first ever admitted for this `strokeId`, or
 *   `null` if this is the very first packet ever seen under it (nothing to compare against yet -- the
 *   first author to publish anything under a `strokeId` always establishes ownership of it).
 * @param incomingAuthorMemberId the SDK-derived (`RemoteParticipant.identity`), trustworthy author of
 *   the CANDIDATE packet -- never anything read out of the payload itself.
 */
internal fun canAcceptWhiteboardStrokeAuthor(
    recordedAuthorMemberId: String?,
    incomingAuthorMemberId: String,
): Boolean = recordedAuthorMemberId == null || recordedAuthorMemberId == incomingAuthorMemberId

/**
 * Design review: Tier 3, matches [breakoutRecallConfirmDialog]'s weight -- disruptive-to-others but
 * neither ends a connection nor destroys a durable artifact (the save action exists precisely so
 * nothing is truly lost first). No `text-danger` body text, primary button restates the noun ("Board
 * leeren", never "OK"/"Bestätigen"), states both the immediacy and the escape hatch.
 */
private fun whiteboardClearConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = "Whiteboard leeren")
    modal.div(
        "Alle Zeichnungen auf dem Whiteboard werden sofort für alle Teilnehmenden entfernt.",
    ) { addCssClass("fw-bold") }
    modal.div(
        "Falls die Zeichnung noch gebraucht wird: vorher als Dokument speichern.",
    ) { addCssClasses("text-muted small") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Board leeren", style = ButtonStyle.WARNING).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * Design review: reuses `startRecordingConfirmDialog`'s shape ([DocumentAccessLevel] select
 * defaulting `BOARD_ONLY` with its own explanatory line) but drops the danger-red irreversibility
 * framing -- saving is additive and repeatable, unlike starting a recording that exposes live speech.
 */
private fun whiteboardSaveAsDocumentDialog(onConfirm: (DocumentAccessLevel) -> Unit) {
    val modal = Modal(caption = "Whiteboard als Dokument speichern")
    modal.div(
        "Der aktuelle Stand des Whiteboards wird als Bild in der Dokumentenablage gespeichert.",
    ) { addCssClasses("small mb-2") }
    val accessOptions = DocumentAccessLevel.entries.map { it.name to conferenceRecordingAccessLevelLabel(it) }
    val accessSelect =
        modal.select(options = accessOptions, value = DocumentAccessLevel.BOARD_ONLY.name, label = "Zugriffsebene")
    modal.div(
        "Bei \"Vorstand\" können anwesende Mitglieder, die nicht dem Vorstand angehören, das " +
            "gespeicherte Whiteboard später NICHT ansehen -- wählen Sie \"Mitglieder\", wenn es allen " +
            "Teilnehmenden zugänglich sein soll.",
    ) { addCssClasses("text-muted small mb-2") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Speichern", style = ButtonStyle.PRIMARY).apply {
            onClick {
                val level = accessSelect.value?.let { DocumentAccessLevel.valueOf(it) } ?: DocumentAccessLevel.BOARD_ONLY
                modal.hide()
                onConfirm(level)
            }
        },
    )
    modal.show()
}
