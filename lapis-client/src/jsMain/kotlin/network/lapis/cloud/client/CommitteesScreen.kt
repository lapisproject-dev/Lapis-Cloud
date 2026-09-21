package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
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
import network.lapis.cloud.shared.domain.rank
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
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 720.px
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

internal fun renderCommitteeEditForm(
    panel: SimplePanel,
    committee: CommitteeDto,
    onSaved: () -> Unit,
) {
    // Formular-Grammatik (V1.4.29, W4b): Name und Quorum sind Pflicht, Beschreibung optional => Fall (a).
    val form = panel.lapisForm()
    val typeOptions = CommitteeType.entries.map { it.name to committeeTypeLabel(it) }
    val nameField = form.textField(label = tr("Name"), value = committee.name, required = true)
    val typeField = form.selectField(label = tr("Typ"), options = typeOptions, value = committee.type.name)
    val descriptionField = form.textField(label = tr("Beschreibung"), value = committee.description)
    val quorumField =
        form.textField(
            label = tr("Quorum in %"),
            value = committee.quorumPercent.toString(),
            required = true,
            hint = gettext("%1 bis %2.", 0, 100),
            rule = { FormRules.intInRange(value = it, min = 0, max = 100) },
        )
    val activeField = form.checkField(label = tr("Aktiv"), value = committee.active)

    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val name = nameField.value.trim()
            // Fail-safe wie im Alt-Code: ein leerer Typ (nicht Pflicht, hier nie zu erwarten) sendet nichts statt zu werfen.
            val type = parseOptionalEnum<CommitteeType>(typeField.value) ?: return@submit
            val result =
                guarded {
                    rpcService<IGovernanceService>().updateCommittee(
                        committee.id,
                        CommitteeInput(
                            name = name,
                            type = type,
                            description = descriptionField.value.trim(),
                            quorumPercent = quorumField.value.trim().toInt(),
                            active = (activeField.control as CheckBox).value,
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("\"%1\" wurde aktualisiert.", name))
                onSaved()
            }
        }
    }
}

internal fun renderCommitteeCreation(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val form = panel.lapisForm()
    val typeOptions = CommitteeType.entries.map { it.name to committeeTypeLabel(it) }
    val nameField = form.textField(label = tr("Name"), required = true)
    val typeField = form.selectField(label = tr("Typ"), options = typeOptions, value = CommitteeType.WORKING_GROUP.name)
    val descriptionField = form.textField(label = tr("Beschreibung"))
    val quorumField =
        form.textField(
            label = tr("Quorum in %"),
            value = "50",
            required = true,
            hint = gettext("%1 bis %2.", 0, 100),
            rule = { FormRules.intInRange(value = it, min = 0, max = 100) },
        )

    val createButton = Button(tr("Gremium anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val type = parseOptionalEnum<CommitteeType>(typeField.value) ?: return@submit
            val result =
                guarded {
                    rpcService<IGovernanceService>().createCommittee(
                        CommitteeInput(
                            name = name,
                            type = type,
                            description = descriptionField.value.trim(),
                            quorumPercent = quorumField.value.trim().toInt(),
                            active = true,
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("\"%1\" wurde angelegt.", name))
                nameField.reset()
                descriptionField.reset()
                quorumField.setValue("50")
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
internal fun endCommitteeMembershipDialog(
    memberDisplayName: String,
    onConfirm: (LocalDate) -> Unit,
) {
    val modal = Modal(caption = tr("Mitgliedschaft beenden"))
    modal.p(gettext("Mitgliedschaft von \"%1\" wirklich beenden?", memberDisplayName))
    // Formular-Grammatik (V1.4.29): ein einziges Pflichtfeld (Enddatum): Stern und Legende (kein Fall (c)), keine Sammelmeldung (Einfeld-Formular).
    val form = modal.lapisForm()
    val untilField =
        form.textField(
            label = tr("Enddatum"),
            value = todayIso(),
            required = true,
            hint = gettext("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(value = it) },
        )
    form.finish()
    modal.addButton(
        Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } },
    )
    modal.addButton(
        Button(tr("Mitgliedschaft beenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                if (!form.validateAndReport()) return@onClick
                val until = LocalDate.parse(untilField.value.trim())
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
internal fun renderAddCommitteeMemberForm(
    panel: SimplePanel,
    committeeId: String,
    onAdded: () -> Unit,
) {
    panel.p(tr("Mitglied hinzufügen")) { addCssClass("fw-bold") }
    // Formular-Grammatik (V1.4.29): drei Pflichtfelder => Fall (b), Legende "Alle Felder sind Pflichtfelder.".
    val form = panel.lapisForm()
    val roleOptions = CommitteeRole.entries.sortedBy { it.rank }.map { it.name to committeeRoleLabel(it) }
    val memberField =
        form.selectField(
            label = tr("Mitglied"),
            options = emptyList(),
            required = true,
            requiredMessage = gettext("Bitte ein Mitglied auswählen."),
        )
    val memberSelect = memberField.control as Select
    val roleField = form.selectField(label = tr("Rolle"), options = roleOptions, value = CommitteeRole.MEMBER.name, required = true)
    val sinceField =
        form.textField(
            label = tr("Seit"),
            value = todayIso(),
            required = true,
            hint = gettext("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(value = it) },
        )

    AppScope.launch {
        val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        memberSelect.options = members.map { it.id to it.displayName }
        memberField.setValue(members.firstOrNull()?.id)
        memberField.validate(force = false)
    }

    val addButton = Button(tr("Mitglied hinzufügen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = addButton)
    addButton.onClick {
        form.submit(addButton) {
            val result =
                guarded {
                    rpcService<IGovernanceService>().addCommitteeMember(
                        committeeId,
                        CommitteeMembershipInput(
                            memberId = memberField.value,
                            role = CommitteeRole.valueOf(roleField.value),
                            since = LocalDate.parse(sinceField.value.trim()),
                        ),
                    )
                }
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
        CommitteeRole.GENERAL_SECRETARY -> gettext("Generalsekretariat")
        CommitteeRole.PRESS_SPOKESPERSON -> gettext("Pressesprecher")
        CommitteeRole.MANAGING_DIRECTOR -> gettext("Geschäftsführung")
    }

fun committeeRoleColor(role: CommitteeRole): String =
    when (role) {
        CommitteeRole.CHAIR -> "primary"
        CommitteeRole.DEPUTY_CHAIR -> "info"
        CommitteeRole.SECRETARY -> "dark"
        CommitteeRole.MEMBER -> "secondary"
        CommitteeRole.ASSESSOR -> "info"
        CommitteeRole.GENERAL_SECRETARY -> "primary"
        CommitteeRole.PRESS_SPOKESPERSON -> "secondary"
        CommitteeRole.MANAGING_DIRECTOR -> "dark"
    }
