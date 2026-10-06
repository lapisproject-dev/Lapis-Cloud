package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import network.lapis.cloud.client.maplibre.ResizeObserver
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus

/*
 * V1.9.66 -- the bottom control bar of a conference call: moderation controls (recording, live stream, end for everyone) live in the
 * bar as their own group, the bar never wraps (what does not fit moves into the "Mehr" sheet), chat sending is an icon.
 *
 * This file is the testable core of that bar: pure functions (overflow order, toggle state model), the one icon-only control factory
 * ([conferenceControlButton], named R58 exception b), the moderation group and the overflow handler. It contains NO RPC call and NO
 * late hook (ledgers of ClientDataStateTripwireTest / ClientLateHookRatchetTest must not grow): every attribute, badge and spinner is
 * written through the KVision patch cycle (Widget attributes, child spans, CSS classes).
 */

/** The controls of the bar, in DOM order. */
internal enum class ConferenceControlSlot { MIC, CAMERA, SCREEN, RECORD, STREAM, END_FOR_ALL, ROSTER, CHAT, VOTE, MORE, BACK, LEAVE }

/** Group index of a slot: 0 devices, 1 moderation, 2 panels, 3 exit. A divider separates two non-empty groups. */
internal fun conferenceControlGroup(slot: ConferenceControlSlot): Int =
    when (slot) {
        ConferenceControlSlot.MIC, ConferenceControlSlot.CAMERA, ConferenceControlSlot.SCREEN -> 0
        ConferenceControlSlot.RECORD, ConferenceControlSlot.STREAM, ConferenceControlSlot.END_FOR_ALL -> 1
        ConferenceControlSlot.ROSTER, ConferenceControlSlot.CHAT, ConferenceControlSlot.VOTE, ConferenceControlSlot.MORE -> 2
        ConferenceControlSlot.BACK, ConferenceControlSlot.LEAVE -> 3
    }

/** Overflow order, the first one is moved first. Never moved: MIC, CAMERA, CHAT, MORE, BACK, LEAVE. */
internal val CONFERENCE_OVERFLOW_ORDER: List<ConferenceControlSlot> =
    listOf(
        ConferenceControlSlot.VOTE,
        ConferenceControlSlot.SCREEN,
        ConferenceControlSlot.ROSTER,
        ConferenceControlSlot.STREAM,
        ConferenceControlSlot.RECORD,
        ConferenceControlSlot.END_FOR_ALL,
    )

/** Space a divider takes in the bar (1 px line + 4 px margin on each side, see theme.css). */
internal const val CONFERENCE_DIVIDER_FOOTPRINT_PX = 9.0

/** Fallback width of a control that was never measured (44 px minimum target + border). */
internal const val CONFERENCE_CONTROL_FALLBACK_WIDTH_PX = 46.0

internal data class ConferenceControlMeasure(
    val slot: ConferenceControlSlot,
    val width: Double,
    val group: Int,
)

/**
 * Pure: which slots move into the sheet so that the shown controls (plus the gaps and the dividers between non-empty groups) fit in
 * [available]. Slots move strictly in [CONFERENCE_OVERFLOW_ORDER]; a slot that is not among [visible] is skipped (nothing to move).
 */
internal fun conferenceControlsOverflow(
    available: Double,
    visible: List<ConferenceControlMeasure>,
    gap: Double,
    dividerWidth: Double,
    containerGroups: Set<Int> = emptySet(),
): Set<ConferenceControlSlot> {
    val moved = linkedSetOf<ConferenceControlSlot>()

    fun total(): Double {
        val shown = visible.filter { it.slot !in moved }
        if (shown.isEmpty()) return 0.0
        val shownGroups = shown.map { it.group }.distinct()
        val groups = shownGroups.size
        // A group wrapper that stays rendered although all its controls moved is an empty flex item: it still takes a column gap.
        val emptyContainers = containerGroups.count { it !in shownGroups }
        val items = shown.size + (groups - 1) + emptyContainers
        return shown.sumOf { it.width } + (groups - 1) * dividerWidth + gap * (items - 1)
    }
    for (slot in CONFERENCE_OVERFLOW_ORDER) {
        if (total() <= available) break
        if (visible.any { it.slot == slot }) moved += slot
    }
    return moved
}

/** Pure invariant: "Für alle beenden" and "Verlassen" are never neighbours among the shown controls (slip protection, design D6). */
internal fun endForAllAdjacentToLeave(shownInOrder: List<ConferenceControlSlot>): Boolean {
    val end = shownInOrder.indexOf(ConferenceControlSlot.END_FOR_ALL)
    val leave = shownInOrder.indexOf(ConferenceControlSlot.LEAVE)
    return end >= 0 && leave >= 0 && kotlin.math.abs(end - leave) == 1
}

/** The state of one toggle control (recording, live stream) as the bar shows it. */
internal data class ConferenceToggleView(
    val visible: Boolean,
    val pressed: Boolean,
    val disabled: Boolean,
    val busy: Boolean,
    /** The verb (tooltip); resolve with [resolvedAttributeText] before it goes into a raw attribute. */
    val title: String,
    /** Shown in the status line while [busy]; `null` otherwise. */
    val progressText: String?,
)

internal fun recordingToggleView(
    status: ConferenceRecordingStatus?,
    canStart: Boolean,
): ConferenceToggleView =
    when (status) {
        ConferenceRecordingStatus.RECORDING ->
            ConferenceToggleView(
                true,
                pressed = true,
                disabled = false,
                busy = false,
                title = tr("Aufzeichnung beenden"),
                progressText = null,
            )
        ConferenceRecordingStatus.STOPPING -> {
            val text = tr("Aufzeichnung wird beendet …")
            ConferenceToggleView(true, pressed = true, disabled = true, busy = true, title = text, progressText = text)
        }
        // PROCESSING can be reached through the poll of an in-flight recording: not recording any more (pressed = false), no click possible.
        ConferenceRecordingStatus.PROCESSING -> {
            val text = tr("Aufzeichnung wird zusammengeführt …")
            ConferenceToggleView(true, pressed = false, disabled = true, busy = true, title = text, progressText = text)
        }
        else ->
            ConferenceToggleView(
                true,
                pressed = false,
                disabled = !canStart,
                busy = false,
                title = tr("Aufzeichnung starten"),
                progressText = null,
            )
    }

internal fun streamToggleView(
    status: ConferenceStreamStatus?,
    pauseReason: ConferenceStreamPauseReason?,
    canStart: Boolean,
): ConferenceToggleView =
    when (status) {
        null, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED ->
            ConferenceToggleView(
                // A new stream while a secret ballot pauses the room is refused by the server; the control never invites that click.
                visible = pauseReason != ConferenceStreamPauseReason.SECRET_BALLOT,
                pressed = false,
                disabled = !canStart,
                busy = false,
                title = tr("Live-Stream starten …"),
                progressText = null,
            )
        ConferenceStreamStatus.STARTING,
        ConferenceStreamStatus.LIVE,
        ConferenceStreamStatus.PAUSING,
        ConferenceStreamStatus.PAUSED,
        ->
            ConferenceToggleView(
                true,
                pressed = true,
                disabled = false,
                busy = false,
                title = tr("Live-Stream beenden"),
                progressText = null,
            )
        ConferenceStreamStatus.STOPPING -> {
            val text = tr("Stream wird beendet …")
            ConferenceToggleView(true, pressed = true, disabled = true, busy = true, title = text, progressText = text)
        }
    }

/** What a click on the stream toggle does. Starting never happens without the destination dialog, stopping never without a confirmation. */
internal enum class StreamToggleAction { OPEN_START_DIALOG, CONFIRM_STOP, NONE }

internal fun conferenceStreamToggleAction(status: ConferenceStreamStatus?): StreamToggleAction =
    when (status) {
        null, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED -> StreamToggleAction.OPEN_START_DIALOG
        ConferenceStreamStatus.STARTING,
        ConferenceStreamStatus.LIVE,
        ConferenceStreamStatus.PAUSING,
        ConferenceStreamStatus.PAUSED,
        -> StreamToggleAction.CONFIRM_STOP
        ConferenceStreamStatus.STOPPING -> StreamToggleAction.NONE
    }

/** The accessible name of "Mehr": names a recording / stream that runs while its control sits inside the sheet. */
internal fun conferenceMoreButtonLabel(
    recordingActiveInSheet: Boolean,
    streamActiveInSheet: Boolean,
): String =
    when {
        recordingActiveInSheet && streamActiveInSheet -> tr("Mehr, Aufzeichnung und Live-Stream laufen")
        recordingActiveInSheet -> tr("Mehr, Aufzeichnung läuft")
        streamActiveInSheet -> tr("Mehr, Live-Stream läuft")
        else -> tr("Mehr")
    }

/** The state signs on "Mehr": ● recording, ◆ live stream (a sign next to the colour, never colour alone). */
internal fun conferenceMoreBadgeGlyphs(
    recordingActiveInSheet: Boolean,
    streamActiveInSheet: Boolean,
): String = (if (recordingActiveInSheet) "●" else "") + (if (streamActiveInSheet) "◆" else "")

/** `Widget.setAttribute` re-renders on every call; only a real change is written. */
private fun Widget.setAttrIfChanged(
    name: String,
    value: String,
) {
    if (getAttribute(name) != value) setAttribute(name, value)
}

/**
 * V1.9.66 (R58 named exception b): the ONLY icon-only factory of the conference bar. At least 44 x 44 px through theme.css.
 * [label] becomes `title` and `aria-label` and `data-label` (the latter stays invisible: the bar is icon-only in every width).
 */
internal fun Container.conferenceControlButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
): Button {
    val button = newIconOnlyActionButton(kind, label, style)
    button.setAttribute("data-label", resolvedAttributeText(label))
    add(button)
    return button
}

/** The group of the moderator's controls in the bar: record, live stream, end for everyone. */
internal class ConferenceModerationGroup internal constructor(
    val root: Div,
    val recordButton: Button,
    val streamButton: Button,
    val endButton: Button,
    private val recordBadge: Span,
    private val streamBadge: Span,
    private val recordSpinner: Span,
    private val streamSpinner: Span,
) {
    /** Called after every state change, so the overflow handler can mirror it to the sheet twins. */
    var onStateChanged: () -> Unit = {}

    private var recordingAvailable = false
    private var streamingAvailable = false
    private var recordingView: ConferenceToggleView? = null
    private var streamView: ConferenceToggleView? = null
    private var recordingBusy = false
    private var streamBusy = false
    private var lastRecording: RenderedToggle? = null
    private var lastStream: RenderedToggle? = null

    /** Status-line text of a recording / stream that is changing state (moderator only). */
    val progressTexts: List<String>
        get() = listOfNotNull(recordingView?.progressText, streamView?.progressText)

    val recordingActive: Boolean get() = recordingView?.pressed == true
    val streamActive: Boolean get() = streamView?.pressed == true

    private data class RenderedToggle(
        val shown: Boolean,
        val view: ConferenceToggleView?,
        val busy: Boolean,
    )

    fun applyRecording(view: ConferenceToggleView) {
        recordingView = view
        renderRecording()
    }

    fun applyStream(view: ConferenceToggleView) {
        streamView = view
        renderStream()
    }

    /** D11: hidden (not disabled) while recording is not configured. */
    fun setRecordingAvailable(available: Boolean) {
        recordingAvailable = available
        renderRecording()
    }

    fun setStreamingAvailable(available: Boolean) {
        streamingAvailable = available
        renderStream()
    }

    /** A click handler's own "request running" state: disabled + aria-busy until the RPC has answered. */
    fun setRecordingBusy(busy: Boolean) {
        recordingBusy = busy
        renderRecording()
    }

    fun setStreamBusy(busy: Boolean) {
        streamBusy = busy
        renderStream()
    }

    /** All three controls hidden: the divider before the group is suppressed. */
    fun isEmpty(): Boolean = !recordButton.visible && !streamButton.visible && !endButton.visible

    private fun renderRecording() {
        val view = recordingView
        val shown = recordingAvailable && (view?.visible ?: true)
        val rendered = RenderedToggle(shown, view, recordingBusy)
        if (rendered == lastRecording) return
        lastRecording = rendered
        renderToggle(recordButton, recordBadge, recordSpinner, shown, view, recordingBusy)
        onStateChanged()
    }

    private fun renderStream() {
        val view = streamView
        val shown = streamingAvailable && (view?.visible ?: true)
        val rendered = RenderedToggle(shown, view, streamBusy)
        if (rendered == lastStream) return
        lastStream = rendered
        renderToggle(streamButton, streamBadge, streamSpinner, shown, view, streamBusy)
        onStateChanged()
    }

    private fun renderToggle(
        button: Button,
        badge: Span,
        spinner: Span,
        shown: Boolean,
        view: ConferenceToggleView?,
        localBusy: Boolean,
    ) {
        if (button.visible != shown) {
            if (shown) {
                button.show()
            } else {
                button.hide()
            }
        }
        val pressed = view?.pressed == true
        val busy = (view?.busy == true) || localBusy
        if (view != null) {
            val title = view.title
            if (button.title != title) button.title = title
            button.setAttrIfChanged("aria-pressed", pressed.toString())
        }
        button.disabled = (view?.disabled == true) || localBusy
        if (busy) {
            button.setAttrIfChanged("aria-busy", "true")
        } else if (button.getAttribute("aria-busy") !=
            null
        ) {
            button.removeAttribute("aria-busy")
        }
        if (pressed) button.addCssClass("active") else button.removeCssClass("active")
        if (pressed) badge.addCssClass(BADGE_ON) else badge.removeCssClass(BADGE_ON)
        if (spinner.visible != busy) {
            if (busy) {
                spinner.show()
            } else {
                spinner.hide()
            }
        }
    }

    companion object {
        const val BADGE_ON = "lapis-conference-control-badge-on"
    }
}

/**
 * Builds the moderation group inside the bar. Returns `null` when [canModerate] is false (plain participant, every breakout room):
 * nothing is rendered, no empty group, no divider.
 *
 * The three controls carry a FIXED noun as accessible name ("Aufzeichnung", "Live-Stream", "Für alle beenden") and show their state
 * through `aria-pressed`; the verb that a click would run is the tooltip. Recording and live stream start hidden until availability
 * is known ([ConferenceModerationGroup.setRecordingAvailable]).
 */
internal fun Container.conferenceModerationGroup(
    canModerate: Boolean,
    onRecord: () -> Unit,
    onStream: () -> Unit,
    onEndForAll: () -> Unit,
): ConferenceModerationGroup? {
    if (!canModerate) return null
    val root = div(className = "lapis-conference-controls-group")
    root.setAttribute("role", "group")
    root.setAttribute("aria-label", gettext("Moderation"))
    val record = root.conferenceControlButton(ActionIcon.RECORD, tr("Aufzeichnung"))
    val recordBadge = record.span("●", className = "lapis-conference-control-badge")
    val recordSpinner = record.span(className = "lapis-conference-control-spinner fas fa-spinner fa-spin")
    val stream = root.conferenceControlButton(ActionIcon.BROADCAST, tr("Live-Stream"))
    val streamBadge = stream.span("◆", className = "lapis-conference-control-badge")
    val streamSpinner = stream.span(className = "lapis-conference-control-spinner fas fa-spinner fa-spin")
    val end = root.conferenceControlButton(ActionIcon.END_FOR_ALL, tr("Für alle beenden"), ButtonStyle.OUTLINEDANGER)
    for (spinner in listOf(recordSpinner, streamSpinner)) {
        spinner.setAttribute("aria-hidden", "true")
        spinner.hide()
    }
    for (badge in listOf(recordBadge, streamBadge)) badge.setAttribute("aria-hidden", "true")
    record.hide()
    stream.hide()
    record.onClick { onRecord() }
    stream.onClick { onStream() }
    end.onClick { onEndForAll() }
    return ConferenceModerationGroup(root, record, stream, end, recordBadge, streamBadge, recordSpinner, streamSpinner)
}

/** A thin vertical line between two groups of the bar; decorative. */
internal fun Container.conferenceControlsDivider(): Span {
    val divider = span(className = "lapis-conference-controls-divider")
    divider.setAttribute("aria-hidden", "true")
    return divider
}

/** One control of the bar that [ConferenceControlsOverflow] may move into the sheet. [twin] is its labelled counterpart in the sheet. */
internal data class OverflowSlot(
    val slot: ConferenceControlSlot,
    val primary: Button,
    val twin: Button?,
    /** Mirror `aria-pressed` of the primary onto the twin (panel toggles); recording / stream carry the state in the verb instead. */
    val mirrorPressed: Boolean = false,
)

/**
 * Measures the bar (ResizeObserver, started lazily WITHOUT a hook through [ensureObserving]), moves slots that do not fit into the
 * sheet and mirrors the state of a moved control to its twin. A moved primary is hidden by a CSS class (never `hide()`: its owner keeps
 * control over its own visibility); a twin is shown only while its primary is moved AND wanted.
 *
 * Only a change of the moved set is written; widths of the controls are cached (a hidden control cannot be measured), the fallback
 * is [CONFERENCE_CONTROL_FALLBACK_WIDTH_PX]. That keeps the bar from flickering.
 */
internal class ConferenceControlsOverflow(
    private val bar: Widget,
    private val slots: List<OverflowSlot>,
    /** A divider and the group it sits in front of. */
    private val dividers: List<Pair<Span, Int>>,
    private val onChanged: (moved: Set<ConferenceControlSlot>) -> Unit,
    /** Groups whose controls sit in a wrapper element that stays rendered (and takes a column gap) even when empty. */
    private val containerGroups: Set<Int> = emptySet(),
) {
    private var observer: ResizeObserver? = null
    private val widths = mutableMapOf<ConferenceControlSlot, Double>()
    private var moved: Set<ConferenceControlSlot> = emptySet()
    private val twinState = mutableMapOf<ConferenceControlSlot, Triple<Boolean, Boolean, Boolean>>()
    private val dividerState = mutableMapOf<Span, Boolean>()
    private var disposed = false

    fun moved(): Set<ConferenceControlSlot> = moved

    /** Idempotent. Needs the element of the bar, so it simply tries again on the next call while it does not exist. */
    fun ensureObserving() {
        if (disposed || observer != null) return
        val element = bar.getElement() ?: return
        observer = ResizeObserver { _, _ -> recompute() }.also { it.observe(element) }
        recompute()
    }

    fun dispose() {
        disposed = true
        observer?.disconnect()
        observer = null
    }

    fun recompute() {
        if (disposed) return
        val element = bar.getElement() ?: return
        if (element.clientWidth <= 0) return
        for (s in slots) {
            val el = s.primary.getElement()
            if (el != null && el.offsetWidth > 0) {
                // The bounding box excludes CSS margins (e.g. `ms-2` on the leave / back buttons), the row still has to hold them.
                val cs = window.getComputedStyle(el)
                widths[s.slot] = el.getBoundingClientRect().width + parsePx(cs.marginLeft) + parsePx(cs.marginRight)
            }
        }
        val style = window.getComputedStyle(element)
        val padding = parsePx(style.paddingLeft) + parsePx(style.paddingRight)
        val gap = style.columnGap.removeSuffix("px").toDoubleOrNull() ?: 6.0
        val measures =
            slots.filter { it.primary.visible }.map {
                ConferenceControlMeasure(it.slot, widths[it.slot] ?: CONFERENCE_CONTROL_FALLBACK_WIDTH_PX, conferenceControlGroup(it.slot))
            }
        val newMoved =
            conferenceControlsOverflow(
                element.clientWidth - padding,
                measures,
                gap,
                CONFERENCE_DIVIDER_FOOTPRINT_PX,
                containerGroups,
            )
        apply(newMoved)
    }

    private fun apply(newMoved: Set<ConferenceControlSlot>) {
        val shownGroups = mutableSetOf<Int>()
        for (s in slots) {
            val isMoved = s.slot in newMoved
            val wanted = s.primary.visible
            if (wanted && !isMoved) shownGroups += conferenceControlGroup(s.slot)
            if (isMoved != (s.slot in moved)) {
                if (isMoved) s.primary.addCssClass(OVERFLOWED) else s.primary.removeCssClass(OVERFLOWED)
            }
            val twin = s.twin ?: continue
            val twinShown = isMoved && wanted
            val state = Triple(twinShown, s.primary.disabled, s.primary.getAttribute("aria-pressed") == "true")
            if (twinState[s.slot] == state) continue
            twinState[s.slot] = state
            if (twin.visible != twinShown) {
                if (twinShown) {
                    twin.show()
                } else {
                    twin.hide()
                }
            }
            twin.disabled = state.second
            if (s.mirrorPressed) twin.setAttrIfChanged("aria-pressed", state.third.toString())
        }
        for ((divider, beforeGroup) in dividers) {
            val show = beforeGroup in shownGroups && shownGroups.any { it < beforeGroup }
            if (dividerState[divider] == show) continue
            dividerState[divider] = show
            if (divider.visible != show) {
                if (show) {
                    divider.show()
                } else {
                    divider.hide()
                }
            }
        }
        if (newMoved != moved) {
            moved = newMoved
            onChanged(newMoved)
        }
    }

    private fun parsePx(value: String): Double = value.removeSuffix("px").toDoubleOrNull() ?: 0.0

    companion object {
        const val OVERFLOWED = "lapis-conference-control-overflowed"
    }
}
