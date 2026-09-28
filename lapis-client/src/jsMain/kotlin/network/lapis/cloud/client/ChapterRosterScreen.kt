package network.lapis.cloud.client

import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberService

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- the "Landesvorstand"
 * (regional-chapter officer) self-service roster (`Routes.MY_CHAPTER`). Deliberately a NARROW,
 * read-only view: only the active members OF THE OFFICER'S OWN CHAPTER, Name/E-Mail/Beitritt --
 * no status, no role, no financial/family data, no actions. The security boundary is enforced
 * server-side (`network.lapis.cloud.server.security.RegionalChapterVisibility`, see
 * `docs/architecture/regional-chapters.adoc`); [chapterRosterQuery] below is this screen's own
 * "never widen the request" anchor, covered by `ChapterRosterQueryTest`.
 *
 * Reads the session ONCE per screen mount (`sessionChecked` below, not on every page/search
 * change) to get a FRESH `SessionInfoDto.chapterScope` -- the route itself only checked a
 * POSSIBLY STALE session before resolving (`Routing.kt`'s `Routes.MY_CHAPTER` handler), and a
 * grant/revoke elsewhere would otherwise not be reflected until the next full page load. Checking
 * on every page/search change instead would add an unnecessary roundtrip per pager click, and
 * `AppState.setSession` only rebuilds navbar/sidebar (never this screen), so no re-render loop can
 * result either way (plan §5 S12).
 */
fun renderChapterRosterScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    root.pageHeader(tr("Mein Landesverband"))

    var sessionChecked = false
    var state = ChapterRosterState()
    val sortFocus = SortFocusRequest()

    val filterRow = root.hPanel(spacing = 8)
    val searchInput = filterRow.text(label = tr("Suche nach Name oder E-Mail"))

    val noticeSlot = root.vPanel(spacing = 4)
    lateinit var pagerRow: SimplePanel
    lateinit var section: DataSection

    fun refresh() {
        pagerRow.hide()
        sortFocus.beginLoad()
        section.reload()
    }

    fun renderPager(page: MemberAdminPageDto) {
        pagerRow.removeAll()
        if (page.totalCount == 0) return
        pagerRow.span(pagerLabel(page.offset, page.limit, page.totalCount))
        val backButton = pagerRow.button(tr("‹ Zurück"), style = ButtonStyle.OUTLINESECONDARY)
        backButton.disabled = page.offset <= 0
        backButton.onClick {
            state = state.copy(offset = (state.offset - page.limit).coerceAtLeast(0))
            refresh()
        }
        val nextButton = pagerRow.button(tr("Weiter ›"), style = ButtonStyle.OUTLINESECONDARY)
        nextButton.disabled = page.offset + page.rows.size >= page.totalCount
        nextButton.onClick {
            state = state.copy(offset = state.offset + page.limit)
            refresh()
        }
        pagerRow.show()
    }

    section =
        root.dataSection<ChapterRosterLoad>(
            emptyText = tr("Keine aktiven Mitglieder in diesem Landesverband."),
            isEmpty = { it is ChapterRosterLoad.Page && it.page.rows.isEmpty() },
            load = {
                val scope =
                    if (!sessionChecked) {
                        sessionChecked = true
                        // Review fix (MINOR logic): `refreshSessionFromServer()` returning `null`
                        // used to be read as "the grant is gone" -- but it ALSO returns `null` on an
                        // ordinary network error/5xx (now that a genuinely expired session is routed
                        // through `guarded` instead, see that function's own fix). Falling back to
                        // the LAST KNOWN session's `chapterScope` here means a transient refresh
                        // failure no longer shows the wrong "Zugang ist nicht mehr aktiv" notice with
                        // no retry: if the grant really is gone, `listMembersForAdministration` below
                        // still fails with `ForbiddenException` (server-side enforced) and that branch
                        // already refreshes + shows `NoLongerOfficer` correctly.
                        refreshSessionFromServer()?.chapterScope ?: AppState.session?.chapterScope
                    } else {
                        AppState.session?.chapterScope
                    }
                if (scope == null) {
                    ChapterRosterLoad.NoLongerOfficer
                } else {
                    try {
                        ChapterRosterLoad.Page(
                            scope,
                            rpcService<IMemberService>().listMembersForAdministration(
                                chapterRosterQuery(state.search, state.sort, state.offset),
                            ),
                        )
                    } catch (e: ForbiddenException) {
                        // Grant revoked between the session read above and this call (race) -- refresh
                        // the session so the sidebar/dropdown drop the entry too, then show NoLongerOfficer.
                        refreshSessionFromServer()
                        ChapterRosterLoad.NoLongerOfficer
                    }
                }
            },
            onSettled = { loaded ->
                sortFocus.settled(rendersTable = loaded is ChapterRosterLoad.Page && loaded.page.rows.isNotEmpty())
                noticeSlot.removeAll()
                when (loaded) {
                    is ChapterRosterLoad.Page -> {
                        val notice = noticeSlot.div { addCssClasses("alert alert-info") }
                        notice.setAttribute("role", "status")
                        untrustedContent(
                            notice,
                            gettext(
                                "Sie sehen als Landesvorstand ausschließlich die aktiven Mitglieder des Landesverbands " +
                                    "%1. Rolle, Beitragsdaten, Familienbezüge und Personennummern sind ausgeblendet.",
                                loaded.scope.name,
                            ),
                        )
                        renderPager(loaded.page)
                    }
                    ChapterRosterLoad.NoLongerOfficer -> {
                        noticeSlot.p(tr("Ihr Landesvorstand-Zugang ist nicht mehr aktiv."))
                        noticeSlot.link(tr("Zum Dashboard"), url = "#${Routes.DASHBOARD}")
                    }
                    null -> Unit
                }
            },
            render = { panel, loaded ->
                if (loaded is ChapterRosterLoad.Page) {
                    panel.dataTable(
                        columns = chapterRosterColumns(),
                        rows = loaded.page.rows,
                        sort = state.sort.toSortState(),
                        onSort = { clicked ->
                            sortFocus.request((clicked ?: state.sort.toSortState()).key)
                            state = state.copy(sort = (clicked ?: state.sort.toSortState()).toMemberAdminSort(), offset = 0)
                            refresh()
                        },
                        focusSortKey = sortFocus.takeForRender(),
                    )
                }
            },
        )
    pagerRow = root.hPanel(spacing = 8)

    var isInitialSearchEvent = true
    var debounceHandle: Int? = null
    searchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        debounceHandle?.let { window.clearTimeout(it) }
        debounceHandle =
            window.setTimeout({
                state = state.copy(search = value.orEmpty(), offset = 0)
                refresh()
            }, 300)
    }

    refresh()
}

/** Same "name"/"joined" sort keys `MemberAdministrationScreen.kt`'s `toSortState()`/`toMemberAdminSort()` already resolve. */
private fun chapterRosterColumns(): List<DataColumn<MemberAdminRowDto>> =
    listOf(
        DataColumn(
            title = tr("Name"),
            primary = true,
            sortKey = "name",
            cell = { container, row -> container.untrustedSpan(row.displayName) },
        ),
        textColumn(title = tr("E-Mail")) { row: MemberAdminRowDto -> row.email },
        DataColumn(
            title = tr("Beitritt"),
            numeric = true,
            sortKey = "joined",
            cell = { container, row -> container.span(formatDate(row.joinedAt)) },
        ),
    )

/** Local, minimal roster state -- lives only on the screen, never `AppState`/`localStorage` (plan §2.9 "kein Cache in AppState"). */
internal data class ChapterRosterState(
    val search: String = "",
    val sort: MemberAdminSort = MemberAdminSort.NAME_ASC,
    val offset: Int = 0,
)

internal sealed interface ChapterRosterLoad {
    data class Page(
        val scope: RegionalChapterRefDto,
        val page: MemberAdminPageDto,
    ) : ChapterRosterLoad

    data object NoLongerOfficer : ChapterRosterLoad
}

/**
 * The single security anchor of this screen: `regionalChapterId`/`unassignedOnly`/`statuses` stay
 * at their ALWAYS-default values (`null`/`false`/empty) -- see plan §2.9/§6 "ChapterRosterScreen
 * sendet nie regionalChapterId, unassignedOnly oder statuses". The server-side narrowing
 * (`RegionalChapterVisibility.Chapter`) does the actual work; this function only guarantees the
 * CLIENT never even tries to widen the request. `ChapterRosterQueryTest` covers this exhaustively.
 */
internal fun chapterRosterQuery(
    search: String,
    sort: MemberAdminSort,
    offset: Int,
): MemberAdminQuery =
    MemberAdminQuery(
        search = search.trim().ifBlank { null },
        sort = sort,
        offset = offset.coerceAtLeast(0),
    )
