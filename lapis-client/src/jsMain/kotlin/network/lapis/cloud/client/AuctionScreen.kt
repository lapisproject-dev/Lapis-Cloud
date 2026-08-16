package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Overflow
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionSettingsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAuctionService
import kotlin.time.Clock

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
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Auktion"))

    // ---- Neues Angebot erstellen (D3: renderMyLtrBalanceInline first, before any input field --
    // identical position to CrowdfundingScreen.kt's own submit form) ---------------------------
    root.h2(tr("Neues Angebot erstellen"))
    val createPanel = root.vPanel(spacing = 6)
    createPanel.renderMyLtrBalanceInline()
    createPanel.div(gettext("Beim Einstellen wird eine feste Gebühr von %1 fällig.", formatLtr(0.01.toDecimal()))) {
        addCssClasses("text-muted small")
    }

    // ---- Auktionen (browse) ---------------------------------------------------------------
    root.h2(tr("Auktionen"))
    val staleRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val staleLabel = staleRow.div(tr("Wird geladen …")) { addCssClasses("text-muted small flex-grow-1") }
    val auctionsRefreshButton = staleRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusFilterOptions =
        listOf("" to tr("Alle (persistierter Status)")) + AuctionStatus.entries.map { it.name to auctionStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = tr("Filter: Status (persistiert)"))
    val disabledBanner = root.vPanel(spacing = 4) { addCssClasses("border rounded p-3 bg-body-tertiary") }
    disabledBanner.hide()
    val auctionsPanel = root.vPanel(spacing = 10)

    // ---- Meine Gebote ------------------------------------------------------------------------
    root.h2(tr("Meine Gebote"))
    val myBidsPanel = root.vPanel(spacing = 6)

    // ---- Meine Auktionen (als Verkäufer) --------------------------------------------------
    root.h2(tr("Meine Auktionen (als Verkäufer)"))
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
            val fetchedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            staleLabel.content =
                gettext("Preisstand: %1:%2:%3 Uhr", fetchedAt.hour.pad2(), fetchedAt.minute.pad2(), fetchedAt.second.pad2())
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

    renderCreateListingForm(createPanel) {
        loadAuctions()
        loadMyAuctionsInto(myAuctionsPanel, currentMemberId)
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

private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"

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
// Neues Angebot erstellen
// ================================================================================================

private fun renderCreateListingForm(
    root: SimplePanel,
    onCompleted: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val titleInput = panel.text(label = tr("Titel"))
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 3)
    val startingBidInput = panel.text(label = tr("Startpreis (LTR)"))
    val buyNowInput = panel.text(label = tr("Sofortkaufpreis (LTR, optional -- muss über dem Startpreis liegen)"))
    // durationHours is server-bounded (1..2160h); deliberately not duplicated as a client-facing
    // constant, per CreateAuctionListingInput's own KDoc -- a loose "z. B. 24" placeholder hint
    // only, same posture Money.kt/Validation.kt already take toward every other server-owned bound.
    val durationInput = panel.text(label = tr("Laufzeit in Stunden (z. B. 24)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = panel.button(tr("Angebot erstellen"), style = ButtonStyle.PRIMARY)

    submitButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value.orEmpty().trim()
        val startingBidText = startingBidInput.value.orEmpty().trim()
        val buyNowText = buyNowInput.value.orEmpty().trim()
        val durationText = durationInput.value.orEmpty().trim()
        val durationHours = durationText.toIntOrNull()

        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(description) || !Validation.isPositiveDecimal(startingBidText)) {
            errorBox.content = tr("Bitte Titel, Beschreibung und einen positiven Startpreis (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        if (durationHours == null || durationHours <= 0) {
            errorBox.content = tr("Bitte eine Laufzeit in ganzen Stunden (größer als 0) angeben.")
            errorBox.show()
            return@onClick
        }
        val startingBid = startingBidText.toDouble().toDecimal()
        var buyNowPrice: Decimal? = null
        if (buyNowText.isNotBlank()) {
            if (!Validation.isPositiveDecimal(buyNowText)) {
                errorBox.content = tr("Der Sofortkaufpreis muss, falls angegeben, ein positiver LTR-Betrag sein.")
                errorBox.show()
                return@onClick
            }
            val parsed = buyNowText.toDouble().toDecimal()
            if (parsed.toDouble() <= startingBid.toDouble()) {
                errorBox.content = tr("Der Sofortkaufpreis muss über dem Startpreis liegen.")
                errorBox.show()
                return@onClick
            }
            buyNowPrice = parsed
        }

        // Tier 1 "Kostenpflichtig" (D4): the plain, neutral-framed confirmDialog -- states the
        // flat listing fee plus the chosen parameters plainly before the caller commits.
        val buyNowSummary = buyNowPrice?.let { gettext(", Sofortkaufpreis %1", formatLtr(it)) } ?: ""
        confirmDialog(
            title = tr("Angebot erstellen"),
            message =
                gettext(
                    "Es wird ein Angebot \"%1\" mit Startpreis %2%3 und %4 Stunden Laufzeit erstellt. Dabei wird eine feste " +
                        "Gebühr von %5 aus Ihrem freien LTR-Guthaben gebucht.",
                    title,
                    formatLtr(startingBid),
                    buyNowSummary,
                    durationHours,
                    formatLtr(0.01.toDecimal()),
                ),
            confirmLabel = tr("Erstellen"),
        ) {
            submitButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IAuctionService>().createListing(
                            CreateAuctionListingInput(
                                title = title,
                                description = description,
                                startingBidLtr = startingBid,
                                buyNowPriceLtr = buyNowPrice,
                                durationHours = durationHours,
                            ),
                        )
                    }
                submitButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Angebot \"%1\" erstellt.", result.title))
                    titleInput.value = null
                    descriptionInput.value = null
                    startingBidInput.value = null
                    buyNowInput.value = null
                    durationInput.value = null
                    onCompleted()
                }
            }
        }
    }
}

// ================================================================================================
// Auction card (shared by the browse list and "Meine Auktionen")
// ================================================================================================

private fun renderAuctionCard(
    panel: SimplePanel,
    auction: AuctionDto,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(auction.title) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(auctionStatusLabel(auction.status), auctionStatusColor(auction.status))
    if (auction.status != auction.effectiveStatus) {
        headerRow.statusBadge(
            gettext("Effektiv: %1", auctionStatusLabel(auction.effectiveStatus)),
            auctionStatusColor(auction.effectiveStatus),
        )
    }

    card.div(auction.description) { addCssClasses("small") }
    card.div(gettext("Verkäufer: %1 · Endet: %2 · Gebote: %3", auction.sellerDisplayName, auction.endsAt, auction.bidCount)) {
        addCssClasses("text-muted small")
    }

    val priceRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val startCell = priceRow.vPanel(spacing = 2)
    startCell.div(tr("Startpreis")) { addCssClasses("text-muted small") }
    startCell.ltrSpan(auction.startingBidLtr)
    val currentPriceForDisplay = auction.currentPriceLtr
    if (currentPriceForDisplay != null) {
        val currentCell = priceRow.vPanel(spacing = 2)
        currentCell.div(if (auction.leaderIsMe) tr("Aktueller Preis (Sie führen)") else tr("Aktueller Preis")) {
            addCssClasses("text-muted small")
        }
        currentCell.ltrSpan(currentPriceForDisplay)
        auction.currentLeaderDisplayName?.let { leader ->
            currentCell.div(if (auction.leaderIsMe) tr("Führend: Sie") else gettext("Führend: %1", leader)) {
                addCssClasses("text-muted small")
            }
        }
    }
    val buyNowPriceForDisplay = auction.buyNowPriceLtr
    if (buyNowPriceForDisplay != null) {
        val buyNowCell = priceRow.vPanel(spacing = 2)
        buyNowCell.div(tr("Sofortkaufpreis")) { addCssClasses("text-muted small") }
        buyNowCell.ltrSpan(buyNowPriceForDisplay)
    }

    if (auction.effectiveStatus == AuctionStatus.SETTLED) {
        card.div(
            gettext(
                "Verkauft an %1 für %2.",
                auction.winnerDisplayName ?: "--",
                auction.finalPriceLtr?.let { formatLtr(it) } ?: "--",
            ),
        ) { addCssClasses("small") }
    }

    val isSeller = currentMemberId != null && auction.sellerMemberId == currentMemberId
    if (!isSeller && auction.effectiveStatus == AuctionStatus.OPEN) {
        renderBidAndBuyNowControls(card, auction, onChanged)
    }

    // Any authenticated member (NOT seller-restricted server-side) may settle -- only
    // rendered/enabled once the auction has ended but the persisted status has not yet lazily
    // flipped (see file KDoc "Confirm-dialog tier" -- no confirm dialog here, deterministic).
    if (auction.status == AuctionStatus.OPEN && auction.effectiveStatus != AuctionStatus.OPEN) {
        val settleRow = card.hPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1") }
        settleRow.div(tr("Diese Auktion ist beendet, aber noch nicht abgewickelt.")) { addCssClasses("text-muted small flex-grow-1") }
        val settleButton = settleRow.button(tr("Abwickeln"), style = ButtonStyle.OUTLINESECONDARY)
        settleButton.onClick {
            settleButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().settleAuction(auction.id) }
                settleButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Auktion \"%1\" abgewickelt.", result.title))
                    onChanged()
                }
            }
        }
    }
}

/**
 * D5: [placeBid]/[buyNow] each get a small "Wird ausgeführt …" busy-affordance ([busyLabel]) next
 * to their button, in addition to `disabled = true` -- a bare disabled button gives no feedback
 * that real LTR is being committed. D6(c): [placeBid]'s confirm dialog restates the price *as last
 * fetched* and states plainly that the actual evaluation happens against the live price at
 * confirmation time.
 */
private fun renderBidAndBuyNowControls(
    card: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val controlsPanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    val bidRow = controlsPanel.hPanel(spacing = 8) { addCssClasses("align-items-end flex-wrap") }
    val bidInput = bidRow.text(label = tr("Ihr Höchstgebot (LTR)"))
    val bidButton = bidRow.button(tr("Bieten"), style = ButtonStyle.OUTLINEDANGER)
    val bidBusyLabel = bidRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
    bidBusyLabel.hide()
    val errorBox =
        controlsPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    bidButton.onClick {
        errorBox.hide()
        val bidText = bidInput.value.orEmpty().trim()
        if (!Validation.isPositiveDecimal(bidText)) {
            errorBox.content = tr("Bitte ein positives Höchstgebot (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        val bidAmount = bidText.toDouble().toDecimal()
        if (bidAmount.toDouble() < auction.startingBidLtr.toDouble()) {
            errorBox.content =
                gettext("Ihr Höchstgebot muss mindestens dem Startpreis (%1) entsprechen.", formatLtr(auction.startingBidLtr))
            errorBox.show()
            return@onClick
        }
        val lastFetchedPriceText =
            auction.currentPriceLtr?.let { gettext("zuletzt abgerufener Preis: %1", formatLtr(it)) }
                ?: gettext("noch keine Gebote, Startpreis: %1", formatLtr(auction.startingBidLtr))
        placeBidConfirmDialog(auction.title, bidAmount, lastFetchedPriceText) {
            bidButton.disabled = true
            bidBusyLabel.show()
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().placeBid(auction.id, bidAmount) }
                bidButton.disabled = false
                bidBusyLabel.hide()
                if (result != null) {
                    val leadCopy = if (result.youAreLeader) gettext("Sie führen jetzt.") else gettext("Ein anderes Gebot führt weiterhin.")
                    notifySuccess(gettext("Gebot angenommen. Aktueller Preis: %1. %2", formatLtr(result.currentPriceLtr), leadCopy))
                    bidInput.value = null
                    onChanged()
                }
            }
        }
    }

    val buyNowPrice = auction.buyNowPriceLtr
    val currentPrice = auction.currentPriceLtr
    if (buyNowPrice != null && (currentPrice == null || currentPrice.toDouble() < buyNowPrice.toDouble())) {
        val buyNowRow = controlsPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        val buyNowButton = buyNowRow.button(gettext("Sofort kaufen für %1", formatLtr(buyNowPrice)), style = ButtonStyle.DANGER)
        val buyNowBusyLabel = buyNowRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
        buyNowBusyLabel.hide()
        buyNowButton.onClick {
            buyNowConfirmDialog(auction.title, buyNowPrice) {
                buyNowButton.disabled = true
                buyNowBusyLabel.show()
                AppScope.launch {
                    val result = guarded { rpcService<IAuctionService>().buyNow(auction.id) }
                    buyNowButton.disabled = false
                    buyNowBusyLabel.hide()
                    if (result != null) {
                        val finalPrice = result.finalPriceLtr ?: buyNowPrice
                        notifySuccess(gettext("Sofortkauf abgeschlossen: \"%1\" für %2.", result.title, formatLtr(finalPrice)))
                        onChanged()
                    }
                }
            }
        }
    }
}

/** Tier 2 "Endgültig" (D4): bespoke modal, matches `LtrLedgerScreen.peerTransferConfirmDialog`'s
 * irreversibility-bar styling. D6(c): restates the price as last fetched and states plainly that
 * the bid is evaluated against the live price at confirmation time, not the one shown here. */
private fun placeBidConfirmDialog(
    auctionTitle: String,
    maxBid: Decimal,
    lastFetchedPriceText: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Gebot bestätigen"))
    modal.div(tr("Ihr Höchstgebot ist verbindlich und reserviert LTR aus Ihrem freien Guthaben.")) {
        addCssClasses("fw-bold text-danger")
    }
    modal.div(
        gettext(
            "Sie bieten %1 auf \"%2\" (%3). Ihr Gebot wird gegen den " +
                "aktuellen Preis zum Zeitpunkt der Bestätigung ausgewertet, nicht den hier angezeigten -- der Preis kann " +
                "sich seit dem letzten Abruf geändert haben.",
            formatLtr(maxBid),
            auctionTitle,
            lastFetchedPriceText,
        ),
    )
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Gebot abgeben"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** Tier 2 "Endgültig" (D4): bespoke modal, same shape as [placeBidConfirmDialog]. */
private fun buyNowConfirmDialog(
    auctionTitle: String,
    buyNowPrice: Decimal,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Sofortkauf bestätigen"))
    modal.div(tr("Sofortkauf ist verbindlich -- kann nicht rückgängig gemacht werden.")) { addCssClasses("fw-bold text-danger") }
    modal.div(gettext("Sie kaufen \"%1\" sofort für %2.", auctionTitle, formatLtr(buyNowPrice)))
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Sofort kaufen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Meine Gebote
// ================================================================================================

private fun renderMyBidsTable(
    panel: SimplePanel,
    bids: List<AuctionBidDto>,
) {
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div(tr("Auktion")) { addCssClasses("flex-grow-1") }
    headerRow.div(tr("Ihr Höchstgebot")) { width = 140.px }
    headerRow.div(tr("Führend")) { width = 90.px }
    headerRow.div(tr("Status")) { width = 160.px }
    headerRow.div(tr("Abgegeben")) { width = 160.px }

    bids.forEach { bid ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(bid.auctionTitle) { addCssClasses("flex-grow-1") }
        val bidCell = row.div { width = 140.px }
        bidCell.ltrSpan(bid.maxBidLtr)
        row.div(if (bid.isCurrentLeader) tr("Ja") else tr("Nein")) { width = 90.px }
        val statusCell = row.div { width = 160.px }
        statusCell.statusBadge(auctionStatusLabel(bid.auctionStatus), auctionStatusColor(bid.auctionStatus))
        row.div(bid.createdAt.toString()) {
            width = 160.px
            addCssClasses("text-muted small")
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
    val disableButton = actionsRow.button(tr("Auktion deaktivieren"), style = ButtonStyle.OUTLINEDANGER)

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
    val maxValueSaveButton = maxValuePanel.button(tr("Obergrenze speichern"), style = ButtonStyle.SECONDARY)
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
                settings.lastAcknowledgedAt,
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
        content = disclaimer.text
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
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
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Deaktivieren"), style = ButtonStyle.DANGER).apply {
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
