package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate

/** The visible pattern of [spec] in the current language, e.g. `TT.MM.JJJJ` (en: `DD/MM/YYYY`). Plain text, safe in a hint, a placeholder, a message. */
internal fun localeDatePattern(spec: DateFieldOrder = currentDateFieldOrder()): String =
    patternText(spec, dayWord = gettext("TT"), monthWord = gettext("MM"), yearWord = gettext("JJJJ"))

/**
 * V1.9.132: a date input field that reads and shows the date in the order and with the separator of the browser locale (`LocaleDate.kt`).
 *
 *  - The label carries NO pattern; the pattern is the placeholder AND the hint ("Format: TT.MM.JJJJ"), which `aria-describedby` already reads.
 *  - The prefill is formatted in the locale order; what [localDateOrNull] returns is always a [LocalDate] (the storage form stays ISO).
 *  - The field rule is [FormRules.localDate]; [rule] is an optional extra rule on the PARSED date (a range, say). [extraHint] is appended to the
 *    format hint as a second sentence.
 *  - Leaving the field normalises a valid entry to the canonical form (`5.3.2026` -> `05.03.2026`); an invalid entry is left exactly as typed.
 *    The write goes through [LapisField.setValue] -- not typing, so `dirty` stays untouched.
 */
internal fun LapisForm.localDateField(
    label: String,
    value: LocalDate?,
    required: Boolean = false,
    rule: ((LocalDate) -> FieldCheck)? = null,
    host: Container = panel,
    extraHint: String? = null,
): LapisField {
    val spec = currentDateFieldOrder()
    val pattern = localeDatePattern(spec)
    val hint = gettext("Format: %1", pattern).let { base -> if (extraHint == null) base else "$base $extraHint" }
    val field =
        textField(
            label = label,
            value = value?.let { formatLocalDate(it, spec) },
            required = required,
            hint = hint,
            host = host,
            rule = { text ->
                val parsed = parseLocalDate(text, spec)
                if (parsed is DateParse.Ok) rule?.invoke(parsed.date) ?: FieldCheck.Ok else FormRules.localDate(text, spec)
            },
            init = { it.placeholder = pattern },
        )
    field.dateSpec = spec
    (field.control as? Widget)?.onEvent {
        blur = {
            val parsed = parseLocalDate(field.value, spec)
            if (parsed is DateParse.Ok) {
                val canonical = formatLocalDate(parsed.date, spec)
                if (field.value != canonical) {
                    field.setValue(canonical)
                    field.validate(force = false)
                }
            }
        }
    }
    return field
}

/** The date a [localDateField] holds, or `null` while it is blank or not (yet) a real date. */
internal fun LapisField.localDateOrNull(): LocalDate? = (parseLocalDate(value, dateSpec ?: currentDateFieldOrder()) as? DateParse.Ok)?.date
