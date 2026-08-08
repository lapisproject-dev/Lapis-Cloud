package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DataBreachIncidentDto
import network.lapis.cloud.shared.domain.DataBreachIncidentInput
import network.lapis.cloud.shared.domain.DpiaAssessmentDto
import network.lapis.cloud.shared.domain.DpiaAssessmentInput
import network.lapis.cloud.shared.domain.DsfaStatus
import network.lapis.cloud.shared.domain.ProcessingAgreementDto
import network.lapis.cloud.shared.domain.ProcessingAgreementInput
import network.lapis.cloud.shared.domain.RiskLevel
import network.lapis.cloud.shared.domain.TechnicalOrganizationalMeasureDto
import network.lapis.cloud.shared.domain.TechnicalOrganizationalMeasureInput
import network.lapis.cloud.shared.domain.TomCategory
import network.lapis.cloud.shared.rpc.IDsgvoComplianceService

/**
 * Compliance UI wave, screen 3 of 5 -- "DSGVO-Compliance" (AVV-Register/TOMs/DSFA-Vorlage/
 * Datenpannenmeldung -- the DSGVO-Vollausbau admin tooling), per the approved plan + UI/UX-Design-
 * Team review on `feature/compliance-ui`. See plan "Screen 3 -- DsgvoComplianceScreen.kt" and design
 * decisions X1 (tab pattern), D7 (Breach 72h deadline surfacing), D8(a) (honesty banners), D11 (AVV
 * `active` proactive flagging), D12 (badge colors, `ComplianceLabels.kt`).
 *
 * X1: four sub-registers as the toggle-button-row-over-`contentPanel` pattern this client already
 * establishes (`NonprofitComplianceReportsScreen.kt`) -- no new tab widget. AVV renders by default.
 *
 * Role gating (verified against `DsgvoComplianceService.kt`'s `COMPLIANCE_READ_ROLES`/
 * `AVV_TOM_WRITE_ROLES`/`DSFA_BREACH_WRITE_ROLES` constants, plan "Role-gating per action"):
 * `Routing.kt` gates the whole `/dsgvo-compliance` route on BOARD/ADMIN (`COMPLIANCE_READ_ROLES` --
 * every read method on all four sub-registers needs exactly this tier, uniformly). Inside the
 * screen, write-form visibility differs per tab: AVV/TOM create/update forms render only for
 * `AppState.hasRole(ADMIN)` (`AVV_TOM_WRITE_ROLES` is narrower than the route's own read tier); DSFA/
 * Breach create/update forms render for `AppState.hasRole(BOARD, ADMIN)` (`DSFA_BREACH_WRITE_ROLES`
 * -- the same tier the route itself requires, so in practice every caller who can reach this screen
 * at all can also write to the DSFA/Breach tabs; still computed explicitly per design decision
 * rather than assumed, so a future narrowing of the route guard alone would not silently over-grant
 * a write affordance here).
 *
 * There is no delete affordance anywhere on any of the four tabs -- matches
 * [IDsgvoComplianceService]'s own CRUD-minus-delete contract (create + update only).
 */
fun renderDsgvoComplianceScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("DSGVO-Compliance")
    root.div(
        "Verarbeitungsverzeichnis (AVV), technisch-organisatorische Maßnahmen (TOM), " +
            "Datenschutz-Folgenabschätzungen (DSFA) und Datenpannenmeldungen -- Dokumentations- und " +
            "Arbeitswerkzeug für eine vom Vorstand getroffene Entscheidung, nie automatisierte " +
            "Rechtsberatung.",
    ) { addCssClasses("text-muted small") }

    // ---- X1: tab toggle row ---------------------------------------------------------------
    val toggleRow = root.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val avvButton = toggleRow.button("Verarbeitungsverzeichnis (AVV)", style = ButtonStyle.OUTLINEPRIMARY)
    val tomButton = toggleRow.button("TOM", style = ButtonStyle.OUTLINEPRIMARY)
    val dsfaButton = toggleRow.button("DSFA", style = ButtonStyle.OUTLINEPRIMARY)
    val breachButton = toggleRow.button("Datenpannen", style = ButtonStyle.OUTLINEPRIMARY)
    val contentPanel = root.vPanel(spacing = 10)

    avvButton.onClick {
        contentPanel.removeAll()
        renderAvvTab(contentPanel)
    }
    tomButton.onClick {
        contentPanel.removeAll()
        renderTomTab(contentPanel)
    }
    dsfaButton.onClick {
        contentPanel.removeAll()
        renderDsfaTab(contentPanel)
    }
    breachButton.onClick {
        contentPanel.removeAll()
        renderBreachTab(contentPanel)
    }

    renderAvvTab(contentPanel)
}

// ================================================================================================
// Baustein 1 -- Verarbeitungsverzeichnis (AVV)
// ================================================================================================

private fun renderAvvTab(panel: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.ADMIN)
    panel.h2("Verarbeitungsverzeichnis (AVV)")
    panel.div(
        "Drittdienst-Verarbeiter (z. B. Letterxpress) und der Stand des jeweiligen " +
            "Auftragsverarbeitungsvertrags. \"Aktiv\" wird bei jedem Laden neu berechnet -- ein " +
            "abgelaufener Prüftermin fällt sofort auf, ohne dass jemand daran denken muss, den " +
            "Status manuell umzustellen.",
    ) { addCssClasses("text-muted small") }

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        AppScope.launch {
            val agreements = guarded { rpcService<IDsgvoComplianceService>().listProcessingAgreements() } ?: return@launch
            if (agreements.isEmpty()) {
                listPanel.p("Noch keine AVV-Einträge angelegt.")
                return@launch
            }
            agreements.forEach { agreement -> renderAgreementRow(listPanel, agreement, canManage, ::refreshList) }
        }
    }
    refreshList()

    if (canManage) {
        panel.h2("Neuen AVV-Eintrag anlegen") { addCssClass("h5") }
        renderAgreementCreationForm(panel, ::refreshList)
    }
}

private fun renderAgreementRow(
    panel: SimplePanel,
    agreement: ProcessingAgreementDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(agreement.processorName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(avvStatusLabel(agreement.avvStatus), avvStatusColor(agreement.avvStatus))
    headerRow.activeStatusBadge(agreement.active)

    // D11: the reason for a SIGNED-but-inactive mismatch is legible without opening the edit form.
    if (!agreement.active && agreement.avvStatus == AvvStatus.SIGNED) {
        row.div(avvReviewOverdueCaption()) { addCssClasses("text-muted small") }
    }

    row.div(agreement.processingPurpose) { addCssClasses("small") }
    row.div("Datenkategorien: ${agreement.dataCategories}") { addCssClasses("text-muted small") }
    agreement.reviewDueDate?.let { row.div("Prüftermin: $it") { addCssClasses("text-muted small") } }

    if (canManage) {
        val editButton = row.button("Bearbeiten", style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                renderAgreementForm(editPanel, agreement) {
                    editPanel.hide()
                    onChanged()
                }
                editPanel.show()
            } else {
                editPanel.hide()
            }
        }
    }
}

private fun renderAgreementCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    renderAgreementForm(panel, existing = null, onSaved = onCreated)
}

/** One form for both create ([existing] `null`) and update ([existing] non-`null`), matching
 * `CommitteesScreen.renderCommitteeEditForm`'s "prefill from the existing row" idiom. */
private fun renderAgreementForm(
    panel: SimplePanel,
    existing: ProcessingAgreementDto?,
    onSaved: () -> Unit,
) {
    val statusOptions = AvvStatus.entries.map { it.name to avvStatusLabel(it) }
    val processorNameInput = panel.text(value = existing?.processorName, label = "Verarbeiter")
    val processingPurposeInput = panel.text(value = existing?.processingPurpose, label = "Verarbeitungszweck")
    val dataCategoriesInput = panel.text(value = existing?.dataCategories, label = "Datenkategorien")
    val statusSelect =
        panel.select(options = statusOptions, value = (existing?.avvStatus ?: AvvStatus.NONE).name, label = "AVV-Status")
    val signedDateInput = panel.text(value = existing?.signedDate?.toString(), label = "Unterzeichnet am (JJJJ-MM-TT, optional)")
    val reviewDueDateInput = panel.text(value = existing?.reviewDueDate?.toString(), label = "Prüftermin (JJJJ-MM-TT, optional)")
    val documentIdInput = panel.text(value = existing?.documentId, label = "Dokument-ID (optional)")
    val notesInput = panel.textArea(value = existing?.notes, label = "Notizen (optional)", rows = 2)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = panel.button(if (existing == null) "AVV-Eintrag anlegen" else "Speichern", style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val processorName = processorNameInput.value.orEmpty().trim()
        val processingPurpose = processingPurposeInput.value.orEmpty().trim()
        val dataCategories = dataCategoriesInput.value.orEmpty().trim()
        val statusValue = statusSelect.value
        val signedDate = parseOptionalDate(signedDateInput.value)
        val reviewDueDate = parseOptionalDate(reviewDueDateInput.value)
        val hasInvalidDate =
            (signedDateInput.value?.trim()?.isNotBlank() == true && signedDate == null) ||
                (reviewDueDateInput.value?.trim()?.isNotBlank() == true && reviewDueDate == null)

        if (!Validation.isNonBlank(processorName) ||
            !Validation.isNonBlank(processingPurpose) ||
            !Validation.isNonBlank(dataCategories) ||
            statusValue == null
        ) {
            errorBox.content = "Bitte Verarbeiter, Verarbeitungszweck, Datenkategorien und AVV-Status angeben."
            errorBox.show()
            return@onClick
        }
        if (hasInvalidDate) {
            errorBox.content = "Bitte gültige Datumsangaben (JJJJ-MM-TT) verwenden, oder das Feld leer lassen."
            errorBox.show()
            return@onClick
        }

        val input =
            ProcessingAgreementInput(
                processorName = processorName,
                processingPurpose = processingPurpose,
                dataCategories = dataCategories,
                avvStatus = AvvStatus.valueOf(statusValue),
                signedDate = signedDate,
                reviewDueDate = reviewDueDate,
                documentId = documentIdInput.value?.trim()?.takeIf { it.isNotBlank() },
                notes = notesInput.value?.trim()?.takeIf { it.isNotBlank() },
            )

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    val service = rpcService<IDsgvoComplianceService>()
                    if (existing ==
                        null
                    ) {
                        service.createProcessingAgreement(input)
                    } else {
                        service.updateProcessingAgreement(existing.id, input)
                    }
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess("AVV-Eintrag \"$processorName\" wurde ${if (existing == null) "angelegt" else "aktualisiert"}.")
                if (existing == null) {
                    processorNameInput.value = null
                    processingPurposeInput.value = null
                    dataCategoriesInput.value = null
                    signedDateInput.value = null
                    reviewDueDateInput.value = null
                    documentIdInput.value = null
                    notesInput.value = null
                }
                onSaved()
            }
        }
    }
}

// ================================================================================================
// Baustein 2 -- Technisch-organisatorische Maßnahmen (TOM)
// ================================================================================================

private fun renderTomTab(panel: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.ADMIN)
    panel.h2("Technisch-organisatorische Maßnahmen (TOM)")
    panel.div(
        "Dokumentation der acht Standard-TOM-Kategorien. \"Version\" ist ein einfacher Zähler, der " +
            "bei jeder Aktualisierung um eins steigt -- keine eigene Versionshistorie mit Diff-Ansicht " +
            "in dieser Welle.",
    ) { addCssClasses("text-muted small") }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val categoryOptions = listOf("" to "Alle Kategorien") + TomCategory.entries.map { it.name to tomCategoryLabel(it) }
    val categorySelect = filterRow.select(options = categoryOptions, value = "", label = "Kategorie")
    val filterButton = filterRow.button("Filtern", style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val category = categorySelect.value?.takeIf { it.isNotBlank() }?.let { TomCategory.valueOf(it) }
        AppScope.launch {
            val toms = guarded { rpcService<IDsgvoComplianceService>().listTechnicalOrganizationalMeasures(category) } ?: return@launch
            if (toms.isEmpty()) {
                listPanel.p("Noch keine TOM-Einträge angelegt.")
                return@launch
            }
            toms.forEach { tom -> renderTomRow(listPanel, tom, canManage, ::refreshList) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()

    if (canManage) {
        panel.h2("Neue TOM anlegen") { addCssClass("h5") }
        renderTomCreationForm(panel, ::refreshList)
    }
}

private fun renderTomRow(
    panel: SimplePanel,
    tom: TechnicalOrganizationalMeasureDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.typeBadge(tomCategoryLabel(tom.category), tomCategoryColor(tom.category))
    headerRow.div(tom.title) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.div("Version ${tom.version}") { addCssClasses("text-muted small") }

    row.div(tom.description) { addCssClasses("small") }

    if (canManage) {
        val editButton = row.button("Bearbeiten", style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                renderTomForm(editPanel, tom) {
                    editPanel.hide()
                    onChanged()
                }
                editPanel.show()
            } else {
                editPanel.hide()
            }
        }
    }
}

private fun renderTomCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    renderTomForm(panel, existing = null, onSaved = onCreated)
}

private fun renderTomForm(
    panel: SimplePanel,
    existing: TechnicalOrganizationalMeasureDto?,
    onSaved: () -> Unit,
) {
    val categoryOptions = TomCategory.entries.map { it.name to tomCategoryLabel(it) }
    val categorySelect =
        panel.select(
            options = categoryOptions,
            value = (existing?.category ?: TomCategory.SYSTEM_ACCESS_CONTROL).name,
            label = "Kategorie",
        )
    val titleInput = panel.text(value = existing?.title, label = "Titel")
    val descriptionInput = panel.textArea(value = existing?.description, label = "Beschreibung", rows = 3)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = panel.button(if (existing == null) "TOM anlegen" else "Speichern", style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val categoryValue = categorySelect.value
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value.orEmpty().trim()

        if (categoryValue == null || !Validation.isNonBlank(title) || !Validation.isNonBlank(description)) {
            errorBox.content = "Bitte Kategorie, Titel und Beschreibung angeben."
            errorBox.show()
            return@onClick
        }

        val input =
            TechnicalOrganizationalMeasureInput(category = TomCategory.valueOf(categoryValue), title = title, description = description)

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    val service = rpcService<IDsgvoComplianceService>()
                    if (existing ==
                        null
                    ) {
                        service.createTechnicalOrganizationalMeasure(input)
                    } else {
                        service.updateTechnicalOrganizationalMeasure(existing.id, input)
                    }
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess("TOM \"$title\" wurde ${if (existing == null) "angelegt" else "aktualisiert"}.")
                if (existing == null) {
                    titleInput.value = null
                    descriptionInput.value = null
                }
                onSaved()
            }
        }
    }
}

// ================================================================================================
// Baustein 3 -- DSFA/DPIA
// ================================================================================================

private fun renderDsfaTab(panel: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    panel.h2("Datenschutz-Folgenabschätzung (DSFA)")

    // D8(a): unconditional, non-dismissible, above everything else on this tab (X2).
    panel.div(dsfaBannerText()) { addCssClasses("alert alert-warning") }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusOptions = listOf("" to "Alle Status") + DsfaStatus.entries.map { it.name to dsfaStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = "Status")
    val filterButton = filterRow.button("Filtern", style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val status = statusSelect.value?.takeIf { it.isNotBlank() }?.let { DsfaStatus.valueOf(it) }
        AppScope.launch {
            val assessments = guarded { rpcService<IDsgvoComplianceService>().listDpiaAssessments(status) } ?: return@launch
            if (assessments.isEmpty()) {
                listPanel.p("Noch keine DSFA-Einträge angelegt.")
                return@launch
            }
            assessments.forEach { assessment -> renderDpiaRow(listPanel, assessment, canManage, ::refreshList) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()

    if (canManage) {
        panel.h2("Neue DSFA anlegen") { addCssClass("h5") }
        renderDpiaCreationForm(panel, ::refreshList)
    }
}

private fun renderDpiaRow(
    panel: SimplePanel,
    assessment: DpiaAssessmentDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(assessment.title) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(dsfaStatusLabel(assessment.status), dsfaStatusColor(assessment.status))
    assessment.riskBand?.let { headerRow.statusBadge(dpiaRiskBandLabel(it), dpiaRiskBandColor(it)) }
    headerRow.div("Version ${assessment.version}") { addCssClasses("text-muted small") }

    row.div(assessment.processingDescription) { addCssClasses("small") }
    row.div("DSFA erforderlich: ${triStateBooleanLabel(assessment.dpiaRequired)}") { addCssClasses("text-muted small") }

    if (canManage) {
        val editButton = row.button("Bearbeiten", style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                renderDpiaForm(editPanel, assessment) {
                    editPanel.hide()
                    onChanged()
                }
                editPanel.show()
            } else {
                editPanel.hide()
            }
        }
    }
}

private fun renderDpiaCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    renderDpiaForm(panel, existing = null, onSaved = onCreated)
}

private fun renderDpiaForm(
    panel: SimplePanel,
    existing: DpiaAssessmentDto?,
    onSaved: () -> Unit,
) {
    val riskOptions = listOf("" to "Nicht festgelegt") + RiskLevel.entries.map { it.name to riskLevelLabel(it) }
    val statusOptions = DsfaStatus.entries.map { it.name to dsfaStatusLabel(it) }
    val requiredOptions = listOf("" to "Noch nicht festgelegt", "true" to "Ja", "false" to "Nein")

    val titleInput = panel.text(value = existing?.title, label = "Titel")
    val processingDescriptionInput = panel.textArea(value = existing?.processingDescription, label = "Verarbeitungsbeschreibung", rows = 3)
    val necessityInput =
        panel.textArea(value = existing?.necessityProportionality, label = "Erforderlichkeit/Verhältnismäßigkeit (optional)", rows = 2)
    val likelihoodSelect =
        panel.select(options = riskOptions, value = existing?.riskLikelihood?.name ?: "", label = "Eintrittswahrscheinlichkeit")
    val severitySelect = panel.select(options = riskOptions, value = existing?.riskSeverity?.name ?: "", label = "Schadenshöhe")
    val riskAssessmentInput = panel.textArea(value = existing?.riskAssessment, label = "Risikobewertung (optional)", rows = 2)
    val mitigationInput = panel.textArea(value = existing?.mitigationMeasures, label = "Abhilfemaßnahmen (optional)", rows = 2)
    val dpiaRequiredSelect =
        panel.select(
            options = requiredOptions,
            value = existing?.dpiaRequired?.toString() ?: "",
            label = "DSFA erforderlich (Ihre Entscheidung)",
        )
    val outcomeRationaleInput = panel.textArea(value = existing?.outcomeRationale, label = "Begründung (optional)", rows = 2)
    val statusSelect = panel.select(options = statusOptions, value = (existing?.status ?: DsfaStatus.DRAFT).name, label = "Status")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = panel.button(if (existing == null) "DSFA anlegen" else "Speichern", style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val processingDescription = processingDescriptionInput.value.orEmpty().trim()
        val statusValue = statusSelect.value

        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(processingDescription) || statusValue == null) {
            errorBox.content = "Bitte Titel, Verarbeitungsbeschreibung und Status angeben."
            errorBox.show()
            return@onClick
        }

        val input =
            DpiaAssessmentInput(
                title = title,
                processingDescription = processingDescription,
                necessityProportionality = necessityInput.value?.trim()?.takeIf { it.isNotBlank() },
                riskLikelihood = parseOptionalEnum<RiskLevel>(likelihoodSelect.value),
                riskSeverity = parseOptionalEnum<RiskLevel>(severitySelect.value),
                riskAssessment = riskAssessmentInput.value?.trim()?.takeIf { it.isNotBlank() },
                mitigationMeasures = mitigationInput.value?.trim()?.takeIf { it.isNotBlank() },
                dpiaRequired = parseTriStateBoolean(dpiaRequiredSelect.value),
                outcomeRationale = outcomeRationaleInput.value?.trim()?.takeIf { it.isNotBlank() },
                status = DsfaStatus.valueOf(statusValue),
            )

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    val service = rpcService<IDsgvoComplianceService>()
                    if (existing == null) service.createDpiaAssessment(input) else service.updateDpiaAssessment(existing.id, input)
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess("DSFA \"$title\" wurde ${if (existing == null) "angelegt" else "aktualisiert"}.")
                if (existing == null) {
                    titleInput.value = null
                    processingDescriptionInput.value = null
                    necessityInput.value = null
                    riskAssessmentInput.value = null
                    mitigationInput.value = null
                    outcomeRationaleInput.value = null
                }
                onSaved()
            }
        }
    }
}

// ================================================================================================
// Baustein 4 -- Datenpannenmeldung (Data Breach Incidents)
// ================================================================================================

private fun renderBreachTab(panel: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    panel.h2("Datenpannen")

    // D8(a): unconditional, non-dismissible, above everything else on this tab (X2).
    panel.div(breachBannerText()) { addCssClasses("alert alert-warning") }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusOptions = listOf("" to "Alle Status") + BreachStatus.entries.map { it.name to breachStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = "Status")
    val filterButton = filterRow.button("Filtern", style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val status = statusSelect.value?.takeIf { it.isNotBlank() }?.let { BreachStatus.valueOf(it) }
        AppScope.launch {
            val incidents = guarded { rpcService<IDsgvoComplianceService>().listDataBreachIncidents(status) } ?: return@launch
            if (incidents.isEmpty()) {
                listPanel.p("Noch keine Datenpannen erfasst.")
                return@launch
            }
            // D7: OVERDUE first, then DUE_SOON, WITHIN_WINDOW, SATISFIED, each group by deadline
            // ascending -- an overdue incident must never require scrolling to find. The server's
            // own order (newest-first by reportedAt) is deliberately overridden here.
            sortBreachIncidentsForDisplay(incidents).forEach { incident -> renderBreachRow(listPanel, incident, canManage, ::refreshList) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()

    if (canManage) {
        panel.h2("Neue Datenpanne melden") { addCssClass("h5") }
        renderBreachCreationForm(panel, ::refreshList)
    }
}

private fun renderBreachRow(
    panel: SimplePanel,
    incident: DataBreachIncidentDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val rowCss = if (incident.deadlineStatus == BreachDeadlineStatus.OVERDUE) "border rounded p-2 border-danger" else "border rounded p-2"
    val row = panel.vPanel(spacing = 4) { addCssClasses(rowCss) }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(breachStatusLabel(incident.status), breachStatusColor(incident.status))
    // D7: deadline badge directly in the row header, next to the status badge -- never only in detail.
    headerRow.statusBadge(breachDeadlineStatusLabel(incident.deadlineStatus), breachDeadlineStatusColor(incident.deadlineStatus))
    headerRow.div("Frist: ${incident.authorityNotificationDeadline}") { addCssClasses("flex-grow-1 text-muted small") }

    row.div(incident.description) { addCssClasses("small") }
    row.div("Entdeckt am: ${incident.discoveredAt} · Betroffene Datenkategorien: ${incident.affectedDataCategories}") {
        addCssClasses("text-muted small")
    }
    row.div(
        "Meldung an Aufsichtsbehörde erforderlich: ${triStateBooleanLabel(incident.authorityNotificationRequired)}" +
            (incident.authorityNotifiedAt?.let { " · gemeldet am $it" } ?: ""),
    ) { addCssClasses("text-muted small") }

    if (canManage) {
        val editButton = row.button("Bearbeiten", style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                renderBreachForm(editPanel, incident) {
                    editPanel.hide()
                    onChanged()
                }
                editPanel.show()
            } else {
                editPanel.hide()
            }
        }
    }
}

private fun renderBreachCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    renderBreachForm(panel, existing = null, onSaved = onCreated)
}

private fun renderBreachForm(
    panel: SimplePanel,
    existing: DataBreachIncidentDto?,
    onSaved: () -> Unit,
) {
    val riskOptions = listOf("" to "Nicht festgelegt") + RiskLevel.entries.map { it.name to riskLevelLabel(it) }
    val statusOptions = BreachStatus.entries.map { it.name to breachStatusLabel(it) }
    val requiredOptions = listOf("" to "Noch nicht festgelegt", "true" to "Ja", "false" to "Nein")

    val discoveredAtInput =
        panel.text(value = existing?.discoveredAt?.toString(), label = "Entdeckt am (JJJJ-MM-TTTHH:MM:SS) -- startet die 72h-Frist")
    val descriptionInput = panel.textArea(value = existing?.description, label = "Beschreibung", rows = 3)
    val affectedDataCategoriesInput = panel.text(value = existing?.affectedDataCategories, label = "Betroffene Datenkategorien")
    val estimatedAffectedPersonsInput =
        panel.text(value = existing?.estimatedAffectedPersons?.toString(), label = "Geschätzte Anzahl betroffener Personen (optional)")
    val riskAssessmentInput = panel.textArea(value = existing?.riskAssessment, label = "Risikobewertung (optional)", rows = 2)
    val riskLevelSelect = panel.select(options = riskOptions, value = existing?.riskLevel?.name ?: "", label = "Risikostufe")
    val authorityRequiredSelect =
        panel.select(
            options = requiredOptions,
            value = existing?.authorityNotificationRequired?.toString() ?: "",
            label = "Meldung an Aufsichtsbehörde erforderlich (Ihre Entscheidung)",
        )
    val authorityNotifiedAtInput =
        panel.text(
            value = existing?.authorityNotifiedAt?.toString(),
            label = "Aufsichtsbehörde benachrichtigt am (JJJJ-MM-TTTHH:MM:SS, optional)",
        )
    val dataSubjectsNotifiedAtInput =
        panel.text(
            value = existing?.dataSubjectsNotifiedAt?.toString(),
            label = "Betroffene Personen benachrichtigt am (JJJJ-MM-TTTHH:MM:SS, optional)",
        )
    val statusSelect = panel.select(options = statusOptions, value = (existing?.status ?: BreachStatus.REPORTED).name, label = "Status")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = panel.button(if (existing == null) "Datenpanne melden" else "Speichern", style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val discoveredAt = parseRequiredDateTime(discoveredAtInput.value)
        val description = descriptionInput.value.orEmpty().trim()
        val affectedDataCategories = affectedDataCategoriesInput.value.orEmpty().trim()
        val statusValue = statusSelect.value
        val estimatedAffectedPersonsRaw = estimatedAffectedPersonsInput.value?.trim().orEmpty()
        val estimatedAffectedPersons = estimatedAffectedPersonsRaw.toIntOrNull()
        val hasInvalidEstimate = estimatedAffectedPersonsRaw.isNotBlank() && estimatedAffectedPersons == null
        val authorityNotifiedAtRaw = authorityNotifiedAtInput.value?.trim().orEmpty()
        val authorityNotifiedAt = parseOptionalDateTime(authorityNotifiedAtInput.value)
        val hasInvalidAuthorityNotifiedAt = authorityNotifiedAtRaw.isNotBlank() && authorityNotifiedAt == null
        val dataSubjectsNotifiedAtRaw = dataSubjectsNotifiedAtInput.value?.trim().orEmpty()
        val dataSubjectsNotifiedAt = parseOptionalDateTime(dataSubjectsNotifiedAtInput.value)
        val hasInvalidDataSubjectsNotifiedAt = dataSubjectsNotifiedAtRaw.isNotBlank() && dataSubjectsNotifiedAt == null

        if (discoveredAt == null ||
            !Validation.isNonBlank(description) ||
            !Validation.isNonBlank(affectedDataCategories) ||
            statusValue == null
        ) {
            errorBox.content =
                "Bitte einen gültigen Entdeckungszeitpunkt (JJJJ-MM-TTTHH:MM:SS), Beschreibung, betroffene " +
                "Datenkategorien und Status angeben."
            errorBox.show()
            return@onClick
        }
        if (hasInvalidEstimate || hasInvalidAuthorityNotifiedAt || hasInvalidDataSubjectsNotifiedAt) {
            errorBox.content = "Bitte gültige Werte für die optionalen Felder verwenden, oder leer lassen."
            errorBox.show()
            return@onClick
        }

        val input =
            DataBreachIncidentInput(
                discoveredAt = discoveredAt,
                description = description,
                affectedDataCategories = affectedDataCategories,
                estimatedAffectedPersons = estimatedAffectedPersons,
                riskAssessment = riskAssessmentInput.value?.trim()?.takeIf { it.isNotBlank() },
                riskLevel = parseOptionalEnum<RiskLevel>(riskLevelSelect.value),
                authorityNotificationRequired = parseTriStateBoolean(authorityRequiredSelect.value),
                authorityNotifiedAt = authorityNotifiedAt,
                dataSubjectsNotifiedAt = dataSubjectsNotifiedAt,
                status = BreachStatus.valueOf(statusValue),
            )

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    val service = rpcService<IDsgvoComplianceService>()
                    if (existing == null) service.createDataBreachIncident(input) else service.updateDataBreachIncident(existing.id, input)
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess("Datenpanne wurde ${if (existing == null) "gemeldet" else "aktualisiert"}.")
                if (existing == null) {
                    discoveredAtInput.value = null
                    descriptionInput.value = null
                    affectedDataCategoriesInput.value = null
                    estimatedAffectedPersonsInput.value = null
                    riskAssessmentInput.value = null
                    authorityNotifiedAtInput.value = null
                    dataSubjectsNotifiedAtInput.value = null
                }
                onSaved()
            }
        }
    }
}

// ================================================================================================
// Pure helpers -- covered by DsgvoComplianceScreenTest.kt
// ================================================================================================

/** D11's exact inline caption for a `SIGNED`-but-now-inactive AVV row. */
fun avvReviewOverdueCaption(): String = "Prüftermin überschritten -- als inaktiv markiert, bis neu geprüft."

/** D8(a)'s exact DSFA-tab banner copy. */
fun dsfaBannerText(): String =
    "Die Risikoeinstufung (LOW/MEDIUM/HIGH/CRITICAL) hier ist eine Visualisierungshilfe aus " +
        "Eintrittswahrscheinlichkeit × Schadenshöhe -- keine Art. 35 DSGVO Erforderlichkeits-" +
        "Feststellung. Ob eine Datenschutz-Folgenabschätzung tatsächlich erforderlich ist, legen " +
        "ausschließlich Sie im Feld \"DSFA erforderlich\" fest; dieses System berechnet das nicht."

/** D8(a)'s exact Breach-tab banner copy. */
fun breachBannerText(): String =
    "Die angezeigte Frist ist die gesetzliche 72-Stunden-Uhr nach Art. 33 Abs. 1 DSGVO ab " +
        "Kenntnisnahme -- sie entscheidet nicht, ob überhaupt eine Meldepflicht besteht (das legen " +
        "ausschließlich Sie im Feld \"Meldung an Aufsichtsbehörde erforderlich\" fest) und ersetzt " +
        "keine rechtliche Prüfung des Meldezeitpunkts. Bei einem echten Vorfall: " +
        "Datenschutzbeauftragte/n oder Anwalt/Anwältin hinzuziehen."

/** D7's exact display group order: `OVERDUE` first, then `DUE_SOON`, `WITHIN_WINDOW`, `SATISFIED`. */
fun breachDeadlineDisplayRank(status: BreachDeadlineStatus): Int =
    when (status) {
        BreachDeadlineStatus.OVERDUE -> 0
        BreachDeadlineStatus.DUE_SOON -> 1
        BreachDeadlineStatus.WITHIN_WINDOW -> 2
        BreachDeadlineStatus.SATISFIED -> 3
    }

/** D7: re-sorts the server's newest-first list into the design's escalation-first order --
 * grouped by [breachDeadlineDisplayRank], each group by [DataBreachIncidentDto.authorityNotificationDeadline]
 * ascending, so an overdue incident is never below the fold. */
fun sortBreachIncidentsForDisplay(incidents: List<DataBreachIncidentDto>): List<DataBreachIncidentDto> =
    incidents.sortedWith(
        compareBy(
            { breachDeadlineDisplayRank(it.deadlineStatus) },
            { it.authorityNotificationDeadline },
        ),
    )

/** Renders a `Boolean?` human-input field's three real states as plain text -- deliberately NOT a
 * colored badge, since this is always a human-entered legal call (`dpiaRequired`/
 * `authorityNotificationRequired`), not a lifecycle status or fixed classification this client
 * itself derives. */
fun triStateBooleanLabel(value: Boolean?): String =
    when (value) {
        true -> "Ja"
        false -> "Nein"
        null -> "Noch nicht festgelegt"
    }

/** Inverse of [triStateBooleanLabel]'s underlying select value -- `""`/`null` means "not set". */
fun parseTriStateBoolean(raw: String?): Boolean? =
    when (raw) {
        "true" -> true
        "false" -> false
        else -> null
    }

/** Blank means "not selected" -- generic helper for any nullable enum backed by an optional
 * `select` with a leading blank "Nicht festgelegt" option (mirrors [parseOptionalDateTime]'s own
 * "blank means no value" posture for the risk-level/status filters and inputs on this screen). */
inline fun <reified T : Enum<T>> parseOptionalEnum(raw: String?): T? = raw?.trim()?.takeIf { it.isNotBlank() }?.let { enumValueOf<T>(it) }

/** Blank/unparsable input means "no value" -- mirrors [parseOptionalDateTime] (`AuditLogScreen.kt`),
 * but for a plain `LocalDate` (AVV `signedDate`/`reviewDueDate`). */
fun parseOptionalDate(raw: String?): LocalDate? =
    raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** A required `LocalDateTime` field (Breach `discoveredAt`) -- blank or unparsable both resolve to
 * `null`, which the caller then treats as a validation failure rather than silently defaulting to
 * "now" or any other guessed value. */
fun parseRequiredDateTime(raw: String?): LocalDateTime? =
    raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
