package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
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
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DunningCaseDetailDto
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.DunningNoticeDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.IDunningService

/**
 * Client-UI wave for GitHub Issue #5 ("Client-UI für das Mahnwesen"). Route-gated TREASURER/
 * BOARD/ADMIN (see `Routes.DUNNING_CASES` KDoc); every write action is additionally gated
 * in-screen via [DunningAuthzUi] (BOARD never sees them -- `issueDunningNotice`/`skipDunningLevel`/
 * `resetDunning`/`cancelDunningNotice` are all TREASURER/ADMIN only).
 *
 * The two warning bands (see [renderDunningWarningBands]) are shown to every role that can see this
 * screen at all (TREASURER/BOARD/ADMIN): `getDunningSettings` is a READ_ROLES method since
 * `f30022c`, so "is dunning enabled but has zero levels configured" is computable for all three.
 * The disclaimer-staleness band stays ADMIN-only, because `getDunningComplianceDisclaimer` itself
 * remains `requireRole(ADMIN)` -- a non-admin never receives a disclaimer to compare against.
 *
 * **Real keyset pagination** (GitHub #7 fix): `listDunningCases`'s `afterDueDate`/
 * `afterContributionId` pair is a genuine continuation cursor now (`ORDER BY dueDate, id ASC` with
 * a matching `(dueDate, id) > (afterDueDate, afterContributionId)` filter, see `DunningService.kt`
 * for the compound-condition reasoning) -- so this screen appends pages via a "Mehr laden" button
 * (same label as every other paginated screen in this codebase), seeded from the last row's own
 * `dueDate`/`contributionId`.
 */
fun renderDunningCasesScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    val role = AppState.session?.role

    if (DunningAuthzUi.showDunningWarningBands(role)) {
        renderDunningWarningBands(root, role)
    }
    root.h1(tr("Mahnwesen"))

    root.h2(tr("Offene Mahnvorgänge")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val searchInput = filterRow.text(label = tr("Suche nach Mitglied"))
    val onlyOpenCheck = filterRow.checkBox(value = true, label = tr("Nur offene Vorgänge"))
    val limitSelect =
        filterRow.select(
            options = listOf("50" to "50", "100" to "100", "200" to "200"),
            value = "50",
            label = tr("Seitengröße"),
        )
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val statusRegion = root.dataStatusRegion()
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Vorgang oben auswählen, um Details zu sehen.")) { addCssClasses("text-muted small") }

    // Continuation cursor for "Mehr laden" -- seeded from the last row of the last page loaded,
    // see the file KDoc above and `DunningService.listDunningCases`'s own compound-cursor comment.
    var cursorDueDate: LocalDate? = null
    var cursorContributionId: String? = null
    var hasMore = false
    var searchTerm = ""
    var generation = 0
    val loaded = mutableListOf<DunningCaseDto>()
    var loading = false
    // Fehlerzustand des Erstabrufs: solange er steht, darf die Suche ihn nicht wegzeichnen.
    var failed = false

    lateinit var loadPage: (Boolean) -> Unit

    fun renderList() {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): solange der erste Ladevorgang läuft, sagt allein
        // die Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld während
        // des Ladens den Leertext über den Ladehinweis -- „lädt" und „keine Daten" gleichzeitig.
        if (loading && loaded.isEmpty()) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen (kein „Noch keine …" darüber).
        if (failed) return
        listPanel.removeAll()
        if (loaded.isEmpty()) {
            countsLabel.content = ""
            // Welle V1.4.26 (W2): „nichts offen" ist eine andere Aussage als „nichts vorhanden" -- bei
            // aktivem „Nur offene Vorgänge" ist eine leere Liste die gute Nachricht.
            val text =
                if (onlyOpenCheck.value) {
                    tr("Kein offener Mahnvorgang.")
                } else {
                    tr("Noch keine Mahnvorgänge erfasst.")
                }
            listPanel.p(text) { addCssClasses("text-muted") }
            return
        }
        val visible = filterDunningCases(loaded, searchTerm)
        countsLabel.content =
            dataCountText(shown = visible.size, loaded = loaded.size, hasMore = hasMore, filtered = searchTerm.isNotBlank())
        if (visible.isEmpty()) {
            listPanel.p(loadedSubsetNoMatchText(term = searchTerm.trim(), hasMore = hasMore)) { addCssClasses("text-muted") }
            return
        }
        listPanel.dataTable(
            columns = dunningCaseColumns(),
            rows = visible,
            actions = { actions, case ->
                // Design-Team-Welle 2026-09-18: Icon-Knopf in der Aktionsspalte, Tooltip "Details anzeigen"
                // (nicht das vorherige knappe "Details" -- ohne sichtbaren Text muss der Tooltip die
                // vollstaendige Handlung benennen).
                val showButton = actions.tableActionButton("fas fa-eye", tr("Details anzeigen"))
                showButton.onClick {
                    selectDunningCase(detailPanel, role, case.contributionId) { loadPage(true) }
                }
            },
        )
    }

    loadPage = { reset ->
        if (reset) {
            generation++
            loaded.clear()
            cursorDueDate = null
            cursorContributionId = null
            hasMore = false
            listPanel.removeAll()
            countsLabel.content = ""
            loadMoreButton.hide()
            statusRegion.showLoading()
            loading = true
            failed = false
        }
        val mine = generation
        val limit = limitSelect.value?.toIntOrNull() ?: 50
        val afterDueDate = cursorDueDate
        val afterContributionId = cursorContributionId
        val requestOnlyOpen = onlyOpenCheck.value
        loadMoreButton.disabled = true
        AppScope.launch {
            val cases =
                guarded {
                    rpcService<IDunningService>().listDunningCases(
                        onlyOpen = requestOnlyOpen,
                        limit = limit,
                        afterDueDate = afterDueDate,
                        afterContributionId = afterContributionId,
                    )
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat übernommen
            statusRegion.clearStatus()
            loading = false
            loadMoreButton.disabled = false
            if (cases == null) {
                loadMoreButton.hide()
                // Vorher endete ein gescheiterter Abruf in `return@launch` und hinterliess ein stumm
                // leeres Panel (der `errorBox`-Div darüber wurde nie befüllt -- toter Code).
                if (reset) {
                    failed = true
                    listPanel.dataErrorState(onRetry = { loadPage(true) })
                }
                return@launch
            }
            loaded += cases
            cases.lastOrNull()?.let { last ->
                cursorDueDate = last.dueDate
                cursorContributionId = last.contributionId
            }
            // Same size-vs-limit heuristic the previous version used to decide whether more rows
            // might exist -- exact (not `>=`), since `listDunningCases` never returns more than
            // `limit` rows itself.
            hasMore = cases.size == limit
            if (hasMore) loadMoreButton.show() else loadMoreButton.hide()
            renderList()
        }
    }
    refreshButton.onClick { loadPage(true) }
    loadMoreButton.onClick { loadPage(false) }
    // Der Arbeitsvorrat-Filter wirkt sofort (wie in `PaymentTransactionsScreen`); die Seitengröße bleibt
    // bewusst an `Aktualisieren` -- sie entscheidet, wie viel geholt wird, nicht was zu sehen ist.
    var isInitialOnlyOpenEvent = true
    onlyOpenCheck.subscribe {
        if (isInitialOnlyOpenEvent) {
            isInitialOnlyOpenEvent = false
            return@subscribe
        }
        loadPage(true)
    }

    var isInitialSearchEvent = true
    var debounceHandle: Int? = null
    searchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        debounceHandle?.let { window.clearTimeout(it) }
        debounceHandle =
            window.setTimeout({
                searchTerm = value.orEmpty()
                renderList()
            }, 300)
    }
    loadPage(true)
}

/** Clientseitige Suche über die geladene Teilmenge (pur, siehe `DunningCasesScreenTest`): Mitgliedsname. */
internal fun filterDunningCases(
    cases: List<DunningCaseDto>,
    search: String,
): List<DunningCaseDto> {
    val term = search.trim()
    if (term.isEmpty()) return cases
    return cases.filter { it.memberDisplayName.contains(term, ignoreCase = true) }
}

// ================================================================================================
// Warning bands -- band 1 (no active level) for every role that can see this screen (TREASURER/
// BOARD/ADMIN, `getDunningSettings` is a READ_ROLES method since `f30022c`), band 2 (stale
// disclaimer) ADMIN-only (`getDunningComplianceDisclaimer` remains `requireRole(ADMIN)`)
// ================================================================================================

private fun renderDunningWarningBands(
    root: SimplePanel,
    role: AccountRole?,
) {
    val bandHost = root.vPanel(spacing = 4)
    AppScope.launch {
        val settings = dunningProbe { rpcService<IDunningService>().getDunningSettings() } ?: return@launch
        val disclaimer =
            if (DunningAuthzUi.canAdminister(role)) {
                dunningProbe { rpcService<IDunningService>().getDunningComplianceDisclaimer() }
            } else {
                null
            }

        // Precedence band: `enableDunning` does not check `hasActiveLevel` (mirrors
        // `DunningService.kt:117-140`) -- "aktiviert" with zero configured levels is reachable and
        // silently means nothing is ever mahned. Its own band, not a footnote on the disclaimer one.
        if (DunningAuthzUi.showNoActiveLevelWarning(settings)) {
            val band = bandHost.div { addCssClasses("alert alert-warning") }
            band.div(
                tr("Das Mahnwesen ist aktiviert, aber keine Mahnstufe ist konfiguriert -- es wird nichts gemahnt."),
            ) { addCssClass("fw-bold") }
            if (DunningAuthzUi.canAdminister(role)) {
                val link = band.button(tr("Jetzt konfigurieren (Mahnwesen-Konfiguration)"), style = ButtonStyle.LINK)
                link.onClick { navigateTo(Routes.DUNNING_SETTINGS) }
            } else {
                band.div(
                    tr("Ein Administrator muss unter „Mahnwesen-Konfiguration\" mindestens eine Mahnstufe anlegen."),
                ) { addCssClasses("text-muted small") }
            }
        }

        if (DunningAuthzUi.showStaleDisclaimerWarning(role, settings, disclaimer)) {
            val band = bandHost.div { addCssClasses("alert alert-warning") }
            band.div(
                gettext(
                    "Der rechtliche Hinweistext für das Mahnwesen wurde seit der letzten Bestätigung (Version %1) " +
                        "auf Version %2 aktualisiert.",
                    settings.lastDisclaimerVersion ?: tr("keine"),
                    disclaimer?.version.orEmpty(),
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

/**
 * Spalten der Mahnvorgangs-Liste / Kartenliste; das Mitglied ist die Identität der Zeile. Bewusst keine
 * Sortierköpfe: die Liste ist eine Cursor-Chronologie nach Fälligkeitsdatum (Pflichtreihenfolge der
 * Mahnarbeit) und der Server kennt keinen Sortierparameter -- ein clientseitiges Umsortieren der
 * geladenen Teilmenge würde genau diese Arbeitsreihenfolge zerstören.
 */
private fun dunningCaseColumns(): List<DataColumn<DunningCaseDto>> =
    listOf(
        DataColumn(
            title = tr("Mitglied"),
            primary = true,
            cell = { container, case ->
                container.span(case.memberDisplayName)
                // Welle V1.4.4.5 -- kein Filter, keine Aktions-Sperre: wer einen Verstorbenen mahnt,
                // mahnt wissentlich (Zustellung an den Nachlass, siehe DunningCaseDto.memberStatus KDoc).
                if (case.memberStatus == MemberStatus.DECEASED) {
                    container.typeBadge(tr("Verstorben"), "dark")
                    container.span(tr("Zustellung an den Nachlass — offene Forderung besteht fort.")) {
                        addCssClasses("text-muted small d-block")
                    }
                }
            },
        ),
        textColumn(title = tr("Zeitraum"), numeric = true) { case: DunningCaseDto ->
            gettext("%1 – %2", case.periodStart, case.periodEnd)
        },
        DataColumn(
            title = tr("Betrag"),
            numeric = true,
            cell = { container, case -> container.moneySpan(case.amountDue) },
        ),
        textColumn(title = tr("Fällig am"), numeric = true) { case: DunningCaseDto -> case.dueDate.toString() },
        DataColumn(
            title = tr("Beitragsstatus"),
            cell = { container, case ->
                container.statusBadge(
                    contributionStatusLabel(case.contributionStatus),
                    contributionStatusColor(case.contributionStatus),
                )
            },
        ),
        DataColumn(
            title = tr("Stufe"),
            numeric = true,
            cell = { container, case ->
                container.div(case.highestLevelNumber?.toString() ?: "–")
                container.div(gettext("Zyklus %1", case.currentCycleNumber)) { addCssClasses("text-muted small") }
            },
        ),
        DataColumn(
            title = tr("Nächste Stufe"),
            numeric = true,
            cell = { container, case ->
                if (case.nextLevelNumber != null) {
                    container.div(gettext("Stufe %1", case.nextLevelNumber))
                    container.div(case.nextLevelDueOn?.toString() ?: "–") { addCssClasses("text-muted small") }
                } else {
                    container.div("–")
                }
            },
        ),
        DataColumn(
            title = tr("Gebühren gesamt"),
            numeric = true,
            cell = { container, case -> container.moneySpan(case.totalFeesCharged) },
        ),
    )

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
    // Welle V1.4.4.5 -- siehe renderDunningCaseRow's identischer Kommentar.
    if (case.memberStatus == MemberStatus.DECEASED) headerRow.typeBadge(tr("Verstorben"), "dark")
    headerRow.statusBadge(contributionStatusLabel(case.contributionStatus), contributionStatusColor(case.contributionStatus))
    panel.div(gettext("Zeitraum %1 – %2", case.periodStart, case.periodEnd)) { addCssClasses("text-muted small") }
    if (case.memberStatus == MemberStatus.DECEASED) {
        panel.div(tr("Zustellung an den Nachlass — offene Forderung besteht fort.")) { addCssClasses("text-muted small") }
    }
    val amountRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    amountRow.div(tr("Betrag:")) { addCssClasses("text-muted small") }
    amountRow.moneySpan(case.amountDue)
    panel.div(gettext("Fällig am %1", case.dueDate)) { addCssClasses("text-muted small") }

    if (detail.notices.isNotEmpty()) {
        panel.h2(tr("Mahnungen")) { addCssClass("h6") }
        panel.dataTable(
            columns = dunningNoticeColumns(role),
            rows = detail.notices,
            actions = { actions, notice -> actions.renderDunningNoticeActions(notice, role, onChanged) },
        )
    }

    if (DunningAuthzUi.canTreasuryAct(role)) {
        renderDunningCaseActionBar(panel, case, role, onChanged)
    }
}

/**
 * Spalten der Mahnungen-Tabelle im Detailbereich; die Mahnstufe ist die Identität der Zeile.
 * Die PDF-Spalte bleibt ein beschrifteter Download-Link (kein [tableActionButton]): `tableActionButton`
 * erzeugt einen `button`, ein Download braucht aber ein echtes `a[href]` mit `target="_blank"`. Dass ein
 * beschrifteter Link in einer Tabellenzelle die Zeilenhöhe treibt, ist eine bekannte Lücke (R38) und
 * gehört zusammen mit den übrigen Download-Links dieses Clients in eine eigene Welle.
 */
private fun dunningNoticeColumns(role: AccountRole?): List<DataColumn<DunningNoticeDto>> =
    listOf(
        textColumn(title = tr("Stufe"), numeric = true) { notice: DunningNoticeDto -> notice.levelNumber.toString() },
        textColumn(title = tr("Name"), primary = true) { notice: DunningNoticeDto -> notice.levelName },
        DataColumn(
            title = tr("Status"),
            cell = { container, notice ->
                container.statusBadge(dunningNoticeStatusLabel(notice.status), dunningNoticeStatusColor(notice.status))
            },
        ),
        textColumn(title = tr("Ausgestellt am"), numeric = true) { notice: DunningNoticeDto -> notice.issuedAt.toString() },
        textColumn(title = tr("Antwort bis"), numeric = true) { notice: DunningNoticeDto -> notice.respondBy.toString() },
        DataColumn(
            title = tr("Gebühr"),
            numeric = true,
            cell = { container, notice -> notice.feeAmount?.let { container.moneySpan(it) } ?: container.div("–") },
        ),
        DataColumn(
            title = tr("Postversand"),
            cell = { container, notice ->
                notice.postalDeliveryStatus?.let {
                    container.statusBadge(postalDeliveryStatusLabel(it), postalDeliveryStatusColor(it))
                } ?: container.div("–") { addCssClasses("text-muted small") }
            },
        ),
        DataColumn(
            title = tr("PDF"),
            cell = { container, notice ->
                if (DunningAuthzUi.canDownloadNoticePdf(role, notice.documentId)) {
                    container.link(tr("Herunterladen"), url = DunningHttp.noticePdfUrl(notice.id), target = "_blank") {
                        addCssClasses("btn btn-sm btn-outline-primary")
                    }
                } else {
                    container.div("–") { addCssClasses("text-muted small") }
                }
            },
        ),
        textColumn(title = tr("Stornogrund")) { notice: DunningNoticeDto -> notice.cancellationReason.orEmpty() },
    )

/** Zeilenaktion -- Rollen-/Statusprüfung und der Begründungs-Dialog sind gegenüber dem Bestand unverändert. */
private fun Container.renderDunningNoticeActions(
    notice: DunningNoticeDto,
    role: AccountRole?,
    onChanged: () -> Unit,
) {
    if (!DunningAuthzUi.canCancelNotice(role, notice.status)) return
    // `fa-rotate-left` (Rueckabwicklung), nicht `fa-ban`: eine Stornierung nimmt den
    // Mahnzyklus zurueck, sie sperrt nichts -- `fa-ban` ist in dieser Welle durchgehend fuer
    // "deaktivieren/widerrufen" reserviert.
    val cancelButton = tableActionButton("fas fa-rotate-left", tr("Stornieren"), ButtonStyle.OUTLINEDANGER)
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
