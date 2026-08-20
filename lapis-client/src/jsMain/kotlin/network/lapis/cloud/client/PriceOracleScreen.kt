package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.select.select
import io.kvision.form.text.text
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
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.OraclePriceStatusDto
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceOracleConversionDto
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IPriceOracleService

/**
 * LTR-Wirtschaft UI wave, screen 5 of 5 -- "Price-Oracle" (`IPriceOracleService`). Kept as its OWN
 * screen rather than folded into `LtrLedgerScreen.kt`: every one of its four methods is TREASURER/
 * BOARD/ADMIN+ (an admin/treasury operational tool, not a member-facing economy screen), and its
 * workflow shape (policy config form + live diagnostic preview + a donation-intake/mint action with
 * its own network-fan-out latency characteristics) is genuinely distinct from the ledger/transfer
 * forms on that screen -- same reasoning the Accounting UI wave already applied when it split five
 * admin-facing report/config screens instead of cramming them into one. See `IPriceOracleService`
 * KDoc and `19-price-oracle.kuml.kts` file header for the full fachlich model this screen surfaces.
 *
 * **Role gating** (verified against `PriceOracleService.kt`'s actual `requireRole` call sites, not
 * guessed from method names -- see `Routes.PRICE_ORACLE` KDoc for the route-level reasoning):
 * - [IPriceOracleService.getOracleConfig]/[IPriceOracleService.previewCurrentPrice]/
 *   [IPriceOracleService.convertDonationToLtr] -- `current.requireRole(TREASURER, BOARD, ADMIN)`
 *   uniformly (`PRICE_ORACLE_TREASURY_ROLES`). This is also the route-level guard, so every caller
 *   who can even reach this screen can read the config, run a diagnostic preview, and book a
 *   donation conversion.
 * - [IPriceOracleService.updateOracleConfig] -- `current.requireRole(AccountRole.ADMIN)`, narrower
 *   than the route guard. Gated here as `canManage`, exactly like [Routes.LEDGER]/
 *   [Routes.COST_CENTERS]/[Routes.DONORS]'s in-screen ADMIN-only write split -- a TREASURER/BOARD
 *   caller sees the current config read-only, with no edit form rendered at all.
 *
 * **`anchorAsset` selectability (design decision D12, superseded V0.6.6)**: originally
 * [AnchorAsset.GOLD_XAU]/[AnchorAsset.FIAT] were structurally excluded from the `<select>`'s
 * options because `updateOracleConfig` hard-rejected anything other than
 * [AnchorAsset.BITCOIN_BTC] server-side. As of V0.6.6 "Price-Oracle: Gold- und Fiat-Anker" all
 * three [AnchorAsset] literals have real, wired price sources (see `IPriceOracleService` KDoc
 * "Anchor coverage"), so all three are now real, selectable `<option>`s
 * (`AnchorAsset.entries.map { it.name to anchorAssetLabel(it) }`) -- the server still rejects an
 * anchor switch a given DEPLOYMENT cannot serve (fewer configured `LAPIS_ORACLE_*` sources than
 * `AnchorPolicy.quorumFloor`), surfaced to the operator via the normal `guarded {}`
 * `ConflictException` path, same as any other server-side validation failure this screen already
 * relies on -- this form does NOT attempt to detect key presence client-side (the server's error
 * message is the honest single source of truth for that). A per-anchor help line under the select
 * (updated on every selection change via `anchorAssetSelect.subscribe { ... }`) states the
 * quorum-floor/refresh-interval/recommended-TTL facts for the currently selected anchor, all read
 * from the shared [AnchorPolicy] object rather than a duplicated constant (file KDoc's own "never
 * duplicate a server constant" rule). `donationCurrency` uses a closed `{EUR, USD}` select instead (matches
 * `PriceOracleService.kt`'s private `SUPPORTED_DONATION_CURRENCIES` exactly) -- the one deliberate
 * exception to "never duplicate a server constant" this wave's plan calls out, justified because a
 * free-text field here would always server-reject any other value, a strictly worse UX than a
 * 2-option dropdown, and the set is small/closed/stable.
 *
 * **Confirm-dialog tier (design decision D4)**: [IPriceOracleService.updateOracleConfig] uses the
 * plain, neutral-framed [confirmDialog] (Tier 1 "Kostenpflichtig" -- a policy change, not itself a
 * money movement). [IPriceOracleService.convertDonationToLtr] uses a bespoke, unmissable-danger-
 * framed modal ([convertDonationConfirmDialog], Tier 2 "Endgültig", same bespoke-modal shape as
 * `LtrLedgerScreen.peerTransferConfirmDialog`/`AuctionScreen.placeBidConfirmDialog` -- no shared
 * tier-preset component exists yet in `ConfirmDialog.kt`, same "out of scope for a single-screen
 * change" reasoning those two files already document) -- it MINTS real LTR into the target member's
 * ledger and is not reversible via any RPC this interface offers.
 *
 * **Pre-commit `ltrMinted` estimate (Review Round 1 / MAJOR-2, supersedes the original design)**:
 * this dialog originally showed NO computed/estimated `ltrMinted` figure at all, reasoning that doing
 * so would require re-implementing the server's exact formula
 * (`donationAmount / (anchorUnitsPerLtr * anchorPrice)`) client-side, precisely the "client-side
 * re-derivation of a server-owned monetary figure" `Money.kt`'s own file KDoc forbids for EUR. The
 * review found a real, safety-relevant gap in that design: with the anchor now switchable
 * (V0.6.6), an ADMIN who switches `anchorAsset` without ALSO updating `anchorUnitsPerLtr` to the new
 * anchor's scale can silently mint orders-of-magnitude too much LTR per donation (see
 * `AnchorSourcePolicy.kt`'s `plausiblePegBand` KDoc for the concrete ~50,000x FIAT / ~25x GOLD_XAU
 * failure mode this closed server-side) -- and this dialog, showing NOTHING, was the one place an
 * operator could otherwise have caught an obviously-wrong number before committing. [estimateLtrMinted]
 * therefore computes a best-effort, clearly-labeled ESTIMATE (fetched fresh via
 * [IPriceOracleService.getOracleConfig]/[IPriceOracleService.previewCurrentPrice] right before the
 * dialog opens) -- this is deliberately NOT the same thing the original design KDoc forbade: it uses
 * [Double] arithmetic (not exact [java.math.BigDecimal]/[dev.kilua.rpc.types.Decimal] math), is
 * labeled "geschätzt, kann beim Bestätigen leicht abweichen" (may differ slightly at confirmation),
 * and is NEVER itself booked, compared against, or substituted for the server's real result -- the
 * real [PriceOracleConversionDto] figures are still shown only AFTER a successful call, verbatim,
 * never re-summed, exactly as before. This is the same "the oracle's own quote is inherently a live
 * snapshot" reasoning [OraclePriceStatusDto]'s own diagnostic-preview feature already relies on, just
 * surfaced one step earlier where a catastrophic misconfiguration can actually be caught. If the
 * config/quote fetch fails or the oracle is halted, the estimate is silently omitted (not an error
 * toast) -- the dialog still opens, and the real convert call below surfaces any real error normally.
 *
 * [IPriceOracleService.previewCurrentPrice] gets no confirm dialog -- pure read-only
 * diagnostic, never mints (see [IPriceOracleService] KDoc). Every non-idempotent button disables
 * itself for the duration of the in-flight request (double-submit protection,
 * `LedgerScreen.postDirectButton`'s idiom); [previewCurrentPrice]/[convertDonationToLtr]
 * additionally show a small "Wird ausgeführt …" busy-affordance (design decision D5, same idiom as
 * `AuctionScreen.kt`'s bid/buy-now controls) since both can legitimately take several seconds (three
 * parallel HTTP calls to Coinbase/Kraken/Bitstamp, each with its own multi-second timeout -- see
 * `PriceOracleService.kt`'s class KDoc "ordering is load-bearing").
 *
 * **Halted vs. live/degraded/cached (mutually exclusive shapes)**: [OraclePriceStatusDto]'s own
 * KDoc documents that exactly one of the halted branch or the status/medianPrice/priceTimestamp
 * branch is populated -- rendered here as two visually distinct outcomes (a red alert box for
 * `halted`, a status badge + figures for the live/degraded/cached case), never as one shape with
 * blank fields.
 *
 * Every LTR amount is rendered via [ltrSpan]/[formatLtr] (`Money.kt`, design decision D2) -- never
 * hand-formatted. Donation amounts are denominated in [PriceOracleConfigDto.donationCurrency] (EUR
 * OR USD, an operator-chosen policy field, not always EUR) -- `Money.kt`'s [formatMoney] is
 * deliberately NOT reused for them, since its trailing `" €"` suffix is hardcoded and would mislabel
 * a USD amount; this file's own tiny [formatDonationAmount] (bare `"$amount $currency"`, the exact
 * same "never re-round/re-derive `Decimal.toString()`" transform as [formatMoney]/[formatLtr], just
 * parameterized by currency) is used instead, confined to this file rather than promoted into
 * `Money.kt` since no other screen in this wave handles a non-EUR/non-LTR amount.
 */
fun renderPriceOracleScreen(container: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Price-Oracle"))
    root.div(
        tr(
            "Verwaltet die Anker-Bindung des Libertaler (LTR) an ein reales Asset (konfigurierbar: Bitcoin, Gold " +
                "oder Fiat/Euro) und bucht eingegangene Spenden als LTR-Mint. Sichtbar für TREASURER/BOARD/ADMIN, " +
                "Konfigurationsänderungen nur für ADMIN.",
        ),
    ) { addCssClasses("text-muted small") }

    // ---- Konfiguration ------------------------------------------------------------------------
    root.h2(tr("Konfiguration"))
    val configPanel = root.vPanel(spacing = 6)
    configPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadConfig() {
        configPanel.removeAll()
        configPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val config = guarded { rpcService<IPriceOracleService>().getOracleConfig() } ?: return@launch
            configPanel.removeAll()
            renderConfigSummary(configPanel, config)
            if (canManage) {
                renderConfigForm(configPanel, config) { loadConfig() }
            }
        }
    }
    loadConfig()

    // ---- Diagnose: aktueller Kurs (D10 empty-state N/A -- always renders halted OR live shape) --
    root.h2(tr("Diagnose: aktueller Kurs"))
    root.div(tr("Rein diagnostisch -- prüft die Orakel-Gesundheit, ohne LTR zu minten.")) { addCssClasses("text-muted small") }
    val previewRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val previewButton = previewRow.button(tr("Kurs abrufen"), style = ButtonStyle.OUTLINESECONDARY)
    val previewBusyLabel = previewRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
    previewBusyLabel.hide()
    val previewResultPanel = root.vPanel(spacing = 4)

    previewButton.onClick {
        previewButton.disabled = true
        previewBusyLabel.show()
        previewResultPanel.removeAll()
        AppScope.launch {
            val status = guarded { rpcService<IPriceOracleService>().previewCurrentPrice() }
            previewButton.disabled = false
            previewBusyLabel.hide()
            if (status != null) {
                renderPriceStatus(previewResultPanel, status)
            }
        }
    }

    // ---- Spende zu LTR konvertieren ------------------------------------------------------------
    root.h2(tr("Spende zu LTR konvertieren"))
    val convertPanel = root.vPanel(spacing = 6)
    convertPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    AppScope.launch {
        val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        convertPanel.removeAll()
        renderConvertForm(convertPanel, members)
    }
}

// ================================================================================================
// Konfiguration: Anzeige + Formular (canManage only)
// ================================================================================================

private fun renderConfigSummary(
    panel: SimplePanel,
    config: PriceOracleConfigDto,
) {
    val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
    val row1 = box.hPanel(spacing = 16) { addCssClasses("flex-wrap") }
    row1.labeledValue(tr("Anker-Asset")) { it.typeBadge(anchorAssetLabel(config.anchorAsset), "primary") }
    row1.labeledValue(tr("Spendenwährung")) { it.div(config.donationCurrency) }
    row1.labeledValue(tr("Peg (Einheiten je LTR)")) { it.div(formatDonationAmount(config.anchorUnitsPerLtr, "")) }

    val row2 = box.hPanel(spacing = 16) { addCssClasses("flex-wrap") }
    row2.labeledValue(tr("Cache-TTL")) { it.div(gettext("%1 s", config.cacheTtlSeconds)) }
    row2.labeledValue(tr("Mindest-Quorum")) { it.div(gettext("%1 Quellen", config.minQuorum)) }
    row2.labeledValue(tr("Ausreißer-Schwelle")) { it.div(gettext("%1 bps", config.outlierThresholdBps)) }
    row2.labeledValue(tr("Max. Spread")) { it.div(gettext("%1 bps", config.maxSpreadBps)) }

    box.div(gettext("Zuletzt aktualisiert: %1", config.updatedAt)) { addCssClasses("text-muted small") }
}

private fun SimplePanel.labeledValue(
    label: String,
    renderValue: (SimplePanel) -> Unit,
) {
    val cell = this.vPanel(spacing = 2)
    cell.div(label) { addCssClasses("text-muted small") }
    renderValue(cell)
}

/**
 * V0.6.6 finding (see file KDoc "anchorAsset selectability"): every [AnchorAsset] literal is now a
 * real, selectable option -- a per-anchor help line under the select (re-rendered on every
 * selection change) states the quorum-floor/refresh-interval/recommended-TTL facts from the shared
 * [AnchorPolicy], and both the minQuorum/cacheTtl client-side validation and the confirm-dialog
 * copy are anchor-aware instead of a hardcoded `< 2`/`BITCOIN_BTC` literal. `donationCurrency`
 * mirrors `PriceOracleService.kt`'s private `SUPPORTED_DONATION_CURRENCIES` (`{EUR, USD}`) as a
 * closed select -- the wave's one deliberate exception to "never duplicate a server constant" (see
 * file KDoc).
 */
private fun renderConfigForm(
    root: SimplePanel,
    config: PriceOracleConfigDto,
    onUpdated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3 mt-2") }
    panel.div(tr("Konfiguration bearbeiten (ADMIN)")) { addCssClass("fw-bold") }

    val anchorAssetOptions = AnchorAsset.entries.map { it.name to anchorAssetLabel(it) }
    val anchorAssetSelect =
        panel.select(options = anchorAssetOptions, value = config.anchorAsset.name, label = tr("Anker-Asset"))
    val anchorHelpBox = panel.div("") { addCssClasses("text-muted small") }

    val donationCurrencyOptions = listOf("EUR" to "EUR", "USD" to "USD")
    val donationCurrencySelect =
        panel.select(options = donationCurrencyOptions, value = config.donationCurrency, label = tr("Spendenwährung"))

    val anchorUnitsInput = panel.text(label = tr("Peg: Einheiten des Anker-Assets je LTR"))
    anchorUnitsInput.value = config.anchorUnitsPerLtr.toString()
    val cacheTtlInput = panel.text(label = tr("Cache-TTL (Sekunden)"))
    cacheTtlInput.value = config.cacheTtlSeconds.toString()
    val minQuorumInput = panel.text(label = tr("Mindest-Quorum (Quellen)"))
    minQuorumInput.value = config.minQuorum.toString()
    val outlierThresholdInput = panel.text(label = tr("Ausreißer-Schwelle (Basispunkte, 1-10000)"))
    outlierThresholdInput.value = config.outlierThresholdBps.toString()
    val maxSpreadInput = panel.text(label = tr("Max. Spread (Basispunkte, >= Ausreißer-Schwelle)"))
    maxSpreadInput.value = config.maxSpreadBps.toString()

    fun selectedAnchor(): AnchorAsset = AnchorAsset.valueOf(anchorAssetSelect.value ?: config.anchorAsset.name)

    fun renderAnchorHelp() {
        val anchor = selectedAnchor()
        val floor = AnchorPolicy.quorumFloor(anchor)
        val refresh = AnchorPolicy.refreshIntervalSeconds(anchor)
        val recommendedTtl = AnchorPolicy.recommendedCacheTtlSeconds(anchor)
        anchorHelpBox.content =
            when (anchor) {
                AnchorAsset.BITCOIN_BTC ->
                    gettext(
                        "Bitcoin nutzt drei kostenlose, schlüssellose Quellen (Coinbase/Kraken/Bitstamp). " +
                            "Mindest-Quorum: %1. Kein Refresh-Intervall -- jede Anfrage ruft live ab.",
                        floor,
                    )
                AnchorAsset.GOLD_XAU ->
                    gettext(
                        "Gold (XAU) benötigt mindestens %1 von drei konfigurierten Preisquellen-API-Schlüsseln " +
                            "auf dem Server (LAPIS_ORACLE_GOLDAPI_KEY, LAPIS_ORACLE_METALPRICEAPI_KEY, " +
                            "LAPIS_ORACLE_ALPHAVANTAGE_KEY); mit allen dreien bleibt der Anker auch beim Ausfall " +
                            "einer Quelle verfügbar. Preise werden höchstens alle %2s neu abgerufen (empfohlene " +
                            "Cache-TTL: %3s).",
                        floor,
                        refresh,
                        recommendedTtl,
                    )
                AnchorAsset.FIAT ->
                    gettext(
                        "Fiat ist an den Euro gebunden (1 Anker-Einheit = 1 EUR) und nutzt die EZB-Referenzkurse " +
                            "als einzige Quelle (Mindest-Quorum: %1). Preise werden höchstens alle %2s neu " +
                            "abgerufen (empfohlene Cache-TTL: %3s).",
                        floor,
                        refresh,
                        recommendedTtl,
                    )
            }
    }
    renderAnchorHelp()
    anchorAssetSelect.subscribe { renderAnchorHelp() }

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val saveButton = panel.button(tr("Konfiguration speichern"), style = ButtonStyle.PRIMARY)

    saveButton.onClick {
        errorBox.hide()
        val anchorAsset = selectedAnchor()
        val quorumFloor = AnchorPolicy.quorumFloor(anchorAsset)
        val refreshInterval = AnchorPolicy.refreshIntervalSeconds(anchorAsset)
        val donationCurrency = donationCurrencySelect.value.orEmpty()
        val anchorUnitsText = anchorUnitsInput.value.orEmpty().trim()
        val cacheTtl =
            cacheTtlInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val minQuorum =
            minQuorumInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val outlierThreshold =
            outlierThresholdInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val maxSpread =
            maxSpreadInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()

        if (!Validation.isPositiveDecimal(anchorUnitsText) ||
            cacheTtl == null ||
            cacheTtl <= 0 ||
            cacheTtl < refreshInterval ||
            minQuorum == null ||
            minQuorum < quorumFloor ||
            outlierThreshold == null ||
            outlierThreshold !in 1..10_000 ||
            maxSpread == null ||
            maxSpread < outlierThreshold
        ) {
            errorBox.content =
                gettext(
                    "Bitte alle Felder gültig ausfüllen: Peg positiv, Cache-TTL positiv und mindestens %1s " +
                        "(Refresh-Intervall für %2), Mindest-Quorum mindestens %3, Ausreißer-Schwelle 1-10000 bps, " +
                        "Max. Spread >= Ausreißer-Schwelle.",
                    refreshInterval,
                    anchorAssetLabel(anchorAsset),
                    quorumFloor,
                )
            errorBox.show()
            return@onClick
        }
        val anchorUnitsPerLtr = anchorUnitsText.toDouble().toDecimal()

        // Tier 1 "Kostenpflichtig" (D4): plain, neutral-framed confirmDialog -- a policy change,
        // not itself a money movement.
        confirmDialog(
            title = tr("Konfiguration speichern"),
            message =
                gettext(
                    "Die Orakel-Konfiguration wird vollständig ersetzt: Anker-Asset %1, " +
                        "Spendenwährung %2, Peg %3, Cache-TTL %4s, Quorum " +
                        "%5, Ausreißer-Schwelle %6bps, Max. Spread %7bps.",
                    anchorAssetLabel(anchorAsset),
                    donationCurrency,
                    anchorUnitsPerLtr,
                    cacheTtl,
                    minQuorum,
                    outlierThreshold,
                    maxSpread,
                ),
            confirmLabel = tr("Speichern"),
        ) {
            saveButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IPriceOracleService>().updateOracleConfig(
                            PriceOracleConfigInput(
                                anchorAsset = anchorAsset,
                                donationCurrency = donationCurrency,
                                anchorUnitsPerLtr = anchorUnitsPerLtr,
                                cacheTtlSeconds = cacheTtl,
                                minQuorum = minQuorum,
                                outlierThresholdBps = outlierThreshold,
                                maxSpreadBps = maxSpread,
                            ),
                        )
                    }
                saveButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Orakel-Konfiguration aktualisiert."))
                    onUpdated()
                }
            }
        }
    }
}

// ================================================================================================
// Diagnose: aktueller Kurs
// ================================================================================================

private fun renderPriceStatus(
    panel: SimplePanel,
    status: OraclePriceStatusDto,
) {
    panel.removeAll()
    if (status.halted) {
        val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3 border-danger") }
        box.div(tr("Orakel angehalten (HALT) -- kein aktueller Kurs verfügbar.")) { addCssClasses("fw-bold text-danger") }
        box.div(status.haltReason ?: tr("Kein Grund angegeben.")) { addCssClasses("small") }
        return
    }
    val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
    val headerRow = box.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(tr("Kurs-Status:")) { addCssClasses("text-muted small") }
    val priceStatus = status.status
    if (priceStatus != null) {
        headerRow.typeBadge(priceStatusLabel(priceStatus), priceStatusColor(priceStatus))
    }
    box.div(gettext("Median-Kurs: %1", status.medianPrice?.let { formatDonationAmount(it, "") } ?: "--")) { addCssClasses("small") }
    box.div(gettext("Quellen: %1", status.sourceIds.joinToString(", ").ifBlank { "--" })) { addCssClasses("text-muted small") }
    box.div(gettext("Zeitstempel: %1", status.priceTimestamp?.toString() ?: "--")) { addCssClasses("text-muted small") }
}

// ================================================================================================
// Spende zu LTR konvertieren
// ================================================================================================

private fun renderConvertForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
) {
    if (members.isEmpty()) {
        root.p(tr("Keine Mitglieder vorhanden, denen eine Spende gutgeschrieben werden könnte.")) { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    panel.div(
        tr(
            "Bucht eine bereits eingegangene Spende als LTR-Mint auf das gewählte Mitglied. Der aktuelle Kurs wird " +
                "beim Bestätigen live abgefragt -- der genaue LTR-Betrag steht erst nach Bestätigung fest.",
        ),
    ) { addCssClasses("text-muted small") }

    val memberSelect = panel.select(options = members.map { it.id to it.displayName }, label = tr("Mitglied"))
    val amountInput = panel.text(label = tr("Spendenbetrag"))
    val currencySelect =
        panel.select(options = listOf("EUR" to "EUR", "USD" to "USD"), value = "EUR", label = tr("Anzeige-Info: Währung"))
    panel.div(
        tr(
            "Hinweis: Die tatsächlich gebuchte Währung ist die in der Konfiguration hinterlegte Spendenwährung -- " +
                "diese Auswahl dient nur der Anzeige im Bestätigungsdialog.",
        ),
    ) { addCssClasses("text-muted small") }
    val noteInput = panel.text(label = tr("Notiz (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val outcomePanel = panel.vPanel(spacing = 4)

    val convertRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val convertButton = convertRow.button(tr("Zu LTR konvertieren"), style = ButtonStyle.OUTLINEDANGER)
    val convertBusyLabel = convertRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
    convertBusyLabel.hide()

    convertButton.onClick {
        errorBox.hide()
        outcomePanel.removeAll()
        val memberId = memberSelect.value
        val member = members.find { it.id == memberId }
        val amountText = amountInput.value.orEmpty().trim()
        val displayCurrency = currencySelect.value.orEmpty()
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (member == null || !Validation.isPositiveDecimal(amountText)) {
            errorBox.content = tr("Bitte ein Mitglied und einen positiven Spendenbetrag angeben.")
            errorBox.show()
            return@onClick
        }
        val amount = amountText.toDouble().toDecimal()

        // Pre-commit estimate fetch (Review Round 1 / MAJOR-2, see file KDoc "Pre-commit ltrMinted
        // estimate") -- best-effort: silently omitted (never an error toast) if this fails, since
        // the real convert call below still surfaces any real error normally.
        convertButton.disabled = true
        convertBusyLabel.show()
        AppScope.launch {
            val estimate = silently { estimateLtrMintedFromLiveQuote(amount) }
            convertButton.disabled = false
            convertBusyLabel.hide()

            convertDonationConfirmDialog(member.displayName, amount, displayCurrency, estimate) {
                convertButton.disabled = true
                convertBusyLabel.show()
                AppScope.launch {
                    val result =
                        guarded {
                            rpcService<IPriceOracleService>().convertDonationToLtr(
                                DonationConversionInput(
                                    memberId = member.id,
                                    donationAmount = amount,
                                    note = note,
                                ),
                            )
                        }
                    convertButton.disabled = false
                    convertBusyLabel.hide()
                    if (result != null) {
                        notifySuccess(gettext("%1 für %2 gemintet.", formatLtr(result.ltrMinted), member.displayName))
                        renderConversionResult(outcomePanel, result)
                        amountInput.value = null
                        noteInput.value = null
                    }
                }
            }
        }
    }
}

/**
 * Fetches the current oracle config + a live/cached quote and computes a best-effort
 * [estimateLtrMinted] from them -- `null` if the oracle is halted or either RPC call fails. See file
 * KDoc "Pre-commit `ltrMinted` estimate" -- called only from within [silently], never shown as an
 * error to the operator on its own (the confirm dialog simply omits the estimate line).
 */
private suspend fun estimateLtrMintedFromLiveQuote(donationAmount: Decimal): Decimal? {
    val config = rpcService<IPriceOracleService>().getOracleConfig()
    val status = rpcService<IPriceOracleService>().previewCurrentPrice()
    val anchorPrice = status.medianPrice ?: return null
    return estimateLtrMinted(donationAmount = donationAmount, anchorUnitsPerLtr = config.anchorUnitsPerLtr, anchorPrice = anchorPrice)
}

/**
 * Runs [block], returning `null` on ANY failure instead of the [guarded] wrapper's error toast --
 * for best-effort, non-critical fetches (the pre-commit `ltrMinted` estimate) where a failure should
 * be silent, not user-facing, and the real action this precedes still goes through [guarded] normally.
 * Rethrows [CancellationException] exactly like [guarded] does -- swallowing it would break
 * structured concurrency.
 */
private suspend fun <T> silently(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * Client-side ESTIMATE of the server's `computeLtrMinted` (`PriceOracleService.kt`) -- Review Round 1
 * / MAJOR-2 fix, see file KDoc "Pre-commit `ltrMinted` estimate" for why this is NOT the "client-side
 * re-derivation of a server-owned monetary figure" the original design avoided. [Double] arithmetic
 * on purpose (no exact-decimal math available for this in commonMain/JS) -- acceptable because this
 * value is only ever shown as a rounded, clearly-labeled estimate, never committed, compared
 * bit-for-bit, or substituted for the server's real result. Returns `null` if [anchorUnitsPerLtr] or
 * [anchorPrice] is not strictly positive (mirrors the server's own division-by-zero guard without
 * duplicating its exact dust-floor threshold).
 *
 * `internal` (not `private`) so [PriceOracleScreenTest] can cover it directly, same reasoning as
 * [formatDonationAmount].
 */
internal fun estimateLtrMinted(
    donationAmount: Decimal,
    anchorUnitsPerLtr: Decimal,
    anchorPrice: Decimal,
): Decimal? {
    val peg = anchorUnitsPerLtr.toDouble()
    val price = anchorPrice.toDouble()
    if (peg <= 0.0 || price <= 0.0) return null
    return (donationAmount.toDouble() / (peg * price)).toDecimal()
}

/**
 * Tier 2 "Endgültig" (D4): bespoke modal, same shape as `LtrLedgerScreen.peerTransferConfirmDialog`/
 * `AuctionScreen.placeBidConfirmDialog`. Shows a clearly-labeled ESTIMATED `ltrMinted` when
 * [estimatedLtrMinted] is non-null (Review Round 1 / MAJOR-2 fix, see file KDoc "Pre-commit
 * `ltrMinted` estimate") -- `null` (fetch failed, or the oracle is halted) falls back to the
 * original copy stating plainly that the price is fetched live at confirmation time.
 */
private fun convertDonationConfirmDialog(
    memberDisplayName: String,
    amount: Decimal,
    displayCurrency: String,
    estimatedLtrMinted: Decimal?,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Spenden-Konvertierung bestätigen"))
    modal.div(tr("Diese Aktion mintet echtes LTR und kann nicht rückgängig gemacht werden.")) { addCssClasses("fw-bold text-danger") }
    modal.div(
        gettext(
            "Sie buchen eine Spende von %1 für %2. Der " +
                "aktuelle Kurs wird beim Bestätigen live abgefragt; der genaue LTR-Betrag steht erst nach Bestätigung fest.",
            formatDonationAmount(amount, displayCurrency),
            memberDisplayName,
        ),
    )
    if (estimatedLtrMinted != null) {
        modal.div(gettext("Geschätzt: ca. %1 (kann beim Bestätigen leicht abweichen).", formatLtr(estimatedLtrMinted))) {
            addCssClasses("text-muted small")
        }
    } else {
        modal.div(tr("Schätzung nicht verfügbar (Orakel angehalten oder Kurs konnte nicht abgerufen werden).")) {
            addCssClasses("text-muted small")
        }
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Konvertieren"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * CRITICAL LESSON/D2 (`PeerTransferResultDto`'s own established pattern): the real figures the
 * server actually computed, shown inline verbatim -- never a bare success toast alone, never
 * re-summed or re-derived client-side.
 */
private fun renderConversionResult(
    panel: SimplePanel,
    result: PriceOracleConversionDto,
) {
    val box = panel.vPanel(spacing = 2) { addCssClasses("border rounded p-2 bg-body-tertiary small") }
    box.div(tr("Konvertierung ausgeführt.")) { addCssClass("fw-bold") }
    box.div(gettext("Spende: %1", formatDonationAmount(result.donationAmount, result.donationCurrency))) { addCssClass("text-muted") }
    box.div(
        gettext(
            "Kurs: %1 je %2 (Status: %3, %4 Quelle(n): %5)",
            formatDonationAmount(result.anchorPrice, result.donationCurrency),
            anchorAssetLabel(result.anchorAsset),
            priceStatusLabel(result.priceStatus),
            result.sourceCount,
            result.sourcesUsed,
        ),
    ) { addCssClass("text-muted") }
    val mintedRow = box.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    mintedRow.div(tr("Gemintet:")) { addCssClass("text-muted") }
    mintedRow.ltrSpan(result.ltrMinted)
    box.div(gettext("Buchung: %1", result.ltrLedgerEntryId)) { addCssClass("text-muted") }
    box.div(gettext("Zeitstempel: %1", result.priceTimestamp)) { addCssClass("text-muted") }
}

// ================================================================================================
// Formatting + German label/badge-color tables
// ================================================================================================

/**
 * See file KDoc "Every LTR amount ... Donation amounts ...": bare `"$amount $currency"`, no
 * re-rounding/re-deriving of `Decimal.toString()`, the currency-parameterized sibling of
 * `Money.kt`'s [formatMoney] (hardcoded to EUR) -- an empty [currency] renders just the bare number
 * (used for the unit-less peg / anchor-price-without-a-clear-single-unit displays above).
 *
 * `internal` (not `private`) so [PriceOracleScreenTest] can cover it directly, same reasoning as
 * [network.lapis.cloud.client.toInputWithPoliticianRankingEnabled].
 */
internal fun formatDonationAmount(
    amount: Decimal,
    currency: String,
): String = if (currency.isBlank()) "$amount" else "$amount $currency"

/** [typeBadge] grammar (`StatusBadge.kt`): a fixed classification, does not progress -- covers every [AnchorAsset] literal. */
fun anchorAssetLabel(asset: AnchorAsset): String =
    when (asset) {
        AnchorAsset.BITCOIN_BTC -> gettext("Bitcoin (BTC)")
        AnchorAsset.GOLD_XAU -> gettext("Gold (XAU)")
        AnchorAsset.FIAT -> gettext("Fiat")
    }

/** [typeBadge] grammar: [PriceStatus] is a quote-trust classification, not a lifecycle status -- covers every literal. */
fun priceStatusLabel(status: PriceStatus): String =
    when (status) {
        PriceStatus.LIVE -> gettext("Live")
        PriceStatus.DEGRADED -> gettext("Eingeschränkt (Degraded)")
        PriceStatus.CACHED -> gettext("Zwischengespeichert (Cached)")
        PriceStatus.DEFERRED -> gettext("Zurückgestellt (reserviert, ungenutzt)")
    }

fun priceStatusColor(status: PriceStatus): String =
    when (status) {
        PriceStatus.LIVE -> "success"
        PriceStatus.DEGRADED -> "warning"
        PriceStatus.CACHED -> "secondary"
        PriceStatus.DEFERRED -> "secondary"
    }
