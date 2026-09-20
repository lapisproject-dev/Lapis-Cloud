package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.span
import io.kvision.i18n.gettext

/**
 * Pure (widget-free, jsTest-able) state and decision logic behind [dataTable] and [dataSection] --
 * Welle V1.4.25 "UI foundation" (W1 of the UI/UX guideline, see `docs/architecture/ui-ux-guideline.adoc`).
 *
 * Everything in this file is deliberately free of widgets and DOM access: sort cycling, ARIA values,
 * icon classes, the card layout split, the five view states. The renderers in `DataTable.kt` /
 * `DataSection.kt` only translate these decisions into widgets.
 */
enum class SortDirection {
    ASC,
    DESC,
    ;

    fun opposite(): SortDirection = if (this == ASC) DESC else ASC
}

/** The one active sort of a table: which column (by [key]) and in which [direction]. */
data class SortState(
    val key: String,
    val direction: SortDirection,
)

/**
 * Next sort state after a click on the header of column [clicked].
 *
 * - Another column (or no sort so far): start that column with [firstDirection].
 * - The same column: reverse the direction.
 * - With [allowUnsorted] the cycle is `first -> opposite -> unsorted` (returns `null`); without it the
 *   third click falls back to [firstDirection], because some sorts (e.g. the member roster's
 *   `MemberAdminSort`) have no "unsorted" value.
 */
fun nextSortState(
    current: SortState?,
    clicked: String,
    firstDirection: SortDirection = SortDirection.ASC,
    allowUnsorted: Boolean = false,
): SortState? =
    when {
        current == null || current.key != clicked -> SortState(key = clicked, direction = firstDirection)
        current.direction == firstDirection -> SortState(key = clicked, direction = firstDirection.opposite())
        allowUnsorted -> null
        else -> SortState(key = clicked, direction = firstDirection)
    }

/** Value of the `aria-sort` attribute on the `th` of column [key]: exactly one column is not `none`. */
fun ariaSortValue(
    current: SortState?,
    key: String,
): String =
    when {
        current == null || current.key != key -> "none"
        current.direction == SortDirection.ASC -> "ascending"
        else -> "descending"
    }

/** Font Awesome class of the sort icon: neutral `fa-sort` for an idle column, arrow up/down for the active one. */
fun sortIconClass(
    current: SortState?,
    key: String,
): String =
    when {
        current == null || current.key != key -> "fa-sort"
        current.direction == SortDirection.ASC -> "fa-sort-up"
        else -> "fa-sort-down"
    }

/** Tooltip (`title`, always the action) and accessible name (`aria-label`, the state once sorted). */
data class SortButtonTexts(
    val tooltip: String,
    val ariaLabel: String,
)

/**
 * Texts of a sortable column header button. [columnTitle] usually is a `tr(...)` result carrying
 * KVision's marker around the UNtranslated key, so it is resolved (marker stripped AND translated) via
 * [resolvedAttributeText] before it goes into `gettext(..., %1)` -- otherwise the tooltip and the
 * `aria-label` would mix languages: translated sentence, German column name (see
 * `ClientTrAttributeLeakTest`, which cannot see this indirect flow).
 */
fun sortButtonTexts(
    current: SortState?,
    key: String,
    columnTitle: String,
    firstDirection: SortDirection = SortDirection.ASC,
    allowUnsorted: Boolean = false,
): SortButtonTexts {
    val title = resolvedAttributeText(columnTitle)
    val nextDirection = nextSortState(current, key, firstDirection, allowUnsorted)?.direction ?: firstDirection
    val action =
        if (nextDirection == SortDirection.ASC) {
            gettext("Nach %1 aufsteigend sortieren", title)
        } else {
            gettext("Nach %1 absteigend sortieren", title)
        }
    val state =
        when {
            current == null || current.key != key -> action
            current.direction == SortDirection.ASC -> gettext("Aufsteigend sortiert nach %1", title)
            else -> gettext("Absteigend sortiert nach %1", title)
        }
    return SortButtonTexts(tooltip = action, ariaLabel = state)
}

/**
 * Which sort header button gets the keyboard focus after the re-render that a sort click triggers -- and,
 * just as important, when that wish EXPIRES (Audit V1.4.25, minor 14). The first version kept the key until
 * a table was rendered: a sort load that failed, was superseded or came back empty left it armed, so the
 * next, unrelated render (a search keystroke) suddenly moved the focus to a header button.
 *
 * Life cycle: [request] on the click, [beginLoad] when that click's load starts (the request moves to
 * the load and can no longer leak into a later one), [settled] when the load has finished (a load that
 * renders no table drops it), [takeForRender] in the render that shows the table (consumed exactly once).
 * A newer [beginLoad] overwrites the running one, so a superseded load never delivers its focus.
 */
class SortFocusRequest {
    private var requested: String? = null
    private var forRunningLoad: String? = null

    fun request(key: String) {
        requested = key
    }

    fun beginLoad() {
        forRunningLoad = requested
        requested = null
    }

    fun settled(rendersTable: Boolean) {
        if (!rendersTable) forRunningLoad = null
    }

    fun takeForRender(): String? = forRunningLoad.also { forRunningLoad = null }
}

/**
 * One column of a [dataTable]. Exactly five fields -- a column is a title, two layout flags, an
 * optional sort key and the cell renderer; anything else belongs to the caller's [cell] lambda.
 *
 * - [numeric]: right-aligned tabular figures (`.lapis-num`) on `td` and `th`. Dates that identify a row
 *   (a primary column) stay left even if they are numbers.
 * - [primary]: the column that becomes the card title in the narrow card list (at most one; without one,
 *   column 0).
 * - [sortKey]: makes the header a sort button. `null` = not sortable.
 * - [cell]: renders the value into the given cell/card container. Text goes in as widget content only
 *   (never `rich = true`, never `innerHTML`) -- row values are foreign data.
 */
class DataColumn<R>(
    val title: String,
    val numeric: Boolean = false,
    val primary: Boolean = false,
    val sortKey: String? = null,
    val cell: (Container, R) -> Unit,
)

/** Factory for the common case "plain text cell" -- deliberately no sixth field on [DataColumn]. */
fun <R> textColumn(
    title: String,
    numeric: Boolean = false,
    primary: Boolean = false,
    sortKey: String? = null,
    text: (R) -> String,
): DataColumn<R> =
    DataColumn(
        title = title,
        numeric = numeric,
        primary = primary,
        sortKey = sortKey,
        cell = { container, row ->
            val value = text(row)
            // A blank value adds nothing, so the card list can drop the empty term/definition pair.
            if (value.isNotBlank()) container.span(value)
        },
    )

/** Which column is the card title and which columns become the `dt`/`dd` pairs of a card. */
data class CardLayout(
    val primaryIndex: Int,
    val detailIndices: List<Int>,
)

/** Splits [columnCount] columns into card title + details; more than one `primary` flag is a programming error. */
fun cardLayout(
    columnCount: Int,
    primaryFlags: List<Boolean>,
): CardLayout {
    require(columnCount > 0) { "a table needs at least one column" }
    require(primaryFlags.size == columnCount) { "one primary flag per column expected" }
    val primaries = primaryFlags.indices.filter { primaryFlags[it] }
    require(primaries.size <= 1) { "at most one primary column, got ${primaries.size}" }
    val primaryIndex = primaries.firstOrNull() ?: 0
    return CardLayout(primaryIndex = primaryIndex, detailIndices = (0 until columnCount).filter { it != primaryIndex })
}

/** The five states a data view can be in; [Content] is the only one that shows rows. */
sealed interface DataViewState<out T> {
    data object Loading : DataViewState<Nothing>

    data object Error : DataViewState<Nothing>

    data object Empty : DataViewState<Nothing>

    data class NoMatch(
        val term: String,
    ) : DataViewState<Nothing>

    data class Content<T>(
        val data: T,
    ) : DataViewState<T>
}

/**
 * Resolves a finished load into a view state. `null` means the load failed (see `guarded`), an empty
 * result is [DataViewState.Empty] without and [DataViewState.NoMatch] with an active filter term -- two
 * different sentences ("no data yet" vs. "nothing matches your search").
 */
fun <T> resolveDataViewState(
    loaded: T?,
    isEmpty: (T) -> Boolean,
    filterTerm: String?,
): DataViewState<T> {
    val term = filterTerm?.trim().orEmpty()
    return when {
        loaded == null -> DataViewState.Error
        !isEmpty(loaded) -> DataViewState.Content(loaded)
        term.isEmpty() -> DataViewState.Empty
        else -> DataViewState.NoMatch(term)
    }
}

/** The one media query that switches a [dataTable] between table and card list (max-width 767.98px). */
internal const val CARD_LIST_MEDIA_QUERY = "(max-width: 767.98px)"

fun cardListMediaQuery(): String = CARD_LIST_MEDIA_QUERY
