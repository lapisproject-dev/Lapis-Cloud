package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.FINANCE_SETUP_REQUIRED_STEP_COUNT
import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.FinanceSetupStep
import network.lapis.cloud.shared.domain.FinanceSetupStepState
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.StandardAssociationChart
import network.lapis.cloud.shared.domain.StandardChartAccount
import network.lapis.cloud.shared.domain.accepts
import network.lapis.cloud.shared.domain.financeSetupStatusOf
import network.lapis.cloud.shared.domain.slug
import network.lapis.cloud.shared.domain.toSetupView
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/**
 * V1.9.206. What the person on the setup tab may do. The server stays the authority: `createStandardChart` is for TREASURER/ADMIN,
 * `updateOrganizationSettings` (the mapping) for ADMIN only.
 */
internal enum class FinanceSetupAccess {
    /** ADMIN: chart and mapping. */
    MANAGE_ALL,

    /** TREASURER: may create the chart, sees the mapping as text only. */
    CHART_ONLY,

    /** BOARD: sees the state, nothing to operate. */
    READ_ONLY,
}

internal fun financeSetupAccessOf(
    isAdmin: Boolean,
    isTreasurer: Boolean,
): FinanceSetupAccess =
    when {
        isAdmin -> FinanceSetupAccess.MANAGE_ALL
        isTreasurer -> FinanceSetupAccess.CHART_ONLY
        else -> FinanceSetupAccess.READ_ONLY
    }

/** The ONLY accounts a step's select offers: exactly those the shared rule [accepts] (which mirrors the server). Wrong types are absent, not greyed out. */
internal fun setupSelectOptions(
    step: FinanceSetupStep,
    accounts: List<LedgerAccountDto>,
): List<Pair<String, String>> =
    accounts
        .filter { step.accepts(it.toSetupView()) }
        .sortedBy { it.accountNumber }
        .map { it.id to "${it.accountNumber} · ${it.name}" }

/** The id of the proposed standard account of [step] in the chart, only if it exists and the step accepts it. */
internal fun standardAccountIdFor(
    step: FinanceSetupStep,
    accounts: List<LedgerAccountDto>,
): String? {
    val number = StandardAssociationChart.defaultFor[step] ?: return null
    return accounts.firstOrNull { it.accountNumber == number && step.accepts(it.toSetupView()) }?.id
}

/** "Alle Standardkonten vorschlagen": fills ONLY the steps that are still empty; never overwrites a choice, never saves. */
internal fun proposeStandardDefaults(
    current: Map<FinanceSetupStep, String?>,
    accounts: List<LedgerAccountDto>,
): Map<FinanceSetupStep, String?> =
    current.mapValues { (step, value) ->
        if (value.isNullOrEmpty()) standardAccountIdFor(step, accounts) ?: value else value
    }

/** The proposed chart accounts that do not exist yet. */
internal fun standardChartToCreate(existingNumbers: Set<String>): List<StandardChartAccount> =
    StandardAssociationChart.accounts.filter { it.accountNumber !in existingNumbers }

private val MAPPING_STEPS =
    listOf(
        FinanceSetupStep.BANK_ACCOUNT,
        FinanceSetupStep.CONTRIBUTION_INCOME,
        FinanceSetupStep.DONATION_INCOME,
        FinanceSetupStep.EVENT_INCOME,
        FinanceSetupStep.FEE_ACCOUNT,
    )

/** Resolved with gettext (not tr): the title is passed as an argument of gettext() elsewhere and a tr() marker would show on screen. */
internal fun setupStepTitle(step: FinanceSetupStep): String =
    when (step) {
        FinanceSetupStep.CHART -> gettext("Kontenplan")
        FinanceSetupStep.BANK_ACCOUNT -> gettext("Bankkonto")
        FinanceSetupStep.CONTRIBUTION_INCOME -> gettext("Erlöskonto für Mitgliedsbeiträge")
        FinanceSetupStep.DONATION_INCOME -> gettext("Erlöskonto für Spenden")
        FinanceSetupStep.EVENT_INCOME -> gettext("Erlöskonto für Veranstaltungen")
        FinanceSetupStep.FEE_ACCOUNT -> gettext("Gebührenkonto (optional)")
    }

private fun setupStepExplanation(step: FinanceSetupStep): String =
    when (step) {
        FinanceSetupStep.CHART ->
            tr("Der Kontenplan ist die Liste aller Konten, auf die Ihr Verein bucht. Ohne ihn kann nichts gebucht werden.")
        FinanceSetupStep.BANK_ACCOUNT -> tr("Das Konto, auf dem das Geld Ihres Vereins liegt, also Ihr Girokonto.")
        FinanceSetupStep.CONTRIBUTION_INCOME ->
            tr("Ein Erlöskonto sammelt Einnahmen einer Art, zum Beispiel alle Mitgliedsbeiträge.")
        FinanceSetupStep.DONATION_INCOME -> tr("Hierhin werden Spenden gebucht, für die Sie eine Zuwendungsbestätigung ausstellen.")
        FinanceSetupStep.EVENT_INCOME -> tr("Hierhin werden Einnahmen aus Veranstaltungen gebucht, zum Beispiel Eintrittsgelder.")
        FinanceSetupStep.FEE_ACCOUNT -> tr("Hierhin würden Gebühren des Zahlungsanbieters gebucht. Dieser Schritt darf leer bleiben.")
    }

private fun setupStepExample(step: FinanceSetupStep): String? =
    when (step) {
        FinanceSetupStep.CHART -> null
        FinanceSetupStep.BANK_ACCOUNT -> gettext("Zum Beispiel: %1", "18000 Bank (Girokonto)")
        FinanceSetupStep.CONTRIBUTION_INCOME -> gettext("Zum Beispiel: %1", "40000 Echte Mitgliedsbeiträge")
        FinanceSetupStep.DONATION_INCOME -> gettext("Zum Beispiel: %1", "40450 Geldzuwendungen (Spenden)")
        FinanceSetupStep.EVENT_INCOME -> gettext("Zum Beispiel: %1", "42010 Erlöse aus Eintrittsgeldern")
        FinanceSetupStep.FEE_ACCOUNT -> gettext("Zum Beispiel: %1", "68550 Nebenkosten des Geldverkehrs")
    }

/** Plain-language names of the missing type, for the "no matching account yet" sentence. */
private fun setupEmptyListText(step: FinanceSetupStep): String =
    when (step) {
        FinanceSetupStep.BANK_ACCOUNT -> tr("Noch kein Bankkonto im Kontenplan. Legen Sie zuerst den Standard-Kontenplan an.")
        FinanceSetupStep.FEE_ACCOUNT -> tr("Noch kein Aufwandskonto im Kontenplan. Legen Sie zuerst den Standard-Kontenplan an.")
        else -> tr("Noch kein Erlöskonto im Kontenplan. Legen Sie zuerst den Standard-Kontenplan an.")
    }

private fun setupStateText(state: FinanceSetupStepState): Pair<String, String> =
    when (state) {
        FinanceSetupStepState.DONE -> "✓" to tr("Erledigt")
        FinanceSetupStepState.OPEN -> "○" to tr("Offen")
        FinanceSetupStepState.OPTIONAL -> "–" to tr("Optional")
        FinanceSetupStepState.ERROR -> "!" to tr("Fehler")
    }

private fun setupStateCss(state: FinanceSetupStepState): String =
    when (state) {
        FinanceSetupStepState.DONE -> "done"
        FinanceSetupStepState.OPEN -> "open"
        FinanceSetupStepState.OPTIONAL -> "optional"
        FinanceSetupStepState.ERROR -> "error"
    }

/** Progress sentence, e.g. "3 von 5 Pflichtschritten erledigt". */
internal fun setupProgressText(status: FinanceSetupStatusDto): String =
    gettext("%1 von %2 Pflichtschritten erledigt", status.doneRequiredCount, FINANCE_SETUP_REQUIRED_STEP_COUNT)

/**
 * V1.9.206. The "Einrichtung" tab: six steps in plain language, safe standard choices, and the one place where the account mapping
 * lives. Loads the accounts, the organization settings and the server's step status; [onStatusChanged] is called after every
 * load/save/chart creation with the fresh server status (the tab label and the hints on the other tabs depend on it).
 */
internal fun renderFinanceSetupTab(
    panel: SimplePanel,
    access: FinanceSetupAccess,
    initialStep: FinanceSetupStep?,
    onStatusChanged: (FinanceSetupStatusDto) -> Unit,
) {
    val host = panel.vPanel(spacing = 8)

    fun load() {
        host.removeAll()
        host.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val accounts = guarded { rpcService<IAccountingService>().listLedgerAccounts(activeOnly = false) }
            val settings = guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val status = guarded { rpcService<IAccountingService>().getFinanceSetupStatus() }
            host.removeAll()
            if (accounts == null || settings == null || status == null) {
                host.dataErrorState(onRetry = { load() })
                return@launch
            }
            onStatusChanged(status)
            renderSetupBody(host, access, accounts, settings, status, initialStep, reload = { load() }, onStatusChanged = onStatusChanged)
        }
    }
    load()
}

private fun settingsMappingOf(settings: OrganizationSettingsDto): Map<FinanceSetupStep, String?> =
    mapOf(
        FinanceSetupStep.BANK_ACCOUNT to settings.paymentBankAccountId,
        FinanceSetupStep.CONTRIBUTION_INCOME to settings.contributionIncomeAccountId,
        FinanceSetupStep.DONATION_INCOME to settings.donationIncomeAccountId,
        FinanceSetupStep.EVENT_INCOME to settings.eventIncomeAccountId,
        FinanceSetupStep.FEE_ACCOUNT to settings.paymentFeeAccountId,
    )

private class StepCard(
    val step: FinanceSetupStep,
    val icon: io.kvision.html.Span,
    val stateText: io.kvision.html.Span,
    val unsaved: io.kvision.html.P,
    val error: io.kvision.html.P,
    val card: Div,
    val field: LapisField?,
)

private fun renderSetupBody(
    host: SimplePanel,
    access: FinanceSetupAccess,
    accounts: List<LedgerAccountDto>,
    settings: OrganizationSettingsDto,
    status: FinanceSetupStatusDto,
    initialStep: FinanceSetupStep?,
    reload: () -> Unit,
    onStatusChanged: (FinanceSetupStatusDto) -> Unit,
) {
    val saved = settingsMappingOf(settings)
    val accountsById = accounts.associateBy { it.id }
    val canEdit = access == FinanceSetupAccess.MANAGE_ALL
    val progress = host.p("") { addCssClass("lapis-setup-progress") }
    progress.content = setupProgressText(status)
    progress.setAttribute("aria-live", "polite")

    if (access == FinanceSetupAccess.CHART_ONLY) {
        host.p(tr("Die Zuordnung kann nur ein Administrator ändern.")) { addCssClasses("text-muted") }
    }
    host.p(
        tr(
            "Die Sphäre ist der steuerliche Bereich eines Vereins (ideeller Bereich, Vermögensverwaltung, Zweckbetrieb, " +
                "wirtschaftlicher Geschäftsbetrieb); sie wird je Buchung gewählt.",
        ),
    ) { addCssClasses("text-muted small") }

    val form = host.lapisForm()
    val cards = mutableMapOf<FinanceSetupStep, StepCard>()

    // ---- local state: what is currently chosen (not yet saved) ----
    val chosen = mutableMapOf<FinanceSetupStep, String>()
    // Steps whose STORED mapping is no longer valid (account deactivated or of the wrong type). They start EMPTY, so the form can be
    // saved again; the old mapping is dropped on save, and the card says so in red.
    val invalidSaved = mutableSetOf<FinanceSetupStep>()
    saved.forEach { (step, id) ->
        val valid = id != null && accountsById[id]?.let { step.accepts(it.toSetupView()) } == true
        chosen[step] = if (valid) id.orEmpty() else ""
        if (id != null && !valid) invalidSaved += step
    }

    fun localStatus(): FinanceSetupStatusDto =
        financeSetupStatusOf(
            accounts = accounts.map { it.toSetupView() },
            bankId = chosen[FinanceSetupStep.BANK_ACCOUNT]?.ifEmpty { null },
            contributionId = chosen[FinanceSetupStep.CONTRIBUTION_INCOME]?.ifEmpty { null },
            donationId = chosen[FinanceSetupStep.DONATION_INCOME]?.ifEmpty { null },
            eventId = chosen[FinanceSetupStep.EVENT_INCOME]?.ifEmpty { null },
            feeId = chosen[FinanceSetupStep.FEE_ACCOUNT]?.ifEmpty { null },
        )

    fun repaint() {
        val local = localStatus()
        progress.content = setupProgressText(local)
        val next = local.firstOpenRequired
        FinanceSetupStep.entries.forEach { step ->
            val card = cards[step] ?: return@forEach
            val hadInvalidSaved = step in invalidSaved && chosen[step].isNullOrEmpty()
            val state =
                when {
                    hadInvalidSaved -> FinanceSetupStepState.ERROR
                    else -> local.stateOf(step)
                }
            val (glyph, text) = setupStateText(state)
            card.icon.content = glyph
            FinanceSetupStepState.entries.forEach { card.icon.removeCssClass("lapis-setup-status-icon--${setupStateCss(it)}") }
            card.icon.addCssClass("lapis-setup-status-icon--${setupStateCss(state)}")
            card.stateText.content = text
            val unsavedNow =
                step != FinanceSetupStep.CHART && canEdit && (chosen[step].orEmpty() != saved[step].orEmpty()) && !hadInvalidSaved
            card.unsaved.content = if (unsavedNow) tr("Nicht gespeichert") else ""
            card.error.content =
                if (hadInvalidSaved && state == FinanceSetupStepState.ERROR) {
                    val old = accountsById[saved[step]]
                    gettext(
                        "Das bisher zugeordnete Konto %1 ist deaktiviert. Bitte ein anderes wählen. Beim Speichern wird die alte Zuordnung entfernt.",
                        old?.accountNumber ?: "",
                    )
                } else {
                    ""
                }
            card.card.removeCssClass("lapis-setup-step--next")
            card.card.removeCssClass("lapis-setup-step--error")
            if (step == next) card.card.addCssClass("lapis-setup-step--next")
            if (state == FinanceSetupStepState.ERROR) card.card.addCssClass("lapis-setup-step--error")
        }
    }

    fun sentenceLink(
        container: Container,
        text: String,
        target: String,
    ) {
        container.link(text, url = target, dataNavigo = true)
    }

    FinanceSetupStep.entries.forEach { step ->
        val titleId = "finance-setup-title-${step.slug}"
        val cardDiv =
            host.div(className = "lapis-setup-step") {
                id = "finance-setup-step-${step.slug}"
                setAttribute("role", "group")
                setAttribute("aria-labelledby", titleId)
            }
        val titleRow = cardDiv.h3("", className = "lapis-setup-step__title h6") { id = titleId }
        val (glyph, text) = setupStateText(status.stateOf(step))
        val iconSpan =
            titleRow.span(
                glyph,
                className = "lapis-setup-status-icon lapis-setup-status-icon--${setupStateCss(status.stateOf(step))}",
            )
        iconSpan.setAttribute("aria-hidden", "true")
        titleRow.span(setupStepTitle(step))
        val stateSpan = titleRow.span(text, className = "text-muted small")
        titleRow.setAttribute("tabindex", "-1")
        cardDiv.p(setupStepExplanation(step))
        setupStepExample(step)?.let { example -> cardDiv.p(example) { addCssClasses("text-muted small") } }

        var field: LapisField? = null
        if (step == FinanceSetupStep.CHART) {
            renderChartStep(cardDiv, access, accounts, reload)
        } else {
            val options = setupSelectOptions(step, accounts)
            if (canEdit) {
                if (options.isEmpty()) {
                    cardDiv.p(setupEmptyListText(step)) { addCssClasses("text-muted") }
                    sentenceLink(cardDiv, tr("Zum Kontenplan"), ledgerHash(LedgerTab.SETUP, FinanceSetupStep.CHART))
                } else {
                    val row = cardDiv.inlineFieldRow {}
                    field =
                        form.selectField(
                            label = setupStepTitle(step),
                            options = listOf("" to tr("(nicht gewählt)")) + untrustedOptions(options),
                            value = chosen[step].orEmpty(),
                            host = row,
                            slotHost = cardDiv,
                        )
                    val standardId = standardAccountIdFor(step, accounts)
                    if (standardId != null) {
                        val useButton = row.actionButton(ActionIcon.APPROVE, tr("Standard verwenden"), style = ButtonStyle.OUTLINESECONDARY)
                        useButton.onClick {
                            field?.setValue(standardId)
                            chosen[step] = standardId
                            repaint()
                        }
                    } else {
                        val number = StandardAssociationChart.defaultFor[step].orEmpty()
                        cardDiv.p(gettext("Standardkonto %1 nicht im Kontenplan.", number)) { addCssClasses("text-muted small") }
                    }
                    field.subscribe { value ->
                        chosen[step] = value
                        repaint()
                    }
                }
            } else {
                val mapped = saved[step]?.let { accountsById[it] }
                if (mapped != null && step.accepts(mapped.toSetupView())) {
                    cardDiv.untrustedP("${mapped.accountNumber} · ${mapped.name}", className = "fw-bold")
                } else {
                    cardDiv.p(tr("(nicht gewählt)")) { addCssClasses("text-muted") }
                }
            }
        }
        val unsaved = cardDiv.p("") { addCssClasses("text-muted small") }
        val error = cardDiv.p("") { addCssClasses("lapis-setup-error") }
        error.setAttribute("role", "alert")
        cards[step] = StepCard(step, iconSpan, stateSpan, unsaved, error, cardDiv, field)
    }

    // ---- "Alle Standardkonten vorschlagen" (fills empty fields only, saves nothing) ----
    if (canEdit) {
        val proposeButton =
            host.actionButton(
                ActionIcon.APPROVE,
                tr("Alle Standardkonten vorschlagen"),
                style = ButtonStyle.OUTLINESECONDARY,
            )
        proposeButton.onClick {
            val proposal = proposeStandardDefaults(MAPPING_STEPS.associateWith { chosen[it] }, accounts)
            proposal.forEach { (step, id) ->
                if (!id.isNullOrEmpty() && chosen[step].isNullOrEmpty()) {
                    chosen[step] = id
                    cards[step]?.field?.setValue(id)
                }
            }
            repaint()
        }
    }

    // ---- "Erweitert" ----
    val advancedFields =
        if (canEdit) {
            renderAdvanced(host, form, accounts, settings, bankId = {
                chosen[FinanceSetupStep.BANK_ACCOUNT]
            })
        } else {
            null
        }

    // ---- Save ----
    if (canEdit) {
        val saveButton = newActionButton(ActionIcon.SAVE, tr("Zuordnung speichern"), ButtonStyle.PRIMARY)
        form.buttons(primary = saveButton)
        saveButton.onClick {
            form.submit(saveButton) {
                val adv = advancedFields ?: return@submit
                val beraterInput = parseDatevNumberInput(adv.datevBerater.value)
                val mandantInput = parseDatevNumberInput(adv.datevMandant.value)
                val sphere =
                    runCatching { GemeinnuetzigkeitSphere.valueOf(adv.eventSphere.value) }.getOrNull()
                        ?: GemeinnuetzigkeitSphere.ZWECKBETRIEB
                val result =
                    guarded {
                        rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                            settings.toInputWithPaymentAccountMapping(
                                paymentBankAccountId = chosen[FinanceSetupStep.BANK_ACCOUNT]?.takeIf { it.isNotBlank() },
                                paymentFeeAccountId = chosen[FinanceSetupStep.FEE_ACCOUNT]?.takeIf { it.isNotBlank() },
                                contributionIncomeAccountId = chosen[FinanceSetupStep.CONTRIBUTION_INCOME]?.takeIf { it.isNotBlank() },
                                donationIncomeAccountId = chosen[FinanceSetupStep.DONATION_INCOME]?.takeIf { it.isNotBlank() },
                                eventIncomeAccountId = chosen[FinanceSetupStep.EVENT_INCOME]?.takeIf { it.isNotBlank() },
                                eventIncomeSphere = sphere,
                                datevBeraterNummer = (beraterInput as? DatevNumberInput.Valid)?.value,
                                datevMandantNummer = (mandantInput as? DatevNumberInput.Valid)?.value,
                                travelExpenseAccountId = adv.travel.value.takeIf { it.isNotBlank() },
                                volunteerAllowanceAccountId = adv.volunteer.value.takeIf { it.isNotBlank() },
                                receivablesAccountId = adv.receivables.value.takeIf { it.isNotBlank() },
                                payablesAccountId = adv.payables.value.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                if (result != null) {
                    notifySuccess(tr("Zuordnung gespeichert."))
                    val fresh = guarded { rpcService<IAccountingService>().getFinanceSetupStatus() }
                    fresh?.let(onStatusChanged)
                    reload()
                }
            }
        }
    }

    repaint()
    initialStep?.let { step ->
        // Scroll to the card and move the focus to its heading (a real document anchor does not exist in a hash-routed page).
        val target = window.document.getElementById("finance-setup-title-${step.slug}") as? org.w3c.dom.HTMLElement
        window.setTimeout({
            target?.scrollIntoView()
            target?.focus()
        }, 0)
    }
}

/** Step 1: the chart. The button exists only while there is no active account and the person may create one. */
private fun renderChartStep(
    cardDiv: Div,
    access: FinanceSetupAccess,
    accounts: List<LedgerAccountDto>,
    reload: () -> Unit,
) {
    val activeCount = accounts.count { it.active }
    if (activeCount > 0) {
        cardDiv.p(gettext("%1 aktive Konten im Kontenplan.", activeCount)) { addCssClasses("text-muted small") }
        return
    }
    if (access == FinanceSetupAccess.READ_ONLY) {
        cardDiv.p(tr("Noch kein Konto angelegt. Das legt die Schatzmeisterei oder die Administration an.")) { addCssClasses("text-muted") }
        return
    }
    cardDiv.p(tr("Ein Vorschlag. Bitte von Ihrer Steuerberatung bestätigen lassen.")) { addCssClasses("text-muted small") }
    val button = cardDiv.actionButton(ActionIcon.ADD, tr("Standard-Kontenplan SKR42 für Vereine anlegen"), style = ButtonStyle.PRIMARY)
    button.onClick {
        val toCreate = standardChartToCreate(accounts.map { it.accountNumber }.toSet())
        confirmDialog(
            title = tr("Standard-Kontenplan anlegen"),
            message = gettext("Es werden %1 Konten angelegt:", toCreate.size),
            confirmLabel = tr("Kontenplan anlegen"),
            confirmStyle = ButtonStyle.PRIMARY,
            extraLines =
                toCreate.map { a ->
                    gettext("%1 %2 (%3), %4", a.accountNumber, a.name, ledgerAccountTypeLabel(a.type), gettext(a.sphereHint))
                } +
                    listOf(
                        tr("Vorhandene Konten bleiben unverändert."),
                        tr("Angelegte Konten lassen sich später nicht löschen, nur deaktivieren."),
                        tr("Der Kontenplan ist ein Vorschlag; bitte von Ihrer Steuerberatung bestätigen lassen."),
                    ),
            focusCancel = true,
            onConfirm = {
                AppScope.launch {
                    try {
                        val result = rpcService<IAccountingService>().createStandardChart()
                        notifySuccess(gettext("%1 Konten angelegt, %2 schon vorhanden.", result.created.size, result.skipped.size))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ConflictException) {
                        notifyWarning(tr("Der Kontenplan wurde inzwischen geändert. Bitte prüfen."))
                    } catch (e: Throwable) {
                        guarded<Unit> { throw e }
                    }
                    reload()
                }
            },
        )
    }
}

private class AdvancedFields(
    val eventSphere: LapisField,
    val travel: LapisField,
    val volunteer: LapisField,
    val receivables: LapisField,
    val payables: LapisField,
    val datevBerater: LapisField,
    val datevMandant: LapisField,
)

/** "Erweitert": the further mapping fields of the old section, folded away. One sentence of explanation each. */
private fun renderAdvanced(
    host: SimplePanel,
    form: LapisForm,
    accounts: List<LedgerAccountDto>,
    settings: OrganizationSettingsDto,
    bankId: () -> String?,
): AdvancedFields {
    val details = Tag(TAG.DETAILS, className = "lapis-advanced")
    details.add(Tag(TAG.SUMMARY, content = tr("Erweitert")))
    host.add(details)
    val none = "" to tr("(nicht konfiguriert)")

    fun typed(type: LedgerAccountType) =
        listOf(none) +
            untrustedOptions(
                accounts.filter { it.active && it.type == type && !it.isCashRegister }.sortedBy { it.accountNumber }.map {
                    it.id to "${it.accountNumber} · ${it.name}"
                },
            )

    fun hint(text: String) = details.p(text) { addCssClasses("text-muted small") }
    hint(tr("Die Sphäre der Veranstaltungserlöse ist der steuerliche Bereich, dem Einnahmen aus Veranstaltungen zugerechnet werden."))
    val eventSphere =
        form.selectField(
            label = tr("Sphäre der Veranstaltungserlöse"),
            options = GemeinnuetzigkeitSphere.entries.map { it.name to sphereLabel(it) },
            value = settings.eventIncomeSphere.name,
            host = details,
        )
    hint(tr("Auf dieses Aufwandskonto werden Reisekostenabrechnungen gebucht."))
    val travel =
        form.selectField(
            label = tr("Reisekosten-Aufwandskonto"),
            options = typed(LedgerAccountType.EXPENSE),
            value = settings.travelExpenseAccountId.orEmpty(),
            host = details,
        )
    hint(tr("Auf dieses Aufwandskonto werden Übungsleiter- und Ehrenamtspauschalen gebucht."))
    val volunteer =
        form.selectField(
            label = tr("Ehrenamtspauschalen-Aufwandskonto"),
            options = typed(LedgerAccountType.EXPENSE),
            value = settings.volunteerAllowanceAccountId.orEmpty(),
            host = details,
        )
    hint(tr("Ohne Forderungs- und Verbindlichkeitenkonto werden offene Posten angelegt, aber nicht gebucht."))
    val receivables =
        form.selectField(
            label = tr("Forderungskonto (Debitoren)"),
            options = typed(LedgerAccountType.ASSET),
            value = settings.receivablesAccountId.orEmpty(),
            host = details,
        )
    val payables =
        form.selectField(
            label = tr("Verbindlichkeitenkonto (Kreditoren)"),
            options = typed(LedgerAccountType.LIABILITY),
            value = settings.payablesAccountId.orEmpty(),
            host = details,
        )
    hint(tr("Berater- und Mandantennummer erhalten Sie von Ihrem Steuerberater."))
    val berater =
        form.textField(
            label = tr("DATEV-Beraternummer"),
            value = settings.datevBeraterNummer?.toString().orEmpty(),
            host = details,
            rule = {
                if (parseDatevNumberInput(it) is DatevNumberInput.Invalid) {
                    FieldCheck.Invalid(gettext("DATEV-Beraternummer ist keine gültige Zahl."))
                } else {
                    FieldCheck.Ok
                }
            },
        )
    val mandant =
        form.textField(
            label = tr("DATEV-Mandantennummer"),
            value = settings.datevMandantNummer?.toString().orEmpty(),
            host = details,
            rule = {
                if (parseDatevNumberInput(it) is DatevNumberInput.Invalid) {
                    FieldCheck.Invalid(gettext("DATEV-Mandantennummer ist keine gültige Zahl."))
                } else {
                    FieldCheck.Ok
                }
            },
        )
    // The cross rule of the old section: bank, receivables and payables must be three different accounts.
    form.crossFieldRule(
        focusOn = receivables.control.input as? Widget,
        watch = listOf(receivables, payables).mapNotNull { it.control.input as? Widget },
    ) {
        val problem =
            paymentAccountMappingProblem(
                PaymentAccountMapping(
                    defaultBankAccountId = bankId()?.takeIf { it.isNotBlank() },
                    receivablesAccountId = receivables.value.takeIf { it.isNotBlank() },
                    payablesAccountId = payables.value.takeIf { it.isNotBlank() },
                ),
            )
        if (problem == null) FieldCheck.Ok else FieldCheck.Invalid(resolvedAttributeText(problem))
    }
    return AdvancedFields(eventSphere, travel, volunteer, receivables, payables, berater, mandant)
}
