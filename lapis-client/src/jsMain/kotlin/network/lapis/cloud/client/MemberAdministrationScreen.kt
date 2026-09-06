package network.lapis.cloud.client

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
import io.kvision.panel.vPanel
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
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
    val root =
        container.vPanel(spacing = 16) {
            addCssClass("mx-auto")
            width = 720.px
            marginTop = 24.px
        }
    root.h1(tr("Mitgliederverwaltung"))

    val callerRole = AppState.session?.role
    val isBoardOrAdmin = callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
    if (isBoardOrAdmin) renderPendingApplications(root)
    renderMemberRoster(root)
    if (isBoardOrAdmin) renderDirectMemberCreation(root)
}

private fun renderPendingApplications(root: SimplePanel) {
    root.h2(tr("Offene Anträge"))
    val pendingPanel = root.vPanel(spacing = 6)

    fun refresh() {
        pendingPanel.removeAll()
        AppScope.launch {
            val applications = guarded { rpcService<IRegistrationService>().listPendingApplications() } ?: return@launch
            if (applications.isEmpty()) {
                pendingPanel.p(tr("Keine offenen Anträge."))
                return@launch
            }
            // UI theme redesign wave (2026-08-20): real Bootstrap table (table-striped/table-hover),
            // replacing the previous hand-rolled "border rounded p-2" hPanel-per-row layout -- see
            // root CLAUDE.md "UI/UX-Design-Team" review. `MemberDto.role` (unlike `MemberSummaryDto`,
            // see `renderMemberDirectory` below) IS available here, so the "Rolle" column uses the
            // new [accountRoleBadge] semantic role badge.
            val table =
                pendingPanel.table(
                    headerNames = listOf(tr("Antragsteller"), tr("Rolle"), tr("Aktionen")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            applications.forEach { application ->
                renderPendingApplicationRow(table, application, onChanged = ::refresh)
            }
        }
    }
    refresh()
}

private fun renderPendingApplicationRow(
    table: Table,
    application: MemberDto,
    onChanged: () -> Unit,
) {
    table.row {
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
        val label = if (friendSince != null) gettext("%1 (Freund-Konto seit %2)", summary, friendSince) else summary
        cell(label)
        cell { accountRoleBadge(application.role) }
        val actionsCell = cell()
        val actionsRow = actionsCell.hPanel(spacing = 8)
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
private data class RosterState(
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

    val filterRow = root.hPanel(spacing = 8)
    val searchInput = filterRow.text(label = tr("Suche nach Name, E-Mail oder Personennummer"))
    val chipsRow = root.hPanel(spacing = 6)
    val tablePanel = root.vPanel(spacing = 2)
    val pagerRow = root.hPanel(spacing = 8)

    lateinit var refresh: () -> Unit
    lateinit var chipButtons: Map<MemberStatus?, Button>

    fun loadAndRender() {
        tablePanel.removeAll()
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IMemberService>().listMembersForAdministration(
                        MemberAdminQuery(
                            search = state.search.ifBlank { null },
                            statuses = state.statuses,
                            sort = state.sort,
                            offset = state.offset,
                        ),
                    )
                } ?: return@launch

            chipButtons.forEach { (status, button) ->
                val count = if (status == null) page.statusCounts.values.sum() else page.statusCounts[status] ?: 0
                button.text = "${status?.let { memberStatusLabel(it) } ?: tr("Alle")} ($count)"
            }

            if (page.rows.isEmpty()) {
                tablePanel.p(tr("Keine Treffer."))
            } else {
                val table =
                    tablePanel.table(
                        headerNames = listOf(tr("Name"), tr("E-Mail"), tr("Status"), tr("Rolle"), tr("Beitritt"), tr("Aktion")),
                        types = setOf(TableType.STRIPED, TableType.HOVER),
                    )
                page.rows.forEach { row -> renderMemberRosterRow(table, row, onChanged = { refresh() }) }
            }

            pagerRow.removeAll()
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
        }
    }
    refresh = ::loadAndRender

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

private fun renderMemberRosterRow(
    table: Table,
    row: MemberAdminRowDto,
    onChanged: () -> Unit,
) {
    table.row {
        cell {
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
        cell(row.email)
        cell { memberStatusRoleBadge(row.status) }
        cell {
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
        cell(row.joinedAt.toString())
        val actionsCell = cell()
        // GitHub issue #1 -- icon instead of text, so the actions column stays narrow at any table
        // width; `title` is set unconditionally right below and is KVision's own `Widget.title`
        // property (not a raw DOM write), so no `###KvI18nS###` marker-leak risk -- see
        // `ConferenceScreen.kt`'s own KDoc on that bug class for why the distinction matters.
        val editButton = actionsCell.button("", icon = "fas fa-pen", style = ButtonStyle.OUTLINEPRIMARY)
        editButton.title = tr("Bearbeiten")
        val callerRole = AppState.session?.role
        val callerMemberId = AppState.session?.memberId
        if (row.anonymized) {
            editButton.disabled = true
            editButton.title = tr("DSGVO-gelöscht")
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
            editButton.title =
                tr(
                    "Keine Bearbeitung möglich -- Peer-Schutz: Vorstand darf Vorstands-/Schatzmeister-/" +
                        "Admin-Konten (auch das eigene) nicht bearbeiten, das ist Admin vorbehalten.",
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
            val financesButton = actionsCell.button("", icon = "fas fa-receipt", style = ButtonStyle.OUTLINESECONDARY)
            financesButton.title = tr("Beitragshistorie")
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
            val honorsButton = actionsCell.button("", icon = "fas fa-medal", style = ButtonStyle.OUTLINESECONDARY)
            honorsButton.title = tr("Ehrungen")
            if (row.anonymized) {
                honorsButton.disabled = true
                honorsButton.title = tr("DSGVO-gelöscht")
            } else {
                honorsButton.onClick { navigateTo(memberHonorsRoute(row.id)) }
            }
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
            consequenceBox.content = statusChangeConsequence(row.status, target, hasAccount = row.role != null)
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
            AppScope.launch {
                val result = memberAdminGuarded { rpcService<IMemberService>().updateMemberStatus(row.id, target, reason) }
                if (result != null) {
                    notifySuccess(tr("Status geändert."))
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
            modal.p(gettext("Aktueller Tarif: %1", row.membershipTierName ?: tr("beitragsfrei")))
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
            modal.p(gettext("Aktueller Tarif: %1", row.membershipTierName ?: tr("beitragsfrei")))
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
 * Whether [openMemberEditorDialog] would render AT LEAST ONE of its five sections for [row] --
 * i.e. whether the "Bearbeiten" button in [renderMemberRosterRow] should be enabled at all. Purely
 * `canEditCoreDataOf(...) || canChangeStatusOf(...) || canEditRoleOf(...) || canGrantAccountTo(...)
 * || canEditMembershipTierOf(...)`, kept as its own named function (rather than inlined at the one
 * call site) so the five predicates this depends on stay a single, obviously-in-sync list with the
 * five `if`-gates inside [openMemberEditorDialog] -- see this file's ESCALATED_ROLES KDoc for why
 * the client mirrors the server's Peer-Schutz boundary at all: an escalated-role target (or, for a
 * BOARD caller, their OWN row -- BOARD/ADMIN/TREASURER is itself an escalated role) can leave all
 * five predicates `false` at once, which without this check would previously open a modal with a
 * title, an empty body, and only a "Schließen" button.
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
        canEditMembershipTierOf(callerRole, callerMemberId, row)

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

/** Reine, DOM-freie Funktion -- der Konsequenztext des Editor-Dialogs, live vor dem Speichern gerendert. */
fun statusChangeConsequence(
    from: MemberStatus,
    to: MemberStatus,
    hasAccount: Boolean,
): String =
    when {
        to == MemberStatus.WITHDRAWN || to == MemberStatus.DECEASED ->
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
