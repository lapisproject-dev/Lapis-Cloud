package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
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
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import kotlin.time.Clock

/**
 * Governance UI wave, screen 1 of 3 -- "Gremien" (Committees & Membership), per the approved plan
 * + UI/UX-Design-Team review on `feature/governance-ui`. Two sub-areas in one screen, mirroring
 * `DocumentsScreen.kt`'s list-then-detail shape: the committee directory (with BOARD/ADMIN-only
 * create/edit), and a per-committee membership roster (with BOARD/ADMIN-only add/end-membership).
 *
 * Role gating matches the server exactly (`GovernanceService.kt`: `createCommittee`/
 * `updateCommittee`/`addCommitteeMember`/`endCommitteeMembership` all call
 * `current.requireRole(*BOARD_ROLES)`, i.e. strictly global BOARD/ADMIN -- committee leadership
 * (CHAIR/DEPUTY_CHAIR/SECRETARY) does NOT qualify for these four actions, unlike the
 * Meetings/Motions screens' `canRecordForMeeting`-gated actions). `canManage` below is therefore a
 * plain `AppState.hasRole(BOARD, ADMIN)` check, same posture as `DocumentsScreen.canManage` -- a UX
 * nicety on top of the server's real authority, not the actual security boundary.
 *
 * "Add committee member" non-ACTIVE-target requirement (plan §5): the member picker is populated
 * from `IMemberService.listMembers()`, which is already ACTIVE-filtered server-side -- a non-ACTIVE
 * member simply cannot be selected through this form in the first place. The only residual case is
 * a race (status changes between page load and submit), which correctly falls through to
 * `guarded()`'s generic "Keine Berechtigung für diese Aktion" toast -- both the caller-role check
 * and the target-status check throw the identical `ForbiddenException` on the wire (see plan §5),
 * so no more specific client-side message is possible today.
 */
fun renderCommitteesScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 720.px
            marginTop = 24.px
        }
    root.h1(tr("Gremien"))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)

    root.h2(tr("Übersicht"))
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Gremien anzeigen"))
    val committeePanel = root.vPanel(spacing = 6)

    root.h2(tr("Mitglieder"))
    val rosterPanel = root.vPanel(spacing = 6)
    rosterPanel.p(tr("Gremium oben auswählen, um die Besetzung zu sehen."))

    fun selectCommittee(committee: CommitteeDto) {
        renderCommitteeRoster(rosterPanel, committee, canManage)
    }

    fun refreshCommittees() {
        committeePanel.removeAll()
        AppScope.launch {
            val committees =
                guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = !includeInactiveCheck.value) }
                    ?: return@launch
            if (committees.isEmpty()) {
                committeePanel.p(tr("Noch keine Gremien vorhanden."))
                return@launch
            }
            committees.forEach { committee ->
                renderCommitteeRow(committeePanel, committee, canManage, ::refreshCommittees, ::selectCommittee)
            }
        }
    }

    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    refreshButton.onClick { refreshCommittees() }
    refreshCommittees()

    if (canManage) {
        root.h2(tr("Neues Gremium anlegen"))
        renderCommitteeCreation(root, ::refreshCommittees)
    }
}

private fun renderCommitteeRow(
    panel: SimplePanel,
    committee: CommitteeDto,
    canManage: Boolean,
    onChanged: () -> Unit,
    onSelect: (CommitteeDto) -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(committee.name) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(committeeTypeLabel(committee.type), committeeTypeColor(committee.type))
    headerRow.statusBadge(if (committee.active) tr("Aktiv") else tr("Inaktiv"), if (committee.active) "success" else "secondary")

    if (committee.description.isNotBlank()) row.p(committee.description) { addCssClass("mb-0") }
    row.div(gettext("Quorum: %1%", committee.quorumPercent)) { addCssClasses("text-muted small") }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.button(tr("Mitglieder anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(committee) }
    if (canManage) {
        val editButton = actionRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                renderCommitteeEditForm(editPanel, committee) {
                    editPanel.hide()
                    onChanged()
                }
                editPanel.show()
            } else {
                editPanel.hide()
            }
        }
    }
}

private fun renderCommitteeEditForm(
    panel: SimplePanel,
    committee: CommitteeDto,
    onSaved: () -> Unit,
) {
    val typeOptions = CommitteeType.entries.map { it.name to committeeTypeLabel(it) }
    val nameInput = panel.text(value = committee.name, label = tr("Name"))
    val typeSelect = panel.select(options = typeOptions, value = committee.type.name, label = tr("Typ"))
    val descriptionInput = panel.text(value = committee.description, label = tr("Beschreibung"))
    val quorumInput = panel.text(value = committee.quorumPercent.toString(), label = tr("Quorum in % (0-100)"))
    val activeCheck = panel.checkBox(value = committee.active, label = tr("Aktiv"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = panel.button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val typeValue = typeSelect.value
        val description = descriptionInput.value.orEmpty().trim()
        val quorumPercent =
            quorumInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()

        if (!Validation.isNonBlank(name) || typeValue == null || quorumPercent == null || quorumPercent !in 0..100) {
            errorBox.content = tr("Bitte Name, Typ und ein gültiges Quorum (0-100) angeben.")
            errorBox.show()
            return@onClick
        }

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().updateCommittee(
                        committee.id,
                        CommitteeInput(
                            name = name,
                            type = CommitteeType.valueOf(typeValue),
                            description = description,
                            quorumPercent = quorumPercent,
                            active = activeCheck.value,
                        ),
                    )
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("\"%1\" wurde aktualisiert.", name))
                onSaved()
            }
        }
    }
}

private fun renderCommitteeCreation(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val typeOptions = CommitteeType.entries.map { it.name to committeeTypeLabel(it) }
    val panel = root.vPanel(spacing = 6)
    val nameInput = panel.text(label = tr("Name"))
    val typeSelect = panel.select(options = typeOptions, value = CommitteeType.WORKING_GROUP.name, label = tr("Typ"))
    val descriptionInput = panel.text(label = tr("Beschreibung"))
    val quorumInput = panel.text(value = "50", label = tr("Quorum in % (0-100)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button(tr("Gremium anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val typeValue = typeSelect.value
        val description = descriptionInput.value.orEmpty().trim()
        val quorumPercent =
            quorumInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()

        if (!Validation.isNonBlank(name) || typeValue == null || quorumPercent == null || quorumPercent !in 0..100) {
            errorBox.content = tr("Bitte Name, Typ und ein gültiges Quorum (0-100) angeben.")
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().createCommittee(
                        CommitteeInput(
                            name = name,
                            type = CommitteeType.valueOf(typeValue),
                            description = description,
                            quorumPercent = quorumPercent,
                            active = true,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("\"%1\" wurde angelegt.", name))
                nameInput.value = null
                descriptionInput.value = null
                quorumInput.value = "50"
                onCreated()
            }
        }
    }
}

private fun renderCommitteeRoster(
    rosterPanel: SimplePanel,
    committee: CommitteeDto,
    canManage: Boolean,
) {
    rosterPanel.removeAll()
    rosterPanel.h2(gettext("Besetzung: %1", committee.name)) { addCssClass("h5") }
    val rosterFilterRow = rosterPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeEndedCheck = rosterFilterRow.checkBox(label = tr("Ausgeschiedene anzeigen"))
    val rosterListPanel = rosterPanel.vPanel(spacing = 4)
    val addMemberPanel = if (canManage) rosterPanel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") } else null

    fun refreshRoster() {
        rosterListPanel.removeAll()
        AppScope.launch {
            val memberships =
                guarded {
                    rpcService<IGovernanceService>().listCommitteeMembers(committee.id, activeOnly = !includeEndedCheck.value)
                } ?: return@launch
            if (memberships.isEmpty()) {
                rosterListPanel.p(tr("Noch keine Mitglieder in diesem Gremium."))
                return@launch
            }
            memberships.forEach { membership -> renderRosterRow(rosterListPanel, membership, canManage, ::refreshRoster) }
        }
    }

    val rosterRefreshButton = rosterFilterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    rosterRefreshButton.onClick { refreshRoster() }
    refreshRoster()

    if (canManage && addMemberPanel != null) {
        renderAddCommitteeMemberForm(addMemberPanel, committee.id, ::refreshRoster)
    }
}

private fun renderRosterRow(
    panel: SimplePanel,
    membership: CommitteeMembershipDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
    row.div(membership.memberDisplayName) { addCssClasses("flex-grow-1") }
    row.typeBadge(committeeRoleLabel(membership.role), committeeRoleColor(membership.role))
    val period =
        if (membership.until != null) {
            gettext("%1 – %2", membership.since, membership.until)
        } else {
            gettext("seit %1", membership.since)
        }
    row.div(period) { addCssClasses("text-muted small") }

    if (canManage && membership.until == null) {
        val endButton = row.button(tr("Mitgliedschaft beenden"), style = ButtonStyle.OUTLINEDANGER)
        endButton.onClick {
            endCommitteeMembershipDialog(membership.memberDisplayName) { until ->
                AppScope.launch {
                    val result = guarded { rpcService<IGovernanceService>().endCommitteeMembership(membership.id, until) }
                    if (result != null) {
                        notifyInfo(gettext("Mitgliedschaft von %1 wurde beendet.", membership.memberDisplayName))
                        onChanged()
                    }
                }
            }
        }
    }
}

/** Real confirm step with an `until` date -- mirrors `rejectApplicationDialog`'s "needs one extra
 * input, [confirmDialog] has no input field of its own" pattern from `MemberAdministrationScreen.kt`. */
private fun endCommitteeMembershipDialog(
    memberDisplayName: String,
    onConfirm: (LocalDate) -> Unit,
) {
    val modal = Modal(caption = tr("Mitgliedschaft beenden"))
    modal.p(gettext("Mitgliedschaft von \"%1\" wirklich beenden?", memberDisplayName))
    val untilInput = modal.text(value = todayIso(), label = tr("Enddatum (JJJJ-MM-TT)"))
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(
        Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } },
    )
    modal.addButton(
        Button(tr("Mitgliedschaft beenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                val until = runCatching { LocalDate.parse(untilInput.value.orEmpty().trim()) }.getOrNull()
                if (until == null) {
                    errorBox.content = tr("Bitte ein gültiges Datum (JJJJ-MM-TT) angeben.")
                    errorBox.show()
                    return@onClick
                }
                modal.hide()
                onConfirm(until)
            }
        },
    )
    modal.show()
}

/**
 * Member picker sourced from `IMemberService.listMembers()` -- same ACTIVE-filtered directory
 * `MemberAdministrationScreen.renderMemberDirectory` already uses (see plan §5: this structurally
 * satisfies "clear error for a non-ACTIVE target", a non-ACTIVE member cannot be selected here).
 */
private fun renderAddCommitteeMemberForm(
    panel: SimplePanel,
    committeeId: String,
    onAdded: () -> Unit,
) {
    panel.p(tr("Mitglied hinzufügen")) { addCssClass("fw-bold") }
    val roleOptions = CommitteeRole.entries.map { it.name to committeeRoleLabel(it) }
    val memberSelect = panel.select(options = emptyList(), label = tr("Mitglied"))
    val roleSelect = panel.select(options = roleOptions, value = CommitteeRole.MEMBER.name, label = tr("Rolle"))
    val sinceInput = panel.text(value = todayIso(), label = tr("Seit (JJJJ-MM-TT)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    var members: List<MemberSummaryDto> = emptyList()
    AppScope.launch {
        members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        memberSelect.options = members.map { it.id to it.displayName }
        memberSelect.value = members.firstOrNull()?.id
    }

    val addButton = panel.button(tr("Mitglied hinzufügen"), style = ButtonStyle.PRIMARY)
    addButton.onClick {
        errorBox.hide()
        val memberId = memberSelect.value
        val roleValue = roleSelect.value
        val since = runCatching { LocalDate.parse(sinceInput.value.orEmpty().trim()) }.getOrNull()

        if (memberId == null || roleValue == null || since == null) {
            errorBox.content = tr("Bitte Mitglied, Rolle und ein gültiges Datum (JJJJ-MM-TT) angeben.")
            errorBox.show()
            return@onClick
        }

        addButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().addCommitteeMember(
                        committeeId,
                        CommitteeMembershipInput(memberId = memberId, role = CommitteeRole.valueOf(roleValue), since = since),
                    )
                }
            addButton.disabled = false
            if (result != null) {
                notifySuccess(tr("Mitglied wurde hinzugefügt."))
                onAdded()
            }
        }
    }
}

/** Today's date as `JJJJ-MM-TT`, used to pre-fill `since`/`until` date-text-inputs -- mirrors
 * `ContributionsScreen.kt`'s `Clock.System.now().toLocalDateTime(...)` idiom (the stdlib
 * `kotlin.time.Clock`, not `kotlinx.datetime.Clock` -- this codebase's pinned kotlinx-datetime
 * version only extends the former). */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

/**
 * German label/badge-color tables for [CommitteeType]/[CommitteeRole] -- this screen's own first
 * concrete instantiation of the [typeBadge] grammar (see `StatusBadge.kt` KDoc). Non-`private` (all
 * four functions) so [CommitteesScreenTest] can cover them directly, same posture as
 * [network.lapis.cloud.client.guestBadgeAriaLabel]/[Validation]'s pure functions.
 */
fun committeeTypeLabel(type: CommitteeType): String =
    when (type) {
        CommitteeType.EXECUTIVE_BOARD -> "Vorstand"
        CommitteeType.WORKING_GROUP -> "Arbeitsgruppe"
        CommitteeType.COMMISSION -> "Kommission"
        CommitteeType.GENERAL_ASSEMBLY -> "Mitgliederversammlung"
        CommitteeType.OTHER -> "Sonstiges"
    }

fun committeeTypeColor(type: CommitteeType): String =
    when (type) {
        CommitteeType.EXECUTIVE_BOARD -> "primary"
        CommitteeType.WORKING_GROUP -> "info"
        CommitteeType.COMMISSION -> "secondary"
        CommitteeType.GENERAL_ASSEMBLY -> "dark"
        CommitteeType.OTHER -> "secondary"
    }

fun committeeRoleLabel(role: CommitteeRole): String =
    when (role) {
        CommitteeRole.CHAIR -> gettext("Vorsitz")
        CommitteeRole.DEPUTY_CHAIR -> gettext("Stellv. Vorsitz")
        CommitteeRole.SECRETARY -> gettext("Schriftführung")
        CommitteeRole.MEMBER -> gettext("Mitglied")
        CommitteeRole.ASSESSOR -> gettext("Beisitz")
    }

fun committeeRoleColor(role: CommitteeRole): String =
    when (role) {
        CommitteeRole.CHAIR -> "primary"
        CommitteeRole.DEPUTY_CHAIR -> "info"
        CommitteeRole.SECRETARY -> "dark"
        CommitteeRole.MEMBER -> "secondary"
        CommitteeRole.ASSESSOR -> "info"
    }
