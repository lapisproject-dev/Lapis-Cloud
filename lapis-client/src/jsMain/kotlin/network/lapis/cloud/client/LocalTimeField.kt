package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * V1.9.137: a time-of-day input field -- the sibling of [localDateField]. A plain text field (no browser time picker, whose display would follow
 * the operating system and not the UI language) that reads `15:05` (en-US also `3:05 PM`), shows the pattern as placeholder and hint
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
): LapisField {
    val spec = currentTemporalLocale()
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

/** The time a [localTimeField] holds, or `null` while it is blank or not (yet) a real time. */
internal fun LapisField.localTimeOrNull(): LocalTime? = (parseLocalTime(value, dateSpec ?: currentTemporalLocale()) as? TimeParse.Ok)?.time

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
): LocalDateTimeFields {
    val dateField = localDateField(label = gettext("%1 (Datum)", label), value = value?.date, required = required, host = host)
    val timeField = localTimeField(label = gettext("%1 (Uhrzeit)", label), value = value?.time, required = required, host = host)
    return LocalDateTimeFields(dateField, timeField)
}
