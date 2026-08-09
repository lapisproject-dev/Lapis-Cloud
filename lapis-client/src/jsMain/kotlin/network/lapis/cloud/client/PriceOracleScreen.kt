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
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
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
 * **`anchorAsset` disabled-option finding (design decision D12, verified before implementation)**:
 * [AnchorAsset.GOLD_XAU]/[AnchorAsset.FIAT] are real, additively-extensible enum literals, but
 * `updateOracleConfig` hard-rejects anything other than [AnchorAsset.BITCOIN_BTC] server-side (see
 * that interface's own KDoc "Scope-cut"). D12 asked for these two to render as *structurally*
 * disabled `<option>`s rather than a clickable-looking control with a "(noch nicht implementiert)"
 * label bolted on. Checked against this codebase's actual `io.kvision.form.select.select` DSL
 * (`SimpleSelectInput`) before writing this form: its `options` parameter is a plain
 * `List<StringPair>` with no per-option `disabled` field, and no screen anywhere in this client
 * builds a `<select>` any other way (grep confirms zero precedent for a richer/manual option-tag
 * construction). Rather than inventing an unverified widget pattern for a single screen -- exactly
 * the risk this wave's own CRITICAL LESSON (`addCssClass` singular/plural) warns against, and the
 * same "don't invent, verify or fall back" discipline `AuctionScreen.kt`/`PoliticianScreen.kt`
 * already apply to their own first-load `ConflictException` handling -- this form takes the
 * strictly *more* honest fallback Rams' own principle actually calls for: [AnchorAsset.GOLD_XAU]/
 * [AnchorAsset.FIAT] are not offered as selectable options at all (only [AnchorAsset.BITCOIN_BTC]
 * is), with a static help line directly under the select naming both by their scope-cut reason
 * (verbatim from [AnchorAsset]'s own KDoc). A control that never renders as clickable cannot lie
 * about being clickable -- this satisfies D12's honesty requirement without a fabricated KVision
 * API. `donationCurrency` uses a closed `{EUR, USD}` select instead (matches
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
 * ledger and is not reversible via any RPC this interface offers. Its copy deliberately does **not**
 * show a computed/estimated `ltrMinted` figure: doing so would require re-implementing the server's
 * exact formula (`donationAmount / (anchorUnitsPerLtr * anchorPrice)`) client-side against a
 * separately-fetched [OraclePriceStatusDto] quote, precisely the "client-side re-derivation of a
 * server-owned monetary figure" `Money.kt`'s own file KDoc forbids for EUR and this wave extends to
 * LTR by the same reasoning -- the dialog instead states plainly that the live price is fetched at
 * confirmation time and the exact LTR amount is only known afterwards. The real
 * [PriceOracleConversionDto] figures are shown only AFTER a successful call, verbatim, never
 * re-summed. [IPriceOracleService.previewCurrentPrice] gets no confirm dialog -- pure read-only
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
    root.h1("Price-Oracle")
    root.div(
        "Verwaltet die Anker-Bindung des Libertaler (LTR) an ein reales Asset (aktuell: Bitcoin) und bucht " +
            "eingegangene Spenden als LTR-Mint. Sichtbar für TREASURER/BOARD/ADMIN, Konfigurationsänderungen nur für ADMIN.",
    ) { addCssClasses("text-muted small") }

    // ---- Konfiguration ------------------------------------------------------------------------
    root.h2("Konfiguration")
    val configPanel = root.vPanel(spacing = 6)
    configPanel.p("Wird geladen …") { addCssClasses("text-muted small") }

    fun loadConfig() {
        configPanel.removeAll()
        configPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
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
    root.h2("Diagnose: aktueller Kurs")
    root.div("Rein diagnostisch -- prüft die Orakel-Gesundheit, ohne LTR zu minten.") { addCssClasses("text-muted small") }
    val previewRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val previewButton = previewRow.button("Kurs abrufen", style = ButtonStyle.OUTLINESECONDARY)
    val previewBusyLabel = previewRow.div("Wird ausgeführt …") { addCssClasses("text-muted small") }
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
    root.h2("Spende zu LTR konvertieren")
    val convertPanel = root.vPanel(spacing = 6)
    convertPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
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
    row1.labeledValue("Anker-Asset") { it.typeBadge(anchorAssetLabel(config.anchorAsset), "primary") }
    row1.labeledValue("Spendenwährung") { it.div(config.donationCurrency) }
    row1.labeledValue("Peg (Einheiten je LTR)") { it.div(formatDonationAmount(config.anchorUnitsPerLtr, "")) }

    val row2 = box.hPanel(spacing = 16) { addCssClasses("flex-wrap") }
    row2.labeledValue("Cache-TTL") { it.div("${config.cacheTtlSeconds} s") }
    row2.labeledValue("Mindest-Quorum") { it.div("${config.minQuorum} Quellen") }
    row2.labeledValue("Ausreißer-Schwelle") { it.div("${config.outlierThresholdBps} bps") }
    row2.labeledValue("Max. Spread") { it.div("${config.maxSpreadBps} bps") }

    box.div("Zuletzt aktualisiert: ${config.updatedAt}") { addCssClasses("text-muted small") }
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
 * D12 finding (see file KDoc): [AnchorAsset.GOLD_XAU]/[AnchorAsset.FIAT] are deliberately NOT
 * offered as selectable options -- only [AnchorAsset.BITCOIN_BTC] is -- with a static help line
 * naming both by their scope-cut reason instead of a clickable-looking-but-dead control.
 * `donationCurrency` mirrors `PriceOracleService.kt`'s private `SUPPORTED_DONATION_CURRENCIES`
 * (`{EUR, USD}`) as a closed select -- the wave's one deliberate exception to "never duplicate a
 * server constant" (see file KDoc).
 */
private fun renderConfigForm(
    root: SimplePanel,
    config: PriceOracleConfigDto,
    onUpdated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3 mt-2") }
    panel.div("Konfiguration bearbeiten (ADMIN)") { addCssClass("fw-bold") }

    val anchorAssetOptions = listOf(AnchorAsset.BITCOIN_BTC.name to anchorAssetLabel(AnchorAsset.BITCOIN_BTC))
    val anchorAssetSelect =
        panel.select(options = anchorAssetOptions, value = AnchorAsset.BITCOIN_BTC.name, label = "Anker-Asset")
    panel.div(
        "Gold (XAU) und Fiat-Kurse sind fachlich vorgesehen, aber serverseitig noch nicht implementiert (kein " +
            "robuster kostenloser Mehrquellen-Feed verfügbar) -- daher hier nicht auswählbar.",
    ) { addCssClasses("text-muted small") }

    val donationCurrencyOptions = listOf("EUR" to "EUR", "USD" to "USD")
    val donationCurrencySelect =
        panel.select(options = donationCurrencyOptions, value = config.donationCurrency, label = "Spendenwährung")

    val anchorUnitsInput = panel.text(label = "Peg: Einheiten des Anker-Assets je LTR")
    anchorUnitsInput.value = config.anchorUnitsPerLtr.toString()
    val cacheTtlInput = panel.text(label = "Cache-TTL (Sekunden)")
    cacheTtlInput.value = config.cacheTtlSeconds.toString()
    val minQuorumInput = panel.text(label = "Mindest-Quorum (Quellen, mind. 2)")
    minQuorumInput.value = config.minQuorum.toString()
    val outlierThresholdInput = panel.text(label = "Ausreißer-Schwelle (Basispunkte, 1-10000)")
    outlierThresholdInput.value = config.outlierThresholdBps.toString()
    val maxSpreadInput = panel.text(label = "Max. Spread (Basispunkte, >= Ausreißer-Schwelle)")
    maxSpreadInput.value = config.maxSpreadBps.toString()

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val saveButton = panel.button("Konfiguration speichern", style = ButtonStyle.PRIMARY)

    saveButton.onClick {
        errorBox.hide()
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
            minQuorum == null ||
            minQuorum < 2 ||
            outlierThreshold == null ||
            outlierThreshold !in 1..10_000 ||
            maxSpread == null ||
            maxSpread < outlierThreshold
        ) {
            errorBox.content =
                "Bitte alle Felder gültig ausfüllen: Peg positiv, Cache-TTL positiv, Mindest-Quorum mind. 2, " +
                "Ausreißer-Schwelle 1-10000 bps, Max. Spread >= Ausreißer-Schwelle."
            errorBox.show()
            return@onClick
        }
        val anchorUnitsPerLtr = anchorUnitsText.toDouble().toDecimal()

        // Tier 1 "Kostenpflichtig" (D4): plain, neutral-framed confirmDialog -- a policy change,
        // not itself a money movement.
        confirmDialog(
            title = "Konfiguration speichern",
            message =
                "Die Orakel-Konfiguration wird vollständig ersetzt: Anker-Asset ${anchorAssetLabel(AnchorAsset.BITCOIN_BTC)}, " +
                    "Spendenwährung $donationCurrency, Peg $anchorUnitsPerLtr, Cache-TTL ${cacheTtl}s, Quorum " +
                    "$minQuorum, Ausreißer-Schwelle ${outlierThreshold}bps, Max. Spread ${maxSpread}bps.",
            confirmLabel = "Speichern",
        ) {
            saveButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IPriceOracleService>().updateOracleConfig(
                            PriceOracleConfigInput(
                                anchorAsset = AnchorAsset.BITCOIN_BTC,
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
                    notifySuccess("Orakel-Konfiguration aktualisiert.")
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
        box.div("Orakel angehalten (HALT) -- kein aktueller Kurs verfügbar.") { addCssClasses("fw-bold text-danger") }
        box.div(status.haltReason ?: "Kein Grund angegeben.") { addCssClasses("small") }
        return
    }
    val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
    val headerRow = box.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div("Kurs-Status:") { addCssClasses("text-muted small") }
    val priceStatus = status.status
    if (priceStatus != null) {
        headerRow.typeBadge(priceStatusLabel(priceStatus), priceStatusColor(priceStatus))
    }
    box.div("Median-Kurs: ${status.medianPrice?.let { formatDonationAmount(it, "") } ?: "--"}") { addCssClasses("small") }
    box.div("Quellen: ${status.sourceIds.joinToString(", ").ifBlank { "--" }}") { addCssClasses("text-muted small") }
    box.div("Zeitstempel: ${status.priceTimestamp?.toString() ?: "--"}") { addCssClasses("text-muted small") }
}

// ================================================================================================
// Spende zu LTR konvertieren
// ================================================================================================

private fun renderConvertForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
) {
    if (members.isEmpty()) {
        root.p("Keine Mitglieder vorhanden, denen eine Spende gutgeschrieben werden könnte.") { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    panel.div(
        "Bucht eine bereits eingegangene Spende als LTR-Mint auf das gewählte Mitglied. Der aktuelle Kurs wird " +
            "beim Bestätigen live abgefragt -- der genaue LTR-Betrag steht erst nach Bestätigung fest.",
    ) { addCssClasses("text-muted small") }

    val memberSelect = panel.select(options = members.map { it.id to it.displayName }, label = "Mitglied")
    val amountInput = panel.text(label = "Spendenbetrag")
    val currencySelect = panel.select(options = listOf("EUR" to "EUR", "USD" to "USD"), value = "EUR", label = "Anzeige-Info: Währung")
    panel.div(
        "Hinweis: Die tatsächlich gebuchte Währung ist die in der Konfiguration hinterlegte Spendenwährung -- " +
            "diese Auswahl dient nur der Anzeige im Bestätigungsdialog.",
    ) { addCssClasses("text-muted small") }
    val noteInput = panel.text(label = "Notiz (optional)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val outcomePanel = panel.vPanel(spacing = 4)

    val convertRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val convertButton = convertRow.button("Zu LTR konvertieren", style = ButtonStyle.OUTLINEDANGER)
    val convertBusyLabel = convertRow.div("Wird ausgeführt …") { addCssClasses("text-muted small") }
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
            errorBox.content = "Bitte ein Mitglied und einen positiven Spendenbetrag angeben."
            errorBox.show()
            return@onClick
        }
        val amount = amountText.toDouble().toDecimal()

        convertDonationConfirmDialog(member.displayName, amount, displayCurrency) {
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
                    notifySuccess("${formatLtr(result.ltrMinted)} für ${member.displayName} gemintet.")
                    renderConversionResult(outcomePanel, result)
                    amountInput.value = null
                    noteInput.value = null
                }
            }
        }
    }
}

/**
 * Tier 2 "Endgültig" (D4): bespoke modal, same shape as `LtrLedgerScreen.peerTransferConfirmDialog`/
 * `AuctionScreen.placeBidConfirmDialog`. Deliberately does NOT show an estimated `ltrMinted` (see
 * file KDoc "Confirm-dialog tier") -- states plainly that the price is fetched live at confirmation
 * time.
 */
private fun convertDonationConfirmDialog(
    memberDisplayName: String,
    amount: Decimal,
    displayCurrency: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Spenden-Konvertierung bestätigen")
    modal.div("Diese Aktion mintet echtes LTR und kann nicht rückgängig gemacht werden.") { addCssClasses("fw-bold text-danger") }
    modal.div(
        "Sie buchen eine Spende von ${formatDonationAmount(amount, displayCurrency)} für $memberDisplayName. Der " +
            "aktuelle Kurs wird beim Bestätigen live abgefragt; der genaue LTR-Betrag steht erst nach Bestätigung fest.",
    )
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Konvertieren", style = ButtonStyle.DANGER).apply {
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
    box.div("Konvertierung ausgeführt.") { addCssClass("fw-bold") }
    box.div("Spende: ${formatDonationAmount(result.donationAmount, result.donationCurrency)}") { addCssClass("text-muted") }
    box.div(
        "Kurs: ${formatDonationAmount(result.anchorPrice, result.donationCurrency)} je ${anchorAssetLabel(result.anchorAsset)} " +
            "(Status: ${priceStatusLabel(result.priceStatus)}, ${result.sourceCount} Quelle(n): ${result.sourcesUsed})",
    ) { addCssClass("text-muted") }
    val mintedRow = box.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    mintedRow.div("Gemintet:") { addCssClass("text-muted") }
    mintedRow.ltrSpan(result.ltrMinted)
    box.div("Buchung: ${result.ltrLedgerEntryId}") { addCssClass("text-muted") }
    box.div("Zeitstempel: ${result.priceTimestamp}") { addCssClass("text-muted") }
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
        AnchorAsset.BITCOIN_BTC -> "Bitcoin (BTC)"
        AnchorAsset.GOLD_XAU -> "Gold (XAU)"
        AnchorAsset.FIAT -> "Fiat"
    }

/** [typeBadge] grammar: [PriceStatus] is a quote-trust classification, not a lifecycle status -- covers every literal. */
fun priceStatusLabel(status: PriceStatus): String =
    when (status) {
        PriceStatus.LIVE -> "Live"
        PriceStatus.DEGRADED -> "Eingeschränkt (Degraded)"
        PriceStatus.CACHED -> "Zwischengespeichert (Cached)"
        PriceStatus.DEFERRED -> "Zurückgestellt (reserviert, ungenutzt)"
    }

fun priceStatusColor(status: PriceStatus): String =
    when (status) {
        PriceStatus.LIVE -> "success"
        PriceStatus.DEGRADED -> "warning"
        PriceStatus.CACHED -> "secondary"
        PriceStatus.DEFERRED -> "secondary"
    }
