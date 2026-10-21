package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.i18n.gettext
import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.w3c.dom.HTMLInputElement

/**
 * V1.9.173: the native date/time picker is the DEFAULT of every function here (`picker = true`); the locale text field described below is the
 * fallback for a browser without `type=date`/`type=time` and the test lever ([forceTextTemporalInputsForTest]). `stepMinutes` is a per-field
 * property (1 by default, 5 only on planning screens).
 *
 * V1.9.137: locale date and time inputs for the screens that build RAW KVision `Text` widgets instead of a [LapisForm] (the event form, the shift
 * forms, filter rows, modals). Same convention as [localDateField]/[localTimeField] -- a plain text field that reads the locale form of
 * `TemporalLocale.kt`, shows the pattern as placeholder and as a hint (`aria-describedby`), normalises a valid entry when the field is left, and
 * stores ISO -- without the form grammar's validation slot: the screen validates when the person submits and reads the result with
 * [LocalDateInput.dateOrNull]/[LocalTimeInput.timeOrNull], exactly as it parsed the ISO text before.
 */
private var localInputIdCounter = 0

/** Test hook: pretend the browser has no native date/time pickers (read per call, never cached). */
internal var forceTextTemporalInputsForTest = false

internal const val INVALID_MARKER = "invalid-native-input"

internal val supportsDatePicker: Boolean by lazy { nativeTypeSupported("date") }
internal val supportsTimePicker: Boolean by lazy { nativeTypeSupported("time") }

private fun nativeTypeSupported(type: String): Boolean {
    val el = document.createElement("input") as HTMLInputElement
    el.type = type
    return el.type == type
}

internal fun pickerEffective(
    requested: Boolean,
    supported: Boolean,
) = requested && supported && !forceTextTemporalInputsForTest

/** What a native `date`/`time` `<input>` holds right now. A half-typed entry has an EMPTY `value` and `validity.badInput`. */
internal sealed interface NativeRead {
    data object Empty : NativeRead

    data object BadInput : NativeRead

    data class StepMismatch(
        val raw: String,
    ) : NativeRead

    data class Value(
        val iso: String,
    ) : NativeRead
}

internal fun HTMLInputElement.nativeRead(): NativeRead {
    val v = value
    if (v.isEmpty()) return if (validity.badInput) NativeRead.BadInput else NativeRead.Empty
    return if (validity.stepMismatch) NativeRead.StepMismatch(v) else NativeRead.Value(v)
}

internal enum class TemporalKind(
    val cssClass: String,
) {
    DATE("lapis-date-input"),
    TIME("lapis-time-input"),
}

/**
 * V1.9.173: the attributes every date/time `<input>` gets. The width class in BOTH modes (picker and text fallback); `lang` and (time only)
 * `step = stepMinutes * 60` only in picker mode. The step is a per-field property: 1 minute by default, 5 only on planning screens.
 */
internal fun Text.applyTemporalAttrs(
    kind: TemporalKind,
    picker: Boolean,
    stepMinutes: Int,
    lang: String,
) {
    input.addCssClass(kind.cssClass)
    if (!picker) return
    input.setAttribute("lang", lang)
    if (kind == TemporalKind.TIME) input.setAttribute("step", (stepMinutes * SECONDS_PER_MINUTE_STEP).toString())
}

private const val SECONDS_PER_MINUTE_STEP = 60

private fun Text.setBoundAttribute(
    name: String,
    date: LocalDate?,
) {
    if (date == null) input.removeAttribute(name) else input.setAttribute(name, date.toString())
}

/** The raw value; a half-typed native entry (empty value, `badInput`) yields a marker that never parses, so it is not mistaken for blank. */
private fun Text.rawValue(): String {
    val v = value.orEmpty()
    if (v.isEmpty()) {
        val el = this.input.getElement() as? HTMLInputElement
        if (el?.validity?.badInput == true) return INVALID_MARKER
    }
    return v
}

private fun formatIsoTime(time: LocalTime): String = "${pad2(time.hour)}:${pad2(time.minute)}"

private fun nextHintId(): String = "lapis-local-hint-${localInputIdCounter++}"

/** A raw date `Text` plus its hint line. Hide/show both with [visible]. */
internal class LocalDateInput(
    val input: Text,
    val hint: Div?,
    val nativePicker: Boolean = false,
) {
    /** The date, or `null` while the field is blank or not (yet) a real date. */
    fun dateOrNull(): LocalDate? = (parse() as? DateParse.Ok)?.date

    /** The parse result, for a screen that names the reason. */
    fun parse(): DateParse {
        if (!nativePicker) return parseLocalDate(input.value.orEmpty(), currentTemporalLocale())
        val v = input.rawValue()
        if (v.isEmpty() || v == INVALID_MARKER) return parseLocalDate("", currentTemporalLocale())
        val d = runCatching { LocalDate.parse(v) }.getOrNull() ?: return DateParse.BadFormat
        return DateParse.Ok(d)
    }

    val text: String get() = if (nativePicker) input.rawValue() else input.value.orEmpty()

    var visible: Boolean
        get() = input.visible
        set(value) {
            input.visible = value
            hint?.visible = value
        }

    fun setDate(date: LocalDate?) {
        input.value = date?.let { if (nativePicker) it.toString() else formatLocalDate(it, currentTemporalLocale()) }
    }

    /** Earliest day the popup offers (picker mode only; a typed date can bypass it, so the screen's own rule stays authoritative). */
    fun setMin(date: LocalDate?) {
        if (nativePicker) input.setBoundAttribute("min", date)
    }

    /** Latest day the popup offers; see [setMin]. */
    fun setMax(date: LocalDate?) {
        if (nativePicker) input.setBoundAttribute("max", date)
    }

    fun subscribe(handler: () -> Unit) {
        input.subscribe { handler() }
    }
}

/** A raw time `Text` plus its hint line. */
internal class LocalTimeInput(
    val input: Text,
    val hint: Div?,
    val nativePicker: Boolean = false,
) {
    fun timeOrNull(): LocalTime? = (parse() as? TimeParse.Ok)?.time

    fun parse(): TimeParse {
        if (!nativePicker) return parseLocalTime(input.value.orEmpty(), currentTemporalLocale())
        val v = input.rawValue()
        if (v.isEmpty() || v == INVALID_MARKER) return parseLocalTime("", currentTemporalLocale())
        val t = runCatching { LocalTime.parse(v) }.getOrNull() ?: return TimeParse.BadFormat
        return TimeParse.Ok(t)
    }

    val text: String get() = if (nativePicker) input.rawValue() else input.value.orEmpty()

    fun setTime(time: LocalTime?) {
        input.value = time?.let { if (nativePicker) formatIsoTime(it) else formatLocalTime(it, currentTemporalLocale()) }
    }

    var visible: Boolean
        get() = input.visible
        set(value) {
            input.visible = value
            hint?.visible = value
        }

    fun subscribe(handler: () -> Unit) {
        input.subscribe { handler() }
    }
}

/** A date plus a time input for one wall-clock [LocalDateTime] (class B, no zone). */
internal class LocalDateTimeInput(
    val date: LocalDateInput,
    val time: LocalTimeInput,
) {
    /** The point in time, or `null` while either part is blank or not (yet) valid. */
    fun dateTimeOrNull(): LocalDateTime? {
        val d = date.dateOrNull() ?: return null
        val t = time.timeOrNull() ?: return null
        return LocalDateTime(d, t)
    }

    /**
     * ISO text (`2026-10-15T18:00`) when both parts are valid, the typed parts joined by a space otherwise -- so a validator that
     * parses ISO sees a failure for anything half-typed, and a blank pair stays `""`.
     */
    val value: String
        get() = dateTimeOrNull()?.toString() ?: listOf(date.text, time.text).filter { it.isNotBlank() }.joinToString(" ")

    val isBlank: Boolean get() = date.text.isBlank() && time.text.isBlank()

    var visible: Boolean
        get() = date.visible
        set(value) {
            date.visible = value
            time.visible = value
        }

    fun subscribe(handler: () -> Unit) {
        date.subscribe(handler)
        time.subscribe(handler)
    }

    fun setDateTime(at: LocalDateTime?) {
        date.setDate(at?.date)
        time.setTime(at?.time)
    }

    fun setMin(day: LocalDate?) = date.setMin(day)

    fun setMax(day: LocalDate?) = date.setMax(day)
}

/**
 * V1.9.173: keeps two date inputs a range -- the end offers no day before the start, the start no day after the end. Attributes only
 * (`setAttribute` fires no KVision event, so there is no loop); the screen's cross-field rule remains the authoritative check.
 */
internal fun linkDateRange(
    from: LocalDateInput,
    to: LocalDateInput,
) {
    val sync = {
        to.setMin(from.dateOrNull())
        from.setMax(to.dateOrNull())
    }
    from.subscribe(sync)
    to.subscribe(sync)
    sync()
}

/** A raw locale date input in this container. [label] is the field label (no pattern in it); the pattern is the placeholder and the hint. */
internal fun Container.localDateInput(
    label: String,
    value: LocalDate? = null,
    hintLine: Boolean = true,
    picker: Boolean = true,
): LocalDateInput {
    val spec = currentTemporalLocale()
    val native = pickerEffective(picker, supportsDatePicker)
    val pattern = localeDatePattern(spec)
    val id = nextHintId()
    val text =
        text(
            label = label,
            type = if (native) InputType.DATE else InputType.TEXT,
            value = value?.let { if (native) it.toString() else formatLocalDate(it, spec) },
        )
    text.applyTemporalAttrs(TemporalKind.DATE, native, 1, spec.tag)
    if (native) return LocalDateInput(text, null, nativePicker = true)
    text.placeholder = pattern
    val hint =
        if (hintLine) {
            text.input.setAttribute("aria-describedby", id)
            div(gettext("Format: %1", pattern)) { addCssClasses("text-muted small") }.also { it.id = id }
        } else {
            null
        }
    text.onEvent {
        blur = {
            val parsed = parseLocalDate(text.value.orEmpty(), spec)
            if (parsed is DateParse.Ok) {
                val canonical = formatLocalDate(parsed.date, spec)
                if (text.value != canonical) text.value = canonical
            }
        }
    }
    return LocalDateInput(text, hint, nativePicker = false)
}

/** A raw locale time input in this container; see [localDateInput]. */
internal fun Container.localTimeInput(
    label: String,
    value: LocalTime? = null,
    hintLine: Boolean = true,
    picker: Boolean = true,
    stepMinutes: Int = 1,
): LocalTimeInput {
    val spec = currentTemporalLocale()
    val native = pickerEffective(picker, supportsTimePicker)
    val pattern = timePatternText(spec)
    val id = nextHintId()
    val text =
        text(
            label = label,
            type = if (native) InputType.TIME else InputType.TEXT,
            value = value?.let { if (native) formatIsoTime(it) else formatLocalTime(it, spec) },
        )
    text.applyTemporalAttrs(TemporalKind.TIME, native, stepMinutes, spec.tag)
    if (native) return LocalTimeInput(text, null, nativePicker = true)
    text.placeholder = pattern
    text.input.setAttribute("inputmode", "numeric")
    val hint =
        if (hintLine) {
            text.input.setAttribute("aria-describedby", id)
            div(gettext("Format: %1", pattern)) { addCssClasses("text-muted small") }.also { it.id = id }
        } else {
            null
        }
    text.onEvent {
        blur = {
            val parsed = parseLocalTime(text.value.orEmpty(), spec)
            if (parsed is TimeParse.Ok) {
                val canonical = formatLocalTime(parsed.time, spec)
                if (text.value != canonical) text.value = canonical
            }
        }
    }
    return LocalTimeInput(text, hint)
}

/**
 * A raw date + time input pair: the date field is labelled "[label] (Datum)", the time field "[label] (Uhrzeit)". Pass an already translated
 * [label] (`gettext("Beginn")`).
 */
internal fun Container.localDateTimeInput(
    label: String,
    value: LocalDateTime? = null,
    hintLine: Boolean = true,
    picker: Boolean = true,
    stepMinutes: Int = 1,
): LocalDateTimeInput {
    // The side-by-side row only exists with native pickers; everything else keeps its exact old layout.
    val pickerMode = pickerEffective(picker, supportsDatePicker) && pickerEffective(picker, supportsTimePicker)
    val host: Container = if (pickerMode) div { addCssClass("lapis-datetime-row") } else this
    return LocalDateTimeInput(
        date = host.localDateInput(label = gettext("%1 (Datum)", label), value = value?.date, hintLine = hintLine, picker = pickerMode),
        time =
            host.localTimeInput(
                label = gettext("%1 (Uhrzeit)", label),
                value = value?.time,
                hintLine = hintLine,
                picker = pickerMode,
                stepMinutes = stepMinutes,
            ),
    )
}
