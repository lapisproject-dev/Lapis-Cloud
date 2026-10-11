package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.html.Autocomplete
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CommitteeCandidateSearch
import network.lapis.cloud.shared.domain.MemberPickDto
import network.lapis.cloud.shared.domain.MemberPickPageDto
import network.lapis.cloud.shared.rpc.IGovernanceService

/**
 * V1.9.101 -- a person picker that searches ON THE SERVER, for places where the choice must not require loading the whole
 * member directory (the General Assembly has every active member). It is a [Text] like [SearchableSelect] (so `LapisForm`,
 * the hint and error slots and `aria-required` work unchanged) and an ARIA combobox: the user types at least
 * [CommitteeCandidateSearch.MIN_QUERY_LENGTH] characters, the query is sent after a short delay, the list shows the first
 * page and a "Weitere anzeigen" button appends the next one (never more than [CommitteeCandidateSearch.MAX_PAGE] + 1 pages).
 *
 * ### Contract
 *  - [value] is ALWAYS the `id` of an entry the server really offered, or `""`. Typing alone never changes it: the text in
 *    the field is only a query until an entry is chosen; leaving the field restores the previous selection.
 *  - [selectedPick] is the chosen id together with its name (for chips and for pre-filling).
 *  - No total count is shown or requested, and there is no endless scrolling: only the explicit button loads more.
 *  - A response that arrives after a newer query was sent is discarded (sequence number).
 *  - Names are untrusted text: shown through [untrustedSpan] only, never as HTML.
 *  - A failed search (rate limit, missing right) is reported as a toast by [guarded] (see `committeeCandidateSearch`) and answers `null`:
 *    the list then offers nothing for that text.
 *
 * @param debounceMs wait after the last keystroke before the request is sent (tests pass `0`).
 */
class RemoteMemberPicker(
    private val search: suspend (query: String, page: Int) -> MemberPickPageDto?,
    initial: MemberPickDto? = null,
    label: String? = null,
    private val debounceMs: Int = SEARCH_DEBOUNCE_MS,
) : Text(type = InputType.TEXT, label = label) {
    // Text's constructor runs before these initialisers and may already assign `value`; the overridden setter must ignore that.
    private var constructed: Boolean = false

    private val uid = "lapis-rmp-${remotePickerIdCounter++}"
    private var selected: MemberPickDto? = null

    /** Every pick this widget has been shown or offered, by id: the only ids [value] accepts besides `""`. */
    private val known = mutableMapOf<String, MemberPickDto>()
    private var unavailableNote: String? = null
    private val observers = mutableListOf<(String?) -> Unit>()

    private var query: String = ""
    private var editing: Boolean = false
    private var isOpen: Boolean = false
    private var active: Int = -1
    private var arrowUsed: Boolean = false
    private var results: List<MemberPickDto> = emptyList()
    private var loadedPage: Int = -1
    private var hasMore: Boolean = false
    private var sequence: Int = 0
    private var pendingTimer: Int? = null
    private var optionWidgets: List<Tag> = emptyList()

    private val layer = Div(className = "lapis-ssel-layer")
    private val dropdown = Div(className = "lapis-ssel-dropdown")
    private val listbox = Tag(TAG.UL, className = "lapis-ssel-list")
    private val hint = Div(className = "lapis-ssel-hint text-muted small")
    private val status = Div(className = "visually-hidden")
    private val moreButton =
        newActionButton(ActionIcon.MORE, tr("Weitere anzeigen"), ButtonStyle.OUTLINESECONDARY) {
            addCssClass("lapis-ssel-more")
            setAttribute("tabindex", "-1")
            hide()
        }

    /** The chosen person (id + name) or `null`. */
    val selectedPick: MemberPickDto?
        get() = selected

    override var value: String?
        get() = selected?.id.orEmpty()
        set(newValue) {
            if (!constructed) return
            val id = newValue.orEmpty()
            if (id == selected?.id.orEmpty()) return
            // Only an id the server has offered (or the caller has [offer]ed) is a valid value; any other id clears the field.
            selected = known[id]
            resetEditing()
            syncDisplay()
            notifyObservers()
        }

    override fun subscribe(observer: (String?) -> Unit): () -> Unit {
        observers += observer
        observer(selected?.id.orEmpty())
        return { observers -= observer }
    }

    /**
     * Makes an id + name pair a valid value (pre-filling an edit form from data in hand); a following `field.setValue(pick.id)`
     * then selects it like any other write through the form grammar.
     */
    fun offer(pick: MemberPickDto) {
        known[pick.id] = pick.copy(displayName = sanitizeUntrustedI18nText(pick.displayName))
    }

    /** Clears the selection (the same as `value = ""`). */
    fun clear() {
        value = ""
    }

    /**
     * `note != null` switches the picker off and shows [note] as its placeholder ("choose a committee first"); `null` switches
     * it on again.
     */
    fun setUnavailable(note: String?) {
        unavailableNote = note
        disabled = note != null
        syncDisplay()
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
        dropdown.add(moreButton)
        dropdown.add(hint)
        dropdown.swallowRemoteMouseDown()
        layer.add(dropdown)
        layer.add(status)
        add(layer)
        moreButton.swallowRemoteMouseDown()
        moreButton.onClick { loadNextPage() }
        val field = input
        field.onEvent {
            focus = { field.selectDomInputText() }
            click = { if (query.trim().length >= CommitteeCandidateSearch.MIN_QUERY_LENGTH) openList() }
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
                    "Tab" -> if (isOpen && arrowUsed && active in results.indices) pickActive() else restore()
                }
            }
            blur = { restore() }
        }
        constructed = true
        if (initial != null) offer(initial)
        selected = initial?.let { known[it.id] }
        syncDisplay()
    }

    private fun syncDisplay() {
        if (editing) return
        val text = selected?.displayName.orEmpty()
        if (input.value.orEmpty() != text) input.value = text
        placeholder = if (selected == null) unavailableNote?.let { resolvedAttributeText(it) } ?: gettext("Tippen zum Suchen") else null
    }

    private fun notifyObservers() {
        observers.toList().forEach { it(selected?.id.orEmpty()) }
    }

    private fun cancelPending() {
        pendingTimer?.let { window.clearTimeout(it) }
        pendingTimer = null
        sequence++
    }

    private fun resetEditing() {
        cancelPending()
        editing = false
        query = ""
        arrowUsed = false
        results = emptyList()
        loadedPage = -1
        hasMore = false
        closeList()
    }

    private fun restore() {
        resetEditing()
        syncDisplay()
    }

    private fun choose(pick: MemberPickDto) {
        known[pick.id] = pick
        val changed = selected?.id != pick.id
        selected = pick
        resetEditing()
        syncDisplay()
        if (changed) notifyObservers()
        input.dispatchDomChange()
    }

    private fun pickActive() {
        results.getOrNull(active)?.let { choose(it) }
    }

    private fun onTyped() {
        query = input.domInputText().orEmpty()
        editing = true
        arrowUsed = false
        cancelPending()
        results = emptyList()
        loadedPage = -1
        hasMore = false
        if (query.trim().length < CommitteeCandidateSearch.MIN_QUERY_LENGTH) {
            renderList()
            openList()
            return
        }
        val mySequence = sequence
        val run = { fetch(page = 0, mySequence = mySequence) }
        if (debounceMs <= 0) {
            run()
        } else {
            pendingTimer =
                window.setTimeout(
                    {
                        pendingTimer = null
                        run()
                    },
                    debounceMs,
                )
        }
    }

    private fun loadNextPage() {
        if (!hasMore || loadedPage >= CommitteeCandidateSearch.MAX_PAGE) return
        fetch(page = loadedPage + 1, mySequence = sequence)
    }

    private fun fetch(
        page: Int,
        mySequence: Int,
    ) {
        val term = query.trim()
        AppScope.launch {
            val response = search(term, page)
            // A newer keystroke (or a pick/escape) bumped the sequence: this answer is stale.
            if (mySequence != sequence || response == null) return@launch
            val cleaned = response.items.map { it.copy(displayName = sanitizeUntrustedI18nText(it.displayName)) }
            cleaned.forEach { known[it.id] = it }
            results = if (page == 0) cleaned else results + cleaned
            loadedPage = page
            hasMore = response.hasMore
            renderList()
            openList()
        }
    }

    private fun openList() {
        if (disabled) return
        if (!editing && results.isEmpty()) return
        renderList()
        if (!isOpen) {
            isOpen = true
            if (input.opensUpwards(MIN_ROOM_BELOW_PX)) {
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
        listbox.removeAll()
        val widgets = mutableListOf<Tag>()
        results.forEachIndexed { index, pick -> widgets += buildOption(index, pick) }
        optionWidgets = widgets
        active = if (results.isEmpty()) -1 else active.coerceIn(0, results.lastIndex).takeIf { it >= 0 } ?: 0
        applyActive(scroll = false)
        val tooShort = query.trim().length < CommitteeCandidateSearch.MIN_QUERY_LENGTH
        val atLastPage = loadedPage >= CommitteeCandidateSearch.MAX_PAGE
        if (hasMore && !atLastPage) moreButton.show() else moreButton.hide()
        hint.content =
            when {
                tooShort -> gettext("Mindestens 2 Zeichen eingeben.")
                results.isEmpty() && loadedPage >= 0 -> gettext("Keine Treffer.")
                hasMore && atLastPage -> gettext("Bitte Suche verfeinern.")
                else -> null
            }
        status.content =
            when {
                tooShort -> ""
                results.isEmpty() && loadedPage >= 0 -> gettext("Keine Treffer.")
                results.isNotEmpty() -> gettext("%1 Treffer angezeigt", results.size)
                else -> ""
            }
    }

    private fun buildOption(
        index: Int,
        pick: MemberPickDto,
    ): Tag {
        val option = Tag(TAG.LI, className = "lapis-ssel-option")
        option.id = "$uid-opt-$index"
        option.setAttribute("role", "option")
        option.setAttribute("data-value", pick.id)
        option.setAttribute("aria-selected", if (pick.id == selected?.id) "true" else "false")
        option.untrustedSpan(pick.displayName, className = "lapis-ssel-label")
        option.swallowRemoteMouseDown()
        option.onClick { choose(pick) }
        listbox.add(option)
        return option
    }

    private fun moveActive(delta: Int) {
        if (results.isEmpty()) return
        active = (active + delta).coerceIn(0, results.lastIndex)
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
        const val SEARCH_DEBOUNCE_MS = 300
        const val MIN_ROOM_BELOW_PX = 200
    }
}

private var remotePickerIdCounter = 0

/** Keeps the keyboard focus in the `<input>`: a press on the dropdown/buttons must not blur the field. */
private fun io.kvision.core.Widget.swallowRemoteMouseDown() {
    onEvent { mousedown = { event -> event.preventDefault() } }
}

fun Container.remoteMemberPicker(
    search: suspend (query: String, page: Int) -> MemberPickPageDto?,
    initial: MemberPickDto? = null,
    label: String? = null,
    init: (RemoteMemberPicker.() -> Unit)? = null,
): RemoteMemberPicker {
    val control = RemoteMemberPicker(search = search, initial = initial, label = label)
    init?.invoke(control)
    add(control)
    return control
}

/**
 * The search function every [RemoteMemberPicker] of a committee uses: a failed call (rate limit, no right) is reported by
 * [guarded] as a toast and yields `null`, which the picker treats as "nothing to offer".
 */
fun committeeCandidateSearch(committeeId: String): suspend (String, Int) -> MemberPickPageDto? =
    { query, page -> guarded { rpcService<IGovernanceService>().searchCommitteeCandidates(committeeId, query, page) } }
