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
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * Screen 4 of the V0.7.3 plan -- BOARD/ADMIN only, route-guarded in `Routing.kt` (never even
 * rendered for a plain MEMBER, per the plan). Three sub-sections in this one file, mirroring the
 * existing `renderXSection` grouping convention the old `App.kt` already used:
 * pending applications (approve/reject), the active-member directory/search, and direct member
 * creation.
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
    renderMemberDirectory(root)
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

/**
 * `IMemberService.listMembers()` returns id+displayName only, deliberately -- it is still
 * reachable unauthenticated (the historical "picker" bootstrap endpoint, ACTIVE-filtered since
 * V0.7.2), and there is no privileged read RPC for another member's email/role/address. This
 * directly bounds what this directory can show -- see V0.7.3 plan "Open Question 2". A small
 * follow-up wave adding a BOARD/ADMIN-gated detailed read would improve this; not added here
 * without being asked for, to avoid adding new backend surface as a side effect of a UI wave.
 */
private fun renderMemberDirectory(root: SimplePanel) {
    root.h2(tr("Mitgliederverzeichnis"))
    root.p(
        tr(
            "Aktive Mitglieder nach Name. E-Mail/Rolle/Adresse sind hier aus Datenschutzgründen nicht " +
                "einsehbar -- dafür existiert aktuell keine privilegierte Leseschnittstelle.",
        ),
    )
    val searchRow = root.hPanel(spacing = 8)
    val searchInput = searchRow.text(label = tr("Suche nach Name"))
    val directoryPanel = root.vPanel(spacing = 2)

    var allMembers: List<MemberSummaryDto> = emptyList()

    fun renderDirectory(filter: String) {
        directoryPanel.removeAll()
        val filtered =
            if (filter.isBlank()) {
                allMembers
            } else {
                allMembers.filter { it.displayName.contains(filter, ignoreCase = true) }
            }
        if (filtered.isEmpty()) {
            directoryPanel.p(tr("Keine Treffer."))
        } else {
            // UI theme redesign wave (2026-08-20): real Bootstrap table, replacing the previous
            // hand-rolled "border-bottom py-1" div-per-row list. Single "Name" column only -- no
            // role/status badge here, unlike renderPendingApplicationRow above: `listMembers()`
            // returns [MemberSummaryDto] (id+displayName only, deliberately -- see this file's own
            // `renderMemberDirectory` KDoc), which carries no role/status to badge.
            val table =
                directoryPanel.table(
                    headerNames = listOf(tr("Name")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            filtered.forEach { member -> table.row { cell(member.displayName) } }
        }
    }

    val searchButton = searchRow.button(tr("Suchen"), style = ButtonStyle.OUTLINESECONDARY)
    searchButton.onClick { renderDirectory(searchInput.value.orEmpty()) }

    AppScope.launch {
        allMembers = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        renderDirectory("")
    }
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
