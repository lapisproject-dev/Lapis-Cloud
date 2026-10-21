package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * V1.9.173: the native time picker is the DEFAULT ([picker] = true), with a minute grid of [stepMinutes] (1 by default, 5 only on planning screens).
 * In picker mode [LapisField.value] is ISO (`09:05`) -- read it with [localTimeOrNull], write it with [setTime]; there is no placeholder and no
 * "Format:" hint. An off-grid entry is an error only where [stepMinutes] is above 1. The text form below is the fallback.
 *
 * V1.9.137: a time-of-day input field -- the sibling of [localDateField]. A plain text field that reads `15:05` (en-US also `3:05 PM`), shows the pattern as placeholder and hint
 * (`aria-describedby` reads the hint), and stores an ISO [LocalTime]. Leaving the field normalises a valid entry (`9.05` -> `09:05`); an invalid
 * entry stays exactly as typed. [extraHint] is appended to the hint as a second sentence.
 */
internal fun LapisForm.localTimeField(
    label: String,
    value: LocalTime?,
    required: Boolean = false,
    rule: ((LocalTime) -> FieldCheck)? = null,
    host: Container = panel,
    extraHint: String? = null,
    requiredMessage: String? = null,
    picker: Boolean = true,
    stepMinutes: Int = 1,
): LapisField {
    val spec = currentTemporalLocale()
    if (pickerEffective(picker, supportsTimePicker)) {
        return nativeTimeField(label, value, required, rule, host, extraHint, requiredMessage, stepMinutes, spec)
    }
    val pattern = timePatternText(spec)
    val hint = gettext("Format: %1", pattern).let { base -> if (extraHint == null) base else "$base $extraHint" }
    val field =
        textField(
            label = label,
            value = value?.let { formatLocalTime(it, spec) },
            required = required,
            hint = hint,
            host = host,
            requiredMessage = requiredMessage,
            rule = { text ->
                val parsed = parseLocalTime(text, spec)
                if (parsed is TimeParse.Ok) rule?.invoke(parsed.time) ?: FieldCheck.Ok else FormRules.localTime(text, spec)
            },
            init = {
                it.placeholder = pattern
                it.applyTemporalAttrs(TemporalKind.TIME, picker = false, stepMinutes = 1, lang = spec.tag)
                (it.input as? Widget)?.setAttribute("inputmode", "numeric")
            },
        )
    field.dateSpec = spec
    (field.control as? Widget)?.onEvent {
        blur = {
            val parsed = parseLocalTime(field.value, spec)
            if (parsed is TimeParse.Ok) {
                val canonical = formatLocalTime(parsed.time, spec)
                if (field.value != canonical) {
                    field.setValue(canonical)
                    field.validate(force = false)
                }
            }
        }
    }
    return field
}

private fun LapisForm.nativeTimeField(
    label: String,
    value: LocalTime?,
    required: Boolean,
    rule: ((LocalTime) -> FieldCheck)?,
    host: Container,
    extraHint: String?,
    requiredMessage: String?,
    stepMinutes: Int,
    spec: TemporalLocale,
): LapisField {
    val field =
        textField(
            label = label,
            type = InputType.TIME,
            value = value?.let { "${pad2(it.hour)}:${pad2(it.minute)}" },
            required = required,
            hint = extraHint,
            host = host,
            requiredMessage = requiredMessage,
            rule = { text ->
                val parsed = runCatching { LocalTime.parse(text) }.getOrNull()
                if (parsed == null) FormRules.localTime(text, spec) else rule?.invoke(parsed) ?: FieldCheck.Ok
            },
            init = { it.applyTemporalAttrs(TemporalKind.TIME, picker = true, stepMinutes = stepMinutes, lang = spec.tag) },
        )
    field.dateSpec = spec
    field.temporalPicker = true
    field.stepMinutes = stepMinutes
    return field
}

/** The time a [localTimeField] holds, or `null` while it is blank or not (yet) a real time (a half-typed picker entry included). */
internal fun LapisField.localTimeOrNull(): LocalTime? =
    if (temporalPicker) {
        runCatching { LocalTime.parse(value) }.getOrNull()
    } else {
        (parseLocalTime(value, dateSpec ?: currentTemporalLocale()) as? TimeParse.Ok)?.time
    }

/** Writes [time] into a [localTimeField] in the form it holds; see [setDate] for the contract. */
internal fun LapisField.setTime(time: LocalTime?) {
    setValue(
        time?.let {
            if (temporalPicker) {
                "${pad2(it.hour)}:${pad2(it.minute)}"
            } else {
                formatLocalTime(
                    it,
                    dateSpec ?: currentTemporalLocale(),
                )
            }
        },
    )
}

/** The two fields of a "date plus time" input, side by side in one row group; [dateTimeOrNull] is the combined ISO value. */
internal class LocalDateTimeFields(
    val date: LapisField,
    val time: LapisField,
) {
    /** The combined [LocalDateTime], or `null` while either field is blank or not (yet) valid. */
    fun dateTimeOrNull(): LocalDateTime? {
        val d: LocalDate = date.localDateOrNull() ?: return null
        val t: LocalTime = time.localTimeOrNull() ?: return null
        return LocalDateTime(d, t)
    }

    fun reset() {
        date.reset()
        time.reset()
    }

    /** Earliest day the date popup offers (picker mode only). */
    fun setMin(day: LocalDate?) = date.setMin(day)

    fun setVisible(visible: Boolean) {
        date.setVisible(visible)
        time.setVisible(visible)
    }

    fun subscribe(handler: () -> Unit) {
        date.subscribe { handler() }
        time.subscribe { handler() }
    }
}

/**
 * Two locale fields for one point in time, labelled "[label] (Datum)" and "[label] (Uhrzeit)" ([label] is an already translated text such as
 * `gettext("Termin")`). Both sit in [host]; the stored value is an ISO [LocalDateTime] (a wall-clock of the organization zone, class B -- no zone
 * is introduced here).
 */
internal fun LapisForm.localDateTimeFields(
    label: String,
    value: LocalDateTime? = null,
    required: Boolean = false,
    host: Container = panel,
    picker: Boolean = true,
    stepMinutes: Int = 1,
): LocalDateTimeFields {
    // The side-by-side row only exists with native pickers; the text fallback keeps its exact old layout.
    val pickerMode = pickerEffective(picker, supportsDatePicker) && pickerEffective(picker, supportsTimePicker)
    val rowHost: Container = if (pickerMode) host.div { addCssClass("lapis-datetime-row") } else host
    val dateField =
        localDateField(label = gettext("%1 (Datum)", label), value = value?.date, required = required, host = rowHost, picker = pickerMode)
    val timeField =
        localTimeField(
            label = gettext("%1 (Uhrzeit)", label),
            value = value?.time,
            required = required,
            host = rowHost,
            picker = pickerMode,
            stepMinutes = stepMinutes,
        )
    return LocalDateTimeFields(dateField, timeField)
}
