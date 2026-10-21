package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.Autocomplete
import io.kvision.html.InputType
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate

/** The visible pattern of [spec] in the current language, e.g. `TT.MM.JJJJ` (en: `DD/MM/YYYY`; en-US `MM/DD/YYYY`). Plain text, safe in a hint, a placeholder, a message. */
internal fun localeDatePattern(spec: TemporalLocale = currentTemporalLocale()): String =
    patternText(spec, dayWord = gettext("TT"), monthWord = gettext("MM"), yearWord = gettext("JJJJ"))

/**
 * V1.9.132: a date input field that reads and shows the date in the order and with the separator of the locale (`TemporalLocale.kt`, `LocaleDate.kt`).
 *
 * V1.9.173: the native date picker is the DEFAULT ([picker] = true; the text field below is the fallback for a browser without `type=date`, and
 * the test lever). In picker mode [LapisField.value] is ISO (`2026-03-05`) -- read it with [localDateOrNull], write it with [setDate]; there is no
 * placeholder and no "Format:" hint, [extraHint] alone is the hint. [min]/[max] only grey out days in the popup (a typed date can bypass them),
 * so the screen's own cross-field rule stays authoritative.
 *
 * The text form (fallback):
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
    picker: Boolean = true,
    min: LocalDate? = null,
    max: LocalDate? = null,
): LapisField {
    val spec = currentTemporalLocale()
    if (pickerEffective(picker, supportsDatePicker)) {
        return nativeDateField(label, value, required, rule, host, extraHint, requiredMessage, autocomplete, min, max, spec)
    }
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
            init = {
                it.placeholder = pattern
                it.applyTemporalAttrs(TemporalKind.DATE, picker = false, stepMinutes = 1, lang = spec.tag)
            },
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

private fun LapisForm.nativeDateField(
    label: String,
    value: LocalDate?,
    required: Boolean,
    rule: ((LocalDate) -> FieldCheck)?,
    host: Container,
    extraHint: String?,
    requiredMessage: String?,
    autocomplete: Autocomplete?,
    min: LocalDate?,
    max: LocalDate?,
    spec: TemporalLocale,
): LapisField {
    val field =
        textField(
            label = label,
            type = InputType.DATE,
            value = value?.toString(),
            required = required,
            hint = extraHint,
            host = host,
            requiredMessage = requiredMessage,
            autocomplete = autocomplete,
            rule = { text ->
                val parsed = runCatching { LocalDate.parse(text) }.getOrNull()
                when {
                    parsed == null -> FormRules.localDate(text, spec)
                    parsed.year !in LOCALE_DATE_MIN_YEAR..LOCALE_DATE_MAX_YEAR ->
                        FieldCheck.Invalid(
                            gettext("Bitte ein Jahr zwischen %1 und %2 eingeben.", LOCALE_DATE_MIN_YEAR, LOCALE_DATE_MAX_YEAR),
                        )
                    else -> rule?.invoke(parsed) ?: FieldCheck.Ok
                }
            },
            init = { it.applyTemporalAttrs(TemporalKind.DATE, picker = true, stepMinutes = 1, lang = spec.tag) },
        )
    field.dateSpec = spec
    field.temporalPicker = true
    field.setNativeBound("min", min?.toString())
    field.setNativeBound("max", max?.toString())
    return field
}

/** The date a [localDateField] holds, or `null` while it is blank or not (yet) a real date (a half-typed picker entry included). */
internal fun LapisField.localDateOrNull(): LocalDate? =
    if (temporalPicker) {
        runCatching { LocalDate.parse(value) }.getOrNull()
    } else {
        (parseLocalDate(value, dateSpec ?: currentTemporalLocale()) as? DateParse.Ok)?.date
    }

/**
 * Writes [date] into a [localDateField] in the form it holds (ISO in picker mode, the locale text otherwise). Like [LapisField.setValue]:
 * [LapisField.dirty] stays untouched and the caller revalidates if an error may be showing.
 */
internal fun LapisField.setDate(date: LocalDate?) {
    setValue(date?.let { if (temporalPicker) it.toString() else formatLocalDate(it, dateSpec ?: currentTemporalLocale()) })
}

/** Day bounds of the popup of a picker date field (no-op in text mode). */
internal fun LapisField.setMin(date: LocalDate?) = setNativeBound("min", date?.toString())

internal fun LapisField.setMax(date: LocalDate?) = setNativeBound("max", date?.toString())

/**
 * V1.9.173: keeps two date fields a range -- the end offers no day before the start, the start none after the end. Only popup hints; the
 * cross-field rule of the screen stays the check. `setAttribute` fires no KVision event, so the two subscriptions cannot loop.
 */
internal fun linkDateRange(
    from: LapisField,
    to: LapisField,
) {
    val sync = {
        to.setMin(from.localDateOrNull())
        from.setMax(to.localDateOrNull())
    }
    from.subscribe { sync() }
    to.subscribe { sync() }
    sync()
}
