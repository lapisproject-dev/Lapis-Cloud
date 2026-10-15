package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.customDurationSeconds
import network.lapis.cloud.client.livekit.formatMmSs
import network.lapis.cloud.client.livekit.timerExtensionAllowed
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * V1.9.133 -- the moderator's control dialog of the conference timer, opened by the pill (or the "Timer" button of the "Mehr" sheet). A
 * KVision [Modal]: Bootstrap supplies the focus trap and Escape; on close the focus goes back to the control that opened it.
 *
 *  - off or expired: five presets (1, 2, 3, 5, 10 minutes, default 5) and "Eigene Zeit" (minutes 0-60, seconds 0-59, 10 s .. 60 min, with an
 *    inline message tied to the fields through `aria-describedby`; "Starten" stays disabled while the input is invalid);
 *  - running or paused: "Pausieren" / "Fortsetzen", "+30 s", "+1 min", "+5 min" (each disabled when the remaining time would pass 60 min;
 *    their accessible names say what they do: "Um 1 Minute verlängern") and "Stoppen".
 *
 * The body is rebuilt only when the MODE changes (a rebuild would drop the focus); the countdown line and the "+" buttons are updated in place.
 */
internal val CONFERENCE_TIMER_PRESET_MINUTES = listOf(1, 2, 3, 5, 10)
internal const val CONFERENCE_TIMER_DEFAULT_PRESET_MINUTES = 5
private const val CUSTOM_KEY = "custom"
private const val SECONDS_PER_MINUTE = 60
private const val FOCUS_DELAY_MS = 0

private var timerDialogSerial = 0

/** What the dialog shows for [display]: the start form (off / expired) or the running controls. */
internal enum class TimerDialogMode { START, RUNNING, PAUSED }

internal fun timerDialogMode(display: TimerDisplay): TimerDialogMode =
    when (display.status) {
        ConferenceTimerStatus.RUNNING -> if (display.remainingMs > 0L) TimerDialogMode.RUNNING else TimerDialogMode.START
        ConferenceTimerStatus.PAUSED -> TimerDialogMode.PAUSED
        ConferenceTimerStatus.EXPIRED, ConferenceTimerStatus.OFF -> TimerDialogMode.START
    }

/** The visible "+30 s" / "+1 min" / "+5 min" and the name of the button. */
private fun extensionLabel(seconds: Int): String =
    when (seconds) {
        30 -> gettext("+30 s")
        60 -> gettext("+1 min")
        else -> gettext("+5 min")
    }

private fun extensionName(seconds: Int): String =
    when (seconds) {
        30 -> gettext("Um 30 Sekunden verlängern")
        60 -> gettext("Um 1 Minute verlängern")
        else -> gettext("Um 5 Minuten verlängern")
    }

internal class ConferenceTimerDialog(
    private val controller: ConferenceTimerController,
    private val scope: CoroutineScope,
    /** The control to give the focus back to when the dialog closes. */
    private val returnFocus: () -> HTMLElement?,
) {
    private var modal: Modal? = null
    private var body: Div? = null
    private var builtMode: TimerDialogMode? = null
    private var timeLine: Div? = null
    private var extendButtons: List<Pair<Int, Button>> = emptyList()
    private var closing = false
    private var serial = 0

    // the start form's state survives a rebuild
    private var selectedKey = CONFERENCE_TIMER_DEFAULT_PRESET_MINUTES.toString()
    private var customMinutes = ""
    private var customSeconds = ""

    val isOpen: Boolean get() = modal != null && !closing

    /** Opens the dialog (a second call while it is open does nothing). */
    fun open() {
        if (isOpen) return
        closing = false
        serial = ++timerDialogSerial
        val m = Modal(caption = tr("Timer"))
        modal = m
        val content = m.div { addCssClass("lapis-timer-dialog") }
        body = content
        val closeButton = newActionButton(ActionIcon.CLOSE, tr("Schließen"), ButtonStyle.SECONDARY).apply { onClick { m.hide() } }
        m.addButton(closeButton)
        m.addAfterInsertHook { vnode ->
            val element = vnode.elm as? HTMLElement ?: return@addAfterInsertHook
            element.addEventListener(
                "hidden.bs.modal",
                {
                    if (modal === m) {
                        modal = null
                        body = null
                        builtMode = null
                        closing = false
                        window.setTimeout({ returnFocus()?.focus() }, FOCUS_DELAY_MS)
                    }
                },
            )
        }
        build(controller.display())
        m.show()
    }

    /** Closes the dialog (the focus goes back to the control that opened it). */
    fun close() {
        val m = modal ?: return
        closing = true
        m.hide()
    }

    /** Called after every change of the timer: re-draws what changed. */
    fun refresh() {
        val content = body ?: return
        val display = controller.display()
        val mode = timerDialogMode(display)
        if (mode != builtMode) {
            build(display)
            return
        }
        content.getElement() ?: return
        timeLine?.content = timeText(display)
        for ((seconds, button) in extendButtons) button.disabled = controller.busy || !timerExtensionAllowed(display.remainingMs, seconds)
    }

    private fun timeText(display: TimerDisplay): String =
        if (display.status == ConferenceTimerStatus.PAUSED) {
            gettext("Timer pausiert: noch %1", formatMmSs(display.remainingMs))
        } else {
            gettext("Timer: noch %1", formatMmSs(display.remainingMs))
        }

    private fun build(display: TimerDisplay) {
        val content = body ?: return
        content.removeAll()
        extendButtons = emptyList()
        timeLine = null
        val mode = timerDialogMode(display)
        builtMode = mode
        val primary =
            when (mode) {
                TimerDialogMode.START -> buildStart(content, display)
                TimerDialogMode.RUNNING, TimerDialogMode.PAUSED -> buildActive(content, display, mode)
            }
        // a rebuild drops the focus (the control that had it is gone): the first real action gets it
        window.setTimeout({ primary.getElement()?.focus() }, FOCUS_DELAY_MS)
    }

    // -- off / expired -------------------------------------------------------------------------------------------------------------

    private fun buildStart(
        content: Div,
        display: TimerDisplay,
    ): Button {
        if (display.status == ConferenceTimerStatus.EXPIRED ||
            (display.status == ConferenceTimerStatus.RUNNING && display.remainingMs <= 0L)
        ) {
            content.div(tr("Zeit abgelaufen")) { addCssClasses("fw-bold mb-2") }
        }
        val fieldset = content.tag(TAG.FIELDSET, className = "lapis-timer-duration")
        fieldset.tag(TAG.LEGEND, content = tr("Dauer"), className = "fs-6 fw-bold")
        val group = "lapis-timer-duration-$serial"
        val radios = LinkedHashMap<String, Tag>()

        fun radioRow(
            key: String,
            id: String,
            label: String,
        ): Div {
            val row = fieldset.div(className = "form-check")
            val radio = row.tag(TAG.INPUT, className = "form-check-input lapis-timer-radio")
            radio.setAttribute("type", "radio")
            radio.setAttribute("name", group)
            radio.setAttribute("id", id)
            radio.setAttribute("value", key)
            if (key == selectedKey) radio.setAttribute("checked", "")
            row.tag(TAG.LABEL, content = label, className = "form-check-label").setAttribute("for", id)
            radios[key] = radio
            return row
        }
        for (minutes in CONFERENCE_TIMER_PRESET_MINUTES) {
            radioRow(minutes.toString(), "$group-$minutes", ngettext("%1 Minute", "%1 Minuten", minutes, minutes))
        }
        val customRow = radioRow(CUSTOM_KEY, "$group-custom", gettext("Eigene Zeit"))
        val errorId = "$group-error"
        val fields = customRow.div(className = "d-flex align-items-center gap-2 mt-1 flex-wrap")
        val minutesInput = numberField(fields, "$group-min", gettext("Minuten"), 0, SECONDS_PER_MINUTE, "lapis-timer-custom-min", errorId)
        val secondsInput =
            numberField(fields, "$group-sec", gettext("Sekunden"), 0, SECONDS_PER_MINUTE - 1, "lapis-timer-custom-sec", errorId)
        minutesInput.setAttribute("value", customMinutes)
        secondsInput.setAttribute("value", customSeconds)
        val error = content.div(tr("Zwischen 10 Sekunden und 60 Minuten"), className = "text-danger small lapis-timer-error")
        error.setAttribute("id", errorId)
        error.hide()
        val start = newActionButton(ActionIcon.NEXT, tr("Starten"), ButtonStyle.PRIMARY)
        start.addCssClass("lapis-timer-start")
        content.add(start)

        fun typed(input: Tag): String = (input.getElement() as? HTMLInputElement)?.value.orEmpty().trim()

        fun chosenSeconds(): Int? =
            if (selectedKey == CUSTOM_KEY) {
                customDurationSeconds(typed(minutesInput).toIntOrNull(), typed(secondsInput).toIntOrNull())
            } else {
                selectedKey.toInt() * SECONDS_PER_MINUTE
            }

        fun validate() {
            customMinutes = typed(minutesInput)
            customSeconds = typed(secondsInput)
            val valid = chosenSeconds() != null
            start.disabled = !valid || controller.busy
            if (selectedKey == CUSTOM_KEY && !valid) error.show() else error.hide()
        }
        for ((key, radio) in radios) {
            radio.onEvent {
                change = {
                    selectedKey = key
                    validate()
                }
            }
        }
        for (field in listOf(minutesInput, secondsInput)) {
            field.onEvent {
                input = {
                    // typing into the own-time fields chooses "Eigene Zeit"
                    selectedKey = CUSTOM_KEY
                    (radios.getValue(CUSTOM_KEY).getElement() as? HTMLInputElement)?.checked = true
                    validate()
                }
            }
        }
        start.onClick {
            val seconds = chosenSeconds() ?: return@onClick
            scope.launch {
                controller.start(seconds)
                if (controller.display().status == ConferenceTimerStatus.RUNNING) close()
            }
        }
        // the checked state is set after the first render (the attribute only sets the DEFAULT of a re-rendered input)
        window.setTimeout({
            (radios[selectedKey]?.getElement() as? HTMLInputElement)?.checked = true
            validate()
        }, FOCUS_DELAY_MS)
        start.disabled =
            selectedKey == CUSTOM_KEY &&
            customDurationSeconds(customMinutes.toIntOrNull(), customSeconds.toIntOrNull()) == null
        if (display.status == ConferenceTimerStatus.EXPIRED ||
            (display.status == ConferenceTimerStatus.RUNNING && display.remainingMs <= 0L)
        ) {
            buildExtensions(content, display)
        }
        return start
    }

    private fun numberField(
        parent: Div,
        id: String,
        label: String,
        min: Int,
        max: Int,
        cssClass: String,
        describedBy: String,
    ): Tag {
        val cell = parent.div(className = "d-flex align-items-center gap-1")
        val input = cell.tag(TAG.INPUT, className = "form-control form-control-sm $cssClass")
        input.setAttribute("type", "number")
        input.setAttribute("id", id)
        input.setAttribute("min", min.toString())
        input.setAttribute("max", max.toString())
        input.setAttribute("inputmode", "numeric")
        input.setAttribute("step", "1")
        input.setAttribute("style", "width: 5rem")
        input.setAttribute("aria-describedby", describedBy)
        cell.tag(TAG.LABEL, content = label, className = "small text-muted").setAttribute("for", id)
        return input
    }

    // -- running / paused ----------------------------------------------------------------------------------------------------------

    private fun buildActive(
        content: Div,
        display: TimerDisplay,
        mode: TimerDialogMode,
    ): Button {
        timeLine = content.div(timeText(display), className = "fs-4 fw-bold mb-2 lapis-timer-time")
        val row = content.div(className = "d-flex gap-2 flex-wrap mb-2")
        val toggle =
            if (mode == TimerDialogMode.PAUSED) {
                newActionButton(ActionIcon.NEXT, tr("Fortsetzen"), ButtonStyle.PRIMARY).apply { addCssClass("lapis-timer-resume") }
            } else {
                newActionButton(ActionIcon.NEXT, tr("Pausieren"), ButtonStyle.PRIMARY).apply { addCssClass("lapis-timer-pause") }
            }
        toggle.onClick {
            scope.launch { if (mode == TimerDialogMode.PAUSED) controller.resume() else controller.pause() }
        }
        row.add(toggle)
        val stop = newActionButton(ActionIcon.CANCEL, tr("Stoppen"), ButtonStyle.OUTLINEDANGER).apply { addCssClass("lapis-timer-stop") }
        stop.onClick {
            scope.launch {
                controller.stop()
                if (controller.display().status == ConferenceTimerStatus.OFF) close()
            }
        }
        row.add(stop)
        buildExtensions(content, display)
        return toggle
    }

    private fun buildExtensions(
        content: Div,
        display: TimerDisplay,
    ) {
        val row = content.div(className = "d-flex gap-2 flex-wrap")
        val buttons = mutableListOf<Pair<Int, Button>>()
        for (seconds in listOf(30, 60, 300)) {
            val button = Button(extensionLabel(seconds), style = ButtonStyle.OUTLINESECONDARY, className = "lapis-timer-extend-$seconds")
            button.setAttribute("type", "button")
            button.setAttribute("aria-label", extensionName(seconds))
            button.disabled = !timerExtensionAllowed(display.remainingMs, seconds)
            button.onClick { scope.launch { controller.extend(seconds) } }
            row.add(button)
            buttons += seconds to button
        }
        extendButtons = buttons
    }
}
