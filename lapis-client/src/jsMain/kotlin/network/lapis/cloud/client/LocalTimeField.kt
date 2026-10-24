package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.html.InputType
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
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
 * V1.9.200: ONE labelled group for a point in time -- `<fieldset class="lapis-datetime-group"><legend>[label]</legend>` and the two inputs. The
 * legend is the only visible label; the inputs keep a visually hidden label and an `aria-label` ("[label], Datum" / "[label], Uhrzeit"), so a
 * screen reader hears the same words and the screen shows no "(Datum)"/"(Uhrzeit)" twice. In picker mode date and time stand side by side in a
 * `.lapis-datetime-row`; the text fallback keeps its stacked layout inside the same fieldset. Shared by [localDateTimeFields] and
 * [localDateTimeInput], so the two families cannot drift apart.
 */
internal class DateTimeGroup(
    val fieldset: Tag,
    val legend: Tag,
    val inner: Container,
) {
    private var starred = false

    /** Appends the required star to the legend, once (both inner fields ask for it). */
    fun markRequired() {
        if (starred) return
        starred = true
        val text = legend.content
        legend.content = null
        if (text != null) legend.span(text)
        legend.span(" *") {
            addCssClass("lapis-required-mark")
            setAttribute("aria-hidden", "true")
        }
    }
}

internal fun Container.dateTimeGroup(
    label: String,
    pickerMode: Boolean,
): DateTimeGroup {
    val fieldset = Tag(TAG.FIELDSET, className = "lapis-datetime-group")
    val legend = Tag(TAG.LEGEND, label, className = "lapis-datetime-legend")
    fieldset.add(legend)
    add(fieldset)
    val inner: Container = if (pickerMode) fieldset.div { addCssClass("lapis-datetime-row") } else fieldset
    return DateTimeGroup(fieldset, legend, inner)
}

/** The visible label of an inner input is replaced by the legend: hidden visually (still the accessible name) plus an explicit `aria-label`. */
internal fun Text.hideVisibleLabel(accessibleName: String) {
    flabel.addCssClass("visually-hidden")
    input.setAttribute("aria-label", resolvedAttributeText(accessibleName))
}

/**
 * Two locale fields for one point in time inside one [dateTimeGroup]: the legend is [label] (an already translated text such as
 * `gettext("Termin")`), the inputs are named "[label], Datum" and "[label], Uhrzeit" for assistive technology. Both sit in [host]; the stored
 * value is an ISO [LocalDateTime] (a wall-clock of the organization zone, class B -- no zone is introduced here).
 */
internal fun LapisForm.localDateTimeFields(
    label: String,
    value: LocalDateTime? = null,
    required: Boolean = false,
    host: Container = panel,
    picker: Boolean = true,
    stepMinutes: Int = 1,
): LocalDateTimeFields {
    // The side-by-side row only exists with native pickers; the text fallback keeps its stacked layout.
    val pickerMode = pickerEffective(picker, supportsDatePicker) && pickerEffective(picker, supportsTimePicker)
    val group = host.dateTimeGroup(label, pickerMode)
    val dateName = gettext("%1, Datum", label)
    val timeName = gettext("%1, Uhrzeit", label)
    val dateField = localDateField(label = dateName, value = value?.date, required = required, host = group.inner, picker = pickerMode)
    val timeField =
        localTimeField(
            label = timeName,
            value = value?.time,
            required = required,
            host = group.inner,
            picker = pickerMode,
            stepMinutes = stepMinutes,
        )
    listOf(dateField to dateName, timeField to timeName).forEach { (field, name) ->
        (field.control as? Text)?.hideVisibleLabel(name)
        field.requiredMarkRedirect = group::markRequired
    }
    return LocalDateTimeFields(dateField, timeField)
}
