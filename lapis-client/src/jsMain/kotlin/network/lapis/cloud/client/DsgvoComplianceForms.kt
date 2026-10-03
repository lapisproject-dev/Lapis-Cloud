package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AvvStatus
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

// V1.9.49 (R36B): the four register forms (AVV, TOM, DSFA, Datenpanne) of `DsgvoComplianceScreen.kt`. One builder serves both creating
// (`existing = null`, built into the host of a collapsible create form, with a Cancel) and editing (inline in the row, no Cancel). The
// fingerprint of each form lists EVERY input (loose widgets, no `LapisForm`); DsgvoComplianceCollapsibleFormsDomTest proves that.

/** One form for both create ([existing] `null`) and update ([existing] non-`null`), matching
 * `CommitteesScreen.renderCommitteeEditForm`'s "prefill from the existing row" idiom. */
internal fun SimplePanel.renderAgreementForm(
    existing: ProcessingAgreementDto?,
    collapse: ((Boolean) -> Unit)? = null,
    onSaved: () -> Unit,
): FormSnapshot {
    val panel = this
    val statusOptions = AvvStatus.entries.map { it.name to avvStatusLabel(it) }
    val processorNameInput = panel.text(value = existing?.processorName, label = tr("Verarbeiter"))
    val processingPurposeInput = panel.text(value = existing?.processingPurpose, label = tr("Verarbeitungszweck"))
    val dataCategoriesInput = panel.text(value = existing?.dataCategories, label = tr("Datenkategorien"))
    val statusSelect =
        panel.select(options = statusOptions, value = (existing?.avvStatus ?: AvvStatus.NONE).name, label = tr("AVV-Status"))
    val signedDateInput = panel.text(value = existing?.signedDate?.toString(), label = tr("Unterzeichnet am (JJJJ-MM-TT, optional)"))
    val reviewDueDateInput = panel.text(value = existing?.reviewDueDate?.toString(), label = tr("Prüftermin (JJJJ-MM-TT, optional)"))
    val documentIdInput = panel.text(value = existing?.documentId, label = tr("Dokument-ID (optional)"))
    val notesInput = panel.textArea(value = existing?.notes, label = tr("Notizen (optional)"), rows = 2)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton =
        newActionButton(
            if (existing == null) ActionIcon.ADD else ActionIcon.SAVE,
            if (existing == null) tr("AVV-Eintrag anlegen") else tr("Speichern"),
            ButtonStyle.PRIMARY,
        )
    val buttonRow = panel.hPanel(spacing = 8)
    // Creating (existing == null) inside a collapsible create form has a Cancel; editing in a row keeps its own toggle.
    if (existing == null && collapse != null) buttonRow.add(collapseCancelButton(collapse))
    buttonRow.add(saveButton)
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
            errorBox.content = tr("Bitte Verarbeiter, Verarbeitungszweck, Datenkategorien und AVV-Status angeben.")
            errorBox.show()
            return@onClick
        }
        if (hasInvalidDate) {
            errorBox.content = tr("Bitte gültige Datumsangaben (JJJJ-MM-TT) verwenden, oder das Feld leer lassen.")
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
                val actionWord = if (existing == null) gettext("angelegt") else gettext("aktualisiert")
                notifySuccess(gettext("AVV-Eintrag \"%1\" wurde %2.", processorName, actionWord))
                if (existing == null) collapse?.invoke(true)
                onSaved()
            }
        }
    }
    return FormSnapshot {
        listOf(
            processorNameInput.value,
            processingPurposeInput.value,
            dataCategoriesInput.value,
            statusSelect.value,
            signedDateInput.value,
            reviewDueDateInput.value,
            documentIdInput.value,
            notesInput.value,
        ).map {
            it.orEmpty()
        }
    }
}

internal fun SimplePanel.renderTomForm(
    existing: TechnicalOrganizationalMeasureDto?,
    collapse: ((Boolean) -> Unit)? = null,
    onSaved: () -> Unit,
): FormSnapshot {
    val panel = this
    val categoryOptions = TomCategory.entries.map { it.name to tomCategoryLabel(it) }
    val categorySelect =
        panel.select(
            options = categoryOptions,
            value = (existing?.category ?: TomCategory.SYSTEM_ACCESS_CONTROL).name,
            label = tr("Kategorie"),
        )
    val titleInput = panel.text(value = existing?.title, label = tr("Titel"))
    val descriptionInput = panel.textArea(value = existing?.description, label = tr("Beschreibung"), rows = 3)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton =
        newActionButton(
            if (existing == null) ActionIcon.ADD else ActionIcon.SAVE,
            if (existing == null) tr("TOM anlegen") else tr("Speichern"),
            ButtonStyle.PRIMARY,
        )
    val buttonRow = panel.hPanel(spacing = 8)
    // Creating (existing == null) inside a collapsible create form has a Cancel; editing in a row keeps its own toggle.
    if (existing == null && collapse != null) buttonRow.add(collapseCancelButton(collapse))
    buttonRow.add(saveButton)
    saveButton.onClick {
        errorBox.hide()
        val categoryValue = categorySelect.value
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value.orEmpty().trim()

        if (categoryValue == null || !Validation.isNonBlank(title) || !Validation.isNonBlank(description)) {
            errorBox.content = tr("Bitte Kategorie, Titel und Beschreibung angeben.")
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
                val actionWord = if (existing == null) gettext("angelegt") else gettext("aktualisiert")
                notifySuccess(gettext("TOM \"%1\" wurde %2.", title, actionWord))
                if (existing == null) collapse?.invoke(true)
                onSaved()
            }
        }
    }
    return FormSnapshot {
        listOf(categorySelect.value, titleInput.value, descriptionInput.value).map { it.orEmpty() }
    }
}

internal fun SimplePanel.renderDpiaForm(
    existing: DpiaAssessmentDto?,
    collapse: ((Boolean) -> Unit)? = null,
    onSaved: () -> Unit,
): FormSnapshot {
    val panel = this
    val riskOptions = listOf("" to tr("Nicht festgelegt")) + RiskLevel.entries.map { it.name to riskLevelLabel(it) }
    val statusOptions = DsfaStatus.entries.map { it.name to dsfaStatusLabel(it) }
    val requiredOptions = listOf("" to tr("Noch nicht festgelegt"), "true" to tr("Ja"), "false" to tr("Nein"))

    val titleInput = panel.text(value = existing?.title, label = tr("Titel"))
    val processingDescriptionInput =
        panel.textArea(value = existing?.processingDescription, label = tr("Verarbeitungsbeschreibung"), rows = 3)
    val necessityInput =
        panel.textArea(
            value = existing?.necessityProportionality,
            label = tr("Erforderlichkeit/Verhältnismäßigkeit (optional)"),
            rows = 2,
        )
    val likelihoodSelect =
        panel.select(options = riskOptions, value = existing?.riskLikelihood?.name ?: "", label = tr("Eintrittswahrscheinlichkeit"))
    val severitySelect = panel.select(options = riskOptions, value = existing?.riskSeverity?.name ?: "", label = tr("Schadenshöhe"))
    val riskAssessmentInput = panel.textArea(value = existing?.riskAssessment, label = tr("Risikobewertung (optional)"), rows = 2)
    val mitigationInput = panel.textArea(value = existing?.mitigationMeasures, label = tr("Abhilfemaßnahmen (optional)"), rows = 2)
    val dpiaRequiredSelect =
        panel.select(
            options = requiredOptions,
            value = existing?.dpiaRequired?.toString() ?: "",
            label = tr("DSFA erforderlich (Ihre Entscheidung)"),
        )
    val outcomeRationaleInput = panel.textArea(value = existing?.outcomeRationale, label = tr("Begründung (optional)"), rows = 2)
    val statusSelect = panel.select(options = statusOptions, value = (existing?.status ?: DsfaStatus.DRAFT).name, label = tr("Status"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton =
        newActionButton(
            if (existing == null) ActionIcon.ADD else ActionIcon.SAVE,
            if (existing == null) tr("DSFA anlegen") else tr("Speichern"),
            ButtonStyle.PRIMARY,
        )
    val buttonRow = panel.hPanel(spacing = 8)
    // Creating (existing == null) inside a collapsible create form has a Cancel; editing in a row keeps its own toggle.
    if (existing == null && collapse != null) buttonRow.add(collapseCancelButton(collapse))
    buttonRow.add(saveButton)
    saveButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val processingDescription = processingDescriptionInput.value.orEmpty().trim()
        val statusValue = statusSelect.value

        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(processingDescription) || statusValue == null) {
            errorBox.content = tr("Bitte Titel, Verarbeitungsbeschreibung und Status angeben.")
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
                val actionWord = if (existing == null) gettext("angelegt") else gettext("aktualisiert")
                notifySuccess(gettext("DSFA \"%1\" wurde %2.", title, actionWord))
                if (existing == null) collapse?.invoke(true)
                onSaved()
            }
        }
    }
    return FormSnapshot {
        listOf(
            titleInput.value,
            processingDescriptionInput.value,
            necessityInput.value,
            likelihoodSelect.value,
            severitySelect.value,
            riskAssessmentInput.value,
            mitigationInput.value,
            dpiaRequiredSelect.value,
            outcomeRationaleInput.value,
            statusSelect.value,
        ).map {
            it.orEmpty()
        }
    }
}

internal fun SimplePanel.renderBreachForm(
    existing: DataBreachIncidentDto?,
    collapse: ((Boolean) -> Unit)? = null,
    onSaved: () -> Unit,
): FormSnapshot {
    val panel = this
    val riskOptions = listOf("" to tr("Nicht festgelegt")) + RiskLevel.entries.map { it.name to riskLevelLabel(it) }
    val statusOptions = BreachStatus.entries.map { it.name to breachStatusLabel(it) }
    val requiredOptions = listOf("" to tr("Noch nicht festgelegt"), "true" to tr("Ja"), "false" to tr("Nein"))

    val discoveredAtInput =
        panel.text(
            value = existing?.discoveredAt?.toString(),
            label = tr("Entdeckt am (JJJJ-MM-TTTHH:MM:SS) -- startet die 72h-Frist"),
        )
    val descriptionInput = panel.textArea(value = existing?.description, label = tr("Beschreibung"), rows = 3)
    val affectedDataCategoriesInput = panel.text(value = existing?.affectedDataCategories, label = tr("Betroffene Datenkategorien"))
    val estimatedAffectedPersonsInput =
        panel.text(
            value = existing?.estimatedAffectedPersons?.toString(),
            label = tr("Geschätzte Anzahl betroffener Personen (optional)"),
        )
    val riskAssessmentInput = panel.textArea(value = existing?.riskAssessment, label = tr("Risikobewertung (optional)"), rows = 2)
    val riskLevelSelect = panel.select(options = riskOptions, value = existing?.riskLevel?.name ?: "", label = tr("Risikostufe"))
    val authorityRequiredSelect =
        panel.select(
            options = requiredOptions,
            value = existing?.authorityNotificationRequired?.toString() ?: "",
            label = tr("Meldung an Aufsichtsbehörde erforderlich (Ihre Entscheidung)"),
        )
    val authorityNotifiedAtInput =
        panel.text(
            value = existing?.authorityNotifiedAt?.toString(),
            label = tr("Aufsichtsbehörde benachrichtigt am (JJJJ-MM-TTTHH:MM:SS, optional)"),
        )
    val dataSubjectsNotifiedAtInput =
        panel.text(
            value = existing?.dataSubjectsNotifiedAt?.toString(),
            label = tr("Betroffene Personen benachrichtigt am (JJJJ-MM-TTTHH:MM:SS, optional)"),
        )
    val statusSelect =
        panel.select(options = statusOptions, value = (existing?.status ?: BreachStatus.REPORTED).name, label = tr("Status"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton =
        newActionButton(
            if (existing == null) ActionIcon.ADD else ActionIcon.SAVE,
            if (existing == null) tr("Datenpanne melden") else tr("Speichern"),
            ButtonStyle.PRIMARY,
        )
    val buttonRow = panel.hPanel(spacing = 8)
    // Creating (existing == null) inside a collapsible create form has a Cancel; editing in a row keeps its own toggle.
    if (existing == null && collapse != null) buttonRow.add(collapseCancelButton(collapse))
    buttonRow.add(saveButton)
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
                tr(
                    "Bitte einen gültigen Entdeckungszeitpunkt (JJJJ-MM-TTTHH:MM:SS), Beschreibung, betroffene " +
                        "Datenkategorien und Status angeben.",
                )
            errorBox.show()
            return@onClick
        }
        if (hasInvalidEstimate || hasInvalidAuthorityNotifiedAt || hasInvalidDataSubjectsNotifiedAt) {
            errorBox.content = tr("Bitte gültige Werte für die optionalen Felder verwenden, oder leer lassen.")
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
                val actionWord = if (existing == null) gettext("gemeldet") else gettext("aktualisiert")
                notifySuccess(gettext("Datenpanne wurde %1.", actionWord))
                if (existing == null) collapse?.invoke(true)
                onSaved()
            }
        }
    }
    return FormSnapshot {
        listOf(
            discoveredAtInput.value,
            descriptionInput.value,
            affectedDataCategoriesInput.value,
            estimatedAffectedPersonsInput.value,
            riskAssessmentInput.value,
            riskLevelSelect.value,
            authorityRequiredSelect.value,
            authorityNotifiedAtInput.value,
            dataSubjectsNotifiedAtInput.value,
            statusSelect.value,
        ).map {
            it.orEmpty()
        }
    }
}
