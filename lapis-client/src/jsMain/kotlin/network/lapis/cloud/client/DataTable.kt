package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.panel.SimplePanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Sort behaviour of a [dataTable]: whether a third click switches sorting off ([allowUnsorted]) and which
 * direction a column starts with ([firstDirection], e.g. newest-first for a date column).
 */
class SortOptions(
    val allowUnsorted: Boolean = false,
    val firstDirection: (String) -> SortDirection = { SortDirection.ASC },
)

/**
 * "Is the viewport narrow enough for the card list?" as an injectable source, so the mode switch is
 * testable without resizing a real browser (see `DataTableWidgetTest`).
 */
internal interface NarrowViewportSource {
    val matches: Boolean

    /** Registers [listener] for changes; returns the function that unregisters it again. */
    fun subscribe(listener: (Boolean) -> Unit): () -> Unit
}

/** The real thing: `window.matchMedia(cardListMediaQuery())`. */
internal object BrowserNarrowViewport : NarrowViewportSource {
    override val matches: Boolean
        get() = window.matchMedia(cardListMediaQuery()).matches

    override fun subscribe(listener: (Boolean) -> Unit): () -> Unit {
        val mediaQuery = window.matchMedia(cardListMediaQuery())
        val handler: (Event) -> Unit = { listener(mediaQuery.matches) }
        mediaQuery.addEventListener("change", handler)
        return { mediaQuery.removeEventListener("change", handler) }
    }
}

/**
 * The data table of the UI/UX guideline (Welle V1.4.25): a `standardTable` on wide viewports, a card list
 * (`.lapis-card-list`, one `.lapis-data-card` per row) below [CARD_LIST_BREAKPOINT_PX].
 *
 * **Stateless on purpose** (Atkinson): rows and the current sort come from the caller, the renderer owns
 * no filter, offset or cursor. To sort, the caller reacts to [onSort] (usually: store the state, reload,
 * call [dataTable] again with `focusSortKey` set). The only state kept here is the current mode.
 *
 * - **Table mode**: sortable columns (those with a [DataColumn.sortKey], when [onSort] is given) get a
 *   native `button` inside the `th` -- Enter/Space work without a key handler -- plus `aria-sort` on the
 *   `th` (exactly one column is not `none`). `title` is the action, `aria-label` the state once sorted.
 *   Numeric columns carry `.lapis-num` on `td` and `th`; the actions column has an empty visible header.
 * - **Card mode**: the primary column is the card title without a label, every other column a `dt`/`dd`
 *   pair (empty pairs are dropped), [actions] go into a labelled `role="group"` (dropped when empty).
 *   There are no sort buttons here -- the sort order chosen on a wide screen simply stays.
 * - **Mode switch**: decided synchronously at construction (no flicker) and re-decided on every media
 *   query change, but re-rendered only when the mode really flips (rotating a device must not lose
 *   anything). The listener is released when the widget is destroyed, and lazily when it fires for a
 *   widget that is no longer in the document.
 *
 * **Security**: cell content goes in as widget content only -- never `rich = true`, never `innerHTML`, no
 * data in `cssText`. Row values (member names, e-mail addresses) are foreign data.
 *
 * The cell lambdas run again on every re-render (sort, mode switch): keep no state in them that has to
 * survive a re-render.
 */
fun <R> Container.dataTable(
    columns: List<DataColumn<R>>,
    rows: List<R>,
    sort: SortState? = null,
    onSort: ((SortState?) -> Unit)? = null,
    sortOptions: SortOptions = SortOptions(),
    actions: ((Container, R) -> Unit)? = null,
    focusSortKey: String? = null,
): SimplePanel =
    dataTableWith(
        columns = columns,
        rows = rows,
        sort = sort,
        onSort = onSort,
        sortOptions = sortOptions,
        actions = actions,
        focusSortKey = focusSortKey,
        viewport = BrowserNarrowViewport,
    )

/** [dataTable] with an injectable [viewport] -- the seam the widget tests use. */
internal fun <R> Container.dataTableWith(
    columns: List<DataColumn<R>>,
    rows: List<R>,
    sort: SortState?,
    onSort: ((SortState?) -> Unit)?,
    sortOptions: SortOptions,
    actions: ((Container, R) -> Unit)?,
    focusSortKey: String?,
    viewport: NarrowViewportSource,
): SimplePanel {
    require(columns.isNotEmpty()) { "a data table needs at least one column" }
    // Validates the primary flags eagerly, in both modes (a second primary is a programming error).
    val layout = cardLayout(columnCount = columns.size, primaryFlags = columns.map { it.primary })
    val host = DataTablePanel()
    add(host)
    var cardMode = viewport.matches
    var pendingFocus = focusSortKey

    fun render() {
        host.removeAll()
        host.renderCount++
        host.headerCells = emptyList()
        host.cardMode = cardMode
        if (cardMode) {
            host.renderCardList(columns, rows, layout, actions)
        } else {
            host.renderTableMode(
                columns = columns,
                rows = rows,
                sort = sort,
                onSort = onSort,
                sortOptions = sortOptions,
                actions = actions,
                focus = FocusRequest(key = { pendingFocus }, consumed = { pendingFocus = null }),
            )
        }
    }
    render()

    var mounted = false
    var unsubscribe: (() -> Unit)? = null

    fun release() {
        unsubscribe?.invoke()
        unsubscribe = null
    }
    unsubscribe =
        viewport.subscribe { narrow ->
            val element = host.getElement()
            if (mounted && element != null && !element.isConnected) {
                // Fired for a widget that has left the document without a destroy hook: stop listening.
                release()
                return@subscribe
            }
            if (narrow != cardMode) {
                cardMode = narrow
                render()
            }
        }
    host.addAfterInsertHook { mounted = true }
    host.addAfterDestroyHook { release() }
    return host
}

/**
 * The panel [dataTable] returns. Plain [SimplePanel] for callers; the extra state exists for the widget
 * tests (KVision keeps a table's header row internal): the current mode, how often it was rendered and
 * the header cells of the last table render.
 */
internal class DataTablePanel : SimplePanel() {
    var cardMode: Boolean = false
    var renderCount: Int = 0
    var headerCells: List<HeaderCell> = emptyList()
}

private fun <R> DataTablePanel.renderTableMode(
    columns: List<DataColumn<R>>,
    rows: List<R>,
    sort: SortState?,
    onSort: ((SortState?) -> Unit)?,
    sortOptions: SortOptions,
    actions: ((Container, R) -> Unit)?,
    focus: FocusRequest,
) {
    val table = standardTable(headers = emptyList())
    val created = mutableListOf<HeaderCell>()

    fun addHeader(cell: HeaderCell) {
        created += cell
        table.addHeaderCell(cell)
    }
    columns.forEach { column ->
        val sortKey = column.sortKey
        if (sortKey == null || onSort == null) {
            addHeader(plainHeaderCell(TableHeader(title = column.title, numeric = column.numeric)))
        } else {
            addHeader(
                HeaderCell(scope = Scope.COL) {
                    // addCssClass (not the className argument): only it makes hasCssClass() truthful.
                    addCssClass("lapis-sort-th")
                    if (column.numeric) addCssClass(NUMERIC_CELL_CLASS)
                    setAttribute("aria-sort", ariaSortValue(sort, sortKey))
                    sortButton(column.title, sortKey, sort, onSort, sortOptions, focus)
                },
            )
        }
    }
    if (actions != null) {
        // Empty visible header, but a name for assistive technology.
        addHeader(
            HeaderCell(scope = Scope.COL) {
                setAttribute("aria-label", gettext("Aktionen"))
            },
        )
    }
    headerCells = created
    rows.forEach { data ->
        table.row {
            columns.forEach { column ->
                cell {
                    if (column.numeric) addCssClass(NUMERIC_CELL_CLASS)
                    column.cell(this, data)
                }
            }
            if (actions != null) cell { actions(this, data) }
        }
    }
}

private fun Container.sortButton(
    columnTitle: String,
    sortKey: String,
    sort: SortState?,
    onSort: (SortState?) -> Unit,
    sortOptions: SortOptions,
    focus: FocusRequest,
) {
    val firstDirection = sortOptions.firstDirection(sortKey)
    val texts = sortButtonTexts(sort, sortKey, columnTitle, firstDirection, sortOptions.allowUnsorted)
    val button = tag(TAG.BUTTON)
    button.setAttribute("type", "button")
    button.title = texts.tooltip
    button.setAttribute("aria-label", texts.ariaLabel)
    button.span(columnTitle)
    val idle = sort == null || sort.key != sortKey
    button.span(className = if (idle) "fas ${sortIconClass(sort, sortKey)} lapis-sort-idle" else "fas ${sortIconClass(sort, sortKey)}") {
        setAttribute("aria-hidden", "true")
    }
    button.onClick {
        onSort(nextSortState(sort, sortKey, firstDirection, sortOptions.allowUnsorted))
    }
    if (focus.key() == sortKey) {
        // A re-render (sort click) destroys the button the keyboard user just pressed: hand the focus to
        // the new button of the same column once it is in the document -- exactly once.
        button.addAfterInsertHook {
            if (focus.key() == sortKey) {
                focus.consumed()
                button.focus()
            }
        }
    }
}

/** Which column header button should get the keyboard focus after the next render (see `focusSortKey`). */
private class FocusRequest(
    val key: () -> String?,
    val consumed: () -> Unit,
)

private fun <R> SimplePanel.renderCardList(
    columns: List<DataColumn<R>>,
    rows: List<R>,
    layout: CardLayout,
    actions: ((Container, R) -> Unit)?,
) {
    val list = div { addCssClass("lapis-card-list") }
    list.setAttribute("role", "list")
    rows.forEach { data ->
        val card = list.div { addCssClass("lapis-data-card") }
        card.setAttribute("role", "listitem")

        val title = card.div { addCssClass("lapis-data-card-title") }
        columns[layout.primaryIndex].cell(title, data)
        if (title.getChildren().isEmpty()) card.remove(title)

        val details = card.tag(TAG.DL)
        layout.detailIndices.forEach { index ->
            val column = columns[index]
            val term = details.tag(TAG.DT, content = column.title)
            val definition = details.tag(TAG.DD)
            column.cell(definition, data)
            if (definition.getChildren().isEmpty()) {
                details.remove(definition)
                details.remove(term)
            }
        }
        if (details.getChildren().isEmpty()) card.remove(details)

        if (actions != null) {
            val group = card.div { addCssClass("lapis-data-card-actions") }
            group.setAttribute("role", "group")
            group.setAttribute("aria-label", gettext("Aktionen"))
            actions(group, data)
            if (group.getChildren().isEmpty()) card.remove(group)
        }
    }
}
