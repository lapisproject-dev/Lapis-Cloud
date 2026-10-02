package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionBallotDto
import network.lapis.cloud.shared.domain.ElectionBoardMemberDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionParticipationDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService

/**
 * V1.9.22 "Wahlen" -- the web client of the democratic elections (`IElectionService`, V0.2.4): one person, one vote, yes/no
 * questions and the election of people, with an election board, a candidate list, a secret ballot with a receipt and a four-eyes
 * release of the count. Until now the server could do all of this, but no screen could reach it.
 *
 * Two views on one route family: `/elections` is the list, `/elections/:id` the detail of one election. An election is never
 * created here -- it is opened from a scheduled motion (`MotionsScreen`, "Wahl eröffnen"), exactly like a meritocratic vote.
 *
 * What the screen never does (enforced by `ElectionSecrecyTripwireTest`): show a server message (Kilua RPC transmits only the
 * exception type, and the server's text can carry member UUIDs), write a receipt code or a ballot selection anywhere but the one
 * DOM node that shows it, or log anything about a ballot.
 */
internal data class ElectionUiContext(
    val currentMemberId: String,
    val isBoardOrAdmin: Boolean,
)

fun renderElectionsScreen(
    container: SimplePanel,
    initialElectionId: String? = null,
) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val ctx = ElectionUiContext(session.memberId, AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN))
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Wahlen"))

    if (initialElectionId != null) {
        val backButton = root.button(tr("Zur Wahlübersicht"), style = ButtonStyle.OUTLINESECONDARY)
        backButton.onClick { navigateTo(Routes.ELECTIONS) }
        // While a ballot receipt is on screen it cannot be shown again, so the in-app way out is closed until "Fertig".
        electionReceiptVisibilityHook = { showing -> backButton.visible = !showing }
        val detailPanel = root.vPanel(spacing = 10)
        renderElectionDetail(detailPanel, initialElectionId, ctx)
        return
    }

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusFilterSelect =
        filterRow.select(
            options = listOf("" to tr("Alle Status")) + ElectionStatus.entries.map { it.name to electionStatusLabel(it) },
            value = "",
            label = tr("Status"),
        )
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val section =
        root.dataSection<List<ElectionDto>>(
            emptyText = gettext("Noch keine Wahlen vorhanden. Eine Wahl wird aus einem terminierten Antrag heraus eröffnet."),
            isEmpty = { it.isEmpty() },
            load = {
                val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { ElectionStatus.valueOf(it) }
                guarded { rpcService<IElectionService>().listElections(null, status) }
            },
            render = { panel, elections -> renderElectionTable(panel, elections.sortedByDescending { it.openedAt }) },
        )
    refreshButton.onClick { section.reload() }
    root.button(tr("Zu den Anträgen"), style = ButtonStyle.OUTLINESECONDARY).onClick { navigateTo(Routes.MOTIONS) }
    section.reload()
}

private fun renderElectionTable(
    panel: SimplePanel,
    elections: List<ElectionDto>,
) {
    panel.dataTable(
        columns =
            listOf(
                textColumn<ElectionDto>(title = tr("Titel"), primary = true, cssClasses = "fw-bold") { it.title },
                textColumn(title = tr("Art")) { electionTypeLabel(it.electionType) },
                textColumn(title = tr("Geheim")) { if (it.secret) gettext("Ja") else gettext("Nein") },
                DataColumn(
                    title = tr("Status"),
                    cell = { container, e -> container.statusBadge(electionStatusLabel(e.status), electionStatusColor(e.status)) },
                ),
                textColumn(title = tr("Eröffnet am")) { formatSystemDateTime(it.openedAt) },
                textColumn(title = tr("Zielgremium")) { it.targetCommitteeName.orEmpty() },
            ),
        rows = elections,
        actions = { container, e ->
            container
                .tableActionButton("fas fa-eye", gettext("Wahl öffnen"))
                .onClick { navigateTo("/elections/${e.id}") }
        },
    )
}

/** Everything one detail view shows, loaded together so the screen is built from one consistent snapshot. */
internal class ElectionDetailData(
    val election: ElectionDto,
    val participation: ElectionParticipationDto,
    val board: List<ElectionBoardMemberDto>,
    val candidacies: List<CandidacyDto>,
    val motion: MotionDto,
    val roster: List<CommitteeMembershipDto>,
    val committees: List<CommitteeDto>,
    val targetRoster: List<CommitteeMembershipDto>,
    val ballots: List<ElectionBallotDto>,
    val result: ElectionResultDto?,
)

private suspend fun loadElectionDetail(electionId: String): ElectionDetailData? {
    val elections = rpcService<IElectionService>()
    val governance = rpcService<IGovernanceService>()
    val election = guarded { elections.getElection(electionId) } ?: return null
    val participation = guarded { elections.getElectionParticipation(electionId) } ?: return null
    val board = guarded { elections.listElectionBoard(electionId) } ?: emptyList()
    val candidacies =
        if (isPersonnelElection(election.electionType)) {
            guarded {
                elections.listCandidacies(electionId)
            } ?: emptyList()
        } else {
            emptyList()
        }
    val motion = guarded { governance.getMotion(election.motionId) } ?: return null
    val roster = guarded { governance.listCommitteeMembers(motion.targetCommitteeId, activeOnly = true) } ?: emptyList()
    val committees = guarded { governance.listCommittees(activeOnly = false) } ?: emptyList()
    val targetIsBoard = committees.firstOrNull { it.id == election.targetCommitteeId }?.type == CommitteeType.EXECUTIVE_BOARD
    val targetRoster =
        if (targetIsBoard && election.targetCommitteeId != null) {
            guarded { governance.listCommitteeMembers(election.targetCommitteeId!!, activeOnly = true) } ?: emptyList()
        } else {
            emptyList()
        }
    val ballots =
        if (election.status == ElectionStatus.TALLIED) guarded { elections.listElectionBallots(electionId) } ?: emptyList() else emptyList()
    val result = if (election.status == ElectionStatus.TALLIED) guarded { elections.getElectionResult(electionId) } else null
    return ElectionDetailData(
        election = election,
        participation = participation,
        board = board,
        candidacies = candidacies,
        motion = motion,
        roster = roster,
        committees = committees,
        targetRoster = targetRoster,
        ballots = ballots,
        result = result,
    )
}

/**
 * Loads and renders the detail of [electionId] into [panel]. Every successful write reloads the whole detail (one consistent
 * snapshot instead of patching pieces) and then calls [onChanged].
 */
internal fun renderElectionDetail(
    panel: SimplePanel,
    electionId: String,
    ctx: ElectionUiContext,
    onChanged: () -> Unit = {},
) {
    panel.removeAll()
    panel.p(tr("Wird geladen …"))
    AppScope.launch {
        val data = loadElectionDetail(electionId)
        panel.removeAll()
        if (data == null) {
            panel.p(tr("Die Wahl konnte nicht geladen werden."))
            return@launch
        }
        val reload: () -> Unit = {
            onChanged()
            renderElectionDetail(panel, electionId, ctx, onChanged)
        }
        val roles = electionRoles(ctx.isBoardOrAdmin, ctx.currentMemberId, data.motion, data.roster, data.participation)
        renderElectionHeader(panel, data)
        renderElectionActions(panel, data, roles, reload)
        renderElectionBoardSection(panel, data, roles, reload)
        if (isPersonnelElection(data.election.electionType)) renderCandidacySection(panel, data, roles, ctx.currentMemberId, reload)
        renderTallyProgress(panel, data)
        renderElectionResultSection(panel, data)
        renderBallotsSection(panel, data)
        renderReceiptVerification(panel, data.election)
    }
}

private fun renderElectionHeader(
    panel: SimplePanel,
    data: ElectionDetailData,
) {
    val e = data.election
    val p = data.participation
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.untrustedHeading(e.title, level = 2, className = "h5 flex-grow-1")
    headerRow.statusBadge(electionStatusLabel(e.status), electionStatusColor(e.status))
    headerRow.typeBadge(electionTypeLabel(e.electionType), "secondary")
    headerRow.typeBadge(if (e.secret) gettext("Geheim") else gettext("Offen"), if (e.secret) "primary" else "warning")

    panel.div(tr("Eine Person, eine Stimme: Jedes wahlberechtigte Mitglied hat genau eine Stimme.")) {
        addCssClasses("alert alert-light border mb-0")
    }
    panel.electionPhaseBar(e)

    panel.div(
        gettext("Eröffnet von %1 am %2", e.openedByDisplayName, formatSystemDateTime(e.openedAt)),
    ) { addCssClasses("text-muted small") }
    if (isPersonnelElection(e.electionType)) {
        panel.div(
            sanitizeUntrustedI18nText(
                gettext(
                    "Zielgremium: %1, Rolle: %2",
                    e.targetCommitteeName.orEmpty(),
                    committeeRoleLabel(e.targetRole ?: CommitteeRole.MEMBER),
                ),
            ),
        ) { addCssClasses("text-muted small") }
        panel.div(gettext("Zu besetzende Sitze: %1", e.seatCount)) { addCssClasses("text-muted small") }
    }
    if (e.electionType != ElectionType.MULTI_CHOICE) {
        panel.div(gettext("Erforderliche Mehrheit: %1", majorityLabel(e))) { addCssClasses("text-muted small") }
    }
    panel.div(gettext("Erforderliche Freigaben der Auszählung: %1", e.tallyThreshold)) { addCssClasses("text-muted small") }
    val eligibleCount = p.eligibleCount
    if (eligibleCount != null) {
        panel.div(gettext("Beteiligung: %1 von %2 Wahlberechtigten", p.ballotCount, eligibleCount)) { addCssClasses("fw-bold") }
    }
    val toMotion = panel.button(tr("Zum Antrag"), style = ButtonStyle.OUTLINESECONDARY)
    toMotion.onClick { navigateTo("/motions/${e.motionId}") }
}

/** A button for [gate]: hidden, enabled, or disabled with its reason as visible text (not only a tooltip). */
private fun Container.gatedButton(
    label: String,
    style: ButtonStyle,
    gate: Gate,
    onClick: (Button) -> Unit,
) {
    if (gate is Gate.Hidden) return
    val box = vPanel(spacing = 2)
    val button = box.button(label, style = style)
    if (gate is Gate.Disabled) {
        button.disabled = true
        val reason = gate.reason
        box.div(reason) { addCssClasses("text-muted small") }
    } else {
        button.onClick { onClick(button) }
    }
}

private fun renderElectionActions(
    panel: SimplePanel,
    data: ElectionDetailData,
    roles: ElectionRoles,
    reload: () -> Unit,
) {
    val e = data.election
    val p = data.participation
    val openGate = canOpenVoting(e, p, roles)
    val releaseGate = canReleaseCandidateList(e, data.candidacies, roles)
    val tallyGate = canTally(e, p, roles)
    val canBooth = canEnterBooth(e, p)
    val canClose = canCloseVoting(e, roles)
    val canApprove = canApproveTally(e, p, roles)
    val canAbortNow = canAbort(e, roles)
    val note = electionParticipationNote(e, p)
    val nothing =
        openGate is Gate.Hidden &&
            releaseGate is Gate.Hidden &&
            tallyGate is Gate.Hidden &&
            !canBooth &&
            !canClose &&
            !canApprove &&
            !canAbortNow &&
            note == null &&
            !(e.status == ElectionStatus.CLOSED && p.hasApprovedTally)
    if (nothing) return

    panel.h2(tr("Aktionen")) { addCssClass("h5") }
    if (note != null) panel.p(note) { addCssClasses("alert alert-info mb-0") }
    if (e.status == ElectionStatus.CLOSED && p.hasApprovedTally) {
        panel.p(tr("Sie haben die Auszählung freigegeben.")) { addCssClasses("text-muted mb-0") }
    }
    // Full width on a phone, natural button width from 576 px up.
    val row = panel.vPanel(spacing = 8) { addCssClass("align-items-sm-start") }

    if (canBooth) {
        row.button(tr("Zur Stimmabgabe"), style = ButtonStyle.PRIMARY).onClick {
            // Leaving the booth, with or without a ballot, reloads the detail: the participation state is what the next screen shows.
            renderElectionBooth(panel, e) { reload() }
        }
    }
    row.gatedButton(tr("Abstimmung öffnen"), ButtonStyle.PRIMARY, openGate) { button ->
        runGuardedAction(button) {
            val result =
                electionGuarded(gettext("Die Abstimmung konnte nicht geöffnet werden. Bitte Ansicht aktualisieren."), onConflict = reload) {
                    rpcService<IElectionService>().openVoting(e.id)
                }
            if (result != null) {
                notifySuccess(tr("Abstimmung geöffnet."))
                reload()
            }
        }
    }
    if (canClose) {
        val closeButton = row.button(tr("Abstimmung beenden"), style = ButtonStyle.WARNING)
        closeButton.onClick {
            confirmDialog(
                title = tr("Abstimmung beenden"),
                message = tr("Nach dem Beenden können keine Stimmen mehr abgegeben werden. Danach folgt die Freigabe der Auszählung."),
                confirmLabel = tr("Abstimmung beenden"),
                confirmStyle = ButtonStyle.PRIMARY,
            ) {
                runGuardedAction(closeButton) {
                    val result =
                        electionGuarded(
                            gettext("Die Abstimmung konnte nicht beendet werden. Bitte Ansicht aktualisieren."),
                            onConflict = reload,
                        ) {
                            rpcService<IElectionService>().closeVoting(e.id)
                        }
                    if (result != null) {
                        notifySuccess(tr("Abstimmung beendet."))
                        reload()
                    }
                }
            }
        }
    }
    if (canApprove) {
        val approve = row.button(tr("Auszählung freigeben"), style = ButtonStyle.PRIMARY)
        approve.onClick {
            runGuardedAction(approve) {
                val result =
                    electionGuarded(gettext("Die Freigabe war nicht möglich. Bitte Ansicht aktualisieren."), onConflict = reload) {
                        rpcService<IElectionService>().approveTally(e.id)
                    }
                if (result != null) {
                    notifySuccess(tr("Auszählung freigegeben."))
                    reload()
                }
            }
        }
    }
    row.gatedButton(tr("Auszählen"), ButtonStyle.SUCCESS, tallyGate) { button ->
        runGuardedAction(button) {
            val result =
                electionGuarded(gettext("Die Auszählung war nicht möglich. Bitte Ansicht aktualisieren."), onConflict = reload) {
                    rpcService<IElectionService>().tally(e.id)
                }
            if (result != null) {
                notifySuccess(tr("Wahl ausgezählt."))
                reload()
            }
        }
    }
    row.gatedButton(tr("Kandidatenliste freigeben"), ButtonStyle.PRIMARY, releaseGate) { button ->
        val count = data.candidacies.count { it.withdrawnAt == null }
        val message =
            if (count == 1) {
                gettext("Die Kandidatenliste mit 1 Kandidatur wird freigegeben. Danach können keine Kandidaturen mehr eingereicht werden.")
            } else {
                gettext(
                    "Die Kandidatenliste mit %1 Kandidaturen wird freigegeben. Danach können keine Kandidaturen mehr eingereicht werden.",
                    count,
                )
            }
        confirmDialog(
            title = tr("Kandidatenliste freigeben"),
            message = message,
            confirmLabel = tr("Freigeben"),
            confirmStyle = ButtonStyle.PRIMARY,
        ) {
            runGuardedAction(button) {
                val result =
                    electionGuarded(
                        gettext("Die Kandidatenliste konnte nicht freigegeben werden. Bitte Ansicht aktualisieren."),
                        onConflict = reload,
                    ) {
                        rpcService<IElectionService>().releaseCandidateList(e.id)
                    }
                if (result != null) {
                    notifySuccess(tr("Kandidatenliste freigegeben."))
                    reload()
                }
            }
        }
    }
    if (canAbortNow) renderAbortAction(row, e, reload)
}

/** The in-place explanation for a member who cannot (or no longer can) vote. */
private fun electionParticipationNote(
    e: ElectionDto,
    p: ElectionParticipationDto,
): String? =
    when {
        e.status != ElectionStatus.OPEN -> null
        p.hasVoted -> gettext("Sie haben bereits abgestimmt.")
        p.eligible == false -> gettext("Sie stehen nicht im Wählerverzeichnis dieser Wahl und können nicht abstimmen.")
        else -> null
    }

private fun renderAbortAction(
    row: Container,
    e: ElectionDto,
    reload: () -> Unit,
) {
    val abort = row.button(tr("Wahl abbrechen"), style = ButtonStyle.OUTLINEDANGER)

    fun doAbort() {
        runGuardedAction(abort) {
            val result =
                electionGuarded(gettext("Die Wahl konnte nicht abgebrochen werden. Bitte Ansicht aktualisieren."), onConflict = reload) {
                    rpcService<IElectionService>().abortElection(e.id)
                }
            if (result != null) {
                notifyInfo(tr("Wahl abgebrochen."))
                reload()
            }
        }
    }
    abort.onClick {
        if (e.status == ElectionStatus.OPEN || e.status == ElectionStatus.CLOSED) {
            confirmWithTypedConfirmationDialog(
                title = tr("Wahl abbrechen"),
                message = tr("Eine laufende Wahl wird endgültig abgebrochen. Alle bereits abgegebenen Stimmen verfallen."),
                expectedText = e.title,
                confirmLabel = tr("Wahl abbrechen"),
            ) { doAbort() }
        } else {
            confirmDialog(
                title = tr("Wahl abbrechen"),
                message = tr("Die Wahl wird abgebrochen. Für diesen Antrag kann danach eine neue Wahl eröffnet werden."),
                confirmLabel = tr("Wahl abbrechen"),
            ) { doAbort() }
        }
    }
}

/** The elections of one motion, for the motion's resolution section. Lives here so every election read sits next to the election state blocks. */
internal suspend fun loadMotionElections(motionId: String): List<ElectionDto> =
    guarded { rpcService<IElectionService>().listElections(motionId = motionId) } ?: emptyList()

/** What a failed ballot leaves to find out: whether the member has voted and whether the election is still open. `null` means unknown. */
internal class ElectionProbe(
    val participation: ElectionParticipationDto?,
    val election: ElectionDto?,
)

/** Reads the state silently (no toast, no log): a failed read is simply unknown, never an error to show. */
internal suspend fun probeElectionState(electionId: String): ElectionProbe {
    val participation = probeQuietly { rpcService<IElectionService>().getElectionParticipation(electionId) }
    val election = probeQuietly { rpcService<IElectionService>().getElection(electionId) }
    return ElectionProbe(participation, election)
}

private suspend fun <T> probeQuietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
