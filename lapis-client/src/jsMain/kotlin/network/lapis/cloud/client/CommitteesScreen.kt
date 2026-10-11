package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeElectionSeatChangeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeSeatChangeKind
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.rank
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService

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
    val header = root.pageHeader(tr("Gremien"))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    // V1.9.40 (R36B): the create form is collapsed; its button sits in the title row and the form opens right under the header.
    val createHost = if (canManage) root.vPanel(spacing = 6) else null
    // V1.9.102: notice band (automatic ends, vacant chair, legacy open seats) -- BOARD/ADMIN only, reloaded after every seat change.
    val reloadAutoEndNotice = if (canManage) renderCommitteeAutoEndNotice(root.vPanel(spacing = 4)) else ({})

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.lapisToolbar()
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Gremien anzeigen"))
    val committeePanel = root.vPanel(spacing = 6)

    root.h2(tr("Mitglieder")) { addCssClass("h5") }
    val rosterPanel = root.vPanel(spacing = 6)
    rosterPanel.p(tr("Gremium oben auswählen, um die Besetzung zu sehen."))

    fun selectCommittee(committee: CommitteeDto) {
        renderCommitteeRoster(rosterPanel, committee, canManage, onSeatsChanged = reloadAutoEndNotice)
    }

    fun refreshCommittees() {
        committeePanel.removeAll()
        AppScope.launch {
            val committees =
                guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = !includeInactiveCheck.value) }
                    ?: return@launch
            if (committees.isEmpty()) {
                committeePanel.p(
                    if (canManage) {
                        tr(
                            "Noch keine Gremien. Mit \"Neues Gremium\" legen Sie eines an.",
                        )
                    } else {
                        tr("Noch keine Gremien vorhanden.")
                    },
                )
                return@launch
            }
            committees.forEach { committee ->
                renderCommitteeRow(committeePanel, committee, canManage, ::refreshCommittees, ::selectCommittee)
            }
        }
    }

    val refreshButton = filterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    refreshButton.onClick { refreshCommittees() }
    refreshCommittees()

    if (createHost != null) {
        collapsibleCreateForm<Unit>(
            actionSlot = header.actionSlot,
            formHost = createHost,
            buttonLabel = tr("Neues Gremium"),
            formId = "lapis-create-committee",
        ) { _, close -> renderCommitteeCreation(this, ::refreshCommittees, close) }
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
    // Security audit W6b follow-up round 3 (major finding A): committee name/description are member-editable
    // free text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(committee.name)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(committeeTypeLabel(committee.type), committeeTypeColor(committee.type))
    headerRow.statusBadge(if (committee.active) tr("Aktiv") else tr("Inaktiv"), if (committee.active) "success" else "secondary")

    if (committee.description.isNotBlank()) row.p(sanitizeUntrustedI18nText(committee.description)) { addCssClass("mb-0") }
    row.div(gettext("Quorum: %1%", committee.quorumPercent)) { addCssClasses("text-muted small") }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.actionButton(ActionIcon.VIEW, tr("Mitglieder anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(committee) }
    if (canManage) {
        val editButton = actionRow.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
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

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
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
    collapse: ((saved: Boolean) -> Unit)? = null,
): FormSnapshot {
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

    val createButton = newActionButton(ActionIcon.ADD, tr("Gremium anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapse?.let { collapseCancelButton(it) })
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
                collapse?.invoke(true)
            }
        }
    }
    return form.snapshot()
}

internal fun renderCommitteeRoster(
    rosterPanel: SimplePanel,
    committee: CommitteeDto,
    canManage: Boolean,
    onSeatsChanged: () -> Unit = {},
) {
    rosterPanel.removeAll()
    if (committee.type == CommitteeType.GENERAL_ASSEMBLY) {
        renderGeneralAssemblyRoster(rosterPanel, committee, canManage, onSeatsChanged)
        return
    }
    // Security audit follow-up (untrusted-text sanitization gaps): committee.name is admin-editable free text
    // composed into a gettext(...) string -- sanitize the whole composed result, same as the raw committee.name
    // rendering in renderCommitteeRow above in this same file.
    rosterPanel.h2(sanitizeUntrustedI18nText(gettext("Besetzung: %1", committee.name))) { addCssClass("h5") }
    val rosterFilterRow = rosterPanel.lapisToolbar()
    val includeEndedCheck = rosterFilterRow.checkBox(label = tr("Ausgeschiedene anzeigen"))
    // Name filter over the roster that is already fully loaded (`listCommitteeMembers` has no paging): hidden while it is empty.
    val rosterFilter = rosterPanel.listFilterField()
    rosterFilter.setVisible(false)
    val rosterListPanel = rosterPanel.vPanel(spacing = 4)
    val addMemberPanel = if (canManage) rosterPanel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") } else null

    var loadedMemberships: List<CommitteeMembershipDto> = emptyList()
    lateinit var seatsChanged: () -> Unit

    fun refreshRoster() {
        rosterListPanel.removeAll()
        AppScope.launch {
            val memberships =
                guarded {
                    rpcService<IGovernanceService>().listCommitteeMembers(committee.id, activeOnly = !includeEndedCheck.value)
                } ?: return@launch
            loadedMemberships = memberships
            renderRosterRows(rosterListPanel, rosterFilter, loadedMemberships, canManage, seatsChanged)
        }
    }
    // A seat added or ended by hand changes what the notice band shows (vacant chair), so the band reloads too.
    seatsChanged = {
        refreshRoster()
        onSeatsChanged()
    }
    rosterFilter.subscribe { renderRosterRows(rosterListPanel, rosterFilter, loadedMemberships, canManage, seatsChanged) }

    val rosterRefreshButton = rosterFilterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    rosterRefreshButton.onClick { refreshRoster() }
    refreshRoster()

    if (canManage && addMemberPanel != null) {
        renderAddCommitteeMemberForm(addMemberPanel, committee.id, seatsChanged)
    }
}

/**
 * V1.9.101 -- the "Besetzung" of the General Assembly. The assembly IS every active member (automatically, the one
 * `generalAssemblyCondition` predicate behind the quorum, votes and elections), so this view
 *  - shows that fact and the current number N instead of a member list,
 *  - lists only the OFFICES of the assembly (chair, secretary ... -- every role except plain `MEMBER`) and lets BOARD/ADMIN add them
 *    with the server-side person search (never the whole directory),
 *  - keeps earlier single entries (role `MEMBER`) collapsed and read-only: they have no effect on attendance, voting right or
 *    quorum any more. No delete function exists or is added.
 * Offices are real committee rows and DO carry rights (`hasCommitteeRole`: recording meetings, polls, conference binding).
 */
internal fun renderGeneralAssemblyRoster(
    rosterPanel: SimplePanel,
    committee: CommitteeDto,
    canManage: Boolean,
    onSeatsChanged: () -> Unit = {},
) {
    rosterPanel.h2(sanitizeUntrustedI18nText(gettext("Besetzung: %1", committee.name))) { addCssClass("h5") }

    // a) the automatic membership and its size. A failed count must not become an error block: the sentence stands without a number.
    val infoRow = rosterPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    infoRow.icon(ActionIcon.PEOPLE.css) { setAttribute("aria-hidden", "true") }
    val infoText = infoRow.div(tr("Alle aktiven Mitglieder (automatisch)")) { addCssClass("text-body") }
    infoText.setAttribute("aria-live", "polite")
    val countNote =
        rosterPanel.div(tr("Diese Zahl ist die Grundlage für Beschlussfähigkeit, Abstimmungen und Wahlen.")) {
            addCssClasses("text-muted small")
        }
    countNote.hide()
    AppScope.launch {
        val count = runCatching { rpcService<IGovernanceService>().countGeneralAssemblyEligible(committee.id) }.getOrNull()
        if (count != null) {
            infoText.content = gettext("Alle aktiven Mitglieder (automatisch), aktuell %1", count)
            infoText.addCssClass("lapis-tabular-nums")
            countNote.show()
        }
    }

    // b) offices of the assembly (rows with a role other than MEMBER); the switch for ended offices stays with them
    val includeEndedRow = rosterPanel.lapisToolbar()
    val includeEndedCheck = includeEndedRow.checkBox(label = tr("Ausgeschiedene anzeigen"))
    val officesList = rosterPanel.vPanel(spacing = 4)
    val legacyHost = rosterPanel.vPanel(spacing = 4)
    val addOfficePanel = if (canManage) rosterPanel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") } else null

    lateinit var seatsChanged: () -> Unit

    fun refresh() {
        AppScope.launch {
            val memberships =
                guarded {
                    rpcService<IGovernanceService>().listCommitteeMembers(committee.id, activeOnly = !includeEndedCheck.value)
                } ?: return@launch
            val (offices, legacy) = memberships.partition { it.role != CommitteeRole.MEMBER }
            officesList.removeAll()
            if (offices.isNotEmpty() || canManage) {
                officesList.p(tr("Ämter der Versammlung")) { addCssClasses("fw-bold mb-1") }
            }
            if (offices.isEmpty() && canManage) {
                officesList.p(tr("Noch keine Ämter besetzt.")) { addCssClasses("text-muted") }
            }
            offices.forEach { renderRosterRow(officesList, it, canManage, seatsChanged) }
            // c) earlier single entries: collapsed, read-only apart from ending the term, never deleted
            legacyHost.removeAll()
            if (legacy.isNotEmpty()) {
                val details = Tag(TAG.DETAILS, className = "small")
                details.add(Tag(TAG.SUMMARY, content = gettext("Frühere Einzeleinträge (%1)", legacy.size)))
                details.add(
                    Tag(
                        TAG.P,
                        content =
                            tr(
                                "Diese Einträge wirken nicht auf Teilnahme, Stimmrecht oder Beschlussfähigkeit; die " +
                                    "Mitgliederversammlung umfasst automatisch alle aktiven Mitglieder.",
                            ),
                        className = "text-muted mt-2 mb-1",
                    ),
                )
                val legacyRows = SimplePanel()
                legacy.forEach { renderRosterRow(legacyRows, it, canManage, seatsChanged) }
                details.add(legacyRows)
                legacyHost.add(details)
            }
        }
    }
    val refreshButton = includeEndedRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    refreshButton.onClick { refresh() }
    seatsChanged = {
        refresh()
        onSeatsChanged()
    }
    refresh()

    if (addOfficePanel != null) renderAddCommitteeOfficeForm(addOfficePanel, committee.id, seatsChanged)
}

/**
 * V1.9.101 -- adds an OFFICE to the General Assembly: every role except `MEMBER` (membership is automatic), the person found by
 * the server-side search, the unchanged `addCommitteeMember` behind it.
 */
internal fun renderAddCommitteeOfficeForm(
    panel: SimplePanel,
    committeeId: String,
    onAdded: () -> Unit,
) {
    panel.p(tr("Amt vergeben")) { addCssClass("fw-bold") }
    val form = panel.lapisForm()
    val roleOptions = (CommitteeRole.entries - CommitteeRole.MEMBER).sortedBy { it.rank }.map { it.name to committeeRoleLabel(it) }
    val memberField =
        form.remoteMemberPickerField(
            label = tr("Mitglied"),
            search = committeeCandidateSearch(committeeId),
            required = true,
            requiredMessage = gettext("Bitte ein Mitglied auswählen."),
        )
    val roleField = form.selectField(label = tr("Rolle"), options = roleOptions, value = CommitteeRole.CHAIR.name, required = true)
    val sinceField =
        form.textField(
            label = tr("Seit"),
            value = todayIso(),
            required = true,
            hint = gettext("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(value = it) },
        )
    val addButton = newActionButton(ActionIcon.ADD, tr("Amt vergeben"), style = ButtonStyle.PRIMARY)
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
                notifySuccess(tr("Amt wurde vergeben."))
                memberField.reset()
                onAdded()
            }
        }
    }
}

/** Renders the roster through the name filter; the filter text survives reloads because [filter] owns it. */
private fun renderRosterRows(
    listPanel: SimplePanel,
    filter: ListFilter,
    memberships: List<CommitteeMembershipDto>,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    listPanel.removeAll()
    if (memberships.isEmpty()) {
        filter.setVisible(false)
        listPanel.p(tr("Noch keine Mitglieder in diesem Gremium."))
        return
    }
    filter.setVisible(true)
    val visible = filter.apply(memberships, nameOf = { it.memberDisplayName })
    if (visible.isEmpty()) {
        listPanel.p(gettext("Kein Eintrag passt zu \"%1\".", filter.term)) { addCssClasses("text-muted") }
        return
    }
    visible.forEach { membership -> renderRosterRow(listPanel, membership, canManage, onChanged) }
}

private fun renderRosterRow(
    panel: SimplePanel,
    membership: CommitteeMembershipDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
    row.untrustedDiv(membership.memberDisplayName, className = "flex-grow-1")
    row.typeBadge(committeeRoleLabel(membership.role), committeeRoleColor(membership.role))
    val period =
        if (membership.until != null) {
            gettext("%1 – %2", formatDate(membership.since), formatDate(membership.until!!))
        } else {
            gettext("seit %1", formatDate(membership.since))
        }
    row.div(period) { addCssClasses("text-muted small") }
    committeeSeatAutoEndNote(membership)?.let { note -> row.div(note) { addCssClasses("text-muted small fst-italic") } }

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
        newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } },
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
        form.searchableSelectField(
            label = tr("Mitglied"),
            options = emptyList(),
            required = true,
            requiredMessage = gettext("Bitte ein Mitglied auswählen."),
        )
    val memberSelect = memberField.control as SearchableSelect
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
        memberSelect.options = untrustedOptions(members.map { it.id to it.displayName })
        memberField.setValue(members.firstOrNull()?.id)
        memberField.validate(force = false)
    }

    val addButton = newActionButton(ActionIcon.ADD, tr("Mitglied hinzufügen"), style = ButtonStyle.PRIMARY)
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
private fun todayIso(): String = organizationToday().toString()

/**
 * German label/badge-color tables for [CommitteeType]/[CommitteeRole] -- this screen's own first
 * concrete instantiation of the [typeBadge] grammar (see `StatusBadge.kt` KDoc). Non-`private` (all
 * four functions) so [CommitteesScreenTest] can cover them directly, same posture as
 * [network.lapis.cloud.client.guestBadgeAriaLabel]/[Validation]'s pure functions.
 */
fun committeeTypeLabel(type: CommitteeType): String =
    when (type) {
        CommitteeType.EXECUTIVE_BOARD -> gettext("Vorstand")
        CommitteeType.WORKING_GROUP -> gettext("Arbeitsgruppe")
        CommitteeType.COMMISSION -> gettext("Kommission")
        CommitteeType.GENERAL_ASSEMBLY -> gettext("Mitgliederversammlung")
        CommitteeType.OTHER -> gettext("Sonstiges")
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

/**
 * V1.9.102 -- neutral note on a roster row whose seat was ended AUTOMATICALLY by a member status change (null otherwise). The server only
 * sends `autoEndReason` to BOARD/ADMIN, so everybody else never sees it. A seat that ends before it began (`since > until`, an appointment
 * in the future cancelled by the status change) reads "not taken up" instead of "ended". The reason is a member-status label, no free text.
 */
internal fun committeeSeatAutoEndNote(membership: CommitteeMembershipDto): String? {
    val reason = membership.autoEndReason ?: return null
    val until = membership.until ?: return null
    return if (membership.since > until) {
        gettext("nicht angetreten, automatisch beendet am %1", formatDate(until))
    } else {
        gettext("automatisch beendet, Grund: %1", memberStatusLabel(reason))
    }
}

/**
 * V1.9.102 -- the notice band above the committee list for BOARD/ADMIN: seats ended automatically in the last 30 days, active executive
 * boards without a chair, and legacy open seats of persons whose status no longer permits one. Not dismissible; invisible while all three
 * lists are empty or while the notice cannot be loaded. Returns the reload function (called after every seat change).
 */
internal fun renderCommitteeAutoEndNotice(host: SimplePanel): () -> Unit {
    fun reload() {
        AppScope.launch {
            val notice = guarded { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            host.removeAll()
            if (notice == null) return@launch
            val nothingToShow =
                notice.recentAutoEnds.isEmpty() &&
                    notice.vacantMandatoryRoles.isEmpty() &&
                    notice.openSeatsWithoutMembership.isEmpty() &&
                    notice.electionSeatChanges.isEmpty()
            if (nothingToShow) return@launch
            val band = host.vPanel(spacing = 4) { addCssClasses("alert alert-info mb-0") }
            band.setAttribute("role", "status")
            if (notice.vacantMandatoryRoles.isNotEmpty()) {
                notice.vacantMandatoryRoles.forEach { vacant ->
                    band.untrustedDiv(gettext("%1: Vorsitz ist unbesetzt.", vacant.committeeName), className = "fw-bold")
                }
            }
            if (notice.recentAutoEnds.isNotEmpty()) {
                band.div(gettext("In den letzten 30 Tagen automatisch beendet: %1 Gremienzeiten", notice.recentAutoEnds.size)) {
                    addCssClass("fw-bold")
                }
                notice.recentAutoEnds.forEach { entry ->
                    band.untrustedDiv(
                        gettext(
                            "%1: %2, %3, bis %4 (%5)",
                            entry.memberDisplayName,
                            entry.committeeName,
                            committeeRoleLabel(entry.role),
                            formatDate(entry.until),
                            memberStatusLabel(entry.reason),
                        ),
                        className = "small",
                    )
                }
            }
            if (notice.electionSeatChanges.isNotEmpty()) {
                band.div(gettext("In den letzten 30 Tagen durch Wahl geändert: %1 Gremienzeiten", notice.electionSeatChanges.size)) {
                    addCssClass("fw-bold")
                }
                notice.electionSeatChanges.forEach { change -> renderElectionSeatChange(band, change) }
            }
            if (notice.openSeatsWithoutMembership.isNotEmpty()) {
                band.div(
                    gettext(
                        "Offene Gremienzeiten von Personen ohne Mitgliedschaft: %1 – bitte prüfen.",
                        notice.openSeatsWithoutMembership.size,
                    ),
                ) { addCssClass("fw-bold") }
                notice.openSeatsWithoutMembership.forEach { seat ->
                    band.untrustedDiv(
                        gettext(
                            "%1: %2, %3, seit %4 (%5)",
                            seat.memberDisplayName,
                            seat.committeeName,
                            committeeRoleLabel(seat.role),
                            formatDate(seat.since),
                            memberStatusLabel(seat.memberStatus),
                        ),
                        className = "small",
                    )
                }
            }
        }
    }
    reload()
    return ::reload
}

/**
 * V1.9.103 -- one line of the notice band for a seat an election started or ended, with a link to that election. The mark is a text glyph in an
 * `aria-hidden` span; the sentence says what happened, so the mark is never the only signal.
 */
internal fun renderElectionSeatChange(
    band: Container,
    change: CommitteeElectionSeatChangeDto,
) {
    val seated = change.kind == CommitteeSeatChangeKind.ELECTION_SEATED
    val row = band.div(className = "small d-flex align-items-baseline gap-2")
    row.span(if (seated) "\u2713" else "\u25CB", className = "flex-shrink-0") { setAttribute("aria-hidden", "true") }
    row.untrustedSpan(
        if (seated) {
            gettext(
                "Übernommen durch Wahl am %1: %2, %3, %4",
                formatDate(change.effectiveOn),
                change.memberDisplayName,
                change.committeeName,
                committeeRoleLabel(change.role),
            )
        } else {
            gettext(
                "Beendet durch Nachfolgewahl am %1: %2, %3, %4",
                formatDate(change.effectiveOn),
                change.memberDisplayName,
                change.committeeName,
                committeeRoleLabel(change.role),
            )
        },
        className = "flex-grow-1 text-break",
    )
    row.button(tr("Zur Wahl"), style = ButtonStyle.OUTLINESECONDARY).onClick { navigateTo("/elections/${change.electionId}") }
}
