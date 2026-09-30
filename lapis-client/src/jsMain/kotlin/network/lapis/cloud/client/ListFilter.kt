package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window

/**
 * The name filter above a list of people (committee roster, board, subscribers, politicians): one labelled text field with a
 * clear button and a live count. The matching is the one of [SearchableSelect] ([filterOptions]: case- and diacritic-insensitive,
 * every word must occur, no regular expressions) -- but applied to the list that is ALREADY fully loaded on the client. Lists
 * that are loaded page by page from the server must NOT use this (a client-side filter would "not find" people on later pages);
 * they need a server-side search instead -- see the exception table in `CHANGELOG.md`.
 *
 * Wiring: the screen keeps its loaded list, calls [apply] (or [filterByName] plus [showCount]) whenever it renders, and
 * re-renders from [subscribe]. The filter text survives reloads of the list because this widget -- not the rendered rows -- owns it.
 * The typed text takes effect after [DEBOUNCE_MS] ms of silence; [clear] and the clear button apply immediately.
 */
class ListFilter internal constructor(
    private val holder: Div,
    private val field: Text,
    private val clearButton: Button,
    private val countRegion: Div,
) {
    private val handlers = mutableListOf<(String) -> Unit>()
    private var debounceHandle: Int? = null

    /** The active filter text, trimmed; blank when no filter is set. */
    var term: String = ""
        private set

    init {
        field.onEvent {
            input = { scheduleApply() }
        }
        clearButton.onClick { clear() }
    }

    /** Calls [handler] whenever [term] changed (not immediately on subscribe). */
    fun subscribe(handler: (String) -> Unit) {
        handlers += handler
    }

    /** Empties the field and applies at once. */
    fun clear() {
        debounceHandle?.let { window.clearTimeout(it) }
        debounceHandle = null
        field.value = ""
        applyNow()
        field.focus()
    }

    /** Hides the whole filter (an empty list needs none) or shows it again. A hidden filter keeps its text. */
    fun setVisible(visible: Boolean) {
        if (visible) holder.show() else holder.hide()
    }

    /** "3 von 12" while a filter is active, otherwise nothing. The region stays mounted (screen readers announce TEXT changes). */
    fun showCount(
        shown: Int,
        total: Int,
    ) {
        countRegion.content = if (term.isBlank()) "" else gettext("%1 von %2", shown, total)
    }

    /** [filterByName] plus [showCount] in one call: the usual render-time use. */
    fun <R> apply(
        items: List<R>,
        nameOf: (R) -> String,
        detailOf: (R) -> String? = { null },
    ): List<R> {
        val filtered = items.filterByName(this, nameOf, detailOf)
        showCount(shown = filtered.size, total = items.size)
        return filtered
    }

    private fun scheduleApply() {
        debounceHandle?.let { window.clearTimeout(it) }
        debounceHandle =
            window.setTimeout({
                debounceHandle = null
                applyNow()
            }, DEBOUNCE_MS)
    }

    private fun applyNow() {
        val next = field.value.orEmpty().trim()
        clearButton.visible = next.isNotEmpty()
        if (next == term) return
        term = next
        handlers.toList().forEach { it(next) }
    }

    internal companion object {
        const val DEBOUNCE_MS = 150
    }
}

/** Builds the filter field in this container. [label] is the visible label (also the accessible name). */
fun Container.listFilterField(label: String = tr("Nach Name filtern")): ListFilter {
    val holder = div(className = "lapis-list-filter d-flex align-items-end gap-2 flex-wrap")
    val field = Text(type = InputType.TEXT, label = label)
    field.addCssClasses("flex-grow-1 mb-0")
    holder.add(field)
    val clearLabel = tr("Suche leeren")
    val clearButton =
        Button("", icon = "fas fa-times", style = ButtonStyle.OUTLINESECONDARY) {
            title = clearLabel
            setAttribute("aria-label", resolvedAttributeText(clearLabel))
            visible = false
        }
    holder.add(clearButton)
    val countRegion =
        Div(className = "text-muted small w-100") {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    holder.add(countRegion)
    return ListFilter(holder = holder, field = field, clearButton = clearButton, countRegion = countRegion)
}

/**
 * The entries of this list whose [nameOf] (and optional [detailOf]) match [filter]'s text -- in the ORIGINAL order (a roster is
 * sorted by the screen, re-ranking it by word start would shuffle rows while typing). A blank filter returns the list unchanged.
 */
internal fun <R> List<R>.filterByName(
    filter: ListFilter,
    nameOf: (R) -> String,
    detailOf: (R) -> String? = { null },
): List<R> {
    if (filter.term.isBlank()) return this
    val prepared = prepareOptions(mapIndexed { index, item -> SearchCandidate(index.toString(), nameOf(item), detailOf(item)) })
    val matching = filterOptions(filter.term, prepared, limit = null).shown.mapTo(mutableSetOf()) { it.value.toInt() }
    return filterIndexed { index, _ -> index in matching }
}
