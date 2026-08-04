package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.VoteBallotInput
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
 *   .MemberStatus.AKTIV] member for the General Assembly, or any active Committee membership (any
 *   role) for a specific Committee. The submission form below restricts its target-Committee
 *   picker to Committees the caller plausibly qualifies for (mirrors the Meetings screen's
 *   `manageableCommittees` computation), but a stale client-side cache is still gracefully
 *   surfaced as a `guarded()` 403 rather than crashing.
 * - `castVoteBallot`: any member in the Vote's underlying Meeting/Committee eligibility set (same
 *   rule [network.lapis.cloud.client.GovernanceAuthzUi] doesn't cover -- mirrors the Meetings
 *   screen's own `eligibleMembers` computation, see [renderVoteSection]).
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
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1("Anträge")

    root.h2("Übersicht")
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val committeeFilterSelect = filterRow.select(options = listOf("" to "Alle Gremien"), value = "", label = "Gremium")
    val statusFilterOptions = listOf("" to "Alle Status") + MotionStatus.entries.map { it.name to motionStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = "Status")
    val refreshButton = filterRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val motionListPanel = root.vPanel(spacing = 6)

    root.h2("Details")
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p("Antrag oben auswählen, um Details zu sehen.")

    root.h2("Neuen Antrag einreichen")
    val submissionPanel = root.vPanel(spacing = 6)
    submissionPanel.p("Wird geladen …") { addCssClasses("text-muted small") }

    var committees: List<CommitteeDto> = emptyList()
    var currentDetailMotionId: String? = null

    fun selectMotion(motionId: String) {
        currentDetailMotionId = motionId
    }

    fun refreshDetail() {
        val motionId = currentDetailMotionId ?: return
        renderMotionDetail(detailPanel, motionId, currentMemberId, isBoardOrAdmin, committees, ::selectMotion) {
            refreshDetail()
        }
    }

    fun refreshMotions() {
        motionListPanel.removeAll()
        AppScope.launch {
            val committeeId = committeeFilterSelect.value?.takeIf { it.isNotBlank() }
            val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { MotionStatus.valueOf(it) }
            val motions = guarded { rpcService<IGovernanceService>().listMotions(committeeId, status) } ?: return@launch
            if (motions.isEmpty()) {
                motionListPanel.p("Noch keine Anträge vorhanden.")
                return@launch
            }
            renderMotionList(motionListPanel, motions) { selected ->
                selectMotion(selected.id)
                refreshDetail()
            }
        }
    }

    refreshButton.onClick { refreshMotions() }

    AppScope.launch {
        committees = guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = false) } ?: emptyList()
        committeeFilterSelect.options = listOf("" to "Alle Gremien") + committees.map { it.id to it.name }
        committeeFilterSelect.value = ""
        refreshMotions()

        // Which Committees the current member may plausibly submit a Motion to (plan §4's
        // broadest gate) -- BOARD/ADMIN may submit anywhere active; the General Assembly is a
        // broad AKTIV-member right (residual "was I actually still AKTIV" race falls through to
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

        submissionPanel.removeAll()
        if (submittableCommittees.isEmpty()) {
            submissionPanel.p("Keine Berechtigung, Anträge einzureichen.")
        } else {
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
            renderMotionSubmissionForm(submissionPanel, submittableCommittees, amendableMotions) { refreshMotions() }
        }
    }
}

/**
 * Design decision D1: main Motions render first, each immediately followed by its own amendments
 * indented beneath it (`ps-4`, `↳ ` prefix). A filtered/paginated [motions] list may not contain an
 * amendment's own parent (different committee/status filter) -- such an orphaned amendment falls
 * back to rendering unindented at the top level rather than being silently dropped.
 */
private fun renderMotionList(
    panel: SimplePanel,
    motions: List<MotionDto>,
    onSelect: (MotionDto) -> Unit,
) {
    val mainMotions = motions.filter { it.amendsMotionId == null }
    val amendmentsByParentId = motions.filter { it.amendsMotionId != null }.groupBy { it.amendsMotionId }
    val rendered = mutableSetOf<String>()

    mainMotions.forEach { main ->
        renderMotionRow(panel, main, indented = false, onSelect = onSelect)
        rendered += main.id
        amendmentsByParentId[main.id]?.forEach { amendment ->
            renderMotionRow(panel, amendment, indented = true, onSelect = onSelect)
            rendered += amendment.id
        }
    }
    motions.filter { it.id !in rendered }.forEach { orphan ->
        renderMotionRow(panel, orphan, indented = false, onSelect = onSelect)
    }
}

private fun renderMotionRow(
    panel: SimplePanel,
    motion: MotionDto,
    indented: Boolean,
    onSelect: (MotionDto) -> Unit,
) {
    val isAmendment = motion.amendsMotionId != null
    val row =
        panel.vPanel(spacing = 4) {
            addCssClasses("border rounded p-2")
            if (indented) addCssClasses("ps-4")
        }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val title = if (isAmendment) "↳ Änderungsantrag: ${motion.title}" else motion.title
    headerRow.div(title) { addCssClasses("flex-grow-1 fw-bold") }
    if (isAmendment) headerRow.typeBadge("Änderungsantrag", "secondary")
    headerRow.statusBadge(motionStatusLabel(motion.status), motionStatusColor(motion.status))
    row.div(
        "${motion.targetCommitteeName} · eingereicht von ${motion.submitterDisplayName} am ${motion.submittedAt}",
    ) { addCssClasses("text-muted small") }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.button("Details anzeigen", style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(motion) }
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
private fun renderMotionSubmissionForm(
    panel: SimplePanel,
    submittableCommittees: List<CommitteeDto>,
    amendableMotions: List<Pair<CommitteeDto, MotionDto>>,
    onSubmitted: () -> Unit,
) {
    val committeeOptions = submittableCommittees.map { it.id to it.name }
    val committeeSelect = panel.select(options = committeeOptions, value = submittableCommittees.firstOrNull()?.id, label = "Zielgremium")
    val amendsOptions =
        listOf("" to "-- kein (neuer Hauptantrag) --") +
            amendableMotions.map { (committee, motion) -> motion.id to "${committee.name}: ${motion.title}" }
    val amendsSelect = panel.select(options = amendsOptions, value = "", label = "Ändert bestehenden Antrag (optional)")
    panel.div(
        "Wird ein bestehender Antrag zum Ändern ausgewählt, wird dessen Gremium automatisch verwendet -- " +
            "das Zielgremium oben wird dann ignoriert.",
    ) { addCssClasses("text-muted small") }
    val titleInput = panel.text(label = "Titel")
    val rationaleInput = panel.text(label = "Begründung")
    val textInput = panel.textArea(label = "Antragstext", rows = 4)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val submitButton = panel.button("Antrag einreichen", style = ButtonStyle.PRIMARY)
    submitButton.onClick {
        errorBox.hide()
        val amendsId = amendsSelect.value?.takeIf { it.isNotBlank() }
        val targetCommitteeId =
            if (amendsId != null) {
                amendableMotions.find { (_, motion) -> motion.id == amendsId }?.first?.id
            } else {
                committeeSelect.value
            }
        val title = titleInput.value.orEmpty().trim()
        val rationale = rationaleInput.value.orEmpty().trim()
        val text = textInput.value.orEmpty().trim()

        if (targetCommitteeId == null || !Validation.isNonBlank(title) || !Validation.isNonBlank(text)) {
            errorBox.content = "Bitte Zielgremium (oder einen zu ändernden Antrag), Titel und Antragstext angeben."
            errorBox.show()
            return@onClick
        }

        submitButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().submitMotion(
                        MotionInput(
                            targetCommitteeId = targetCommitteeId,
                            title = title,
                            rationale = rationale,
                            text = text,
                            amendsMotionId = amendsId,
                        ),
                    )
                }
            submitButton.disabled = false
            if (result != null) {
                notifySuccess("Antrag \"$title\" wurde eingereicht.")
                titleInput.value = null
                rationaleInput.value = null
                textInput.value = null
                amendsSelect.value = ""
                onSubmitted()
            }
        }
    }
}

/**
 * Loads the Motion itself, its target Committee's roster (for [GovernanceAuthzUi]), its parent (if
 * it is an amendment) or its own amendments (if it is a main Motion), and any Vote opened against
 * it -- then dispatches the status-specific action sections. [onSelectMotion] lets a nested link
 * (the D1 parent callout, an amendment row, a D2 blocking-amendment link) reselect a different
 * Motion within this same screen without a route change.
 */
private fun renderMotionDetail(
    panel: SimplePanel,
    motionId: String,
    currentMemberId: String,
    isBoardOrAdmin: Boolean,
    committees: List<CommitteeDto>,
    onSelectMotion: (String) -> Unit,
    onChanged: () -> Unit,
) {
    panel.removeAll()
    panel.p("Wird geladen …")
    AppScope.launch {
        val motion = guarded { rpcService<IGovernanceService>().getMotion(motionId) } ?: return@launch
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

        val votes = guarded { rpcService<IGovernanceService>().listVotes(motionId = motion.id) } ?: emptyList()
        val activeVote =
            votes.find { it.status == VoteStatus.OPEN } ?: votes.find { it.status == VoteStatus.CLOSED }

        val committee = committees.find { it.id == motion.targetCommitteeId }
        val eligibleMembers: List<MemberSummaryDto> =
            if (committee?.type == CommitteeType.GENERAL_ASSEMBLY) {
                guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
            } else {
                roster.map { MemberSummaryDto(id = it.memberId, displayName = it.memberDisplayName) }
            }

        panel.removeAll()
        renderMotionMeta(panel, motion, canManage, isSubmitter, onChanged)
        if (parent != null) renderAmendmentContext(panel, parent, onSelectMotion)
        if (motion.amendsMotionId == null) renderAmendmentsSection(panel, amendments, onSelectMotion)

        when (motion.status) {
            MotionStatus.SUBMITTED -> renderReviewSection(panel, motion, canManage, onChanged)
            MotionStatus.REVIEWED, MotionStatus.POSTPONED ->
                renderScheduleSection(panel, motion, parent, canManage, onChanged)
            MotionStatus.SCHEDULED ->
                renderResolutionSection(panel, motion, pendingAmendments, canManage, activeVote, onSelectMotion, onChanged)
            MotionStatus.RESOLVED, MotionStatus.REJECTED, MotionStatus.REJECTED_PRELIMINARY, MotionStatus.WITHDRAWN ->
                renderOutcomeSummary(panel, motion)
        }

        if (activeVote != null) {
            val isEligibleToBallot = eligibleMembers.any { it.id == currentMemberId }
            renderVoteSection(panel, activeVote, canManage, currentMemberId, isEligibleToBallot, onChanged)
        }
    }
}

private fun renderMotionMeta(
    panel: SimplePanel,
    motion: MotionDto,
    canManage: Boolean,
    isSubmitter: Boolean,
    onChanged: () -> Unit,
) {
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val title = if (motion.amendsMotionId != null) "Änderungsantrag: ${motion.title}" else motion.title
    headerRow.h2(title) { addCssClasses("h4 flex-grow-1") }
    if (motion.amendsMotionId != null) headerRow.typeBadge("Änderungsantrag", "secondary")
    headerRow.statusBadge(motionStatusLabel(motion.status), motionStatusColor(motion.status))

    panel.div(
        "Gremium: ${motion.targetCommitteeName} · eingereicht von ${motion.submitterDisplayName} am ${motion.submittedAt}",
    ) { addCssClasses("text-muted small") }
    if (motion.rationale.isNotBlank()) panel.p(motion.rationale) { addCssClass("mb-0") }
    panel.p(motion.effectiveText) { addCssClass("mb-0") }
    motion.reviewedByDisplayName?.let { reviewer ->
        panel.div("Geprüft von $reviewer am ${motion.reviewedAt}${motion.reviewNote?.let { " -- $it" } ?: ""}") {
            addCssClasses("text-muted small")
        }
    }

    renderWithdrawAction(panel, motion, canManage, isSubmitter, onChanged)
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

    val withdrawButton = panel.button("Zurückziehen", style = ButtonStyle.OUTLINEDANGER)
    withdrawButton.onClick {
        confirmDialog(
            title = "Antrag zurückziehen",
            message = "\"${motion.title}\" wirklich zurückziehen?",
            confirmLabel = "Zurückziehen",
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().withdrawMotion(motion.id) }
                if (result != null) {
                    notifyInfo("Antrag wurde zurückgezogen.")
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
    callout.div("Änderungsantrag zu: ${parent.title}") { addCssClass("fw-bold") }
    val link = callout.button("Zum Hauptantrag", style = ButtonStyle.OUTLINESECONDARY)
    link.onClick { onSelectMotion(parent.id) }
}

/** Design decision D1: a main Motion's detail view always shows this section, even when empty,
 * so its presence is predictable rather than conditionally vanishing. */
private fun renderAmendmentsSection(
    panel: SimplePanel,
    amendments: List<MotionDto>,
    onSelectMotion: (String) -> Unit,
) {
    panel.h2("Änderungsanträge zu diesem Antrag") { addCssClass("h5") }
    if (amendments.isEmpty()) {
        panel.p("Keine Änderungsanträge.")
        return
    }
    amendments.forEach { amendment ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(amendment.title) { addCssClasses("flex-grow-1") }
        row.statusBadge(motionStatusLabel(amendment.status), motionStatusColor(amendment.status))
        val showButton = row.button("Anzeigen", style = ButtonStyle.OUTLINESECONDARY)
        showButton.onClick { onSelectMotion(amendment.id) }
    }
}

private fun renderReviewSection(
    panel: SimplePanel,
    motion: MotionDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    if (!canManage) return
    panel.h2("Prüfung") { addCssClass("h5") }
    val noteInput = panel.text(label = "Notiz (optional)")
    val actionRow = panel.hPanel(spacing = 8)
    val acceptButton = actionRow.button("Annehmen", style = ButtonStyle.SUCCESS)
    val rejectButton = actionRow.button("Vorläufig ablehnen", style = ButtonStyle.OUTLINEDANGER)

    fun review(decision: MotionReviewDecision) {
        acceptButton.disabled = true
        rejectButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().reviewMotion(
                        motion.id,
                        decision,
                        noteInput.value?.trim()?.takeIf { it.isNotBlank() },
                    )
                }
            acceptButton.disabled = false
            rejectButton.disabled = false
            if (result != null) {
                val message =
                    if (decision == MotionReviewDecision.ACCEPT) "Antrag angenommen zur Terminierung." else "Antrag vorläufig abgelehnt."
                notifySuccess(message)
                onChanged()
            }
        }
    }
    acceptButton.onClick { review(MotionReviewDecision.ACCEPT) }
    rejectButton.onClick { review(MotionReviewDecision.REJECT) }
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
    panel.h2("Terminierung") { addCssClass("h5") }

    if (motion.amendsMotionId != null) {
        val parentMeetingId = parent?.meetingId
        if (parentMeetingId == null) {
            panel.p("Der Hauptantrag muss zuerst selbst terminiert werden.")
            return
        }
        panel.div("Wird automatisch auf dieselbe Sitzung terminiert wie \"${parent.title}\".") {
            addCssClasses("text-muted small")
        }
        val scheduleButton = panel.button("Jetzt terminieren", style = ButtonStyle.PRIMARY)
        scheduleButton.onClick {
            scheduleButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().scheduleMotion(motion.id, parentMeetingId, 0) }
                scheduleButton.disabled = false
                if (result != null) {
                    notifySuccess("Änderungsantrag terminiert.")
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
            panel.p("Keine geplante Sitzung für dieses Gremium verfügbar -- zuerst auf \"Sitzungen\" eine anlegen.")
            return@launch
        }
        renderScheduleForm(panel, motion, meetings, onChanged)
    }
}

private fun renderScheduleForm(
    panel: SimplePanel,
    motion: MotionDto,
    meetings: List<MeetingDto>,
    onChanged: () -> Unit,
) {
    val meetingOptions = meetings.map { it.id to "${it.title} (${it.scheduledAt})" }
    val meetingSelect = panel.select(options = meetingOptions, value = meetings.firstOrNull()?.id, label = "Sitzung")
    val positionInput = panel.text(value = "1", label = "Position auf der Tagesordnung")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val scheduleButton = panel.button("Terminieren", style = ButtonStyle.PRIMARY)
    scheduleButton.onClick {
        errorBox.hide()
        val meetingId = meetingSelect.value
        val position =
            positionInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        if (meetingId == null || position == null) {
            errorBox.content = "Bitte eine Sitzung und eine gültige Position angeben."
            errorBox.show()
            return@onClick
        }
        scheduleButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IGovernanceService>().scheduleMotion(motion.id, meetingId, position) }
            scheduleButton.disabled = false
            if (result != null) {
                notifySuccess("Antrag \"${motion.title}\" terminiert.")
                onChanged()
            }
        }
    }
}

/**
 * Design decision D2: while [pendingAmendments] is non-empty, both resolution paths render
 * disabled with an explanatory `alert-warning` box (Norman: explain, don't hide, why an action is
 * unavailable) listing every blocking amendment as a link. Once clear, both paths are offered --
 * unless [activeVote] is already OPEN, in which case the Committee-Quorum path and a second
 * `openVote` are both withheld with a plain explanation, to avoid the two parallel resolution paths
 * racing to finalize the same Motion twice.
 */
private fun renderResolutionSection(
    panel: SimplePanel,
    motion: MotionDto,
    pendingAmendments: List<MotionDto>,
    canManage: Boolean,
    activeVote: VoteDto?,
    onSelectMotion: (String) -> Unit,
    onChanged: () -> Unit,
) {
    if (!canManage) return
    panel.h2("Entscheidung") { addCssClass("h5") }

    if (pendingAmendments.isNotEmpty()) {
        val count = pendingAmendments.size
        val warningBox = panel.vPanel(spacing = 4) { addCssClasses("alert alert-warning") }
        val noun = if (count == 1) "1 offenen Änderungsantrag" else "$count offene Änderungsanträge"
        warningBox.div("Dieser Antrag hat $noun, der/die zuerst entschieden werden muss/müssen:") { addCssClass("fw-bold") }
        pendingAmendments.forEach { amendment ->
            val row = warningBox.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            row.div(amendment.title) { addCssClasses("flex-grow-1") }
            row.statusBadge(motionStatusLabel(amendment.status), motionStatusColor(amendment.status))
            val link = row.button("Anzeigen", style = ButtonStyle.OUTLINESECONDARY)
            link.onClick { onSelectMotion(amendment.id) }
        }
        val disabledRow = panel.hPanel(spacing = 8)
        disabledRow.button("Committee-Quorum entscheiden", style = ButtonStyle.PRIMARY).apply {
            disabled = true
            title = "Zuerst alle Änderungsanträge entscheiden"
        }
        disabledRow.button("Meritokratische Vote eröffnen", style = ButtonStyle.OUTLINEPRIMARY).apply {
            disabled = true
            title = "Zuerst alle Änderungsanträge entscheiden"
        }
        return
    }

    if (activeVote != null && activeVote.status == VoteStatus.OPEN) {
        panel.p("Es läuft bereits eine meritokratische Vote für diesen Antrag -- siehe Abschnitt \"Vote\" unten.")
        return
    }

    renderCommitteeQuorumResolutionForm(panel, motion, onChanged)
    renderOpenVoteForm(panel, motion, onChanged)
}

private fun renderCommitteeQuorumResolutionForm(
    panel: SimplePanel,
    motion: MotionDto,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    formPanel.p("Committee-Quorum entscheiden") { addCssClass("fw-bold") }
    val votesYesInput = formPanel.text(value = "0", label = "Ja-Stimmen")
    val votesNoInput = formPanel.text(value = "0", label = "Nein-Stimmen")
    val votesAbstainInput = formPanel.text(value = "0", label = "Enthaltungen")
    val statusOptions = ResolutionStatus.entries.map { it.name to resolutionStatusLabel(it) }
    val statusSelect = formPanel.select(options = statusOptions, value = ResolutionStatus.ADOPTED.name, label = "Status")
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val resolveButton = formPanel.button("Entscheidung speichern", style = ButtonStyle.PRIMARY)
    resolveButton.onClick {
        errorBox.hide()
        val votesYes =
            votesYesInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val votesNo =
            votesNoInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val votesAbstain =
            votesAbstainInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val statusValue = statusSelect.value

        if (
            votesYes == null ||
            votesNo == null ||
            votesAbstain == null ||
            votesYes < 0 ||
            votesNo < 0 ||
            votesAbstain < 0 ||
            statusValue == null
        ) {
            errorBox.content = "Bitte nicht-negative Stimmzahlen und einen Status angeben."
            errorBox.show()
            return@onClick
        }

        resolveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().resolveMotion(
                        motion.id,
                        MotionResolutionInput(
                            votesYes = votesYes,
                            votesNo = votesNo,
                            votesAbstain = votesAbstain,
                            status = ResolutionStatus.valueOf(statusValue),
                        ),
                    )
                }
            resolveButton.disabled = false
            if (result != null) {
                notifySuccess("Entscheidung für \"${motion.title}\" gespeichert.")
                onChanged()
            }
        }
    }
}

private fun renderOpenVoteForm(
    panel: SimplePanel,
    motion: MotionDto,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    formPanel.p("Meritokratische Vote eröffnen") { addCssClass("fw-bold") }
    val labelsInput = formPanel.text(value = "YES,NO", label = "Optionen (kommagetrennt, mind. 2)")
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val openButton = formPanel.button("Vote eröffnen", style = ButtonStyle.OUTLINEPRIMARY)
    openButton.onClick {
        errorBox.hide()
        val labels =
            labelsInput.value
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        if (labels.size < 2) {
            errorBox.content = "Bitte mindestens 2 unterschiedliche Optionen angeben."
            errorBox.show()
            return@onClick
        }
        openButton.disabled = true
        AppScope.launch {
            val result =
                guarded { rpcService<IGovernanceService>().openVote(VoteOpenInput(motionId = motion.id, optionLabels = labels)) }
            openButton.disabled = false
            if (result != null) {
                notifySuccess("Vote eröffnet.")
                onChanged()
            }
        }
    }
}

private fun renderOutcomeSummary(
    panel: SimplePanel,
    motion: MotionDto,
) {
    panel.h2("Ergebnis") { addCssClass("h5") }
    if (motion.resolutionId == null) {
        panel.p("Kein Beschluss verknüpft.")
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
            panel.p("Beschluss konnte nicht geladen werden.")
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
private fun renderVoteSection(
    panel: SimplePanel,
    vote: VoteDto,
    canManage: Boolean,
    currentMemberId: String,
    isEligibleToBallot: Boolean,
    onChanged: () -> Unit,
) {
    panel.h2("Vote") { addCssClass("h5") }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(vote.title) { addCssClasses("flex-grow-1") }
    headerRow.statusBadge(voteStatusLabel(vote.status), voteStatusColor(vote.status))

    AppScope.launch {
        val ballots = guarded { rpcService<IGovernanceService>().listVoteBallots(vote.id) } ?: emptyList()

        if (vote.status == VoteStatus.OPEN) {
            panel.div("${ballots.size} Stimmen abgegeben.") { addCssClasses("text-muted small") }
            val myBallot = ballots.find { it.memberId == currentMemberId }
            if (myBallot != null) {
                val optionLabel = vote.options.find { it.id == myBallot.optionId }?.label ?: myBallot.optionId
                panel.div("Ihr Gebot: $optionLabel, ${myBallot.stakeLtr} LTR") { addCssClasses("text-muted small") }
            }
            if (canManage) {
                panel.div("Teilnehmende: ${ballots.joinToString(", ") { it.memberDisplayName }.ifBlank { "keine" }}") {
                    addCssClasses("text-muted small")
                }
            }
            if (isEligibleToBallot) {
                renderBallotForm(panel, vote, myBallot?.optionId, onChanged)
            }
            if (canManage) {
                renderVoteControls(panel, vote, onChanged)
            }
        } else {
            vote.options.forEach { option ->
                val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                row.div(option.label) { addCssClasses("flex-grow-1") }
                if (option.id == vote.winnerOptionId) row.statusBadge("Gewinner", "success")
                row.div("${option.basketTotalLtr} LTR") { addCssClasses("text-muted small") }
            }
            if (vote.winnerOptionId == null) {
                panel.p(
                    "Unentschieden -- die höchsten beiden Gebotskörbe waren gleich hoch. Kein Gewinner, keine " +
                        "Belastung; der Antrag wird zurückgestellt (POSTPONED).",
                )
            } else {
                vote.secondPriceLtr?.let { price ->
                    panel.div("Preis: $price LTR (Vickrey-Zweitpreis)") { addCssClasses("text-muted small") }
                }
            }
            panel.h2("Alle Gebote") { addCssClass("h6") }
            if (ballots.isEmpty()) {
                panel.p("Keine Gebote abgegeben.")
            } else {
                ballots.forEach { ballot ->
                    val optionLabel = vote.options.find { it.id == ballot.optionId }?.label ?: ballot.optionId
                    val settledSuffix = ballot.settledLtr?.let { " · belastet: $it LTR" } ?: ""
                    panel.div("${ballot.memberDisplayName}: $optionLabel, ${ballot.stakeLtr} LTR$settledSuffix") {
                        addCssClasses("text-muted small")
                    }
                }
            }
        }
    }
}

private fun renderBallotForm(
    panel: SimplePanel,
    vote: VoteDto,
    currentOptionId: String?,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    formPanel.p("Gebot abgeben") { addCssClass("fw-bold") }
    val optionOptions = vote.options.sortedBy { it.position }.map { it.id to it.label }
    val optionSelect =
        formPanel.select(options = optionOptions, value = currentOptionId ?: optionOptions.firstOrNull()?.first, label = "Option")
    val stakeInput = formPanel.text(label = "Einsatz (LTR)")
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val castButton = formPanel.button("Gebot abgeben", style = ButtonStyle.PRIMARY)
    castButton.onClick {
        errorBox.hide()
        val optionId = optionSelect.value
        val stakeText = stakeInput.value.orEmpty().trim()
        if (optionId == null || !Validation.isPositiveDecimal(stakeText)) {
            errorBox.content = "Bitte eine Option und einen positiven LTR-Einsatz angeben."
            errorBox.show()
            return@onClick
        }
        castButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IGovernanceService>().castVoteBallot(
                        VoteBallotInput(voteId = vote.id, optionId = optionId, stakeLtr = stakeText.toDouble().toDecimal()),
                    )
                }
            castButton.disabled = false
            if (result != null) {
                notifySuccess("Gebot gespeichert.")
                onChanged()
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
    val closeButton = actionRow.button("Vote schließen", style = ButtonStyle.SUCCESS)
    closeButton.onClick {
        AppScope.launch {
            val result = guarded { rpcService<IGovernanceService>().closeVote(vote.id) }
            if (result != null) {
                notifySuccess("Vote geschlossen.")
                onChanged()
            }
        }
    }
    val abortButton = actionRow.button("Vote abbrechen", style = ButtonStyle.OUTLINEDANGER)
    abortButton.onClick {
        confirmDialog(
            title = "Vote abbrechen",
            message = "\"${vote.title}\" wirklich abbrechen? Es wird keine Abrechnung durchgeführt.",
            confirmLabel = "Abbrechen",
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().abortVote(vote.id) }
                if (result != null) {
                    notifyInfo("Vote abgebrochen.")
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
private val NON_TERMINAL_MOTION_STATUSES =
    setOf(MotionStatus.SUBMITTED, MotionStatus.REVIEWED, MotionStatus.SCHEDULED, MotionStatus.POSTPONED)

/**
 * German label/badge-color tables for [MotionStatus]/[VoteStatus] -- this screen's own
 * instantiation of the [statusBadge]/[typeBadge] grammar (see `StatusBadge.kt` KDoc and the design
 * review's exact table). Non-private so [MotionsScreenTest] can cover them directly, same posture
 * as `MeetingsScreen.kt`'s own label/color functions.
 */
fun motionStatusLabel(status: MotionStatus): String =
    when (status) {
        MotionStatus.SUBMITTED -> "Eingereicht"
        MotionStatus.REVIEWED -> "Geprüft"
        MotionStatus.REJECTED_PRELIMINARY -> "Vorläufig abgelehnt"
        MotionStatus.SCHEDULED -> "Terminiert"
        MotionStatus.RESOLVED -> "Angenommen"
        MotionStatus.REJECTED -> "Abgelehnt"
        MotionStatus.POSTPONED -> "Zurückgestellt"
        MotionStatus.WITHDRAWN -> "Zurückgezogen"
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
        VoteStatus.OPEN -> "Läuft"
        VoteStatus.CLOSED -> "Geschlossen"
        VoteStatus.ABORTED -> "Abgebrochen"
    }

fun voteStatusColor(status: VoteStatus): String =
    when (status) {
        VoteStatus.OPEN -> "primary"
        VoteStatus.CLOSED -> "secondary"
        VoteStatus.ABORTED -> "dark"
    }
