package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianRaterType
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import network.lapis.cloud.shared.domain.PoliticianWeightSnapshotDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPoliticianService

/**
 * LTR-Wirtschaft UI wave, screen 4 of 5 -- "Politiker" ([IPoliticianService], self-contained
 * domain: politician profiles, member+guest Like/Dislike ranking, BOARD/ADMIN grant/revoke/
 * mandate-text administration, manually-triggered weight snapshots) plus a small ADMIN-only inline
 * toggle for `OrganizationSettingsDto.politicianRankingEnabled` ([IOrganizationSettingsService]),
 * the wave's first client wiring for that RPC (see "Inline ADMIN-only feature toggle" below). See
 * `20-politician.kuml.kts` file header for the full fachlich model this screen surfaces.
 *
 * **Role gating** (verified against `PoliticianService.kt`'s actual `requireRole`/
 * `requirePoliticianRankingEnabled` call sites, not guessed from method names -- see
 * `Routes.POLITICIANS` KDoc for the route-level `requireAuth` reasoning):
 * - [IPoliticianService.listPoliticians]/[IPoliticianService.getPoliticianProfile]/
 *   [IPoliticianService.getTopPoliticians]/[IPoliticianService.getMyRating]/
 *   [IPoliticianService.getWeightHistory] -- any authenticated member.
 * - [IPoliticianService.castRating]/[IPoliticianService.retractRating] -- MEMBER+/ACTIVE OR GUEST
 *   (`requirePoliticianRaterMembership`). Rendered for any authenticated caller regardless of role --
 *   [network.lapis.cloud.shared.domain.SessionInfoDto] carries no member-status field at all, same
 *   reasoning `LtrLedgerScreen.kt`/`CrowdfundingScreen.kt`/`AuctionScreen.kt` already document for
 *   their own ACTIVE-gated writes -- only rendered on `status == ACTIVE` profiles.
 * - [IPoliticianService.grantPoliticianStatus]/[IPoliticianService.revokePoliticianStatus]/
 *   [IPoliticianService.updateMandateText]/[IPoliticianService.snapshotWeights] --
 *   `current.requireRole(BOARD, ADMIN)`. Gated here as `canBoard`.
 *
 * **The `politicianRankingEnabled` gate**: EVERY single [IPoliticianService] method -- including
 * every read -- calls `requirePoliticianRankingEnabled()` first (see that interface's own KDoc:
 * "This applies even to `grantPoliticianStatus` -- a BOARD member cannot silently activate the
 * feature by granting status while it is toggled off"). Unlike `AuctionScreen.kt`'s Verwaltung
 * panel (which stays reachable specifically because `IAuctionService.enableAuction` is itself the
 * ADMIN's path to switch `auctionEnabled` back on), **nothing on `IPoliticianService` itself can
 * flip `politicianRankingEnabled`** -- only [IOrganizationSettingsService.updateOrganizationSettings]
 * can, a completely separate RPC/service. This screen therefore gates the read-heavy,
 * `IPoliticianService`-driven area (Top-Politiker widget + profile list) behind a single primary
 * gate probe ([loadPoliticiansOrShowBanner], `listPoliticians()`) -- simpler than
 * `AuctionScreen.kt`'s per-call `ConflictException` handling because the Top-Politiker load only
 * runs AFTER that probe has already confirmed the feature is enabled, so no second banner/toast can
 * fire for the same disabled state. The BOARD "Verwaltung" forms and the "Einstellungen" toggle
 * stay visible regardless of that probe's outcome (role-gated only, not feature-flag-gated) -- a
 * disabled feature simply makes their buttons fail with the ordinary `guarded()` `ConflictException`
 * toast, same posture every other ACTIVE-/flag-gated write in this codebase already takes. The
 * "Einstellungen" toggle is the ONLY path to re-enable the feature, so it is never hidden by the
 * banner above it.
 *
 * **Design decision D9 (politician self-edit finding, resolved as a must-fix)**:
 * [IPoliticianService.updateMandateText] is BOARD/ADMIN-only server-side -- reading
 * `PoliticianService.kt`/`IPoliticianService.kt` in full turns up no `requireSelfOrAdmin`-shaped
 * call anywhere for this method, and no self-service mandate-text path exists at all. This is a
 * deliberate editorial-control model (the party/association controls what its
 * officially-recognized politicians' profiles say, not the politician unilaterally) -- the
 * interface's own class KDoc frames BOARD/ADMIN as the sole administrator of a profile's lifecycle
 * AND content, with no carve-out anywhere for the politician editing their own row. Documented here
 * explicitly as a checked, intentional finding, not silently implemented as "no self-service, full
 * stop" without acknowledgment (per the design review's must-fix D9).
 *
 * **Data-shape honesty**: [PoliticianProfileDto.memberTrustWeight]/`guestTrustWeight`/
 * `combinedTrustWeight` are rendered as THREE separate, explicitly-labeled figures, never
 * summed/blended into one percentage -- that DTO's own KDoc documents `combinedTrustWeight` as the
 * literal (not "fair blend") sum of two non-commensurable units (a share of real LTR wealth vs. a
 * raw vote count). Only `memberTrustWeight` is genuinely LTR-denominated and rendered via
 * [ltrSpan]/[formatLtr]; `guestTrustWeight`/`combinedTrustWeight` are plain vote-count-shaped
 * `Decimal` figures shown WITHOUT the LTR badge -- giving them the LTR badge would misrepresent
 * their unit exactly as much as omitting [formatLtr] from a genuine LTR figure would.
 *
 * **Confirm-dialog tier (design decision D4)**: [IPoliticianService.grantPoliticianStatus] uses the
 * plain, neutral-framed [confirmDialog] (Tier 1 "Kostenpflichtig" -- costs the target member no
 * LTR, but is a material, publicly-visible status change). [IPoliticianService.revokePoliticianStatus]
 * uses a bespoke Tier 3 "Löschend" modal ([politicianRevokeConfirmDialog]) -- its own KDoc documents
 * that it irreversibly deletes every `PoliticianReactionDto`/`PoliticianWeightSnapshotDto` row for
 * this profile, not merely a status flip; the confirm copy names that erasure explicitly, not just
 * "wirklich widerrufen?". [IPoliticianService.castRating]/[IPoliticianService.retractRating]/
 * [IPoliticianService.updateMandateText]/[IPoliticianService.snapshotWeights] get no confirm
 * dialog: a rating can be changed again at will (same posture `CrowdfundingScreen.kt`'s
 * `castReaction`/`retractReaction` already document), `updateMandateText` is a reversible text
 * edit, and `snapshotWeights` is idempotent per (politician, month) and produces only an audit
 * record (same posture `CrowdfundingScreen.computeMonthlyDistribution`/`AuctionScreen.settleAuction`
 * already document). The `politicianRankingEnabled` toggle also uses the plain [confirmDialog] --
 * reversible at any time and destroys no data, unlike `AuctionScreen`'s Tier-3 `disableAuction`
 * (see [renderPoliticianRankingToggle]). All non-idempotent buttons disable themselves for the
 * duration of the in-flight request (double-submit protection, `LedgerScreen.postDirectButton`'s
 * idiom).
 *
 * **Empty states (D10)**: zero politicians renders "Noch keine Politiker-Profile vorhanden.", a
 * caller with no rating on a given politician renders "Sie haben noch nicht bewertet.", and zero
 * weight-history renders "Noch kein Gewichtsverlauf vorhanden." -- never a blank list/table.
 *
 * Every LTR-denominated amount is rendered via [ltrSpan]/[formatLtr] (`Money.kt`, D2) -- never
 * hand-formatted. Every server-computed weight figure is shown verbatim, never re-summed or
 * re-derived client-side.
 */
fun renderPoliticianScreen(container: SimplePanel) {
    val canBoard = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    val canTreasury = AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    val canAdmin = AppState.hasRole(AccountRole.ADMIN)
    val currentMemberId = AppState.session?.memberId

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Politiker"))

    // ---- Top-Politiker (dashboard widget, top of screen) ---------------------------------------
    root.h2(tr("Top-Politiker"))
    val topPanel = root.vPanel(spacing = 4)
    topPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    // ---- Feature-disabled banner (shown in place of the read-heavy area below when the primary
    // gate probe fails -- see file KDoc "The politicianRankingEnabled gate") -----------------------
    val disabledBanner = root.vPanel(spacing = 4) { addCssClasses("border rounded p-3 bg-body-tertiary") }
    disabledBanner.hide()

    // ---- Politiker-Profile list -----------------------------------------------------------------
    root.h2(tr("Politiker-Profile"))
    val listControlsRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeFormerSelect =
        listControlsRow.select(
            options = listOf("false" to tr("Nur aktive Profile"), "true" to tr("Inklusive ehemaliger Profile")),
            value = "false",
            label = tr("Anzeige"),
        )
    val politiciansRefreshButton = listControlsRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val politiciansPanel = root.vPanel(spacing = 10)

    // ---- Verwaltung (BOARD/ADMIN, D3 staged disclosure -- always reachable, see file KDoc) ------
    val boardPanel = if (canBoard) root.vPanel(spacing = 10) { addCssClasses("border rounded p-3 mt-2") } else null

    // ---- Einstellungen (politicianRankingEnabled toggle -- independent of the gate above, the
    // ONLY path to re-enable the feature, see file KDoc) ------------------------------------------
    val settingsPanel = if (canTreasury) root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2") } else null

    fun loadTopPoliticians() {
        topPanel.removeAll()
        topPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val top = guarded { rpcService<IPoliticianService>().getTopPoliticians(6) } ?: return@launch
            topPanel.removeAll()
            if (top.isEmpty()) {
                topPanel.p(tr("Noch keine Politiker-Profile vorhanden.")) { addCssClasses("text-muted small") }
            } else {
                renderTopPoliticiansList(topPanel, top)
            }
        }
    }

    fun loadPoliticians() {
        disabledBanner.hide()
        topPanel.show()
        politiciansPanel.show()
        politiciansPanel.removeAll()
        politiciansPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        val includeFormer = includeFormerSelect.value == "true"
        AppScope.launch {
            val politicians =
                loadPoliticiansOrShowBanner(includeFormer, topPanel, politiciansPanel, disabledBanner, canAdmin, canTreasury)
                    ?: return@launch
            politiciansPanel.removeAll()
            if (politicians.isEmpty()) {
                politiciansPanel.p(tr("Noch keine Politiker-Profile vorhanden.")) { addCssClasses("text-muted small") }
            } else {
                politicians.forEach { politician ->
                    renderPoliticianCard(politiciansPanel, politician, canBoard, currentMemberId) { loadPoliticians() }
                }
            }
            loadTopPoliticians()
        }
    }

    politiciansRefreshButton.onClick { loadPoliticians() }
    includeFormerSelect.subscribe { loadPoliticians() }

    loadPoliticians()

    if (boardPanel != null) {
        boardPanel.h2(tr("Verwaltung")) { addCssClass("h5") }
        boardPanel.div(tr("Sichtbar für BOARD/ADMIN.")) { addCssClasses("text-muted small mb-2") }
        AppScope.launch {
            val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
            renderGrantForm(boardPanel, members) { loadPoliticians() }
            renderSnapshotForm(boardPanel) { loadPoliticians() }
        }
    }

    if (settingsPanel != null) {
        settingsPanel.h2(tr("Einstellungen")) { addCssClass("h5") }
        settingsPanel.div(tr("Sichtbar für TREASURER/BOARD/ADMIN.")) { addCssClasses("text-muted small mb-2") }
        renderPoliticianRankingToggle(settingsPanel, canAdmin) { loadPoliticians() }
    }
}

/**
 * The one call this screen routes outside `guarded()`'s generic wrapper (see file KDoc "The
 * politicianRankingEnabled gate"). Returns the loaded list on success. On a [ConflictException]
 * (`politicianRankingEnabled == false`), hides [topPanel]/[politiciansPanel], shows
 * [disabledBanner] with role-appropriate copy pointing at the "Einstellungen" section, and returns
 * `null`. On any OTHER exception, re-dispatches into a throwing `guarded { }` block (so
 * `AppState.guarded`'s own mapping -- session expiry, forbidden, not-found, ... -- fires exactly as
 * it would for every other call site in this app) and returns `null`.
 */
private suspend fun loadPoliticiansOrShowBanner(
    includeFormer: Boolean,
    topPanel: SimplePanel,
    politiciansPanel: SimplePanel,
    disabledBanner: SimplePanel,
    canAdmin: Boolean,
    canTreasury: Boolean,
): List<PoliticianProfileDto>? =
    try {
        rpcService<IPoliticianService>().listPoliticians(includeFormer)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        topPanel.removeAll()
        topPanel.hide()
        politiciansPanel.removeAll()
        politiciansPanel.hide()
        disabledBanner.removeAll()
        disabledBanner.show()
        disabledBanner.div(tr("Das Politiker-Ranking ist derzeit deaktiviert.")) { addCssClass("fw-bold") }
        disabledBanner.div(
            when {
                canAdmin -> tr("Sie können das Politiker-Ranking im Abschnitt \"Einstellungen\" unten aktivieren.")
                canTreasury -> tr("Ein ADMIN kann das Politiker-Ranking im Abschnitt \"Einstellungen\" unten aktivieren.")
                else -> tr("Bitte wenden Sie sich an ein ADMIN-Mitglied, falls Sie hierauf Zugriff benötigen.")
            },
        ) { addCssClasses("text-muted small") }
        null
    } catch (e: Throwable) {
        politiciansPanel.removeAll()
        politiciansPanel.p(tr("Politiker-Profile konnten nicht geladen werden.")) { addCssClasses("text-muted small") }
        guarded<Unit> { throw e }
        null
    }

// ================================================================================================
// Top-Politiker widget
// ================================================================================================

private fun renderTopPoliticiansList(
    panel: SimplePanel,
    top: List<PoliticianProfileDto>,
) {
    top.forEachIndexed { index, politician ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center border-bottom py-1 flex-wrap") }
        row.div("${index + 1}.") { width = 24.px }
        row.div(politician.displayName) { addCssClasses("flex-grow-1 fw-bold") }
        val memberCell = row.vPanel(spacing = 0)
        memberCell.div(tr("Mitglieder")) { addCssClasses("text-muted small") }
        memberCell.ltrSpan(politician.memberTrustWeight)
        val guestCell = row.vPanel(spacing = 0)
        guestCell.div(tr("Gäste")) { addCssClasses("text-muted small") }
        guestCell.div(politician.guestTrustWeight.toString())
        val combinedCell = row.vPanel(spacing = 0)
        combinedCell.div(tr("Gesamt")) { addCssClasses("text-muted small") }
        combinedCell.div(politician.combinedTrustWeight.toString()) { addCssClass("fw-bold") }
    }
}

// ================================================================================================
// Politiker-Profile card
// ================================================================================================

private fun renderPoliticianCard(
    panel: SimplePanel,
    politician: PoliticianProfileDto,
    canBoard: Boolean,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(politician.displayName) { addCssClasses("flex-grow-1 fw-bold") }
    if (currentMemberId != null && politician.memberId == currentMemberId) {
        headerRow.div(tr("(Sie)")) { addCssClasses("text-muted small") }
    }
    headerRow.statusBadge(politicianProfileStatusLabel(politician.status), politicianProfileStatusColor(politician.status))

    politician.mandateText?.takeIf { it.isNotBlank() }?.let { mandate ->
        card.div(mandate) { addCssClasses("small") }
    }

    card.div(gettext("Politiker-Status seit %1 (erteilt von %2)", politician.grantedAt, politician.grantedByDisplayName)) {
        addCssClasses("text-muted small")
    }
    if (politician.status == PoliticianProfileStatus.FORMER && politician.revokedAt != null) {
        card.div(gettext("Widerrufen am %1 von %2", politician.revokedAt, politician.revokedByDisplayName ?: "--")) {
            addCssClasses("text-muted small")
        }
    }

    // Data-shape honesty (design review, file KDoc): three separate, non-summable-as-a-percentage
    // figures. Only memberTrustWeight is genuinely LTR-denominated and gets the ltrSpan badge.
    val weightRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val memberCell = weightRow.vPanel(spacing = 2)
    memberCell.div(tr("Mitglieder-Gewicht (LTR-gewichtet)")) { addCssClasses("text-muted small") }
    memberCell.ltrSpan(politician.memberTrustWeight)
    val guestCell = weightRow.vPanel(spacing = 2)
    guestCell.div(tr("Gast-Gewicht (reine Stimmenzahl, ungewichtet)")) { addCssClasses("text-muted small") }
    guestCell.div(politician.guestTrustWeight.toString()) { addCssClass("fw-bold") }
    val combinedCell = weightRow.vPanel(spacing = 2)
    combinedCell.div(tr("Gesamt (Summe, keine vergleichbare Einheit)")) { addCssClasses("text-muted small") }
    combinedCell.div(politician.combinedTrustWeight.toString()) { addCssClass("fw-bold") }

    card.div(
        gettext(
            "Mitglieder: Like %1 / Dislike %2 · Gäste: Like %3 / Dislike %4",
            politician.memberLikeCount,
            politician.memberDislikeCount,
            politician.guestLikeCount,
            politician.guestDislikeCount,
        ),
    ) { addCssClasses("small text-muted") }

    // castRating/retractRating require status == ACTIVE server-side -- only RENDERED once active,
    // never shown-then-failed on a FORMER profile (same posture CrowdfundingScreen.kt's
    // effectiveStatus == APPROVED gate already establishes for its own reaction controls).
    if (politician.status == PoliticianProfileStatus.ACTIVE) {
        renderRatingControls(card, politician, onChanged)
    }

    if (canBoard) {
        renderBoardCardActions(card, politician, onChanged)
    }

    renderWeightHistorySection(card, politician)
}

private fun renderRatingControls(
    card: SimplePanel,
    politician: PoliticianProfileDto,
    onChanged: () -> Unit,
) {
    val ratingRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap border-top pt-2 mt-1") }
    val myRatingPanel = ratingRow.vPanel(spacing = 0) { addCssClasses("flex-grow-1") }
    myRatingPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    val likeButton = ratingRow.button(tr("Like"), style = ButtonStyle.OUTLINESUCCESS)
    val dislikeButton = ratingRow.button(tr("Dislike"), style = ButtonStyle.OUTLINEDANGER)
    val retractButton = ratingRow.button(tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)

    fun setButtonsDisabled(disabled: Boolean) {
        likeButton.disabled = disabled
        dislikeButton.disabled = disabled
        retractButton.disabled = disabled
    }

    fun refreshMyRating() {
        myRatingPanel.removeAll()
        myRatingPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val ratings = guarded { rpcService<IPoliticianService>().getMyRating(politician.memberId) } ?: emptyList()
            val mine = ratings.firstOrNull()
            myRatingPanel.removeAll()
            if (mine == null) {
                myRatingPanel.div(tr("Sie haben noch nicht bewertet.")) { addCssClasses("text-muted small") }
                retractButton.hide()
            } else {
                val row = myRatingPanel.hPanel(spacing = 6) { addCssClasses("align-items-center") }
                row.div(tr("Ihre Bewertung:")) { addCssClasses("text-muted small") }
                row.typeBadge(politicianReactionValueLabel(mine.value), politicianReactionValueColor(mine.value))
                row.typeBadge(politicianRaterTypeLabel(mine.raterType), politicianRaterTypeColor(mine.raterType))
                retractButton.show()
            }
        }
    }

    fun castRating(value: PoliticianReactionValue) {
        setButtonsDisabled(true)
        AppScope.launch {
            val result = guarded { rpcService<IPoliticianService>().castRating(politician.memberId, value) }
            setButtonsDisabled(false)
            if (result != null) {
                notifySuccess(tr("Bewertung gespeichert."))
                refreshMyRating()
                onChanged()
            }
        }
    }

    likeButton.onClick { castRating(PoliticianReactionValue.LIKE) }
    dislikeButton.onClick { castRating(PoliticianReactionValue.DISLIKE) }
    retractButton.onClick {
        setButtonsDisabled(true)
        AppScope.launch {
            val result = guarded { rpcService<IPoliticianService>().retractRating(politician.memberId) }
            setButtonsDisabled(false)
            if (result != null) {
                notifySuccess(tr("Bewertung zurückgezogen."))
                refreshMyRating()
                onChanged()
            }
        }
    }

    refreshMyRating()
}

/**
 * BOARD/ADMIN actions on an individual card: Mandatstext bearbeiten (D9 -- BOARD/ADMIN-only, see
 * file KDoc) and Widerrufen (Tier 3 "Löschend", see [politicianRevokeConfirmDialog]). A FORMER
 * profile shows neither -- it can only be reactivated via the "Politiker-Status erteilen" form
 * above ([renderGrantForm], the same idempotent-upsert entry point as a first-time grant).
 */
private fun renderBoardCardActions(
    card: SimplePanel,
    politician: PoliticianProfileDto,
    onChanged: () -> Unit,
) {
    val panel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    panel.div(tr("Verwaltung (BOARD/ADMIN)")) { addCssClass("fw-bold") }

    if (politician.status == PoliticianProfileStatus.FORMER) {
        panel.div(
            tr(
                "Dieses Profil ist ehemalig -- über das Formular \"Politiker-Status erteilen\" oben kann es erneut " +
                    "aktiviert werden.",
            ),
        ) { addCssClasses("text-muted small") }
        return
    }

    val mandateInput = panel.textArea(value = politician.mandateText, label = tr("Mandatstext"), rows = 2)
    val mandateSaveButton = panel.button(tr("Mandatstext speichern"), style = ButtonStyle.OUTLINESECONDARY)
    mandateSaveButton.onClick {
        val text = mandateInput.value?.trim()?.takeIf { it.isNotBlank() }
        mandateSaveButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IPoliticianService>().updateMandateText(politician.memberId, text) }
            mandateSaveButton.disabled = false
            if (result != null) {
                notifySuccess(tr("Mandatstext aktualisiert."))
                onChanged()
            }
        }
    }

    val revokeButton = panel.button(tr("Politiker-Status widerrufen"), style = ButtonStyle.OUTLINEDANGER)
    revokeButton.onClick {
        politicianRevokeConfirmDialog(politician.displayName) {
            revokeButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IPoliticianService>().revokePoliticianStatus(politician.memberId) }
                revokeButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Politiker-Status von %1 widerrufen.", result.displayName))
                    onChanged()
                }
            }
        }
    }
}

/**
 * Tier 3 "Löschend" (D4): same visual tier as Tier 2, but additionally names EXACTLY what is
 * permanently destroyed -- [IPoliticianService.revokePoliticianStatus]'s own KDoc: every
 * `PoliticianReactionDto` AND every `PoliticianWeightSnapshotDto` row for this profile is deleted,
 * not merely a status flip. A later `grantPoliticianStatus` reactivates the same row starting both
 * baskets back at zero -- this history does not come back.
 */
private fun politicianRevokeConfirmDialog(
    displayName: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Politiker-Status widerrufen bestätigen"))
    modal.div(
        tr(
            "Diese Aktion LÖSCHT UNWIDERRUFLICH sämtliche Bewertungen (Mitglieder UND Gäste) sowie den gesamten " +
                "Gewichtsverlauf für dieses Profil -- nicht nur den Status.",
        ),
    ) { addCssClasses("fw-bold text-danger") }
    modal.div(
        gettext(
            "Der Politiker-Status von %1 wird widerrufen. Eine spätere erneute Erteilung reaktiviert das " +
                "Profil, aber beide Bewertungs-Körbe (Mitglieder und Gäste) beginnen dann wieder bei null -- die " +
                "gelöschte Historie ist nicht wiederherstellbar.",
            displayName,
        ),
    )
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Unwiderruflich widerrufen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Gewichtsverlauf (lazy-loaded per card, on demand)
// ================================================================================================

private fun renderWeightHistorySection(
    card: SimplePanel,
    politician: PoliticianProfileDto,
) {
    val section = card.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    val toggleButton = section.button(tr("Gewichtsverlauf anzeigen/ausblenden"), style = ButtonStyle.OUTLINESECONDARY)
    val historyPanel = section.vPanel(spacing = 4)
    historyPanel.hide()
    var loaded = false

    toggleButton.onClick {
        if (historyPanel.visible) {
            historyPanel.hide()
            return@onClick
        }
        historyPanel.show()
        if (!loaded) {
            historyPanel.removeAll()
            historyPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
            AppScope.launch {
                val history = guarded { rpcService<IPoliticianService>().getWeightHistory(politician.memberId) } ?: return@launch
                loaded = true
                historyPanel.removeAll()
                if (history.isEmpty()) {
                    historyPanel.p(tr("Noch kein Gewichtsverlauf vorhanden.")) { addCssClasses("text-muted small") }
                } else {
                    renderWeightHistoryTable(historyPanel, history)
                }
            }
        }
    }
}

private fun renderWeightHistoryTable(
    panel: SimplePanel,
    history: List<PoliticianWeightSnapshotDto>,
) {
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div(tr("Monat")) { width = 100.px }
    headerRow.div(tr("Mitglieder-Gewicht")) { width = 160.px }
    headerRow.div(tr("Gast-Gewicht")) { width = 110.px }
    headerRow.div(tr("Gesamt")) { width = 90.px }
    headerRow.div(tr("Berechnet")) { addCssClasses("flex-grow-1") }

    history.forEach { snapshot ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(snapshot.periodMonth.toString()) { width = 100.px }
        val memberCell = row.div { width = 160.px }
        memberCell.ltrSpan(snapshot.memberTrustWeight)
        row.div(snapshot.guestTrustWeight.toString()) {
            width = 110.px
            addCssClasses("small")
        }
        row.div(snapshot.combinedTrustWeight.toString()) {
            width = 90.px
            addCssClasses("fw-bold small")
        }
        row.div("${snapshot.computedAt}") {
            addCssClasses("text-muted small flex-grow-1")
        }
    }
}

// ================================================================================================
// Verwaltung: Politiker-Status erteilen + Gewichts-Snapshot auslösen
// ================================================================================================

private fun renderGrantForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
    onCompleted: () -> Unit,
) {
    root.h2(tr("Politiker-Status erteilen")) { addCssClass("h6") }
    if (members.isEmpty()) {
        root.p(tr("Keine Mitglieder vorhanden.")) { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    val memberSelect = panel.select(options = members.map { it.id to it.displayName }, label = tr("Mitglied"))
    val mandateInput = panel.textArea(label = tr("Mandatstext (optional)"), rows = 2)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val grantButton = panel.button(tr("Erteilen / Aktualisieren"), style = ButtonStyle.PRIMARY)

    grantButton.onClick {
        errorBox.hide()
        val memberId = memberSelect.value
        val member = members.find { it.id == memberId }
        if (member == null) {
            errorBox.content = tr("Bitte ein Mitglied auswählen.")
            errorBox.show()
            return@onClick
        }
        val mandateText = mandateInput.value?.trim()?.takeIf { it.isNotBlank() }

        // Tier 1 "Kostenpflichtig" (D4): plain, neutral-framed confirmDialog -- costs the target
        // member no LTR, but is a material, publicly-visible status change; also states the
        // idempotent-upsert semantics so the caller isn't surprised by re-running this on an
        // already-ACTIVE profile.
        confirmDialog(
            title = tr("Politiker-Status erteilen"),
            message =
                gettext(
                    "%1 erhält (oder behält) den Politiker-Status. Ein bereits aktives Profil wird " +
                        "aktualisiert, ein ehemaliges (widerrufenes) Profil reaktiviert -- niemals wird ein zweites " +
                        "Profil für dasselbe Mitglied angelegt.",
                    member.displayName,
                ),
            confirmLabel = tr("Erteilen"),
        ) {
            grantButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IPoliticianService>().grantPoliticianStatus(member.id, mandateText) }
                grantButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Politiker-Status für %1 erteilt.", result.displayName))
                    mandateInput.value = null
                    onCompleted()
                }
            }
        }
    }
}

/**
 * No confirm-dialog: idempotent per (politician, month) via `insertIgnore`, produces only an audit
 * record -- same posture `CrowdfundingScreen.computeMonthlyDistribution`/`AuctionScreen.settleAuction`
 * already document.
 */
private fun renderSnapshotForm(
    root: SimplePanel,
    onCompleted: () -> Unit,
) {
    root.h2(tr("Gewichts-Snapshot auslösen")) { addCssClass("h6") }
    root.div(
        tr(
            "Berechnet und speichert für JEDES aktive Politiker-Profil einen Gewichts-Schnappschuss für den gewählten " +
                "Monat (Tag wird ignoriert, auf den Monatsersten normalisiert). Erneutes Ausführen für denselben Monat " +
                "erzeugt keine Duplikate.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    val panel = root.vPanel(spacing = 6)
    val monthInput = panel.text(label = tr("Monat (JJJJ-MM-TT, Tag wird ignoriert)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val snapshotButton = panel.button(tr("Snapshot auslösen"), style = ButtonStyle.SECONDARY)

    snapshotButton.onClick {
        errorBox.hide()
        val monthText = monthInput.value.orEmpty().trim()
        val periodMonth = runCatching { LocalDate.parse(monthText) }.getOrNull()
        if (periodMonth == null) {
            errorBox.content = tr("Bitte einen Monat im Format JJJJ-MM-TT angeben.")
            errorBox.show()
            return@onClick
        }
        snapshotButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IPoliticianService>().snapshotWeights(periodMonth) }
            snapshotButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Snapshot für %1 Profil(e) berechnet.", result.size))
                onCompleted()
            }
        }
    }
}

// ================================================================================================
// Einstellungen: politicianRankingEnabled toggle (IOrganizationSettingsService)
// ================================================================================================

/**
 * Design review "Inline ADMIN-only feature toggle" / plan: the wave's first client wiring for
 * [IOrganizationSettingsService] -- confirmed via grep that no existing screen calls
 * `updateOrganizationSettings` at all (`LedgerScreen.kt`/`PostalMailScreen.kt` only ever call
 * `getOrganizationSettings`, read-only). ADMIN replaces the FULL [OrganizationSettingsDto] wholesale
 * (no partial-update RPC exists -- see that DTO's own KDoc), copying every existing field unchanged
 * and flipping ONLY `politicianRankingEnabled`, never re-deriving/guessing any of the other fields
 * ([toInputWithPoliticianRankingEnabled]). TREASURER/BOARD (`canAdmin == false`) see the current
 * state read-only, matching that DTO's own KDoc "a BOARD member cannot silently activate the
 * feature". No confirm-dialog tier beyond the plain [confirmDialog] (D4): unlike `AuctionScreen`'s
 * Tier-3 `disableAuction`, flipping this flag destroys no data and is reversible at any time --
 * copy states the practical consequence (existing profiles/ratings become unreachable, not deleted)
 * without overstating the risk.
 */
private fun renderPoliticianRankingToggle(
    root: SimplePanel,
    canAdmin: Boolean,
    onChanged: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun load() {
        panel.removeAll()
        panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } ?: return@launch
            panel.removeAll()
            val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            statusRow.div(tr("Politiker-Ranking:")) { addCssClasses("text-muted small") }
            statusRow.statusBadge(
                if (settings.politicianRankingEnabled) tr("Aktiviert") else tr("Deaktiviert"),
                if (settings.politicianRankingEnabled) "success" else "secondary",
            )
            if (canAdmin) {
                val toggleButton =
                    panel.button(
                        if (settings.politicianRankingEnabled) tr("Deaktivieren") else tr("Aktivieren"),
                        style = if (settings.politicianRankingEnabled) ButtonStyle.OUTLINEDANGER else ButtonStyle.PRIMARY,
                    )
                toggleButton.onClick {
                    val newValue = !settings.politicianRankingEnabled
                    confirmDialog(
                        title = if (newValue) tr("Politiker-Ranking aktivieren") else tr("Politiker-Ranking deaktivieren"),
                        message =
                            if (newValue) {
                                tr(
                                    "Mitglieder und Gäste können ab sofort Politiker-Profile einsehen und bewerten; " +
                                        "BOARD/ADMIN können Politiker-Status erteilen/widerrufen.",
                                )
                            } else {
                                tr(
                                    "Sämtliche Politiker-Funktionen (Profile, Bewertungen, Vergabe/Widerruf, Snapshot) sind " +
                                        "bis zur erneuten Aktivierung nicht mehr erreichbar -- bestehende Profile/Bewertungen " +
                                        "bleiben dabei erhalten, sie werden nur unerreichbar, nicht gelöscht.",
                                )
                            },
                        confirmLabel = if (newValue) tr("Aktivieren") else tr("Deaktivieren"),
                    ) {
                        toggleButton.disabled = true
                        AppScope.launch {
                            val result =
                                guarded {
                                    rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                                        settings.toInputWithPoliticianRankingEnabled(newValue),
                                    )
                                }
                            toggleButton.disabled = false
                            if (result != null) {
                                notifySuccess(if (newValue) tr("Politiker-Ranking aktiviert.") else tr("Politiker-Ranking deaktiviert."))
                                load()
                                onChanged()
                            }
                        }
                    }
                }
            }
        }
    }
    load()
}

/**
 * Wholesale-replace helper (see [renderPoliticianRankingToggle] KDoc) -- copies every field of an
 * already-fetched [OrganizationSettingsDto] unchanged except [OrganizationSettingsDto.politicianRankingEnabled].
 * Deliberately excludes `auctionEnabled`/`auctionMaxValueLtr`: [OrganizationSettingsInput] itself has
 * no fields for them (see that DTO's own KDoc) -- the generic update path can never touch them.
 *
 * `internal` (not `private`) so [PoliticianScreenTest] can cover the "never silently drop/reset a
 * field" contract directly -- exactly the kind of regression a one-line unit test catches on the
 * next [OrganizationSettingsDto] field addition, per code review round 1.
 */
internal fun OrganizationSettingsDto.toInputWithPoliticianRankingEnabled(newValue: Boolean) =
    OrganizationSettingsInput(
        name = name,
        street = street,
        postalCode = postalCode,
        city = city,
        country = country,
        bankIban = bankIban,
        bankBic = bankBic,
        taxExemptionAuthority = taxExemptionAuthority,
        taxExemptionDate = taxExemptionDate,
        isPoliticalParty = isPoliticalParty,
        postalMailEnabled = postalMailEnabled,
        politicianRankingEnabled = newValue,
        paymentBankAccountId = paymentBankAccountId,
        paymentFeeAccountId = paymentFeeAccountId,
        contributionIncomeAccountId = contributionIncomeAccountId,
        // Review MAJOR fix: `donationIncomeAccountId` was already silently dropped here before this
        // round (never listed by PoliticianScreenTest's own field-by-field assertions either) --
        // `eventIncomeAccountId`/`eventIncomeSphere` (V1.4.3.1) would otherwise have joined it as a
        // THIRD field this "wholesale-replace, one flag flipped" helper quietly resets to its
        // Kotlin default the next time a BOARD/ADMIN merely toggles politician ranking. All three are
        // fixed together -- see this function's own KDoc "never silently drop/reset a field".
        donationIncomeAccountId = donationIncomeAccountId,
        eventIncomeAccountId = eventIncomeAccountId,
        eventIncomeSphere = eventIncomeSphere,
    )

// ================================================================================================
// German label/badge-color tables
// ================================================================================================

/** [statusBadge] grammar (`StatusBadge.kt`): a profile's status progresses over its lifetime
 * (ACTIVE -> FORMER, and back via a later grant), so it uses the filled/lifecycle variant. Covers
 * every [PoliticianProfileStatus] literal. */
fun politicianProfileStatusLabel(status: PoliticianProfileStatus): String =
    when (status) {
        PoliticianProfileStatus.ACTIVE -> gettext("Aktiv")
        PoliticianProfileStatus.FORMER -> gettext("Ehemalig")
    }

fun politicianProfileStatusColor(status: PoliticianProfileStatus): String =
    when (status) {
        PoliticianProfileStatus.ACTIVE -> "success"
        PoliticianProfileStatus.FORMER -> "secondary"
    }

/** [typeBadge] grammar: a member's Like/Dislike is a fixed classification, not a progressing
 * status -- outline variant, same convention `CrowdfundingScreen.kt`'s own reaction-value table
 * already establishes for its (distinct, same-named-literal) enum. */
fun politicianReactionValueLabel(value: PoliticianReactionValue): String =
    when (value) {
        PoliticianReactionValue.LIKE -> gettext("Like")
        PoliticianReactionValue.DISLIKE -> gettext("Dislike")
    }

fun politicianReactionValueColor(value: PoliticianReactionValue): String =
    when (value) {
        PoliticianReactionValue.LIKE -> "success"
        PoliticianReactionValue.DISLIKE -> "danger"
    }

/** [typeBadge] grammar: who cast a rating (MEMBER/GAST) is a fixed classification frozen at cast
 * time, not a progressing status. */
fun politicianRaterTypeLabel(type: PoliticianRaterType): String =
    when (type) {
        PoliticianRaterType.MEMBER -> gettext("Mitglied")
        PoliticianRaterType.GAST -> gettext("Gast")
    }

fun politicianRaterTypeColor(type: PoliticianRaterType): String =
    when (type) {
        PoliticianRaterType.MEMBER -> "primary"
        PoliticianRaterType.GAST -> "info"
    }
