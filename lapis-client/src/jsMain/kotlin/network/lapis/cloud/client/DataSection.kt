package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The load lifecycle of a data view without any widget -- Welle V1.4.25, jsTest-able with
 * `Dispatchers.Unconfined` and a `CompletableDeferred` (see `DataLoadControllerTest`).
 *
 * [reload] reports [DataViewState.Loading], runs [load] and reports the resolved state. A `generation`
 * counter drops the result of a superseded load: two quick reloads (a keystroke in a search field while
 * the previous request is still in flight) can never leave the older answer on screen -- the member roster
 * had no such guard before.
 *
 * `null` from [load] means "failed" (the convention of `guarded`, which has already shown its toast); an
 * exception that escapes [load] is treated the same way. Its message is never surfaced: the error state
 * shows one fixed sentence, so no exception name or identifier can reach the page.
 */
class DataLoadController<T>(
    private val scope: CoroutineScope,
    private val load: suspend () -> T?,
    private val isEmpty: (T) -> Boolean,
    private val filterTerm: () -> String?,
    private val onSettled: (T?) -> Unit,
    private val onState: (DataViewState<T>) -> Unit,
) {
    private var generation = 0

    fun reload() {
        generation++
        val mine = generation
        onState(DataViewState.Loading)
        scope.launch {
            val loaded =
                try {
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            if (mine != generation) return@launch // a newer load has taken over
            onSettled(loaded)
            onState(resolveDataViewState(loaded, isEmpty, filterTerm()))
        }
    }
}

/** Handle of a [dataSection]: [reload] is the only way in -- call it once for the first load. */
class DataSection internal constructor(
    private val controller: DataLoadController<*>,
) {
    fun reload() = controller.reload()
}

/**
 * A region that owns its load and shows exactly one of five states (guideline: "one loading, one error,
 * two empty texts"): loading (a persistent `role="status"` live region, see [DataSectionViews]), error
 * ([dataErrorState] with a retry that reloads), empty ([emptyText]), no match for the active filter
 * ([noMatchText]) or the content ([render]).
 *
 * - [load] is usually `{ guarded { rpc... } }`; `null` = failure. Bundle several RPCs into one result
 *   object there rather than using several sections.
 * - [filterTerm] is the active search/filter term, or `null`/blank when none: it decides between
 *   "nothing here yet" ([emptyText]) and "nothing matches" ([noMatchText]).
 * - [onSettled] runs on every finished (non-stale) load, also when the page is empty or failed (`null`) --
 *   for counters and pagers that live outside the region.
 *
 * Does not load by itself: call [DataSection.reload] once after wiring (filters and counters often need
 * to exist first). Not for screens with their own paging/cursor state (`OpenItemsScreen` uses only the
 * building blocks below).
 */
fun <T> Container.dataSection(
    emptyText: String = gettext("Keine Daten vorhanden."),
    filterTerm: () -> String? = { null },
    noMatchText: (String) -> String = { term -> gettext("Kein Eintrag passt zu \"%1\".", term) },
    isEmpty: (T) -> Boolean,
    onSettled: (T?) -> Unit = {},
    load: suspend () -> T?,
    render: (SimplePanel, T) -> Unit,
): DataSection {
    val views = dataSectionViews()
    lateinit var section: DataSection
    val controller =
        DataLoadController(
            scope = AppScope,
            load = load,
            isEmpty = isEmpty,
            filterTerm = filterTerm,
            onSettled = onSettled,
            onState = { state ->
                views.show(
                    state = state,
                    emptyText = emptyText,
                    noMatchText = noMatchText,
                    onRetry = { section.reload() },
                    render = render,
                )
            },
        )
    section = DataSection(controller)
    return section
}

/**
 * The two parts of a [dataSection]: a live region ([status]) that is mounted ONCE and never removed, and the
 * [body] that is cleared and refilled on every state change.
 *
 * Why two parts (Audit V1.4.25 M3, guideline 2.12): assistive technology announces a change of TEXT inside a
 * live region that already exists; a `role="status"` element that is created together with its text -- what
 * the first version did by clearing the whole host and mounting a fresh `role="status"` div -- is usually
 * not announced at all, so a screen-reader user heard nothing while the data loaded. Now only the text
 * content of [status] changes; the element itself stays in the document.
 */
internal class DataSectionViews(
    val host: SimplePanel,
    val status: Div,
    val body: SimplePanel,
)

/** Creates the host with its persistent live region (empty until a load starts) and the body below it. */
internal fun Container.dataSectionViews(): DataSectionViews {
    val host = simplePanel()
    val status = host.dataStatusRegion()
    val body = host.simplePanel()
    return DataSectionViews(host = host, status = status, body = body)
}

/** The persistent, polite live region: muted small text, `role="status"`, empty until [showLoading]. */
internal fun Container.dataStatusRegion(): Div =
    div {
        addCssClasses("text-muted small")
        setAttribute("role", "status")
        setAttribute("aria-live", "polite")
    }

/** Announces the load: only the text of the already mounted region changes. */
internal fun Div.showLoading() {
    content = tr("Wird geladen …")
}

/** Empties the region's text (the element stays mounted). */
internal fun Div.clearStatus() {
    content = ""
}

/** Renders [state]: the status text for loading, the body for everything else (exactly one at a time). */
internal fun <T> DataSectionViews.show(
    state: DataViewState<T>,
    emptyText: String,
    noMatchText: (String) -> String,
    onRetry: () -> Unit,
    render: (SimplePanel, T) -> Unit,
) {
    body.removeAll()
    // Audit fix: `aria-busy` marks the region while it loads (assistive technology waits for the finished content).
    host.setAttribute("aria-busy", if (state is DataViewState.Loading) "true" else "false")
    if (state is DataViewState.Loading) status.showLoading() else status.clearStatus()
    when (state) {
        DataViewState.Loading -> Unit
        DataViewState.Error -> body.dataErrorState(onRetry = onRetry)
        DataViewState.Empty -> body.p(emptyText) { addCssClasses("text-muted") }
        is DataViewState.NoMatch -> body.p(noMatchText(state.term)) { addCssClasses("text-muted") }
        is DataViewState.Content -> render(body, state.data)
    }
}

/**
 * The error state: an `alert-danger` with `role="alert"`, ONE fixed sentence and an in-box retry button.
 * No `e.message` and no identifier -- that is also the no-data-leak requirement. The toast that `guarded`
 * shows stays: the toast is the notification, this box is the state (it is still there after the toast
 * has gone, and it offers the way out).
 *
 * Clicking retry first moves the keyboard focus to the enclosing region (the retry button is about to be
 * destroyed by the re-render), then calls [onRetry].
 */
fun Container.dataErrorState(onRetry: () -> Unit) {
    val region = this as? Widget
    val box = div { addCssClasses("alert alert-danger d-flex align-items-center flex-wrap gap-2 mb-0") }
    box.setAttribute("role", "alert")
    box.span(tr("Die Daten konnten nicht geladen werden."))
    box.button(tr("Erneut versuchen"), style = ButtonStyle.OUTLINESECONDARY).onClick {
        region?.let { focusRegion ->
            focusRegion.setAttribute("tabindex", "-1")
            focusRegion.focus()
        }
        onRetry()
    }
}
