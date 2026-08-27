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
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusTransitions
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * Screen 4 of the V0.7.3 plan -- BOARD/ADMIN only, route-guarded in `Routing.kt` (never even
 * rendered for a plain MEMBER, per the plan). Three sub-sections in this one file, mirroring the
 * existing `renderXSection` grouping convention the old `App.kt` already used: pending
 * applications (approve/reject), the privileged member roster (Welle V1.2.12 -- see
 * [renderMemberRoster]), and direct member creation.
 */
fun renderMemberAdministrationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 16) {
            addCssClass("mx-auto")
            width = 720.px
            marginTop = 24.px
        }
    root.h1(tr("Mitgliederverwaltung"))

    renderPendingApplications(root)
    renderMemberRoster(root)
    renderDirectMemberCreation(root)
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
 * `IMemberService.listMembersForAdministration` is exactly that interface. BOARD/ADMIN only (the
 * whole screen already is, per this file's own class KDoc) -- server-side re-enforces this
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
        cell(row.displayName)
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
        val editButton = actionsCell.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
        val callerRole = AppState.session?.role
        val callerMemberId = AppState.session?.memberId
        if (row.anonymized) {
            editButton.disabled = true
            editButton.title = tr("DSGVO-gelöscht")
        } else if (!hasAnyEditableSectionFor(callerRole, callerMemberId, row)) {
            // Regression fix (Review Runde 3): before the per-section gating in openMemberEditorDialog
            // existed, "Stammdaten" was rendered UNCONDITIONALLY, so the modal could never be empty.
            // Now that all three sections are individually gated (Peer-Schutz), a BOARD caller on an
            // escalated-role target (or their OWN row, which is itself BOARD/ADMIN/TREASURER-scoped)
            // can hit a state where NONE of the three predicates allow anything -- opening the dialog
            // would show only a title and a "Schließen" button. Same house rule this file's own KDoc
            // on ESCALATED_ROLES already states: "the client does not OFFER an action the server's
            // peer-protection rejects anyway" -- consequently applied here to the button itself, not
            // just to the sections inside a dialog the caller would otherwise be free to open.
            editButton.disabled = true
            editButton.title =
                tr(
                    "Keine Bearbeitung möglich -- Peer-Schutz: Vorstand darf Vorstands-/Schatzmeister-/" +
                        "Admin-Konten (auch das eigene) nicht bearbeiten, das ist Admin vorbehalten.",
                )
        } else {
            editButton.onClick { openMemberEditorDialog(row, onChanged) }
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
        val statusChipsRow = modal.hPanel(spacing = 6)
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

    modal.addButton(Button(tr("Schließen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()
}

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
 * Whether [openMemberEditorDialog] would render AT LEAST ONE of its three sections for [row] --
 * i.e. whether the "Bearbeiten" button in [renderMemberRosterRow] should be enabled at all. Purely
 * `canEditCoreDataOf(...) || canChangeStatusOf(...) || canEditRoleOf(...)`, kept as its own named
 * function (rather than inlined at the one call site) so the three predicates this depends on stay
 * a single, obviously-in-sync list with the three `if`-gates inside [openMemberEditorDialog] --
 * see this file's ESCALATED_ROLES KDoc for why the client mirrors the server's Peer-Schutz boundary
 * at all: an escalated-role target (or, for a BOARD caller, their OWN row -- BOARD/ADMIN/TREASURER
 * is itself an escalated role) can leave all three predicates `false` at once, which without this
 * check would previously open a modal with a title, an empty body, and only a "Schließen" button.
 */
fun hasAnyEditableSectionFor(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean =
    canEditCoreDataOf(callerRole, row) ||
        canChangeStatusOf(callerRole, callerMemberId, row) ||
        canEditRoleOf(callerRole, callerMemberId, row)

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
