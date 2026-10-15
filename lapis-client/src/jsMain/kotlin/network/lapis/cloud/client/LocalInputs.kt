package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * V1.9.137: locale date and time inputs for the screens that build RAW KVision `Text` widgets instead of a [LapisForm] (the event form, the shift
 * forms, filter rows, modals). Same convention as [localDateField]/[localTimeField] -- a plain text field that reads the locale form of
 * `TemporalLocale.kt`, shows the pattern as placeholder and as a hint (`aria-describedby`), normalises a valid entry when the field is left, and
 * stores ISO -- without the form grammar's validation slot: the screen validates when the person submits and reads the result with
 * [LocalDateInput.dateOrNull]/[LocalTimeInput.timeOrNull], exactly as it parsed the ISO text before.
 */
private var localInputIdCounter = 0

private fun nextHintId(): String = "lapis-local-hint-${localInputIdCounter++}"

/** A raw date `Text` plus its hint line. Hide/show both with [visible]. */
internal class LocalDateInput(
    val input: Text,
    val hint: Div?,
) {
    /** The date, or `null` while the field is blank or not (yet) a real date. */
    fun dateOrNull(): LocalDate? = (parseLocalDate(input.value.orEmpty(), currentTemporalLocale()) as? DateParse.Ok)?.date

    /** The parse result, for a screen that names the reason. */
    fun parse(): DateParse = parseLocalDate(input.value.orEmpty(), currentTemporalLocale())

    val text: String get() = input.value.orEmpty()

    var visible: Boolean
        get() = input.visible
        set(value) {
            input.visible = value
            hint?.visible = value
        }

    fun setDate(date: LocalDate?) {
        input.value = date?.let { formatLocalDate(it, currentTemporalLocale()) }
    }

    fun subscribe(handler: () -> Unit) {
        input.subscribe { handler() }
    }
}

/** A raw time `Text` plus its hint line. */
internal class LocalTimeInput(
    val input: Text,
    val hint: Div?,
) {
    fun timeOrNull(): LocalTime? = (parseLocalTime(input.value.orEmpty(), currentTemporalLocale()) as? TimeParse.Ok)?.time

    fun parse(): TimeParse = parseLocalTime(input.value.orEmpty(), currentTemporalLocale())

    val text: String get() = input.value.orEmpty()

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
        time.input.value = at?.time?.let { formatLocalTime(it, currentTemporalLocale()) }
    }
}

/** A raw locale date input in this container. [label] is the field label (no pattern in it); the pattern is the placeholder and the hint. */
internal fun Container.localDateInput(
    label: String,
    value: LocalDate? = null,
    hintLine: Boolean = true,
): LocalDateInput {
    val spec = currentTemporalLocale()
    val pattern = localeDatePattern(spec)
    val id = nextHintId()
    val text = text(label = label, value = value?.let { formatLocalDate(it, spec) })
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
    return LocalDateInput(text, hint)
}

/** A raw locale time input in this container; see [localDateInput]. */
internal fun Container.localTimeInput(
    label: String,
    value: LocalTime? = null,
    hintLine: Boolean = true,
): LocalTimeInput {
    val spec = currentTemporalLocale()
    val pattern = timePatternText(spec)
    val id = nextHintId()
    val text = text(label = label, value = value?.let { formatLocalTime(it, spec) })
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
): LocalDateTimeInput =
    LocalDateTimeInput(
        date = localDateInput(label = gettext("%1 (Datum)", label), value = value?.date, hintLine = hintLine),
        time = localTimeInput(label = gettext("%1 (Uhrzeit)", label), value = value?.time, hintLine = hintLine),
    )
