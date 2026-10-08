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
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus

/*
 * V1.9.66 -- the bottom control bar of a conference call: moderation controls (recording, live stream) live in the
 * bar as their own group (V1.9.72: "Für alle beenden" moved out of it, into the exit group next to "Verlassen"), the bar never wraps (what does not fit moves into the "Mehr" sheet), chat sending is an icon.
 *
 * This file is the testable core of that bar: pure functions (overflow order, toggle state model), the one icon-only control factory
 * ([conferenceControlButton], named R58 exception b), the moderation group and the overflow handler. It contains NO RPC call and NO
 * late hook (ledgers of ClientDataStateTripwireTest / ClientLateHookRatchetTest must not grow): every attribute, badge and spinner is
 * written through the KVision patch cycle (Widget attributes, child spans, CSS classes).
 */

/** The controls of the bar, in DOM order. */
internal enum class ConferenceControlSlot { MIC, CAMERA, SCREEN, RECORD, STREAM, ROSTER, CHAT, VOTE, MORE, BACK, END_FOR_ALL, LEAVE }

/** Group index of a slot: 0 devices, 1 moderation, 2 panels, 3 exit. A divider separates two non-empty groups. */
internal fun conferenceControlGroup(slot: ConferenceControlSlot): Int =
    when (slot) {
        ConferenceControlSlot.MIC, ConferenceControlSlot.CAMERA, ConferenceControlSlot.SCREEN -> 0
        ConferenceControlSlot.RECORD, ConferenceControlSlot.STREAM -> 1
        ConferenceControlSlot.ROSTER, ConferenceControlSlot.CHAT, ConferenceControlSlot.VOTE, ConferenceControlSlot.MORE -> 2
        ConferenceControlSlot.BACK, ConferenceControlSlot.END_FOR_ALL, ConferenceControlSlot.LEAVE -> 3
    }

/** The exit group: BACK (breakout only), END_FOR_ALL (moderator only), LEAVE. */
internal const val CONFERENCE_EXIT_GROUP = 3

/** Overflow order, the first one is moved first (END_FOR_ALL last, V1.9.72). Never moved: MIC, CAMERA, CHAT, MORE, BACK, LEAVE. */
internal val CONFERENCE_OVERFLOW_ORDER: List<ConferenceControlSlot> =
    listOf(
        ConferenceControlSlot.VOTE,
        ConferenceControlSlot.SCREEN,
        ConferenceControlSlot.ROSTER,
        ConferenceControlSlot.STREAM,
        ConferenceControlSlot.RECORD,
        ConferenceControlSlot.END_FOR_ALL,
    )

/** V1.9.72: gap between the controls of the exit group (BACK, END_FOR_ALL, LEAVE); theme.css `--exit` uses the same 12 px. */
internal const val CONFERENCE_EXIT_GAP_PX = 12.0

/** Space a divider takes in the bar (1 px line + 4 px margin on each side, see theme.css). */
internal const val CONFERENCE_DIVIDER_FOOTPRINT_PX = CONTROL_BAR_DIVIDER_FOOTPRINT_PX

/** Fallback width of a control that was never measured (44 px minimum target + border). */
internal const val CONFERENCE_CONTROL_FALLBACK_WIDTH_PX = CONTROL_BAR_FALLBACK_WIDTH_PX

/** V1.9.74: the measure type is the generic one of [ControlBarOverflow]; the constructor with positional arguments is unchanged. */
internal typealias ConferenceControlMeasure = ControlMeasure<ConferenceControlSlot>

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
    exitGap: Double = CONFERENCE_EXIT_GAP_PX,
): Set<ConferenceControlSlot> =
    controlBarOverflow(
        available = available,
        visible = visible,
        order = CONFERENCE_OVERFLOW_ORDER,
        gap = gap,
        dividerWidth = dividerWidth,
        exitGroup = CONFERENCE_EXIT_GROUP,
        containerGroups = containerGroups,
        exitGap = exitGap,
    )

/** Pure invariant (V1.9.72): "Verlassen" is the last shown control of the bar. */
internal fun leaveIsLast(shownInOrder: List<ConferenceControlSlot>): Boolean = shownInOrder.lastOrNull() == ConferenceControlSlot.LEAVE

/**
 * Pure invariant (V1.9.72, replaces `endForAllAdjacentToLeave` of V1.9.66 on explicit request): "Verlassen" is the last shown control
 * and "Für alle beenden", when shown, is the control directly before it. The confirmation dialog, not the distance, is the slip protection.
 */
internal fun endForAllImmediatelyBeforeLeave(shownInOrder: List<ConferenceControlSlot>): Boolean {
    if (!leaveIsLast(shownInOrder)) return false
    val end = shownInOrder.indexOf(ConferenceControlSlot.END_FOR_ALL)
    return end < 0 || end == shownInOrder.size - 2
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

/** The group of the moderator's controls in the bar: record, live stream ("Für alle beenden" sits in the exit group since V1.9.72). */
internal class ConferenceModerationGroup internal constructor(
    val root: Div,
    val recordButton: Button,
    val streamButton: Button,
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

    /** Both controls hidden: the divider before the group is suppressed. */
    fun isEmpty(): Boolean = !recordButton.visible && !streamButton.visible

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
 * The two controls carry a FIXED noun as accessible name ("Aufzeichnung", "Live-Stream") and show their state
 * through `aria-pressed`; the verb that a click would run is the tooltip. Recording and live stream start hidden until availability
 * is known ([ConferenceModerationGroup.setRecordingAvailable]).
 */
internal fun Container.conferenceModerationGroup(
    canModerate: Boolean,
    onRecord: () -> Unit,
    onStream: () -> Unit,
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
    for (spinner in listOf(recordSpinner, streamSpinner)) {
        spinner.setAttribute("aria-hidden", "true")
        spinner.hide()
    }
    for (badge in listOf(recordBadge, streamBadge)) badge.setAttribute("aria-hidden", "true")
    record.hide()
    stream.hide()
    record.onClick { onRecord() }
    stream.onClick { onStream() }
    return ConferenceModerationGroup(root, record, stream, recordBadge, streamBadge, recordSpinner, streamSpinner)
}

/** The exit group of the bar (V1.9.72): back to the main room (breakout only), end for everyone (moderator, main room only), leave. */
internal class ConferenceExitGroup internal constructor(
    val root: Div,
    val backButton: Button?,
    val endButton: Button?,
    val leaveButton: Button,
)

/**
 * V1.9.72 -- builds the exit group: [Zurück zum Hauptraum] (only [isBreakout]), [Für alle beenden] (only [canEndForAll] and never in a
 * breakout), [Verlassen] -- "Verlassen" is always the last control. 12 px between the controls (theme.css `--exit`), no divider.
 *
 * "Für alle beenden" only calls [onEndForAll]; the caller routes that to the confirmation dialog (`endRoomConfirmDialog`), there is no other
 * path to `endRoom`. The server still decides who may end a room; this only hides the control. [onLeave] / [onBack] are optional: the
 * call screen attaches its own handlers once its session exists.
 */
internal fun Container.conferenceExitGroup(
    canEndForAll: Boolean,
    isBreakout: Boolean,
    onEndForAll: () -> Unit,
    onLeave: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
): ConferenceExitGroup {
    val root = div(className = "lapis-conference-controls-group lapis-conference-controls-group--exit")
    root.setAttribute("role", "group")
    root.setAttribute("aria-label", gettext("Besprechung"))
    // Inside a breakout "Zurück zum Hauptraum" is the frequent, low-stakes action (PRIMARY), "ganz verlassen" the heavier one.
    val back =
        if (isBreakout) {
            root.conferenceControlButton(ActionIcon.BACK, tr("Zurück zum Hauptraum"), ButtonStyle.PRIMARY).also { button ->
                onBack?.let { button.onClick { it() } }
            }
        } else {
            null
        }
    val end =
        if (canEndForAll && !isBreakout) {
            root.conferenceControlButton(ActionIcon.END_FOR_ALL, tr("Für alle beenden"), ButtonStyle.OUTLINEDANGER).also { button ->
                button.onClick { onEndForAll() }
            }
        } else {
            null
        }
    val leave =
        root.conferenceControlButton(
            ActionIcon.HANG_UP,
            if (isBreakout) tr("Besprechung ganz verlassen") else tr("Verlassen"),
            ButtonStyle.DANGER,
        )
    onLeave?.let { leave.onClick { it() } }
    return ConferenceExitGroup(root, back, end, leave)
}

/**
 * V1.9.72 -- the labelled twin of "Für alle beenden" in the "Mehr" sheet (the control moves there LAST when the bar is too narrow). It
 * stands last in the sheet, set apart by a rule and in the danger colour (theme.css `.lapis-conference-twin-end`). The caller wires the
 * click to the primary control, so there is exactly one path to the confirmation dialog.
 */
internal fun Container.conferenceEndForAllTwin(): Button =
    actionButton(ActionIcon.END_FOR_ALL, tr("Für alle beenden"), ButtonStyle.OUTLINEDANGER) {
        addCssClasses("lapis-conference-twin-end text-danger")
    }

/** The four controls of the dock bar (V1.9.70), built once; the bar only toggles their state. */
internal class DockBarControls internal constructor(
    val root: Div,
    val mic: Button,
    val camera: Button,
    val stopShare: Button,
    val leave: Button,
) {
    private var shownMic: Boolean? = null
    private var shownCamera: Boolean? = null

    /** Mic and camera show their state through `aria-pressed` ("on" = pressed) and the slash icon; the verb is the tooltip. */
    fun applyDevices(
        micOn: Boolean,
        cameraOn: Boolean,
    ) {
        if (shownMic != micOn) {
            shownMic = micOn
            mic.icon = if (micOn) "fas fa-microphone" else "fas fa-microphone-slash"
            mic.toggleClass("text-danger", !micOn)
            mic.setAttrIfChanged("aria-pressed", micOn.toString())
            val label = resolvedAttributeText(if (micOn) tr("Mikrofon ausschalten") else tr("Mikrofon einschalten"))
            mic.setAttrIfChanged("aria-label", label)
            mic.setAttrIfChanged("title", label)
        }
        if (shownCamera != cameraOn) {
            shownCamera = cameraOn
            camera.icon = if (cameraOn) "fas fa-video" else "fas fa-video-slash"
            camera.toggleClass("text-danger", !cameraOn)
            camera.setAttrIfChanged("aria-pressed", cameraOn.toString())
            val label = resolvedAttributeText(if (cameraOn) tr("Kamera ausschalten") else tr("Kamera einschalten"))
            camera.setAttrIfChanged("aria-label", label)
            camera.setAttrIfChanged("title", label)
        }
    }

    private fun Widget.toggleClass(
        name: String,
        on: Boolean,
    ) {
        if (on) addCssClass(name) else removeCssClass(name)
    }
}

/**
 * V1.9.70: the controls of the conference dock bar -- microphone, camera, "stop sharing the screen" (only while sharing) and "leave".
 * They call the SAME functions as the buttons of the full view (see `DockableSession`), no second implementation. Four
 * [conferenceControlButton] calls: R58 ledger entry in `ClientToolbarIconTripwireTest` (named exception b, extended).
 */
internal fun Container.conferenceDockBarControls(
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onStopShare: () -> Unit,
    onLeave: () -> Unit,
): DockBarControls {
    val root = div(className = "lapis-conference-controls-group lapis-dock-controls")
    root.setAttribute("role", "group")
    root.setAttribute("aria-label", gettext("Besprechung"))
    val mic = root.conferenceControlButton(ActionIcon.MICROPHONE, tr("Mikrofon ausschalten"))
    val camera = root.conferenceControlButton(ActionIcon.CAMERA, tr("Kamera ausschalten"))
    val stopShare = root.conferenceControlButton(ActionIcon.SCREEN_SHARE, tr("Bildschirmfreigabe beenden"))
    val leave = root.conferenceControlButton(ActionIcon.HANG_UP, tr("Verlassen"), ButtonStyle.DANGER)
    stopShare.hide()
    mic.setAttribute("aria-pressed", "true")
    camera.setAttribute("aria-pressed", "true")
    mic.onClick { onMic() }
    camera.onClick { onCamera() }
    stopShare.onClick { onStopShare() }
    leave.onClick { onLeave() }
    return DockBarControls(root, mic, camera, stopShare, leave)
}

/** A thin vertical line between two groups of the bar; decorative. */
internal fun Container.conferenceControlsDivider(): Span {
    val divider = span(className = "lapis-conference-controls-divider")
    divider.setAttribute("aria-hidden", "true")
    return divider
}

/** One control of the bar that [ConferenceControlsOverflow] may move into the sheet. [twin] is its labelled counterpart in the sheet. */
internal typealias OverflowSlot = ControlBarSlot<ConferenceControlSlot>

/**
 * The overflow handler of the conference bar: a thin wrapper around the generic [ControlBarOverflow] (V1.9.74) that fixes the slot type,
 * the group function, the overflow order and the CSS class. Behaviour is unchanged since V1.9.72.
 */
internal class ConferenceControlsOverflow(
    bar: Widget,
    slots: List<OverflowSlot>,
    /** A divider and the group it sits in front of. */
    dividers: List<Pair<Span, Int>>,
    onChanged: (moved: Set<ConferenceControlSlot>) -> Unit,
    /** Groups whose controls sit in a wrapper element that stays rendered (and takes a column gap) even when empty. */
    containerGroups: Set<Int> = emptySet(),
) {
    private val delegate =
        ControlBarOverflow(
            bar = bar,
            slots = slots,
            dividers = dividers,
            groupOf = ::conferenceControlGroup,
            order = CONFERENCE_OVERFLOW_ORDER,
            exitGroup = CONFERENCE_EXIT_GROUP,
            overflowedClass = OVERFLOWED,
            onChanged = onChanged,
            containerGroups = containerGroups,
        )

    fun moved(): Set<ConferenceControlSlot> = delegate.moved()

    /** Idempotent. Needs the element of the bar, so it simply tries again on the next call while it does not exist. */
    fun ensureObserving() = delegate.ensureObserving()

    fun dispose() = delegate.dispose()

    fun recompute() = delegate.recompute()

    companion object {
        const val OVERFLOWED = "lapis-conference-control-overflowed"
    }
}
