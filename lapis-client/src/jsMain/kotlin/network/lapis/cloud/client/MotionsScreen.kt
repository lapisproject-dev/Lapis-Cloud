package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.P
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService

/**
 * Governance UI wave, screen 3 of 3 -- "Anträge" (Motions & Voting: submit, amend, review,
 * schedule, vote, resolve, withdraw), per the approved plan + UI/UX-Design-Team review on
 * `feature/governance-ui`. Per the plan's deliberate scope decision, this is ONE screen covering
 * both the Motion lifecycle AND the Vote sub-flow (open/ballot-cast/tally/close/abort) as a
 * section of the Motion detail view -- a Vote only ever exists in the context of exactly one
 * [MotionStatus.SCHEDULED] Motion, so a separate `VotesScreen.kt` would only fragment one workflow
 * across two files.
 *
 * **Backend addition found while building this screen (documented in this wave's CHANGELOG, not
 * a UI-authoring mistake): `IGovernanceService.listVotes(motionId, status)`.** Through V0.2.6,
 * every Vote-scoped method required the caller to already know a specific Vote's id, which only
 * ever reached a client as the return value of the one `openVote` call that created it -- a second
 * visitor to a Motion (or a page reload) had no RPC path back to an already-OPEN Vote's id at all.
 * Added as a small, read-only, no-role-required, purely additive method mirroring [listMotions]'s
 * own optional-filter shape -- see `GovernanceService.listVotes` KDoc and its own test coverage in
 * `GovernanceServiceTest.kt`.
 *
 * Role gating (plan §4, verified against `GovernanceService.kt`, same [GovernanceAuthzUi] this
 * wave's Meetings screen already established):
 * - `reviewMotion`/`scheduleMotion`/`resolveMotion`/`openVote`/`closeVote`/`abortVote`: target
 *   Committee leadership (CHAIR/DEPUTY_CHAIR/SECRETARY) or global BOARD/ADMIN --
 *   [GovernanceAuthzUi.canRecordForMeeting], fed by that Motion's target Committee's own active
 *   roster.
 * - `withdrawMotion`: the submitter themself while [MotionStatus.SUBMITTED], OR committee
 *   leadership/BOARD/ADMIN at ANY status except already-WITHDRAWN (matches the server's own
 *   documented rule verbatim -- see [renderWithdrawAction], fixed in review round 1 after
 *   originally being narrowed to non-terminal statuses only).
 * - `submitMotion`: the broadest gate of the whole wave -- any [network.lapis.cloud.shared.domain
 *   .MemberStatus.ACTIVE] member for the General Assembly, or any active Committee membership (any
 *   role) for a specific Committee. The submission form below restricts its target-Committee
 *   picker to Committees the caller plausibly qualifies for (mirrors the Meetings screen's
 *   `manageableCommittees` computation), but a stale client-side cache is still gracefully
 *   surfaced as a `guarded()` 403 rather than crashing.
 * - `castVoteBallot`: any member in the Vote's underlying Meeting/Committee eligibility set (same
 *   rule [network.lapis.cloud.client.GovernanceAuthzUi] doesn't cover -- the Committee's active roster,
 *   or, V1.9.101, for the General Assembly the caller's own `getCurrentMember()` status: every active member belongs to it
 *   and the screen never loads the whole directory, see [renderVoteSection]).
 * - Every read (`listMotions`/`getMotion`/`getVote`/`listVotes`/`listVoteBallots`) requires no
 *   role at all server-side.
 *
 * Design decisions this file implements verbatim (see the approved design document for full
 * rationale/rejected alternatives):
 * - **D1** -- an amendment renders indented (`ps-4`) with a `↳ Änderungsantrag: ` prefix beneath
 *   its target main Motion in the list, and its detail view carries a callout linking back to the
 *   parent; a main Motion's own detail view always shows its (possibly empty) amendments section.
 * - **D2** -- a SCHEDULED main Motion with any non-terminal amendment shows both resolution
 *   controls disabled with an explanatory `alert-warning` box listing the blocking amendments; an
 *   amendment's own scheduling control is a reduced "Jetzt terminieren" button using its parent's
 *   Meeting silently, not a full picker.
 * - **D3** -- while a Vote is OPEN: a plain ballot count, the caller's own cast ballot (if any),
 *   and (privileged only) a names-only participant list -- never running totals/a leaderboard.
 *   Full reveal (basket totals, full ballot table, winner/second-price/tie) only once CLOSED.
 * - **D4** -- quorum/`generateProtocolDraft` are this screen's own N/A (no quorum control here;
 *   protocol lives on the Meetings screen) -- not applicable to this file.
 * - **D6** -- a `ResolutionDto.resolutionMode` badge (in the terminal-status outcome summary)
 *   reuses [renderResolutionRow]/[resolutionStatusLabel]/[resolutionModeLabel] etc. from
 *   `MeetingsScreen.kt` verbatim, unchanged.
 * - **D7** -- `confirmDialog` wraps `withdrawMotion`/`abortVote` here (per the design's explicit
 *   list); `closeVote` (a forward/completing transition, mirrors `updateMeetingStatus -> HELD`)
 *   does not.
 */
fun renderMotionsScreen(container: SimplePanel) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val currentMemberId = session.memberId
    val isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)

    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Anträge"), intro = PageIntros.forRoute(Routes.MOTIONS))
    // V1.9.40 (R36B): the submission form is collapsed; its button sits in the title row and the form opens right under the header.
    val submissionHost = root.vPanel(spacing = 6)

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.lapisToolbar()
    val committeeFilterSelect = filterRow.select(options = listOf("" to tr("Alle Gremien")), value = "", label = tr("Gremium"))
    val statusFilterOptions =
        listOf("" to tr("Alle Status")) + MotionStatus.entries.map { it.name to motionStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = tr("Status"))
    val refreshButton = filterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val motionListPanel = root.vPanel(spacing = 6)

    // Known only after the committee rosters are loaded (below), i.e. usually AFTER the first list render: the empty-state text is
    // corrected in place once it is (see [emptyNote]).
    var canSubmit = false
    var emptyNote: P? = null

    fun emptyText(): String =
        if (canSubmit) tr("Noch keine Anträge. Mit \"Neuer Antrag\" reichen Sie einen ein.") else tr("Noch keine Anträge vorhanden.")

    fun refreshMotions() {
        motionListPanel.removeAll()
        emptyNote = null
        AppScope.launch {
            val committeeId = committeeFilterSelect.value?.takeIf { it.isNotBlank() }
            val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { MotionStatus.valueOf(it) }
            val motions = guarded { rpcService<IGovernanceService>().listMotions(committeeId, status) } ?: return@launch
            if (motions.isEmpty()) {
                emptyNote = motionListPanel.p(emptyText())
                return@launch
            }
            renderMotionTable(motionListPanel, motions)
        }
    }

    refreshButton.onClick { refreshMotions() }

    AppScope.launch {
        val committees = guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = false) } ?: emptyList()
        committeeFilterSelect.options = listOf("" to tr("Alle Gremien")) + untrustedOptions(committees.map { it.id to it.name })
        committeeFilterSelect.value = ""
        refreshMotions()

        // Which Committees the current member may plausibly submit a Motion to (plan §4's
        // broadest gate) -- BOARD/ADMIN may submit anywhere active; the General Assembly is a
        // broad ACTIVE-member right (residual "was I actually still ACTIVE" race falls through to
        // guarded()'s 403 toast, see file KDoc); any other Committee requires an active membership
        // of ANY CommitteeRole (not just leadership, unlike the Meetings screen's own
        // manageableCommittees computation).
        val activeCommittees = committees.filter { it.active }
        val submittableCommittees =
            if (isBoardOrAdmin) {
                activeCommittees
            } else {
                val result = mutableListOf<CommitteeDto>()
                for (committee in activeCommittees) {
                    if (committee.type == CommitteeType.GENERAL_ASSEMBLY) {
                        result.add(committee)
                        continue
                    }
                    val roster =
                        guarded {
                            rpcService<IGovernanceService>().listCommitteeMembers(committee.id, activeOnly = true)
                        } ?: emptyList()
                    if (roster.any { it.memberId == currentMemberId }) result.add(committee)
                }
                result
            }

        // No permission: no button and no empty action area (R36B).
        if (submittableCommittees.isNotEmpty()) {
            // Eligible amendment targets: every non-terminal MAIN Motion (amendsMotionId == null)
            // across the Committees the caller may submit to -- see [renderMotionSubmissionForm]
            // KDoc for why this is loaded once here rather than reactively on committee-select
            // change (no `onChange` precedent exists anywhere in this client, see that KDoc).
            val amendableMotions = mutableListOf<Pair<CommitteeDto, MotionDto>>()
            for (committee in submittableCommittees) {
                val motions =
                    guarded { rpcService<IGovernanceService>().listMotions(targetCommitteeId = committee.id) } ?: emptyList()
                motions
                    .filter { it.amendsMotionId == null && it.status in NON_TERMINAL_MOTION_STATUSES }
                    .forEach { amendableMotions.add(committee to it) }
            }
            // Loaded with the page, as before; the button lives outside the list refresh, so reloading the list never discards a form
            // someone is filling in.
            collapsibleCreateForm<Unit>(
                actionSlot = header.actionSlot,
                formHost = submissionHost,
                buttonLabel = tr("Neuer Antrag"),
                formId = "lapis-create-motion",
            ) { _, close -> renderMotionSubmissionForm(this, submittableCommittees, amendableMotions, { refreshMotions() }, close) }
            canSubmit = true
            emptyNote?.content = emptyText()
        }
    }
}

/**
 * Design decision D1: main Motions come first, each immediately followed by its own amendments. A filtered [motions] list may not
 * contain an amendment's own parent (different committee/status filter) -- such an orphaned amendment goes to the end rather than
 * being silently dropped.
 */
internal fun orderMotionsForTable(motions: List<MotionDto>): List<MotionDto> {
    val mainMotions = motions.filter { it.amendsMotionId == null }
    val amendmentsByParentId = motions.filter { it.amendsMotionId != null }.groupBy { it.amendsMotionId }
    val ordered = mutableListOf<MotionDto>()
    val placed = mutableSetOf<String>()
    mainMotions.forEach { main ->
        ordered += main
        placed += main.id
        amendmentsByParentId[main.id]?.forEach { amendment ->
            ordered += amendment
            placed += amendment.id
        }
    }
    motions.filter { it.id !in placed }.forEach { ordered += it }
    return ordered
}

/**
 * V1.9.193 -- the motions as a table (Titel | Gremium | Antragsteller | Status | Datum | Aktionen); the title is the real link to
 * `/motions/:id`. An amendment carries "Änderung zu: <parent title>" below its link (when the parent is in the list; with an active
 * filter it may not be -- then the plain word "Änderungsantrag").
 */
internal fun renderMotionTable(
    host: Container,
    motions: List<MotionDto>,
) {
    val headers =
        listOf(
            TableHeader(tr("Titel")),
            TableHeader(tr("Gremium")),
            TableHeader(tr("Antragsteller")),
            TableHeader(tr("Status")),
            TableHeader(tr("Datum")),
            TableHeader(tr("Aktionen")),
        )
    val table = host.masterTable(tr("Anträge"), headers)
    val titlesById = motions.associate { motion -> motion.id to motion.title }

    orderMotionsForTable(motions).forEach { motion ->
        table.row {
            masterRow()
            val titleCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(titleCell)
            // Motion title is member-controlled free text: sanitized inside [recordLink].
            titleCell.recordLink(motion.title, Routes.MOTIONS, motion.id)
            if (motion.amendsMotionId != null) {
                val parentTitle = titlesById[motion.amendsMotionId]
                val note = if (parentTitle != null) gettext("Änderung zu: %1", parentTitle) else gettext("Änderungsantrag")
                titleCell.untrustedDiv(note, className = "small text-muted")
            }
            textCell(motion.targetCommitteeName)
            textCell(motion.submitterDisplayName.ifBlank { "–" })
            cell { statusBadge(motionStatusLabel(motion.status), motionStatusColor(motion.status)) }
            textCell(trusted(formatSystemDateTime(motion.submittedAt)))
            cell {
                val tooltip = gettext("Details zu %1", sanitizeUntrustedI18nText(motion.title))
                tableActionGroup()
                    .tableActionButton(ActionIcon.VIEW, tooltip)
                    .onClick {
                        MasterDetailFocus.remember(Routes.MOTIONS, motion.id)
                        navigateTo(detailRoute(Routes.MOTIONS, motion.id))
                    }
            }
        }
    }
}

/**
 * "This amends motion X" is only offered for a non-terminal main Motion -- [amendableMotions]
 * already carries that filter applied (see [renderMotionsScreen]'s loading loop). No `onChange`
 * precedent exists in this client (see that call site's own KDoc), so this form deliberately does
 * NOT try to dynamically filter the amends-picker as the target-Committee select changes -- instead
 * the amends-picker's own options ALREADY encode a "{Committee}: {Titel}" label, and selecting one
 * silently OVERRIDES whatever the target-Committee select shows (the amendment's own
 * `targetCommitteeId` is what's actually submitted) -- explained in the helper text beneath it.
 */
internal fun renderMotionSubmissionForm(
    panel: SimplePanel,
    submittableCommittees: List<CommitteeDto>,
    amendableMotions: List<Pair<CommitteeDto, MotionDto>>,
    onSubmitted: () -> Unit,
    collapse: ((saved: Boolean) -> Unit)? = null,
): FormSnapshot {
    // Formular-Grammatik (V1.4.29, W4b): Titel und Antragstext sind Pflicht, Begründung und der zu ändernde Antrag optional => Fall (a).
    val form = panel.lapisForm()
    val committeeOptions = untrustedOptions(submittableCommittees.map { it.id to it.name })
    val committeeField =
        form.selectField(label = tr("Zielgremium"), options = committeeOptions, value = submittableCommittees.firstOrNull()?.id)
    val amendsOptions =
        listOf("" to tr("-- kein (neuer Hauptantrag) --")) +
            amendableMotions.map { (committee, motion) -> motion.id to gettext("%1: %2", committee.name, motion.title) }
    val amendsField = form.selectField(label = tr("Ändert bestehenden Antrag"), options = amendsOptions, value = "")
    form.panel.div(
        tr(
            "Wird ein bestehender Antrag zum Ändern ausgewählt, wird dessen Gremium automatisch verwendet -- " +
                "das Zielgremium oben wird dann ignoriert.",
        ),
    ) { addCssClasses("text-muted small") }
    val titleField = form.textField(label = tr("Titel"), required = true)
    val rationaleField = form.textField(label = tr("Begründung"))
    val textField = form.textAreaField(label = tr("Antragstext"), rows = 4, required = true)

    val submitButton = newActionButton(ActionIcon.SEND, tr("Antrag einreichen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton, cancel = collapse?.let { collapseCancelButton(it) })
    submitButton.onClick {
        form.submit(submitButton) {
            val amendsId = amendsField.value.takeIf { it.isNotBlank() }
            val targetCommitteeId =
                if (amendsId != null) {
                    amendableMotions.find { (_, motion) -> motion.id == amendsId }?.first?.id
                } else {
                    committeeField.value.takeIf { it.isNotBlank() }
                }
            if (targetCommitteeId == null) {
                form.showFormError(tr("Bitte Zielgremium (oder einen zu ändernden Antrag), Titel und Antragstext angeben."))
                return@submit
            }
            val title = titleField.value.trim()
            val input =
                MotionInput(
                    targetCommitteeId = targetCommitteeId,
                    title = title,
                    rationale = rationaleField.value.trim(),
                    text = textField.value.trim(),
                    amendsMotionId = amendsId,
                )
            // V1.9.54: the server refuses an amendment while the target's election, vote or consensus runs (same ConflictException type
            // as every other refusal), so an amendment gets a fixed text naming that likely cause; a new motion keeps the generic path.
            val result =
                if (amendsId != null) {
                    electionGuarded(
                        conflictMessage =
                            tr(
                                "Zu diesem Antrag läuft gerade eine Wahl, eine Abstimmung oder ein Konsensieren. " +
                                    "Änderungsanträge sind erst danach wieder möglich.",
                            ),
                    ) { rpcService<IGovernanceService>().submitMotion(input) }
                } else {
                    guarded { rpcService<IGovernanceService>().submitMotion(input) }
                }
            if (result != null) {
                notifySuccess(gettext("Antrag \"%1\" wurde eingereicht.", title))
                titleField.reset()
                rationaleField.reset()
                textField.reset()
                amendsField.setValue("")
                onSubmitted()
                collapse?.invoke(true)
            }
        }
    }
    return form.snapshot()
}

/**
 * V1.9.101: may the caller cast a ballot on a vote of [committee]? A committee: its active roster. The General Assembly is every
 * active member, so only the caller's OWN record is asked for (never the whole directory); an own record that cannot be loaded
 * counts as "not eligible", like an empty list did before. The server decides the vote in any case.
 */
internal suspend fun isEligibleToBallot(
    committee: CommitteeDto?,
    roster: List<CommitteeMembershipDto>,
    currentMemberId: String,
): Boolean =
    if (committee?.type == CommitteeType.GENERAL_ASSEMBLY) {
        guarded { rpcService<IMemberService>().getCurrentMember() }?.status == MemberStatus.ACTIVE
    } else {
        roster.any { it.memberId == currentMemberId }
    }

/**
 * V1.9.193 -- one motion as its own page (`/motions/:id`, deep link, also the target of an election's "Zum Antrag" link): back button
 * above the title, then the sections of [buildMotionDetail]. An unknown id leaves a sentence next to the back button, never a blank
 * page (same as the meeting page; no redirect, so there is no loop to get into).
 */
fun renderMotionDetailScreen(
    container: SimplePanel,
    id: String,
) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.overviewBackButton(Routes.MOTIONS)
    val loading = root.p(tr("Wird geladen …"))
    val isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    AppScope.launch {
        val motion = guarded { rpcService<IGovernanceService>().getMotion(id) }
        root.remove(loading)
        if (motion == null) {
            root.p(tr("Dieser Antrag wurde nicht gefunden."))
            return@launch
        }
        val committees = guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = false) } ?: emptyList()
        // The title is the motion (member-controlled free text): the whole composed result is sanitized.
        val title =
            if (motion.amendsMotionId != null) gettext("Änderungsantrag: %1", motion.title) else gettext("Antrag: %1", motion.title)
        root.pageHeader(sanitizeUntrustedI18nText(title))
        val body = root.vPanel(spacing = 10)
        val reload = {
            container.removeAll()
            renderMotionDetailScreen(container, id)
        }
        buildMotionDetail(body, motion, session.memberId, isBoardOrAdmin, committees, reload)
    }
}

/**
 * Loads the Motion's target Committee roster (for [GovernanceAuthzUi]), its parent (if it is an amendment) or its own amendments (if it
 * is a main Motion), and any Vote opened against it -- then dispatches the status-specific action sections. A nested link (the D1
 * parent callout, an amendment row, a D2 blocking-amendment link) navigates to that motion's own page.
 */
private fun buildMotionDetail(
    panel: SimplePanel,
    motion: MotionDto,
    currentMemberId: String,
    isBoardOrAdmin: Boolean,
    committees: List<CommitteeDto>,
    onChanged: () -> Unit,
) {
    val onSelectMotion: (String) -> Unit = { navigateTo(detailRoute(Routes.MOTIONS, it)) }
    AppScope.launch {
        val roster =
            guarded {
                rpcService<IGovernanceService>().listCommitteeMembers(motion.targetCommitteeId, activeOnly = true)
            } ?: emptyList()
        val canManage =
            GovernanceAuthzUi.canRecordForMeeting(isBoardOrAdmin, currentMemberId, motion.targetCommitteeId, roster)
        val isSubmitter = motion.submitterMemberId == currentMemberId

        val parent = motion.amendsMotionId?.let { guarded { rpcService<IGovernanceService>().getMotion(it) } }
        val amendments =
            if (motion.amendsMotionId == null) {
                guarded { rpcService<IGovernanceService>().listMotions(amendsMotionId = motion.id) } ?: emptyList()
            } else {
                emptyList()
            }
        val pendingAmendments = amendments.filter { it.status in NON_TERMINAL_MOTION_STATUSES }

        val elections = loadMotionElections(motion.id)
        val consensuses = loadMotionConsensuses(motion.id)
        val votes = guarded { rpcService<IGovernanceService>().listVotes(motionId = motion.id) } ?: emptyList()
        val activeVote =
            votes.find { it.status == VoteStatus.OPEN } ?: votes.find { it.status == VoteStatus.CLOSED }

        val committee = committees.find { it.id == motion.targetCommitteeId }
        panel.removeAll()
        renderMotionMeta(panel, motion)
        if (parent != null) renderAmendmentContext(panel, parent, onSelectMotion)
        if (motion.amendsMotionId == null) renderAmendmentsSection(panel, amendments)
        if (RejectedBidNotice.shownFor(votes)) renderRejectedBidNotice(panel)

        when (motion.status) {
            MotionStatus.SUBMITTED -> renderReviewSection(panel, motion, canManage, onChanged)
            MotionStatus.REVIEWED, MotionStatus.POSTPONED ->
                renderScheduleSection(panel, motion, parent, canManage, onChanged)
            MotionStatus.SCHEDULED ->
                renderResolutionSection(
                    panel = panel,
                    motion = motion,
                    pendingAmendments = pendingAmendments,
                    canManage = canManage,
                    activeVote = activeVote,
                    onSelectMotion = onSelectMotion,
                    onChanged = onChanged,
                    elections = elections,
                    committees = committees,
                    consensuses = consensuses,
                )
            MotionStatus.RESOLVED, MotionStatus.REJECTED, MotionStatus.REJECTED_PRELIMINARY, MotionStatus.WITHDRAWN ->
                renderOutcomeSummary(panel, motion)
        }

        if (activeVote != null) {
            val eligible = isEligibleToBallot(committee, roster, currentMemberId)
            renderVoteSection(panel, activeVote, canManage, currentMemberId, eligible, onChanged)
        }
        // Last on the page, a quiet secondary button (not a red one next to the title).
        renderWithdrawAction(panel, motion, canManage, isSubmitter, onChanged)
    }
}

/** The facts of the motion under the page title: badges, committee/submitter/date line, rationale, text and the review note. */
private fun renderMotionMeta(
    panel: SimplePanel,
    motion: MotionDto,
) {
    val badgeRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    if (motion.amendsMotionId != null) badgeRow.typeBadge(tr("Änderungsantrag"), "secondary")
    badgeRow.statusBadge(motionStatusLabel(motion.status), motionStatusColor(motion.status))

    panel.div(
        gettext(
            "Gremium: %1 · eingereicht von %2 am %3",
            motion.targetCommitteeName,
            motion.submitterDisplayName,
            formatSystemDateTime(motion.submittedAt),
        ),
    ) { addCssClasses("text-muted small") }
    if (motion.rationale.isNotBlank()) panel.untrustedP(motion.rationale, className = "mb-0")
    panel.untrustedP(motion.effectiveText, className = "mb-0")
    motion.reviewedByDisplayName?.let { reviewer ->
        panel.div(
            gettext(
                "Geprüft von %1 am %2%3",
                reviewer,
                formatSystemDateTime(motion.reviewedAt!!),
                motion.reviewNote?.let { " -- $it" } ?: "",
            ),
        ) {
            addCssClasses("text-muted small")
        }
    }
}

/**
 * Review round 1 fix: the server's actual rule (`IGovernanceService.withdrawMotion` KDoc --
 * "the submitter themself while SUBMITTED, or that Committee's leadership/BOARD/ADMIN at ANY
 * status", confirmed by `GovernanceServiceTest`'s "leadership can withdraw at any status" case,
 * which withdraws a REVIEWED Motion) is NOT "any non-terminal status" -- the only status that
 * actually blocks a manager-withdraw server-side is an already-WITHDRAWN Motion, which the server
 * rejects with a `ConflictException` regardless of caller. The previous
 * `motion.status in NON_TERMINAL_MOTION_STATUSES` gate silently hid this button for RESOLVED/
 * REJECTED/REJECTED_PRELIMINARY Motions even for BOARD/ADMIN, making a real, tested server
 * capability (e.g. correcting an erroneously recorded RESOLVED Motion) unreachable from the UI.
 * D7: real `confirmDialog` step either way.
 */
private fun renderWithdrawAction(
    panel: SimplePanel,
    motion: MotionDto,
    canManage: Boolean,
    isSubmitter: Boolean,
    onChanged: () -> Unit,
) {
    val submitterMayWithdraw = isSubmitter && motion.status == MotionStatus.SUBMITTED
    val managerMayWithdraw = canManage && motion.status != MotionStatus.WITHDRAWN
    if (!submitterMayWithdraw && !managerMayWithdraw) return

    val actionRow = panel.hPanel(spacing = 8)
    val withdrawButton = actionRow.actionButton(ActionIcon.UNDO, tr("Antrag zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)
    withdrawButton.onClick {
        confirmDialog(
            title = tr("Antrag zurückziehen"),
            message = gettext("\"%1\" wirklich zurückziehen?", motion.title),
            confirmLabel = tr("Zurückziehen"),
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().withdrawMotion(motion.id) }
                if (result != null) {
                    notifyInfo(tr("Antrag wurde zurückgezogen."))
                    onChanged()
                }
            }
        }
    }
}

/** Design decision D1: an amendment's detail view opens with a callout linking back to its
 * target main Motion. */
private fun renderAmendmentContext(
    panel: SimplePanel,
    parent: MotionDto,
    onSelectMotion: (String) -> Unit,
) {
    val callout = panel.vPanel(spacing = 4) { addCssClasses("alert alert-light border") }
    // Security audit follow-up (untrusted-text sanitization gaps): parent.title is member-controlled free text
    // (a MotionDto, same as amendment.title sanitized below in this file) composed into a gettext(...) string --
    // sanitize the whole composed result.
    callout.div(sanitizeUntrustedI18nText(gettext("Änderungsantrag zu: %1", parent.title))) { addCssClass("fw-bold") }
    val link = callout.button(tr("Zum Hauptantrag"), style = ButtonStyle.OUTLINESECONDARY)
    link.onClick { onSelectMotion(parent.id) }
}

/** Design decision D1: a main Motion's detail view always shows this section, even when empty,
 * so its presence is predictable rather than conditionally vanishing. The amendments are a small table with the real link. */
private fun renderAmendmentsSection(
    panel: SimplePanel,
    amendments: List<MotionDto>,
) {
    panel.h2(tr("Änderungsanträge zu diesem Antrag")) { addCssClass("h5") }
    if (amendments.isEmpty()) {
        panel.p(tr("Keine Änderungsanträge."))
        return
    }
    val table = panel.masterTable(tr("Änderungsanträge zu diesem Antrag"), listOf(TableHeader(tr("Titel")), TableHeader(tr("Status"))))
    amendments.forEach { amendment ->
        table.row {
            masterRow()
            val titleCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(titleCell)
            // Security audit W6b follow-up round 3 (major finding A): an amendment title is member-controlled free text,
            // sanitized inside [recordLink].
            titleCell.recordLink(amendment.title, Routes.MOTIONS, amendment.id)
            cell { statusBadge(motionStatusLabel(amendment.status), motionStatusColor(amendment.status)) }
        }
    }
}

internal fun renderReviewSection(
    panel: SimplePanel,
    motion: MotionDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    if (!canManage) return
    panel.h2(tr("Prüfung")) { addCssClass("h5") }
    // Formular-Grammatik (V1.4.29): eine optionale Notiz, "Annehmen" als Primäraktion, "Vorläufig ablehnen" in der Gefahrenzone.
    val form = panel.lapisForm()
    val noteField = form.textField(label = tr("Notiz"))
    val acceptButton = Button(tr("Annehmen"), style = ButtonStyle.SUCCESS)
    val rejectButton = Button(tr("Vorläufig ablehnen"), style = ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = acceptButton, destructive = rejectButton)

    fun review(
        decision: MotionReviewDecision,
        pressed: Button,
        other: Button,
    ) {
        form.submit(pressed) {
            other.disabled = true
            try {
                val result =
                    guarded {
                        rpcService<IGovernanceService>().reviewMotion(
                            motion.id,
                            decision,
                            noteField.value.trim().takeIf { it.isNotBlank() },
                        )
                    }
                if (result != null) {
                    val message =
                        if (decision == MotionReviewDecision.ACCEPT) {
                            tr("Antrag angenommen zur Terminierung.")
                        } else {
                            tr("Antrag vorläufig abgelehnt.")
                        }
                    notifySuccess(message)
                    onChanged()
                }
            } finally {
                other.disabled = false
            }
        }
    }
    acceptButton.onClick { review(MotionReviewDecision.ACCEPT, acceptButton, rejectButton) }
    rejectButton.onClick { review(MotionReviewDecision.REJECT, rejectButton, acceptButton) }
}

/**
 * Design decision D2: an amendment's scheduling control is a single reduced "Jetzt terminieren"
 * button using its parent's own Meeting silently (both `meetingId`/`position` the server actually
 * uses are pre-filled/ignored) -- not the full meeting/position picker a main Motion gets.
 */
private fun renderScheduleSection(
    panel: SimplePanel,
    motion: MotionDto,
    parent: MotionDto?,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    if (!canManage) return
    panel.h2(tr("Terminierung")) { addCssClass("h5") }

    if (motion.amendsMotionId != null) {
        val parentMeetingId = parent?.meetingId
        if (parentMeetingId == null) {
            panel.p(tr("Der Hauptantrag muss zuerst selbst terminiert werden."))
            return
        }
        // Security audit follow-up (untrusted-text sanitization gaps): parent.title is member-controlled free
        // text composed into a gettext(...) string -- sanitize the whole composed result, same as above.
        panel.div(sanitizeUntrustedI18nText(gettext("Wird automatisch auf dieselbe Sitzung terminiert wie \"%1\".", parent.title))) {
            addCssClasses("text-muted small")
        }
        val scheduleButton = panel.button(tr("Jetzt terminieren"), style = ButtonStyle.PRIMARY)
        scheduleButton.onClick {
            scheduleButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().scheduleMotion(motion.id, parentMeetingId, 0) }
                scheduleButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Änderungsantrag terminiert."))
                    onChanged()
                }
            }
        }
        return
    }

    AppScope.launch {
        val meetings =
            guarded {
                rpcService<IGovernanceService>().listMeetings(motion.targetCommitteeId, MeetingStatus.PLANNED)
            } ?: emptyList()
        if (meetings.isEmpty()) {
            panel.p(tr("Keine geplante Sitzung für dieses Gremium verfügbar -- zuerst auf \"Sitzungen\" eine anlegen."))
            return@launch
        }
        renderScheduleForm(panel, motion, meetings, onChanged)
    }
}

internal fun renderScheduleForm(
    panel: SimplePanel,
    motion: MotionDto,
    meetings: List<MeetingDto>,
    onChanged: () -> Unit,
) {
    val form = panel.lapisForm()
    val meetingOptions = meetings.map { it.id to gettext("%1 (%2)", it.title, formatDateTime(it.scheduledAt)) }
    val meetingField = form.selectField(label = tr("Sitzung"), options = meetingOptions, value = meetings.firstOrNull()?.id)
    val positionField =
        form.textField(
            label = tr("Position auf der Tagesordnung"),
            value = "1",
            required = true,
            rule = { FormRules.wholeNumber(value = it) },
        )

    val scheduleButton = Button(tr("Terminieren"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = scheduleButton)
    scheduleButton.onClick {
        form.submit(scheduleButton) {
            val meetingId = meetingField.value.takeIf { it.isNotBlank() }
            if (meetingId == null) {
                form.showFormError(tr("Bitte eine Sitzung und eine gültige Position angeben."))
                return@submit
            }
            val result =
                guarded { rpcService<IGovernanceService>().scheduleMotion(motion.id, meetingId, positionField.value.trim().toInt()) }
            if (result != null) {
                notifySuccess(gettext("Antrag \"%1\" terminiert.", motion.title))
                onChanged()
            }
        }
    }
}

/** The four ways to decide a scheduled motion, in the order the selector shows them. */
internal enum class DecisionMethod { QUORUM, MERIT, ELECTION, CONSENSUS }

/** One selector segment: whether the way is open and, if not, why (already translated). */
internal data class DecisionMethodState(
    val method: DecisionMethod,
    val enabled: Boolean,
    val disabledReason: String?,
)

/**
 * Pure and testable: which ways are open, in selector order, and why one is closed. Mirrors the server rules (the server stays the
 * authority; its refusals still end in the conflict dialog): open amendments close all four; an evaluated (advisory) consensus closes
 * a second one -- one consensus per motion.
 */
internal fun decisionMethodStates(
    pendingAmendments: List<MotionDto>,
    consensuses: List<SystemicConsensusDto>,
): List<DecisionMethodState> {
    val amendmentsOpen = pendingAmendments.isNotEmpty()
    val amendmentsReason = gettext("Zuerst alle Änderungsanträge entscheiden")
    val consensusExists = consensuses.any { it.status != SystemicConsensusStatus.ABORTED }
    return DecisionMethod.entries.map { method ->
        when {
            amendmentsOpen -> DecisionMethodState(method, enabled = false, disabledReason = amendmentsReason)
            method == DecisionMethod.CONSENSUS && consensusExists ->
                DecisionMethodState(
                    method,
                    enabled = false,
                    disabledReason = gettext("Pro Antrag ist nur ein Konsensieren möglich; die Sondierung ist bereits ausgewertet."),
                )
            else -> DecisionMethodState(method, enabled = true, disabledReason = null)
        }
    }
}

/** The default choice of the selector: the first open way, or `null` when all are closed. */
internal fun firstEnabledMethod(states: List<DecisionMethodState>): DecisionMethod? = states.firstOrNull { it.enabled }?.method

private fun decisionMethodLabel(method: DecisionMethod): String =
    when (method) {
        DecisionMethod.QUORUM -> gettext("Quorum-Beschluss des Gremiums")
        DecisionMethod.MERIT -> gettext("Meritokratische Abstimmung")
        DecisionMethod.ELECTION -> gettext("Wahl")
        DecisionMethod.CONSENSUS -> gettext("Konsensieren")
    }

private fun decisionMethodExplanation(method: DecisionMethod): String =
    when (method) {
        DecisionMethod.QUORUM ->
            gettext(
                "Das Gremium hat in der Sitzung abgestimmt; Sie halten hier die Ja-, Nein- und Enthaltungsstimmen und das Ergebnis fest.",
            )
        DecisionMethod.MERIT ->
            gettext(
                "Die Mitglieder stimmen online mit Ja oder Nein ab und gewichten ihre Stimme mit einem LTR-Einsatz; " +
                    "es gilt die Antwort mit dem höchsten Gewicht.",
            )
        DecisionMethod.ELECTION ->
            gettext(
                "Eine förmliche Wahl mit eigenem Ablauf (Ja/Nein-Frage oder Kandidaten), die auf einer eigenen Seite durchgeführt " +
                    "und ausgewertet wird.",
            )
        DecisionMethod.CONSENSUS ->
            gettext(
                "Die Teilnehmenden bewerten Vorschläge nach ihrem Widerstand; der Vorschlag mit dem geringsten Widerstand gewinnt " +
                    "– als unverbindliche Sondierung vorab oder als verbindliche Entscheidung.",
            )
    }

/** Id of the sentence under the selector; the radio group points to it with `aria-describedby`. */
internal const val DECISION_HINT_ID = "motion-decision-hint"

/**
 * V1.9.193 -- "Wie soll entschieden werden?": ONE segmented radio group instead of four forms stacked on top of each other. Only the
 * form of the chosen way is in the DOM (switching clears the holder, so no stale handler survives). Closed ways stay in the group as
 * `aria-disabled` and their reason is written out below it, visibly (not only in a tooltip).
 */
private fun renderDecisionSelector(
    panel: SimplePanel,
    motion: MotionDto,
    states: List<DecisionMethodState>,
    committees: List<CommitteeDto>,
    onChanged: () -> Unit,
) {
    val host = panel.vPanel(spacing = 6) { addCssClass("lapis-decision-selector") }
    val first = firstEnabledMethod(states)
    val formHolder = SimplePanel()
    var hint: P? = null

    fun showMethod(method: DecisionMethod) {
        formHolder.removeAll()
        hint?.content = decisionMethodExplanation(method)
        when (method) {
            DecisionMethod.QUORUM -> renderCommitteeQuorumResolutionForm(formHolder, motion, onChanged)
            DecisionMethod.MERIT -> renderOpenVoteForm(formHolder, motion, onChanged)
            DecisionMethod.ELECTION ->
                renderOpenElectionForm(formHolder, motion, committees, onConflict = onChanged) { opened ->
                    navigateTo("/elections/${opened.id}")
                }
            DecisionMethod.CONSENSUS ->
                renderOpenConsensusForm(formHolder, motion, onConflict = onChanged) { opened ->
                    navigateTo("/consensus/${opened.id}")
                }
        }
    }

    val group =
        host.segmentedRadioGroup(
            options =
                states.map { state ->
                    SegmentOption(
                        value = state.method,
                        label = decisionMethodLabel(state.method),
                        enabled = state.enabled,
                        disabledHint = state.disabledReason,
                    )
                },
            selected = first ?: DecisionMethod.QUORUM,
            ariaLabel = tr("Wie soll entschieden werden?"),
            onSelect = ::showMethod,
        )
    states.filter { !it.enabled }.forEach { state ->
        host.div(gettext("Nicht verfügbar: %1 – %2", decisionMethodLabel(state.method), state.disabledReason.orEmpty())) {
            addCssClasses("small text-body-secondary")
        }
    }
    if (first != null) {
        hint = host.p("") { setAttribute("id", DECISION_HINT_ID) }
        group.setAttribute("aria-describedby", DECISION_HINT_ID)
        host.add(formHolder)
        showMethod(first)
    }
}

/**
 * Design decision D2: while [pendingAmendments] is non-empty, all four ways render closed with an explanatory `alert-warning` box
 * (Norman: explain, don't hide, why an action is unavailable) listing every blocking amendment as a link. Once clear, the ways are
 * offered in the selector of [renderDecisionSelector] -- unless a vote, election or consensus already runs or exists: then a plain
 * status sentence replaces the selector, to avoid parallel resolution paths racing to finalize the same Motion twice.
 */
internal fun renderResolutionSection(
    panel: SimplePanel,
    motion: MotionDto,
    pendingAmendments: List<MotionDto>,
    canManage: Boolean,
    activeVote: VoteDto?,
    onSelectMotion: (String) -> Unit,
    onChanged: () -> Unit,
    elections: List<ElectionDto> = emptyList(),
    committees: List<CommitteeDto> = emptyList(),
    consensuses: List<SystemicConsensusDto> = emptyList(),
) {
    if (!canManage) return

    if (pendingAmendments.isNotEmpty()) {
        panel.h2(tr("Wie soll entschieden werden?")) { addCssClass("h5") }
        val count = pendingAmendments.size
        val warningBox = panel.vPanel(spacing = 4) { addCssClasses("alert alert-warning") }
        // Audit fix M4: two whole sentences instead of a hand-built German plural noun passed as an argument (a plural cannot be translated).
        val warning =
            if (count == 1) {
                gettext("Dieser Antrag hat 1 offenen Änderungsantrag, der zuerst entschieden werden muss:")
            } else {
                gettext("Dieser Antrag hat %1 offene Änderungsanträge, die zuerst entschieden werden müssen:", count)
            }
        warningBox.div(warning) { addCssClass("fw-bold") }
        pendingAmendments.forEach { amendment ->
            val row = warningBox.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            // Security audit W6b follow-up round 3 (major finding A): see the sibling sanitization in
            // `renderAmendmentsSection` above -- same untrusted `amendment.title`, same widget-content path.
            row.div(sanitizeUntrustedI18nText(amendment.title)) { addCssClasses("flex-grow-1") }
            row.statusBadge(motionStatusLabel(amendment.status), motionStatusColor(amendment.status))
            val link = row.actionButton(ActionIcon.VIEW, tr("Anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
            link.onClick { onSelectMotion(amendment.id) }
        }
        renderDecisionSelector(panel, motion, decisionMethodStates(pendingAmendments, consensuses), committees, onChanged)
        return
    }

    panel.h2(tr("Entscheidung")) { addCssClass("h5") }
    if (activeVote != null && activeVote.status == VoteStatus.OPEN) {
        // V1.9.54: the server refuses resolveMotion while a vote is OPEN, so the form is not offered at all.
        panel
            .p(
                tr(
                    "Zu diesem Antrag läuft noch eine meritokratische Abstimmung. Schließen Sie sie oder brechen Sie sie ab, bevor Sie entscheiden.",
                ),
            ) {
                addCssClasses("alert alert-info mb-0")
                setAttribute("role", "note")
            }
        return
    }
    if (activeVote != null && activeVote.status == VoteStatus.CLOSED) {
        panel.p(tr("Zu diesem Antrag gibt es bereits eine meritokratische Abstimmung -- siehe Abschnitt \"Abstimmung\" unten."))
        return
    }

    // V1.9.22: a running or finished election already decides this motion -- the other ways would race it, so none is
    // offered, only the way back to the election. V1.9.54: the server refuses resolveMotion while one runs; while it is still running
    // the hint says what to do first.
    val runningElection = elections.firstOrNull { it.status != ElectionStatus.ABORTED }
    if (runningElection != null) {
        if (runningElection.status == ElectionStatus.TALLIED) {
            panel.p(tr("Zu diesem Antrag läuft eine Wahl.")) { addCssClasses("alert alert-info mb-0") }
        } else {
            panel.p(tr("Zu diesem Antrag läuft noch eine Wahl. Werten Sie sie aus oder brechen Sie sie ab, bevor Sie entscheiden.")) {
                addCssClasses("alert alert-info mb-0")
                setAttribute("role", "note")
            }
        }
        panel.button(tr("Zur Wahl"), style = ButtonStyle.PRIMARY).onClick { navigateTo("/elections/${runningElection.id}") }
        return
    }

    if (renderConsensusDecisionGate(panel, consensuses)) return

    renderDecisionSelector(panel, motion, decisionMethodStates(pendingAmendments, consensuses), committees, onChanged)
}

internal fun renderCommitteeQuorumResolutionForm(
    panel: SimplePanel,
    motion: MotionDto,
    onChanged: () -> Unit,
) {
    // Formular-Grammatik (V1.4.29): Ja/Nein/Enthaltungen sind drei gleichtypige Zahlenfelder -- der Body-Test pinnt die Zuordnung.
    val form = panel.lapisForm()
    val votesYesField =
        form.textField(label = tr("Ja-Stimmen"), value = "0", required = true, rule = { FormRules.intAtLeast(value = it, min = 0) })
    val votesNoField =
        form.textField(label = tr("Nein-Stimmen"), value = "0", required = true, rule = { FormRules.intAtLeast(value = it, min = 0) })
    val votesAbstainField =
        form.textField(label = tr("Enthaltungen"), value = "0", required = true, rule = { FormRules.intAtLeast(value = it, min = 0) })
    val statusOptions = ResolutionStatus.entries.map { it.name to resolutionStatusLabel(it) }
    val statusField =
        form.selectField(label = tr("Status"), options = statusOptions, value = ResolutionStatus.ADOPTED.name, required = true)
    form.panel.div(tr("Nach dem Speichern steht das Ergebnis fest und kann nicht mehr geändert werden.")) {
        addCssClasses("text-muted small")
    }

    val resolveButton = newActionButton(ActionIcon.SAVE, tr("Ergebnis festhalten"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = resolveButton)
    resolveButton.onClick {
        form.submit(resolveButton) {
            val result =
                motionDecisionGuarded(onConflict = onChanged) {
                    rpcService<IGovernanceService>().resolveMotion(
                        motion.id,
                        MotionResolutionInput(
                            votesYes = votesYesField.value.trim().toInt(),
                            votesNo = votesNoField.value.trim().toInt(),
                            votesAbstain = votesAbstainField.value.trim().toInt(),
                            status = ResolutionStatus.valueOf(statusField.value),
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("Entscheidung für \"%1\" gespeichert.", motion.title))
                onChanged()
            }
        }
    }
}

/** Kleinste zulässige Zahl unterschiedlicher Optionen einer Abstimmung (Serverregel bleibt Autorität). */
private const val MIN_VOTE_OPTIONS = 2

/**
 * Display only: the wire format stays "YES"/"NO", because `closeVote` treats every winner other than "NO" as ADOPTED. Everything else
 * is member-controlled free text and sanitized.
 */
internal fun voteOptionDisplayLabel(raw: String): String =
    when (raw.trim().uppercase()) {
        "YES" -> gettext("Ja")
        "NO" -> gettext("Nein")
        else -> sanitizeUntrustedI18nText(raw)
    }

internal fun renderOpenVoteForm(
    panel: SimplePanel,
    motion: MotionDto,
    onChanged: () -> Unit,
) {
    // The standard is Ja/Nein (sent as YES/NO); own answers live under "Erweitert" with a warning.
    val chips = panel.hPanel(spacing = 6) { addCssClasses("align-items-center") }
    MERIT_YES_NO_LABELS.forEach { raw -> chips.span(voteOptionDisplayLabel(raw)) { addCssClasses("badge text-bg-light border") } }
    val form = panel.lapisForm()

    fun parsedLabels(raw: String): List<String> =
        raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    val details = Tag(TAG.DETAILS, className = "small")
    details.add(Tag(TAG.SUMMARY, content = gettext("Erweitert: eigene Antwortmöglichkeiten")))
    details.add(
        Tag(
            TAG.P,
            content = gettext("Achtung: Gewinnt eine andere Antwort als \"NO\", gilt der Antrag als angenommen."),
            className = "alert alert-warning mt-2 mb-2",
        ),
    )
    val advanced = SimplePanel()
    details.add(advanced)
    form.panel.add(details)
    val labelsField =
        form.textField(
            label = tr("Antwortmöglichkeiten, durch Komma getrennt"),
            host = advanced,
            value = MERIT_YES_NO_LABELS.joinToString(","),
            required = true,
            // Die Untergrenze steht als Platzhalter im Hinweis, nicht im msgid des Labels (eine Konstante in acht Katalogen).
            hint = gettext("Kommagetrennt, mindestens %1 verschiedene Optionen.", MIN_VOTE_OPTIONS),
            rule = { raw ->
                if (parsedLabels(raw).size < MIN_VOTE_OPTIONS) {
                    FieldCheck.Invalid(gettext("Bitte mindestens 2 unterschiedliche Optionen angeben."))
                } else {
                    FieldCheck.Ok
                }
            },
        )

    val openButton = Button(tr("Abstimmung eröffnen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = openButton)
    openButton.onClick {
        form.submit(openButton) {
            // Untouched, the field still holds "YES,NO": exactly the wire labels.
            val labels = parsedLabels(labelsField.value)
            val result =
                guarded { rpcService<IGovernanceService>().openVote(VoteOpenInput(motionId = motion.id, optionLabels = labels)) }
            if (result != null) {
                notifySuccess(tr("Abstimmung eröffnet."))
                onChanged()
            }
        }
    }
}

private fun renderOutcomeSummary(
    panel: SimplePanel,
    motion: MotionDto,
) {
    panel.h2(tr("Ergebnis")) { addCssClass("h5") }
    if (motion.resolutionId == null) {
        panel.p(tr("Kein Beschluss verknüpft."))
        return
    }
    AppScope.launch {
        val meetingId = motion.meetingId
        val resolution =
            if (meetingId != null) {
                guarded { rpcService<IGovernanceService>().listResolutions(meetingId = meetingId) }
                    ?.find { it.id == motion.resolutionId }
            } else {
                null
            }
        if (resolution == null) {
            panel.p(tr("Beschluss konnte nicht geladen werden."))
        } else {
            renderResolutionRow(panel, resolution)
        }
    }
}

/**
 * Design decision D3: while OPEN, only a plain count, the caller's own ballot, and (privileged
 * only) a names-only participant list are shown -- never running totals or a leaderboard, even
 * though [network.lapis.cloud.shared.rpc.IGovernanceService.listVoteBallots] technically returns
 * full amounts to any authenticated member the instant a ballot lands (see this file's own class
 * KDoc D3 summary for the full rejected-alternative rationale). Full reveal (basket totals, the
 * complete ballot table, winner/second-price/tie) only once CLOSED.
 */
internal fun renderVoteSection(
    panel: SimplePanel,
    vote: VoteDto,
    canManage: Boolean,
    currentMemberId: String,
    isEligibleToBallot: Boolean,
    onChanged: () -> Unit,
) {
    panel.h2(tr("Abstimmung")) { addCssClass("h5") }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    // Security audit W6b follow-up (major finding 4): vote.title is server-/member-controlled free text, rendered
    // directly as widget content. KVision resolves ANY content starting with KV_I18N_MARKER through I18n.trans, so
    // this must be sanitized here even though it never goes through trFormat/gettext.
    headerRow.div(sanitizeUntrustedI18nText(vote.title)) { addCssClasses("flex-grow-1") }
    headerRow.statusBadge(voteStatusLabel(vote.status), voteStatusColor(vote.status))

    AppScope.launch {
        val ballots = guarded { rpcService<IGovernanceService>().listVoteBallots(vote.id) } ?: emptyList()

        if (vote.status == VoteStatus.OPEN) {
            panel.div(gettext("%1 Stimmen abgegeben.", ballots.size)) { addCssClasses("text-muted small") }
            val myBallot = ballots.find { it.memberId == currentMemberId }
            if (myBallot != null) {
                val optionLabel = voteOptionDisplayLabel(vote.options.find { it.id == myBallot.optionId }?.label ?: myBallot.optionId)
                panel.div(trFormat(tr("Ihr Gebot: %1, %2"), optionLabel, trusted(ltrToken(myBallot.stakeLtr)))) {
                    addCssClasses("text-muted small")
                }
            }
            if (canManage) {
                panel.div(
                    gettext(
                        "Teilnehmende: %1",
                        ballots.joinToString(", ") { it.memberDisplayName }.ifBlank { gettext("keine") },
                    ),
                ) {
                    addCssClasses("text-muted small")
                }
            }
            if (isEligibleToBallot) {
                renderBallotForm(
                    panel = panel,
                    model = vote.toBallotFormModel(),
                    currentOptionId = myBallot?.optionId,
                    onChanged = onChanged,
                    onNotCounted = {
                        // V1.9.54: the vote closed under the bid -- say so lastingly (the reload below rebuilds this view).
                        RejectedBidNotice.mark(vote.id)
                        onChanged()
                    },
                )
            }
            if (canManage) {
                renderVoteControls(panel, vote, onChanged)
            }
        } else {
            vote.options.forEach { option ->
                val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                // Security audit W6b follow-up (major finding 4): see the sanitizeUntrustedI18nText note on vote.title above.
                row.div(voteOptionDisplayLabel(option.label)) { addCssClasses("flex-grow-1") }
                if (option.id == vote.winnerOptionId) row.statusBadge(tr("Gewinner"), "success")
                row.ltrSpan(option.basketTotalLtr)
            }
            if (vote.winnerOptionId == null) {
                panel.p(
                    tr(
                        "Unentschieden -- die höchsten beiden Gebotskörbe waren gleich hoch. Kein Gewinner, keine " +
                            "Belastung; der Antrag wird zurückgestellt (POSTPONED).",
                    ),
                )
            } else {
                vote.secondPriceLtr?.let { price ->
                    panel.div(
                        trFormat(tr("Preis: %1 (Vickrey-Zweitpreis)"), trusted(ltrToken(price))),
                    ) { addCssClasses("text-muted small") }
                }
            }
            panel.h2(tr("Alle Gebote")) { addCssClass("h6") }
            if (ballots.isEmpty()) {
                panel.p(tr("Keine Gebote abgegeben."))
            } else {
                ballots.forEach { ballot ->
                    val optionLabel = voteOptionDisplayLabel(vote.options.find { it.id == ballot.optionId }?.label ?: ballot.optionId)
                    val settledLtr = ballot.settledLtr
                    panel.div(
                        if (settledLtr != null) {
                            trFormat(
                                tr("%1: %2, %3 · belastet: %4"),
                                ballot.memberDisplayName,
                                optionLabel,
                                trusted(ltrToken(ballot.stakeLtr)),
                                trusted(ltrToken(settledLtr)),
                            )
                        } else {
                            trFormat(
                                tr("%1: %2, %3"),
                                ballot.memberDisplayName,
                                optionLabel,
                                trusted(ltrToken(ballot.stakeLtr)),
                            )
                        },
                    ) {
                        addCssClasses("text-muted small")
                    }
                }
            }
        }
    }
}

/** Design decision D7: `closeVote` is a forward/completing transition (no confirm, mirrors
 * `updateMeetingStatus -> HELD`); `abortVote` is destructive and gets the real `confirmDialog`. */
private fun renderVoteControls(
    panel: SimplePanel,
    vote: VoteDto,
    onChanged: () -> Unit,
) {
    val actionRow = panel.hPanel(spacing = 8)
    val closeButton = actionRow.actionButton(ActionIcon.CLOSE, tr("Abstimmung schließen"), style = ButtonStyle.SUCCESS)
    closeButton.onClick {
        AppScope.launch {
            val result = guarded { rpcService<IGovernanceService>().closeVote(vote.id) }
            if (result != null) {
                notifySuccess(tr("Abstimmung geschlossen."))
                onChanged()
            }
        }
    }
    val abortButton = actionRow.actionButton(ActionIcon.CANCEL, tr("Abstimmung abbrechen"), style = ButtonStyle.OUTLINEDANGER)
    abortButton.onClick {
        confirmDialog(
            title = tr("Abstimmung abbrechen"),
            message = gettext("\"%1\" wirklich abbrechen? Es wird keine Abrechnung durchgeführt.", vote.title),
            confirmLabel = tr("Abbrechen"),
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().abortVote(vote.id) }
                if (result != null) {
                    notifyInfo(tr("Abstimmung abgebrochen."))
                    onChanged()
                }
            }
        }
    }
}

/**
 * Mirrors `network.lapis.cloud.server.rpc.NON_TERMINAL_MOTION_STATUSES` (server-internal, not
 * exposed) -- used for the D2 amendment-ordering-guard display and for computing which existing
 * Motions are still amendable ([renderMotionsScreen]'s `amendableMotions` loading loop).
 * [renderWithdrawAction]'s manager-withdraw gate does NOT use this set -- the server allows
 * leadership/BOARD/ADMIN to withdraw at any status except already-WITHDRAWN, terminal statuses
 * included (see that function's own KDoc).
 */
internal val NON_TERMINAL_MOTION_STATUSES =
    setOf(MotionStatus.SUBMITTED, MotionStatus.REVIEWED, MotionStatus.SCHEDULED, MotionStatus.POSTPONED)

/**
 * German label/badge-color tables for [MotionStatus]/[VoteStatus] -- this screen's own
 * instantiation of the [statusBadge]/[typeBadge] grammar (see `StatusBadge.kt` KDoc and the design
 * review's exact table). Non-private so [MotionsScreenTest] can cover them directly, same posture
 * as `MeetingsScreen.kt`'s own label/color functions.
 */
fun motionStatusLabel(status: MotionStatus): String =
    when (status) {
        MotionStatus.SUBMITTED -> gettext("Eingereicht")
        MotionStatus.REVIEWED -> gettext("Geprüft")
        MotionStatus.REJECTED_PRELIMINARY -> gettext("Vorläufig abgelehnt")
        MotionStatus.SCHEDULED -> gettext("Terminiert")
        MotionStatus.RESOLVED -> gettext("Angenommen")
        MotionStatus.REJECTED -> gettext("Abgelehnt")
        MotionStatus.POSTPONED -> gettext("Zurückgestellt")
        MotionStatus.WITHDRAWN -> gettext("Zurückgezogen")
    }

fun motionStatusColor(status: MotionStatus): String =
    when (status) {
        MotionStatus.SUBMITTED -> "secondary"
        MotionStatus.REVIEWED -> "info"
        MotionStatus.REJECTED_PRELIMINARY -> "danger"
        MotionStatus.SCHEDULED -> "primary"
        MotionStatus.RESOLVED -> "success"
        MotionStatus.REJECTED -> "danger"
        MotionStatus.POSTPONED -> "warning"
        MotionStatus.WITHDRAWN -> "dark"
    }

fun voteStatusLabel(status: VoteStatus): String =
    when (status) {
        VoteStatus.OPEN -> gettext("Läuft")
        VoteStatus.CLOSED -> gettext("Geschlossen")
        VoteStatus.ABORTED -> gettext("Abgebrochen")
    }

fun voteStatusColor(status: VoteStatus): String =
    when (status) {
        VoteStatus.OPEN -> "primary"
        VoteStatus.CLOSED -> "secondary"
        VoteStatus.ABORTED -> "dark"
    }
