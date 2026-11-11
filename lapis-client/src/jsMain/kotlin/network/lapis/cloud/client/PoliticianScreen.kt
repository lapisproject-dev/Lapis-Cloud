package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
import org.w3c.dom.HTMLElement

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
 * caller with no rating on a given politician simply sees both thumbs unpressed, and zero
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
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Politiker"), intro = PageIntros.forRoute(Routes.POLITICIANS))
    // V1.9.301: "Politiker ernennen" is the one primary action of the page; its form opens collapsed under the header (R36B).
    val grantFormHost = if (canBoard) root.vPanel(spacing = 6) else null

    // ---- Top-Politiker (dashboard widget, top of screen) ---------------------------------------
    root.h2(tr("Top-Politiker")) { addCssClass("h5") }
    val topPanel = root.vPanel(spacing = 4)
    topPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    // ---- Feature-disabled banner (shown in place of the read-heavy area below when the primary
    // gate probe fails -- see file KDoc "The politicianRankingEnabled gate") -----------------------
    val disabledBanner = root.vPanel(spacing = 4) { addCssClasses("border rounded p-3 bg-body-tertiary") }
    disabledBanner.hide()
    var openSettings: () -> Unit = {}

    // ---- Politiker-Profile list -----------------------------------------------------------------
    root.h2(tr("Politiker-Profile")) { addCssClass("h5") }
    val listControlsRow = root.lapisToolbar()
    val includeFormerSelect =
        listControlsRow.select(
            options = listOf("false" to tr("Nur aktive Profile"), "true" to tr("Inklusive ehemaliger Profile")),
            value = "false",
            label = tr("Anzeige"),
        )
    val politiciansRefreshButton =
        listControlsRow.actionButton(
            ActionIcon.REFRESH,
            tr("Aktualisieren"),
            style = ButtonStyle.OUTLINESECONDARY,
        )
    // Name filter over the fully loaded profile list (`listPoliticians` has no paging); hidden while the list is empty.
    val politicianFilter = root.listFilterField()
    politicianFilter.setVisible(false)
    val politiciansPanel = root.vPanel(spacing = 10)

    // ---- Verwaltung (Vorstand/Administratoren, collapsed -- always reachable, see file KDoc) -----
    val boardPanel = if (canBoard) root.vPanel(spacing = 10) { addCssClasses("border rounded p-3 mt-2") } else null

    // ---- Einstellungen (politicianRankingEnabled toggle, collapsed -- independent of the gate above, the
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

    var loadedPoliticians: List<PoliticianProfileDto> = emptyList()
    var reloadPoliticians: () -> Unit = {}

    // Weight repainters of the currently rendered cards, keyed by memberId (the shared LTR pool makes a single rating
    // change every politician's weights, see refreshWeights).
    val weightUpdaters = mutableMapOf<String, (PoliticianProfileDto) -> Unit>()
    var weightsGeneration = 0

    /**
     * After a rating change: the LTR pool is shared and split by ratio, so the weights (and ranks) of EVERY politician move. Reloads the
     * Top list and repaints the weight figures of all rendered cards in place (open editors and rating state stay untouched).
     */
    fun refreshWeights() {
        loadTopPoliticians()
        val includeFormer = includeFormerSelect.value == "true"
        val generation = ++weightsGeneration
        AppScope.launch {
            val fresh = guarded { rpcService<IPoliticianService>().listPoliticians(includeFormer) } ?: return@launch
            // A newer rating change or a list reload (e.g. toggled "show former") has overtaken this answer: drop it. It only repaints the weights
            // and never replaces `loadedPoliticians`, so it cannot bring back the profile set of a stale filter.
            if (generation != weightsGeneration) return@launch
            fresh.forEach { weightUpdaters[it.memberId]?.invoke(it) }
        }
    }

    fun renderPoliticianCards() {
        weightUpdaters.clear()
        politiciansPanel.removeAll()
        if (loadedPoliticians.isEmpty()) {
            politicianFilter.setVisible(false)
            politiciansPanel.p(tr("Noch keine Politiker-Profile vorhanden.")) { addCssClasses("text-muted small") }
            return
        }
        politicianFilter.setVisible(true)
        val visible = politicianFilter.apply(loadedPoliticians, nameOf = { it.displayName })
        if (visible.isEmpty()) {
            politiciansPanel.p(gettext("Kein Eintrag passt zu \"%1\".", politicianFilter.term)) { addCssClasses("text-muted") }
            return
        }
        visible.forEach { politician ->
            renderPoliticianCard(politiciansPanel, politician, canBoard, currentMemberId, onChanged = {
                reloadPoliticians()
            }, weightUpdaters = weightUpdaters, onRatingChanged = { refreshWeights() })
        }
    }
    politicianFilter.subscribe { renderPoliticianCards() }

    fun loadPoliticians() {
        weightsGeneration++
        disabledBanner.hide()
        topPanel.show()
        politiciansPanel.show()
        politiciansPanel.removeAll()
        politiciansPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        val includeFormer = includeFormerSelect.value == "true"
        AppScope.launch {
            val politicians =
                loadPoliticiansOrShowBanner(includeFormer, topPanel, politiciansPanel, disabledBanner, canAdmin, canTreasury) {
                    openSettings()
                }
                    ?: return@launch
            loadedPoliticians = politicians
            renderPoliticianCards()
            loadTopPoliticians()
        }
    }

    reloadPoliticians = ::loadPoliticians
    politiciansRefreshButton.onClick { loadPoliticians() }
    includeFormerSelect.subscribe { loadPoliticians() }

    loadPoliticians()

    if (grantFormHost != null) {
        var members: List<MemberSummaryDto>? = null
        // `build` is synchronous: the member list is loaded before the form may open.
        collapsibleCreateForm<Unit>(
            actionSlot = header.actionSlot,
            formHost = grantFormHost,
            buttonLabel = tr("Politiker ernennen"),
            formId = "lapis-politician-grant",
            beforeOpen = { proceed ->
                if (members != null) {
                    proceed()
                } else {
                    AppScope.launch {
                        val loaded = guarded { rpcService<IMemberService>().listMembers() }
                        if (loaded != null) {
                            members = loaded
                            proceed()
                        }
                    }
                }
            },
        ) { _, close ->
            renderGrantForm(this, members.orEmpty(), onCompleted = {
                members = null
                close(true)
                loadPoliticians()
            }, close)
        }
    }

    if (boardPanel != null) {
        boardPanel.disclosure(tr("Verwaltung"), "politician-admin-body", initiallyOpen = false) {
            div(tr("Sichtbar für Vorstand und Administratoren.")) { addCssClasses("text-muted small mb-2") }
            renderSnapshotForm(this) { loadPoliticians() }
        }
    }

    if (settingsPanel != null) {
        val settingsToggle =
            settingsPanel.disclosure(tr("Einstellungen"), "politician-settings-body", initiallyOpen = false) {
                div(tr("Sichtbar für Kassenwarte, Vorstand und Administratoren.")) { addCssClasses("text-muted small mb-2") }
                renderPoliticianRankingToggle(this, canAdmin) { loadPoliticians() }
            }
        // The banner button opens the section and moves the focus into it; nothing opens on its own.
        openSettings = {
            val toggleElement = settingsToggle.getElement() as? HTMLElement
            if (toggleElement?.getAttribute("aria-expanded") == "false") toggleElement.click()
            whenRendered({ document.querySelector("#politician-settings-body button") != null }) {
                (document.querySelector("#politician-settings-body button") as? HTMLElement)?.focus()
            }
        }
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
    onOpenSettings: () -> Unit,
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
                canAdmin -> tr("Sie können das Politiker-Ranking im Bereich „Einstellungen“ aktivieren.")
                canTreasury -> tr("Ein Administrator kann das Politiker-Ranking im Bereich „Einstellungen“ aktivieren.")
                else -> tr("Bitte wenden Sie sich an einen Administrator, falls Sie hierauf Zugriff benötigen.")
            },
        ) { addCssClasses("text-muted small") }
        if (canTreasury || canAdmin) {
            disabledBanner.actionButton(ActionIcon.SETTINGS, tr("Einstellungen öffnen"), style = ButtonStyle.OUTLINESECONDARY) {
                onClick { onOpenSettings() }
            }
        }
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

internal fun renderTopPoliticiansList(
    panel: SimplePanel,
    top: List<PoliticianProfileDto>,
    viewport: NarrowViewportSource = BrowserNarrowViewport,
) {
    // W5: the ranking is a real table (rank, name, the three weights) instead of a row of fixed-width cells with a label
    // repeated in every row; the labels are the column headers now.
    panel.plainDataTable(
        columns =
            listOf(
                textColumn<IndexedValue<PoliticianProfileDto>>(title = tr("Rang"), numeric = true) { "${it.index + 1}." },
                DataColumn(
                    title = tr("Politiker"),
                    primary = true,
                    cell = { cell, p ->
                        val line = cell.div(className = "d-flex align-items-center gap-2")
                        line.politicianAvatar(p.value, AvatarSize.SM)
                        line.untrustedSpan(p.value.displayName, className = "fw-bold")
                    },
                ),
                DataColumn(title = tr("Mitglieder"), numeric = true, cell = { cell, p -> cell.ltrSpan(p.value.memberTrustWeight) }),
                DataColumn(title = tr("Gäste"), numeric = true, cell = { cell, p -> cell.countSpan(p.value.guestTrustWeight) }),
                DataColumn(
                    title = tr("Gesamt"),
                    numeric = true,
                    cell = { cell, p -> cell.plainAmountSpan(p.value.combinedTrustWeight).addCssClass("fw-bold") },
                ),
            ),
        rows = top.withIndex().toList(),
        viewport = viewport,
        label = gettext("Top-Politiker"),
    )
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
    weightUpdaters: MutableMap<String, (PoliticianProfileDto) -> Unit>,
    onRatingChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.div(className = "d-flex flex-wrap align-items-center gap-2")
    headerRow.politicianAvatar(politician, AvatarSize.MD)
    // Security audit W6b follow-up round 3 (major finding A): display name/mandate text are member-editable
    // free text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(politician.displayName)) { addCssClasses("fw-bold") }
    if (currentMemberId != null && politician.memberId == currentMemberId) {
        headerRow.div(tr("(Sie)")) { addCssClasses("text-muted small") }
    }
    headerRow.statusBadge(politicianProfileStatusLabel(politician.status), politicianProfileStatusColor(politician.status))

    // Mandate text: edited inline from the header's edit button (D9, Vorstand/Administratoren only), updated in place after saving.
    val editBody = card.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    editBody.id = "politician-mandate-${politician.id}"
    editBody.hide()
    val mandateDiv = card.div("") { addCssClasses("small") }

    fun showMandate(text: String?) {
        val shown = text?.takeIf { it.isNotBlank() }
        mandateDiv.content = shown?.let { sanitizeUntrustedI18nText(it) } ?: ""
        if (shown != null) mandateDiv.show() else mandateDiv.hide()
    }
    showMandate(politician.mandateText)

    if (canBoard) {
        renderBoardCardHeaderActions(headerRow, editBody, politician, onChanged, ::showMandate)
    }

    card.div(
        gettext("Politiker-Status seit %1 (erteilt von %2)", formatSystemDateTime(politician.grantedAt), politician.grantedByDisplayName),
    ) {
        addCssClasses("text-muted small")
    }
    if (politician.status == PoliticianProfileStatus.FORMER && politician.revokedAt != null) {
        card.div(
            gettext(
                "Widerrufen am %1 von %2",
                formatSystemDateTime(politician.revokedAt!!),
                politician.revokedByDisplayName ?: "--",
            ),
        ) {
            addCssClasses("text-muted small")
        }
    }

    // Data-shape honesty (design review, file KDoc): three separate, non-summable-as-a-percentage
    // figures. Only memberTrustWeight is genuinely LTR-denominated and gets the ltrSpan badge.
    val weightRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val memberCell = weightRow.vPanel(spacing = 2)
    val guestCell = weightRow.vPanel(spacing = 2)
    val combinedCell = weightRow.vPanel(spacing = 2)

    fun paintWeights(p: PoliticianProfileDto) {
        memberCell.removeAll()
        memberCell.div(tr("Mitglieder-Gewicht (LTR-gewichtet)")) { addCssClasses("text-muted small") }
        memberCell.ltrSpan(p.memberTrustWeight)
        guestCell.removeAll()
        guestCell.div(tr("Gast-Gewicht (reine Stimmenzahl, ungewichtet)")) { addCssClasses("text-muted small") }
        guestCell.countSpan(p.guestTrustWeight).addCssClass("fw-bold")
        combinedCell.removeAll()
        combinedCell.div(tr("Gesamt (Summe, keine vergleichbare Einheit)")) { addCssClasses("text-muted small") }
        combinedCell.plainAmountSpan(p.combinedTrustWeight).addCssClass("fw-bold")
    }
    paintWeights(politician)
    weightUpdaters[politician.memberId] = ::paintWeights

    // castRating/retractRating require status == ACTIVE server-side -- only RENDERED once active,
    // never shown-then-failed on a FORMER profile (same posture CrowdfundingScreen.kt's
    // effectiveStatus == APPROVED gate already establishes for its own reaction controls).
    if (politician.status == PoliticianProfileStatus.ACTIVE) {
        renderRatingControls(card, politician, onRatingChanged)
    } else {
        card.div(politicianRatingSummary(politician)) { addCssClasses("small text-muted") }
    }

    renderWeightHistorySection(card, politician)
}

/** The breakdown line below the rating buttons (the buttons carry only the combined number). */
internal fun politicianRatingSummary(politician: PoliticianProfileDto): String =
    gettext(
        "Mitglieder: %1 positiv, %2 negativ · Gäste: %3 positiv, %4 negativ",
        politician.memberLikeCount,
        politician.memberDislikeCount,
        politician.guestLikeCount,
        politician.guestDislikeCount,
    )

private fun ratingGroupName(name: String): String = sanitizeUntrustedI18nText(gettext("Bewertung von %1", name))

private fun positiveName(
    name: String,
    n: Int,
): String = sanitizeUntrustedI18nText(gettext("%1 positiv bewerten (%2 positive Bewertungen)", name, n))

private fun negativeName(
    name: String,
    n: Int,
): String = sanitizeUntrustedI18nText(gettext("%1 negativ bewerten (%2 negative Bewertungen)", name, n))

/**
 * Rating (ACTIVE profiles only): two thumb toggles, each with the combined number of members and guests, plus "Bewertung zurückziehen" while
 * the caller has a rating. A click on the unpressed thumb casts (the server upserts, so switching thumbs works); a click on the pressed
 * thumb retracts. Everything updates in place from a fresh profile read and `getMyRating`; a failed call leaves the state as the server has it.
 */
private fun renderRatingControls(
    card: SimplePanel,
    politician: PoliticianProfileDto,
    onRatingChanged: () -> Unit,
) {
    var current = politician
    var myRating: PoliticianReactionValue? = null
    val name = politician.displayName

    val ratingRow = card.div(className = "border-top pt-2 mt-1")
    val summary = ratingRow.div(politicianRatingSummary(current)) { addCssClasses("small text-muted mb-2") }
    val group = ratingRow.actionGroup()
    group.setAttribute("role", "group")
    group.setAttribute("aria-label", ratingGroupName(name))

    fun positives() = current.memberLikeCount + current.guestLikeCount

    fun negatives() = current.memberDislikeCount + current.guestDislikeCount

    val like =
        CountedToggleButton(
            ActionIcon.RATE_POSITIVE,
            ButtonStyle.OUTLINESUCCESS,
            ButtonStyle.SUCCESS,
            positives(),
            positiveName(name, positives()),
            pressed = false,
        )
    group.add(like.button)
    val dislike =
        CountedToggleButton(
            ActionIcon.RATE_NEGATIVE,
            ButtonStyle.OUTLINEDANGER,
            ButtonStyle.DANGER,
            negatives(),
            negativeName(name, negatives()),
            pressed = false,
        )
    group.add(dislike.button)
    val retractButton =
        group.actionButton(
            ActionIcon.UNDO,
            tr("Bewertung zurückziehen"),
            style = ButtonStyle.OUTLINESECONDARY,
            labelMode = ActionLabelMode.RESPONSIVE,
            accessibleName = sanitizeUntrustedI18nText(gettext("Bewertung zurückziehen: %1", name)),
        )
    retractButton.hide()

    fun setButtonsDisabled(disabled: Boolean) {
        like.button.disabled = disabled
        dislike.button.disabled = disabled
        retractButton.disabled = disabled
    }

    fun paint() {
        like.update(positiveName(name, positives()), myRating == PoliticianReactionValue.LIKE, positives())
        dislike.update(negativeName(name, negatives()), myRating == PoliticianReactionValue.DISLIKE, negatives())
        summary.content = politicianRatingSummary(current)
        if (myRating == null) retractButton.hide() else retractButton.show()
    }

    /** Reads the caller's own rating (and, after a change, the fresh counts) from the server, then repaints. */
    suspend fun refreshFromServer(withCounts: Boolean) {
        if (withCounts) {
            val fresh = guarded { rpcService<IPoliticianService>().getPoliticianProfile(politician.memberId) }
            if (fresh != null) current = fresh
        }
        val ratings = guarded { rpcService<IPoliticianService>().getMyRating(politician.memberId) }
        if (ratings != null) myRating = ratings.firstOrNull()?.value
        paint()
    }

    // R29 (W4d): `runGuardedAction(null)` -- the double-click protection is the manual `setButtonsDisabled(true)`
    // ABOVE it (all three rating buttons, not just the one clicked), same idiom `BankAccountsScreen.kt`/
    // `BankStatementImportScreen.kt` already use for a multi-widget guard [runGuardedAction] itself cannot express.
    fun castRating(value: PoliticianReactionValue) {
        setButtonsDisabled(true)
        runGuardedAction(null) {
            try {
                val result = guarded { rpcService<IPoliticianService>().castRating(politician.memberId, value) }
                if (result != null) {
                    notifySuccess(tr("Bewertung gespeichert."))
                    refreshFromServer(withCounts = true)
                    onRatingChanged()
                }
            } finally {
                setButtonsDisabled(false)
            }
        }
    }

    fun retract() {
        setButtonsDisabled(true)
        runGuardedAction(null) {
            try {
                val result = guarded { rpcService<IPoliticianService>().retractRating(politician.memberId) }
                if (result != null) {
                    notifySuccess(tr("Bewertung zurückgezogen."))
                    refreshFromServer(withCounts = true)
                    onRatingChanged()
                }
            } finally {
                setButtonsDisabled(false)
            }
        }
    }

    fun toggle(value: PoliticianReactionValue) {
        if (myRating == value) retract() else castRating(value)
    }

    like.button.onClick { toggle(PoliticianReactionValue.LIKE) }
    dislike.button.onClick { toggle(PoliticianReactionValue.DISLIKE) }
    retractButton.onClick { retract() }

    // Disabled until the own rating is known: a click before that would see myRating == null and cast instead of retract.
    setButtonsDisabled(true)
    AppScope.launch {
        try {
            refreshFromServer(withCounts = false)
        } finally {
            setButtonsDisabled(false)
        }
    }
}

/**
 * Vorstand/Administrator actions in the card header (ACTIVE profiles only): "Mandatstext bearbeiten" (D9, opens the mandate form inline in
 * [editBody]) and "Politiker-Status widerrufen" (Tier 3 "Löschend", see [politicianRevokeConfirmDialog]), last in the group. A FORMER
 * profile shows neither -- it can only be reactivated via "Politiker ernennen" at the top of the page (the same idempotent-upsert entry
 * point as a first-time grant).
 */
private fun renderBoardCardHeaderActions(
    headerRow: Container,
    editBody: SimplePanel,
    politician: PoliticianProfileDto,
    onChanged: () -> Unit,
    showMandate: (String?) -> Unit,
) {
    if (politician.status == PoliticianProfileStatus.FORMER) {
        headerRow.div(
            tr("Dieses Profil ist ehemalig -- über „Politiker ernennen“ oben kann es erneut aktiviert werden."),
        ) { addCssClasses("text-muted small w-100") }
        return
    }
    val name = politician.displayName
    var currentMandate = politician.mandateText
    lateinit var editButton: Button

    fun closeEditor(returnFocus: Boolean) {
        editBody.removeAll()
        editBody.hide()
        editButton.setAttribute("aria-expanded", "false")
        if (returnFocus) (editButton.getElement() as? HTMLElement)?.focus()
    }

    fun openEditor() {
        editBody.removeAll()
        editBody.show()
        editButton.setAttribute("aria-expanded", "true")
        // R24 (W4d): the form grammar -- a single optional field, saved on its own button.
        val mandateForm = editBody.lapisForm()
        val mandateField = mandateForm.textAreaField(label = tr("Mandatstext"), rows = 2, value = currentMandate)
        val mandateSaveButton = newActionButton(ActionIcon.SAVE, tr("Mandatstext speichern"), style = ButtonStyle.OUTLINESECONDARY)
        val cancelButton =
            newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY).apply { onClick { closeEditor(true) } }
        mandateForm.buttons(primary = mandateSaveButton, cancel = cancelButton)
        mandateSaveButton.onClick {
            mandateForm.submit(mandateSaveButton) {
                val text = mandateField.value.trim().takeIf { it.isNotBlank() }
                val result = guarded { rpcService<IPoliticianService>().updateMandateText(politician.memberId, text) }
                if (result != null) {
                    notifySuccess(tr("Mandatstext aktualisiert."))
                    currentMandate = result.mandateText
                    showMandate(result.mandateText)
                    closeEditor(true)
                }
            }
        }
    }

    val actions = headerRow.actionGroup()
    actions.addCssClass("ms-auto")
    run {
        editButton =
            actions.actionButton(
                ActionIcon.EDIT,
                tr("Mandatstext bearbeiten"),
                style = ButtonStyle.OUTLINESECONDARY,
                labelMode = ActionLabelMode.RESPONSIVE,
                accessibleName = sanitizeUntrustedI18nText(gettext("Mandatstext bearbeiten: %1", name)),
            )
        editButton.setAttribute("aria-expanded", "false")
        editButton.setAttribute("aria-controls", "politician-mandate-${politician.id}")
        editButton.onClick { if (editBody.visible) closeEditor(false) else openEditor() }

        val revokeButton =
            actions.actionButton(
                ActionIcon.REVOKE,
                tr("Politiker-Status widerrufen"),
                style = ButtonStyle.OUTLINEDANGER,
                labelMode = ActionLabelMode.RESPONSIVE,
                accessibleName = sanitizeUntrustedI18nText(gettext("Politiker-Status widerrufen: %1", name)),
            )
        revokeButton.onClick {
            politicianRevokeConfirmDialog(politician.displayName) {
                // R29 (W4d): the confirm dialog is one-shot, but `runGuardedAction` also disables the button itself.
                runGuardedAction(revokeButton) {
                    val result = guarded { rpcService<IPoliticianService>().revokePoliticianStatus(politician.memberId) }
                    if (result != null) {
                        notifySuccess(gettext("Politiker-Status von %1 widerrufen.", result.displayName))
                        onChanged()
                    }
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
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        newActionButton(ActionIcon.REVOKE, tr("Unwiderruflich widerrufen"), style = ButtonStyle.DANGER).apply {
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

internal fun renderWeightHistoryTable(
    panel: SimplePanel,
    history: List<PoliticianWeightSnapshotDto>,
    viewport: NarrowViewportSource = BrowserNarrowViewport,
) {
    // W5: a real table instead of the hand-built pseudo-table (see PseudoTableGoldenDomTest for the unchanged cells).
    panel.plainDataTable(
        columns =
            listOf(
                dateColumn<PoliticianWeightSnapshotDto>(title = tr("Monat"), numeric = false, primary = true) { it.periodMonth },
                DataColumn(title = tr("Mitglieder-Gewicht"), numeric = true, cell = { cell, s -> cell.ltrSpan(s.memberTrustWeight) }),
                DataColumn(
                    title = tr("Gast-Gewicht"),
                    numeric = true,
                    cell = { cell, s -> cell.countSpan(s.guestTrustWeight).addCssClass("small") },
                ),
                DataColumn(
                    title = tr("Gesamt"),
                    numeric = true,
                    cell = { cell, s -> cell.plainAmountSpan(s.combinedTrustWeight).addCssClasses("fw-bold small") },
                ),
                systemDateTimeColumn<PoliticianWeightSnapshotDto>(
                    title = tr("Berechnet"),
                    numeric = false,
                    cssClasses = "text-muted small",
                ) { it.computedAt },
            ),
        rows = history,
        viewport = viewport,
        label = gettext("Gewichtsverlauf"),
    )
}

// ================================================================================================
// Verwaltung: Politiker-Status erteilen + Gewichts-Snapshot auslösen
// ================================================================================================

private fun renderGrantForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
    onCompleted: () -> Unit,
    close: (saved: Boolean) -> Unit,
): FormSnapshot {
    if (members.isEmpty()) {
        root.p(tr("Keine Mitglieder vorhanden.")) { addCssClasses("text-muted small") }
        root.add(collapseCancelButton(close))
        return FormSnapshot { emptyList() }
    }
    // R24/R24B (W4d): the form grammar -- Mitglied (required select) + Mandatstext (optional textArea).
    val form = root.lapisForm()
    val memberField =
        form.searchableSelectField(
            label = tr("Mitglied"),
            options = untrustedOptions(members.map { it.id to it.displayName }),
            required = true,
            requiredMessage = tr("Bitte ein Mitglied auswählen."),
            hint =
                gettext(
                    "Ein Foto lädt die Person selbst unter „Meine Daten“ hoch. Es erscheint hier nur, wenn sie es dort " +
                        "öffentlich freigibt, sonst zeigen wir ihre Initialen.",
                ),
        )
    val mandateField = form.textAreaField(label = tr("Mandatstext (optional)"), rows = 2)
    val grantButton = newActionButton(ActionIcon.ADD, tr("Erteilen / Aktualisieren"), ButtonStyle.PRIMARY)
    form.buttons(primary = grantButton, cancel = collapseCancelButton(close))

    grantButton.onClick {
        if (!form.validateAndReport()) return@onClick
        val member = members.find { it.id == memberField.value } ?: return@onClick
        val mandateText = mandateField.value.trim().takeIf { it.isNotBlank() }

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
            form.runBusy(grantButton) {
                val result = guarded { rpcService<IPoliticianService>().grantPoliticianStatus(member.id, mandateText) }
                if (result != null) {
                    notifySuccess(gettext("Politiker-Status für %1 erteilt.", result.displayName))
                    onCompleted()
                }
            }
        }
    }
    return form.snapshot()
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
    // R24 (W4d): migrated to the form grammar -- a native date input type is a named wave of its own
    // (K1, see ui-ux-guideline.adoc), so this stays a text field with the example in the hint, like
    // every other date-shaped field in this client (e.g. `BoardMembershipScreen.kt`'s "Seit").
    val form = root.lapisForm()
    val monthField =
        form.localDateField(
            label = tr("Monat (Tag wird ignoriert)"),
            required = true,
        )
    val snapshotButton = Button(tr("Snapshot auslösen"), style = ButtonStyle.SECONDARY)
    form.buttons(primary = snapshotButton)

    snapshotButton.onClick {
        form.submit(snapshotButton) {
            val periodMonth = checkNotNull(monthField.localDateOrNull())
            val result = guarded { rpcService<IPoliticianService>().snapshotWeights(periodMonth) }
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
                                        "Vorstand und Administratoren können den Politiker-Status erteilen und widerrufen.",
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
                        // R29 (W4d): the confirm dialog is one-shot, but `runGuardedAction` also disables the button itself.
                        runGuardedAction(toggleButton) {
                            val result =
                                guarded {
                                    rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                                        settings.toInputWithPoliticianRankingEnabled(newValue),
                                    )
                                }
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
    // Audit-Fund M2: ein Feld umschalten, alles andere über `toInput()` unverändert mitschicken --
    // statt 26 Felder von Hand abzuschreiben. Die ausführliche Historie der fünf Felder, die in
    // dieser Kopie über die Wellen hinweg einzeln vergessen wurden (donationIncomeAccountId,
    // eventIncomeAccountId/-Sphere, travelExpenseAccountId, volunteerAllowanceAccountId,
    // isKleinunternehmer, receivablesAccountId/payablesAccountId/receivableDunningEnabled), steht
    // jetzt im KDoc von `toInput` -- diese Funktion kann sie strukturell nicht mehr verlieren.
    toInput().copy(politicianRankingEnabled = newValue)

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

/** [typeBadge] grammar: a member's positive/negative rating (LIKE/DISLIKE) is a fixed classification, not a progressing
 * status -- outline variant, same convention `CrowdfundingScreen.kt`'s own reaction-value table
 * already establishes for its (distinct, same-named-literal) enum. */
fun politicianReactionValueLabel(value: PoliticianReactionValue): String =
    when (value) {
        PoliticianReactionValue.LIKE -> gettext("Positiv")
        PoliticianReactionValue.DISLIKE -> gettext("Negativ")
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

// ================================================================================================
// Avatar
// ================================================================================================

/** Size of a politician avatar: [SM] in the Top-Politiker table, [MD] in the profile card header. */
internal enum class AvatarSize(
    val css: String,
) {
    SM("lapis-avatar-sm"),
    MD("lapis-avatar-md"),
}

/**
 * The politician's photo while it is publicly released ([PoliticianProfileDto.photoUrl], decided live on the server), otherwise an initials
 * avatar. Decorative either way (the name stands next to it): `alt=""` / `aria-hidden`. The URL is only ever set as `src`.
 */
internal fun Container.politicianAvatar(
    p: PoliticianProfileDto,
    size: AvatarSize,
) {
    val url = p.photoUrl
    if (url != null) {
        image(url, "").apply { addCssClasses("lapis-public-card-avatar ${size.css}") }
    } else {
        untrustedDiv(initialsOf(p.displayName), className = "lapis-public-card-avatar lapis-public-card-initials ${size.css}")
            .setAttribute("aria-hidden", "true")
    }
}
