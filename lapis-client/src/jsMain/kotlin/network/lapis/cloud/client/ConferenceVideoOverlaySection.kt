package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement

/*
 * V1.9.129 "Video-Overlays" -- the panel in the "Mehr" sheet: ONE collapsed row (like Whiteboard/Notes/Background; the state stands in its
 * text), below it the switch, the preview, the 2x2 corner grid with the choices of the selected corner, and the frame.
 *
 * - The whole section does not exist (no node in the DOM) while the option is off for this instance ([offered] `false`). In a browser
 *   without the processor support the row stays, disabled, with the same kind of explanation as the background effects (Norman K4).
 * - The honest notice "Verändert Ihr Bild – alle sehen es, auch in Aufnahme und Livestream." is shown for as long as something is drawn.
 * - Every group is a real `radiogroup` of `role=radio` buttons with roving tabindex; the arrow keys move AND choose (like native radios).
 *   The corner grid additionally moves by row and column ([overlayCornerArrowTarget]).
 * - The preview is the person's OWN processed camera stream (`track.attach()` on a `<video>`, so it shows exactly what others see) or, with
 *   the camera off, a static canvas with a neutral silhouette and the same scene drawn by the same plan. Nothing is ever an `<img>`.
 * - `tr(...)` only as widget content, `gettext(...)` for attributes (`ClientTrAttributeLeakTest`); no `addAfterInsertHook` except for the
 *   `<video>` element (its `srcObject` needs the real node); every other attribute goes through the patch cycle.
 */

private const val OVERLAY_PANEL_ID = "lapis-conference-video-overlay-panel"

/** What the preview needs from the call: the live camera track, and a throw-away scene for the static picture. */
internal interface OverlayPreviewHost {
    fun cameraOn(): Boolean

    /** Attaches the (processed) local camera stream to [video]; `false` if there is no camera track. */
    fun attachPreview(video: HTMLVideoElement): Boolean

    fun detachPreview(video: HTMLVideoElement)

    /** Draws the current scene (and nothing else) onto [canvas]'s 2D context -- asynchronous, best effort. */
    fun drawStaticScene(canvas: HTMLCanvasElement)
}

/** The arrow-key target inside the 2x2 corner grid: right/left within the row, down/up within the column (no wrap); `null` otherwise. */
internal fun overlayCornerArrowTarget(
    from: OverlayCorner,
    key: String,
): OverlayCorner? =
    when (key) {
        "ArrowRight" ->
            when (from) {
                OverlayCorner.TOP_LEFT -> OverlayCorner.TOP_RIGHT
                OverlayCorner.BOTTOM_LEFT -> OverlayCorner.BOTTOM_RIGHT
                else -> null
            }
        "ArrowLeft" ->
            when (from) {
                OverlayCorner.TOP_RIGHT -> OverlayCorner.TOP_LEFT
                OverlayCorner.BOTTOM_RIGHT -> OverlayCorner.BOTTOM_LEFT
                else -> null
            }
        "ArrowDown" ->
            when (from) {
                OverlayCorner.TOP_LEFT -> OverlayCorner.BOTTOM_LEFT
                OverlayCorner.TOP_RIGHT -> OverlayCorner.BOTTOM_RIGHT
                else -> null
            }
        "ArrowUp" ->
            when (from) {
                OverlayCorner.BOTTOM_LEFT -> OverlayCorner.TOP_LEFT
                OverlayCorner.BOTTOM_RIGHT -> OverlayCorner.TOP_RIGHT
                else -> null
            }
        else -> null
    }

/** Next index of a linear radiogroup of [size] items for [key] (wrapping); `null` for every other key. */
internal fun overlayRadioTarget(
    from: Int,
    key: String,
    size: Int,
): Int? {
    if (size <= 0 || from !in 0 until size) return null
    return when (key) {
        "ArrowRight", "ArrowDown" -> (from + 1) % size
        "ArrowLeft", "ArrowUp" -> (from - 1 + size) % size
        "Home" -> 0
        "End" -> size - 1
        else -> null
    }
}

private fun cornerLabelTr(corner: OverlayCorner): String =
    when (corner) {
        OverlayCorner.TOP_LEFT -> tr("Oben links")
        OverlayCorner.TOP_RIGHT -> tr("Oben rechts")
        OverlayCorner.BOTTOM_LEFT -> tr("Unten links")
        OverlayCorner.BOTTOM_RIGHT -> tr("Unten rechts")
    }

internal fun overlayCornerLabel(corner: OverlayCorner): String =
    when (corner) {
        OverlayCorner.TOP_LEFT -> gettext("Oben links")
        OverlayCorner.TOP_RIGHT -> gettext("Oben rechts")
        OverlayCorner.BOTTOM_LEFT -> gettext("Unten links")
        OverlayCorner.BOTTOM_RIGHT -> gettext("Unten rechts")
    }

private fun sizeLabelTr(size: OverlaySize): String =
    when (size) {
        OverlaySize.SMALL -> tr("Klein")
        OverlaySize.MEDIUM -> tr("Mittelgroß")
        OverlaySize.LARGE -> tr("Groß")
    }

private fun opacityLabelTr(opacity: OverlayOpacity): String =
    when (opacity) {
        OverlayOpacity.FULL -> tr("100 %")
        OverlayOpacity.STRONG -> tr("70 %")
        OverlayOpacity.LIGHT -> tr("40 %")
    }

private class RadioOption(
    val key: String,
    val label: String,
)

/**
 * One `radiogroup`: [setOptions] (re)builds the buttons, [select] marks one (roving tabindex: the checked one, or the first when none is).
 * A key press that moves also chooses ([onPick]).
 */
private class OverlayRadioGroup(
    parent: Container,
    ariaLabel: String,
    private val onPick: (String) -> Unit,
    className: String = "lapis-conference-overlay-radios",
) {
    val root: Div = parent.div(className = className)
    private var options: List<RadioOption> = emptyList()
    private val buttons = LinkedHashMap<String, Tag>()
    private var selected: String? = null

    init {
        root.setAttribute("role", "radiogroup")
        root.setAttribute("aria-label", ariaLabel)
    }

    fun setOptions(newOptions: List<RadioOption>) {
        options = newOptions
        root.removeAll()
        buttons.clear()
        for ((index, option) in newOptions.withIndex()) {
            val button =
                root.tag(
                    TAG.BUTTON,
                    content = option.label,
                    className = "btn btn-outline-secondary btn-sm lapis-conference-overlay-radio",
                )
            button.setAttribute("type", "button")
            button.setAttribute("role", "radio")
            button.setAttribute("aria-checked", "false")
            button.setAttribute("tabindex", if (index == 0) "0" else "-1")
            button.onEvent {
                click = { onPick(option.key) }
                keydown = { e ->
                    val key = e.key
                    overlayRadioTarget(index, key, options.size)?.let { target ->
                        e.preventDefault()
                        val targetKey = options[target].key
                        onPick(targetKey)
                        buttons[targetKey]?.getElement()?.focus()
                    }
                }
            }
            buttons[option.key] = button
        }
        select(selected)
    }

    fun select(key: String?) {
        selected = key
        val anyMatch = key != null && buttons.containsKey(key)
        for ((index, entry) in buttons.entries.withIndex()) {
            val isSelected = anyMatch && entry.key == key
            entry.value.setAttribute("aria-checked", isSelected.toString())
            entry.value.setAttribute("tabindex", if (isSelected || (!anyMatch && index == 0)) "0" else "-1")
            if (isSelected) entry.value.addCssClass("active") else entry.value.removeCssClass("active")
        }
    }
}

internal class ConferenceVideoOverlaySection(
    parent: Container,
    /** `false` => NOTHING is built (the panel is not in the DOM). */
    val offered: Boolean,
    val availability: ConferenceBackgroundAvailability,
    private val onChange: (VideoOverlaySettings) -> Unit,
    private val previewHost: OverlayPreviewHost? = null,
) {
    val supported: Boolean get() = offered && availability == ConferenceBackgroundAvailability.AVAILABLE
    val present: Boolean get() = offered

    internal var toggleButton: Button? = null
        private set
    private var group: SimplePanel? = null
    var panelOpen: Boolean = false
        private set

    private var lastUi: VideoOverlayUiState? = null
    private var selectedCorner: OverlayCorner = OverlayCorner.TOP_LEFT

    private var hintLine: Div? = null
    private var switchButton: Tag? = null
    private var cornerTiles = LinkedHashMap<OverlayCorner, Tag>()
    private var cornerGroupRoot: Div? = null
    private var contentGroup: OverlayRadioGroup? = null
    private var sizeGroup: OverlayRadioGroup? = null
    private var opacityGroup: OverlayRadioGroup? = null
    private var frameGroup: OverlayRadioGroup? = null
    private var frameOpacityGroup: OverlayRadioGroup? = null
    private var emptyLibraryLine: Div? = null
    private var previewCaption: Div? = null
    private var previewVideo: Tag? = null
    private var previewCanvas: Tag? = null
    private val previewVideoElement: HTMLVideoElement?
        get() = (previewVideo?.getElement() as? HTMLVideoElement)?.also { it.muted = true }
    private var previewAttached = false
    private var renderedLibraryKey: List<String>? = null

    init {
        if (offered) {
            val rowButton = parent.button(tr("Overlays: Aus"), icon = "fas fa-layer-group", style = ButtonStyle.OUTLINESECONDARY)
            rowButton.addCssClasses("w-100 text-start")
            toggleButton = rowButton
            val panel = parent.vPanel(spacing = 6) { addCssClasses("lapis-conference-video-overlay-group") }
            panel.setAttribute("id", OVERLAY_PANEL_ID)
            group = panel
            if (!supported) {
                rowButton.disabled = true
                panel.div(unsupportedExplanation()) { addCssClasses("text-muted small") }
            } else {
                panel.hide()
                buildOpenContent(panel)
                rowButton.onClick { togglePanel() }
            }
            applyToggleAria()
        }
    }

    private fun unsupportedExplanation(): String =
        if (availability == ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW) {
            tr("Overlays werden in der App noch nicht unterstützt – im Browser dieses Geräts stehen sie zur Verfügung.")
        } else {
            tr("Overlays werden von diesem Browser oder Gerät nicht unterstützt.")
        }

    private fun togglePanel() {
        panelOpen = !panelOpen
        if (panelOpen) group?.show() else group?.hide()
        applyToggleAria()
        syncPreview()
    }

    fun applyToggleAria() {
        val button = toggleButton ?: return
        if (!supported) return
        button.setAttribute("aria-expanded", panelOpen.toString())
        button.setAttribute("aria-controls", OVERLAY_PANEL_ID)
    }

    private fun mutate(change: (VideoOverlaySettings) -> VideoOverlaySettings) {
        val ui = lastUi ?: return
        onChange(change(ui.settings))
    }

    private fun updateSlot(change: (CornerSlot) -> CornerSlot) {
        mutate { settings ->
            val slots = settings.corners.toMutableMap()
            slots[selectedCorner] = change(settings.slot(selectedCorner))
            settings.copy(corners = slots)
        }
    }

    private fun buildOpenContent(panel: SimplePanel) {
        hintLine =
            panel.div(tr("Verändert Ihr Bild – alle sehen es, auch in Aufnahme und Livestream.")) {
                addCssClasses("small lapis-conference-overlay-hint")
                hide()
            }
        val switch = panel.tag(TAG.BUTTON, content = tr("Overlays einblenden"), className = "btn btn-outline-secondary w-100 text-start")
        switch.setAttribute("type", "button")
        switch.setAttribute("role", "switch")
        switch.setAttribute("aria-checked", "false")
        switch.onEvent { click = { mutate { it.copy(enabled = !it.enabled) } } }
        switchButton = switch

        buildPreview(panel)

        val corners = panel.div(className = "lapis-conference-overlay-corners")
        corners.setAttribute("role", "radiogroup")
        corners.setAttribute("aria-label", gettext("Ecke wählen"))
        cornerGroupRoot = corners
        for (corner in OverlayCorner.entries) {
            val tile =
                corners.tag(
                    TAG.BUTTON,
                    content = cornerLabelTr(corner),
                    className = "btn btn-outline-secondary lapis-conference-overlay-corner",
                )
            tile.setAttribute("type", "button")
            tile.setAttribute("role", "radio")
            tile.setAttribute("aria-checked", "false")
            tile.setAttribute("tabindex", "-1")
            tile.onEvent {
                click = { selectCorner(corner, focus = false) }
                keydown = { e ->
                    val key = e.key
                    overlayCornerArrowTarget(corner, key)?.let { target ->
                        e.preventDefault()
                        selectCorner(target, focus = true)
                    }
                }
            }
            cornerTiles[corner] = tile
        }

        contentGroup = OverlayRadioGroup(panel, gettext("Inhalt"), onPick = { key -> pickContent(key) })
        sizeGroup =
            OverlayRadioGroup(panel, gettext("Größe"), onPick = { key ->
                OverlaySize.entries.firstOrNull { it.id == key }?.let { s -> updateSlot { it.copy(size = s) } }
            })
                .also { it.setOptions(OverlaySize.entries.map { s -> RadioOption(s.id, sizeLabelTr(s)) }) }
        opacityGroup =
            OverlayRadioGroup(panel, gettext("Deckkraft"), onPick = { key ->
                OverlayOpacity.entries.firstOrNull { it.id == key }?.let { o -> updateSlot { it.copy(opacity = o) } }
            })
                .also { it.setOptions(OverlayOpacity.entries.map { o -> RadioOption(o.id, opacityLabelTr(o)) }) }

        panel.div(tr("Rahmen")) { addCssClasses("small text-muted") }
        frameGroup = OverlayRadioGroup(panel, gettext("Rahmen"), onPick = { key -> pickFrame(key) })
        frameOpacityGroup =
            OverlayRadioGroup(
                panel,
                gettext("Deckkraft des Rahmens"),
                onPick = { key ->
                    OverlayOpacity.entries.firstOrNull { it.id == key }?.let { o ->
                        mutate { settings -> settings.frame?.let { settings.copy(frame = it.copy(opacity = o)) } ?: settings }
                    }
                },
            ).also { it.setOptions(OverlayOpacity.entries.map { o -> RadioOption(o.id, opacityLabelTr(o)) }) }
        emptyLibraryLine =
            panel.div(tr("Es sind noch keine Logos oder Rahmen hinterlegt – Ihr Name steht trotzdem zur Verfügung.")) {
                addCssClasses("small text-muted")
                hide()
            }
    }

    private fun buildPreview(panel: SimplePanel) {
        val box = panel.div(className = "lapis-conference-overlay-preview")
        previewCaption = box.div(tr("So sehen andere Sie")) { addCssClasses("small text-muted") }
        if (previewHost != null) {
            val video = box.tag(TAG.VIDEO, className = "lapis-conference-overlay-preview-video")
            video.setAttribute("playsinline", "")
            video.setAttribute("muted", "")
            video.setAttribute("aria-hidden", "true")
            previewVideo = video
            val canvas = box.tag(TAG.CANVAS, className = "lapis-conference-overlay-preview-canvas")
            canvas.setAttribute("width", OVERLAY_PREVIEW_WIDTH.toString())
            canvas.setAttribute("height", OVERLAY_PREVIEW_HEIGHT.toString())
            canvas.setAttribute("aria-hidden", "true")
            canvas.hide()
            previewCanvas = canvas
        }
    }

    private fun selectCorner(
        corner: OverlayCorner,
        focus: Boolean,
    ) {
        selectedCorner = corner
        lastUi?.let { applyCornerState(it) }
        if (focus) cornerTiles[corner]?.getElement()?.focus()
    }

    private fun pickContent(key: String) {
        val content =
            when (key) {
                CONTENT_EMPTY -> OverlayContent.Empty
                CONTENT_NAME -> OverlayContent.OwnName
                else -> if (isValidOverlayAssetId(key)) OverlayContent.Library(key) else return
            }
        updateSlot { it.copy(content = content) }
    }

    private fun pickFrame(key: String) {
        mutate { settings ->
            if (key == FRAME_NONE) {
                settings.copy(frame = null)
            } else if (isValidOverlayAssetId(key)) {
                settings.copy(frame = FrameSlot(assetId = key, opacity = settings.frame?.opacity ?: OverlayOpacity.FULL))
            } else {
                settings
            }
        }
    }

    /** The one place that sets text, checks and visibility from the controller's state; repeated calls are intended. */
    fun render(ui: VideoOverlayUiState) {
        if (!offered) return
        lastUi = ui
        val rowButton = toggleButton ?: return
        if (!supported) return
        rowButton.text = if (ui.settings.enabled) tr("Overlays: An") else tr("Overlays: Aus")
        switchButton?.setAttribute("aria-checked", ui.settings.enabled.toString())
        if (ui.drawing) hintLine?.show() else hintLine?.hide()
        rebuildLibraryOptions(ui)
        applyCornerState(ui)
        frameGroup?.select(ui.settings.frame?.assetId ?: FRAME_NONE)
        frameOpacityGroup?.select((ui.settings.frame?.opacity ?: OverlayOpacity.FULL).id)
        val hasLibrary = ui.library.isNotEmpty()
        if (ui.libraryLoaded && !hasLibrary) emptyLibraryLine?.show() else emptyLibraryLine?.hide()
        applyToggleAria()
        syncPreview()
    }

    private fun rebuildLibraryOptions(ui: VideoOverlayUiState) {
        val key = ui.library.map { "${it.id}|${it.kind}|${it.name}" }
        if (key == renderedLibraryKey) return
        renderedLibraryKey = key
        val logos = ui.library.filter { it.kind == VideoOverlayKind.LOGO }
        val frames = ui.library.filter { it.kind == VideoOverlayKind.FRAME }
        contentGroup?.setOptions(
            listOf(RadioOption(CONTENT_EMPTY, tr("Leer")), RadioOption(CONTENT_NAME, tr("Ihr Name"))) +
                logos.map { RadioOption(it.id, sanitizeUntrustedI18nText(it.name)) },
        )
        frameGroup?.setOptions(
            listOf(RadioOption(FRAME_NONE, tr("Kein Rahmen"))) + frames.map { RadioOption(it.id, sanitizeUntrustedI18nText(it.name)) },
        )
    }

    private fun applyCornerState(ui: VideoOverlayUiState) {
        for ((corner, tile) in cornerTiles) {
            val selected = corner == selectedCorner
            tile.setAttribute("aria-checked", selected.toString())
            tile.setAttribute("tabindex", if (selected) "0" else "-1")
            tile.setAttribute("aria-label", cornerTileAriaLabel(corner, ui))
            if (selected) tile.addCssClass("active") else tile.removeCssClass("active")
        }
        val slot = ui.settings.slot(selectedCorner)
        contentGroup?.select(
            when (val content = slot.content) {
                OverlayContent.Empty -> CONTENT_EMPTY
                OverlayContent.OwnName -> CONTENT_NAME
                is OverlayContent.Library -> content.assetId
            },
        )
        sizeGroup?.select(slot.size.id)
        opacityGroup?.select(slot.opacity.id)
    }

    private fun cornerTileAriaLabel(
        corner: OverlayCorner,
        ui: VideoOverlayUiState,
    ): String {
        val content = ui.settings.slot(corner).content
        val what =
            when (content) {
                OverlayContent.Empty -> gettext("Leer")
                OverlayContent.OwnName -> gettext("Ihr Name")
                is OverlayContent.Library -> ui.library.firstOrNull { it.id == content.assetId }?.name ?: gettext("Leer")
            }
        return "${overlayCornerLabel(corner)}: $what"
    }

    /** Attaches the live stream while the panel is open and the camera is on; otherwise the static picture (or nothing). */
    private fun syncPreview() {
        val host = previewHost ?: return
        val video = previewVideoElement
        val shouldShow = panelOpen && supported
        if (!shouldShow) {
            if (previewAttached && video != null) host.detachPreview(video)
            previewAttached = false
            return
        }
        if (host.cameraOn() && video != null) {
            if (!previewAttached) previewAttached = host.attachPreview(video)
            previewVideo?.show()
            previewCanvas?.hide()
        } else {
            if (previewAttached && video != null) host.detachPreview(video)
            previewAttached = false
            previewVideo?.hide()
            previewCanvas?.show()
            (previewCanvas?.getElement() as? HTMLCanvasElement)?.let { canvas ->
                drawSilhouette(canvas)
                host.drawStaticScene(canvas)
            }
        }
    }

    /** The preview stream was swapped (processor set or removed): attach it again, a `srcObject` swap pauses a `<video>`. */
    fun refreshPreview() {
        val host = previewHost ?: return
        val video = previewVideoElement ?: return
        if (previewAttached) {
            host.detachPreview(video)
            previewAttached = host.attachPreview(video)
        }
        video.play().catch { }
    }

    private fun drawSilhouette(canvas: HTMLCanvasElement) {
        val ctx = canvas.getContext("2d").asDynamic() ?: return
        val w = canvas.width.toDouble()
        val h = canvas.height.toDouble()
        ctx.fillStyle = overlayCssColor("--lapis-surface-sunken", "lightgray")
        ctx.fillRect(0.0, 0.0, w, h)
        ctx.fillStyle = overlayCssColor("--lapis-muted", "gray")
        ctx.beginPath()
        ctx.arc(w / 2, h * 0.42, h * 0.17, 0.0, 2 * kotlin.math.PI)
        ctx.fill()
        ctx.beginPath()
        ctx.ellipse(w / 2, h * 1.02, w * 0.22, h * 0.36, 0.0, 0.0, 2 * kotlin.math.PI)
        ctx.fill()
    }

    /** Leaving the call: the preview stream is detached. */
    fun dispose() {
        val host = previewHost
        val video = previewVideoElement
        if (host != null && video != null && previewAttached) host.detachPreview(video)
        previewAttached = false
    }

    private companion object {
        const val CONTENT_EMPTY = "empty"
        const val CONTENT_NAME = "name"
        const val FRAME_NONE = "none"
    }
}

/** Library item names for the list in tests and the admin screen: plain text, never HTML. */
internal fun videoOverlayItemsOfKind(
    library: List<VideoOverlayItemDto>,
    kind: VideoOverlayKind,
): List<VideoOverlayItemDto> = library.filter { it.kind == kind }
