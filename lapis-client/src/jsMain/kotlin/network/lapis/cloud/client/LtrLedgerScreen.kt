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
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArbitrationTransferInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.domain.PeerTransferResultDto
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IPeerTransferService

/**
 * LTR-Wirtschaft UI wave, screen 1 of 5 -- "LTR-Konto", the LTR-economy home. Hosts
 * [ILtrLedgerService] (own balance/entries always self-service; another member's balance/entries
 * and [ILtrLedgerService.mintLtr] gated TREASURER/BOARD/ADMIN) AND both [IPeerTransferService]
 * forms ([IPeerTransferService.transferLtr] self-service MEMBER+/AKTIV; [IPeerTransferService
 * .executeArbitrationTransfer] TREASURER/BOARD/ADMIN-only) on the same screen -- that interface's
 * own KDoc documents that transfer history is deliberately NOT exposed through a second, parallel
 * read path: every transfer's two ledger rows (`referenceType == PEER_TRANSFER`) already surface
 * through [ILtrLedgerService.listMyEntries]/`listMemberEntries`, reused as-is. A client-side-only
 * `referenceType` filter is layered over the already-fetched entry list below -- no new RPC.
 *
 * **Role gating** (verified against `LtrLedgerService.kt`/`PeerTransferService.kt`, not guessed
 * from method names -- see `Routes.LTR_LEDGER` KDoc for the route-level `requireAuth` reasoning):
 * - [ILtrLedgerService.getMyBalance]/[ILtrLedgerService.listMyEntries] -- any authenticated member,
 *   always their own data. Rendered unconditionally at the top of this screen.
 * - [ILtrLedgerService.getMemberBalance]/[ILtrLedgerService.listMemberEntries] for a DIFFERENT
 *   member, and [ILtrLedgerService.mintLtr] -- `current.requireRole(TREASURER, BOARD, ADMIN)`
 *   server-side. Gated here as `canTreasury`.
 * - [IPeerTransferService.transferLtr] -- MEMBER+, but the caller must additionally be AKTIV,
 *   checked via `requireActiveMembership` INSIDE the server transaction, not reachable as an
 *   `AccountRole` predicate -- [network.lapis.cloud.shared.domain.SessionInfoDto] carries no
 *   member-status field at all, so this client cannot pre-gate on AKTIV the way it can on role.
 *   A non-AKTIV caller sees the ordinary `guarded()` ConflictException toast, same established
 *   pattern as `CrowdfundingService.submitProject`/`MotionsScreen`'s ballot form.
 * - [IPeerTransferService.executeArbitrationTransfer] -- `current.requireRole(TREASURER, BOARD,
 *   ADMIN)` server-side. Gated here as `canTreasury`, same tier as the mint form.
 *
 * **Layout (design decision D3, staged disclosure)**: fixed vertical order for every member --
 * (1) balance card, (2) own-entries table + `referenceType` filter, (3) the self-service
 * Peer-Transfer send form (a MEMBER+ action, deliberately kept OUTSIDE the treasury panel so it is
 * never mistaken for a privileged tool), (4) a visually separated, clearly labeled
 * "Treuhänder-Werkzeuge" panel rendered only under `canTreasury`, hosting the member-lookup,
 * mint form, and arbitration-transfer form.
 *
 * **Confirm-dialog severity (design decision D4)**: [mintLtr] uses the plain, neutral-framed
 * [confirmDialog] (Tier 1 "Kostenpflichtig" -- material to the recipient, not costly to the
 * treasurer). [transferLtr]/[executeArbitrationTransfer] use bespoke, unmissable-danger-framed
 * modals ([peerTransferConfirmDialog]/[arbitrationTransferConfirmDialog], Tier 2 "Endgültig") that
 * restate recipient/amount/characterization in full-sentence form before the caller can confirm --
 * design decision D8, matching `PostalMailScreen.kt`'s dispatch-confirm-dialog rigor. All three
 * writes additionally disable their trigger button for the duration of the in-flight request
 * (double-submit protection, `LedgerScreen.postDirectButton`'s idiom) -- these move real,
 * mostly-irreversible LTR value, per the wave's CRITICAL LESSON. No dedicated shared tier-preset
 * component exists yet in `ConfirmDialog.kt` (that cross-cutting infrastructure spans all five
 * screens of this wave and is out of scope for a single-screen change) -- this file's two bespoke
 * modals follow the tier-2 copy discipline by hand, same as `PostalMailScreen`/`BackupScreen`'s own
 * pre-existing bespoke modals do today.
 *
 * Every LTR amount is rendered via [ltrSpan]/[formatLtr] (`Money.kt`, design decision D2) -- never
 * hand-formatted. [PeerTransferResultDto]/[LtrLedgerEntryDto] figures returned by the server are
 * shown verbatim, never re-summed or re-derived client-side.
 */
fun renderLtrLedgerScreen(container: SimplePanel) {
    val canTreasury = AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    val currentMemberId = AppState.session?.memberId

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("LTR-Konto")

    // ---- (1) Balance card ------------------------------------------------------------------
    val balanceCard = root.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
    balanceCard.p("Wird geladen …") { addCssClasses("text-muted small") }

    fun refreshBalance() {
        balanceCard.removeAll()
        AppScope.launch {
            val balance = guarded { rpcService<ILtrLedgerService>().getMyBalance() } ?: return@launch
            balanceCard.removeAll()
            val row = balanceCard.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            row.div("Ihr LTR-Guthaben") { addCssClasses("fw-bold flex-grow-1") }
            row.ltrSpan(balance.freeBalanceLtr)
        }
    }
    refreshBalance()

    // ---- (2) Own entries + referenceType filter (D10 empty state) --------------------------
    root.h2("Meine Buchungen")
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val referenceFilterOptions =
        listOf("" to "Alle") +
            listOf("NONE" to "Ohne Referenz (z. B. Gutschrift)") +
            LtrLedgerReferenceType.entries.map { it.name to ltrLedgerReferenceTypeLabel(it) }
    val referenceFilterSelect = filterRow.select(options = referenceFilterOptions, value = "", label = "Filter: Referenztyp")
    val entriesRefreshButton = filterRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val entriesPanel = root.vPanel(spacing = 6)

    var myEntriesCache: List<LtrLedgerEntryDto> = emptyList()

    fun applyEntryFilter() {
        entriesPanel.removeAll()
        val filterValue = referenceFilterSelect.value.orEmpty()
        val filtered =
            when (filterValue) {
                "" -> myEntriesCache
                "NONE" -> myEntriesCache.filter { it.referenceType == null }
                else -> myEntriesCache.filter { it.referenceType?.name == filterValue }
            }
        if (myEntriesCache.isEmpty()) {
            entriesPanel.p("Noch keine Buchungen.")
        } else if (filtered.isEmpty()) {
            entriesPanel.p("Keine Buchungen für diesen Filter.")
        } else {
            renderLtrEntriesTable(entriesPanel, filtered)
        }
    }

    fun refreshMyEntries() {
        entriesPanel.removeAll()
        entriesPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val entries = guarded { rpcService<ILtrLedgerService>().listMyEntries() } ?: return@launch
            myEntriesCache = entries
            applyEntryFilter()
        }
    }
    entriesRefreshButton.onClick { refreshMyEntries() }
    referenceFilterSelect.subscribe { applyEntryFilter() }
    refreshMyEntries()

    // Members list backs every member-picker on this screen (self-service recipient picker AND,
    // if `canTreasury`, the treasury member-lookup/mint/arbitration pickers) -- fetched once here,
    // `IMemberService.listMembers()` is unauthenticated-safe/AKTIV-only per its own KDoc.
    root.h2("LTR senden")
    val transferSectionPanel = root.vPanel(spacing = 6)
    val treasuryPanel =
        if (canTreasury) {
            root.vPanel(spacing = 10) { addCssClasses("border rounded p-3 mt-2") }
        } else {
            null
        }

    AppScope.launch {
        val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        val recipientCandidates = members.filter { it.id != currentMemberId }

        // ---- (3) Self-service Peer-Transfer send form (outside the treasury panel) ----------
        renderPeerTransferForm(transferSectionPanel, recipientCandidates) {
            refreshBalance()
            refreshMyEntries()
        }

        // ---- (4) Treuhänder-Werkzeuge (canTreasury only) ------------------------------------
        if (treasuryPanel != null) {
            treasuryPanel.h2("Treuhänder-Werkzeuge") { addCssClass("h5") }
            treasuryPanel.div("Sichtbar für TREASURER/BOARD/ADMIN.") { addCssClasses("text-muted small mb-2") }

            renderMemberLookupSection(treasuryPanel, members)
            renderMintForm(treasuryPanel, members) {
                refreshBalance()
                refreshMyEntries()
            }
            renderArbitrationTransferForm(treasuryPanel, members) {
                refreshBalance()
                refreshMyEntries()
            }
        }
    }
}

/**
 * LTR-Wirtschaft UI wave, design decision D3 ("Atkinson: always know your resources before you
 * commit them") -- a small "Ihr LTR-Guthaben: X LTR [Zum LTR-Konto ->]" strip, consumed by
 * `CrowdfundingScreen`/`AuctionScreen` at the very top of their respective forms (before any input
 * field, identical position on both screens per D3). Fetches [ILtrLedgerService.getMyBalance]
 * independently on every call -- deliberately not threaded down from a caller, since the whole
 * point is "works standalone, one line, no extra plumbing at the call site".
 */
fun SimplePanel.renderMyLtrBalanceInline(): SimplePanel {
    val panel = this.hPanel(spacing = 8) { addCssClasses("align-items-center border rounded p-2 mb-2") }
    panel.div("Wird geladen …") { addCssClasses("text-muted small") }
    AppScope.launch {
        val balance = guarded { rpcService<ILtrLedgerService>().getMyBalance() }
        panel.removeAll()
        panel.div("Ihr LTR-Guthaben:") { addCssClasses("text-muted small") }
        if (balance != null) {
            panel.ltrSpan(balance.freeBalanceLtr)
        } else {
            panel.div("--") { addCssClasses("text-muted small") }
        }
        panel.link("Zum LTR-Konto ->", url = "#${Routes.LTR_LEDGER}") { addCssClasses("ms-auto small") }
    }
    return panel
}

// ================================================================================================
// Entries table
// ================================================================================================

private fun renderLtrEntriesTable(
    panel: SimplePanel,
    entries: List<LtrLedgerEntryDto>,
) {
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div("Datum") { width = 140.px }
    headerRow.div("Typ") { width = 200.px }
    headerRow.div("Betrag") { width = 120.px }
    headerRow.div("Referenz") { addCssClasses("flex-grow-1") }
    headerRow.div("Notiz / erstellt von") { width = 200.px }

    entries.forEach { entry ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(entry.createdAt.toString()) { width = 140.px }
        val typeCell = row.div { width = 200.px }
        typeCell.typeBadge(ltrLedgerEntryTypeLabel(entry.entryType), ltrLedgerEntryTypeColor(entry.entryType))
        val amountCell = row.div { width = 120.px }
        amountCell.ltrSpan(entry.amountLtr, warnIfNegative = true)
        row.div(entry.referenceType?.let { ltrLedgerReferenceTypeLabel(it) } ?: "--") { addCssClasses("flex-grow-1 text-muted small") }
        val noteParts = listOfNotNull(entry.note, entry.createdByDisplayName?.let { "von $it" })
        row.div(if (noteParts.isEmpty()) "--" else noteParts.joinToString(" · ")) {
            width = 200.px
            addCssClasses("text-muted small")
        }
    }
}

// ================================================================================================
// (3) Self-service Peer-Transfer send form
// ================================================================================================

private fun renderPeerTransferForm(
    root: SimplePanel,
    recipientCandidates: List<MemberSummaryDto>,
    onCompleted: () -> Unit,
) {
    root.removeAll()
    if (recipientCandidates.isEmpty()) {
        root.p("Keine anderen Mitglieder vorhanden, an die übertragen werden könnte.") { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    val recipientSelect =
        panel.select(options = recipientCandidates.map { it.id to it.displayName }, label = "Empfänger")
    val amountInput = panel.text(label = "Betrag (LTR)")
    val characterizationOptions = PeerTransferCharacterization.entries.map { it.name to peerTransferCharacterizationLabel(it) }
    val characterizationSelect =
        panel.select(options = characterizationOptions, value = PeerTransferCharacterization.SONSTIGES.name, label = "Charakterisierung")
    val purposeInput = panel.text(label = "Zweck (optional)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val outcomePanel = panel.vPanel(spacing = 4)

    val sendButton = panel.button("LTR übertragen", style = ButtonStyle.OUTLINEDANGER)
    sendButton.onClick {
        errorBox.hide()
        outcomePanel.removeAll()
        val recipientId = recipientSelect.value
        val recipient = recipientCandidates.find { it.id == recipientId }
        val amountText = amountInput.value.orEmpty().trim()
        val characterizationValue = characterizationSelect.value
        val characterization = characterizationValue?.let { runCatching { PeerTransferCharacterization.valueOf(it) }.getOrNull() }
        val purpose = purposeInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (recipient == null || !Validation.isPositiveDecimal(amountText) || characterization == null) {
            errorBox.content = "Bitte Empfänger, einen positiven LTR-Betrag und eine Charakterisierung angeben."
            errorBox.show()
            return@onClick
        }
        val amount = amountText.toDouble().toDecimal()

        // D8: recipient display name + amount + characterization echoed back in full-sentence
        // form BEFORE the caller can confirm, not left to the specifics-only-in-form-fields shape.
        peerTransferConfirmDialog(recipient.displayName, amount, characterization) {
            sendButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IPeerTransferService>().transferLtr(
                            PeerTransferInput(
                                recipientMemberId = recipient.id,
                                amountLtr = amount,
                                characterization = characterization,
                                purpose = purpose,
                            ),
                        )
                    }
                sendButton.disabled = false
                if (result != null) {
                    notifySuccess("${formatLtr(result.amountLtr)} an ${result.recipientDisplayName} übertragen.")
                    renderPeerTransferResult(outcomePanel, result)
                    amountInput.value = null
                    purposeInput.value = null
                    onCompleted()
                }
            }
        }
    }
}

/**
 * D8/CRITICAL LESSON: the two ledger-entry ids ([PeerTransferResultDto.outEntryId]/[inEntryId])
 * are the audit trail a member/treasurer would actually want to see immediately -- shown inline,
 * never a bare success toast alone (the toast above still fires for the ambient confirmation, this
 * panel is the durable, re-readable record of what actually happened).
 */
private fun renderPeerTransferResult(
    panel: SimplePanel,
    result: PeerTransferResultDto,
) {
    val box = panel.vPanel(spacing = 2) { addCssClasses("border rounded p-2 bg-body-tertiary small") }
    box.div("Übertragung ausgeführt: ${formatLtr(result.amountLtr)} von ${result.senderDisplayName} an ${result.recipientDisplayName}") {
        addCssClass("fw-bold")
    }
    box.div("Charakterisierung: ${peerTransferCharacterizationLabel(result.characterization)}") { addCssClass("text-muted") }
    result.purpose?.let { box.div("Zweck: $it") { addCssClass("text-muted") } }
    box.div("Buchung (Soll): ${result.outEntryId}") { addCssClass("text-muted") }
    box.div("Buchung (Haben): ${result.inEntryId}") { addCssClass("text-muted") }
}

/**
 * Design decision D8, Tier 2 "Endgültig" (D4): bespoke modal, NOT the generic [confirmDialog] --
 * matches `PostalMailScreen.postalDispatchConfirmDialog`'s irreversibility-bar styling. Restates
 * recipient + amount + characterization in one full sentence, not just left in the form fields
 * above, and states plainly that no Storno/Widerruf function exists (`18-peer-transfer.kuml.kts`'s
 * own "Unwiderruflichkeit" framing).
 */
private fun peerTransferConfirmDialog(
    recipientDisplayName: String,
    amount: Decimal,
    characterization: PeerTransferCharacterization,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "LTR-Übertragung bestätigen")
    modal.div("Diese Übertragung ist ENDGÜLTIG und kann nicht widerrufen werden.") { addCssClasses("fw-bold text-danger") }
    modal.div(
        "Sie übertragen ${formatLtr(amount)} an $recipientDisplayName " +
            "(${peerTransferCharacterizationLabel(characterization)}). Es gibt keine Storno-/Widerruffunktion -- " +
            "die einzige Korrekturmöglichkeit ist eine Schiedsverfahren-Korrekturübertragung durch TREASURER/BOARD/ADMIN.",
    )
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Endgültig übertragen", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// (4) Treuhänder-Werkzeuge -- member lookup, mint, arbitration transfer
// ================================================================================================

private fun renderMemberLookupSection(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
) {
    root.h2("Guthaben & Buchungen eines Mitglieds") { addCssClass("h6") }
    if (members.isEmpty()) {
        root.p("Keine Mitglieder vorhanden.") { addCssClasses("text-muted small") }
        return
    }
    val row = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val memberSelect = row.select(options = members.map { it.id to it.displayName }, label = "Mitglied")
    val showButton = row.button("Anzeigen", style = ButtonStyle.OUTLINESECONDARY)
    val resultPanel = root.vPanel(spacing = 6)

    showButton.onClick {
        val memberId = memberSelect.value ?: return@onClick
        val displayName = members.find { it.id == memberId }?.displayName.orEmpty()
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val balance = guarded { rpcService<ILtrLedgerService>().getMemberBalance(memberId) }
            val entries = guarded { rpcService<ILtrLedgerService>().listMemberEntries(memberId) }
            resultPanel.removeAll()
            if (balance == null || entries == null) return@launch

            val balanceRow = resultPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            balanceRow.div("Guthaben von $displayName") { addCssClasses("fw-bold flex-grow-1") }
            balanceRow.ltrSpan(balance.freeBalanceLtr)

            if (entries.isEmpty()) {
                resultPanel.p("Noch keine Buchungen.")
            } else {
                renderLtrEntriesTable(resultPanel, entries)
            }
        }
    }
}

private fun renderMintForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
    onCompleted: () -> Unit,
) {
    root.h2("LTR gutschreiben (Mint)") { addCssClass("h6") }
    if (members.isEmpty()) {
        root.p("Keine Mitglieder vorhanden.") { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    val memberSelect = panel.select(options = members.map { it.id to it.displayName }, label = "Mitglied")
    val amountInput = panel.text(label = "Betrag (LTR)")
    val noteInput = panel.text(label = "Notiz (optional)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val mintButton = panel.button("Gutschreiben", style = ButtonStyle.PRIMARY)
    mintButton.onClick {
        errorBox.hide()
        val memberId = memberSelect.value
        val member = members.find { it.id == memberId }
        val amountText = amountInput.value.orEmpty().trim()
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (member == null || !Validation.isPositiveDecimal(amountText)) {
            errorBox.content = "Bitte Mitglied und einen positiven LTR-Betrag angeben."
            errorBox.show()
            return@onClick
        }
        val amount = amountText.toDouble().toDecimal()

        // Tier 1 "Kostenpflichtig" (D4): the plain, neutral-framed confirmDialog -- a MINT is
        // material to the recipient/organization but not costly to the treasurer performing it.
        confirmDialog(
            title = "LTR gutschreiben",
            message =
                "Es werden ${formatLtr(amount)} an ${member.displayName} gutgeschrieben. Diese Gutschrift lässt sich " +
                    "nur durch eine gegenläufige Buchung (z. B. eine Schiedsverfahren-Korrekturübertragung) rückgängig machen.",
            confirmLabel = "Gutschreiben",
        ) {
            mintButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<ILtrLedgerService>().mintLtr(MintLtrInput(memberId = member.id, amountLtr = amount, note = note))
                    }
                mintButton.disabled = false
                if (result != null) {
                    notifySuccess("${formatLtr(amount)} an ${member.displayName} gutgeschrieben.")
                    amountInput.value = null
                    noteInput.value = null
                    onCompleted()
                }
            }
        }
    }
}

private fun renderArbitrationTransferForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
    onCompleted: () -> Unit,
) {
    root.h2("Schiedsverfahren-Korrekturübertragung") { addCssClass("h6") }
    if (members.size < 2) {
        root.p("Mindestens zwei Mitglieder erforderlich.") { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    val memberOptions = members.map { it.id to it.displayName }
    val senderSelect = panel.select(options = memberOptions, label = "Absender")
    val recipientSelect = panel.select(options = memberOptions, label = "Empfänger")
    val amountInput = panel.text(label = "Betrag (LTR)")
    val characterizationOptions = PeerTransferCharacterization.entries.map { it.name to peerTransferCharacterizationLabel(it) }
    val characterizationSelect =
        panel.select(options = characterizationOptions, value = PeerTransferCharacterization.SONSTIGES.name, label = "Charakterisierung")
    val purposeInput = panel.text(label = "Schiedsanordnungs-Referenz (Pflichtfeld)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val outcomePanel = panel.vPanel(spacing = 4)

    val executeButton = panel.button("Korrekturübertragung ausführen", style = ButtonStyle.OUTLINEDANGER)
    executeButton.onClick {
        errorBox.hide()
        outcomePanel.removeAll()
        val senderId = senderSelect.value
        val recipientId = recipientSelect.value
        val sender = members.find { it.id == senderId }
        val recipient = members.find { it.id == recipientId }
        val amountText = amountInput.value.orEmpty().trim()
        val characterizationValue = characterizationSelect.value
        val characterization = characterizationValue?.let { runCatching { PeerTransferCharacterization.valueOf(it) }.getOrNull() }
        val purpose = purposeInput.value.orEmpty().trim()

        if (sender == null ||
            recipient == null ||
            sender.id == recipient.id ||
            !Validation.isPositiveDecimal(amountText) ||
            characterization == null ||
            !Validation.isNonBlank(purpose)
        ) {
            errorBox.content =
                "Bitte unterschiedliche Absender/Empfänger, einen positiven LTR-Betrag, eine Charakterisierung " +
                "und eine Schiedsanordnungs-Referenz angeben."
            errorBox.show()
            return@onClick
        }
        val amount = amountText.toDouble().toDecimal()

        arbitrationTransferConfirmDialog(sender.displayName, recipient.displayName, amount, characterization, purpose) {
            executeButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IPeerTransferService>().executeArbitrationTransfer(
                            ArbitrationTransferInput(
                                senderMemberId = sender.id,
                                recipientMemberId = recipient.id,
                                amountLtr = amount,
                                characterization = characterization,
                                purpose = purpose,
                            ),
                        )
                    }
                executeButton.disabled = false
                if (result != null) {
                    notifySuccess("Korrekturübertragung ausgeführt: ${formatLtr(result.amountLtr)}.")
                    renderPeerTransferResult(outcomePanel, result)
                    amountInput.value = null
                    purposeInput.value = null
                    onCompleted()
                }
            }
        }
    }
}

/** Tier 2 "Endgültig" (D4), same bespoke-modal shape as [peerTransferConfirmDialog] -- additionally
 * echoes the mandatory Schiedsanordnungs-Referenz ([purpose]) since that reference is the entire
 * legal justification for this privileged correction. */
private fun arbitrationTransferConfirmDialog(
    senderDisplayName: String,
    recipientDisplayName: String,
    amount: Decimal,
    characterization: PeerTransferCharacterization,
    purpose: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Schiedsverfahren-Korrekturübertragung bestätigen")
    modal.div("Diese Korrekturübertragung ist ENDGÜLTIG und kann nicht widerrufen werden.") { addCssClasses("fw-bold text-danger") }
    modal.div(
        "Sie übertragen ${formatLtr(amount)} von $senderDisplayName an $recipientDisplayName " +
            "(${peerTransferCharacterizationLabel(characterization)}). Schiedsanordnungs-Referenz: „$purpose\".",
    )
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Endgültig ausführen", style = ButtonStyle.DANGER).apply {
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

/** [typeBadge] grammar (D2/`StatusBadge.kt`): an entry's kind is a fixed classification, not a
 * lifecycle status that progresses. Covers every [LtrLedgerEntryType] literal. */
fun ltrLedgerEntryTypeLabel(type: LtrLedgerEntryType): String =
    when (type) {
        LtrLedgerEntryType.MINT -> "Gutschrift (Mint)"
        LtrLedgerEntryType.PROJECT_STAKE -> "Projekteinsatz"
        LtrLedgerEntryType.PROJECT_STAKE_RELEASE -> "Projekteinsatz freigegeben"
        LtrLedgerEntryType.VOTE_STAKE -> "Abstimmungseinsatz"
        LtrLedgerEntryType.PEER_TRANSFER_OUT -> "Übertragung gesendet"
        LtrLedgerEntryType.PEER_TRANSFER_IN -> "Übertragung empfangen"
        LtrLedgerEntryType.AUCTION_LISTING_FEE -> "Auktions-Einstellgebühr"
        LtrLedgerEntryType.AUCTION_HOLD -> "Auktions-Reservierung"
        LtrLedgerEntryType.AUCTION_HOLD_RELEASE -> "Auktions-Reservierung freigegeben"
        LtrLedgerEntryType.AUCTION_SALE_OUT -> "Auktionskauf"
        LtrLedgerEntryType.AUCTION_SALE_IN -> "Auktionsverkauf"
    }

fun ltrLedgerEntryTypeColor(type: LtrLedgerEntryType): String =
    when (type) {
        LtrLedgerEntryType.MINT -> "success"
        LtrLedgerEntryType.PROJECT_STAKE -> "warning"
        LtrLedgerEntryType.PROJECT_STAKE_RELEASE -> "info"
        LtrLedgerEntryType.VOTE_STAKE -> "warning"
        LtrLedgerEntryType.PEER_TRANSFER_OUT -> "danger"
        LtrLedgerEntryType.PEER_TRANSFER_IN -> "success"
        LtrLedgerEntryType.AUCTION_LISTING_FEE -> "secondary"
        LtrLedgerEntryType.AUCTION_HOLD -> "warning"
        LtrLedgerEntryType.AUCTION_HOLD_RELEASE -> "info"
        LtrLedgerEntryType.AUCTION_SALE_OUT -> "danger"
        LtrLedgerEntryType.AUCTION_SALE_IN -> "success"
    }

fun ltrLedgerReferenceTypeLabel(type: LtrLedgerReferenceType): String =
    when (type) {
        LtrLedgerReferenceType.CROWDFUNDING_PROJECT -> "Crowdfunding-Projekt"
        LtrLedgerReferenceType.VOTE -> "Abstimmung"
        LtrLedgerReferenceType.PEER_TRANSFER -> "Peer-Transfer"
        LtrLedgerReferenceType.AUCTION -> "Auktion"
    }

fun peerTransferCharacterizationLabel(characterization: PeerTransferCharacterization): String =
    when (characterization) {
        PeerTransferCharacterization.SCHENKUNG -> "Schenkung"
        PeerTransferCharacterization.HONORAR -> "Honorar"
        PeerTransferCharacterization.PRIVATVERKAUF -> "Privatverkauf"
        PeerTransferCharacterization.SONSTIGES -> "Sonstiges"
    }
