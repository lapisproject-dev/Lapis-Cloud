package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Overflow
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
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
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionSettingsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAuctionService

/**
 * LTR-Wirtschaft UI wave, screen 3 of 5 -- "Auktion". Self-contained domain ([IAuctionService]):
 * English proxy-bid auction with second-price settlement, optional Sofortkauf, an ADMIN-only
 * `auctionEnabled` legal-disclaimer gate, and an ADMIN-only `auctionMaxValueLtr` cap. See
 * `21-auction.kuml.kts` file header and [IAuctionService] class KDoc for the full fachlich model
 * this screen surfaces.
 *
 * **Role gating** (verified against `AuctionService.kt`'s actual `requireRole`/
 * `requireActiveMembership`/`requireAuctionEnabled` call sites, not guessed from method names --
 * see `Routes.AUCTION` KDoc for the route-level `requireAuth` reasoning):
 * - [IAuctionService.createListing]/[IAuctionService.placeBid]/[IAuctionService.buyNow]/
 *   [IAuctionService.settleAuction] -- MEMBER+, additionally must be ACTIVE
 *   (`requireActiveMembership` INSIDE the server transaction, not reachable as an `AccountRole`
 *   predicate -- same reasoning `LtrLedgerScreen.kt`/`CrowdfundingScreen.kt` already document for
 *   their own ACTIVE-gated writes). [placeBid]/[buyNow] additionally reject the auction's own seller
 *   server-side -- mirrored here as a client-side UX hint (the bid/Sofortkauf controls are simply
 *   not rendered for the seller's own listing), never the actual security boundary.
 * - [IAuctionService.getAuction]/[IAuctionService.listAuctions]/[IAuctionService.listMyBids]/
 *   [IAuctionService.listMyAuctions] -- any authenticated member.
 * - [IAuctionService.getAuctionComplianceDisclaimer]/[IAuctionService.enableAuction]/
 *   [IAuctionService.disableAuction]/[IAuctionService.setAuctionMaxValueLtr]/
 *   [IAuctionService.getAuctionSettings] -- `current.requireRole(AccountRole.ADMIN)` server-side,
 *   uniformly. Gated here as `canAdmin`, inside a visually separated "Verwaltung" panel (design
 *   decision D3's staged-disclosure principle). Deliberately **not** gated by `auctionEnabled`
 *   itself (see [IAuctionService] class KDoc "The `auctionEnabled` gate") -- this panel must stay
 *   reachable and functional even while the feature is switched off, since it is the only path an
 *   ADMIN has to switch it back on.
 *
 * **The `auctionEnabled` first-load gate**: [IAuctionService.listAuctions] is the one call this
 * screen routes OUTSIDE `guarded()`'s generic wrapper -- a [ConflictException] here means
 * `auctionEnabled == false`, and is caught directly to render a friendly inline banner in place of
 * the auction-browsing section, instead of the generic "im Konflikt" toast. Every OTHER exception
 * type still routes through `guarded()`'s own mapping (session expiry, forbidden, ...) -- this file
 * does not duplicate that table; it re-dispatches any non-`ConflictException` failure into a
 * throwing `guarded { }` block so `AppState.guarded` remains the single source of truth for that
 * mapping (see [loadAuctionsOrShowBanner]). [IAuctionService.listMyBids]/[IAuctionService.listMyAuctions]
 * get the identical quiet-notice treatment via [loadOrShowDisabledNotice] -- both sit behind the same
 * uniform `requireAuctionEnabled` gate as `listAuctions`, so routing them through plain `guarded { }`
 * would leave their "Wird geladen …" placeholder stuck forever plus fire redundant duplicate error
 * toasts on top of the one banner that already explains the disabled state (found live in the browser
 * during this wave's independent verification). The remaining, genuinely mutating actions
 * (createListing/placeBid/buyNow/settleAuction) are NOT specially wrapped -- if the feature is
 * disabled they simply surface the ordinary `guarded()` `ConflictException` toast, same "loose UX
 * affordance, not the security boundary" posture this wave already established for ACTIVE-gating; a
 * one-off toast on a deliberate user action is a different situation from a silently stuck first-load
 * placeholder.
 *
 * **`maxBidLtr` visibility (Kare's restraint)**: [AuctionDto] never carries any OTHER bidder's
 * `maxBidLtr` -- only `currentPriceLtr`/`currentLeaderDisplayName`/`leaderIsMe` are ever rendered on
 * an auction card. [AuctionBidDto.maxBidLtr] is shown ONLY inside the "Meine Gebote" section
 * ([IAuctionService.listMyBids], the caller's own bids), never anywhere else on this screen.
 *
 * **Design decision D6 (staleness)**: `currentPriceLtr`/`currentLeaderDisplayName` are read at
 * fetch time, with no live push anywhere in this codebase. This screen shows an absolute
 * "Preisstand: HH:MM:SS Uhr" wall-clock timestamp (not a relative "vor X Sekunden" counter) next to
 * a manual refresh button -- a relative counter would itself silently go stale the instant it stops
 * being recomputed, and no `setInterval`/timer-with-cleanup infrastructure exists anywhere in this
 * client to safely keep one ticking across a route change (see `Routing.kt`'s `show()`: a screen
 * has no unmount hook). An absolute timestamp is honest about "as of when" without that risk.
 * [placeBid]'s confirm dialog additionally restates the price *as last fetched* and states plainly
 * that the bid is evaluated against the live price at confirmation time, not the displayed one.
 *
 * **Confirm-dialog tier (design decision D4)**: [IAuctionService.createListing] uses the plain,
 * neutral-framed [confirmDialog] (Tier 1 "Kostenpflichtig" -- explicitly named as a Tier 1 example
 * in the design review). [IAuctionService.placeBid]/[IAuctionService.buyNow] use bespoke,
 * unmissable-danger-framed modals ([placeBidConfirmDialog]/[buyNowConfirmDialog], Tier 2
 * "Endgültig") -- a leading bid immediately reserves real LTR, and Sofortkauf is immediately
 * binding. [IAuctionService.settleAuction] gets no confirm dialog: it is a deterministic
 * "resolve what already happened" trigger (only rendered/enabled once the auction has already
 * ended), not a discretionary financial decision -- considered and rejected, same posture
 * `AuctionService.kt`'s own KDoc documents for this exact method. [IAuctionService.disableAuction]
 * uses a bespoke Tier 3 "Löschend"-style modal ([auctionDisableConfirmDialog]) that names exactly
 * what freezes (every in-flight OPEN auction, discovered by reading `requireAuctionEnabled()`'s
 * uniform call-site coverage in `AuctionService.kt`). [IAuctionService.enableAuction] uses the
 * dedicated disclaimer-echo modal ([auctionEnableDisclaimerModal]) -- the wave's most
 * security-sensitive new interaction: `version`/`sha256` are held as read-only local values from
 * the JUST-fetched [AuctionComplianceDisclaimerDto], never rendered as editable fields, and resent
 * verbatim. [IAuctionService.setAuctionMaxValueLtr] uses a light Tier 1 [confirmDialog]. Every
 * non-idempotent button disables itself for the duration of the in-flight request (double-submit
 * protection, `LedgerScreen.postDirectButton`'s idiom); [placeBid]/[buyNow] additionally show a
 * small "Wird ausgeführt …" busy-affordance next to the button (design decision D5) since a bare
 * disabled button gives no feedback that real LTR is being committed.
 *
 * **Empty states (D10)**: zero auctions renders "Noch keine Auktionen vorhanden.", zero own bids
 * "Sie haben noch keine Gebote abgegeben.", zero own listings "Sie haben noch keine Auktionen
 * eingestellt." -- never a blank list.
 *
 * Every LTR amount is rendered via [ltrSpan]/[formatLtr] (`Money.kt`, D2) -- never hand-formatted.
 * [AuctionBidResultDto]/[AuctionDto] figures returned by the server are shown verbatim, never
 * re-summed or re-derived client-side.
 */
fun renderAuctionScreen(container: SimplePanel) {
    val canAdmin = AppState.hasRole(AccountRole.ADMIN)
    val currentMemberId = AppState.session?.memberId

    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    val header = root.pageHeader(tr("Auktion"))

    // ---- Neues Angebot (V1.9.49, R36B: collapsed behind the header button; the form incl. D3's balance line lives in
    // AuctionCreateListingForm.kt) -- always visible, like the form was: the server's 409 on a disabled auction is shown by `guarded`.
    val listingHost = root.vPanel(spacing = 6)

    // ---- Auktionen (browse) ---------------------------------------------------------------
    root.h2(tr("Auktionen")) { addCssClass("h5") }
    val staleRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val staleLabel = staleRow.div(tr("Wird geladen …")) { addCssClasses("text-muted small flex-grow-1") }
    val auctionsRefreshButton = staleRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val filterRow = root.lapisToolbar()
    val statusFilterOptions =
        listOf("" to tr("Alle (persistierter Status)")) + AuctionStatus.entries.map { it.name to auctionStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = tr("Filter: Status (persistiert)"))
    val disabledBanner = root.vPanel(spacing = 4) { addCssClasses("border rounded p-3 bg-body-tertiary") }
    disabledBanner.hide()
    val auctionsPanel = root.vPanel(spacing = 10)

    // ---- Meine Gebote ------------------------------------------------------------------------
    root.h2(tr("Meine Gebote")) { addCssClass("h5") }
    val myBidsPanel = root.vPanel(spacing = 6)

    // ---- Meine Auktionen (als Verkäufer) --------------------------------------------------
    root.h2(tr("Meine Auktionen (als Verkäufer)")) { addCssClass("h5") }
    val myAuctionsPanel = root.vPanel(spacing = 10)

    // ---- Verwaltung (ADMIN only, D3 staged disclosure) ----------------------------------------
    val adminPanel = if (canAdmin) root.vPanel(spacing = 10) { addCssClasses("border rounded p-3 mt-2") } else null

    fun loadAuctions() {
        disabledBanner.hide()
        auctionsPanel.show()
        auctionsPanel.removeAll()
        auctionsPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        staleLabel.content = tr("Wird geladen …")
        val statusFilter = parseOptionalEnum<AuctionStatus>(statusFilterSelect.value)
        AppScope.launch {
            val auctions = loadAuctionsOrShowBanner(statusFilter, auctionsPanel, disabledBanner) ?: return@launch
            val fetchedAt = organizationNow()
            staleLabel.content =
                gettext("Preisstand: %1:%2:%3 Uhr", pad2(fetchedAt.hour), pad2(fetchedAt.minute), pad2(fetchedAt.second))
            auctionsPanel.removeAll()
            if (auctions.isEmpty()) {
                auctionsPanel.p(tr("Noch keine Auktionen vorhanden.")) { addCssClasses("text-muted small") }
            } else {
                auctions.forEach { auction ->
                    renderAuctionCard(auctionsPanel, auction, currentMemberId) {
                        loadAuctions()
                        loadMyBidsInto(myBidsPanel)
                        loadMyAuctionsInto(myAuctionsPanel, currentMemberId)
                    }
                }
            }
        }
    }

    auctionsRefreshButton.onClick { loadAuctions() }
    statusFilterSelect.subscribe { loadAuctions() }

    collapsibleCreateForm<Unit>(
        actionSlot = header.actionSlot,
        formHost = listingHost,
        buttonLabel = tr("Neues Angebot"),
        formId = "auction-listing-create",
    ) { _, close ->
        renderCreateListingForm(close) {
            loadAuctions()
            loadMyAuctionsInto(myAuctionsPanel, currentMemberId)
        }
    }

    loadAuctions()
    loadMyBidsInto(myBidsPanel)
    loadMyAuctionsInto(myAuctionsPanel, currentMemberId)

    if (adminPanel != null) {
        adminPanel.h2(tr("Verwaltung")) { addCssClass("h5") }
        adminPanel.div(tr("Sichtbar für ADMIN.")) { addCssClasses("text-muted small mb-2") }
        // Enabling/disabling flips the same `requireAuctionEnabled` gate `listMyBids`/`listMyAuctions`
        // sit behind (see file KDoc) -- without refreshing them here too, an ADMIN who just enabled
        // the auction would keep seeing "Die Auktion ist derzeit deaktiviert." in both sections until
        // a manual page reload. Found live in the browser during this wave's verification.
        renderAdminSection(adminPanel) {
            loadAuctions()
            loadMyBidsInto(myBidsPanel)
            loadMyAuctionsInto(myAuctionsPanel, currentMemberId)
        }
    }
}

/**
 * The one call this screen routes outside `guarded()`'s generic wrapper (see file KDoc). Returns
 * the loaded list on success. On a [ConflictException] (`auctionEnabled == false`), clears
 * [auctionsPanel], shows [disabledBanner] with role-appropriate copy, and returns `null`. On any
 * OTHER exception, re-dispatches into a throwing `guarded { }` block (so `AppState.guarded`'s own
 * mapping -- session expiry, forbidden, not-found, ... -- fires exactly as it would for every other
 * call site in this app) and returns `null`.
 */
private suspend fun loadAuctionsOrShowBanner(
    statusFilter: AuctionStatus?,
    auctionsPanel: SimplePanel,
    disabledBanner: SimplePanel,
): List<AuctionDto>? =
    try {
        rpcService<IAuctionService>().listAuctions(statusFilter)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        auctionsPanel.removeAll()
        auctionsPanel.hide()
        disabledBanner.removeAll()
        disabledBanner.show()
        val canAdmin = AppState.hasRole(AccountRole.ADMIN)
        disabledBanner.div(tr("Die Auktion ist derzeit deaktiviert.")) { addCssClass("fw-bold") }
        disabledBanner.div(
            if (canAdmin) {
                tr(
                    "Ein ADMIN kann die Auktion im Abschnitt \"Verwaltung\" unten aktivieren -- dafür muss zunächst der " +
                        "aktuelle rechtliche Hinweistext gelesen und bestätigt werden.",
                )
            } else {
                tr("Bitte wenden Sie sich an ein ADMIN-Mitglied, falls Sie hierauf Zugriff benötigen.")
            },
        ) { addCssClasses("text-muted small") }
        null
    } catch (e: Throwable) {
        auctionsPanel.removeAll()
        auctionsPanel.p(tr("Auktionen konnten nicht geladen werden.")) { addCssClasses("text-muted small") }
        guarded<Unit> { throw e }
        null
    }

/**
 * Same "friendly banner instead of a generic toast" treatment [loadAuctionsOrShowBanner] gives the
 * main browse list -- [IAuctionService.listMyBids]/[IAuctionService.listMyAuctions] sit behind the
 * exact same uniform `requireAuctionEnabled` server-side gate, so a disabled auction fails them with
 * the identical [ConflictException] every time the main list also fails with it. Routing them through
 * plain `guarded { }` instead would leave [panel]'s "Wird geladen …" placeholder stuck forever (the
 * `?: return@launch` bails before ever clearing it) while also firing a second/third redundant error
 * toast on top of the one banner that already explains the situation once. Found live in the browser
 * during this wave's independent verification, not by the review/security loops (neither runs real
 * DOM), fixed the same day.
 */
private suspend fun <T> loadOrShowDisabledNotice(
    panel: SimplePanel,
    disabledText: String,
    call: suspend () -> List<T>,
): List<T>? =
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        panel.removeAll()
        panel.p(disabledText) { addCssClasses("text-muted small") }
        null
    } catch (e: Throwable) {
        panel.removeAll()
        guarded<Unit> { throw e }
        null
    }

private fun loadMyBidsInto(panel: SimplePanel) {
    panel.removeAll()
    panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    AppScope.launch {
        val bids =
            loadOrShowDisabledNotice(panel, tr("Die Auktion ist derzeit deaktiviert.")) {
                rpcService<IAuctionService>().listMyBids()
            } ?: return@launch
        panel.removeAll()
        if (bids.isEmpty()) {
            panel.p(tr("Sie haben noch keine Gebote abgegeben.")) { addCssClasses("text-muted small") }
        } else {
            renderMyBidsTable(panel, bids)
        }
    }
}

private fun loadMyAuctionsInto(
    panel: SimplePanel,
    currentMemberId: String?,
) {
    panel.removeAll()
    panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    AppScope.launch {
        val auctions =
            loadOrShowDisabledNotice(panel, tr("Die Auktion ist derzeit deaktiviert.")) {
                rpcService<IAuctionService>().listMyAuctions()
            } ?: return@launch
        panel.removeAll()
        if (auctions.isEmpty()) {
            panel.p(tr("Sie haben noch keine Auktionen eingestellt.")) { addCssClasses("text-muted small") }
        } else {
            auctions.forEach { auction ->
                renderAuctionCard(panel, auction, currentMemberId) {
                    loadMyAuctionsInto(panel, currentMemberId)
                }
            }
        }
    }
}

// ================================================================================================
// Verwaltung (ADMIN): Einstellungen, Aktivieren/Deaktivieren, Wertobergrenze
// ================================================================================================

private fun renderAdminSection(
    root: SimplePanel,
    onSettingsChanged: () -> Unit,
) {
    val settingsPanel = root.vPanel(spacing = 4)
    settingsPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadSettings() {
        settingsPanel.removeAll()
        settingsPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<IAuctionService>().getAuctionSettings() } ?: return@launch
            settingsPanel.removeAll()
            renderAuctionSettingsSummary(settingsPanel, settings)
        }
    }

    val actionsRow = root.hPanel(spacing = 8) { addCssClasses("mt-2") }
    val enableButton = actionsRow.button(tr("Auktion aktivieren …"), style = ButtonStyle.PRIMARY)
    val disableButton = actionsRow.actionButton(ActionIcon.REVOKE, tr("Auktion deaktivieren"), style = ButtonStyle.OUTLINEDANGER)

    enableButton.onClick {
        enableButton.disabled = true
        AppScope.launch {
            val disclaimer = guarded { rpcService<IAuctionService>().getAuctionComplianceDisclaimer() }
            enableButton.disabled = false
            if (disclaimer != null) {
                auctionEnableDisclaimerModal(disclaimer) {
                    AppScope.launch {
                        val result =
                            guarded {
                                rpcService<IAuctionService>().enableAuction(
                                    AuctionComplianceAcknowledgmentInput(
                                        disclaimerVersion = disclaimer.version,
                                        disclaimerSha256 = disclaimer.sha256,
                                    ),
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Auktion aktiviert."))
                            loadSettings()
                            onSettingsChanged()
                        }
                    }
                }
            }
        }
    }

    disableButton.onClick {
        auctionDisableConfirmDialog {
            disableButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().disableAuction() }
                disableButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Auktion deaktiviert."))
                    loadSettings()
                    onSettingsChanged()
                }
            }
        }
    }

    root.h2(tr("Wertobergrenze (LTR)")) { addCssClass("h6") }
    val maxValuePanel = root.vPanel(spacing = 6)
    val maxValueInput = maxValuePanel.text(label = tr("Wertobergrenze (LTR, leer = kein Limit)"))
    val maxValueErrorBox =
        maxValuePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val maxValueSaveButton = maxValuePanel.actionButton(ActionIcon.SAVE, tr("Obergrenze speichern"), style = ButtonStyle.SECONDARY)
    maxValueSaveButton.onClick {
        maxValueErrorBox.hide()
        val text = maxValueInput.value.orEmpty().trim()
        val value: Decimal? =
            if (text.isBlank()) {
                null
            } else {
                if (!Validation.isPositiveDecimal(text)) {
                    maxValueErrorBox.content = tr("Die Wertobergrenze muss, falls angegeben, ein positiver LTR-Betrag sein.")
                    maxValueErrorBox.show()
                    return@onClick
                }
                text.toDouble().toDecimal()
            }
        confirmDialog(
            title = tr("Wertobergrenze setzen"),
            message =
                value?.let {
                    gettext(
                        "Die Wertobergrenze wird auf %1 gesetzt -- neue Angebote dürfen diesen Wert nicht überschreiten.",
                        formatLtr(it),
                    )
                }
                    ?: tr("Die Wertobergrenze wird entfernt (kein Limit mehr)."),
            confirmLabel = tr("Speichern"),
            confirmIcon = ActionIcon.SAVE,
        ) {
            maxValueSaveButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().setAuctionMaxValueLtr(value) }
                maxValueSaveButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Wertobergrenze aktualisiert."))
                    maxValueInput.value = null
                    loadSettings()
                }
            }
        }
    }

    loadSettings()
}

private fun renderAuctionSettingsSummary(
    panel: SimplePanel,
    settings: AuctionSettingsDto,
) {
    val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("Status:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (settings.auctionEnabled) tr("Aktiviert") else tr("Deaktiviert"),
        if (settings.auctionEnabled) "success" else "secondary",
    )

    val capRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    capRow.div(tr("Wertobergrenze:")) { addCssClasses("text-muted small") }
    val maxValue = settings.auctionMaxValueLtr
    if (maxValue != null) {
        capRow.ltrSpan(maxValue)
    } else {
        capRow.div(tr("Kein Limit")) { addCssClasses("small") }
    }

    if (settings.lastAcknowledgedByDisplayName != null) {
        panel.div(
            gettext(
                "Zuletzt bestätigt von %1 am %2 (Hinweistext-Version %3).",
                settings.lastAcknowledgedByDisplayName,
                formatSystemDateTime(settings.lastAcknowledgedAt!!),
                settings.lastDisclaimerVersion,
            ),
        ) { addCssClasses("text-muted small") }
    } else {
        panel.div(tr("Noch keine Bestätigung des rechtlichen Hinweistexts erfolgt.")) { addCssClasses("text-muted small") }
    }
}

/**
 * The wave's most security-sensitive new interaction pattern (design decision, per the plan). The
 * ADMIN must read [disclaimer] before confirming; [disclaimer]'s `version`/`sha256` are held as
 * read-only local values captured directly from this JUST-fetched DTO -- never rendered as editable
 * fields, never re-derived, and resent verbatim to [IAuctionService.enableAuction] so the server can
 * constant-time-verify the ADMIN was shown the CURRENT, not stale/tampered, text.
 */
private fun auctionEnableDisclaimerModal(
    disclaimer: AuctionComplianceDisclaimerDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = gettext("Auktion aktivieren -- rechtlicher Hinweis (Version %1)", disclaimer.version))
    modal.div(
        tr(
            "Bitte lesen Sie den folgenden rechtlichen Hinweistext vollständig, bevor Sie die Auktion aktivieren. Diese " +
                "Plattform führt keine automatisierte Rechtsberatung durch -- die rechtliche Einordnung liegt bei Ihrer " +
                "Organisation.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    modal.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 300.px
        overflow = Overflow.AUTO
        content = sanitizeUntrustedI18nText(disclaimer.text)
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Ich bestätige, den aktuellen Text gelesen zu haben"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * Tier 3 "Löschend"-style (D4): same visual tier as Tier 2, but additionally names exactly what
 * freezes -- discovered by reading `requireAuctionEnabled()`'s uniform call-site coverage across
 * every mutating AND read method in `AuctionService.kt` (no carve-out for already-OPEN auctions).
 */
private fun auctionDisableConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Auktion deaktivieren bestätigen"))
    modal.div(
        tr(
            "Bereits laufende (OPEN) Auktionen können bis zur erneuten Aktivierung nicht mehr abgewickelt werden " +
                "(kein Gebot, kein Sofortkauf, kein Abwickeln) -- sie bleiben eingefroren.",
        ),
    ) { addCssClasses("fw-bold text-danger") }
    modal.div(tr("Neue Angebote können ebenfalls nicht erstellt werden, bis ein ADMIN die Auktion erneut aktiviert."))
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        newActionButton(ActionIcon.REVOKE, tr("Deaktivieren"), ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// German label/badge-color tables
// ================================================================================================

/** [statusBadge] grammar (`StatusBadge.kt`): an auction's status progresses over its lifetime
 * (OPEN -> SETTLED/CLOSED_NO_SALE), so it uses the filled/lifecycle variant. Covers every
 * [AuctionStatus] literal. */
fun auctionStatusLabel(status: AuctionStatus): String =
    when (status) {
        AuctionStatus.OPEN -> gettext("Offen")
        AuctionStatus.SETTLED -> gettext("Abgeschlossen (verkauft)")
        AuctionStatus.CLOSED_NO_SALE -> gettext("Abgeschlossen (kein Verkauf)")
    }

fun auctionStatusColor(status: AuctionStatus): String =
    when (status) {
        AuctionStatus.OPEN -> "primary"
        AuctionStatus.SETTLED -> "success"
        AuctionStatus.CLOSED_NO_SALE -> "secondary"
    }
