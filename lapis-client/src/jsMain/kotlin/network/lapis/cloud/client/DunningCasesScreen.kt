package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
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
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DunningCaseDetailDto
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningNoticeDto
import network.lapis.cloud.shared.rpc.IDunningService

/**
 * Client-UI wave for GitHub Issue #5 ("Client-UI für das Mahnwesen"). Route-gated TREASURER/
 * BOARD/ADMIN (see `Routes.DUNNING_CASES` KDoc); every write action is additionally gated
 * in-screen via [DunningAuthzUi] (BOARD never sees them -- `issueDunningNotice`/`skipDunningLevel`/
 * `resetDunning`/`cancelDunningNotice` are all TREASURER/ADMIN only).
 *
 * The two ADMIN-only warning bands are rendered ONLY for ADMIN -- `getDunningSettings`/
 * `getDunningComplianceDisclaimer` are both ADMIN-only server-side (plan finding B2), so a
 * TREASURER cannot compute either "is dunning enabled but has zero levels configured" or "is the
 * acknowledged disclaimer stale" without a backend change -- same open question this wave shares
 * with `SepaBatchesScreen.kt`'s own documented O-1.
 *
 * **Real keyset pagination** (GitHub #7 fix): `listDunningCases`'s `afterDueDate`/
 * `afterContributionId` pair is a genuine continuation cursor now (`ORDER BY dueDate, id ASC` with
 * a matching `(dueDate, id) > (afterDueDate, afterContributionId)` filter, see `DunningService.kt`
 * for the compound-condition reasoning) -- so this screen appends pages via a "Weitere laden"
 * button, seeded from the last row's own `dueDate`/`contributionId`, rather than the old ad-hoc
 * date-filter-as-workaround this screen previously shipped with (see git history for the earlier
 * plan finding B1 this superseded).
 */
fun renderDunningCasesScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 960.px
            marginTop = 24.px
        }
    root.h1(tr("Mahnwesen"))

    val role = AppState.session?.role

    if (AppState.hasRole(AccountRole.ADMIN)) {
        renderDunningAdminWarningBands(root)
    }

    root.h2(tr("Offene Mahnvorgänge")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val onlyOpenCheck = filterRow.checkBox(value = true, label = tr("Nur offene Vorgänge"))
    val limitSelect =
        filterRow.select(
            options = listOf("50" to "50", "100" to "100", "200" to "200"),
            value = "50",
            label = tr("Seitengröße"),
        )
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val loadMoreButton = loadMoreRow.button(tr("Weitere laden"), style = ButtonStyle.OUTLINESECONDARY)
    loadMoreButton.hide()

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Vorgang oben auswählen, um Details zu sehen.")) { addCssClasses("text-muted small") }

    // Continuation cursor for "Weitere laden" -- seeded from the last row of the last page loaded,
    // see the file KDoc above and `DunningService.listDunningCases`'s own compound-cursor comment.
    var cursorDueDate: LocalDate? = null
    var cursorContributionId: String? = null
    var table: Table? = null

    fun appendCase(
        case: DunningCaseDto,
        reload: () -> Unit,
    ) {
        var t = table
        if (t == null) {
            t =
                listPanel.table(
                    headerNames =
                        listOf(
                            tr("Mitglied"),
                            tr("Zeitraum"),
                            tr("Betrag"),
                            tr("Fällig am"),
                            tr("Beitragsstatus"),
                            tr("Stufe"),
                            tr("Nächste Stufe"),
                            tr("Gebühren gesamt"),
                            "",
                        ),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            table = t
        }
        renderDunningCaseRow(t, case) { contributionId ->
            selectDunningCase(detailPanel, role, contributionId, reload)
        }
    }

    fun loadPage(reset: Boolean) {
        errorBox.hide()
        if (reset) {
            listPanel.removeAll()
            table = null
            cursorDueDate = null
            cursorContributionId = null
        }
        val limit = limitSelect.value?.toIntOrNull() ?: 50
        loadMoreButton.disabled = true
        AppScope.launch {
            val cases =
                guarded {
                    rpcService<IDunningService>().listDunningCases(
                        onlyOpen = onlyOpenCheck.value,
                        limit = limit,
                        afterDueDate = cursorDueDate,
                        afterContributionId = cursorContributionId,
                    )
                }
            loadMoreButton.disabled = false
            if (cases == null) return@launch
            if (cases.isEmpty() && reset) {
                listPanel.p(tr("Keine Mahnvorgänge für diese Filter."))
                loadMoreButton.hide()
                return@launch
            }
            cases.forEach { appendCase(it) { loadPage(reset = true) } }
            cases.lastOrNull()?.let { last ->
                cursorDueDate = last.dueDate
                cursorContributionId = last.contributionId
            }
            // Same size-vs-limit heuristic the previous version used to decide whether more rows
            // might exist -- exact (not `>=`), since `listDunningCases` never returns more than
            // `limit` rows itself.
            if (cases.size == limit) loadMoreButton.show() else loadMoreButton.hide()
        }
    }
    refreshButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)
}

// ================================================================================================
// ADMIN-only warning bands (plan §2.5 "1", finding B2 -- both getDunningSettings and
// getDunningComplianceDisclaimer are ADMIN-only, so this cannot be computed for TREASURER/BOARD)
// ================================================================================================

private fun renderDunningAdminWarningBands(root: SimplePanel) {
    val bandHost = root.vPanel(spacing = 4)
    AppScope.launch {
        val settings = dunningProbe { rpcService<IDunningService>().getDunningSettings() } ?: return@launch
        val disclaimer = dunningProbe { rpcService<IDunningService>().getDunningComplianceDisclaimer() }

        // Precedence band: `enableDunning` does not check `hasActiveLevel` (plan finding, mirrors
        // `DunningService.kt:117-140`) -- "aktiviert" with zero configured levels is reachable and
        // silently means nothing is ever mahned. Its own band, not a footnote on the disclaimer one.
        if (settings.dunningEnabled && settings.activeLevelCount == 0) {
            val band = bandHost.div { addCssClasses("alert alert-warning") }
            band.div(
                tr("Das Mahnwesen ist aktiviert, aber keine Mahnstufe ist konfiguriert -- es wird nichts gemahnt."),
            ) { addCssClass("fw-bold") }
            val link = band.button(tr("Jetzt konfigurieren (Mahnwesen-Konfiguration)"), style = ButtonStyle.LINK)
            link.onClick { navigateTo(Routes.DUNNING_SETTINGS) }
        }

        if (settings.dunningEnabled && disclaimer != null && settings.lastDisclaimerVersion != disclaimer.version) {
            val band = bandHost.div { addCssClasses("alert alert-warning") }
            band.div(
                gettext(
                    "Der rechtliche Hinweistext für das Mahnwesen wurde seit der letzten Bestätigung (Version %1) " +
                        "auf Version %2 aktualisiert.",
                    settings.lastDisclaimerVersion ?: tr("keine"),
                    disclaimer.version,
                ),
            ) { addCssClass("fw-bold") }
            val link = band.button(tr("Erneut bestätigen (Mahnwesen-Konfiguration)"), style = ButtonStyle.LINK)
            link.onClick { navigateTo(Routes.DUNNING_SETTINGS) }
        }
    }
}

// ================================================================================================
// Liste (Zeile)
// ================================================================================================

private fun renderDunningCaseRow(
    table: Table,
    case: DunningCaseDto,
    onSelect: (String) -> Unit,
) {
    table.row {
        cell(case.memberDisplayName)
        cell(gettext("%1 – %2", case.periodStart, case.periodEnd))
        cell { moneySpan(case.amountDue) }
        cell(case.dueDate.toString())
        val statusCell = cell()
        statusCell.statusBadge(contributionStatusLabel(case.contributionStatus), contributionStatusColor(case.contributionStatus))
        val levelCell = cell()
        levelCell.div(case.highestLevelNumber?.toString() ?: "–")
        levelCell.div(gettext("Zyklus %1", case.currentCycleNumber)) { addCssClasses("text-muted small") }
        val nextCell = cell()
        if (case.nextLevelNumber != null) {
            nextCell.div(gettext("Stufe %1", case.nextLevelNumber))
            nextCell.div(case.nextLevelDueOn?.toString() ?: "–") { addCssClasses("text-muted small") }
        } else {
            nextCell.div("–")
        }
        cell { moneySpan(case.totalFeesCharged) }
        val actionsCell = cell()
        val showButton = actionsCell.button(tr("Details"), style = ButtonStyle.OUTLINESECONDARY)
        showButton.onClick { onSelect(case.contributionId) }
    }
}

// ================================================================================================
// Detailbereich (mount-agnostisch)
// ================================================================================================

private fun selectDunningCase(
    detailPanel: SimplePanel,
    role: AccountRole?,
    contributionId: String,
    onListChanged: () -> Unit,
) {
    detailPanel.removeAll()
    detailPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    AppScope.launch {
        val detail = guarded { rpcService<IDunningService>().getDunningCase(contributionId) }?.firstOrNull()
        detailPanel.removeAll()
        if (detail == null) {
            detailPanel.p(tr("Vorgang nicht gefunden.")) { addCssClasses("text-muted small") }
            return@launch
        }
        renderDunningCaseDetail(detailPanel, detail, role) {
            selectDunningCase(detailPanel, role, contributionId, onListChanged)
            onListChanged()
        }
    }
}

private fun renderDunningCaseDetail(
    container: SimplePanel,
    detail: DunningCaseDetailDto,
    role: AccountRole?,
    onChanged: () -> Unit,
) {
    val case = detail.case
    val surface = container.div { addCssClasses("lapis-surface border rounded p-3") }
    val panel = surface.vPanel(spacing = 8)

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(case.memberDisplayName) { addCssClasses("fw-bold flex-grow-1") }
    headerRow.statusBadge(contributionStatusLabel(case.contributionStatus), contributionStatusColor(case.contributionStatus))
    panel.div(gettext("Zeitraum %1 – %2", case.periodStart, case.periodEnd)) { addCssClasses("text-muted small") }
    val amountRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    amountRow.div(tr("Betrag:")) { addCssClasses("text-muted small") }
    amountRow.moneySpan(case.amountDue)
    panel.div(gettext("Fällig am %1", case.dueDate)) { addCssClasses("text-muted small") }

    if (detail.notices.isNotEmpty()) {
        panel.h2(tr("Mahnungen")) { addCssClass("h6") }
        val noticesTable =
            panel.table(
                headerNames =
                    listOf(
                        tr("Stufe"),
                        tr("Name"),
                        tr("Status"),
                        tr("Ausgestellt am"),
                        tr("Antwort bis"),
                        tr("Gebühr"),
                        tr("Postversand"),
                        tr("PDF"),
                        tr("Stornogrund"),
                        "",
                    ),
                types = setOf(TableType.STRIPED, TableType.HOVER),
            )
        detail.notices.forEach { notice -> renderDunningNoticeRow(noticesTable, notice, role, onChanged) }
    }

    if (DunningAuthzUi.canTreasuryAct(role)) {
        renderDunningCaseActionBar(panel, case, role, onChanged)
    }
}

private fun renderDunningNoticeRow(
    table: Table,
    notice: DunningNoticeDto,
    role: AccountRole?,
    onChanged: () -> Unit,
) {
    table.row {
        cell(notice.levelNumber.toString())
        cell(notice.levelName)
        val statusCell = cell()
        statusCell.statusBadge(dunningNoticeStatusLabel(notice.status), dunningNoticeStatusColor(notice.status))
        cell(notice.issuedAt.toString())
        cell(notice.respondBy.toString())
        cell { notice.feeAmount?.let { moneySpan(it) } ?: div("–") }
        val postalCell = cell()
        notice.postalDeliveryStatus?.let {
            postalCell.statusBadge(postalDeliveryStatusLabel(it), postalDeliveryStatusColor(it))
        } ?: postalCell.div("–") { addCssClasses("text-muted small") }
        val pdfCell = cell()
        if (DunningAuthzUi.canDownloadNoticePdf(role, notice.documentId)) {
            pdfCell.link(tr("Herunterladen"), url = DunningHttp.noticePdfUrl(notice.id), target = "_blank") {
                addCssClasses("btn btn-sm btn-outline-primary")
            }
        } else {
            pdfCell.div("–") { addCssClasses("text-muted small") }
        }
        cell(notice.cancellationReason.orEmpty())
        val actionsCell = cell()
        if (DunningAuthzUi.canCancelNotice(role, notice.status)) {
            val cancelButton = actionsCell.button(tr("Stornieren"), style = ButtonStyle.OUTLINEDANGER)
            cancelButton.onClick {
                confirmWithReasonDialog(
                    title = tr("Mahnung stornieren"),
                    message =
                        tr(
                            "Der gesamte Mahnzyklus dieses Beitrags wird storniert, nicht nur diese eine Mahnung. " +
                                "Der Beitrag fällt auf den Status Überfällig zurück.",
                        ),
                    dangerNote =
                        tr(
                            "Nach dem Stornieren stellt der Automat eine neue Mahnung ab Stufe 1 aus -- bei " +
                                "aktiviertem Postversand als echter, kostenpflichtiger Brief.",
                        ),
                    reasonLabel = tr("Grund für die Stornierung"),
                    reasonRequired = true,
                    confirmLabel = tr("Stornieren"),
                ) { reason ->
                    cancelButton.disabled = true
                    AppScope.launch {
                        val result =
                            dunningGuarded(tr(DUNNING_ISSUE_CONFLICT_MESSAGE)) {
                                rpcService<IDunningService>().cancelDunningNotice(notice.id, reason.orEmpty())
                            }
                        cancelButton.disabled = false
                        if (result != null) {
                            notifySuccess(tr("Mahnung storniert."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/** The concrete, human-readable reason a treasurer sees in place of a hidden action button --
 * mirrors [DunningAuthzUi.nextCaseAction]'s own two structural "no action" causes. */
private fun noDunningActionReason(case: DunningCaseDto): String? =
    when {
        case.contributionStatus !in ContributionStatusSets.DUNNABLE ->
            gettext(
                "Beitrag hat den Status \"%1\" -- kein Mahnvorgang möglich.",
                contributionStatusLabel(case.contributionStatus),
            )
        case.nextLevelNumber == null -> tr("Alle konfigurierten Mahnstufen sind im laufenden Zyklus bereits genutzt.")
        else -> null
    }

private fun renderDunningCaseActionBar(
    panel: SimplePanel,
    case: DunningCaseDto,
    role: AccountRole?,
    onChanged: () -> Unit,
) {
    val actionsRow = panel.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center mt-2") }

    fun issue(button: Button? = null) {
        button?.disabled = true
        AppScope.launch {
            val result =
                dunningGuarded(tr(DUNNING_ISSUE_CONFLICT_MESSAGE)) {
                    rpcService<IDunningService>().issueDunningNotice(case.contributionId)
                }
            button?.disabled = false
            if (result != null) {
                notifySuccess(tr("Mahnung ausgestellt."))
                onChanged()
            }
        }
    }

    val nextAction = DunningAuthzUi.nextCaseAction(role, case, todayLocalDate())
    when (nextAction) {
        null -> {
            noDunningActionReason(case)?.let { reason ->
                actionsRow.div(reason) { addCssClasses("text-muted small") }
            }
        }
        DunningCaseAction.ISSUE -> {
            val button = actionsRow.button(tr("Mahnung ausstellen"), style = ButtonStyle.PRIMARY)
            button.onClick { issue(button) }
        }
        DunningCaseAction.ISSUE_EARLY -> {
            val label =
                case.nextLevelDueOn?.let {
                    gettext("Vorzeitig ausstellen (planmäßig ab %1)", it)
                } ?: tr("Vorzeitig ausstellen")
            val button = actionsRow.button(label, style = ButtonStyle.OUTLINEWARNING)
            button.onClick {
                confirmDialog(
                    title = tr("Vorzeitig ausstellen"),
                    message =
                        case.nextLevelDueOn?.let {
                            gettext(
                                "Diese Mahnstufe ist planmäßig erst ab %1 fällig. Trotzdem jetzt ausstellen?",
                                it,
                            )
                        } ?: tr("Diese Mahnstufe ist planmäßig noch nicht fällig. Trotzdem jetzt ausstellen?"),
                    confirmLabel = tr("Jetzt ausstellen"),
                ) { issue(button) }
            }
        }
    }

    if (DunningAuthzUi.canPreviewNextNotice(role, case)) {
        val previewButton = actionsRow.button(tr("Brief-Vorschau (PDF)"), style = ButtonStyle.OUTLINESECONDARY)
        previewButton.onClick { DunningHttp.submitNoticePreviewPdf(case.contributionId) }
    }

    if (DunningAuthzUi.canSkipLevel(role, case)) {
        val skipButton = actionsRow.button(tr("Stufe überspringen"), style = ButtonStyle.OUTLINESECONDARY)
        skipButton.onClick {
            confirmWithReasonDialog(
                title = tr("Mahnstufe überspringen"),
                message = tr("Die nächste Mahnstufe wird ohne Versand einer Mahnung als übersprungen vermerkt."),
                reasonLabel = tr("Grund"),
                reasonRequired = true,
                confirmLabel = tr("Überspringen"),
            ) { reason ->
                skipButton.disabled = true
                AppScope.launch {
                    val result =
                        dunningGuarded(tr(DUNNING_SKIP_CONFLICT_MESSAGE)) {
                            rpcService<IDunningService>().skipDunningLevel(case.contributionId, reason.orEmpty())
                        }
                    skipButton.disabled = false
                    if (result != null) {
                        notifySuccess(tr("Mahnstufe übersprungen."))
                        onChanged()
                    }
                }
            }
        }
    }

    if (DunningAuthzUi.canResetDunning(role, case)) {
        val resetButton = actionsRow.button(tr("Mahnwesen zurücksetzen"), style = ButtonStyle.OUTLINEDANGER)
        resetButton.onClick {
            confirmWithReasonDialog(
                title = tr("Mahnwesen zurücksetzen"),
                message =
                    tr(
                        "Der gesamte laufende Mahnzyklus dieses Beitrags wird storniert und beginnt beim nächsten " +
                            "Fälligkeitslauf von vorn.",
                    ),
                dangerNote =
                    tr(
                        "Der Automat kann daraufhin eine neue Mahnung ab Stufe 1 ausstellen -- bei aktiviertem " +
                            "Postversand als echter, kostenpflichtiger Brief.",
                    ),
                reasonLabel = tr("Grund für das Zurücksetzen"),
                reasonRequired = true,
                confirmLabel = tr("Zurücksetzen"),
            ) { reason ->
                resetButton.disabled = true
                AppScope.launch {
                    val result =
                        dunningGuarded(tr(DUNNING_ISSUE_CONFLICT_MESSAGE)) {
                            rpcService<IDunningService>().resetDunning(case.contributionId, reason.orEmpty())
                        }
                    resetButton.disabled = false
                    if (result != null) {
                        notifySuccess(tr("Mahnwesen zurückgesetzt."))
                        onChanged()
                    }
                }
            }
        }
    }
}
