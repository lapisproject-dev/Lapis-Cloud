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
import io.kvision.table.Cell
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeElectionSeatChangeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeSeatChangeKind
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.rank
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import kotlin.math.ceil

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
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Gremien"), intro = PageIntros.forRoute(Routes.COMMITTEES))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    // V1.9.40 (R36B): the create form is collapsed; its button sits in the title row and the form opens right under the header.
    val createHost = if (canManage) root.vPanel(spacing = 6) else null
    // V1.9.102: notice band (automatic ends, vacant chair, legacy open seats) -- BOARD/ADMIN only, reloaded after every seat change.
    if (canManage) renderCommitteeAutoEndNotice(root.vPanel(spacing = 4))

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.lapisToolbar()
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Gremien anzeigen"))
    val committeePanel = root.vPanel(spacing = 6)

    fun refreshCommittees() {
        committeePanel.removeAll()
        AppScope.launch {
            val committees =
                guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = !includeInactiveCheck.value) }
                    ?: return@launch
            if (committees.isEmpty()) {
                committeePanel.p(
                    if (canManage) {
                        tr("Noch keine Gremien. Mit \"Neues Gremium\" legen Sie eines an.")
                    } else {
                        tr("Noch keine Gremien angelegt.")
                    },
                )
                return@launch
            }
            renderCommitteeTable(committeePanel, committees, canManage)
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

/** True for a committee whose members are derived (General Assembly: every active member; rule committee: whoever the rule selects). */
internal fun isDerivedCommittee(committee: CommitteeDto): Boolean =
    committee.type == CommitteeType.GENERAL_ASSEMBLY || committee.membershipMode == CommitteeMembershipMode.RULE

/**
 * The number of members that count for the quorum right now, or `null` when it cannot be read (quiet: no toast). A manual committee counts
 * its active seats; the General Assembly and rule committees ask the server ([IGovernanceService.countCommitteeEligible] throws for a manual
 * committee and is rate limited, so it is never called for one).
 */
internal suspend fun loadCommitteeEligibleCount(committee: CommitteeDto): Int? =
    try {
        val service = rpcService<IGovernanceService>()
        if (isDerivedCommittee(committee)) {
            service.countCommitteeEligible(committee.id)
        } else {
            service.listCommitteeMembers(committee.id, activeOnly = true).size
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

/** Loads the counts of [committees] one request each, at most [COUNT_PARALLELISM] in flight; [onCount] runs per committee (also with `null`). */
internal fun loadCommitteeCountsLazily(
    committees: List<CommitteeDto>,
    onCount: (CommitteeDto, Int?) -> Unit,
) {
    val queue = ArrayDeque(committees)
    repeat(minOf(COUNT_PARALLELISM, committees.size)) {
        AppScope.launch {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                onCount(next, loadCommitteeEligibleCount(next))
            }
        }
    }
}

private const val COUNT_PARALLELISM = 4

/**
 * Number of members needed for a quorum: `ceil(eligible * percent / 100)`. Mirrors the server (`ResolutionBook.kt`): PRESENT and REPRESENTED
 * count, it is attendance, not yes votes.
 */
internal fun committeeQuorumRequired(
    eligible: Int,
    percent: Int,
): Int = ceil(eligible * percent / 100.0).toInt()

/** Cell text "3 von 5 (50 %)" -- "–" while the count is unknown. */
internal fun committeeQuorumText(
    eligible: Int?,
    percent: Int,
): String =
    if (eligible == null) {
        "–"
    } else {
        gettext("%1 von %2 (%3 %)", committeeQuorumRequired(eligible, percent), eligible, percent)
    }

/**
 * The plain-German explanation on the detail page. With exactly half (50 % and an even number) it adds one sentence, because there "half" is
 * enough and "more than half" would be one more. For derived committees the numbers are the current roster. No legal advice.
 */
internal fun committeeQuorumHint(
    eligible: Int?,
    percent: Int,
    derived: Boolean,
): String {
    if (eligible == null) {
        return gettext("Eine Sitzung ist beschlussfähig, wenn mindestens %1 % der Mitglieder anwesend oder vertreten sind.", percent)
    }
    var text =
        gettext(
            "Eine Sitzung ist beschlussfähig, wenn mindestens %1 von %2 Mitgliedern (%3 %) anwesend oder vertreten sind.",
            committeeQuorumRequired(eligible, percent),
            eligible,
            percent,
        )
    if (percent == 50 && eligible > 0 && eligible % 2 == 0) {
        text += " " +
            gettext(
                "Genau die Hälfte (%1 von %2) genügt bereits; mehr als die Hälfte wären %3.",
                eligible / 2,
                eligible,
                eligible / 2 + 1,
            )
    }
    if (derived) text += " " + gettext("Die Zahlen zeigen den aktuellen Stand.")
    return text
}

/** Hand-over from the list's edit icon to the detail page: that page opens "Gremium bearbeiten" for this id once. */
internal object PendingCommitteeEdit {
    private var committeeId: String? = null

    fun request(id: String) {
        committeeId = id
    }

    fun consume(id: String): Boolean {
        val match = committeeId == id
        committeeId = null
        return match
    }
}

private fun renderCommitteeTable(
    host: SimplePanel,
    committees: List<CommitteeDto>,
    canManage: Boolean,
) {
    val headers =
        listOf(
            TableHeader(tr("Name")),
            TableHeader(tr("Art")),
            TableHeader(tr("Mitglieder"), numeric = true),
            TableHeader(tr("Beschlussfähig ab")),
            TableHeader(tr("Status")),
        ) + if (canManage) listOf(TableHeader(tr("Aktionen"))) else emptyList()
    val table = host.masterTable(tr("Gremien"), headers)
    val countCells = mutableMapOf<String, Pair<Cell, Cell>>()
    committees.forEach { committee ->
        table.row {
            masterRow()
            val nameCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(nameCell)
            // Committee name is member-editable free text: sanitized inside [recordLink].
            nameCell.recordLink(committee.name, Routes.COMMITTEES, committee.id)
            if (committee.membershipMode == CommitteeMembershipMode.RULE) {
                nameCell.span(" ")
                nameCell.ruleCommitteeBadge()
            }
            cell { typeBadge(committeeTypeLabel(committee.type), committeeTypeColor(committee.type)) }
            val memberCount = numCell(trusted("…"))
            val quorumCell = textCell(trusted("…"))
            countCells[committee.id] = memberCount to quorumCell
            cell { statusBadge(if (committee.active) tr("Aktiv") else tr("Inaktiv"), if (committee.active) "success" else "secondary") }
            if (canManage) {
                cell {
                    tableActionGroup()
                        .tableActionButton(ActionIcon.EDIT, tr("Bearbeiten"))
                        .onClick {
                            PendingCommitteeEdit.request(committee.id)
                            MasterDetailFocus.remember(Routes.COMMITTEES, committee.id)
                            navigateTo(detailRoute(Routes.COMMITTEES, committee.id))
                        }
                }
            }
        }
    }
    loadCommitteeCountsLazily(committees) { committee, count ->
        val (memberCount, quorumCell) = countCells[committee.id] ?: return@loadCommitteeCountsLazily
        memberCount.content = count?.toString() ?: "–"
        quorumCell.content = committeeQuorumText(count, committee.quorumPercent)
    }
}

/**
 * V1.9.192 -- one committee as its own page (`/committees/:id`, deep link): back button above the title, the facts, the quorum
 * explanation, the roster (or the derived-membership facts) and, for BOARD/ADMIN, two collapsed sections to add a member and to edit.
 */
fun renderCommitteeDetail(
    container: SimplePanel,
    id: String,
) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.overviewBackButton(Routes.COMMITTEES)
    val loading = root.p(tr("Wird geladen …"))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    AppScope.launch {
        val committees = guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = false) }
        root.remove(loading)
        if (committees == null) {
            root.p(tr("Das Gremium konnte nicht geladen werden."))
            return@launch
        }
        val committee = committees.find { it.id == id }
        if (committee == null) {
            root.p(tr("Dieses Gremium wurde nicht gefunden."))
            return@launch
        }
        // The title is the committee (member-editable free text): the whole composed result is sanitized.
        root.pageHeader(sanitizeUntrustedI18nText(gettext("Gremium: %1", committee.name)))
        val reload = {
            container.removeAll()
            renderCommitteeDetail(container, id)
        }
        val facts = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        facts.typeBadge(committeeTypeLabel(committee.type), committeeTypeColor(committee.type))
        if (committee.membershipMode == CommitteeMembershipMode.RULE) facts.ruleCommitteeBadge()
        facts.statusBadge(if (committee.active) tr("Aktiv") else tr("Inaktiv"), if (committee.active) "success" else "secondary")
        if (committee.description.isNotBlank()) root.untrustedP(committee.description, className = "mb-0")

        val hint = root.div(className = "alert alert-light border mb-0")
        hint.setAttribute("role", "note")
        hint.content = committeeQuorumHint(null, committee.quorumPercent, isDerivedCommittee(committee))

        fun refreshHint() {
            AppScope.launch {
                val count = loadCommitteeEligibleCount(committee)
                hint.content = committeeQuorumHint(count, committee.quorumPercent, isDerivedCommittee(committee))
            }
        }
        // A rule committee's facts already ask the count (rate limited): the hint takes it from there instead of asking twice.
        if (committee.membershipMode != CommitteeMembershipMode.RULE) refreshHint()

        if (committee.membershipMode == CommitteeMembershipMode.RULE) {
            val tiers =
                if (canManage) guarded { rpcService<IContributionService>().listMembershipTiers() }.orEmpty() else emptyList()
            renderRuleCommitteeFacts(
                row = root.vPanel(spacing = 4),
                committee = committee,
                canManage = canManage,
                committees = committees,
                tiers = tiers,
                onCount = { count -> hint.content = committeeQuorumHint(count, committee.quorumPercent, derived = true) },
            )
        } else {
            renderCommitteeRoster(
                root.vPanel(spacing = 6),
                committee,
                canManage,
                onSeatsChanged = { refreshHint() },
                addMemberCollapsed = true,
            )
        }
        if (canManage) {
            val editRequested = PendingCommitteeEdit.consume(id)
            val editHost = root.vPanel(spacing = 6)
            editHost.disclosure(tr("Gremium bearbeiten"), "lapis-edit-committee", initiallyOpen = editRequested) {
                renderCommitteeEditForm(this, committee, reload)
                renderCommitteeMembershipSection(this, committee, reload)
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
    // V1.9.112: manual or by rule; the choice follows the type (nothing for the General Assembly and the executive board)
    val membership =
        MembershipModeSection(
            form = form,
            host = form.panel,
            selfId = null,
            initialMode = CommitteeMembershipMode.MANUAL,
            initialRule = MemberRule(op = RuleOp.ALL, conditions = emptyList()),
        )
    membership.setTypeAllowsRule(committeeTypeAllowsRule(CommitteeType.WORKING_GROUP))
    typeField.subscribe { value ->
        membership.setTypeAllowsRule(parseOptionalEnum<CommitteeType>(value)?.let { committeeTypeAllowsRule(it) } ?: true)
    }

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
                            membershipMode = membership.mode,
                            ruleJson = membership.ruleJson(),
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("\"%1\" wurde angelegt.", name))
                nameField.reset()
                descriptionField.reset()
                quorumField.setValue("50")
                membership.dispose()
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
    addMemberCollapsed: Boolean = false,
) {
    rosterPanel.removeAll()
    if (committee.type == CommitteeType.GENERAL_ASSEMBLY) {
        renderGeneralAssemblyRoster(rosterPanel, committee, canManage, onSeatsChanged)
        return
    }
    // V1.9.112: a rule committee has no roster, no "Mitglied hinzufügen" and no offices -- only the number and what that means
    if (committee.membershipMode == CommitteeMembershipMode.RULE) {
        renderRuleCommitteeRoster(rosterPanel, committee)
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
    val addMemberPanel =
        if (canManage) {
            rosterPanel.vPanel(spacing = 6) { if (!addMemberCollapsed) addCssClasses("border-top pt-2 mt-2") }
        } else {
            null
        }

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
            renderRosterRows(rosterListPanel, rosterFilter, committee.name, loadedMemberships, canManage, seatsChanged)
        }
    }
    // A seat added or ended by hand changes what the notice band shows (vacant chair), so the band reloads too.
    seatsChanged = {
        refreshRoster()
        onSeatsChanged()
    }
    rosterFilter.subscribe { renderRosterRows(rosterListPanel, rosterFilter, committee.name, loadedMemberships, canManage, seatsChanged) }

    val rosterRefreshButton = rosterFilterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    rosterRefreshButton.onClick { refreshRoster() }
    refreshRoster()

    if (canManage && addMemberPanel != null) {
        if (addMemberCollapsed) {
            addMemberPanel.disclosure(tr("Mitglied hinzufügen"), "lapis-add-committee-member") {
                renderAddCommitteeMemberForm(this, committee.id, seatsChanged)
            }
        } else {
            renderAddCommitteeMemberForm(addMemberPanel, committee.id, seatsChanged)
        }
    }
}

/**
 * V1.9.112 -- the "Besetzung" of a rule-based committee: nobody is listed (the members are whoever the rule selects at the moment it is asked),
 * so the view says that plainly and shows the live number -- the basis of quorum, votes and elections, frozen when a vote opens.
 */
internal fun renderRuleCommitteeRoster(
    rosterPanel: SimplePanel,
    committee: CommitteeDto,
) {
    rosterPanel.h2(sanitizeUntrustedI18nText(gettext("Besetzung: %1", committee.name))) { addCssClass("h5") }
    val infoRow = rosterPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    infoRow.icon(ActionIcon.FILTER.css) { setAttribute("aria-hidden", "true") }
    val infoText = infoRow.div(tr("Mitglieder ergeben sich aus der Regel.")) { addCssClass("text-body") }
    infoText.setAttribute("aria-live", "polite")
    AppScope.launch {
        val n = runCatching { rpcService<IGovernanceService>().countCommitteeEligible(committee.id) }.getOrNull()
        if (n != null) {
            infoText.content = ngettext("%1 Mitglied gehört dazu", "%1 Mitglieder gehören dazu", n, n)
            infoText.addCssClass("lapis-tabular-nums")
        }
    }
    rosterPanel.div(tr("Bei einer Abstimmung wird der Kreis zum Start festgeschrieben.")) { addCssClasses("text-muted small") }
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
            if (offices.isNotEmpty()) renderRosterTable(officesList, committee.name, offices, canManage, seatsChanged)
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
                renderRosterTable(legacyRows, committee.name, legacy, canManage, seatsChanged)
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
        form.localDateField(
            label = tr("Seit"),
            value = organizationToday(),
            required = true,
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
                            since = checkNotNull(sinceField.localDateOrNull()),
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
    committeeName: String,
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
    renderRosterTable(listPanel, committeeName, visible, canManage, onChanged)
}

/**
 * V1.9.192 -- the roster as a table (Name | Rolle | Zeitraum | Aktionen). The only action, "Mitgliedschaft beenden", is an icon-only
 * button (R58 named exception for master-detail tables) whose name says person and committee; the date dialog is unchanged.
 */
private fun renderRosterTable(
    host: Container,
    committeeName: String,
    memberships: List<CommitteeMembershipDto>,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val showActions = canManage && memberships.any { it.until == null }
    val headers =
        listOf(TableHeader(tr("Name")), TableHeader(tr("Rolle")), TableHeader(tr("Zeitraum"))) +
            if (showActions) listOf(TableHeader(tr("Aktionen"))) else emptyList()
    val table = host.masterTable(tr("Mitglieder"), headers)
    memberships.forEach { membership ->
        table.row {
            val nameCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(nameCell)
            nameCell.untrustedDiv(membership.memberDisplayName)
            cell { typeBadge(committeeRoleLabel(membership.role), committeeRoleColor(membership.role)) }
            val period =
                if (membership.until != null) {
                    gettext("%1 – %2", formatDate(membership.since), formatDate(membership.until!!))
                } else {
                    gettext("seit %1", formatDate(membership.since))
                }
            textCell(trusted(period)) {
                committeeSeatAutoEndNote(membership)?.let { note -> div(note) { addCssClasses("text-muted small fst-italic") } }
            }
            if (showActions) {
                cell {
                    if (canManage && membership.until == null) {
                        // Person and committee are untrusted: the whole composed label is sanitized (title + aria-label).
                        val label =
                            sanitizeUntrustedI18nText(
                                gettext("Mitgliedschaft von %1 in %2 beenden", membership.memberDisplayName, committeeName),
                            )
                        tableActionGroup().tableActionButton(ActionIcon.REMOVE, label, ButtonStyle.OUTLINEDANGER).onClick {
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
        form.localDateField(
            label = tr("Enddatum"),
            value = organizationToday(),
            required = true,
        )
    form.finish()
    modal.addButton(
        newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } },
    )
    modal.addButton(
        Button(tr("Mitgliedschaft beenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                if (!form.validateAndReport()) return@onClick
                val until = checkNotNull(untilField.localDateOrNull())
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
        form.localDateField(
            label = tr("Seit"),
            value = organizationToday(),
            required = true,
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
                            since = checkNotNull(sinceField.localDateOrNull()),
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
        gettext("automatisch beendet, Grund: %1", committeeSeatEndReasonLabel(reason))
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
                            committeeSeatEndReasonLabel(entry.reason),
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
