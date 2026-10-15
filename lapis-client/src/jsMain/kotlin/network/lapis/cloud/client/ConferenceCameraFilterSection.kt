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
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLVideoElement
import kotlin.math.max
import kotlin.math.min

/*
 * V1.9.142 "Kamera-Filter" -- the panel in the "Mehr" sheet: ONE collapsed row ("Kamera-Filter: Aus|An", the state stands in its text), below
 * it the preview, the honest notice, the main switch, the group "Licht und Farbe" (four sliders), the group "Hautglättung" (one slider, with
 * its own notice), the pause line with "Erneut versuchen", and "Alle Filter aus".
 *
 * - The whole section does not exist (no node in the DOM) while [offered] is `false` (guests without a member session, joins that do not
 *   offer it). In a browser that cannot run the stage the row stays, disabled, with an explanation (Chromium-based browsers only).
 * - The sliders are real `<input type=range>` with a `<label for>`, an `<output>` and an `aria-valuetext`. The keys (arrows +-1, Page up/down
 *   +-10, Home/End to the ends) are handled here ([cameraFilterSliderKeyTarget]) and the native behaviour is suppressed, so the steps are the
 *   same in every browser and testable.
 * - The preview is the person's OWN processed camera stream (`track.attach()` on a `<video>`, i.e. exactly what others see) or, with the
 *   camera off, a neutral silhouette. Nothing is ever an `<img>`.
 * - `tr(...)` only as widget content, `gettext(...)` for attributes (`ClientTrAttributeLeakTest`); no `addAfterInsertHook` except for the
 *   `<video>` element; every other attribute goes through the patch cycle.
 */

private const val FILTER_PANEL_ID = "lapis-conference-camera-filter-panel"

/** The slider ids (`<input id>`), also the DOM contract of the tests. */
internal object CameraFilterSliderIds {
    const val BRIGHTNESS = "lapis-camera-filter-brightness"
    const val CONTRAST = "lapis-camera-filter-contrast"
    const val WARMTH = "lapis-camera-filter-warmth"
    const val BACKLIGHT = "lapis-camera-filter-backlight"
    const val SMOOTHING = "lapis-camera-filter-smoothing"
}

/** The value a key press moves a slider to, or `null` for any other key. Page up / down move by 10, the arrows by 1, Home / End to the ends. */
internal fun cameraFilterSliderKeyTarget(
    current: Int,
    min: Int,
    max: Int,
    key: String,
): Int? =
    when (key) {
        "ArrowRight", "ArrowUp" -> current + 1
        "ArrowLeft", "ArrowDown" -> current - 1
        "PageUp" -> current + 10
        "PageDown" -> current - 10
        "Home" -> min
        "End" -> max
        else -> null
    }?.let { max(min, min(max, it)) }

/** `+20 %`, `0 %`, `-10 %` for signed sliders; `50 %` for the ones that start at 0. Also the `aria-valuetext`. */
internal fun cameraFilterValueText(
    value: Int,
    signed: Boolean,
): String = if (signed && value > 0) "+$value %" else "$value %"

private class FilterSlider(
    parent: Container,
    val id: String,
    labelText: String,
    private val min: Int,
    private val max: Int,
    private val signed: Boolean,
    private val onValue: (Int) -> Unit,
) {
    val root: Div = parent.div(className = "lapis-conference-filter-slider d-flex align-items-center gap-2")
    private val range: Tag
    private val output: Tag

    init {
        val label = root.tag(TAG.LABEL, content = labelText, className = "small flex-shrink-0 lapis-conference-filter-label")
        label.setAttribute("for", id)
        range = root.tag(TAG.INPUT, className = "form-range flex-grow-1")
        range.setAttribute("id", id)
        range.setAttribute("type", "range")
        range.setAttribute("min", min.toString())
        range.setAttribute("max", max.toString())
        range.setAttribute("step", "1")
        range.setAttribute("value", "0")
        range.setAttribute("aria-valuetext", cameraFilterValueText(0, signed))
        output =
            root.tag(TAG.OUTPUT, content = cameraFilterValueText(0, signed), className = "small text-muted lapis-conference-filter-output")
        output.setAttribute("for", id)
        range.onEvent {
            input = { _ -> readInput()?.let { onValue(it) } }
            keydown = { e ->
                val current = readInput() ?: 0
                cameraFilterSliderKeyTarget(current, min, max, e.key)?.let { target ->
                    e.preventDefault()
                    onValue(target)
                }
            }
        }
    }

    private fun readInput(): Int? = (range.getElement() as? HTMLInputElement)?.value?.toIntOrNull()

    /** Sets the shown value (the thumb, the number and the spoken text) without announcing anything. */
    fun show(value: Int) {
        val shown = max(min, min(max, value))
        val text = cameraFilterValueText(shown, signed)
        range.setAttribute("value", shown.toString())
        range.setAttribute("aria-valuetext", text)
        (range.getElement() as? HTMLInputElement)?.let { element ->
            if (element.value != shown.toString()) element.value = shown.toString()
        }
        output.content = text
    }

    fun setDisabled(disabled: Boolean) {
        if (disabled) range.setAttribute("disabled", "disabled") else range.removeAttribute("disabled")
    }
}

internal class ConferenceCameraFilterSection(
    parent: Container,
    /** `false` => NOTHING is built (the panel is not in the DOM). */
    val offered: Boolean,
    val availability: ConferenceBackgroundAvailability,
    /** `false` if this browser cannot run the frame stage although the processors are supported (no WebGL2, ...). */
    private val stageAvailable: Boolean,
    private val onSlider: (change: (CameraFilterSettings) -> CameraFilterSettings) -> Unit,
    private val onToggle: (Boolean) -> Unit,
    private val onResetAll: () -> Unit,
    private val onRetrySmoothing: () -> Unit,
    private val previewHost: OverlayPreviewHost? = null,
) {
    val supported: Boolean get() = offered && availability == ConferenceBackgroundAvailability.AVAILABLE && stageAvailable
    val present: Boolean get() = offered

    internal var toggleButton: Button? = null
        private set
    private var group: SimplePanel? = null
    var panelOpen: Boolean = false
        private set

    private var lastUi: CameraFilterUiState? = null
    private var hintLine: Div? = null
    private var smoothingNote: Div? = null
    private var switchButton: Tag? = null
    private var pauseLine: Div? = null
    private var pauseText: Tag? = null
    private var resetButton: Tag? = null
    private var liveRegion: Div? = null
    private var previewVideo: Tag? = null
    private var previewCanvas: Tag? = null
    private val sliders = LinkedHashMap<String, FilterSlider>()
    private val previewVideoElement: HTMLVideoElement?
        get() = (previewVideo?.getElement() as? HTMLVideoElement)?.also { it.muted = true }
    private var previewAttached = false

    init {
        if (offered) {
            val rowButton = parent.button(tr("Kamera-Filter: Aus"), icon = "fas fa-sliders", style = ButtonStyle.OUTLINESECONDARY)
            rowButton.addCssClasses("w-100 text-start")
            toggleButton = rowButton
            val panel = parent.vPanel(spacing = 6) { addCssClasses("lapis-conference-camera-filter-group") }
            panel.setAttribute("id", FILTER_PANEL_ID)
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
            tr("Kamera-Filter werden in der App noch nicht unterstützt – im Browser dieses Geräts stehen sie zur Verfügung.")
        } else {
            tr("Kamera-Filter sind derzeit nur in Chromium-basierten Browsern (Chrome, Edge, Brave) verfügbar.")
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
        button.setAttribute("aria-controls", FILTER_PANEL_ID)
    }

    private fun buildOpenContent(panel: SimplePanel) {
        buildPreview(panel)
        hintLine =
            panel.div(tr("Verändert Ihr Bild – alle sehen es, auch in Aufnahme und Livestream.")) {
                addCssClasses("small lapis-conference-filter-hint")
                hide()
            }
        val switch = panel.tag(TAG.BUTTON, content = tr("Filter einblenden"), className = "btn btn-outline-secondary w-100 text-start")
        switch.setAttribute("type", "button")
        switch.setAttribute("role", "switch")
        switch.setAttribute("aria-checked", "false")
        switch.onEvent { click = { onToggle(lastUi?.settings?.enabled != true) } }
        switchButton = switch

        val light = panel.tag(TAG.FIELDSET, className = "lapis-conference-filter-fieldset")
        light.tag(TAG.LEGEND, content = tr("Licht und Farbe"), className = "small text-muted mb-1")
        addSlider(
            light,
            CameraFilterSliderIds.BRIGHTNESS,
            tr("Helligkeit"),
            CameraFilterLimits.BRIGHTNESS_MIN,
            CameraFilterLimits.BRIGHTNESS_MAX,
            true,
        ) { v ->
            onSlider { it.copy(brightness = v) }
        }
        addSlider(
            light,
            CameraFilterSliderIds.CONTRAST,
            tr("Kontrast"),
            CameraFilterLimits.CONTRAST_MIN,
            CameraFilterLimits.CONTRAST_MAX,
            true,
        ) { v ->
            onSlider { it.copy(contrast = v) }
        }
        addSlider(
            light,
            CameraFilterSliderIds.WARMTH,
            tr("Farbtemperatur"),
            CameraFilterLimits.WARMTH_MIN,
            CameraFilterLimits.WARMTH_MAX,
            true,
        ) { v ->
            onSlider { it.copy(warmth = v) }
        }
        addSlider(
            light,
            CameraFilterSliderIds.BACKLIGHT,
            tr("Gegenlicht ausgleichen"),
            CameraFilterLimits.BACKLIGHT_MIN,
            CameraFilterLimits.BACKLIGHT_MAX,
            false,
        ) { v ->
            onSlider { it.copy(backlight = v) }
        }

        val skin = panel.tag(TAG.FIELDSET, className = "lapis-conference-filter-fieldset")
        skin.tag(TAG.LEGEND, content = tr("Hautglättung"), className = "small text-muted mb-1")
        addSlider(
            skin,
            CameraFilterSliderIds.SMOOTHING,
            tr("Stärke"),
            CameraFilterLimits.SMOOTHING_MIN,
            CameraFilterLimits.SMOOTHING_MAX,
            false,
        ) { v ->
            onSlider { it.copy(smoothing = v) }
        }
        smoothingNote =
            skin.div(tr("Hautglättung verändert die Darstellung Ihres Gesichts.")) { addCssClasses("small text-muted") }

        pauseLine = panel.div(className = "lapis-conference-filter-pause d-flex align-items-center gap-2")
        pauseText = pauseLine?.tag(TAG.SPAN, content = tr("Pausiert"), className = "small")
        val retry = pauseLine?.tag(TAG.BUTTON, content = tr("Erneut versuchen"), className = "btn btn-outline-secondary btn-sm")
        retry?.setAttribute("type", "button")
        retry?.onEvent { click = { onRetrySmoothing() } }
        pauseLine?.hide()

        val reset = panel.tag(TAG.BUTTON, content = tr("Alle Filter aus"), className = "btn btn-outline-secondary w-100 text-start")
        reset.setAttribute("type", "button")
        reset.onEvent { click = { onResetAll() } }
        resetButton = reset

        val live = panel.div(className = "visually-hidden")
        live.setAttribute("aria-live", "polite")
        live.setAttribute("role", "status")
        liveRegion = live
    }

    @Suppress("LongParameterList")
    private fun addSlider(
        parent: Container,
        id: String,
        label: String,
        min: Int,
        max: Int,
        signed: Boolean,
        onValue: (Int) -> Unit,
    ) {
        sliders[id] = FilterSlider(parent, id, label, min, max, signed, onValue)
    }

    private fun buildPreview(panel: SimplePanel) {
        val box = panel.div(className = "lapis-conference-overlay-preview")
        box.div(tr("So sehen andere Sie")) { addCssClasses("small text-muted") }
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

    /** The one place that sets text, values and visibility from the controller's state; repeated calls are intended. */
    fun render(ui: CameraFilterUiState) {
        if (!offered) return
        lastUi = ui
        val rowButton = toggleButton ?: return
        if (!supported) return
        rowButton.text = if (ui.settings.enabled) tr("Kamera-Filter: An") else tr("Kamera-Filter: Aus")
        switchButton?.setAttribute("aria-checked", ui.settings.enabled.toString())
        if (ui.filtering) hintLine?.show() else hintLine?.hide()
        sliders[CameraFilterSliderIds.BRIGHTNESS]?.show(ui.settings.brightness)
        sliders[CameraFilterSliderIds.CONTRAST]?.show(ui.settings.contrast)
        sliders[CameraFilterSliderIds.WARMTH]?.show(ui.settings.warmth)
        sliders[CameraFilterSliderIds.BACKLIGHT]?.show(ui.settings.backlight)
        sliders[CameraFilterSliderIds.SMOOTHING]?.show(ui.settings.smoothing)
        if (ui.smoothingPause != SmoothingPause.NONE) {
            pauseText?.content =
                if (ui.smoothingPause ==
                    SmoothingPause.PERFORMANCE
                ) {
                    tr("Pausiert (Gerät zu stark belastet)")
                } else {
                    tr("Pausiert (nicht gestartet)")
                }
            pauseLine?.show()
        } else {
            pauseLine?.hide()
        }
        ui.liveMessage?.let { liveRegion?.content = it }
        applyToggleAria()
        syncPreview()
    }

    /** Focus goes to the main switch after "Alle Filter aus" (the button that was pressed stays, but the person lands on the state). */
    fun focusMainSwitch() {
        switchButton?.getElement()?.focus()
    }

    /** Attaches the live stream while the panel is open and the camera is on; otherwise the silhouette. */
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
            (previewCanvas?.getElement() as? HTMLCanvasElement)?.let { drawFilterSilhouette(it) }
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

    private fun drawFilterSilhouette(canvas: HTMLCanvasElement) {
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
}
