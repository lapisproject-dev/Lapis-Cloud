package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DataBreachIncidentDto
import network.lapis.cloud.shared.domain.DpiaAssessmentDto
import network.lapis.cloud.shared.domain.DsfaStatus
import network.lapis.cloud.shared.domain.ProcessingAgreementDto
import network.lapis.cloud.shared.domain.TechnicalOrganizationalMeasureDto
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
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    root.pageHeader(tr("DSGVO-Compliance"))
    root.div(
        tr(
            "Verarbeitungsverzeichnis (AVV), technisch-organisatorische Maßnahmen (TOM), " +
                "Datenschutz-Folgenabschätzungen (DSFA) und Datenpannenmeldungen -- Dokumentations- und " +
                "Arbeitswerkzeug für eine vom Vorstand getroffene Entscheidung, nie automatisierte " +
                "Rechtsberatung.",
        ),
    ) { addCssClasses("text-muted small") }

    // ---- X1: tab toggle row ---------------------------------------------------------------
    val toggleRow = root.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val avvButton = toggleRow.button(tr("Verarbeitungsverzeichnis (AVV)"), style = ButtonStyle.OUTLINEPRIMARY)
    val tomButton = toggleRow.button(tr("TOM"), style = ButtonStyle.OUTLINEPRIMARY)
    val dsfaButton = toggleRow.button(tr("DSFA"), style = ButtonStyle.OUTLINEPRIMARY)
    val breachButton = toggleRow.button(tr("Datenpannen"), style = ButtonStyle.OUTLINEPRIMARY)
    val contentPanel = root.vPanel(spacing = 10)

    // V1.9.49: each view may hold a collapsible create form. Switching the view asks first when that form was changed
    // ("Weiter bearbeiten" stays in the view).
    var active: CollapsibleCreateFormController<Unit>? = null

    fun switchTo(render: (SimplePanel) -> CollapsibleCreateFormController<Unit>?) {
        val go = {
            contentPanel.removeAll()
            active = render(contentPanel)
        }
        val current = active
        if (current != null) current.requestClose(go) else go()
    }
    avvButton.onClick { switchTo(::renderAvvTab) }
    tomButton.onClick { switchTo(::renderTomTab) }
    dsfaButton.onClick { switchTo(::renderDsfaTab) }
    breachButton.onClick { switchTo(::renderBreachTab) }

    active = renderAvvTab(contentPanel)
}

// ================================================================================================
// Baustein 1 -- Verarbeitungsverzeichnis (AVV)
// ================================================================================================

private fun renderAvvTab(panel: SimplePanel): CollapsibleCreateFormController<Unit>? {
    val canManage = AppState.hasRole(AccountRole.ADMIN)
    val titleSlot = panel.sectionTitleRow(tr("Verarbeitungsverzeichnis (AVV)"))
    panel.div(
        tr(
            "Drittdienst-Verarbeiter (z. B. Letterxpress) und der Stand des jeweiligen " +
                "Auftragsverarbeitungsvertrags. \"Aktiv\" wird bei jedem Laden neu berechnet -- ein " +
                "abgelaufener Prüftermin fällt sofort auf, ohne dass jemand daran denken muss, den " +
                "Status manuell umzustellen.",
        ),
    ) { addCssClasses("text-muted small") }

    val createHost = if (canManage) panel.vPanel(spacing = 6) else null

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        AppScope.launch {
            val agreements = guarded { rpcService<IDsgvoComplianceService>().listProcessingAgreements() } ?: return@launch
            if (agreements.isEmpty()) {
                listPanel.p(tr("Noch keine AVV-Einträge angelegt."))
                return@launch
            }
            agreements.forEach { agreement -> renderAgreementRow(listPanel, agreement, canManage, ::refreshList) }
        }
    }
    refreshList()

    return createHost?.let { host ->
        collapsibleCreateForm<Unit>(
            actionSlot = titleSlot,
            formHost = host,
            buttonLabel = tr("Neuer AVV-Eintrag"),
            formId = "dsgvo-avv-create",
        ) { _, close ->
            vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
                .renderAgreementForm(existing = null, collapse = close, onSaved = ::refreshList)
        }
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
    headerRow.untrustedCardTitle(agreement.processorName)
    headerRow.statusBadge(avvStatusLabel(agreement.avvStatus), avvStatusColor(agreement.avvStatus))
    headerRow.activeStatusBadge(agreement.active)

    // D11: the reason for a SIGNED-but-inactive mismatch is legible without opening the edit form.
    if (!agreement.active && agreement.avvStatus == AvvStatus.SIGNED) {
        row.div(avvReviewOverdueCaption()) { addCssClasses("text-muted small") }
    }

    row.untrustedDiv(agreement.processingPurpose, className = "small")
    row.div(gettext("Datenkategorien: %1", agreement.dataCategories)) { addCssClasses("text-muted small") }
    agreement.reviewDueDate?.let { row.div(gettext("Prüftermin: %1", formatDate(it))) { addCssClasses("text-muted small") } }

    if (canManage) {
        val editButton = row.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                editPanel.renderAgreementForm(existing = agreement) {
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

// ================================================================================================
// Baustein 2 -- Technisch-organisatorische Maßnahmen (TOM)
// ================================================================================================

private fun renderTomTab(panel: SimplePanel): CollapsibleCreateFormController<Unit>? {
    val canManage = AppState.hasRole(AccountRole.ADMIN)
    val titleSlot = panel.sectionTitleRow(tr("Technisch-organisatorische Maßnahmen (TOM)"))
    panel.div(
        tr(
            "Dokumentation der acht Standard-TOM-Kategorien. \"Version\" ist ein einfacher Zähler, der " +
                "bei jeder Aktualisierung um eins steigt -- keine eigene Versionshistorie mit Diff-Ansicht " +
                "in dieser Welle.",
        ),
    ) { addCssClasses("text-muted small") }

    val createHost = if (canManage) panel.vPanel(spacing = 6) else null
    val filterRow = panel.lapisToolbar()
    val categoryOptions = listOf("" to tr("Alle Kategorien")) + TomCategory.entries.map { it.name to tomCategoryLabel(it) }
    val categorySelect = filterRow.select(options = categoryOptions, value = "", label = tr("Kategorie"))
    val filterButton = filterRow.actionButton(ActionIcon.FILTER, tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val category = categorySelect.value?.takeIf { it.isNotBlank() }?.let { TomCategory.valueOf(it) }
        AppScope.launch {
            val toms = guarded { rpcService<IDsgvoComplianceService>().listTechnicalOrganizationalMeasures(category) } ?: return@launch
            if (toms.isEmpty()) {
                listPanel.p(tr("Noch keine TOM-Einträge angelegt."))
                return@launch
            }
            toms.forEach { tom -> renderTomRow(listPanel, tom, canManage, ::refreshList) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()

    return createHost?.let { host ->
        collapsibleCreateForm<Unit>(
            actionSlot = titleSlot,
            formHost = host,
            buttonLabel = tr("Neue TOM"),
            formId = "dsgvo-tom-create",
        ) { _, close ->
            vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
                .renderTomForm(existing = null, collapse = close, onSaved = ::refreshList)
        }
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
    // Security audit W6b follow-up round 3 (major finding A): TOM title/description are admin-editable free
    // text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(tom.title)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.div(gettext("Version %1", tom.version)) { addCssClasses("text-muted small") }

    row.div(sanitizeUntrustedI18nText(tom.description)) { addCssClasses("small") }

    if (canManage) {
        val editButton = row.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                editPanel.renderTomForm(existing = tom) {
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

// ================================================================================================
// Baustein 3 -- DSFA/DPIA
// ================================================================================================

private fun renderDsfaTab(panel: SimplePanel): CollapsibleCreateFormController<Unit>? {
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    val titleSlot = panel.sectionTitleRow(tr("Datenschutz-Folgenabschätzung (DSFA)"))

    // D8(a): unconditional, non-dismissible, above everything else on this tab (X2).
    panel.div(dsfaBannerText()) { addCssClasses("alert alert-warning") }

    val createHost = if (canManage) panel.vPanel(spacing = 6) else null
    val filterRow = panel.lapisToolbar()
    val statusOptions = listOf("" to tr("Alle Status")) + DsfaStatus.entries.map { it.name to dsfaStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.actionButton(ActionIcon.FILTER, tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val status = statusSelect.value?.takeIf { it.isNotBlank() }?.let { DsfaStatus.valueOf(it) }
        AppScope.launch {
            val assessments = guarded { rpcService<IDsgvoComplianceService>().listDpiaAssessments(status) } ?: return@launch
            if (assessments.isEmpty()) {
                listPanel.p(tr("Noch keine DSFA-Einträge angelegt."))
                return@launch
            }
            assessments.forEach { assessment -> renderDpiaRow(listPanel, assessment, canManage, ::refreshList) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()

    return createHost?.let { host ->
        collapsibleCreateForm<Unit>(
            actionSlot = titleSlot,
            formHost = host,
            buttonLabel = tr("Neue DSFA"),
            formId = "dsgvo-dsfa-create",
        ) { _, close ->
            vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
                .renderDpiaForm(existing = null, collapse = close, onSaved = ::refreshList)
        }
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
    // Security audit W6b follow-up round 3 (major finding A): DPIA title/description are admin-editable free
    // text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(assessment.title)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(dsfaStatusLabel(assessment.status), dsfaStatusColor(assessment.status))
    assessment.riskBand?.let { headerRow.statusBadge(dpiaRiskBandLabel(it), dpiaRiskBandColor(it)) }
    headerRow.div(gettext("Version %1", assessment.version)) { addCssClasses("text-muted small") }

    row.div(sanitizeUntrustedI18nText(assessment.processingDescription)) { addCssClasses("small") }
    row.div(gettext("DSFA erforderlich: %1", triStateBooleanLabel(assessment.dpiaRequired))) { addCssClasses("text-muted small") }

    if (canManage) {
        val editButton = row.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                editPanel.renderDpiaForm(existing = assessment) {
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

// ================================================================================================
// Baustein 4 -- Datenpannenmeldung (Data Breach Incidents)
// ================================================================================================

private fun renderBreachTab(panel: SimplePanel): CollapsibleCreateFormController<Unit>? {
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    val titleSlot = panel.sectionTitleRow(tr("Datenpannen"))

    // D8(a): unconditional, non-dismissible, above everything else on this tab (X2).
    panel.div(breachBannerText()) { addCssClasses("alert alert-warning") }

    val createHost = if (canManage) panel.vPanel(spacing = 6) else null
    val filterRow = panel.lapisToolbar()
    val statusOptions = listOf("" to tr("Alle Status")) + BreachStatus.entries.map { it.name to breachStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.actionButton(ActionIcon.FILTER, tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = panel.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val status = statusSelect.value?.takeIf { it.isNotBlank() }?.let { BreachStatus.valueOf(it) }
        AppScope.launch {
            val incidents = guarded { rpcService<IDsgvoComplianceService>().listDataBreachIncidents(status) } ?: return@launch
            if (incidents.isEmpty()) {
                listPanel.p(tr("Noch keine Datenpannen erfasst."))
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

    return createHost?.let { host ->
        collapsibleCreateForm<Unit>(
            actionSlot = titleSlot,
            formHost = host,
            buttonLabel = tr("Datenpanne melden"),
            formId = "dsgvo-breach-create",
        ) { _, close ->
            vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
                .renderBreachForm(existing = null, collapse = close, onSaved = ::refreshList)
        }
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
    headerRow.div(
        gettext("Frist: %1", formatDateTime(incident.authorityNotificationDeadline)),
    ) { addCssClasses("flex-grow-1 text-muted small") }

    // Security audit W6b follow-up round 3 (major finding A): an incident description is admin-editable free
    // text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    row.div(sanitizeUntrustedI18nText(incident.description)) { addCssClasses("small") }
    row.div(
        gettext(
            "Entdeckt am: %1 · Betroffene Datenkategorien: %2",
            formatDateTime(incident.discoveredAt),
            incident.affectedDataCategories,
        ),
    ) {
        addCssClasses("text-muted small")
    }
    val notifiedSuffix = incident.authorityNotifiedAt?.let { gettext(" · gemeldet am %1", formatDateTime(it)) } ?: ""
    row.div(
        gettext(
            "Meldung an Aufsichtsbehörde erforderlich: %1",
            triStateBooleanLabel(incident.authorityNotificationRequired),
        ) + notifiedSuffix,
    ) { addCssClasses("text-muted small") }

    if (canManage) {
        val editButton = row.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
        val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        editPanel.hide()
        var editOpen = false
        editButton.onClick {
            editOpen = !editOpen
            if (editOpen) {
                editPanel.removeAll()
                editPanel.renderBreachForm(existing = incident) {
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
