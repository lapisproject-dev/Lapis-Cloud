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
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefRequestInput
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionReliefStatusSets
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.rpc.IContributionReliefService
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IPostalMailService
import kotlin.time.Clock

/**
 * Screen 5 of the V0.7.3 plan -- the `IContributionService` backend has existed since V0.1/V0.4;
 * only the UI was missing. Every caller sees their own summary; TREASURER/BOARD/ADMIN additionally
 * see the org-wide table (mirrors `listContributions`'s own `isPrivileged || TREASURER`
 * authorization, see that method's KDoc), with "als bezahlt markieren" limited to TREASURER/ADMIN
 * and "als erlassen markieren" limited to BOARD/ADMIN (TREASURER may pay but not waive, per
 * `markContributionWaived`'s own role check) -- the tier-administration sub-panel
 * (`generateContributionsForPeriod`) is TREASURER/ADMIN only, matching `createMembershipTier`'s
 * own role check.
 *
 * Mail-merge/Postal-Dispatch UI wave, design decision D3: [renderContributionRow] additionally
 * renders a "Rechnung (PDF)" download link ([MailmergeHttp.invoiceUrl]) -- only inside
 * [renderOrgWideContributions] (already TREASURER/BOARD/ADMIN-gated by the caller, matching
 * `MailmergeRoutes.kt`'s `FINANCIAL_DOC_ROLES` exactly), never inside [renderOwnSummary]. A member
 * cannot self-serve their own invoice this wave -- see [MailmergeHttp] KDoc for the verified
 * server-side access tier this deliberately mirrors.
 *
 * Design decision D5: the same row additionally renders a "Per Post versenden" postal-dispatch
 * trigger (`IPostalMailService.dispatchBeitragsrechnungByPost`, matching `FINANCIAL_DISPATCH_ROLES`
 * -- the same TREASURER/BOARD/ADMIN tier as the row itself, no extra in-row gating needed) next to
 * the PDF link, gated by [isPostalMailEnabled] (D7) and confirmed via [postalDispatchConfirmDialog]
 * (D5) -- see `PostalMailScreen.kt`'s file KDoc for the "address never touches the browser"
 * load-bearing finding that shapes that dialog's copy.
 *
 * Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche" -- [renderOwnSummary] and the new
 * [renderOwnReliefRequests] are two SEPARATE local panels rebuilt as a UNIT (see
 * [reloadOwnSections] below) whenever either mutates relief state: a freshly-requested DEFERRAL
 * must immediately block the "Stundung beantragen" button on every OTHER open contribution row too
 * (see [activeBlockingDeferralRequest]), which [renderOwnSummary] can only know by re-fetching. Both
 * panels and the reload closure are plain local variables/functions of [renderContributionsScreen]
 * -- no module-level `var`, rebuilt from scratch on every call (repo convention).
 */
fun renderContributionsScreen(container: SimplePanel) {
    val session =
        AppState.session ?: run {
            navigateTo(Routes.LOGIN)
            return
        }
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 760.px
            marginTop = 24.px
        }
    root.h1(tr("Beitragsübersicht"))

    // V1.2.2 SEPA-Client-UI wave -- see SepaMandateSection.kt file KDoc "K1". Owns its own panel,
    // renders nothing at all for a plain MEMBER when SEPA is disabled for this organization.
    renderSepaMandateSection(root)

    // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- see PspCheckoutSection.kt file
    // KDoc. Owns its own panel, renders nothing at all for a plain MEMBER when online payment is
    // disabled for this organization -- same S-16/K1 treatment as renderSepaMandateSection above.
    renderPspCheckoutSection(root)

    val ownSummaryPanel = root.vPanel(spacing = 4)
    val ownReliefPanel = root.vPanel(spacing = 4)

    fun reloadOwnSections() {
        ownSummaryPanel.removeAll()
        ownReliefPanel.removeAll()
        renderOwnSummary(ownSummaryPanel, session.memberId, ::reloadOwnSections)
        renderOwnReliefRequests(ownReliefPanel, session.memberId, ::reloadOwnSections)
    }
    reloadOwnSections()

    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)) {
        renderTierAdministration(root)
    }
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN, AccountRole.BOARD)) {
        renderOrgWideContributions(root)
    }
}

/**
 * Welle V1.4.10.1: fetches [IContributionService.getMemberContributionSummary] AND
 * [IContributionReliefService.listMyReliefRequests] sequentially in the same coroutine (same
 * idiom as [renderOrgWideContributions]'s own `isPostalMailEnabled()` + `listContributions()`
 * pair) -- an open, still-blocking DEFERRAL request (see [activeBlockingDeferralRequest]) hides
 * every "Stundung beantragen" button on this table (Design-Team Norman/Atkinson: the block is
 * per-member, not per-row) and shows a single notice above the table instead, plus a
 * "Stundung beantragt" badge on the specific row it targets.
 */
private fun renderOwnSummary(
    panel: SimplePanel,
    memberId: String,
    onReliefChanged: () -> Unit,
) {
    panel.h2(tr("Meine Beiträge"))
    val contentPanel = panel.vPanel(spacing = 4)
    AppScope.launch {
        val summary = guarded { rpcService<IContributionService>().getMemberContributionSummary(memberId) } ?: return@launch
        val reliefRequests = guarded { rpcService<IContributionReliefService>().listMyReliefRequests() }.orEmpty()
        contentPanel.div(gettext("Offen: %1 | Bezahlt: %2 | Gesamt: %3", summary.totalOpen, summary.totalPaid, summary.totalDue))
        // Welle V1.4.4.1 "Beitragshistorie" -- der zweite von zwei Einstiegen in
        // MemberFinancialHistoryScreen.kt (der erste ist der Roster-Button in
        // MemberAdministrationScreen.kt). memberFinancesRoute(null) -> die eigene Historie.
        val historyLink = contentPanel.button(tr("Vollständige Beitragshistorie"), style = ButtonStyle.LINK)
        historyLink.onClick { navigateTo(memberFinancesRoute(null)) }

        val blockingRequest = activeBlockingDeferralRequest(reliefRequests)
        if (blockingRequest != null) {
            contentPanel.div(gettext("Stundungsantrag vom %1 in Bearbeitung", blockingRequest.requestedAt)) {
                addCssClasses("alert alert-info")
            }
        }

        if (summary.contributions.isEmpty()) {
            contentPanel.p(tr("Keine Beiträge vorhanden."))
        } else {
            // UI theme redesign wave (2026-08-20): real Bootstrap table (table-striped/table-hover),
            // replacing the previous hand-rolled "border-bottom py-1" div-per-row list -- see root
            // CLAUDE.md "UI/UX-Design-Team" review.
            val table =
                contentPanel.table(
                    headerNames = listOf(tr("Zeitraum"), tr("Betrag"), tr("Status"), tr("Aktionen")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            summary.contributions.forEach { contribution ->
                renderOwnContributionRow(table, contribution, memberId, blockingRequest, onReliefChanged)
            }
        }
    }
}

private fun renderOwnContributionRow(
    table: Table,
    contribution: ContributionDto,
    memberId: String,
    blockingRequest: ContributionReliefRequestDto?,
    onReliefChanged: () -> Unit,
) {
    table.row {
        cell(gettext("%1 bis %2", contribution.periodStart, contribution.periodEnd))
        cell(contribution.amountDue.toString())
        // Jobs' Nebenbefund (Punkt 8): ein Status-Badge statt des rohen Enum-`.toString()` -- nur in
        // dieser (mit dieser Welle neu um eine Aktionen-Spalte ergänzten) Tabelle, siehe Klassen-KDoc.
        cell {
            statusBadge(contributionStatusLabel(contribution.status), contributionStatusColor(contribution.status))
            if (blockingRequest != null && contribution.id == blockingRequest.deferralContributionId) {
                typeBadge(tr("Stundung beantragt"), "warning")
            }
        }
        val actionsCell = cell()
        if (canRequestDeferral(contribution.status, blockingRequest)) {
            val requestButton = actionsCell.button(tr("Stundung beantragen"), style = ButtonStyle.OUTLINEWARNING)
            requestButton.onClick { openDeferralRequestDialog(memberId, contribution, onReliefChanged) }
        }
    }
}

/** Pure -- see `ContributionsScreenTest`. `null` = nothing currently blocks a new DEFERRAL request. */
internal fun activeBlockingDeferralRequest(requests: List<ContributionReliefRequestDto>): ContributionReliefRequestDto? =
    requests.firstOrNull { it.kind == ContributionReliefKind.DEFERRAL && it.status in ContributionReliefStatusSets.BLOCKS_NEW_REQUEST }

/** Pure -- see `ContributionsScreenTest`. "Hide, don't disable" gate for the "Stundung beantragen" button. */
internal fun canRequestDeferral(
    status: ContributionStatus,
    blockingRequest: ContributionReliefRequestDto?,
): Boolean = status in ContributionStatusSets.DEFERRABLE && blockingRequest == null

/**
 * A hand-built [Modal] with form fields, not [confirmDialog] -- same "Modal, kein confirmDialog"
 * posture as `MemberPasswordResetDialog.openMemberPasswordResetDialog`, for the same reason: this
 * needs actual input fields, not a single confirm/cancel choice. Art.-9-hint text placed ABOVE the
 * free-text field (Kare-Reihenfolge, Plan Abschnitt 2.2) -- the person must see the warning BEFORE
 * they start typing, not after.
 */
private fun openDeferralRequestDialog(
    memberId: String,
    contribution: ContributionDto,
    onReliefChanged: () -> Unit,
) {
    val modal = Modal(caption = tr("Stundung beantragen"))
    val body = modal.div()
    val dueDateInput = body.text(label = tr("Neues Fälligkeitsdatum (JJJJ-MM-TT)"))
    val reasonOptions = ContributionReliefReason.entries.map { it.name to reliefReasonLabel(it) }
    val reasonSelect =
        body.select(options = reasonOptions, value = reasonOptions.firstOrNull()?.first, label = tr("Begründungs-Kategorie"))
    body.div(
        tr(
            "Diese Erläuterung kann Angaben zu Gesundheit oder sozialer Situation enthalten (Art. 9 DSGVO) und ist " +
                "nur für den Vorstand sichtbar.",
        ),
    ) { addCssClasses("text-muted small") }
    val reasonTextInput = body.textArea(label = tr("Erläuterung (optional)"), rows = 3) { maxlength = 500 }
    val errorBox =
        body.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = body.button(tr("Stundung beantragen"), style = ButtonStyle.PRIMARY)

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()

    submitButton.onClick {
        errorBox.hide()
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val today = now.date
        val newDueDate = runCatching { LocalDate.parse(dueDateInput.value.orEmpty().trim()) }.getOrNull()
        val reason = reasonSelect.value?.let { runCatching { ContributionReliefReason.valueOf(it) }.getOrNull() }
        if (newDueDate == null || newDueDate <= today || reason == null) {
            errorBox.content = tr("Bitte ein gültiges, in der Zukunft liegendes Fälligkeitsdatum angeben (JJJJ-MM-TT).")
            errorBox.show()
            return@onClick
        }
        submitButton.disabled = true
        AppScope.launch {
            try {
                val result =
                    guarded {
                        rpcService<IContributionReliefService>().requestRelief(
                            subjectMemberId = memberId,
                            input =
                                ContributionReliefRequestInput(
                                    kind = ContributionReliefKind.DEFERRAL,
                                    reasonCategory = reason,
                                    reasonText = reasonTextInput.value?.trim()?.takeIf { it.isNotBlank() },
                                    deferralContributionId = contribution.id,
                                    deferralNewDueDate = newDueDate,
                                ),
                        )
                    }
                if (result != null) {
                    notifySuccess(tr("Stundung beantragt"))
                    modal.hide()
                    onReliefChanged()
                }
            } finally {
                submitButton.disabled = false
            }
        }
    }
}

/**
 * Welle V1.4.10.1, Punkt A (zweiter Teil): EXEMPTION/REDUCTION-Selbstbedienung + die eigene
 * Antragsliste (jeder Betrachter ist per Definition für seine eigenen Anträge autorisiert). Baut
 * die Tier-Map für [reliefEffectDescription] genau EINMAL pro Aufruf aus `listMembershipTiers()`
 * (DoS-Prüfliste "Kein N+1", Plan Abschnitt 2.2/2.3) -- keine Tier-Karte pro Zeile.
 */
private fun renderOwnReliefRequests(
    panel: SimplePanel,
    memberId: String,
    onReliefChanged: () -> Unit,
) {
    panel.h2(tr("Beitragsvergünstigung beantragen"))
    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }

    val kindOptions =
        listOf(
            ContributionReliefKind.EXEMPTION.name to reliefKindLabel(ContributionReliefKind.EXEMPTION),
            ContributionReliefKind.REDUCTION.name to reliefKindLabel(ContributionReliefKind.REDUCTION),
        )
    val kindSelect = formPanel.select(options = kindOptions, value = kindOptions.first().first, label = tr("Art"))

    val exemptionFieldsPanel = formPanel.vPanel(spacing = 4)
    val exemptionFromInput = exemptionFieldsPanel.text(label = tr("Befreiung ab (JJJJ-MM-TT)"))
    val exemptionUntilInput = exemptionFieldsPanel.text(label = tr("bis (optional, leer = unbefristet)"))

    val reductionFieldsPanel = formPanel.vPanel(spacing = 4)
    val tierSelect = reductionFieldsPanel.select(options = emptyList(), label = tr("Ziel-Beitragsstufe"))

    fun applyKindVisibility(kindName: String?) {
        val isExemption = kindName == ContributionReliefKind.EXEMPTION.name
        if (isExemption) exemptionFieldsPanel.show() else exemptionFieldsPanel.hide()
        if (isExemption) reductionFieldsPanel.hide() else reductionFieldsPanel.show()
    }
    applyKindVisibility(kindSelect.value)
    kindSelect.subscribe { value -> applyKindVisibility(value) }

    val reviewDueOnInput = formPanel.text(label = tr("Wiedervorlage am (optional, JJJJ-MM-TT)"))
    val reasonOptions = ContributionReliefReason.entries.map { it.name to reliefReasonLabel(it) }
    val reasonSelect =
        formPanel.select(options = reasonOptions, value = reasonOptions.firstOrNull()?.first, label = tr("Begründungs-Kategorie"))
    formPanel.div(
        tr(
            "Diese Erläuterung kann Angaben zu Gesundheit oder sozialer Situation enthalten (Art. 9 DSGVO) und ist " +
                "nur für den Vorstand sichtbar.",
        ),
    ) { addCssClasses("text-muted small") }
    val reasonTextInput = formPanel.textArea(label = tr("Erläuterung (optional)"), rows = 3) { maxlength = 500 }
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = formPanel.button(tr("Beitragsvergünstigung beantragen"), style = ButtonStyle.PRIMARY)

    submitButton.onClick {
        errorBox.hide()
        val kind = kindSelect.value?.let { runCatching { ContributionReliefKind.valueOf(it) }.getOrNull() }
        val reason = reasonSelect.value?.let { runCatching { ContributionReliefReason.valueOf(it) }.getOrNull() }
        // Optionales Feld -- ein unparsbarer Wert wird stillschweigend als "kein Wiedervorlage-Datum"
        // behandelt statt den Antrag zu blockieren (reine Komfortfunktion, keine Pflichtangabe).
        val reviewDueOn =
            reviewDueOnInput.value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        val input: ContributionReliefRequestInput? =
            when (kind) {
                ContributionReliefKind.EXEMPTION -> {
                    val from = runCatching { LocalDate.parse(exemptionFromInput.value.orEmpty().trim()) }.getOrNull()
                    val untilRaw = exemptionUntilInput.value?.trim().orEmpty()
                    val until = if (untilRaw.isBlank()) null else runCatching { LocalDate.parse(untilRaw) }.getOrNull()
                    if (from == null || reason == null || (untilRaw.isNotBlank() && until == null)) {
                        errorBox.content = tr("Bitte ein gültiges Beginn-Datum angeben (JJJJ-MM-TT).")
                        errorBox.show()
                        null
                    } else {
                        ContributionReliefRequestInput(
                            kind = ContributionReliefKind.EXEMPTION,
                            reasonCategory = reason,
                            reasonText = reasonTextInput.value?.trim()?.takeIf { it.isNotBlank() },
                            exemptionFrom = from,
                            exemptionUntil = until,
                            reviewDueOn = reviewDueOn,
                        )
                    }
                }
                ContributionReliefKind.REDUCTION -> {
                    val tierId = tierSelect.value
                    if (tierId == null || reason == null) {
                        errorBox.content = tr("Bitte eine Ziel-Beitragsstufe auswählen.")
                        errorBox.show()
                        null
                    } else {
                        ContributionReliefRequestInput(
                            kind = ContributionReliefKind.REDUCTION,
                            reasonCategory = reason,
                            reasonText = reasonTextInput.value?.trim()?.takeIf { it.isNotBlank() },
                            reductionTargetTierId = tierId,
                            reviewDueOn = reviewDueOn,
                        )
                    }
                }
                ContributionReliefKind.DEFERRAL, null -> null
            }
        if (input == null) return@onClick

        submitButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IContributionReliefService>().requestRelief(memberId, input) }
                if (result != null) {
                    notifySuccess(tr("Antrag gestellt."))
                    onReliefChanged()
                }
            } finally {
                submitButton.disabled = false
            }
        }
    }

    val listPanel = panel.vPanel(spacing = 8)
    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() }.orEmpty()
        val activeTierOptions = tiers.filter { it.active }.map { it.id to tierOptionLabel(it) }
        tierSelect.options = activeTierOptions
        val tierAmountLabels = tiers.associate { it.id to tierOptionLabel(it) }

        val requests = guarded { rpcService<IContributionReliefService>().listMyReliefRequests() }.orEmpty()
        if (requests.isEmpty()) {
            listPanel.p(tr("Noch keine eigenen Anträge gestellt."))
        } else {
            requests.forEach { request -> renderOwnReliefRequestCard(listPanel, request, tierAmountLabels, onReliefChanged) }
        }
    }
}

/**
 * Stolperfalle (Plan Abschnitt 2.7): dieses Format ("%1 (%2 / %3)") existiert NOCH NICHT im Katalog
 * -- `renderTierAdministration` nutzt ein ANDERES Format ("%1: %2 (%3, %4)"), keine Wiederverwendung.
 * `internal`, nicht `private` -- [ContributionReliefQueueScreen] baut seine eigene Tier-Map mit
 * demselben Format (siehe dessen Datei-KDoc "Kein N+1").
 */
internal fun tierOptionLabel(tier: MembershipTierDto): String =
    gettext("%1 (%2 / %3)", tier.name, formatMoney(tier.contributionAmount), tier.billingInterval)

private fun renderOwnReliefRequestCard(
    panel: SimplePanel,
    request: ContributionReliefRequestDto,
    tierAmountLabels: Map<String, String>,
    onReliefChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(reliefKindLabel(request.kind), reliefKindColor(request.kind))
    card.reliefStepTracker(request)
    val tierAmountLabel = if (request.kind == ContributionReliefKind.REDUCTION) tierAmountLabels[request.reductionTargetTierId] else null
    card.div(reliefEffectDescription(request, tierAmountLabel)) { addCssClasses("small") }
    card.div(reliefReasonLabel(request.reasonCategory)) { addCssClasses("text-muted small") }
    // Redaktions-Transparenz (Security-relevanter Zusatz, Plan Abschnitt 2.2/5 Stolperfalle 8): der
    // Betrachter hier ist per Definition autorisiert (eigener Antrag) -- ein Redaktionshinweis legt
    // nichts zusätzlich offen, verhindert aber eine kommentarlos leere Zeile.
    if (request.reasonText != null) {
        card.div(gettext("Erläuterung: %1", request.reasonText)) { addCssClasses("text-muted small") }
    } else if (request.reasonRedactedAt != null) {
        card.div(gettext("Begründung am %1 automatisch gelöscht (12-Monats-Frist).", request.reasonRedactedAt)) {
            addCssClasses("text-muted small")
        }
    }
    if (request.status == ContributionReliefStatus.REQUESTED) {
        val withdrawButton = card.button(tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)
        withdrawButton.onClick {
            withdrawButton.disabled = true
            AppScope.launch {
                try {
                    val result = guarded { rpcService<IContributionReliefService>().withdrawReliefRequest(request.id) }
                    if (result != null) {
                        notifySuccess(tr("Antrag zurückgezogen."))
                        onReliefChanged()
                    }
                } finally {
                    withdrawButton.disabled = false
                }
            }
        }
    }
}

/**
 * Drei-Pillen-Schritt-Tracker -- Vorbild `DsgvoRightsScreen.erasureStepTracker`. Erster
 * Verwendungsort dieser Welle (Plan Abschnitt 2.1): lebt hier, nicht in `ContributionReliefLabels.kt`,
 * gleiches Muster wie `erasureStepTracker` in `DsgvoRightsScreen.kt` statt in einer separaten
 * Labels-Datei. Gleiches Package wie `ContributionReliefQueueScreen.kt` -- kein `internal`/Export
 * nötig, damit die Warteschlange sie mitbenutzen kann.
 */
fun SimplePanel.reliefStepTracker(request: ContributionReliefRequestDto) {
    val states = reliefStepStates(request.status)
    val pillRow = hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }

    fun pill(
        label: String,
        state: ReliefStepState,
        color: String,
    ) {
        if (state == ReliefStepState.FUTURE) pillRow.typeBadge(label, "secondary") else pillRow.statusBadge(label, color)
    }
    pill(tr("Beantragt"), states[0], "secondary")
    pill(reliefStep2Label(request.status), states[1], reliefStep2Color(request.status))
    pill(tr("Ausgeführt"), states[2], "success")
}

private fun renderTierAdministration(root: SimplePanel) {
    root.h2(tr("Beitragssätze und Beitragsgenerierung"))
    val tiersPanel = root.vPanel(spacing = 4)
    val formPanel = root.vPanel(spacing = 6)

    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: return@launch
        if (tiers.isEmpty()) {
            tiersPanel.p(tr("Keine Beitragssätze vorhanden."))
            return@launch
        }
        tiers.forEach { tier ->
            val activeLabel = if (tier.active) gettext("aktiv") else gettext("inaktiv")
            tiersPanel.div(gettext("%1: %2 (%3, %4)", tier.name, tier.contributionAmount, tier.billingInterval, activeLabel))
        }

        val tierOptions = tiers.map { it.id to it.name }
        val tierSelect = formPanel.select(options = tierOptions, value = tierOptions.firstOrNull()?.first, label = tr("Beitragssatz"))
        val periodStartInput = formPanel.text(label = tr("Periodenbeginn (JJJJ-MM-TT)"))
        val periodEndInput = formPanel.text(label = tr("Periodenende (JJJJ-MM-TT)"))
        val errorBox =
            formPanel.div().apply {
                addCssClass("text-danger")
                hide()
            }

        val generateButton = formPanel.button(tr("Beiträge generieren"), style = ButtonStyle.PRIMARY)
        generateButton.onClick {
            errorBox.hide()
            val tierId = tierSelect.value
            val periodStart = runCatching { LocalDate.parse(periodStartInput.value.orEmpty().trim()) }.getOrNull()
            val periodEnd = runCatching { LocalDate.parse(periodEndInput.value.orEmpty().trim()) }.getOrNull()
            if (tierId == null || periodStart == null || periodEnd == null) {
                errorBox.content = tr("Bitte Beitragssatz sowie gültiges Beginn-/Enddatum (JJJJ-MM-TT) angeben.")
                errorBox.show()
                return@onClick
            }
            generateButton.disabled = true
            AppScope.launch {
                val created = guarded { rpcService<IContributionService>().generateContributionsForPeriod(tierId, periodStart, periodEnd) }
                generateButton.disabled = false
                if (created != null) notifySuccess(gettext("%1 neue Beiträge erzeugt (bereits vorhandene wurden übersprungen).", created))
            }
        }
    }
}

private fun renderOrgWideContributions(root: SimplePanel) {
    root.h2(tr("Alle Beiträge"))
    val canMarkPaid = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)
    val canWaive = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    val listPanel = root.vPanel(spacing = 4)

    fun refresh() {
        listPanel.removeAll()
        AppScope.launch {
            val postalMailEnabled = isPostalMailEnabled()
            val contributions =
                guarded { rpcService<IContributionService>().listContributions(status = ContributionStatus.OPEN) } ?: return@launch
            if (contributions.isEmpty()) {
                listPanel.p(tr("Keine offenen Beiträge."))
                return@launch
            }
            // UI theme redesign wave (2026-08-20): real Bootstrap table (table-striped/table-hover),
            // replacing the previous hand-rolled "border rounded p-2" hPanel-per-row layout -- see
            // root CLAUDE.md "UI/UX-Design-Team" review.
            val table =
                listPanel.table(
                    headerNames = listOf(tr("Mitglied"), tr("Zeitraum"), tr("Betrag"), tr("Status"), tr("Aktionen")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            contributions.forEach { contribution ->
                renderContributionRow(table, contribution, canMarkPaid, canWaive, postalMailEnabled, ::refresh)
            }
        }
    }
    refresh()
}

private fun renderContributionRow(
    table: Table,
    contribution: ContributionDto,
    canMarkPaid: Boolean,
    canWaive: Boolean,
    postalMailEnabled: Boolean,
    onChanged: () -> Unit,
) {
    table.row {
        cell(contribution.memberDisplayName)
        cell(gettext("%1–%2", contribution.periodStart, contribution.periodEnd))
        cell(contribution.amountDue.toString())
        cell(contribution.status.toString())

        val actionsCell = cell()
        val actionsPanel = actionsCell.vPanel(spacing = 4)
        val row = actionsPanel.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center") }

        // D3: only rendered here (renderOrgWideContributions, TREASURER/BOARD/ADMIN-gated by the
        // caller) -- never on renderOwnSummary. See MailmergeHttp KDoc for why.
        row.link(tr("Rechnung (PDF)"), url = MailmergeHttp.invoiceUrl(contribution.id), target = "_blank")

        val outcomePanel = actionsPanel.vPanel(spacing = 2)
        if (postalMailEnabled) {
            val postalButton = row.button(tr("Per Post versenden"), style = ButtonStyle.OUTLINEDANGER)
            postalButton.onClick {
                postalDispatchConfirmDialog(
                    caption = tr("Beitragsrechnung per Post versenden"),
                    recipientDisplayName = contribution.memberDisplayName,
                    documentLabel = gettext("Beitragsrechnung %1–%2", contribution.periodStart, contribution.periodEnd),
                ) {
                    postalButton.disabled = true
                    outcomePanel.removeAll()
                    AppScope.launch {
                        val result = guarded { rpcService<IPostalMailService>().dispatchBeitragsrechnungByPost(contribution.id) }
                        postalButton.disabled = false
                        if (result != null) {
                            if (result.status == PostalDeliveryStatus.SENT) {
                                notifySuccess(gettext("Brief an %1 wurde an Letterxpress übergeben.", result.recipientDisplayName))
                            } else {
                                notifyError(tr("Postversand fehlgeschlagen."))
                            }
                            outcomePanel.renderPostalDispatchOutcome(result)
                        }
                    }
                }
            }
        } else {
            row.postalMailDisabledNotice()
        }

        // Review Round 3 (2026-08-19, SHOULD-5): canMarkPaid/canWaive are role-only gates, never
        // status-aware on their own -- an already-SETTLED (PAID/WAIVED) row must still hide both
        // buttons, or a stale-rendered row (e.g. another actor settled it between this list's fetch
        // and the click, or a race between the two buttons on the same row) surfaces a raw, English,
        // technical ConflictException to the user instead of the button simply not being there. Same
        // "hide, don't just disable, an action that would always fail" convention this codebase already
        // follows elsewhere for status-gated actions.
        val isSettled = contribution.status in ContributionStatusSets.SETTLED
        if (canMarkPaid && !isSettled) {
            val payButton = row.button(tr("Als bezahlt markieren"), style = ButtonStyle.SUCCESS)
            payButton.onClick {
                // Disabled for the duration of the in-flight RPC call -- reduces (does not replace,
                // see ContributionService.markContributionPaid's own server-side idempotency guard)
                // the chance an accidental double-click double-posts a journal entry. Same pattern as
                // LedgerScreen.kt's saveButton around the account-mapping save call. Review Round 1
                // (2026-08-19, CRITICAL-2).
                payButton.disabled = true
                AppScope.launch {
                    try {
                        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                        val result =
                            guarded {
                                rpcService<IContributionService>().markContributionPaid(
                                    contribution.id,
                                    now,
                                    contribution.amountDue,
                                    null,
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Als bezahlt markiert."))
                            onChanged()
                        }
                    } finally {
                        // Review Round 2 (2026-08-19, SHOULD-3): guarded() rethrows CancellationException
                        // (see its own KDoc/implementation) -- a plain post-guarded() re-enable never runs
                        // if this coroutine is cancelled mid-flight, leaving the button permanently
                        // disabled until a page refresh. finally runs regardless of success, a business
                        // exception guarded() swallowed, or cancellation.
                        payButton.disabled = false
                    }
                }
            }
        }
        if (canWaive && !isSettled) {
            val waiveButton = row.button(tr("Erlassen"), style = ButtonStyle.OUTLINEWARNING)
            waiveButton.onClick {
                confirmDialog(
                    title = tr("Beitrag erlassen"),
                    message =
                        gettext("Beitrag von %1 über %2 wirklich erlassen?", contribution.memberDisplayName, contribution.amountDue),
                    confirmLabel = tr("Erlassen"),
                ) {
                    AppScope.launch {
                        val result = guarded { rpcService<IContributionService>().markContributionWaived(contribution.id, null) }
                        if (result != null) {
                            notifySuccess(tr("Beitrag erlassen."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}
