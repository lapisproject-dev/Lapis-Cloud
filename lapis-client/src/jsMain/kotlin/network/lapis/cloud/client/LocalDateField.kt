package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.Autocomplete
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate

/** The visible pattern of [spec] in the current language, e.g. `TT.MM.JJJJ` (en: `DD/MM/YYYY`; en-US `MM/DD/YYYY`). Plain text, safe in a hint, a placeholder, a message. */
internal fun localeDatePattern(spec: TemporalLocale = currentTemporalLocale()): String =
    patternText(spec, dayWord = gettext("TT"), monthWord = gettext("MM"), yearWord = gettext("JJJJ"))

/**
 * V1.9.132: a date input field that reads and shows the date in the order and with the separator of the locale (`TemporalLocale.kt`, `LocaleDate.kt`).
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
    value: LocalDate? = null,
    required: Boolean = false,
    rule: ((LocalDate) -> FieldCheck)? = null,
    host: Container = panel,
    extraHint: String? = null,
    requiredMessage: String? = null,
    autocomplete: Autocomplete? = null,
): LapisField {
    val spec = currentTemporalLocale()
    val pattern = localeDatePattern(spec)
    val hint = gettext("Format: %1", pattern).let { base -> if (extraHint == null) base else "$base $extraHint" }
    val field =
        textField(
            label = label,
            value = value?.let { formatLocalDate(it, spec) },
            required = required,
            hint = hint,
            host = host,
            requiredMessage = requiredMessage,
            autocomplete = autocomplete,
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
internal fun LapisField.localDateOrNull(): LocalDate? = (parseLocalDate(value, dateSpec ?: currentTemporalLocale()) as? DateParse.Ok)?.date
