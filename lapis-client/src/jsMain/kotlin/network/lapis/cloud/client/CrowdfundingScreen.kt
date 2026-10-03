package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.textArea
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
import network.lapis.cloud.shared.domain.CrowdfundingDistributionDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.rpc.ICrowdfundingService

/**
 * LTR-Wirtschaft UI wave, screen 2 of 5 -- "Crowdfunding". Self-contained domain
 * ([ICrowdfundingService]) covering project submission, the board approve/reject decision, member
 * Like/Dislike donations, and the treasury monthly-distribution calculation. See
 * `17-crowdfunding.kuml.kts` file header for the full fachlich model this screen surfaces
 * (Sichtbarkeits-Gewicht vs. Verteilungs-Korb, silence-is-approval, submission-gate locking).
 *
 * **Role gating** (verified against `CrowdfundingService.kt`'s actual `requireRole`/
 * `requireActiveMembership` call sites, not guessed from method names -- see `Routes.CROWDFUNDING`
 * KDoc for the route-level `requireAuth` reasoning):
 * - [ICrowdfundingService.submitProject] -- MEMBER+, additionally must be ACTIVE
 *   (`requireActiveMembership` INSIDE the server transaction, not reachable as an `AccountRole`
 *   predicate -- [network.lapis.cloud.shared.domain.SessionInfoDto] carries no member-status field
 *   at all, same reasoning `LtrLedgerScreen.kt`'s own KDoc documents for `transferLtr`). A non-ACTIVE
 *   caller sees the ordinary `guarded()` ConflictException toast.
 * - [ICrowdfundingService.listProjects]/[ICrowdfundingService.getProject]/
 *   [ICrowdfundingService.getMyReaction]/[ICrowdfundingService.listDistributions] -- any
 *   authenticated member. Rendered unconditionally.
 * - [ICrowdfundingService.approveProject]/[ICrowdfundingService.rejectProject] --
 *   `current.requireRole(BOARD, ADMIN)` server-side. Gated here as `canBoard`.
 * - [ICrowdfundingService.castReaction]/[ICrowdfundingService.retractReaction] -- MEMBER+/ACTIVE
 *   (same `requireActiveMembership` gate as `submitProject`), and additionally only meaningful once
 *   `effectiveStatus == APPROVED` (server throws `ConflictException` otherwise) -- this screen
 *   therefore only RENDERS the Like/Dislike buttons on an approved project, never shows-then-fails
 *   them on a still-PENDING/REJECTED one.
 * - [ICrowdfundingService.computeMonthlyDistribution] -- `current.requireRole(TREASURER, BOARD,
 *   ADMIN)` server-side. Gated here as `canTreasury`, inside a visually separated
 *   "Treuhänder-Werkzeuge" panel (design decision D3's staged-disclosure principle, applied here
 *   even though this screen is smaller than `LtrLedgerScreen.kt` -- member-facing content first,
 *   privileged tools clearly labeled and separated below).
 *
 * **Must-fix D7 (resolved by reading `CrowdfundingService.kt` in full, not assumed)**: neither
 * [ICrowdfundingService.approveProject] nor [ICrowdfundingService.rejectProject] ever writes a
 * ledger entry -- the [network.lapis.cloud.shared.domain.LtrLedgerEntryType.PROJECT_STAKE] debit
 * booked at [ICrowdfundingService.submitProject] time is permanent, regardless of the board's
 * eventual decision.
 * [network.lapis.cloud.shared.domain.LtrLedgerEntryType.PROJECT_STAKE_RELEASE] exists in the enum
 * but is confirmed reserved-and-never-written (see that literal's own KDoc and
 * `08-ltr-balance.kuml.kts`, plus `V1__baseline.sql`'s enum literal list) -- there is no refund path
 * in this codebase at all, not even a partial one, for either outcome. This is surfaced as explicit
 * UI copy directly under the `initialWeightLtr` input on the submit form (not left to member
 * inference), and repeated in the reject confirm-dialog copy, per design decision D7.
 *
 * **Design decision D9 note (politician self-edit finding)**: not applicable to this screen -- see
 * `PoliticianScreen.kt` for that finding.
 *
 * **Confirm-dialog tier (design decision D4)**: [ICrowdfundingService.submitProject] uses the
 * plain, neutral-framed [confirmDialog] (Tier 1 "Kostenpflichtig" -- explicitly named as a Tier 1
 * example in the design review, material to the submitter's own balance, not a treasury cost).
 * [ICrowdfundingService.rejectProject] also uses [confirmDialog] (Tier 1) but with copy that
 * repeats the D7 forfeiture consequence one more time, right at the point of commitment -- the
 * board caller is deciding to permanently keep the submitter's stake forfeited, so that consequence
 * belongs in the confirmation, not just the submit-time copy. [ICrowdfundingService.approveProject]
 * gets a lighter Tier-1 confirm too (a one-way decision, but moves no LTR).
 * [ICrowdfundingService.computeMonthlyDistribution]/[ICrowdfundingService.castReaction]/
 * [ICrowdfundingService.retractReaction] get no confirm dialog: `castReaction`/`retractReaction`
 * only change the caller's own vote and can be changed again at will, and
 * `computeMonthlyDistribution` is idempotent per period and produces only an audit/decision record,
 * never a bank transfer (considered and rejected, same posture `AuctionService`'s own KDoc takes
 * for its analogous `settleAuction` call). All non-idempotent buttons still disable themselves for
 * the duration of the in-flight request (double-submit protection,
 * `LedgerScreen.postDirectButton`'s idiom).
 *
 * **Empty states (D10)**: zero projects renders "Noch keine Projekte eingereicht." instead of a
 * blank list; zero distributions renders "Noch keine Verteilung berechnet." likewise.
 *
 * Every LTR amount is rendered via [ltrSpan]/[formatLtr] (`Money.kt`, D2); every EUR amount
 * ([CrowdfundingDistributionDto.amountEur]) via [moneySpan]/[formatMoney] -- never hand-formatted,
 * and never conflated with each other, even though both units appear side by side in the
 * distribution history.
 */
fun renderCrowdfundingScreen(container: SimplePanel) {
    val canBoard = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    val canTreasury = AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    val header = root.pageHeader(tr("Crowdfunding"))

    // ---- Submit new project (V1.9.48, R36B: collapsed behind the header button; D3: the balance strip is the first
    // element of the opened form, before any input field) ------------------------------------------
    val submitHost = root.vPanel(spacing = 6)

    // ---- Project list + status filter (containers created now, populated by loadProjects()) ---
    root.h2(tr("Projekte")) { addCssClass("h5") }
    val statusFilterRow = root.lapisToolbar()
    val statusFilterOptions =
        listOf("" to tr("Alle (persistierter Status)")) +
            CrowdfundingProjectStatus.entries.map { it.name to crowdfundingProjectStatusLabel(it) }
    val statusFilterSelect =
        statusFilterRow.select(options = statusFilterOptions, value = "", label = tr("Filter: Status (Vorstandsbeschluss)"))
    val projectsRefreshButton = statusFilterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val projectsPanel = root.vPanel(spacing = 10)

    // ---- Treuhänder-Werkzeuge container (canTreasury only, D3 staged disclosure) --------------
    val treasuryPanel = if (canTreasury) root.vPanel(spacing = 10) { addCssClasses("border rounded p-3 mt-2") } else null

    // ---- Verteilungshistorie container (any authenticated member, read-only) -------------------
    root.h2(tr("Verteilungshistorie")) { addCssClass("h5") }
    val distributionsPanel = root.vPanel(spacing = 8)

    // ---- Loaders (declared after every panel they populate exists, before they're wired up) ---
    fun loadProjects() {
        projectsPanel.removeAll()
        projectsPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        val statusFilter = parseOptionalEnum<CrowdfundingProjectStatus>(statusFilterSelect.value)
        AppScope.launch {
            val projects = guarded { rpcService<ICrowdfundingService>().listProjects(statusFilter) } ?: return@launch
            projectsPanel.removeAll()
            if (projects.isEmpty()) {
                projectsPanel.p(tr("Noch keine Projekte eingereicht.")) { addCssClasses("text-muted small") }
                return@launch
            }
            projects.forEach { project -> renderProjectCard(projectsPanel, project, canBoard) { loadProjects() } }
        }
    }

    fun loadDistributions() {
        distributionsPanel.removeAll()
        distributionsPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val distributions = guarded { rpcService<ICrowdfundingService>().listDistributions() } ?: return@launch
            distributionsPanel.removeAll()
            if (distributions.isEmpty()) {
                distributionsPanel.p(tr("Noch keine Verteilung berechnet.")) { addCssClasses("text-muted small") }
            } else {
                renderDistributionsTable(distributionsPanel, distributions)
            }
        }
    }

    // ---- Wire up + initial loads -----------------------------------------------------------
    projectsRefreshButton.onClick { loadProjects() }
    statusFilterSelect.subscribe { loadProjects() }
    collapsibleCreateForm<Unit>(
        actionSlot = header.actionSlot,
        formHost = submitHost,
        buttonLabel = tr("Projekt einreichen"),
        formId = "lapis-create-crowdfunding-project",
        icon = ActionIcon.ADD,
    ) { _, close -> renderSubmitProjectForm(this, close) { loadProjects() } }

    if (treasuryPanel != null) {
        val distributionSlot = treasuryPanel.sectionTitleRow(tr("Treuhänder-Werkzeuge"))
        treasuryPanel.div(tr("Sichtbar für TREASURER/BOARD/ADMIN.")) { addCssClasses("text-muted small mb-2") }
        val distributionHost = treasuryPanel.vPanel(spacing = 6)
        collapsibleCreateForm<Unit>(
            actionSlot = distributionSlot,
            formHost = distributionHost,
            buttonLabel = tr("Verteilung berechnen"),
            formId = "lapis-create-crowdfunding-distribution",
        ) { _, close -> renderDistributionComputeForm(this, close) { loadDistributions() } }
    }

    loadProjects()
    loadDistributions()
}

// ================================================================================================
// Submit-project form
// ================================================================================================

// ================================================================================================
// Project list
// ================================================================================================

private fun renderProjectCard(
    panel: SimplePanel,
    project: CrowdfundingProjectDto,
    canBoard: Boolean,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    // Security audit W6b follow-up round 3 (major finding A): project title/description are submitter-controlled
    // free text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(project.title)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(
        gettext("Status: %1", crowdfundingProjectStatusLabel(project.status)),
        crowdfundingProjectStatusColor(project.status),
    )
    if (project.status != project.effectiveStatus) {
        headerRow.statusBadge(
            gettext("Effektiv: %1", crowdfundingProjectStatusLabel(project.effectiveStatus)),
            crowdfundingProjectStatusColor(project.effectiveStatus),
        )
    }

    card.div(sanitizeUntrustedI18nText(project.description)) { addCssClasses("small") }
    card.div(
        gettext("Eingereicht von %1 am %2", project.submitterDisplayName, formatSystemDateTime(project.submittedAt)),
    ) { addCssClasses("text-muted small") }

    // status (persisted board decision) vs. effectiveStatus/isAutoApproved (14-day
    // silence-is-approval) are two DISTINCT signals -- a project can permanently show
    // status=PENDING, effectiveStatus=APPROVED, isAutoApproved=true, and that is the normal,
    // expected shape, not a bug to hide.
    if (project.isAutoApproved) {
        card.div(
            tr(
                "Automatisch genehmigt durch die 14-Tage-Schweigefrist -- der Vorstand hat keinen expliziten " +
                    "Beschluss gefasst (Status bleibt dauerhaft \"Ausstehend\", effektiver Status zeigt \"Genehmigt\"). " +
                    "Das ist der normale, erwartete Zustand.",
            ),
        ) { addCssClasses("text-muted small fst-italic") }
    }

    // initialWeightLtr (immutable, submission-time) vs. currentWeightLtr (same value, decayed
    // 10%/day, computed fresh on every read) -- side by side, never conflated into one figure.
    val weightRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val initialCell = weightRow.vPanel(spacing = 2)
    initialCell.div(tr("Ursprüngliches Gewicht")) { addCssClasses("text-muted small") }
    initialCell.ltrSpan(project.initialWeightLtr)
    val currentCell = weightRow.vPanel(spacing = 2)
    currentCell.div(tr("Aktuelles Gewicht (10 %/Tag Zerfall)")) { addCssClasses("text-muted small") }
    currentCell.ltrSpan(project.currentWeightLtr)

    // basketTotal = max(0, likeCount - dislikeCount) is what computeMonthlyDistribution actually
    // splits by -- shown next to the raw Like/Dislike counts so the connection to the
    // monthly-distribution section is legible.
    card.div(gettext("Like: %1 · Dislike: %2 · Verteilungs-Korb: %3", project.likeCount, project.dislikeCount, project.basketTotal)) {
        addCssClasses("small")
    }

    if (project.status == CrowdfundingProjectStatus.REJECTED) {
        card.div(gettext("Ablehnungsgrund: %1", project.rejectionReason.orEmpty())) { addCssClasses("text-danger small") }
    }
    project.reviewedByDisplayName?.let { reviewer ->
        card.div(
            gettext("Entschieden von %1 am %2", reviewer, formatSystemDateTime(project.reviewedAt!!)),
        ) { addCssClasses("text-muted small") }
    }

    // castReaction/retractReaction require effectiveStatus == APPROVED server-side -- only
    // RENDERED once approved, never shown-then-failed on a still-PENDING/REJECTED project.
    if (project.effectiveStatus == CrowdfundingProjectStatus.APPROVED) {
        renderReactionControls(card, project, onChanged)
    }

    if (canBoard && project.status == CrowdfundingProjectStatus.PENDING) {
        renderBoardDecidePanel(card, project, onChanged)
    }
}

private fun renderReactionControls(
    card: SimplePanel,
    project: CrowdfundingProjectDto,
    onChanged: () -> Unit,
) {
    val reactionRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap border-top pt-2 mt-1") }
    val myReactionPanel = reactionRow.vPanel(spacing = 0) { addCssClasses("flex-grow-1") }
    myReactionPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    val likeButton = reactionRow.button(tr("Like"), style = ButtonStyle.OUTLINESUCCESS)
    val dislikeButton = reactionRow.button(tr("Dislike"), style = ButtonStyle.OUTLINEDANGER)
    val retractButton = reactionRow.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)

    fun setButtonsDisabled(disabled: Boolean) {
        likeButton.disabled = disabled
        dislikeButton.disabled = disabled
        retractButton.disabled = disabled
    }

    fun refreshMyReaction() {
        myReactionPanel.removeAll()
        myReactionPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val reactions = guarded { rpcService<ICrowdfundingService>().getMyReaction(project.id) } ?: emptyList()
            val mine = reactions.firstOrNull()
            myReactionPanel.removeAll()
            if (mine == null) {
                myReactionPanel.div(tr("Sie haben noch nicht reagiert.")) { addCssClasses("text-muted small") }
                retractButton.hide()
            } else {
                val row = myReactionPanel.hPanel(spacing = 6) { addCssClasses("align-items-center") }
                row.div(tr("Ihre Reaktion:")) { addCssClasses("text-muted small") }
                row.typeBadge(crowdfundingReactionValueLabel(mine.value), crowdfundingReactionValueColor(mine.value))
                retractButton.show()
            }
        }
    }

    fun castReaction(value: CrowdfundingReactionValue) {
        setButtonsDisabled(true)
        AppScope.launch {
            val result = guarded { rpcService<ICrowdfundingService>().castReaction(project.id, value) }
            setButtonsDisabled(false)
            if (result != null) {
                notifySuccess(tr("Reaktion gespeichert."))
                refreshMyReaction()
                onChanged()
            }
        }
    }

    likeButton.onClick { castReaction(CrowdfundingReactionValue.LIKE) }
    dislikeButton.onClick { castReaction(CrowdfundingReactionValue.DISLIKE) }
    retractButton.onClick {
        setButtonsDisabled(true)
        AppScope.launch {
            val result = guarded { rpcService<ICrowdfundingService>().retractReaction(project.id) }
            setButtonsDisabled(false)
            if (result != null) {
                notifySuccess(tr("Reaktion zurückgezogen."))
                refreshMyReaction()
                onChanged()
            }
        }
    }

    refreshMyReaction()
}

/**
 * D4: [ICrowdfundingService.approveProject] gets a light Tier-1 [confirmDialog] (one-way decision,
 * moves no LTR). [ICrowdfundingService.rejectProject] also uses [confirmDialog] (Tier 1) but its
 * copy repeats the D7 forfeiture consequence at the point of commitment, and requires a non-blank
 * `reason` (client pre-check, server hard-rejects blank too) -- that reason is PUBLIC/visible on
 * the project, no private rejection path exists.
 */
private fun renderBoardDecidePanel(
    card: SimplePanel,
    project: CrowdfundingProjectDto,
    onChanged: () -> Unit,
) {
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    decidePanel.div(tr("Vorstandsentscheidung")) { addCssClass("fw-bold") }

    if (project.isAutoApproved) {
        decidePanel.div(
            tr(
                "Dieses Projekt wurde bereits durch die 14-Tage-Schweigefrist automatisch genehmigt -- eine " +
                    "Vorstandsentscheidung ist serverseitig nicht mehr möglich.",
            ),
        ) { addCssClasses("text-muted small") }
        return
    }

    val reasonInput =
        decidePanel.textArea(
            label = tr("Ablehnungsgrund (nur für \"Ablehnen\" erforderlich, öffentlich sichtbar)"),
            rows = 2,
        )
    val errorBox =
        decidePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonsRow = decidePanel.hPanel(spacing = 8)
    val approveButton = buttonsRow.actionButton(ActionIcon.APPROVE, tr("Genehmigen"), style = ButtonStyle.SUCCESS)
    val rejectButton = buttonsRow.actionButton(ActionIcon.REJECT, tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

    approveButton.onClick {
        errorBox.hide()
        confirmDialog(
            title = tr("Projekt genehmigen"),
            message = gettext("Das Projekt \"%1\" wird genehmigt und für Like/Dislike-Reaktionen (Spenden) geöffnet.", project.title),
            confirmLabel = tr("Genehmigen"),
            confirmIcon = ActionIcon.APPROVE,
        ) {
            approveButton.disabled = true
            rejectButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<ICrowdfundingService>().approveProject(project.id) }
                approveButton.disabled = false
                rejectButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Projekt \"%1\" genehmigt.", result.title))
                    onChanged()
                }
            }
        }
    }

    rejectButton.onClick {
        errorBox.hide()
        val reason = reasonInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(reason)) {
            errorBox.content = tr("Bitte einen Ablehnungsgrund angeben -- dieser wird öffentlich auf dem Projekt angezeigt.")
            errorBox.show()
            return@onClick
        }
        confirmDialog(
            title = tr("Projekt ablehnen"),
            message =
                gettext(
                    "Das Projekt \"%1\" wird abgelehnt (Grund: \"%2\"). Der bereits gebuchte Einsatz von " +
                        "%3 wird NICHT zurückerstattet -- es gibt keinen Rückerstattungspfad, " +
                        "unabhängig von dieser Entscheidung.",
                    project.title,
                    reason,
                    formatLtr(project.initialWeightLtr),
                ),
            confirmLabel = tr("Ablehnen"),
            confirmIcon = ActionIcon.REJECT,
        ) {
            approveButton.disabled = true
            rejectButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<ICrowdfundingService>().rejectProject(project.id, reason) }
                approveButton.disabled = false
                rejectButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Projekt \"%1\" abgelehnt.", result.title))
                    onChanged()
                }
            }
        }
    }
}

// ================================================================================================
// Treuhänder-Werkzeuge: monatliche Verteilung berechnen + Verteilungshistorie
// ================================================================================================

internal fun renderDistributionsTable(
    panel: SimplePanel,
    distributions: List<CrowdfundingDistributionDto>,
    viewport: NarrowViewportSource = BrowserNarrowViewport,
) {
    // W5: a real table instead of the hand-built pseudo-table (see PseudoTableGoldenDomTest for the unchanged cells).
    panel.plainDataTable(
        columns =
            listOf(
                textColumn<CrowdfundingDistributionDto>(title = tr("Projekt"), primary = true) { it.projectTitle },
                textColumn<CrowdfundingDistributionDto>(
                    title = tr("Zeitraum"),
                    cssClasses = "small",
                ) { gettext("%1 – %2", formatDate(it.periodStart), formatDate(it.periodEnd)) },
                textColumn<CrowdfundingDistributionDto>(title = tr("Korb"), numeric = true) { it.basketTotalAtDistribution.toString() },
                DataColumn(title = tr("Betrag"), numeric = true, cell = { cell, d -> cell.moneySpan(d.amountEur) }),
                textColumn<CrowdfundingDistributionDto>(
                    title = tr("Berechnet"),
                    cssClasses = "text-muted small",
                ) { gettext("%1 von %2", formatSystemDateTime(it.computedAt), it.triggeredByDisplayName) },
            ),
        rows = distributions,
        viewport = viewport,
        label = gettext("Verteilungshistorie"),
    )
}

// ================================================================================================
// German label/badge-color tables
// ================================================================================================

/** [statusBadge] grammar (`StatusBadge.kt`): a project's persisted board decision progresses over
 * its lifetime (PENDING -> APPROVED/REJECTED), so it uses the filled/lifecycle variant, not
 * [typeBadge]. Covers every [CrowdfundingProjectStatus] literal. */
fun crowdfundingProjectStatusLabel(status: CrowdfundingProjectStatus): String =
    when (status) {
        CrowdfundingProjectStatus.PENDING -> gettext("Ausstehend")
        CrowdfundingProjectStatus.APPROVED -> gettext("Genehmigt")
        CrowdfundingProjectStatus.REJECTED -> gettext("Abgelehnt")
    }

fun crowdfundingProjectStatusColor(status: CrowdfundingProjectStatus): String =
    when (status) {
        CrowdfundingProjectStatus.PENDING -> "warning"
        CrowdfundingProjectStatus.APPROVED -> "success"
        CrowdfundingProjectStatus.REJECTED -> "danger"
    }

/** [typeBadge] grammar: a member's Like/Dislike is a fixed classification, not a progressing
 * status -- outline variant, per the plan's own convention table. */
fun crowdfundingReactionValueLabel(value: CrowdfundingReactionValue): String =
    when (value) {
        CrowdfundingReactionValue.LIKE -> gettext("Like")
        CrowdfundingReactionValue.DISLIKE -> gettext("Dislike")
    }

fun crowdfundingReactionValueColor(value: CrowdfundingReactionValue): String =
    when (value) {
        CrowdfundingReactionValue.LIKE -> "success"
        CrowdfundingReactionValue.DISLIKE -> "danger"
    }
