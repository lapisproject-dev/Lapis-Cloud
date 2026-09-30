package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.DeathDateRules
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MemberStatusTransitions
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IRegionalChapterService
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException

/**
 * Screen 4 of the V0.7.3 plan -- BOARD/ADMIN only, route-guarded in `Routing.kt` (never even
 * rendered for a plain MEMBER, per the plan). Three sub-sections in this one file, mirroring the
 * existing `renderXSection` grouping convention the old `App.kt` already used: pending
 * applications (approve/reject), the privileged member roster (Welle V1.2.12 -- see
 * [renderMemberRoster]), and direct member creation.
 *
 * **Welle V1.4.4.4 "Familienmitgliedschaften" widened this to also admit TREASURER** -- but only
 * for the roster, and only so a Schatzmeister can reach the "Mitgliedschaftsstufe" section of
 * [openMemberEditorDialog] ([canEditMembershipTierOf] KDoc has the full Rollen-Asymmetrie). The
 * other two sections stay BOARD/ADMIN-exclusive, both server-side (`IRegistrationService
 * .listPendingApplications`/`createMemberDirect` both `requireRole(BOARD, ADMIN)`) and here on the
 * client, so a TREASURER caller is never offered a section the server would reject anyway.
 */
fun renderMemberAdministrationScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    root.pageHeader(tr("Mitgliederverwaltung"))

    val callerRole = AppState.session?.role
    val isBoardOrAdmin = callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
    // Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- loaded once, BEFORE any
    // of the sections below, and threaded through as a plain parameter (never
    // `session.regionalChaptersExist`, see plan §1 P5: that session flag is stale until the next
    // login/boot-probe, `chapters.isNotEmpty()` is the live source of truth). Not `guarded` -- see
    // `loadRegionalChapterOptionsOrEmpty` KDoc.
    AppScope.launch {
        val chapters = loadRegionalChapterOptionsOrEmpty()
        renderMemberAdministrationSections(root, chapters, callerRole, isBoardOrAdmin)
    }
}

private fun renderMemberAdministrationSections(
    root: SimplePanel,
    chapters: List<RegionalChapterRefDto>,
    callerRole: AccountRole?,
    isBoardOrAdmin: Boolean,
) {
    if (isBoardOrAdmin) renderPendingApplications(root, chapters)
    renderMemberRoster(root, chapters)
    // V1.7.2 sub-wave 2b "Keycloak als externe Benutzerverwaltung -- UI": ADMIN-only (mirrors
    // IKeycloakLinkService's own role gate), and only in Keycloak mode -- see KeycloakLinkScreen.kt
    // class KDoc "house rule ... never offer an action the server rejects anyway".
    if (callerRole == AccountRole.ADMIN && AppState.session?.keycloakMode == true) renderKeycloakLinkSection(root)
    if (isBoardOrAdmin) renderDirectMemberCreation(root, chapters)
    // Welle V1.9.10 "Mitgliederzahl-Sichtbarkeit": visible to TREASURER/BOARD/ADMIN alike (same read
    // gate IOrganizationSettingsService.getOrganizationSettings already enforces server-side), but
    // ADMIN-only to CHANGE -- see renderPublicMemberCountToggle KDoc for why this deliberately does
    // NOT mirror renderPoliticianRankingToggle's canAdmin computation (BOARD there IS writable).
    renderPublicMemberCountToggle(root, canAdmin = callerRole == AccountRole.ADMIN)
}

private fun renderPendingApplications(
    root: SimplePanel,
    chapters: List<RegionalChapterRefDto>,
) {
    root.h2(tr("Offene Anträge")) { addCssClass("h5") }
    // Welle V1.4.25: `dataSection` + `dataTable` replace the hand-built table -- the same density,
    // loading/error/empty grammar and narrow-viewport card list as the roster below, so the two tables of
    // this screen no longer follow two different conventions. The role column keeps the semantic
    // [accountRoleBadge] (UI theme redesign wave 2026-08-20); `MemberDto.role` (unlike `MemberSummaryDto`)
    // IS available here.
    lateinit var section: DataSection
    section =
        root.dataSection<List<MemberDto>>(
            emptyText = tr("Keine offenen Anträge."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IRegistrationService>().listPendingApplications() } },
            render = { panel, applications ->
                panel.dataTable(
                    columns = pendingApplicationColumns(chapters),
                    rows = applications,
                    actions = {
                        actions,
                        application,
                        ->
                        renderPendingApplicationActions(actions, application, chapters, onChanged = { section.reload() })
                    },
                )
            },
        )
    section.reload()
}

private fun pendingApplicationColumns(chapters: List<RegionalChapterRefDto>): List<DataColumn<MemberDto>> =
    listOf<DataColumn<MemberDto>>(
        DataColumn(
            title = tr("Antragsteller"),
            primary = true,
            cell = { container, application -> container.span(pendingApplicationLabel(application)) },
        ),
        DataColumn(title = tr("Rolle"), cell = { container, application -> container.accountRoleBadge(application.role) }),
    ) +
        if (chapters.isNotEmpty()) {
            listOf(
                DataColumn(
                    title = tr("Landesverband"),
                    cell = { container, application ->
                        val name = chapters.firstOrNull { it.id == application.regionalChapterId }?.name
                        if (name !=
                            null
                        ) {
                            container.untrustedSpan(name)
                        } else {
                            container.span(tr("nicht zugeordnet")) { addCssClass("text-body-secondary") }
                        }
                    },
                ),
            )
        } else {
            emptyList()
        }

private fun pendingApplicationLabel(application: MemberDto): String {
    val friendSince = application.friendSince
    val summary =
        gettext(
            "%1 (%2) -- eingereicht am %3",
            application.displayName,
            application.email,
            formatDate(application.joinedAt),
        )
    // V0.11.0: shows the board that this applicant came from an existing FRIEND account
    // (see MemberDto.friendSince KDoc "load-bearing") -- FriendUpgradePathTest covers the
    // applyForMembership transition itself; this is purely informational.
    return if (friendSince != null) gettext("%1 (Freund-Konto seit %2)", summary, friendSince) else summary
}

private fun renderPendingApplicationActions(
    actionsContainer: Container,
    application: MemberDto,
    chapters: List<RegionalChapterRefDto>,
    onChanged: () -> Unit,
) {
    val actionsRow = actionsContainer.hPanel(spacing = 8)
    val approveButton = actionsRow.button(tr("Annehmen"), style = ButtonStyle.SUCCESS)
    approveButton.onClick {
        AppScope.launch {
            // Welle V1.9.14: RegionalChapterRequiredException is possible here when the enforcement
            // flag is on and no chapter is assigned yet -- regionalChapterGuarded (not guarded) shows
            // the right toast for that case too.
            val result = regionalChapterGuarded { rpcService<IRegistrationService>().approveApplication(application.id) }
            if (result != null) {
                notifySuccess(gettext("%1 wurde aufgenommen.", application.displayName))
                onChanged()
            }
        }
    }
    val rejectButton = actionsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)
    rejectButton.onClick {
        rejectApplicationDialog(application.displayName) { reason ->
            AppScope.launch {
                val result = guarded { rpcService<IRegistrationService>().rejectApplication(application.id, reason) }
                if (result != null) {
                    notifyInfo(gettext("%1 wurde abgelehnt.", application.displayName))
                    onChanged()
                }
            }
        }
    }
    // Welle V1.9.14 -- only offered when chapters exist at all AND the caller may assign one for
    // this row's role/status (same peer-protection predicate the roster editor uses).
    if (chapters.isNotEmpty() &&
        canAssignChapterOf(AppState.session?.role, AppState.session?.memberId, application.role, application.status, false, application.id)
    ) {
        val assignButton = actionsRow.button(tr("Landesverband zuordnen"), style = ButtonStyle.OUTLINESECONDARY)
        assignButton.onClick {
            // Security audit follow-up (untrusted-text sanitization gaps): application.displayName is a member
            // display name, free text -- Modal.caption is a widget-content sink, sanitize the composed result.
            val modal = Modal(caption = sanitizeUntrustedI18nText(gettext("Landesverband für %1", application.displayName)))
            val legendGroup = LegendGroup()
            renderChapterAssignmentSection(modal, application.id, application.regionalChapterId, chapters, legendGroup) {
                modal.hide()
                onChanged()
            }
            modal.addButton(Button(tr("Schließen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
            modal.show()
        }
    }
}

/** Reject requires a non-blank reason -- see `IRegistrationService.rejectApplication` KDoc, a real
 * modal input rather than a bare confirm, since [confirmDialog] has no input field of its own. */
internal fun rejectApplicationDialog(
    applicantName: String,
    onConfirm: (String) -> Unit,
) {
    // Security audit follow-up (untrusted-text sanitization gaps): applicantName is a member display name, free
    // text -- Modal.caption is a widget-content sink like div/span/p, sanitize the whole composed result.
    val modal = Modal(caption = sanitizeUntrustedI18nText(gettext("Antrag von %1 ablehnen", applicantName)))
    modal.p(tr("Bitte geben Sie einen Ablehnungsgrund an (wird beim Mitglied gespeichert)."))
    // Formular-Grammatik (V1.4.29): das früher UNBESCHRIFTETE Pflichtfeld hat jetzt ein Label, einen Feldfehler und einen
    // Fokus dorthin. Die Serverregel (nicht leer) bleibt unverändert; die Regel hier spiegelt sie nur früher.
    val form = modal.lapisForm()
    val reasonField =
        form.textAreaField(
            label = tr("Begründung"),
            rows = 3,
            required = true,
            requiredMessage = gettext("Bitte einen Grund angeben."),
        )
    form.finish()
    val rejectButton = Button(tr("Ablehnen"), style = ButtonStyle.DANGER)
    rejectButton.onClick {
        if (!form.validateAndReport()) return@onClick
        val reason = reasonField.value.trim()
        modal.hide()
        onConfirm(reason)
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(rejectButton)
    modal.show()
}

// ── Welle V1.2.12 -- privilegiertes Roster + vollständige Bearbeitung ──────────────────────────

/**
 * Client-side filter/sort/paging state for [renderMemberRoster] -- bundled into one data class so
 * "reload the same page with the filter/search/offset preserved" after a save (Jobs/Atkinson
 * review point: never drop the operator back to page 1 / an unfiltered view just because they
 * edited one row) is a single `copy()`, not five scattered `var`s to keep in sync by hand.
 */
internal data class RosterState(
    val search: String = "",
    val statuses: Set<MemberStatus> = emptySet(),
    val sort: MemberAdminSort = MemberAdminSort.NAME_ASC,
    val offset: Int = 0,
    /** Welle V1.9.14 -- see [ChapterFilter]. */
    val chapterFilter: ChapterFilter = ChapterFilter.All,
)

private val STATUS_CHIPS: List<MemberStatus?> =
    listOf(null, MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED)

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- the roster's chapter
 * filter, kept as a closed set rather than a bare `String?` so [toQueryFields] can guarantee
 * `regionalChapterId`/`unassignedOnly` are NEVER both set at once (the server throws
 * `BadRequestException` if they are, see `MemberAdminQuery.regionalChapterId` KDoc).
 */
internal sealed interface ChapterFilter {
    data object All : ChapterFilter

    data object Unassigned : ChapterFilter

    data class Chapter(
        val id: String,
    ) : ChapterFilter
}

/** `(regionalChapterId, unassignedOnly)` -- never both set. */
internal fun ChapterFilter.toQueryFields(): Pair<String?, Boolean> =
    when (this) {
        ChapterFilter.All -> null to false
        ChapterFilter.Unassigned -> null to true
        is ChapterFilter.Chapter -> id to false
    }

/** Sentinel select value for [ChapterFilter.Unassigned] -- never collides with a real chapter UUID. */
internal const val CHAPTER_FILTER_UNASSIGNED_VALUE = "__unassigned__"

/** Parses a `selectField` value into a [ChapterFilter]: `""` -> [ChapterFilter.All], the sentinel -> [ChapterFilter.Unassigned], else a chapter id. */
internal fun chapterFilterFromSelectValue(value: String?): ChapterFilter =
    when {
        value.isNullOrBlank() -> ChapterFilter.All
        value == CHAPTER_FILTER_UNASSIGNED_VALUE -> ChapterFilter.Unassigned
        else -> ChapterFilter.Chapter(value)
    }

/** Extracted from [renderMemberRoster]'s inline `load` so [ChapterFilterTest] can cover it without a DOM. */
internal fun rosterQuery(state: RosterState): MemberAdminQuery {
    val (chapterId, unassignedOnly) = state.chapterFilter.toQueryFields()
    return MemberAdminQuery(
        search = state.search.ifBlank { null },
        statuses = state.statuses,
        sort = state.sort,
        offset = state.offset,
        regionalChapterId = chapterId,
        unassignedOnly = unassignedOnly,
    )
}

/**
 * Replaces the old `renderMemberDirectory` -- that function's own KDoc ("dafür existiert aktuell
 * keine privilegierte Leseschnittstelle") is, as of this wave, no longer true:
 * `IMemberService.listMembersForAdministration` is exactly that interface. BOARD/ADMIN/TREASURER
 * (Welle V1.4.4.4 widened `listMembersForAdministration`'s own server-side gate from `isPrivileged`
 * to also admit TREASURER, purely so a Schatzmeister can reach the "Mitgliedschaftsstufe" section --
 * see this file's class KDoc and [canEditMembershipTierOf]) -- server-side re-enforces this
 * independently, this is not the only gate.
 */
private fun renderMemberRoster(
    root: SimplePanel,
    chapters: List<RegionalChapterRefDto>,
) {
    root.h2(tr("Mitgliederverzeichnis")) { addCssClass("h5") }

    var state = RosterState()
    // Set by a sort click, consumed by the render of THAT click's load: hands the keyboard focus back to
    // the header button of the clicked column (the re-render destroys the one the user just pressed).
    // Expires when that load fails, is superseded or renders no table (see [SortFocusRequest]).
    val sortFocus = SortFocusRequest()

    val filterRow = root.hPanel(spacing = 8)
    val searchInput = filterRow.text(label = tr("Suche nach Name, E-Mail oder Personennummer"))
    // Welle V1.9.14 -- only rendered once chapters actually exist (mirrors every other chapter-UI
    // element's `chapters.isNotEmpty()` gate, plan §1 P5).
    val chapterFilterSelect =
        if (chapters.isNotEmpty()) {
            filterRow.select(
                label = tr("Landesverband"),
                options =
                    listOf("" to tr("Alle Landesverbände"), CHAPTER_FILTER_UNASSIGNED_VALUE to tr("Nicht zugeordnet")) +
                        untrustedOptions(chapters.map { it.id to it.name }),
                value = "",
            )
        } else {
            null
        }
    val chipsRow = root.hPanel(spacing = 6)

    lateinit var section: DataSection
    lateinit var chipButtons: Map<MemberStatus?, Button>
    lateinit var pagerRow: SimplePanel

    fun refresh() {
        // Hidden until the new page has settled: a pager for the previous page next to a new "loading"
        // state would offer navigation from an offset that no longer matches what is shown.
        pagerRow.hide()
        sortFocus.beginLoad()
        section.reload()
    }

    fun renderPager(page: MemberAdminPageDto) {
        pagerRow.removeAll()
        if (page.totalCount == 0) return // the section already says "no members" / "no match"
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

    val chapterFilterTermLabel: (ChapterFilter) -> String? = { filter ->
        when (filter) {
            ChapterFilter.All -> null
            ChapterFilter.Unassigned -> tr("Nicht zugeordnet")
            is ChapterFilter.Chapter -> chapters.firstOrNull { it.id == filter.id }?.name
        }
    }

    section =
        root.dataSection<MemberAdminPageDto>(
            emptyText = tr("Noch keine Mitglieder vorhanden."),
            filterTerm = { rosterFilterTerm(state.search, state.statuses, chapterFilterTermLabel(state.chapterFilter)) },
            noMatchText = { term -> gettext("Kein Mitglied passt zu \"%1\".", term) },
            isEmpty = { it.rows.isEmpty() },
            onSettled = { page ->
                sortFocus.settled(rendersTable = page != null && page.rows.isNotEmpty())
                // Also for an empty page: the status counters must reflect an empty result, too.
                if (page != null) {
                    chipButtons.forEach { (status, button) ->
                        val count = if (status == null) page.statusCounts.values.sum() else page.statusCounts[status] ?: 0
                        // gettext (not `"${tr("Alle")} ($count)"`): a `tr()` marker inside a composed string looks up the
                        // whole composed text in the catalog and finds nothing, so "Alle" stayed German (audit minor 2).
                        button.text = if (status == null) gettext("Alle (%1)", count) else "${memberStatusLabel(status)} ($count)"
                    }
                    renderPager(page)
                }
            },
            load = { guarded { rpcService<IMemberService>().listMembersForAdministration(rosterQuery(state)) } },
            render = { panel, page ->
                panel.dataTable(
                    columns = rosterColumns(chapters.isNotEmpty()),
                    rows = page.rows,
                    sort = state.sort.toSortState(),
                    onSort = { clicked ->
                        sortFocus.request((clicked ?: state.sort.toSortState()).key)
                        state = state.withSortClick(clicked)
                        refresh()
                    },
                    sortOptions = ROSTER_SORT_OPTIONS,
                    actions = { actions, row -> renderRosterActions(actions, row, chapters, onChanged = { refresh() }) },
                    focusSortKey = sortFocus.takeForRender(),
                )
            },
        )
    pagerRow = root.hPanel(spacing = 8)

    // Review fix (efficiency): KVision's `subscribe` invokes the observer immediately with the
    // field's current value on registration -- same trap `searchInput.subscribe` below already
    // documents and guards against with `isInitialSearchEvent`. Without this guard, EVERY mount of
    // a roster with at least one chapter fired `refresh()` twice (the synthetic first `subscribe`
    // event, then the explicit `refresh()` call at the end of this function): two identical
    // `listMembersForAdministration` round trips. Harmless for correctness (`DataLoadController`'s
    // generation guard already discards the stale one), but avoidable load.
    var isInitialChapterFilterEvent = true
    chapterFilterSelect?.subscribe { value ->
        if (isInitialChapterFilterEvent) {
            isInitialChapterFilterEvent = false
            return@subscribe
        }
        // No debounce (Design-Team decision, same as every other `select` filter): a select change is
        // one discrete event, not a keystroke stream.
        state = state.copy(chapterFilter = chapterFilterFromSelectValue(value), offset = 0)
        refresh()
    }

    chipButtons =
        STATUS_CHIPS.associateWith { status ->
            val isAll = status == null
            val label = if (isAll) tr("Alle") else memberStatusLabel(status)
            val chip = chipsRow.button(label, style = ButtonStyle.OUTLINESECONDARY)
            chip.onClick {
                state = state.copy(statuses = if (isAll) emptySet() else setOf(status), offset = 0)
                refresh()
            }
            chip
        }

    // 300ms debounce -- no search button (Jobs/Raskin review: one fewer click for the single most
    // frequent action on this screen). Same `.subscribe { }` reactive idiom `ConfirmDialog.kt`'s
    // reason field already establishes, just debounced.
    // KVision's `subscribe` invokes the observer immediately with the field's current value on
    // registration (not just on subsequent user input) -- without this guard, that synthetic
    // first call would ALSO register a 300ms debounce that fires `refresh()` a second time, on
    // top of the explicit `refresh()` call below: two identical `listMembersForAdministration`
    // roundtrips per screen mount plus a second, delayed re-render. Skipping just that first,
    // synthetic invocation keeps every real user keystroke debounced as before.
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

/**
 * The roster state after a sort-header click: the new sort, back on the first page (a new order makes the
 * old offset meaningless). `null` cannot come from the roster's [ROSTER_SORT_OPTIONS] (`allowUnsorted =
 * false`, and `MemberAdminSort` has no "unsorted" value) -- it keeps the current sort defensively.
 */
internal fun RosterState.withSortClick(clicked: SortState?): RosterState =
    copy(sort = (clicked ?: sort.toSortState()).toMemberAdminSort(), offset = 0)

/** First click on the "Beitritt" column shows the newest members first; every other column starts ascending. */
private val ROSTER_SORT_OPTIONS =
    SortOptions(
        allowUnsorted = false,
        firstDirection = { key -> if (key == ROSTER_SORT_JOINED) SortDirection.DESC else SortDirection.ASC },
    )

private const val ROSTER_SORT_NAME = "name"
private const val ROSTER_SORT_JOINED = "joined"

/** The columns of the roster table / card list; Name is the card title. [showChapter] mirrors every other chapter-UI element's `chapters.isNotEmpty()` gate (Welle V1.9.14). */
private fun rosterColumns(showChapter: Boolean): List<DataColumn<MemberAdminRowDto>> =
    listOf<DataColumn<MemberAdminRowDto>>(
        DataColumn(
            title = tr("Name"),
            primary = true,
            sortKey = ROSTER_SORT_NAME,
            cell = { container, row -> container.renderRosterName(row) },
        ),
        textColumn(title = tr("E-Mail")) { row: MemberAdminRowDto -> row.email },
        DataColumn(title = tr("Status"), cell = { container, row -> container.renderRosterStatus(row) }),
        DataColumn(title = tr("Rolle"), cell = { container, row -> container.renderRosterRole(row) }),
    ) +
        if (showChapter) {
            listOf(
                DataColumn(
                    title = tr("Landesverband"),
                    cell = { container, row ->
                        val name = row.regionalChapterName
                        if (name !=
                            null
                        ) {
                            container.untrustedSpan(name)
                        } else {
                            container.span(tr("nicht zugeordnet")) { addCssClass("text-body-secondary") }
                        }
                    },
                ),
            )
        } else {
            emptyList()
        } +
        listOf(
            DataColumn(
                title = tr("Beitritt"),
                numeric = true,
                sortKey = ROSTER_SORT_JOINED,
                cell = { container, row -> container.cellText(row.joinedAt.toString()) },
            ),
        )

/**
 * The term the "no match" sentence quotes: the search text, or -- with only a status chip active -- that
 * chip's label, or -- with a chapter filter active and no other filter -- that chapter's label (Welle
 * V1.9.14, [chapterFilterLabel]). `null` when nothing filters (an empty page then means "no members yet").
 * [chapterFilterLabel] defaults to `null` so every pre-existing two-argument call site stays
 * source-compatible.
 */
internal fun rosterFilterTerm(
    search: String,
    statuses: Set<MemberStatus>,
    chapterFilterLabel: String? = null,
): String? =
    search.trim().takeIf { it.isNotEmpty() }
        ?: statuses.singleOrNull()?.let { memberStatusLabel(it) }
        ?: chapterFilterLabel

/** `MemberAdminSort` <-> the generic [SortState] of `dataTable` (all four values, both directions). */
internal fun MemberAdminSort.toSortState(): SortState =
    when (this) {
        MemberAdminSort.NAME_ASC -> SortState(key = ROSTER_SORT_NAME, direction = SortDirection.ASC)
        MemberAdminSort.NAME_DESC -> SortState(key = ROSTER_SORT_NAME, direction = SortDirection.DESC)
        MemberAdminSort.JOINED_ASC -> SortState(key = ROSTER_SORT_JOINED, direction = SortDirection.ASC)
        MemberAdminSort.JOINED_DESC -> SortState(key = ROSTER_SORT_JOINED, direction = SortDirection.DESC)
    }

internal fun SortState.toMemberAdminSort(): MemberAdminSort {
    val ascending = direction == SortDirection.ASC
    return when (key) {
        ROSTER_SORT_JOINED -> if (ascending) MemberAdminSort.JOINED_ASC else MemberAdminSort.JOINED_DESC
        else -> if (ascending) MemberAdminSort.NAME_ASC else MemberAdminSort.NAME_DESC
    }
}

/** Name column: display name plus the (BOARD/ADMIN-only) family badge -- also the title of the narrow card. */
private fun Container.renderRosterName(row: MemberAdminRowDto) {
    untrustedSpan(row.displayName)
    // Welle V1.4.4.4 "Familienmitgliedschaften" -- unaufdringliches Badge, NUR wenn eine
    // Familienverknüpfung existiert (kein Pixel für ein Mitglied ohne Familie). Kein
    // eigener Knopf/Schalter -- der Badge selbst öffnet die gefilterte Familienansicht.
    // Zusätzlich BOARD/ADMIN-only (Review-Fix, Regression): Ziel-Route
    // `Routes.MEMBER_FAMILIES` (Routing.kt) und der Server (`MemberFamilyService.
    // FAMILY_ROLES`) verlangen beide BOARD/ADMIN -- die V1.4.4.4-Erweiterung von
    // `Routes.MEMBERS` auf TREASURER erlaubt einem Schatzmeister nur das Roster selbst zu
    // sehen, nicht die Familienverwaltung dahinter. Ohne dieses Gate wuerde JEDE Zeile mit
    // Familienbezug einem TREASURER einen Link anbieten, den Route-Guard und Server
    // ohnehin ablehnen (Hausregel: kein Client-Angebot für eine vom Server ohnehin
    // abgelehnte Aktion).
    val familyId = row.familyId
    if (familyId != null && AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        // `link` (not a manual onClick) -- the app's hash-based routing already intercepts
        // href="#..." navigation, same idiom every other cross-screen link in this codebase
        // uses (see e.g. MemberHonorsScreen's "Alle Ehrungen anzeigen").
        div { addCssClasses("small mt-1") }.link("", url = "#${memberFamiliesRoute(familyId)}") {
            addCssClass("text-decoration-none")
            typeBadge(
                familyRosterBadgeText(row.familyName.orEmpty(), row.familyRole),
                familyRoleBadgeColor(row.familyRole ?: FamilyMemberRole.DEPENDENT),
            )
        }
    }
}

/** Status column: role-coloured status badge plus the recorded date of death, if any. */
private fun Container.renderRosterStatus(row: MemberAdminRowDto) {
    memberStatusRoleBadge(row.status)
    // Welle V1.4.4.5 -- zeigt hinter dem "Verstorben"-Badge, ob (und ggf. welches)
    // Sterbedatum erfasst ist.
    deceasedDateNote(row.status, row.dateOfDeath)?.let { note ->
        span(note) { addCssClasses("text-muted small ms-1") }
    }
}

/** Role column: the account role badge, or a muted note for a CSV-imported member without account. */
private fun Container.renderRosterRole(row: MemberAdminRowDto) {
    val role = row.role
    if (role != null) {
        accountRoleBadge(role)
    } else {
        span(tr("— (kein Konto)")) {
            title = tr("Kein Login-Konto -- CSV-importiertes Mitglied ohne Account-Zeile.")
            addCssClass("text-muted")
        }
    }
}

/**
 * Actions of one roster row. [actionsCell] is the actions cell of the table row or, in the narrow card
 * list, the card's action group -- the buttons are identical in both (Welle V1.4.25: `dataTable` owns the
 * container, this function owns the content, unchanged from the former `renderMemberRosterRow`).
 */
private fun renderRosterActions(
    actionsCell: Container,
    row: MemberAdminRowDto,
    chapters: List<RegionalChapterRefDto>,
    onChanged: () -> Unit,
) {
    // GitHub issue #1 -- icon instead of text, so the actions column stays narrow at any table
    // width; `title` is set unconditionally right below and is KVision's own `Widget.title`
    // property (not a raw DOM write), so no `###KvI18nS###` marker-leak risk -- see
    // `ConferenceScreen.kt`'s own KDoc on that bug class for why the distinction matters.
    val editButton = actionsCell.tableActionButton("fas fa-pen", tr("Bearbeiten"), ButtonStyle.OUTLINEPRIMARY)
    val callerRole = AppState.session?.role
    val callerMemberId = AppState.session?.memberId
    if (row.anonymized) {
        editButton.disabled = true
        editButton.tableActionTooltip(tr("DSGVO-gelöscht"))
    } else if (!hasAnyEditableSectionFor(callerRole, callerMemberId, row, chaptersExist = chapters.isNotEmpty())) {
        // Regression fix (Review Runde 3): before the per-section gating in openMemberEditorDialog
        // existed, "Stammdaten" was rendered UNCONDITIONALLY, so the modal could never be empty.
        // Now that all five sections are individually gated (Peer-Schutz), a BOARD caller on an
        // escalated-role target (or their OWN row, which is itself BOARD/ADMIN/TREASURER-scoped)
        // can hit a state where NONE of the five predicates allow anything -- opening the dialog
        // would show only a title and a "Schließen" button. Same house rule this file's own KDoc
        // on ESCALATED_ROLES already states: "the client does not OFFER an action the server's
        // peer-protection rejects anyway" -- consequently applied here to the button itself, not
        // just to the sections inside a dialog the caller would otherwise be free to open.
        // Review fix (Welle V1.4.4.4, MAJOR finding): `hasAnyEditableSectionFor` did NOT
        // originally include `canEditMembershipTierOf` -- a BOARD caller on an escalated-role
        // target (TREASURER/BOARD/ADMIN, including their own row) with a removable tier
        // (`row.membershipTierId != null`) has all four OTHER predicates false, so the button
        // was wrongly disabled even though `canEditMembershipTierOf` alone would allow the
        // "Mitgliedschaftsstufe entfernen" action -- see [canEditMembershipTierOf] KDoc.
        editButton.disabled = true
        editButton.tableActionTooltip(
            tr(
                "Keine Bearbeitung möglich -- Peer-Schutz: Vorstand darf Vorstands-/Schatzmeister-/" +
                    "Admin-Konten (auch das eigene) nicht bearbeiten, das ist Admin vorbehalten.",
            ),
        )
    } else {
        editButton.onClick { openMemberEditorDialog(row, onChanged, chapters) }
    }

    // Welle V1.4.4.1 "Beitragshistorie" -- der erste von zwei Einstiegen in
    // MemberFinancialHistoryScreen.kt (der zweite ist der Link in ContributionsScreen.kt für
    // die eigene Historie). Seit Welle V1.4.4.4 erreicht ein TREASURER `/members` tatsächlich
    // (Routing.kt lässt TREASURER inzwischen zusätzlich zu BOARD/ADMIN zu, siehe dieser Datei
    // Klassen-KDoc) -- der Rollen-Check hier ist also kein reines Zukunfts-Dokument mehr,
    // sondern der tatsächlich wirksame Gate für diesen Knopf.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        val financesButton = actionsCell.tableActionButton("fas fa-receipt", tr("Beitragshistorie"))
        financesButton.onClick { navigateTo(memberFinancesRoute(row.id)) }
    }

    // Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- der zweite von zwei
    // Einstiegen in MemberHonorsScreen.kt (der erste ist die board-weite Liste unter
    // `Routes.MEMBER_HONORS` ohne Parameter). Anders als `financesButton` oben bleibt dieser
    // Knopf bewusst BOARD/ADMIN-only: Ziel-Route `Routes.MEMBER_HONORS` (Routing.kt) und der
    // Server (`MemberHonorService.HONOR_READ_WRITE_ROLES`) verlangen beide weiterhin BOARD/ADMIN,
    // die V1.4.4.4-Erweiterung von `Routes.MEMBERS`/`updateMemberMembershipTier` auf TREASURER
    // hat daran nichts geändert -- ein TREASURER erreicht `/members` seit Welle V1.4.4.4 zwar
    // tatsächlich, aber NICHT die Ehrungsverwaltung. Review-Fix (Regression): dieser Knopf war
    // versehentlich auf TREASURER erweitert worden, obwohl weder Route noch Server das erlauben
    // (Hausregel: kein Client-Angebot für eine vom Server ohnehin abgelehnte Aktion).
    // Anders als `financesButton`
    // (der KEIN `row.anonymized`-Gate hat, weil `MemberFinancialHistoryScreen` selbst mit einem
    // "DSGVO-gelöscht"-Badge umgehen kann) wird dieser Knopf für ein anonymisiertes Mitglied
    // deaktiviert -- `MemberHonorsScreen` hat keine eigene Anzeige-Logik für einen
    // anonymisierten Zielmember (Welle-Plan §13 "S5").
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        val honorsButton = actionsCell.tableActionButton("fas fa-medal", tr("Ehrungen"))
        if (row.anonymized) {
            honorsButton.disabled = true
            honorsButton.tableActionTooltip(tr("DSGVO-gelöscht"))
        } else {
            honorsButton.onClick { navigateTo(memberHonorsRoute(row.id)) }
        }
    }

    // Welle "Digitaler Mitgliedsausweis (PDF)" -- fuenfter Einstieg der Aktionsspalte,
    // BOARD/ADMIN (NICHT TREASURER: ein Mitgliedsausweis ist ein Identitaets-, kein
    // Finanzdokument -- dieselbe Stufe, die `registerMemberCardRoutes` serverseitig erzwingt).
    // Gleiches Icon-Knopf-Muster wie die vier Knoepfe darueber (Icon + Pflicht-Tooltip, siehe
    // `DataScreenLayout.tableActionButton`).
    //
    // Nur fuer Mitglieder mit ausweisfaehigem Status sichtbar -- der Server lehnt alles andere
    // mit 409 ab (`MemberCardEligibility`), und die Hausregel dieser Datei ist ausdruecklich:
    // kein Client-Angebot fuer eine vom Server ohnehin abgelehnte Aktion. Deshalb wird der
    // Knopf hier NICHT deaktiviert angeboten, sondern gar nicht erst gerendert -- anders als
    // bei `row.anonymized`, wo ein sichtbarer, deaktivierter Knopf mit Begruendung dem
    // Vorstand die Ursache erklaert, waehrend "Gast hat keinen Mitgliedsausweis" keine
    // Erklaerung braucht.
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN) && row.status in MemberStatusSets.ORGANIZATION_MEMBER) {
        val cardButton = actionsCell.tableActionButton("fas fa-id-card", tr("Mitgliedsausweis ausstellen"))
        if (row.anonymized) {
            cardButton.disabled = true
            cardButton.tableActionTooltip(tr("DSGVO-gelöscht"))
        } else {
            cardButton.onClick {
                confirmDialog(
                    title = tr("Neuen Mitgliedsausweis ausstellen"),
                    message =
                        gettext(
                            "Für %1 wird ein neuer Ausweis ausgestellt und als PDF heruntergeladen. Ein zuvor " +
                                "ausgestellter Ausweis dieses Mitglieds verliert damit seine Gültigkeit.",
                            row.displayName,
                        ),
                    confirmLabel = tr("Ausstellen und herunterladen"),
                ) {
                    MemberCardHttp.submitCardPdfDownload(row.id)
                }
            }
        }
    }

    // Welle V1.4.9 "Admin-Passwort-Reset" -- vierter Einstieg der Aktionsspalte, ADMIN-exklusiv.
    // BEWUSST NICHT als siebter Abschnitt im Editor-Dialog und BEWUSST NICHT in
    // hasAnyEditableSectionFor aufgenommen (die ODER-Kette bleibt bei sechs): ein Zugriffs-Akt
    // ist kategorial etwas anderes als Stammdatenpflege, und diese Kette hat in dieser Datei
    // bereits zweimal Regressionen produziert (V1.4.4.4-MAJOR, Review Runde 3). Ein eigenes
    // Prädikat berührt sie nicht. Icon `fa-key`, nicht `fa-user-lock`: ein Schloss hieße
    // "gesperrt" -- das ist der Zustand DANACH gerade nicht.
    // V1.7.2 sub-wave 2b: not rendered at all in Keycloak mode -- an admin-initiated LOCAL password
    // reset is meaningless for a member whose login is Keycloak-managed (see `MemberPasswordResetDialog.kt`
    // KDoc "V1.7.2 sub-wave 2b"). `AppState.hasRole(ADMIN)` alone left this button ENABLED in Keycloak
    // mode -- it opened a dialog that could still set an unreachable local password.
    if (AppState.hasRole(AccountRole.ADMIN) && AppState.session?.keycloakMode != true) {
        val accessButton =
            actionsCell.tableActionButton("fas fa-key", tr("Zugang zurücksetzen"), ButtonStyle.OUTLINEWARNING)
        val block = passwordResetBlockReason(callerRole, callerMemberId, row)
        if (block != null) {
            accessButton.disabled = true
            accessButton.tableActionTooltip(block)
        } else {
            accessButton.onClick { openMemberPasswordResetDialog(row, onChanged) }
        }
    }

    // V1.7.2 sub-wave 2b -- self-gated (returns immediately outside Keycloak mode / for a non-ADMIN
    // caller), see KeycloakLinkScreen.kt KDoc "renderKeycloakUnlinkAction".
    renderKeycloakUnlinkAction(actionsCell, row, onChanged)
}

/**
 * Editor-Modal, drei unabhängig gespeicherte Abschnitte (Stammdaten/Status/Rolle) -- Muster:
 * `rejectApplicationDialog` in dieser Datei. Kein gemeinsamer "Speichern"-Knopf, weil die drei
 * Abschnitte drei unterschiedlich autorisierte, unabhängige RPCs sind (siehe `IMemberService`).
 */
internal fun openMemberEditorDialog(
    row: MemberAdminRowDto,
    onChanged: () -> Unit,
    chapters: List<RegionalChapterRefDto> = emptyList(),
) {
    val callerRole = AppState.session?.role
    val callerMemberId = AppState.session?.memberId
    // Security audit follow-up (untrusted-text sanitization gaps): row.displayName is a member display name,
    // free text -- Modal.caption is a widget-content sink like div/span/p, sanitize the whole composed result.
    val modal = Modal(caption = sanitizeUntrustedI18nText(gettext("%1 bearbeiten", row.displayName)))
    // Sechs Formulare in einem Modal: die Legende "* Pflichtfeld" steht nur einmal (beim ersten Formular, das sie braucht).
    val legendGroup = LegendGroup()

    // Formular-Grammatik (V1.4.29, W4b): jeder Abschnitt ist ein EIGENES Formular mit eigener Knopfzeile -- die Abschnitte sind
    // unabhängig gespeicherte, unterschiedlich autorisierte RPCs. R28 (genau ein `PRIMARY`) gilt deshalb je FORMULAR, nicht je
    // Modal. Die Knöpfe stehen im Modal-KÖRPER (nicht in der Fußleiste), also `buttons()`, nicht `finish()`. Fehler stehen am
    // Feld; die sechs handgebauten `text-danger`-Boxen sind entfallen, Serverfehler meldet `memberAdminGuarded`.

    // ── Stammdaten ──
    if (canEditCoreDataOf(callerRole, row)) {
        modal.h2(tr("Stammdaten")) { addCssClass("h6") }
        val form = modal.lapisForm(legendGroup)
        val nameField = form.textField(label = tr("Name"), value = row.displayName, required = true)
        val emailField =
            form.textField(
                label = tr("E-Mail"),
                type = InputType.EMAIL,
                value = row.email,
                required = true,
                rule = { FormRules.email(value = it) },
            )
        val saveCoreDataButton = Button(tr("Stammdaten speichern"), style = ButtonStyle.PRIMARY)
        form.buttons(primary = saveCoreDataButton)
        saveCoreDataButton.onClick {
            form.submit(saveCoreDataButton) {
                val name = nameField.value.trim()
                val email = emailField.value.trim()
                val result = memberAdminGuarded { rpcService<IMemberService>().updateMemberCoreData(row.id, name, email) }
                if (result != null) {
                    notifySuccess(tr("Stammdaten gespeichert."))
                    modal.hide()
                    onChanged()
                }
            }
        }
    }

    if (canChangeStatusOf(callerRole, callerMemberId, row)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Status")) { addCssClass("h6") }
        val form = modal.lapisForm(legendGroup)
        val targets = MemberStatusTransitions.allowedTargets(row.status).toList()
        val statusField =
            form.selectField(
                label = tr("Neuer Status"),
                options = targets.map { it.name to memberStatusLabel(it) },
                value = targets.firstOrNull()?.name,
                required = true,
            )
        // Bug fix (live user report after V1.2.12 deploy): addCssClass() only ever adds a single
        // literal token (classList.add() throws InvalidCharacterError on a space-containing
        // string) -- addCssClass("alert alert-secondary") crashed uncaught inside this onClick
        // handler, aborting openMemberEditorDialog() before modal.show() ever ran. Use
        // addCssClasses() (see CssClasses.kt) for any multi-class string.
        val consequenceBox = form.panel.div { addCssClasses("alert alert-secondary") }
        val warningBox =
            form.panel.div {
                addCssClasses("alert alert-warning")
                hide()
            }

        // Welle V1.4.4.5 -- nur sichtbar, wenn der Zielstatus "Verstorben" ist. BEWUSST NICHT mit
        // todayLocalDate() vorbefüllt (anders als BoardMembershipScreen.todayIso()): ein
        // vorbefülltes heutiges Datum in einem Sterbedatumsfeld ist eine Behauptung, die die
        // Software aufstellt und der Mensch nur noch bestätigt -- übersieht er das Feld, hat dieses
        // System aus eigenem Antrieb einen Todestag erfunden und in eine GoBD-unveränderliche
        // Buchhaltung geschrieben. Leer. Immer leer.
        fun deceasedSelected(): Boolean = statusField.value == MemberStatus.DECEASED.name
        val deathDateField =
            form.textField(
                label = tr("Sterbedatum"),
                hint = "${gettext(
                    "Beispiel: 2026-03-14.",
                )} ${gettext("Leer lassen, wenn das genaue Datum noch nicht feststeht — später korrigierbar.")}",
                // Ein ausgeblendetes Feld darf das Absenden nicht durch einen unsichtbaren Fehler blockieren.
                rule = { if (deceasedSelected()) deathDateCheck(value = it) else FieldCheck.Ok },
            )
        val reasonField = memberReasonField(form)
        // Bug fix (live user report): hPanel is a non-wrapping flex row by default -- four chip
        // buttons together exceed the modal's width, so the last one got clipped -- "flex-wrap"
        // (same fix as ConferenceScreen.kt's controlsRow).
        val statusChipsRow = form.panel.hPanel(spacing = 6) { addCssClasses("flex-wrap") }
        listOf(
            tr("Austrittserklärung liegt vor"),
            tr("Sterbefall gemeldet"),
            tr("Datenkorrektur CSV-Import"),
            tr("Sonstiges"),
        ).forEach { suggestion ->
            statusChipsRow.button(suggestion, style = ButtonStyle.OUTLINESECONDARY).onClick {
                // Über das Feld, nie am Control vorbei: ein stehender "zu kurz"-Fehler muss am neuen Wert verschwinden.
                reasonField.setValue(resolvedAttributeText(suggestion))
                reasonField.validate(force = false)
            }
        }

        fun refreshConsequence() {
            val target = statusField.value.takeIf { it.isNotBlank() }?.let { MemberStatus.valueOf(it) } ?: return
            consequenceBox.content =
                statusChangeConsequence(row.status, target, hasAccount = row.role != null, familyRole = row.familyRole)
            // Welle V1.4.4.5 -- das Sterbedatumsfeld erscheint nur, wenn der Zielstatus DECEASED ist.
            deathDateField.setVisible(target == MemberStatus.DECEASED)
            if (MemberStatusTransitions.requiresAdmin(row.status)) {
                warningBox.content =
                    tr(
                        "Datenkorrektur -- diese Person ist im System als verstorben geführt. Ein widerrufenes " +
                            "SEPA-Mandat wird dadurch nicht wiederhergestellt.",
                    )
                warningBox.show()
            } else {
                warningBox.hide()
            }
        }
        statusField.subscribe { refreshConsequence() }
        refreshConsequence()

        val statusButtonStyle = if (MemberStatusTransitions.requiresAdmin(row.status)) ButtonStyle.WARNING else ButtonStyle.PRIMARY
        val saveStatusButton = Button(tr("Status ändern"), style = statusButtonStyle)
        form.buttons(primary = saveStatusButton)
        saveStatusButton.onClick {
            form.submit(saveStatusButton) {
                val target = MemberStatus.valueOf(statusField.value)
                val reason = reasonField.value.trim()
                // Welle V1.4.4.5 -- nur relevant, wenn der Zielstatus DECEASED ist; leer bleibt erlaubt
                // ("Datum noch nicht bekannt", siehe Kommentar oben). Die Feldregel hat das Datum bereits geprüft.
                val rawDeathDate = deathDateField.value.trim()
                val deathDate =
                    if (target == MemberStatus.DECEASED && rawDeathDate.isNotEmpty()) LocalDate.parse(rawDeathDate) else null
                val result =
                    memberAdminGuarded { rpcService<IMemberService>().updateMemberStatus(row.id, target, reason, deathDate) }
                if (result != null) {
                    notifySuccess(tr("Status geändert."))
                    modal.hide()
                    onChanged()
                }
            }
        }
    }

    // ── Sterbedatum (Welle V1.4.4.5) ──
    // Datenkorrektur, ADMIN-exklusiv, eigener RPC-Aufruf (correctDateOfDeath). Bewusst getrennt von
    // "Status ändern": updateMemberStatus ist für newStatus == from ein zugesagtes No-op (siehe
    // IMemberService KDoc) und kann eine Datumskorrektur strukturell nicht ausführen. Direkt NACH
    // der Status-Sektion, damit Feld und Begriff optisch an derselben Stelle stehen.
    if (canCorrectDateOfDeathOf(callerRole, callerMemberId, row)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Sterbedatum")) { addCssClass("h6") }
        val form = modal.lapisForm(legendGroup)
        val correctionField =
            form.textField(
                label = tr("Sterbedatum"),
                value = row.dateOfDeath?.toString(),
                hint = "${gettext("Beispiel: 2026-03-14.")} ${gettext("Leer lassen nimmt ein irrtümlich erfasstes Datum zurück.")}",
                rule = { deathDateCheck(value = it) },
            )
        val correctionReason = memberReasonField(form)
        val correctButton = Button(tr("Sterbedatum korrigieren"), style = ButtonStyle.WARNING)
        form.buttons(primary = correctButton)
        correctButton.onClick {
            form.submit(correctButton) {
                val reason = correctionReason.value.trim()
                val raw = correctionField.value.trim()
                val parsedDate = if (raw.isEmpty()) null else LocalDate.parse(raw)
                val result =
                    memberAdminGuarded { rpcService<IMemberService>().correctDateOfDeath(row.id, parsedDate, reason) }
                if (result != null) {
                    notifySuccess(tr("Sterbedatum korrigiert."))
                    modal.hide()
                    onChanged()
                }
            }
        }
    }

    if (canEditRoleOf(callerRole, callerMemberId, row)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Rolle")) { addCssClass("h6") }
        val form = modal.lapisForm(legendGroup)
        val roleOptions = AccountRole.entries.map { it.name to accountRoleLabel(it) }
        val roleField = form.selectField(label = tr("Rolle"), options = roleOptions, value = row.role?.name, required = true)
        val saveRoleButton = Button(tr("Rolle ändern"), style = ButtonStyle.PRIMARY)
        form.buttons(primary = saveRoleButton)
        saveRoleButton.onClick {
            form.submit(saveRoleButton) {
                val newRole = AccountRole.valueOf(roleField.value)
                val result = memberAdminGuarded { rpcService<IMemberService>().updateMemberRole(row.id, newRole) }
                if (result != null) {
                    notifySuccess(tr("Rolle geändert."))
                    modal.hide()
                    onChanged()
                }
            }
        }
    }

    // ── Mitgliedschaftsstufe (Welle V1.4.4.4, umbenannt V1.9.18 von "Beitragstarif") ──
    if (canEditMembershipTierOf(callerRole, callerMemberId, row)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Mitgliedschaftsstufe")) { addCssClass("h6") }
        val form = modal.lapisForm(legendGroup)
        val tierWarning =
            form.panel.div {
                addCssClasses("alert alert-warning")
                hide()
            }

        fun showTierConsequence(assigningRealTier: Boolean) {
            if (assigningRealTier) {
                tierWarning.content =
                    tr(
                        "Ab der nächsten Beitragserzeugung entsteht für dieses Mitglied eine Forderung. Bereits " +
                            "erzeugte Beiträge werden nicht rückwirkend berührt.",
                    )
                tierWarning.show()
            } else {
                tierWarning.hide()
            }
        }

        if (callerRole == AccountRole.ADMIN) {
            val tierField =
                form.selectField(
                    label = tr("Mitgliedschaftsstufe"),
                    options = listOf("" to tr("— beitragsfrei / keine Mitgliedschaftsstufe —")),
                    value = row.membershipTierId ?: "",
                )
            val tierSelect = tierField.control as Select
            AppScope.launch {
                val tiers: List<MembershipTierDto> = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: emptyList()
                tierSelect.options =
                    listOf("" to tr("— beitragsfrei / keine Mitgliedschaftsstufe —")) + tierSelectOptions(tiers, row.membershipTierId)
                // Über das Feld (Select-fähiges `setValue`), dann `validate(force = false)`: ein Fehler kann hier noch nicht stehen.
                tierField.setValue(row.membershipTierId ?: "")
                tierField.validate(force = false)
            }
            tierField.subscribe { value -> showTierConsequence(value.isNotBlank()) }
            val tierReasonField = memberReasonField(form)
            val saveTierButton = Button(tr("Mitgliedschaftsstufe speichern"), style = ButtonStyle.PRIMARY)
            form.buttons(primary = saveTierButton)
            saveTierButton.onClick {
                form.submit(saveTierButton) {
                    val reason = tierReasonField.value.trim()
                    val chosenTierId = tierField.value.takeIf { it.isNotBlank() }
                    val result =
                        memberAdminGuarded { rpcService<IMemberService>().updateMemberMembershipTier(row.id, chosenTierId, reason) }
                    if (result != null) {
                        notifySuccess(tr("Mitgliedschaftsstufe gespeichert."))
                        modal.hide()
                        onChanged()
                    }
                }
            }
        } else if (callerRole == AccountRole.TREASURER) {
            // TREASURER (Welle V1.4.4.4 review fix, MAJOR finding): nur die Zuweisung eines ECHTEN
            // Tarifs -- KEIN "— beitragsfrei / keine Mitgliedschaftsstufe —"-Eintrag, weil der Server
            // (`updateMemberMembershipTier`, `membershipTierId == null`-Zweig) das Entfernen einem
            // TREASURER-Aufrufer verweigert (nur `isPrivileged`, also BOARD/ADMIN) -- siehe
            // [canEditMembershipTierOf] KDoc. Anders als beim ADMIN-Zweig oben ist die Select-Liste
            // deshalb NIE leer wählbar; ein Absenden ohne geladene Tarife meldet der Feldfehler.
            form.panel.p(gettext("Aktuelle Mitgliedschaftsstufe: %1", row.membershipTierName ?: gettext("beitragsfrei")))
            val tierField =
                form.selectField(
                    label = tr("Neue Mitgliedschaftsstufe"),
                    options = emptyList(),
                    required = true,
                    requiredMessage = gettext("Bitte eine Mitgliedschaftsstufe auswählen."),
                )
            val tierSelect = tierField.control as Select
            AppScope.launch {
                val tiers: List<MembershipTierDto> = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: emptyList()
                val offered = tierSelectOptions(tiers, row.membershipTierId)
                tierSelect.options = offered
                tierField.setValue(row.membershipTierId ?: offered.firstOrNull()?.first)
                tierField.validate(force = false)
            }
            tierField.subscribe { value -> showTierConsequence(value.isNotBlank()) }
            val tierReasonField = memberReasonField(form)
            val saveTierButton = Button(tr("Mitgliedschaftsstufe zuweisen"), style = ButtonStyle.PRIMARY)
            form.buttons(primary = saveTierButton)
            saveTierButton.onClick {
                form.submit(saveTierButton) {
                    val chosenTierId = tierField.value
                    val reason = tierReasonField.value.trim()
                    val result =
                        memberAdminGuarded { rpcService<IMemberService>().updateMemberMembershipTier(row.id, chosenTierId, reason) }
                    if (result != null) {
                        notifySuccess(tr("Mitgliedschaftsstufe zugewiesen."))
                        modal.hide()
                        onChanged()
                    }
                }
            }
        } else {
            // BOARD: nur die Schaltfläche "Mitgliedschaftsstufe entfernen" -- siehe canEditMembershipTierOf KDoc.
            form.panel.p(gettext("Aktuelle Mitgliedschaftsstufe: %1", row.membershipTierName ?: gettext("beitragsfrei")))
            val tierReasonField = memberReasonField(form)
            val removeTierButton = Button(tr("Mitgliedschaftsstufe entfernen"), style = ButtonStyle.WARNING)
            form.buttons(primary = removeTierButton)
            removeTierButton.onClick {
                form.submit(removeTierButton) {
                    val reason = tierReasonField.value.trim()
                    val result = memberAdminGuarded { rpcService<IMemberService>().updateMemberMembershipTier(row.id, null, reason) }
                    if (result != null) {
                        notifySuccess(tr("Mitgliedschaftsstufe entfernt."))
                        modal.hide()
                        onChanged()
                    }
                }
            }
        }
    }

    // ── Landesverband (Welle V1.9.14) ──
    if (chapters.isNotEmpty() && canAssignChapterOf(callerRole, callerMemberId, row.role, row.status, row.anonymized, row.id)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Landesverband")) { addCssClass("h6") }
        renderChapterAssignmentSection(modal, row.id, row.regionalChapterId, chapters, legendGroup) {
            modal.hide()
            onChanged()
        }
    }

    // ── Konto anlegen (Welle V1.2.13) ──
    if (canGrantAccountTo(callerRole, row)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Konto anlegen")) { addCssClass("h6") }
        modal.p(
            tr(
                "Legt für dieses Mitglied ein Login-Konto an. Das vorläufige Passwort wird NICHT per " +
                    "E-Mail versendet -- geben Sie es der Person persönlich weiter; sie kann es danach " +
                    "selbst ändern.",
            ),
        )
        grantAccountConsequence(row.status)?.let { hint ->
            modal.div(hint) { addCssClasses("alert alert-secondary") }
        }
        val form = modal.lapisForm(legendGroup)
        // Ein Passwort für einen ANDEREN Menschen: keine Passwortmanager-Angebote (`suppressManagers`), aber aufdeckbar.
        val grantPasswordField =
            form.passwordField(
                label = gettext("Vorläufiges Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH),
                required = true,
                suppressManagers = true,
                reveal = true,
                rule = { FormRules.newPassword(value = it, email = row.email) },
            )
        val grantRoleField =
            form.selectField(
                label = tr("Rolle"),
                options = AccountRole.entries.map { it.name to accountRoleLabel(it) },
                value = AccountRole.MEMBER.name,
                required = true,
            )
        val grantButton = Button(tr("Konto anlegen"), style = ButtonStyle.PRIMARY)
        form.buttons(primary = grantButton)
        grantButton.onClick {
            form.submit(grantButton) {
                // Ein Passwort wird NIE getrimmt: ein getrimmtes wäre ein anderes als das gewählte.
                val password = grantPasswordField.value
                val selectedRole = AccountRole.valueOf(grantRoleField.value)
                val updated =
                    memberAdminGuarded {
                        rpcService<IMemberService>().grantMemberAccount(
                            memberId = row.id,
                            temporaryPassword = password,
                            role = selectedRole,
                        )
                    }
                if (updated != null) {
                    notifySuccess(gettext("Login-Konto für %1 angelegt.", row.displayName))
                    modal.hide()
                    onChanged()
                    reopenMemberEditorAfterHide(updated, onChanged)
                }
            }
        }
    }

    modal.addButton(Button(tr("Schließen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()
}

/** Begründung mit Protokollwirkung (3..1000 Zeichen, spiegelt die Servergrenze) -- ein Feld, fünfmal im Editor-Dialog. */
private fun memberReasonField(form: LapisForm): LapisField =
    form.textAreaField(
        label = tr("Begründung"),
        rows = 2,
        required = true,
        hint = gettext("%1 bis %2 Zeichen.", FormRules.REASON_MIN_LENGTH, FormRules.REASON_MAX_LENGTH),
        rule = { FormRules.reasonText(value = it) },
    )

/**
 * Sterbedatum: ein echtes Kalenderdatum, das nicht in der Zukunft liegt ([DeathDateRules], gleiche Regel wie der Server). Leer
 * ist gültig (die Prüfung sieht nur nicht-leere Werte). Der Text nennt KEIN Format -- das Beispiel steht im Hinweis des Feldes.
 */
internal fun deathDateCheck(value: String): FieldCheck {
    val parsed = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    return if (parsed == null || DeathDateRules.violation(dateOfDeath = parsed, dateOfBirth = null, today = todayLocalDate()) != null) {
        FieldCheck.Invalid(gettext("Bitte ein gültiges Datum angeben, das nicht in der Zukunft liegt."))
    } else {
        FieldCheck.Ok
    }
}

/**
 * Welle V1.2.13 -- öffnet den Editor-Dialog nach einer erfolgreichen Kontoanlage sofort mit der
 * AKTUALISIERTEN Zeile neu, damit der "Rolle"-Abschnitt genau an der Stelle steht, an der eben
 * noch das Anlege-Formular stand (kein "bitte Dialog neu öffnen"-Hinweistext).
 *
 * Bewusst um einen Tick verzögert: `Modal.hide()` startet Bootstraps Fade-Transition (~150 ms);
 * ein `show()` im SELBEN Tick lässt das `hidden.bs.modal`-Event des ALTEN Dialogs nachträglich
 * `body.modal-open` und den `.modal-backdrop` des NEUEN Dialogs abräumen -- der neue Dialog steht
 * dann ohne Backdrop und ohne Scroll-Lock da. Dieselbe `window.setTimeout`-Entkopplung, die
 * [renderMemberRoster]s Such-Debounce in dieser Datei bereits verwendet.
 */
private fun reopenMemberEditorAfterHide(
    row: MemberAdminRowDto,
    onChanged: () -> Unit,
) {
    window.setTimeout({ openMemberEditorDialog(row, onChanged) }, MODAL_FADE_MS)
}

/** Bootstrap-5-Default für `.modal.fade` (`transition: opacity .15s linear`), plus Reserve. */
private const val MODAL_FADE_MS = 250

/**
 * Mirrors the server-internal `network.lapis.cloud.server.security.ESCALATED_ROLES` (JVM-only,
 * not reachable from this JS module) -- purely so the client does not OFFER an action the
 * server's peer-protection (`MemberService.updateMemberCoreData`/`updateMemberStatus`) rejects
 * anyway. The server remains the sole authority; this set only mirrors its boundary for UI gating.
 */
private val ESCALATED_ROLES: Set<AccountRole> = setOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)

/**
 * BOARD/ADMIN, but never for the caller's own row (`MemberService.updateMemberCoreData` has no
 * such restriction for a bare name correction, but its Peer-Schutz check applies here too: a
 * BOARD caller may not edit an ADMIN/BOARD/TREASURER account's core data, including their own).
 */
fun canEditCoreDataOf(
    callerRole: AccountRole?,
    row: MemberAdminRowDto,
): Boolean {
    if (row.anonymized) return false
    if (row.role != null && row.role in ESCALATED_ROLES && callerRole != AccountRole.ADMIN) return false
    return callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
}

/**
 * Whether [openMemberEditorDialog] would render AT LEAST ONE of its sections for [row] -- i.e.
 * whether the "Bearbeiten" button in [renderMemberRosterRow] should be enabled at all. Purely
 * `canEditCoreDataOf(...) || canChangeStatusOf(...) || canEditRoleOf(...) || canGrantAccountTo(...)
 * || canEditMembershipTierOf(...) || canCorrectDateOfDeathOf(...) || (chaptersExist &&
 * canAssignChapterOf(...))` (the sixth predicate, added V1.4.4.5, and the seventh, added V1.9.14,
 * follow the exact same reasoning as the first five), kept as its own named function (rather than
 * inlined at the one call site) so the predicates this depends on stay a single, obviously-in-sync
 * list with the `if`-gates inside [openMemberEditorDialog] -- see this file's ESCALATED_ROLES KDoc
 * for why the client mirrors the server's Peer-Schutz boundary at all: an escalated-role target
 * (or, for a BOARD caller, their OWN row -- BOARD/ADMIN/TREASURER is itself an escalated role) can
 * leave every predicate `false` at once, which without this check would previously open a modal
 * with a title, an empty body, and only a "Schließen" button.
 *
 * Review fix (Welle V1.4.4.4, MAJOR finding): `canEditMembershipTierOf` was originally left OUT of
 * this OR-chain, so a BOARD caller on an escalated-role target (TREASURER/BOARD/ADMIN, including
 * their own row) with a removable tier (`row.membershipTierId != null`) saw a disabled "Bearbeiten"
 * button even though `canEditMembershipTierOf` alone would have allowed the "Mitgliedschaftsstufe entfernen"
 * action -- the newly added "Mitgliedschaftsstufe" section was, for exactly the rows it was built for,
 * unreachable.
 *
 * Security fix (Welle V1.4.4.4 review, MAJOR finding, follow-up): `canEditMembershipTierOf` itself
 * now applies the SAME self-target/Peer-Schutz gate as the other four predicates (see its own
 * KDoc), so the specific scenario the paragraph above describes -- an escalated-role target with a
 * removable tier -- is once again correctly `false` for a non-ADMIN caller across ALL FIVE
 * predicates: the earlier fix had accidentally re-opened exactly the self-/peer-benefit hole this
 * function exists to close, just through the newest of the five sections instead of one of the
 * original four.
 *
 * [chaptersExist] (Welle V1.9.14, default `false`) additionally ORs in [canAssignChapterOf] --
 * default `false` keeps every pre-existing call site byte-for-byte unchanged (plan §2.7 "S3").
 * **Keep the OR-chain and this KDoc in sync** -- this exact chain has already caused two
 * regressions (V1.4.4.4 MAJOR, Review Runde 3), see [canEditMembershipTierOf]'s own KDoc.
 */
fun hasAnyEditableSectionFor(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
    chaptersExist: Boolean = false,
): Boolean =
    canEditCoreDataOf(callerRole, row) ||
        canChangeStatusOf(callerRole, callerMemberId, row) ||
        canEditRoleOf(callerRole, callerMemberId, row) ||
        canGrantAccountTo(callerRole, row) ||
        canEditMembershipTierOf(callerRole, callerMemberId, row) ||
        canCorrectDateOfDeathOf(callerRole, callerMemberId, row) ||
        (chaptersExist && canAssignChapterOf(callerRole, callerMemberId, row.role, row.status, row.anonymized, row.id))

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- mirrors
 * `IRegionalChapterService.assignMemberToChapter`'s own KDoc exactly: BOARD or ADMIN may assign,
 * the target's [MemberStatus] must be in [network.lapis.cloud.shared.domain.RegionalChapterRules
 * .ASSIGNABLE_STATUSES], the row must not be anonymized, and -- the peer-protection boundary --
 * an ESCALATED target role (BOARD/TREASURER/ADMIN) requires ADMIN specifically. [targetMemberId]
 * is unused today (the server has no "not your own row" special case for THIS action, unlike
 * [canEditCoreDataOf]/[canChangeStatusOf]: a BOARD caller assigning their OWN chapter is allowed,
 * as long as their own role -- BOARD -- does not already trip the escalated-role branch above,
 * which it does, so ADMIN is required anyway) -- kept as a parameter purely for documentation and
 * call-site symmetry with the escalated-role predicates above.
 */
fun canAssignChapterOf(
    callerRole: AccountRole?,
    @Suppress("UNUSED_PARAMETER") callerMemberId: String?,
    targetRole: AccountRole?,
    targetStatus: MemberStatus,
    anonymized: Boolean,
    @Suppress("UNUSED_PARAMETER") targetMemberId: String,
): Boolean {
    if (anonymized) return false
    if (targetStatus !in RegionalChapterRules.ASSIGNABLE_STATUSES) return false
    if (targetRole != null && targetRole in ESCALATED_ROLES) return callerRole == AccountRole.ADMIN
    return callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
}

/**
 * The chapter-picker section shared by [openMemberEditorDialog], the pending-applications
 * "Landesverband zuordnen" action and (indirectly, via its own form) `RegistrationScreen.kt`'s
 * optional picker. [onSaved] is called after a successful `assignMemberToChapter` (the caller
 * decides whether that means closing a modal, reloading a section, or both).
 */
internal fun renderChapterAssignmentSection(
    host: Container,
    memberId: String,
    currentChapterId: String?,
    chapters: List<RegionalChapterRefDto>,
    legendGroup: LegendGroup,
    onSaved: () -> Unit,
) {
    val form = host.lapisForm(legendGroup)
    val chapterField =
        form.selectField(
            label = tr("Landesverband"),
            options = listOf("" to tr("— nicht zugeordnet —")) + untrustedOptions(chapters.map { it.id to it.name }),
            value = currentChapterId ?: "",
        )
    form.panel.p(tr("Ein bestehender Landesvorstand-Zugang für einen anderen Landesverband endet dabei automatisch.")) {
        addCssClasses("text-muted small")
    }
    val saveButton = Button(tr("Zuordnung speichern"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val result =
                regionalChapterGuarded {
                    rpcService<IRegionalChapterService>().assignMemberToChapter(memberId, chapterField.value.ifBlank { null })
                }
            if (result != null) {
                notifySuccess(tr("Landesverband-Zuordnung gespeichert."))
                onSaved()
            }
        }
    }
}

/**
 * Welle V1.4.4.4 "Familienmitgliedschaften" -- ob der Abschnitt "Mitgliedschaftsstufe" in
 * [openMemberEditorDialog] erscheint, gespiegelt an `IMemberService.updateMemberMembershipTier`s
 * eigener Rollen-Asymmetrie (siehe dessen KDoc/`MemberService`-Implementierung): Zuweisen eines
 * ECHTEN Tarifs braucht TREASURER/ADMIN, Entfernen (Tarif = `null`) braucht nur `isPrivileged`
 * (BOARD/ADMIN) -- TREASURER darf also zuweisen, aber NICHT entfernen, und BOARD darf entfernen,
 * aber NICHT zuweisen. ADMIN sieht den Abschnitt immer (beide Aktionen); TREASURER immer (nur
 * Zuweisen -- KEIN `row.membershipTierId != null`-Gate, weil TREASURER unabhängig vom aktuellen
 * Tarif jederzeit einen anderen zuweisen darf); BOARD nur, wenn ein Tarif zum Entfernen existiert
 * (die einzige Aktion, die dieser Rolle erlaubt ist). Nie für ein anonymisiertes Mitglied.
 */
fun canEditMembershipTierOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean {
    if (row.anonymized) return false
    // Security fix (Welle V1.4.4.4 review, MAJOR finding) -- self-target and Peer-Schutz, mirroring
    // canChangeStatusOf/canEditCoreDataOf: never for the caller's OWN row (unconditionally, mirrors
    // MemberService.updateMemberMembershipTier's own self-target ForbiddenException, checked
    // regardless of role/direction), and never for a fellow ADMIN/BOARD/TREASURER account unless the
    // caller is themselves ADMIN (mirrors that same method's ESCALATED_ROLES peer gate). Without
    // these, a BOARD caller could remove their OWN membership tier (a self-benefit -- no payment
    // obligation from the next contribution run) or a peer's.
    if (row.id == callerMemberId) return false
    if (row.role != null && row.role in ESCALATED_ROLES && callerRole != AccountRole.ADMIN) return false
    return when (callerRole) {
        AccountRole.ADMIN -> true
        AccountRole.TREASURER -> true
        AccountRole.BOARD -> row.membershipTierId != null
        else -> false
    }
}

/**
 * Welle V1.2.13 -- ob der Abschnitt "Konto anlegen" in [openMemberEditorDialog] erscheint.
 * ADMIN-exklusiv (spiegelt `MemberService.grantMemberAccount`s unbedingten
 * `requireRole(ADMIN)`-Gate), nur für Zeilen OHNE Konto (`role == null`, siehe
 * [MemberAdminRowDto.role] KDoc -- die 407 CSV-Importe), nie für ein anonymisiertes Mitglied
 * (dessen `account`-Zeile hat `FoundationPersonalData.erase` hart gelöscht, `role == null` heißt
 * hier also NICHT "kontenlos importiert") und nie für DECEASED (Statuskorrektur zuerst -- siehe
 * `IMemberService.grantMemberAccount` KDoc). Ein Selbstziel ist strukturell ausgeschlossen: die
 * aufrufende Person hat per Definition ein Konto, ihre eigene Zeile trägt also `role != null`.
 */
fun canGrantAccountTo(
    callerRole: AccountRole?,
    row: MemberAdminRowDto,
): Boolean =
    callerRole == AccountRole.ADMIN &&
        row.role == null &&
        !row.anonymized &&
        row.status != MemberStatus.DECEASED

/**
 * Welle V1.2.13 -- analog zu [statusChangeConsequence]: was die Kontoanlage für DIESEN Status
 * bedeutet, live vor dem Anlegen gerendert. `null` = nichts Besonderes zu sagen.
 */
fun grantAccountConsequence(status: MemberStatus): String? =
    when (status) {
        MemberStatus.DONOR ->
            tr(
                "Für Spender ist der Login gesperrt. Das Konto wird angelegt, die Person kann sich " +
                    "aber erst anmelden, wenn der Status auf Aktiv geändert wird.",
            )
        MemberStatus.WITHDRAWN, MemberStatus.REJECTED ->
            tr("Für diesen Status ist der Login gesperrt. Das Konto bleibt bis zu einer Statusänderung wirkungslos.")
        else -> null
    }

/**
 * Nur ADMIN, und nur wenn das Mitglied überhaupt ein Login-Konto hat (siehe
 * [MemberAdminRowDto.role] KDoc). Nie für die eigene Zeile -- `MemberService.updateMemberRole`
 * lehnt ein Selbstziel unconditional mit `ForbiddenException` ab (siehe dort).
 */
fun canEditRoleOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean = callerRole == AccountRole.ADMIN && row.role != null && !row.anonymized && row.id != callerMemberId

/**
 * BOARD/ADMIN, aber der Rückweg aus DECEASED ist ADMIN-exklusiv (Datenkorrektur, kein
 * Lebenszyklus-Ereignis). Nie für die eigene Zeile -- `MemberService.updateMemberStatus` lehnt
 * ein Selbstziel unconditional mit `ForbiddenException` ab, unabhängig von Rolle/Richtung (siehe
 * dort). Und nie, wenn das Ziel ein ADMIN/BOARD/TREASURER-Konto hat und die aufrufende Person
 * nicht selbst ADMIN ist -- derselbe Peer-Schutz wie [canEditCoreDataOf].
 */
fun canChangeStatusOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean {
    if (row.anonymized) return false
    if (row.id == callerMemberId) return false
    if (MemberStatusTransitions.allowedTargets(row.status).isEmpty()) return false
    if (MemberStatusTransitions.requiresAdmin(row.status) && callerRole != AccountRole.ADMIN) return false
    if (row.role != null && row.role in ESCALATED_ROLES && callerRole != AccountRole.ADMIN) return false
    return callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
}

/**
 * Welle V1.4.4.5 -- die Liste zeigt hinter dem "Verstorben"-Badge, ob ein Sterbedatum erfasst ist.
 * `null` für jeden anderen Status. **Kein †-Zeichen** -- dieses Haus hat sich bewusst für eine
 * Schleife statt eines Kreuzes entschieden (religiöse Neutralität, siehe MemberStatusLabels.kt).
 */
fun deceasedDateNote(
    status: MemberStatus,
    dateOfDeath: LocalDate?,
): String? =
    when {
        status != MemberStatus.DECEASED -> null
        dateOfDeath == null -> tr("Sterbedatum fehlt")
        else -> gettext("verstorben am %1", dateOfDeath.toString())
    }

/**
 * Welle V1.4.4.5 -- ob die neue "Sterbedatum"-Sektion in [openMemberEditorDialog] erscheint.
 * ADMIN-exklusiv (spiegelt `MemberService.correctDateOfDeath`s unbedingten
 * `requireRole(ADMIN)`-Gate), nur für eine bereits als verstorben geführte, nicht anonymisierte
 * Fremdzeile -- nie für die eigene Zeile (strukturell ohnehin ausgeschlossen: ein ADMIN kann sich
 * selbst nicht auf DECEASED setzen, siehe [canChangeStatusOf]s Selbstziel-Sperre, aber die Prüfung
 * steht trotzdem hier, spiegelbildlich zu [canEditRoleOf]/[canEditMembershipTierOf]).
 */
fun canCorrectDateOfDeathOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean =
    callerRole == AccountRole.ADMIN &&
        !row.anonymized &&
        row.id != callerMemberId &&
        row.status == MemberStatus.DECEASED

/**
 * Reine, DOM-freie Funktion -- der Konsequenztext des Editor-Dialogs, live vor dem Speichern
 * gerendert.
 *
 * Welle V1.4.4.5 "Sterbefall-Workflow" hat [MemberStatus.DECEASED] von [MemberStatus.WITHDRAWN]
 * getrennt: beide teilten sich vorher denselben Text (Sitzungen/Gremien/SEPA-Mandat), aber ein
 * Todesfall ist rechtlich etwas anderes als ein Austritt -- § 38 BGB beendet die Mitgliedschaft
 * AUTOMATISCH, dieser Dialog dokumentiert das nur, er bewirkt es nicht. Der DECEASED-Text nennt
 * deshalb zusätzlich die Rechtsgrundlage, macht ausdrücklich klar, dass NIEMAND benachrichtigt
 * wird (kein Automatisierungsziel dieser Welle), und -- wenn [familyRole] bekannt ist -- weist
 * bei einem Familien-Zahler auf den fehlenden neuen Zahler hin.
 */
fun statusChangeConsequence(
    from: MemberStatus,
    to: MemberStatus,
    hasAccount: Boolean,
    familyRole: FamilyMemberRole? = null,
): String =
    when {
        to == MemberStatus.DECEASED ->
            listOfNotNull(
                tr("Die Mitgliedschaft endete mit dem Tod (§ 38 BGB). Dieser Eintrag hält das fest, er beendet sie nicht."),
                tr(
                    "Sitzungen werden beendet, offene Gremiensitze enden, ein aktives SEPA-Mandat wird " +
                        "widerrufen. Bereits offene Forderungen bleiben bestehen — sie sind eine " +
                        "Nachlassangelegenheit.",
                ),
                tr("Es wird niemand benachrichtigt."),
                if (familyRole == FamilyMemberRole.PAYER) {
                    tr("Diese Person ist Beitragszahler einer Familie — die Angehörigen brauchen einen neuen Zahler.")
                } else {
                    null
                },
            ).joinToString(separator = " ")
        to == MemberStatus.WITHDRAWN ->
            tr(
                "Alle Sitzungen werden sofort beendet, offene Gremien-Mitgliedschaften werden beendet, " +
                    "ein aktives SEPA-Mandat wird widerrufen.",
            )
        to == MemberStatus.DONOR -> tr("Der Login wird gesperrt. Kein Beitrag, keine Governance-Rechte.")
        to == MemberStatus.ACTIVE && !hasAccount ->
            tr(
                "Dieses Mitglied hat kein Login-Konto -- ein Statuswechsel erzeugt keines. Ohne zugeordnete " +
                    "Mitgliedschaftsstufe entstehen außerdem keine Beiträge.",
            )
        else -> gettext("Status wird von %1 auf %2 geändert.", memberStatusLabel(from), memberStatusLabel(to))
    }

/** Reine Funktion für das Pager-Label, z. B. "26–50 von 407". */
fun pagerLabel(
    offset: Int,
    pageSize: Int,
    totalCount: Int,
): String {
    if (totalCount == 0) return gettext("Keine Treffer")
    val from = offset + 1
    val to = minOf(offset + pageSize, totalCount)
    return gettext("%1–%2 von %3", from, to, totalCount)
}

internal fun renderDirectMemberCreation(
    root: SimplePanel,
    chapters: List<RegionalChapterRefDto> = emptyList(),
) {
    root.h2(tr("Mitglied direkt anlegen")) { addCssClass("h5") }
    root.p(
        tr(
            "Legt ein Mitglied ohne Antrags-/Freigabeschritt an (z. B. für Beitritte auf Papier oder " +
                "Datenmigration) -- Status sofort Aktiv.",
        ),
    )

    val callerRole = AppState.session?.role ?: AccountRole.MEMBER
    val roleOptions = selectableRolesFor(callerRole).map { it.name to it.name }

    // Formular-Grammatik (V1.4.29): vier Pflichtfelder => Fall (b), keine Sterne, Legende "Alle Felder sind Pflichtfelder."
    val form = root.lapisForm()
    val nameField = form.textField(label = tr("Name"), required = true)
    val emailField =
        form.textField(
            label = tr("E-Mail"),
            type = InputType.EMAIL,
            required = true,
            rule = { FormRules.email(value = it) },
        )
    val passwordField =
        form.passwordField(
            label = gettext("Vorläufiges Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH),
            required = true,
            suppressManagers = true,
            reveal = true,
            rule = { FormRules.newPassword(value = it, email = emailField.value.trim()) },
        )
    val roleField =
        form.selectField(
            label = tr("Rolle"),
            options = roleOptions,
            value = roleOptions.firstOrNull()?.first,
            required = true,
        )
    if (roleOptions.size == 1) {
        form.panel.p(
            tr("Als Vorstand können Sie hier nur reguläre Mitglieder anlegen -- Vorstand/Schatzmeister/Admin ist Admin vorbehalten."),
        )
    }
    // Welle V1.9.14 -- MUST be built before `form.buttons(...)` (plan §1 P9): the pflicht-legend
    // decision happens there and needs the final field count/required-set.
    val chapterField =
        if (chapters.isNotEmpty()) {
            form.selectField(
                label = tr("Landesverband"),
                options = listOf("" to tr("— noch nicht festgelegt —")) + untrustedOptions(chapters.map { it.id to it.name }),
                required = false,
            )
        } else {
            null
        }

    val createButton = Button(tr("Mitglied anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val email = emailField.value.trim()
            // Ein Passwort wird NIE getrimmt.
            val temporaryPassword = passwordField.value
            val chapterId = chapterField?.value?.ifBlank { null }
            // Review fix (NIT "misleading KDoc/behavior"): a chapter-shaped `BadRequestException`
            // (the chapter picked here was deleted between loading the options and submitting --
            // `createMemberDirect` then throws `BadRequestException("Unknown regionalChapterId")`)
            // now gets the SAME field error `RegistrationScreen.kt`'s own catch chain already shows
            // for the identical case, instead of `regionalChapterGuarded`'s generic "Ungültige
            // Anfrage." toast -- see [regionalChapterGuarded] KDoc. RegionalChapterRequiredException
            // is caught here too so the fallback to `regionalChapterGuarded { throw e }` (everything
            // else) still gets its usual dispatch, unchanged from before this fix.
            val result =
                try {
                    rpcService<IRegistrationService>().createMemberDirect(
                        AdminCreateMemberInput(
                            displayName = name,
                            email = email,
                            role = AccountRole.valueOf(roleField.value),
                            temporaryPassword = temporaryPassword,
                            regionalChapterId = chapterId,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: RegionalChapterRequiredException) {
                    chapterField?.showError(tr("Bitte wählen Sie einen Landesverband."))
                        ?: notifyError(tr("Bitte zuerst einen Landesverband zuordnen."))
                    null
                } catch (e: BadRequestException) {
                    if (chapterField != null && !chapterId.isNullOrBlank()) {
                        chapterField.showError(tr("Dieser Landesverband ist nicht mehr verfügbar -- bitte Seite neu laden."))
                        null
                    } else {
                        regionalChapterGuarded { throw e }
                    }
                } catch (e: Throwable) {
                    regionalChapterGuarded { throw e }
                }
            if (result != null) {
                notifySuccess(gettext("%1 wurde angelegt.", name))
                nameField.reset()
                emailField.reset()
                passwordField.reset()
                chapterField?.reset()
            }
        }
    }
}

// ================================================================================================
// Öffentliche Sichtbarkeit: showPublicMemberCount toggle (IOrganizationSettingsService)
// ================================================================================================

/**
 * Welle V1.9.10 "Mitgliederzahl-Sichtbarkeit" -- exact same wholesale-replace pattern
 * [renderPoliticianRankingToggle] (`PoliticianScreen.kt`) established: ADMIN replaces the FULL
 * [OrganizationSettingsDto] wholesale, copying every existing field unchanged and flipping ONLY
 * `showPublicMemberCount` ([toInputWithShowPublicMemberCount]).
 *
 * **Deliberately NOT the same `canAdmin` treatment as [renderPoliticianRankingToggle]'s own caller**
 * (there, `canAdmin` covers BOARD too): `updateOrganizationSettings` replaces the ENTIRE
 * [OrganizationSettingsDto] -- IBAN, DATEV-Berater-/Mandantennummer, Konten-Zuordnung -- and giving
 * BOARD write access here (even though the *toggle itself* is harmless) would open that whole
 * surface to BOARD just to save one click. TREASURER/BOARD see the current state READ-ONLY, same
 * "cannot silently activate/deactivate a feature" tier [renderPoliticianRankingToggle] establishes
 * for its own `canAdmin == false` branch. Placed in `MemberAdministrationScreen.kt` (not
 * `PoliticianScreen.kt` or `EmbedIntegrationScreen.kt`, the latter explicitly read-only by its own
 * KDoc) -- this setting governs what a visitor of the public member-facing pages sees, closest in
 * spirit to this screen's own membership-roster concerns.
 */
private fun renderPublicMemberCountToggle(
    root: SimplePanel,
    canAdmin: Boolean,
) {
    val section = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2") }
    section.h2(tr("Öffentliche Sichtbarkeit")) { addCssClass("h5") }
    section.p(
        tr(
            "Legt fest, ob die Zahl der aktiven Mitglieder auf der öffentlichen Startseite und der " +
                "Transparenzseite erscheint. Beiträge und LTR-Kennzahlen bleiben davon unberührt.",
        ),
    ) { addCssClasses("text-muted small") }
    val panel = section.vPanel(spacing = 6)
    panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun load() {
        panel.removeAll()
        panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } ?: return@launch
            panel.removeAll()
            val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            statusRow.div(tr("Mitgliederzahl öffentlich:")) { addCssClasses("text-muted small") }
            statusRow.statusBadge(
                if (settings.showPublicMemberCount) tr("Sichtbar") else tr("Verborgen"),
                if (settings.showPublicMemberCount) "success" else "secondary",
            )
            if (canAdmin) {
                val toggleButton =
                    panel.button(
                        if (settings.showPublicMemberCount) tr("Verbergen") else tr("Anzeigen"),
                        style = if (settings.showPublicMemberCount) ButtonStyle.OUTLINESECONDARY else ButtonStyle.PRIMARY,
                    )
                toggleButton.onClick {
                    val newValue = !settings.showPublicMemberCount
                    confirmDialog(
                        title = if (newValue) tr("Mitgliederzahl öffentlich anzeigen") else tr("Mitgliederzahl verbergen"),
                        message =
                            if (newValue) {
                                tr(
                                    "Die aktuelle Zahl der aktiven Mitglieder ist dann für alle Besucher ohne " +
                                        "Anmeldung sichtbar. Die Änderung wird innerhalb weniger Minuten wirksam.",
                                )
                            } else {
                                tr(
                                    "Die Mitgliederzahl wird auf der öffentlichen Startseite und der " +
                                        "Transparenzseite nicht mehr angezeigt. Die Änderung wird innerhalb " +
                                        "weniger Minuten wirksam.",
                                )
                            },
                        confirmLabel = if (newValue) tr("Anzeigen") else tr("Verbergen"),
                    ) {
                        runGuardedAction(toggleButton) {
                            val result =
                                guarded {
                                    rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                                        settings.toInputWithShowPublicMemberCount(newValue),
                                    )
                                }
                            if (result != null) {
                                notifySuccess(
                                    if (newValue) {
                                        tr("Mitgliederzahl wird öffentlich angezeigt.")
                                    } else {
                                        tr("Mitgliederzahl wird öffentlich nicht mehr angezeigt.")
                                    },
                                )
                                load()
                            }
                        }
                    }
                }
            } else {
                panel.p(tr("Ein ADMIN kann diese Einstellung ändern.")) { addCssClasses("text-muted small") }
            }
        }
    }
    load()
}

/**
 * Wholesale-replace helper (see [renderPublicMemberCountToggle] KDoc) -- copies every field of an
 * already-fetched [OrganizationSettingsDto] unchanged except
 * [OrganizationSettingsDto.showPublicMemberCount]. `internal` (not `private`), same visibility
 * rationale [toInputWithPoliticianRankingEnabled] KDoc gives, so a client test can cover the
 * "never silently drop/reset a field" contract directly.
 */
internal fun OrganizationSettingsDto.toInputWithShowPublicMemberCount(newValue: Boolean) = toInput().copy(showPublicMemberCount = newValue)

/**
 * The options of the tier select of the roster dialog (V1.9.18). A CLOSED tier (`active = false`) takes no new
 * assignments -- the server refuses it ([network.lapis.cloud.shared.rpc.MembershipTierClosedException]) -- so it is
 * offered ONLY when it is the member's current one, marked "(geschlossen)", so that the select can still show
 * where the member stands. The label goes through [untrustedOptions]: a tier name is treasurer-written text, and
 * `gettext` with a `%1` is fine, a `tr()`/string concatenation would not be.
 */
internal fun tierSelectOptions(
    tiers: List<MembershipTierDto>,
    currentTierId: String?,
): List<Pair<String, String>> =
    untrustedOptions(
        tiers
            .filter { it.active || it.id == currentTierId }
            .map { tier -> tier.id to if (tier.active) tier.name else gettext("%1 (geschlossen)", tier.name) },
    )
