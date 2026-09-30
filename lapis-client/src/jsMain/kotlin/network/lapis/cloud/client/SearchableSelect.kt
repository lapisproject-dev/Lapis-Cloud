package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr

/**
 * A searchable replacement for the plain `<select>` wherever a PERSON is chosen (member, donor, contact, participant).
 * With hundreds of members a native dropdown is unusable; this widget is an ARIA combobox: the user types a few letters of
 * the name, the list narrows (see [filterOptions] for the matching rules), Enter picks the highlighted entry.
 *
 * ### Contract (what callers may rely on)
 *  - [value] is ALWAYS the `id` (the first half of an options pair) of an entry that was really offered, never free text.
 *    Typing alone never changes it: the text in the field is only a search query until an entry is chosen. Leaving the field
 *    (blur, Tab without having arrowed to an entry, Esc Esc) restores the previous selection.
 *  - It IS a [Text] (and therefore an `AbstractText`/`StringFormControl`), so `LapisForm.wire`, `LapisField.setValue/reset/
 *    subscribe/validate`, the hint and error slots, `aria-required`/`aria-invalid` and the blur validation work unchanged.
 *    The dropdown never takes focus (focus stays in the `<input>`; list, arrow and clear buttons swallow `mousedown`), so
 *    a click on an entry is not a blur and cannot trigger a premature validation.
 *  - [options] takes the same `(id, label)` pairs as KVision's `Select`. A pair whose id is `""` is the "none" entry
 *    ("-- keine --", "— bitte wählen —"): pinned on top, shown only while the query is empty, its label is trusted developer
 *    text resolved through [resolvedAttributeText] (so `tr("...")` keeps working). EVERY other label is untrusted text and is
 *    run through [sanitizeUntrustedI18nText] here (idempotent, so callers that already used [untrustedOptions] lose nothing)
 *    and rendered only as plain widget text, never as HTML.
 *  - A [value] that is not (yet) among the options is kept and not reported -- options often arrive asynchronously, exactly
 *    like KVision's `Select`. The field shows the name as soon as the matching option exists.
 *  - At most [MAX_SHOWN] entries are rendered; the dropdown then says how many more match ("weitertippen, um einzugrenzen").
 *  - [subscribe] behaves like KVision's: it calls the observer immediately and afterwards on every change of [value].
 *
 * ### Keyboard
 * Focus selects the text; the list does not open by itself. Click, Arrow Down/Up, Alt+Arrow Down or typing open it; the first
 * match is active. Arrows do not wrap (Home/End belong to the text field). Enter picks the active entry and closes. Esc closes;
 * a second Esc restores the selection and clears the query (a third Esc, with nothing to undo, reaches the surrounding modal).
 * Tab picks the active entry ONLY if the user moved there with the arrows; otherwise it restores and moves focus on.
 *
 * Layout: the dropdown and the two icon buttons live in a zero-height `.lapis-ssel-layer` directly below the `<input>` (CSS
 * `order` in `theme.css`), so no JavaScript measures anything but the free space below the field when the list opens.
 */
class SearchableSelect(
    options: List<Pair<String, String>>? = null,
    value: String? = null,
    label: String? = null,
) : Text(type = InputType.TEXT, label = label) {
    // Text's constructor runs before these initialisers and may already assign `value`; the overridden setter must ignore that.
    private var constructed: Boolean = false

    private val uid = "lapis-ssel-${selectIdCounter++}"
    private var selected: String? = null
    private var rawOptions: List<Pair<String, String>> = emptyList()
    private var noneLabel: String? = null
    private var prepared: List<PreparedOption> = emptyList()
    private val observers = mutableListOf<(String?) -> Unit>()

    private var query: String = ""
    private var editing: Boolean = false
    private var isOpen: Boolean = false
    private var active: Int = -1
    private var arrowUsed: Boolean = false
    private var entries: List<Entry> = emptyList()
    private var optionWidgets: List<Tag> = emptyList()

    private class Entry(
        val value: String,
        val label: String,
        val detail: String?,
    )

    private val layer = Div(className = "lapis-ssel-layer")
    private val dropdown = Div(className = "lapis-ssel-dropdown")
    private val listbox = Tag(TAG.UL, className = "lapis-ssel-list")
    private val hint = Div(className = "lapis-ssel-hint text-muted small")
    private val status = Div(className = "visually-hidden")
    private val actions = Div(className = "lapis-ssel-actions")
    private val clearButton =
        Button("", icon = "fas fa-times", style = ButtonStyle.LINK) {
            addCssClass("lapis-ssel-button")
            title = gettext("Suche leeren")
            setAttribute("aria-label", gettext("Suche leeren"))
            setAttribute("tabindex", "-1")
            hide()
        }
    private val arrowLabel = tr("Vorschläge anzeigen")
    private val arrowButton =
        Button("", icon = "fas fa-chevron-down", style = ButtonStyle.LINK) {
            addCssClass("lapis-ssel-button")
            title = arrowLabel
            setAttribute("aria-label", resolvedAttributeText(arrowLabel))
            setAttribute("tabindex", "-1")
        }

    /** The `(id, label)` pairs, labels already sanitised (the none entry's label is kept as given: trusted text). */
    var options: List<Pair<String, String>>?
        get() = rawOptions
        set(newOptions) {
            rawOptions =
                newOptions.orEmpty().map { (id, text) -> if (id.isEmpty()) id to text else id to sanitizeUntrustedI18nText(text) }
            rebuildPrepared()
        }

    /**
     * A standing remark at the foot of the dropdown (e.g. "only the first 200 of 340 contacts were loaded"): the search can only
     * find what was loaded, and a silent cap would read as "this person does not exist". Shown when no other hint applies.
     */
    var listNote: String? = null

    /** Optional second search/display text per id (member number, e-mail ...): searched and shown muted behind the name. */
    var details: Map<String, String> = emptyMap()
        set(newDetails) {
            field = newDetails.mapValues { (_, text) -> sanitizeUntrustedI18nText(text) }
            rebuildPrepared()
        }

    override var value: String?
        get() = selected
        set(newValue) {
            if (!constructed) return
            val changed = selected.orEmpty() != newValue.orEmpty()
            selected = newValue
            resetEditing()
            syncDisplay()
            if (changed) notifyObservers()
        }

    override fun subscribe(observer: (String?) -> Unit): () -> Unit {
        observers += observer
        observer(selected)
        return { observers -= observer }
    }

    init {
        addCssClass("lapis-ssel")
        autocomplete = Autocomplete.OFF
        with(input) {
            setAttribute("role", "combobox")
            setAttribute("aria-autocomplete", "list")
            setAttribute("aria-expanded", "false")
            setAttribute("aria-controls", "$uid-list")
            setAttribute("autocapitalize", "off")
            setAttribute("spellcheck", "false")
        }
        listbox.id = "$uid-list"
        listbox.setAttribute("role", "listbox")
        val listboxName = label ?: tr("Tippen zum Suchen")
        listbox.setAttribute("aria-label", resolvedAttributeText(listboxName))
        status.setAttribute("role", "status")
        status.setAttribute("aria-live", "polite")
        dropdown.hide()
        dropdown.add(listbox)
        dropdown.add(hint)
        dropdown.swallowMouseDown()
        layer.add(dropdown)
        layer.add(status)
        actions.add(clearButton)
        actions.add(arrowButton)
        clearButton.swallowMouseDown()
        arrowButton.swallowMouseDown()
        layer.add(actions)
        add(layer)
        clearButton.onClick { onClearClicked() }
        arrowButton.onClick { onArrowClicked() }
        val field = input
        field.onEvent {
            focus = { field.selectDomInputText() }
            click = { openList() }
            this.input = { onTyped() }
            keydown = { event ->
                when (event.key) {
                    "ArrowDown" -> {
                        event.preventDefault()
                        if (isOpen && !event.altKey) moveActive(1) else openList()
                    }
                    "ArrowUp" -> {
                        event.preventDefault()
                        if (isOpen) moveActive(-1) else openList()
                    }
                    "Enter" ->
                        if (isOpen) {
                            event.preventDefault()
                            pickActive()
                        }
                    "Escape" ->
                        if (isOpen) {
                            event.preventDefault()
                            event.stopPropagation()
                            closeList()
                        } else if (editing) {
                            event.preventDefault()
                            event.stopPropagation()
                            restore()
                        }
                    "Tab" -> if (isOpen && arrowUsed && active in entries.indices) pickActive() else restore()
                }
            }
            blur = { restore() }
        }
        constructed = true
        this.options = options
        selected = value
        syncDisplay()
    }

    private fun rebuildPrepared() {
        noneLabel = rawOptions.firstOrNull { it.first.isEmpty() }?.second
        prepared =
            prepareOptions(
                rawOptions.filter { it.first.isNotEmpty() }.map { (id, text) -> SearchCandidate(id, text, details[id]) },
            )
        if (constructed) {
            syncDisplay()
            if (isOpen) renderList()
        }
    }

    private fun labelOf(id: String): String? = prepared.firstOrNull { it.value == id }?.label

    /** Writes the chosen entry's name (or the placeholder) into the field, unless the user is in the middle of searching. */
    private fun syncDisplay() {
        if (editing) return
        val id = selected.orEmpty()
        val text = if (id.isEmpty()) "" else labelOf(id).orEmpty()
        if (input.value.orEmpty() != text) input.value = text
        placeholder = if (id.isEmpty()) noneLabel?.let { resolvedAttributeText(it) } ?: gettext("Tippen zum Suchen") else null
        updateClearButton()
    }

    private fun notifyObservers() {
        observers.toList().forEach { it(selected) }
    }

    private fun resetEditing() {
        editing = false
        query = ""
        arrowUsed = false
        closeList()
    }

    /** Back to the chosen value: the query is dropped, the list closes, the field shows the selected name again. */
    private fun restore() {
        resetEditing()
        syncDisplay()
    }

    private fun choose(id: String) {
        val changed = selected.orEmpty() != id
        selected = id
        resetEditing()
        syncDisplay()
        if (changed) notifyObservers()
        // After the new name is in the field: the listeners of `change` (dirty flag, cross-field rules) read the settled state.
        input.dispatchDomChange()
    }

    private fun pickActive() {
        entries.getOrNull(active)?.let { choose(it.value) }
    }

    private fun onTyped() {
        query = input.domInputText().orEmpty()
        editing = true
        arrowUsed = false
        openList()
    }

    private fun onArrowClicked() {
        if (disabled) return
        if (isOpen) {
            closeList()
        } else {
            input.focus()
            openList()
        }
    }

    private fun onClearClicked() {
        if (editing && query.isNotEmpty()) {
            query = ""
            input.value = ""
            input.focus()
            openList()
            updateClearButton()
        } else if (noneLabel != null && selected.orEmpty().isNotEmpty()) {
            choose("")
            input.focus()
        }
    }

    private fun updateClearButton() {
        val searchText = editing && query.isNotEmpty()
        val clearSelection = !editing && noneLabel != null && selected.orEmpty().isNotEmpty()
        if (searchText || clearSelection) {
            val text = if (searchText) gettext("Suche leeren") else gettext("Auswahl leeren")
            clearButton.title = text
            clearButton.setAttribute("aria-label", text)
            clearButton.show()
        } else {
            clearButton.hide()
        }
    }

    private fun openList() {
        if (disabled) return
        renderList()
        updateClearButton()
        if (entries.isEmpty() && query.isBlank()) {
            closeList()
            return
        }
        if (!isOpen) {
            isOpen = true
            if (input.opensUpwards(
                    MIN_ROOM_BELOW_PX,
                )
            ) {
                layer.addCssClass("lapis-ssel-layer--up")
            } else {
                layer.removeCssClass("lapis-ssel-layer--up")
            }
            dropdown.show()
            input.setAttribute("aria-expanded", "true")
        }
    }

    private fun closeList() {
        if (!isOpen) return
        isOpen = false
        dropdown.hide()
        input.setAttribute("aria-expanded", "false")
        input.setAttribute("aria-activedescendant", "")
        status.content = ""
        active = -1
    }

    private fun renderList() {
        val result = filterOptions(query, prepared, MAX_SHOWN)
        val list = mutableListOf<Entry>()
        val noneEntry = noneLabel
        if (query.isBlank() && noneEntry != null) list += Entry("", resolvedAttributeText(noneEntry), null)
        result.shown.forEach { list += Entry(it.value, it.label, it.detail) }
        entries = list
        listbox.removeAll()
        val widgets = mutableListOf<Tag>()
        list.forEachIndexed { index, entry -> widgets += buildOption(index, entry) }
        optionWidgets = widgets
        active = initialActive()
        applyActive(scroll = false)
        hint.content = hintText(result)
        if (result.totalMatches > 0 || list.isNotEmpty()) {
            status.content = gettext("Treffer: %1", result.totalMatches)
        } else {
            status.content = noMatchText()
        }
    }

    private fun initialActive(): Int {
        if (entries.isEmpty()) return -1
        if (query.isBlank()) {
            val selectedIndex = entries.indexOfFirst { it.value == selected.orEmpty() }
            if (selectedIndex >= 0) return selectedIndex
        }
        return 0
    }

    private fun hintText(result: FilterResult): String? =
        when {
            result.totalMatches == 0 && query.isNotBlank() -> noMatchText()
            result.totalMatches > result.shown.size ->
                gettext("%1 von %2 angezeigt – weitertippen, um einzugrenzen.", result.shown.size, result.totalMatches)
            else -> listNote
        }

    private fun noMatchText(): String = gettext("Kein Eintrag passt zu \"%1\".", query.trim())

    private fun buildOption(
        index: Int,
        entry: Entry,
    ): Tag {
        val option = Tag(TAG.LI, className = "lapis-ssel-option")
        option.id = "$uid-opt-$index"
        option.setAttribute("role", "option")
        option.setAttribute("data-value", entry.value)
        option.setAttribute("aria-selected", if (entry.value == selected.orEmpty()) "true" else "false")
        option.untrustedSpan(entry.label, className = "lapis-ssel-label")
        if (entry.detail != null) option.untrustedSpan(entry.detail, className = "lapis-ssel-detail text-muted small")
        option.swallowMouseDown()
        option.onClick { choose(entry.value) }
        listbox.add(option)
        return option
    }

    private fun moveActive(delta: Int) {
        if (entries.isEmpty()) return
        active = (active + delta).coerceIn(0, entries.lastIndex)
        arrowUsed = true
        applyActive(scroll = true)
    }

    private fun applyActive(scroll: Boolean) {
        optionWidgets.forEachIndexed { index, widget ->
            if (index == active) widget.addCssClass("lapis-ssel-option--active") else widget.removeCssClass("lapis-ssel-option--active")
        }
        val current = optionWidgets.getOrNull(active)
        input.setAttribute("aria-activedescendant", current?.id.orEmpty())
        if (scroll) current?.scrollNearestIntoView()
    }

    private companion object {
        const val MAX_SHOWN = 50
        const val MIN_ROOM_BELOW_PX = 200
    }
}

private var selectIdCounter = 0

/** Keeps the keyboard focus in the `<input>`: a press on the dropdown/buttons must not blur the field. */
private fun io.kvision.core.Widget.swallowMouseDown() {
    onEvent { mousedown = { event -> event.preventDefault() } }
}

fun Container.searchableSelect(
    options: List<Pair<String, String>>? = null,
    value: String? = null,
    label: String? = null,
    noneOption: String? = null,
    details: Map<String, String> = emptyMap(),
    init: (SearchableSelect.() -> Unit)? = null,
): SearchableSelect {
    val all = if (noneOption != null) listOf("" to noneOption) + options.orEmpty() else options
    val control = SearchableSelect(options = all, value = value, label = label)
    if (details.isNotEmpty()) control.details = details
    init?.invoke(control)
    add(control)
    return control
}
