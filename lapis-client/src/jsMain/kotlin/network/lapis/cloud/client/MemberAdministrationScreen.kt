package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
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
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * Screen 4 of the V0.7.3 plan -- BOARD/ADMIN only, route-guarded in `Routing.kt` (never even
 * rendered for a plain MEMBER, per the plan). Three sub-sections in this one file, mirroring the
 * existing `renderXSection` grouping convention the old `App.kt` already used: pending
 * applications (approve/reject), the privileged member roster (Welle V1.2.12 -- see
 * [renderMemberRoster]), and direct member creation.
 *
 * **Welle V1.4.4.4 "Familienmitgliedschaften" widened this to also admit TREASURER** -- but only
 * for the roster, and only so a Schatzmeister can reach the "Beitragstarif" section of
 * [openMemberEditorDialog] ([canEditMembershipTierOf] KDoc has the full Rollen-Asymmetrie). The
 * other two sections stay BOARD/ADMIN-exclusive, both server-side (`IRegistrationService
 * .listPendingApplications`/`createMemberDirect` both `requireRole(BOARD, ADMIN)`) and here on the
 * client, so a TREASURER caller is never offered a section the server would reject anyway.
 */
fun renderMemberAdministrationScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    root.h1(tr("Mitgliederverwaltung"))

    val callerRole = AppState.session?.role
    val isBoardOrAdmin = callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
    if (isBoardOrAdmin) renderPendingApplications(root)
    renderMemberRoster(root)
    if (isBoardOrAdmin) renderDirectMemberCreation(root)
}

private fun renderPendingApplications(root: SimplePanel) {
    root.h2(tr("Offene Anträge"))
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
                    columns = pendingApplicationColumns(),
                    rows = applications,
                    actions = {
                        actions,
                        application,
                        ->
                        renderPendingApplicationActions(actions, application, onChanged = { section.reload() })
                    },
                )
            },
        )
    section.reload()
}

private fun pendingApplicationColumns(): List<DataColumn<MemberDto>> =
    listOf(
        DataColumn(
            title = tr("Antragsteller"),
            primary = true,
            cell = { container, application -> container.span(pendingApplicationLabel(application)) },
        ),
        DataColumn(title = tr("Rolle"), cell = { container, application -> container.accountRoleBadge(application.role) }),
    )

private fun pendingApplicationLabel(application: MemberDto): String {
    val friendSince = application.friendSince
    val summary =
        gettext(
            "%1 (%2) -- eingereicht am %3",
            application.displayName,
            application.email,
            application.joinedAt,
        )
    // V0.11.0: shows the board that this applicant came from an existing FRIEND account
    // (see MemberDto.friendSince KDoc "load-bearing") -- FriendUpgradePathTest covers the
    // applyForMembership transition itself; this is purely informational.
    return if (friendSince != null) gettext("%1 (Freund-Konto seit %2)", summary, friendSince) else summary
}

private fun renderPendingApplicationActions(
    actionsContainer: Container,
    application: MemberDto,
    onChanged: () -> Unit,
) {
    val actionsRow = actionsContainer.hPanel(spacing = 8)
    val approveButton = actionsRow.button(tr("Annehmen"), style = ButtonStyle.SUCCESS)
    approveButton.onClick {
        AppScope.launch {
            val result = guarded { rpcService<IRegistrationService>().approveApplication(application.id) }
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
}

/** Reject requires a non-blank reason -- see `IRegistrationService.rejectApplication` KDoc, a real
 * modal input rather than a bare confirm, since [confirmDialog] has no input field of its own. */
private fun rejectApplicationDialog(
    applicantName: String,
    onConfirm: (String) -> Unit,
) {
    val modal = Modal(caption = gettext("Antrag von %1 ablehnen", applicantName))
    modal.p(tr("Bitte geben Sie einen Ablehnungsgrund an (wird beim Mitglied gespeichert)."))
    val reasonInput = modal.textArea(rows = 3)
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Ablehnen"), style = ButtonStyle.DANGER).apply {
            onClick {
                val reason = reasonInput.value.orEmpty().trim()
                if (reason.isBlank()) {
                    errorBox.content = tr("Bitte einen Grund angeben.")
                    errorBox.show()
                    return@onClick
                }
                modal.hide()
                onConfirm(reason)
            }
        },
    )
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
)

private val STATUS_CHIPS: List<MemberStatus?> =
    listOf(null, MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED)

/**
 * Replaces the old `renderMemberDirectory` -- that function's own KDoc ("dafür existiert aktuell
 * keine privilegierte Leseschnittstelle") is, as of this wave, no longer true:
 * `IMemberService.listMembersForAdministration` is exactly that interface. BOARD/ADMIN/TREASURER
 * (Welle V1.4.4.4 widened `listMembersForAdministration`'s own server-side gate from `isPrivileged`
 * to also admit TREASURER, purely so a Schatzmeister can reach the "Beitragstarif" section --
 * see this file's class KDoc and [canEditMembershipTierOf]) -- server-side re-enforces this
 * independently, this is not the only gate.
 */
private fun renderMemberRoster(root: SimplePanel) {
    root.h2(tr("Mitgliederverzeichnis"))

    var state = RosterState()
    // Set by a sort click, consumed by the render of THAT click's load: hands the keyboard focus back to
    // the header button of the clicked column (the re-render destroys the one the user just pressed).
    // Expires when that load fails, is superseded or renders no table (see [SortFocusRequest]).
    val sortFocus = SortFocusRequest()

    val filterRow = root.hPanel(spacing = 8)
    val searchInput = filterRow.text(label = tr("Suche nach Name, E-Mail oder Personennummer"))
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

    section =
        root.dataSection<MemberAdminPageDto>(
            emptyText = tr("Noch keine Mitglieder vorhanden."),
            filterTerm = { rosterFilterTerm(state) },
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
            load = {
                guarded {
                    rpcService<IMemberService>().listMembersForAdministration(
                        MemberAdminQuery(
                            search = state.search.ifBlank { null },
                            statuses = state.statuses,
                            sort = state.sort,
                            offset = state.offset,
                        ),
                    )
                }
            },
            render = { panel, page ->
                panel.dataTable(
                    columns = rosterColumns(),
                    rows = page.rows,
                    sort = state.sort.toSortState(),
                    onSort = { clicked ->
                        sortFocus.request((clicked ?: state.sort.toSortState()).key)
                        state = state.withSortClick(clicked)
                        refresh()
                    },
                    sortOptions = ROSTER_SORT_OPTIONS,
                    actions = { actions, row -> renderRosterActions(actions, row, onChanged = { refresh() }) },
                    focusSortKey = sortFocus.takeForRender(),
                )
            },
        )
    pagerRow = root.hPanel(spacing = 8)

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

/** The columns of the roster table / card list; Name is the card title. */
private fun rosterColumns(): List<DataColumn<MemberAdminRowDto>> =
    listOf(
        DataColumn(
            title = tr("Name"),
            primary = true,
            sortKey = ROSTER_SORT_NAME,
            cell = { container, row -> container.renderRosterName(row) },
        ),
        textColumn(title = tr("E-Mail")) { row: MemberAdminRowDto -> row.email },
        DataColumn(title = tr("Status"), cell = { container, row -> container.renderRosterStatus(row) }),
        DataColumn(title = tr("Rolle"), cell = { container, row -> container.renderRosterRole(row) }),
        DataColumn(
            title = tr("Beitritt"),
            numeric = true,
            sortKey = ROSTER_SORT_JOINED,
            cell = { container, row -> container.span(row.joinedAt.toString()) },
        ),
    )

/**
 * The term the "no match" sentence quotes: the search text, or -- with only a status chip active -- that
 * chip's label. `null` when nothing filters (an empty page then means "no members yet").
 */
internal fun rosterFilterTerm(
    search: String,
    statuses: Set<MemberStatus>,
): String? =
    search.trim().takeIf { it.isNotEmpty() }
        ?: statuses.singleOrNull()?.let { memberStatusLabel(it) }

private fun rosterFilterTerm(state: RosterState): String? = rosterFilterTerm(state.search, state.statuses)

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
    span(row.displayName)
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
    } else if (!hasAnyEditableSectionFor(callerRole, callerMemberId, row)) {
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
        // "Tarif entfernen" action -- see [canEditMembershipTierOf] KDoc.
        editButton.disabled = true
        editButton.tableActionTooltip(
            tr(
                "Keine Bearbeitung möglich -- Peer-Schutz: Vorstand darf Vorstands-/Schatzmeister-/" +
                    "Admin-Konten (auch das eigene) nicht bearbeiten, das ist Admin vorbehalten.",
            ),
        )
    } else {
        editButton.onClick { openMemberEditorDialog(row, onChanged) }
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
    if (AppState.hasRole(AccountRole.ADMIN)) {
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
}

/**
 * Editor-Modal, drei unabhängig gespeicherte Abschnitte (Stammdaten/Status/Rolle) -- Muster:
 * `rejectApplicationDialog` in dieser Datei. Kein gemeinsamer "Speichern"-Knopf, weil die drei
 * Abschnitte drei unterschiedlich autorisierte, unabhängige RPCs sind (siehe `IMemberService`).
 */
private fun openMemberEditorDialog(
    row: MemberAdminRowDto,
    onChanged: () -> Unit,
) {
    val callerRole = AppState.session?.role
    val callerMemberId = AppState.session?.memberId
    val modal = Modal(caption = gettext("%1 bearbeiten", row.displayName))

    // ── Stammdaten ──
    if (canEditCoreDataOf(callerRole, row)) {
        modal.h2(tr("Stammdaten")) { addCssClass("h6") }
        val nameInput = modal.text(value = row.displayName, label = tr("Name"))
        val emailInput = modal.text(type = InputType.EMAIL, value = row.email, label = tr("E-Mail"))
        val coreDataError =
            modal.div().apply {
                addCssClass("text-danger")
                hide()
            }
        val saveCoreDataButton = modal.button(tr("Stammdaten speichern"), style = ButtonStyle.PRIMARY)
        saveCoreDataButton.onClick {
            coreDataError.hide()
            val name = nameInput.value.orEmpty().trim()
            val email = emailInput.value.orEmpty().trim()
            if (email.length > Validation.EMAIL_MAX_LENGTH) {
                // Review Runde 3 NIT fix -- a specific message, not the generic one below: without
                // this, an overlong address either silently passed as "looks like an email" (before
                // EMAIL_MAX_LENGTH was folded into looksLikeEmail) or, now that it is folded in,
                // would produce the SAME generic "invalid address" message a typo would -- worse
                // guidance than telling the operator exactly what is wrong.
                coreDataError.content = gettext("Die E-Mail-Adresse ist zu lang (höchstens %1 Zeichen).", Validation.EMAIL_MAX_LENGTH)
                coreDataError.show()
                return@onClick
            }
            if (!Validation.isNonBlank(name) || !Validation.looksLikeEmail(email)) {
                coreDataError.content = tr("Bitte Name und eine gültige E-Mail-Adresse angeben.")
                coreDataError.show()
                return@onClick
            }
            AppScope.launch {
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
        val targets = MemberStatusTransitions.allowedTargets(row.status).toList()
        val statusSelect =
            modal.select(
                options = targets.map { it.name to memberStatusLabel(it) },
                value = targets.firstOrNull()?.name,
                label = tr("Neuer Status"),
            )
        // Bug fix (live user report after V1.2.12 deploy): addCssClass() only ever adds a single
        // literal token (classList.add() throws InvalidCharacterError on a space-containing
        // string) -- addCssClass("alert alert-secondary") crashed uncaught inside this onClick
        // handler (outside initRouting's render-time try/catch, see that KDoc for the identical
        // bug shape hit before in DashboardScreen), aborting openMemberEditorDialog() before
        // modal.show() ever ran. Every click on "Bearbeiten" for a row whose status has any
        // allowed transition silently did nothing. Use addCssClasses() (see CssClasses.kt) for
        // any multi-class string.
        val consequenceBox = modal.div { addCssClasses("alert alert-secondary") }
        val warningBox =
            modal.div {
                addCssClasses("alert alert-warning")
                hide()
            }
        // Welle V1.4.4.5 -- nur sichtbar, wenn der Zielstatus "Verstorben" ist. BEWUSST NICHT mit
        // todayLocalDate() vorbefüllt (anders als BoardMembershipScreen.todayIso()): ein
        // vorbefülltes heutiges Datum in einem Sterbedatumsfeld ist eine Behauptung, die die
        // Software aufstellt und der Mensch nur noch bestätigt -- übersieht er das Feld, hat dieses
        // System aus eigenem Antrieb einen Todestag erfunden und in eine GoBD-unveränderliche
        // Buchhaltung geschrieben. Leer. Immer leer.
        val deathDatePanel = modal.div { hide() }
        val deathDateInput = deathDatePanel.text(label = tr("Sterbedatum (JJJJ-MM-TT, optional)"))
        deathDatePanel.div(tr("Leer lassen, wenn das genaue Datum noch nicht feststeht — später korrigierbar.")) {
            addCssClasses("form-text text-muted")
        }
        val reasonInput = modal.textArea(rows = 2, label = tr("Begründung (3-1000 Zeichen)"))
        // Bug fix (live user report): hPanel is a non-wrapping flex row by default -- four chip
        // buttons ("Austrittserklärung liegt vor" / "Sterbefall gemeldet" / "Datenkorrektur
        // CSV-Import" / "Sonstiges") together exceed the modal's width, so the last one ("Sonstiges")
        // got clipped/pushed outside the visible dialog instead of wrapping onto a second line --
        // same "flex-wrap" fix already used elsewhere in this codebase for a wide button row (see
        // ConferenceScreen.kt's controlsRow).
        val statusChipsRow = modal.hPanel(spacing = 6) { addCssClasses("flex-wrap") }
        listOf(
            tr("Austrittserklärung liegt vor"),
            tr("Sterbefall gemeldet"),
            tr("Datenkorrektur CSV-Import"),
            tr("Sonstiges"),
        ).forEach { suggestion ->
            statusChipsRow.button(suggestion, style = ButtonStyle.OUTLINESECONDARY).onClick {
                reasonInput.value = suggestion
            }
        }

        fun refreshConsequence() {
            val target = statusSelect.value?.let { MemberStatus.valueOf(it) } ?: return
            consequenceBox.content =
                statusChangeConsequence(row.status, target, hasAccount = row.role != null, familyRole = row.familyRole)
            // Welle V1.4.4.5 -- das Sterbedatumsfeld erscheint nur, wenn der Zielstatus DECEASED ist.
            if (target == MemberStatus.DECEASED) deathDatePanel.show() else deathDatePanel.hide()
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
        statusSelect.subscribe { refreshConsequence() }
        refreshConsequence()

        val statusError =
            modal.div().apply {
                addCssClass("text-danger")
                hide()
            }
        val statusButtonStyle = if (MemberStatusTransitions.requiresAdmin(row.status)) ButtonStyle.WARNING else ButtonStyle.PRIMARY
        val saveStatusButton = modal.button(tr("Status ändern"), style = statusButtonStyle)
        saveStatusButton.onClick {
            statusError.hide()
            val target = statusSelect.value?.let { MemberStatus.valueOf(it) }
            val reason = reasonInput.value.orEmpty().trim()
            if (target == null || reason.length < 3 || reason.length > 1000) {
                statusError.content = tr("Bitte einen Zielstatus und eine Begründung (3-1000 Zeichen) angeben.")
                statusError.show()
                return@onClick
            }
            // Welle V1.4.4.5 -- nur relevant, wenn der Zielstatus DECEASED ist; leer bleibt erlaubt
            // ("Datum noch nicht bekannt", siehe deathDatePanel-Kommentar oben).
            val rawDeathDate = deathDateInput.value.orEmpty().trim()
            val deathDate =
                if (target == MemberStatus.DECEASED && rawDeathDate.isNotEmpty()) {
                    val parsed = runCatching { LocalDate.parse(rawDeathDate) }.getOrNull()
                    if (parsed == null ||
                        DeathDateRules.violation(dateOfDeath = parsed, dateOfBirth = null, today = todayLocalDate()) != null
                    ) {
                        statusError.content = tr("Bitte ein gültiges Datum (JJJJ-MM-TT) angeben, das nicht in der Zukunft liegt.")
                        statusError.show()
                        return@onClick
                    }
                    parsed
                } else {
                    null
                }
            AppScope.launch {
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
        val correctionInput =
            modal.text(value = row.dateOfDeath?.toString(), label = tr("Sterbedatum (JJJJ-MM-TT, optional)"))
        modal.div(tr("Leer lassen nimmt ein irrtümlich erfasstes Datum zurück.")) {
            addCssClasses("form-text text-muted")
        }
        val correctionReason = modal.textArea(rows = 2, label = tr("Begründung (3-1000 Zeichen)"))
        val correctionError =
            modal.div().apply {
                addCssClass("text-danger")
                hide()
            }
        val correctButton = modal.button(tr("Sterbedatum korrigieren"), style = ButtonStyle.WARNING)
        correctButton.onClick {
            correctionError.hide()
            val reason = correctionReason.value.orEmpty().trim()
            if (reason.length < 3 || reason.length > 1000) {
                correctionError.content = tr("Bitte eine Begründung (3-1000 Zeichen) angeben.")
                correctionError.show()
                return@onClick
            }
            val raw = correctionInput.value.orEmpty().trim()
            val parsedDate =
                if (raw.isEmpty()) {
                    null
                } else {
                    val parsed = runCatching { LocalDate.parse(raw) }.getOrNull()
                    if (parsed == null ||
                        DeathDateRules.violation(dateOfDeath = parsed, dateOfBirth = null, today = todayLocalDate()) != null
                    ) {
                        correctionError.content = tr("Bitte ein gültiges Datum (JJJJ-MM-TT) angeben, das nicht in der Zukunft liegt.")
                        correctionError.show()
                        return@onClick
                    }
                    parsed
                }
            AppScope.launch {
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
        val roleOptions = AccountRole.entries.map { it.name to accountRoleLabel(it) }
        val roleSelect = modal.select(options = roleOptions, value = row.role?.name, label = tr("Rolle"))
        val saveRoleButton = modal.button(tr("Rolle ändern"), style = ButtonStyle.PRIMARY)
        saveRoleButton.onClick {
            val newRole = roleSelect.value?.let { AccountRole.valueOf(it) } ?: return@onClick
            AppScope.launch {
                val result = memberAdminGuarded { rpcService<IMemberService>().updateMemberRole(row.id, newRole) }
                if (result != null) {
                    notifySuccess(tr("Rolle geändert."))
                    modal.hide()
                    onChanged()
                }
            }
        }
    }

    // ── Beitragstarif (Welle V1.4.4.4) ──
    if (canEditMembershipTierOf(callerRole, callerMemberId, row)) {
        modal.div { addCssClass("mt-3") }
        modal.h2(tr("Beitragstarif")) { addCssClass("h6") }
        val tierError =
            modal.div().apply {
                addCssClass("text-danger")
                hide()
            }
        val tierWarning =
            modal.div {
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
            val tierSelect =
                modal.select(
                    options = listOf("" to tr("— beitragsfrei / kein Tarif —")),
                    value = row.membershipTierId ?: "",
                    label = tr("Tarif"),
                )
            AppScope.launch {
                val tiers: List<MembershipTierDto> = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: emptyList()
                tierSelect.options = listOf("" to tr("— beitragsfrei / kein Tarif —")) + tiers.map { it.id to it.name }
                tierSelect.value = row.membershipTierId ?: ""
            }
            tierSelect.subscribe { value -> showTierConsequence(!value.isNullOrBlank()) }
            val tierReasonInput = modal.textArea(rows = 2, label = tr("Begründung (3-1000 Zeichen)"))
            val saveTierButton = modal.button(tr("Tarif speichern"), style = ButtonStyle.PRIMARY)
            saveTierButton.onClick {
                tierError.hide()
                val reason = tierReasonInput.value.orEmpty().trim()
                if (reason.length < 3 || reason.length > 1000) {
                    tierError.content = tr("Bitte eine Begründung (3-1000 Zeichen) angeben.")
                    tierError.show()
                    return@onClick
                }
                val chosenTierId = tierSelect.value?.takeIf { it.isNotBlank() }
                AppScope.launch {
                    val result =
                        memberAdminGuarded { rpcService<IMemberService>().updateMemberMembershipTier(row.id, chosenTierId, reason) }
                    if (result != null) {
                        notifySuccess(tr("Beitragstarif gespeichert."))
                        modal.hide()
                        onChanged()
                    }
                }
            }
        } else if (callerRole == AccountRole.TREASURER) {
            // TREASURER (Welle V1.4.4.4 review fix, MAJOR finding): nur die Zuweisung eines ECHTEN
            // Tarifs -- KEIN "— beitragsfrei / kein Tarif —"-Eintrag, weil der Server
            // (`updateMemberMembershipTier`, `membershipTierId == null`-Zweig) das Entfernen einem
            // TREASURER-Aufrufer verweigert (nur `isPrivileged`, also BOARD/ADMIN) -- siehe
            // [canEditMembershipTierOf] KDoc. Anders als beim ADMIN-Zweig oben ist die Select-Liste
            // deshalb NIE leer wählbar; ein no-op-Klick ohne Tiers geladen wird unten abgefangen.
            modal.p(gettext("Aktueller Tarif: %1", row.membershipTierName ?: gettext("beitragsfrei")))
            val tierSelect = modal.select(options = emptyList(), label = tr("Neuer Tarif"))
            AppScope.launch {
                val tiers: List<MembershipTierDto> = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: emptyList()
                tierSelect.options = tiers.map { it.id to it.name }
                tierSelect.value = row.membershipTierId ?: tiers.firstOrNull()?.id
            }
            tierSelect.subscribe { value -> showTierConsequence(!value.isNullOrBlank()) }
            val tierReasonInput = modal.textArea(rows = 2, label = tr("Begründung (3-1000 Zeichen)"))
            val saveTierButton = modal.button(tr("Tarif zuweisen"), style = ButtonStyle.PRIMARY)
            saveTierButton.onClick {
                tierError.hide()
                val chosenTierId = tierSelect.value?.takeIf { it.isNotBlank() }
                if (chosenTierId == null) {
                    tierError.content = tr("Bitte einen Tarif auswählen.")
                    tierError.show()
                    return@onClick
                }
                val reason = tierReasonInput.value.orEmpty().trim()
                if (reason.length < 3 || reason.length > 1000) {
                    tierError.content = tr("Bitte eine Begründung (3-1000 Zeichen) angeben.")
                    tierError.show()
                    return@onClick
                }
                AppScope.launch {
                    val result =
                        memberAdminGuarded { rpcService<IMemberService>().updateMemberMembershipTier(row.id, chosenTierId, reason) }
                    if (result != null) {
                        notifySuccess(tr("Beitragstarif zugewiesen."))
                        modal.hide()
                        onChanged()
                    }
                }
            }
        } else {
            // BOARD: nur die Schaltfläche "Tarif entfernen" -- siehe canEditMembershipTierOf KDoc.
            modal.p(gettext("Aktueller Tarif: %1", row.membershipTierName ?: gettext("beitragsfrei")))
            val tierReasonInput = modal.textArea(rows = 2, label = tr("Begründung (3-1000 Zeichen)"))
            val removeTierButton = modal.button(tr("Tarif entfernen"), style = ButtonStyle.WARNING)
            removeTierButton.onClick {
                tierError.hide()
                val reason = tierReasonInput.value.orEmpty().trim()
                if (reason.length < 3 || reason.length > 1000) {
                    tierError.content = tr("Bitte eine Begründung (3-1000 Zeichen) angeben.")
                    tierError.show()
                    return@onClick
                }
                AppScope.launch {
                    val result = memberAdminGuarded { rpcService<IMemberService>().updateMemberMembershipTier(row.id, null, reason) }
                    if (result != null) {
                        notifySuccess(tr("Beitragstarif entfernt."))
                        modal.hide()
                        onChanged()
                    }
                }
            }
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
        val grantPasswordInput =
            modal.password(label = gettext("Vorläufiges Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH))
        val grantRoleSelect =
            modal.select(
                options = AccountRole.entries.map { it.name to accountRoleLabel(it) },
                value = AccountRole.MEMBER.name,
                label = tr("Rolle"),
            )
        val grantError =
            modal.div().apply {
                addCssClass("text-danger")
                hide()
            }
        val grantButton = modal.button(tr("Konto anlegen"), style = ButtonStyle.PRIMARY)
        grantButton.onClick {
            grantError.hide()
            val password = grantPasswordInput.value.orEmpty()
            val selectedRole = grantRoleSelect.value?.let { AccountRole.valueOf(it) }
            if (selectedRole == null) {
                grantError.content = tr("Bitte eine Rolle auswählen.")
                grantError.show()
                return@onClick
            }
            val passwordHint = Validation.passwordHint(password, row.email)
            if (passwordHint != null) {
                grantError.content = passwordHint
                grantError.show()
                return@onClick
            }
            grantButton.disabled = true
            AppScope.launch {
                val updated =
                    memberAdminGuarded {
                        rpcService<IMemberService>().grantMemberAccount(
                            memberId = row.id,
                            temporaryPassword = password,
                            role = selectedRole,
                        )
                    }
                grantButton.disabled = false
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
 * Whether [openMemberEditorDialog] would render AT LEAST ONE of its six sections for [row] --
 * i.e. whether the "Bearbeiten" button in [renderMemberRosterRow] should be enabled at all. Purely
 * `canEditCoreDataOf(...) || canChangeStatusOf(...) || canEditRoleOf(...) || canGrantAccountTo(...)
 * || canEditMembershipTierOf(...) || canCorrectDateOfDeathOf(...)` (the sixth predicate, added
 * V1.4.4.5, follows the exact same reasoning as the other five), kept as its own named function
 * (rather than inlined at the one call site) so the six predicates this depends on stay a single,
 * obviously-in-sync list with the six `if`-gates inside [openMemberEditorDialog] -- see this
 * file's ESCALATED_ROLES KDoc for why the client mirrors the server's Peer-Schutz boundary at all:
 * an escalated-role target (or, for a BOARD caller, their OWN row -- BOARD/ADMIN/TREASURER is
 * itself an escalated role) can leave all six predicates `false` at once, which without this check
 * would previously open a modal with a title, an empty body, and only a "Schließen" button.
 *
 * Review fix (Welle V1.4.4.4, MAJOR finding): `canEditMembershipTierOf` was originally left OUT of
 * this OR-chain, so a BOARD caller on an escalated-role target (TREASURER/BOARD/ADMIN, including
 * their own row) with a removable tier (`row.membershipTierId != null`) saw a disabled "Bearbeiten"
 * button even though `canEditMembershipTierOf` alone would have allowed the "Tarif entfernen"
 * action -- the newly added "Beitragstarif" section was, for exactly the rows it was built for,
 * unreachable.
 *
 * Security fix (Welle V1.4.4.4 review, MAJOR finding, follow-up): `canEditMembershipTierOf` itself
 * now applies the SAME self-target/Peer-Schutz gate as the other four predicates (see its own
 * KDoc), so the specific scenario the paragraph above describes -- an escalated-role target with a
 * removable tier -- is once again correctly `false` for a non-ADMIN caller across ALL FIVE
 * predicates: the earlier fix had accidentally re-opened exactly the self-/peer-benefit hole this
 * function exists to close, just through the newest of the five sections instead of one of the
 * original four.
 */
fun hasAnyEditableSectionFor(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean =
    canEditCoreDataOf(callerRole, row) ||
        canChangeStatusOf(callerRole, callerMemberId, row) ||
        canEditRoleOf(callerRole, callerMemberId, row) ||
        canGrantAccountTo(callerRole, row) ||
        canEditMembershipTierOf(callerRole, callerMemberId, row) ||
        canCorrectDateOfDeathOf(callerRole, callerMemberId, row)

/**
 * Welle V1.4.4.4 "Familienmitgliedschaften" -- ob der Abschnitt "Beitragstarif" in
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
                "Dieses Mitglied hat kein Login-Konto -- ein Statuswechsel erzeugt keines. Ohne zugeordneten " +
                    "Beitragstarif entstehen außerdem keine Beiträge.",
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

private fun renderDirectMemberCreation(root: SimplePanel) {
    root.h2(tr("Mitglied direkt anlegen"))
    root.p(
        tr(
            "Legt ein Mitglied ohne Antrags-/Freigabeschritt an (z. B. für Beitritte auf Papier oder " +
                "Datenmigration) -- Status sofort Aktiv.",
        ),
    )

    val callerRole = AppState.session?.role ?: AccountRole.MEMBER
    val roleOptions = selectableRolesFor(callerRole).map { it.name to it.name }

    val nameInput = root.text(label = tr("Name"))
    val emailInput = root.text(type = InputType.EMAIL, label = tr("E-Mail"))
    val passwordInput = root.password(label = gettext("Vorläufiges Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH))
    val roleSelect = root.select(options = roleOptions, value = roleOptions.firstOrNull()?.first, label = tr("Rolle"))
    if (roleOptions.size == 1) {
        root.p(tr("Als Vorstand können Sie hier nur reguläre Mitglieder anlegen -- Vorstand/Schatzmeister/Admin ist Admin vorbehalten."))
    }
    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = root.button(tr("Mitglied anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val email = emailInput.value.orEmpty().trim()
        val temporaryPassword = passwordInput.value.orEmpty()
        val roleValue = roleSelect.value

        if (!Validation.isNonBlank(name) || !Validation.looksLikeEmail(email) || roleValue == null) {
            errorBox.content = tr("Bitte Name, eine gültige E-Mail-Adresse und eine Rolle angeben.")
            errorBox.show()
            return@onClick
        }
        val passwordHint = Validation.passwordHint(temporaryPassword, email)
        if (passwordHint != null) {
            errorBox.content = passwordHint
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IRegistrationService>().createMemberDirect(
                        AdminCreateMemberInput(
                            displayName = name,
                            email = email,
                            role = AccountRole.valueOf(roleValue),
                            temporaryPassword = temporaryPassword,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("%1 wurde angelegt.", name))
                nameInput.value = null
                emailInput.value = null
                passwordInput.value = null
            }
        }
    }
}
