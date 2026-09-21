package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import io.kvision.core.Widget
import io.kvision.form.select.Select
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
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchInput
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewDto
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewItemDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemDto
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaReturnDto
import network.lapis.cloud.shared.domain.SepaReturnInput
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaReturnReasonSets
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.ISepaService
import kotlin.time.Clock

/**
 * V1.2.2 SEPA-Client-UI wave -- Plan §2.7/§4.3. Route-gated TREASURER/BOARD/ADMIN (see
 * `Routes.SEPA_BATCHES` KDoc); "Neuer Lauf" and every batch-lifecycle action are additionally
 * gated in-screen via [SepaAuthzUi.canTreasuryAct]/[SepaAuthzUi.nextBatchAction] (BOARD never sees
 * them, `previewDebitBatch`/`createDebitBatch`/... are TREASURER/ADMIN only).
 *
 * Plan §4.3 / O-1: the disclaimer-mismatch warning band is rendered ONLY for ADMIN --
 * `getSepaSettings`/`getSepaComplianceDisclaimer` are both ADMIN-only server-side, so a TREASURER
 * cannot compute "is the acknowledged disclaimer version current" at all without a backend change
 * (see the plan's open question O-1). For TREASURER/BOARD, [SEPA_WRITE_CONFLICT_MESSAGE] (surfaced
 * via [sepaGuarded] on the actual write attempt) is the substitute.
 */
fun renderSepaBatchesScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    val role = AppState.session?.role
    val canTreasuryAct = SepaAuthzUi.canTreasuryAct(role)

    if (AppState.hasRole(AccountRole.ADMIN)) {
        renderAdminDisclaimerWarningBand(root)
    }
    root.h1(tr("SEPA-Lastschrift"))

    root.h2(tr("Läufe")) { addCssClass("h5") }
    val statusRegion = root.dataStatusRegion()
    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Lauf oben auswählen, um Details zu sehen.")) { addCssClasses("text-muted small") }

    var lastCreatedAt: LocalDateTime? = null
    var hasMore = false
    var generation = 0
    val loaded = mutableListOf<SepaDebitBatchDto>()
    var loading = false
    // Fresh per screen instance -- see [SelectedBatchState] KDoc.
    val batchState = SelectedBatchState()

    // Defined before `showDetail`/`selectBatch` below, purely so those closures can reference it --
    // Kotlin local functions, unlike top-level ones, are not forward-referenceable within the same
    // block.
    lateinit var loadBatches: (Boolean) -> Unit

    fun renderBatchList() {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): solange der erste Ladevorgang läuft, sagt allein
        // die Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld während
        // des Ladens den Leertext über den Ladehinweis -- „lädt" und „keine Daten" gleichzeitig.
        if (loading && loaded.isEmpty()) return
        listPanel.removeAll()
        if (loaded.isEmpty()) {
            countsLabel.content = ""
            listPanel.p(tr("Noch kein SEPA-Lauf angelegt.")) { addCssClasses("text-muted") }
            return
        }
        countsLabel.content = dataCountText(shown = loaded.size, loaded = loaded.size, hasMore = hasMore)
        listPanel.dataTable(
            columns = sepaBatchColumns(),
            rows = loaded,
            actions = { actions, batch ->
                // Design-Team-Welle 2026-09-18: Icon-Knopf in der Aktionsspalte. Die "Stornieren"-Aktion
                // weiter unten im DETAILPANEL behaelt bewusst ihren Volltext-Knopf -- sie steht nicht in
                // einem dichten Raster, sondern allein in einer Aktionszeile, und eine irreversible
                // Stornierung soll dort ihren Namen tragen (Norman/Raskin-Linie der Sitzung).
                val showButton = actions.tableActionButton("fas fa-eye", tr("Details anzeigen"))
                showButton.onClick {
                    selectBatch(detailPanel, role, batchState, batch.id) { loadBatches(true) }
                }
            },
        )
    }

    loadBatches = { reset ->
        if (reset) {
            generation++
            loaded.clear()
            lastCreatedAt = null
            hasMore = false
            listPanel.removeAll()
            countsLabel.content = ""
            loadMoreButton.hide()
            statusRegion.showLoading()
            loading = true
        }
        val mine = generation
        val cursor = if (reset) null else lastCreatedAt
        loadMoreButton.disabled = true
        AppScope.launch {
            val batches =
                sepaGuarded(tr(SEPA_READ_CONFLICT_MESSAGE)) {
                    rpcService<ISepaService>().listBatches(beforeCreatedAt = cursor)
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat übernommen
            statusRegion.clearStatus()
            loading = false
            loadMoreButton.disabled = false
            if (batches == null) {
                loadMoreButton.hide()
                if (reset) listPanel.dataErrorState(onRetry = { loadBatches(true) })
                return@launch
            }
            loaded += batches
            batches.lastOrNull()?.let { lastCreatedAt = it.createdAt }
            hasMore = batches.size >= SEPA_BATCHES_PAGE_SIZE
            if (hasMore) loadMoreButton.show() else loadMoreButton.hide()
            renderBatchList()
        }
    }

    if (canTreasuryAct) {
        renderNewBatchSection(root) { loadBatches(true) }
    }

    loadMoreButton.onClick { loadBatches(false) }
    loadBatches(true)

    renderSepaReturnsSection(root, canTreasuryAct)
}

private const val SEPA_BATCHES_PAGE_SIZE = 50

/**
 * S-5: `getBatch()` always returns an empty `failedItemIds`; only `settleBatch`'s OWN response
 * ever carries real ones. [SelectedBatchState] folds the freshest non-empty set into a per-batch
 * cache so a later, unrelated refresh (e.g. re-selecting the same batch after "Ankündigen") does
 * not silently lose it -- reset the moment a DIFFERENT batch is selected.
 *
 * `internal`, not `private` (Review Round 2, 2026-08-20, NIT): so [SelectedBatchStateTest] can cover
 * it directly -- before this fix, nothing exercised this class at all, and a test would have caught
 * the MAJOR finding that [showDetail] never actually fed it a non-empty `failedItemIds` (see that
 * function's own KDoc).
 *
 * [apply]'s `fromSettle` parameter (Review Round 2, 2026-08-20, MINOR fix) distinguishes the two
 * callers that used to be folded through the same "non-empty wins" rule: a `settleBatch` response
 * IS the authoritative failure set for that attempt, including when it is empty (a fully successful
 * retry) -- it must always overwrite the cache. A `getBatch()` refetch's ALWAYS-empty
 * `failedItemIds` carries no information at all and must never overwrite a real cached failure.
 * Without this distinction, a successful retry after a failed `settleBatch` call left the stale
 * failure banner and per-item markers on screen even though the batch had already moved to
 * `SETTLED`.
 */
internal class SelectedBatchState {
    var batchId: String? = null
    var lastSettleFailedIds: List<String> = emptyList()

    fun apply(
        detail: SepaDebitBatchDetailDto,
        fromSettle: Boolean = false,
    ): SepaDebitBatchDetailDto {
        if (detail.batch.id != batchId) {
            batchId = detail.batch.id
            lastSettleFailedIds = emptyList()
        }
        val effective =
            when {
                // The settle response is authoritative for its own attempt -- even an empty list
                // (a fully successful settle/retry) must overwrite whatever failed before.
                fromSettle -> detail.failedItemIds
                detail.failedItemIds.isNotEmpty() -> detail.failedItemIds
                else -> lastSettleFailedIds
            }
        lastSettleFailedIds = effective
        return detail.copy(failedItemIds = effective)
    }
}

private fun showDetail(
    detailPanel: SimplePanel,
    role: AccountRole?,
    batchState: SelectedBatchState,
    detail: SepaDebitBatchDetailDto,
    onChanged: () -> Unit,
    fromSettle: Boolean = false,
) {
    detailPanel.removeAll()
    renderSepaBatchDetail(
        container = detailPanel,
        detail = batchState.apply(detail, fromSettle),
        role = role,
        onChanged = {
            AppScope.launch {
                val refreshed = guarded { rpcService<ISepaService>().getBatch(detail.batch.id) } ?: return@launch
                showDetail(detailPanel, role, batchState, refreshed, onChanged)
                onChanged()
            }
        },
        onSettled = { settledDetail ->
            // MAJOR (Review Round 2, 2026-08-20): feed `settleBatch`'s OWN response straight back
            // into `showDetail`/`SelectedBatchState.apply` instead of discarding it and re-fetching
            // via `getBatch()` (which -- per `SepaDebitBatchDetailDto.failedItemIds` KDoc -- ALWAYS
            // returns an empty list). Before this fix, `SelectedBatchState.lastSettleFailedIds` never
            // saw a non-empty value at all, so the "N Positionen konnten nicht gebucht werden"
            // banner and the per-item "fehlgeschlagen" marker were dead code: a treasurer saw the
            // failure toast, but the re-rendered detail view underneath it showed no failed items
            // whatsoever.
            //
            // `fromSettle = true` (MINOR fix, Review Round 2, 2026-08-20): this response is
            // authoritative for the attempt that just happened, so it must overwrite the cache even
            // when `settledDetail.failedItemIds` is empty -- otherwise a fully successful retry
            // after an earlier partial failure kept showing the stale failure banner/markers even
            // though the batch had already moved to SETTLED.
            showDetail(detailPanel, role, batchState, settledDetail, onChanged, fromSettle = true)
            onChanged()
        },
    )
}

private fun selectBatch(
    detailPanel: SimplePanel,
    role: AccountRole?,
    batchState: SelectedBatchState,
    batchId: String,
    onChanged: () -> Unit,
) {
    AppScope.launch {
        val detail = guarded { rpcService<ISepaService>().getBatch(batchId) } ?: return@launch
        showDetail(detailPanel, role, batchState, detail, onChanged)
    }
}

/** `internal`, not `private` (Client-UI wave for GitHub Issue #5): shared with
 * `DunningCasesScreen.kt`'s own "today" comparisons (`DunningAuthzUi.nextCaseAction`), so a second
 * copy of this exact computation does not need to exist in this client. */
internal fun todayLocalDate(): LocalDate =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date

// ================================================================================================
// ADMIN-only disclaimer-mismatch warning band (K2, Plan §4.3/O-1)
// ================================================================================================

private fun renderAdminDisclaimerWarningBand(root: SimplePanel) {
    val bandHost = root.vPanel(spacing = 4)
    AppScope.launch {
        val settings = sepaProbe { rpcService<ISepaService>().getSepaSettings() } ?: return@launch
        val disclaimer = sepaProbe { rpcService<ISepaService>().getSepaComplianceDisclaimer() } ?: return@launch
        if (settings.sepaDebitEnabled && settings.lastDisclaimerVersion != disclaimer.version) {
            val band = bandHost.div { addCssClasses("alert alert-warning") }
            band.div(
                gettext(
                    "Der rechtliche Hinweistext für SEPA-Lastschrift wurde seit der letzten Bestätigung " +
                        "(Version %1) auf Version %2 aktualisiert.",
                    settings.lastDisclaimerVersion,
                    disclaimer.version,
                ),
            ) { addCssClass("fw-bold") }
            val link = band.button(tr("Erneut bestätigen (SEPA-Konfiguration)"), style = ButtonStyle.LINK)
            link.onClick { navigateTo(Routes.SEPA_SETTINGS) }
        }
    }
}

// ================================================================================================
// Neuer Lauf (K7: preview -> create, label always carries the current preview numbers)
// ================================================================================================

internal fun renderNewBatchSection(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    root.h2(tr("Neuer Lauf")) { addCssClass("h5") }
    val form = root.lapisForm()
    // MAJOR (Review Round 2, 2026-08-20): defaulting this to TODAY made the form's own standard
    // path fail every time -- `createDebitBatch` (SepaService.kt:552) rejects any
    // `requestedCollectionDate <= today` outright, but `previewDebitBatch` does NOT check the date
    // at all, so "Vorschau berechnen" would succeed with a real item count/total, inviting a click
    // on "Lauf anlegen" that was then guaranteed to fail. Defaulting to tomorrow is still not a
    // guarantee of success (the real floor is the notice period), but it at least clears the
    // unconditional server-side rejection every prior default hit.
    // `createDebitBatch` lehnt `requestedCollectionDate <= today` ab, `previewDebitBatch` prüft das Datum gar nicht: die Regel steht
    // deshalb am Einzugsdatum-Feld, damit schon die Vorschau nicht mit einem Datum gelingt, mit dem "Lauf anlegen" nie gelingen kann.
    val collectionDateField =
        form.textField(
            label = tr("Einzugsdatum"),
            value = todayLocalDate().plus(1, DateTimeUnit.DAY).toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            rule = { value ->
                val check = FormRules.isoDate(value)
                when {
                    check is FieldCheck.Invalid -> check
                    LocalDate.parse(
                        value.trim(),
                    ) <= todayLocalDate() -> FieldCheck.Invalid(gettext("Das Einzugsdatum muss in der Zukunft liegen."))
                    else -> FieldCheck.Ok
                }
            },
        )
    val dueOnOrBeforeField =
        form.textField(
            label = tr("Fällig bis"),
            value = todayLocalDate().toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            rule = { FormRules.isoDate(it) },
        )
    val tierField =
        form.selectField(
            label = tr("Beitragssatz"),
            options = emptyList(),
            value = null,
            hint = tr("Leer = alle Beitragssätze."),
        )
    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: return@launch
        (tierField.control as Select).options = listOf("" to tr("Alle Beitragssätze")) + tiers.map { it.id to it.name }
        tierField.setValue("")
    }
    val previewButton = Button(tr("Vorschau berechnen"), style = ButtonStyle.OUTLINEPRIMARY)
    form.buttons(primary = previewButton)
    val previewPanel = form.panel.vPanel(spacing = 4)
    val createButtonHost = form.panel.vPanel(spacing = 4)

    fun buildInput(): SepaDebitBatchInput? {
        val collectionDate = runCatching { LocalDate.parse(collectionDateField.value.trim()) }.getOrNull() ?: return null
        val dueOnOrBefore = runCatching { LocalDate.parse(dueOnOrBeforeField.value.trim()) }.getOrNull() ?: return null
        val tierId = tierField.value.takeIf { it.isNotBlank() }
        return SepaDebitBatchInput(requestedCollectionDate = collectionDate, dueOnOrBefore = dueOnOrBefore, membershipTierId = tierId)
    }

    fun resetPreview() {
        previewPanel.removeAll()
        createButtonHost.removeAll()
    }
    collectionDateField.subscribe { resetPreview() }
    dueOnOrBeforeField.subscribe { resetPreview() }
    tierField.subscribe { resetPreview() }

    previewButton.onClick {
        form.submit(previewButton) {
            val input = buildInput() ?: return@submit
            val preview = sepaGuarded(tr(SEPA_WRITE_CONFLICT_MESSAGE)) { rpcService<ISepaService>().previewDebitBatch(input) }
            if (preview != null) {
                renderBatchPreview(previewPanel, preview)
                createButtonHost.removeAll()
                val createButton =
                    createButtonHost.button(
                        gettext("Lauf anlegen (%1 Positionen, %2)", preview.itemCount, formatMoney(preview.totalAmount)),
                        style = ButtonStyle.PRIMARY,
                    )
                // Fachlicher Zustand, NICHT der Doppelklickschutz: ein Lauf ohne Positionen wird nicht angelegt. Ein gesperrter Knopf
                // erreicht `runBusy` nie, dessen `finally` ihn also auch nie versehentlich entsperrt.
                createButton.disabled = preview.itemCount == 0
                createButton.onClick {
                    if (!form.validateAndReport()) return@onClick
                    val currentInput = buildInput() ?: return@onClick
                    form.runBusy(createButton) {
                        val created =
                            sepaGuarded(
                                tr(SEPA_BATCH_CREATE_CONFLICT_MESSAGE),
                            ) { rpcService<ISepaService>().createDebitBatch(currentInput) }
                        if (created != null) notifySuccess(tr("Lauf angelegt."))
                        // Erfolg wie Fehlschlag: die Vorschau (und mit ihr die Anzahl/Summe im Knopftext) gilt nicht mehr. Nach einem
                        // Fehlschlag (Konflikt, geänderte Bestandslage) stand sonst der alte Knopf mit den alten Zahlen bereit.
                        resetPreview()
                        if (created != null) onCreated()
                    }
                }
            }
        }
    }
}

private fun renderBatchPreview(
    panel: SimplePanel,
    preview: SepaDebitBatchPreviewDto,
) {
    panel.removeAll()
    if (preview.items.isEmpty()) {
        panel.p(tr("Keine fälligen Beiträge mit aktivem Mandat gefunden.")) { addCssClasses("text-muted small") }
    } else {
        panel.dataTable(
            columns =
                listOf(
                    textColumn(title = tr("Mitglied"), primary = true) { item: SepaDebitBatchPreviewItemDto ->
                        item.memberDisplayName
                    },
                    DataColumn(
                        title = tr("Betrag"),
                        numeric = true,
                        cell = { container, item -> container.moneySpan(item.amount) },
                    ),
                    textColumn(title = tr("Mandatsreferenz")) { item: SepaDebitBatchPreviewItemDto -> item.mandateReference },
                    textColumn(title = tr("IBAN"), numeric = true) { item: SepaDebitBatchPreviewItemDto ->
                        formatIbanLast4(item.debtorIbanLast4)
                    },
                    textColumn(title = tr("Erhöht?")) { item: SepaDebitBatchPreviewItemDto ->
                        if (item.amountIncreased) tr("Ja") else tr("Nein")
                    },
                ),
            rows = preview.items,
        )
    }
    if (preview.excluded.isNotEmpty()) {
        panel.div(tr("Ausgeschlossen:")) { addCssClasses("text-muted small mt-1") }
        preview.excluded.forEach { exclusion ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center small") }
            row.div(exclusion.memberDisplayName) { addCssClasses("flex-grow-1") }
            row.typeBadge(sepaExclusionReasonLabel(exclusion.reason), sepaExclusionReasonColor(exclusion.reason))
        }
    }
}

// ================================================================================================
// Läufe-Liste (Zeile)
// ================================================================================================

/**
 * Spalten der Läufe-Liste / Kartenliste. Das Erstellungsdatum ist die Identität eines Laufes (es gibt
 * keinen Namen), bleibt aber linksbündig -- ein Datum, das eine Zeile identifiziert, ist kein Messwert
 * (Richtlinie K8, gleiche Entscheidung wie „Zeitraum" in `ContributionsScreen`). Keine Sortierköpfe: die
 * Liste ist eine Cursor-Chronologie, neueste zuerst.
 */
private fun sepaBatchColumns(): List<DataColumn<SepaDebitBatchDto>> =
    listOf(
        textColumn(title = tr("Erstellt am"), primary = true) { batch: SepaDebitBatchDto -> batch.createdAt.toString() },
        textColumn(title = tr("Fälligkeit"), numeric = true) { batch: SepaDebitBatchDto ->
            batch.requestedCollectionDate.toString()
        },
        DataColumn(
            title = tr("Sequenztyp"),
            cell = { container, batch ->
                container.typeBadge(sepaSequenceTypeLabel(batch.sequenceType), sepaSequenceTypeColor(batch.sequenceType))
            },
        ),
        DataColumn(
            title = tr("Status"),
            cell = { container, batch ->
                container.statusBadge(sepaBatchStatusLabel(batch.status), sepaBatchStatusColor(batch.status))
            },
        ),
        textColumn(title = tr("Positionen"), numeric = true) { batch: SepaDebitBatchDto -> batch.itemCount.toString() },
        DataColumn(
            title = tr("Summe"),
            numeric = true,
            cell = { container, batch -> container.moneySpan(batch.totalAmount) },
        ),
    )

// ================================================================================================
// Detailpanel (mount-agnostisch, s. Plan §2.7/K6/K7)
// ================================================================================================

/**
 * Plan §2.7 -- mount-agnostic: takes everything it needs as parameters, never reaches into
 * screen-level state itself. K6: `div { addCssClasses("lapis-surface border rounded p-3") }`, no
 * KVision `card()`.
 */
internal fun renderSepaBatchDetail(
    container: SimplePanel,
    detail: SepaDebitBatchDetailDto,
    role: AccountRole?,
    onChanged: () -> Unit,
    onSettled: (SepaDebitBatchDetailDto) -> Unit,
) {
    val batch = detail.batch
    val surface = container.div { addCssClasses("lapis-surface border rounded p-3") }
    val panel = surface.vPanel(spacing = 8)

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.statusBadge(sepaBatchStatusLabel(batch.status), sepaBatchStatusColor(batch.status))
    headerRow.typeBadge(sepaSequenceTypeLabel(batch.sequenceType), sepaSequenceTypeColor(batch.sequenceType))
    headerRow.div(gettext("Einzugsdatum %1", batch.requestedCollectionDate)) { addCssClasses("text-muted small") }

    // Zustandsleiste: fünf Stationen, aktive fett, erledigte grau, getrennt durch "→".
    val stages =
        listOf(
            SepaDebitBatchStatus.DRAFT to tr("Entwurf"),
            SepaDebitBatchStatus.NOTIFIED to tr("Angekündigt"),
            SepaDebitBatchStatus.GENERATED to tr("Datei erzeugt"),
            SepaDebitBatchStatus.SUBMITTED to tr("Eingereicht"),
            SepaDebitBatchStatus.SETTLED to tr("Abgerechnet"),
        )
    if (batch.status != SepaDebitBatchStatus.CANCELLED) {
        val stageOrder = stages.map { it.first }
        val currentIndex = stageOrder.indexOf(batch.status)
        val stageRow = panel.hPanel(spacing = 6) { addCssClasses("flex-wrap align-items-center small") }
        stages.forEachIndexed { index, (status, label) ->
            stageRow.div(label) { addCssClass(if (index <= currentIndex) "fw-bold" else "text-muted") }
            if (index != stages.lastIndex) stageRow.div("→") { addCssClasses("text-muted") }
        }
    } else {
        panel.div(gettext("Storniert: %1", batch.cancellationReason.orEmpty())) { addCssClasses("text-danger fw-bold") }
    }

    batch.requiredNoticeDays?.let { panel.div(gettext("Ankündigungsfrist: %1 Tage", it)) { addCssClasses("text-muted small") } }
    batch.fileGenerationAllowedFrom?.let {
        panel.div(gettext("Datei erzeugbar ab: %1", it)) { addCssClasses("text-muted small") }
    }
    batch.submittedNote?.let { panel.div(gettext("Notiz: %1", it)) { addCssClasses("text-muted small") } }

    // ── Aktionen ────────────────────────────────────────────────────────────────────────────────
    val actionsRow = panel.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center") }
    val hasSettleableItems = detail.items.any { it.status == SepaDebitItemStatus.SETTLEABLE }
    val nextAction =
        SepaAuthzUi.nextBatchAction(role, batch.status, batch.requestedCollectionDate, batch.fileGenerationAllowedFrom, hasSettleableItems)
    renderBatchActionButton(actionsRow, batch, nextAction, onChanged, onSettled)

    if (SepaAuthzUi.canTreasuryAct(role) &&
        batch.status in setOf(SepaDebitBatchStatus.DRAFT, SepaDebitBatchStatus.NOTIFIED, SepaDebitBatchStatus.GENERATED)
    ) {
        val cancelButton = actionsRow.button(tr("Stornieren"), style = ButtonStyle.OUTLINEDANGER)
        cancelButton.onClick {
            confirmWithReasonDialog(
                title = tr("Lauf stornieren"),
                message = tr("Dieser Lastschriftlauf wird storniert und kann nicht fortgesetzt werden."),
                dangerNote = tr("Stornieren kann nicht rückgängig gemacht werden."),
                reasonLabel = tr("Grund"),
                reasonRequired = true,
                confirmLabel = tr("Stornieren"),
            ) { reason ->
                runGuardedAction(cancelButton) {
                    val result =
                        sepaGuarded(tr(SEPA_WRITE_CONFLICT_MESSAGE)) {
                            rpcService<ISepaService>().cancelBatch(batch.id, reason.orEmpty())
                        }
                    if (result != null) {
                        notifySuccess(tr("Lauf storniert."))
                        onChanged()
                    }
                }
            }
        }
    }

    if (SepaAuthzUi.canDownloadBatchFile(role, batch.status, batch.generatedDocumentId)) {
        // S-6: target = "_blank" is NOT optional -- Link.useDataNavigoForLinks is set globally
        // (App.kt main()), a download anchor without it would be hijacked as an SPA route.
        actionsRow.link(tr("pain.008 herunterladen"), url = SepaHttp.batchFileUrl(batch.id), target = "_blank") {
            addCssClasses("btn btn-sm btn-outline-primary")
        }
    }

    if (detail.failedItemIds.isNotEmpty()) {
        panel.div(
            gettext("%1 Positionen konnten nicht gebucht werden -- erneut versuchen mit \"Abrechnen\".", detail.failedItemIds.size),
        ) { addCssClasses("text-danger small") }
    }

    // ── Positionen ──────────────────────────────────────────────────────────────────────────────
    if (detail.items.isNotEmpty()) {
        panel.h2(tr("Positionen")) { addCssClass("h6") }
        panel.dataTable(columns = sepaItemColumns(detail.failedItemIds), rows = detail.items)
    }
}

private fun renderBatchActionButton(
    row: SimplePanel,
    batch: SepaDebitBatchDto,
    action: SepaBatchAction?,
    onChanged: () -> Unit,
    onSettled: (SepaDebitBatchDetailDto) -> Unit,
) {
    when (action) {
        null -> Unit
        SepaBatchAction.NOTIFY -> {
            val button = row.button(tr("Ankündigen"), style = ButtonStyle.PRIMARY)
            button.onClick { runBatchAction(button, onChanged) { rpcService<ISepaService>().notifyBatch(batch.id) } }
        }
        SepaBatchAction.GENERATE_FILE -> {
            val button = row.button(tr("Datei erzeugen"), style = ButtonStyle.PRIMARY)
            button.onClick {
                runBatchAction(button, onChanged, tr(SEPA_GENERATE_FILE_CONFLICT_MESSAGE)) {
                    rpcService<ISepaService>().generateBatchFile(batch.id)
                }
            }
        }
        // K12/S-13: the ONE deliberately-disabled-instead-of-hidden action in this whole wave.
        //
        // Review Round 2 (2026-08-20, MAJOR): reworded away from "ab %1" ("available FROM this
        // date") -- since both `requestedCollectionDate` and `fileGenerationAllowedFrom` are fixed
        // once a batch is NOTIFIED (see `SepaAuthzUi.nextBatchAction` KDoc), a batch that lands here
        // does NOT necessarily become generatable once that date passes; it may be PERMANENTLY
        // blocked (`requestedCollectionDate < fileGenerationAllowedFrom` never changes). The label
        // now states both dates so a treasurer can judge for themselves whether waiting will help,
        // rather than being told a specific date implies the button will self-enable.
        SepaBatchAction.GENERATE_FILE_TOO_EARLY -> {
            val label =
                batch.fileGenerationAllowedFrom?.let {
                    gettext(
                        "Datei erzeugen -- Vorabankündigungsfrist erst ab %1 gewahrt (Einzugsdatum %2)",
                        it,
                        batch.requestedCollectionDate,
                    )
                } ?: tr("Datei erzeugen -- noch nicht möglich")
            row.button(label, style = ButtonStyle.OUTLINESECONDARY).disabled = true
        }
        SepaBatchAction.MARK_SUBMITTED -> {
            val button = row.button(tr("Als eingereicht markieren"), style = ButtonStyle.PRIMARY)
            button.onClick {
                confirmWithReasonDialog(
                    title = tr("Als eingereicht markieren"),
                    message = tr("Bestätigen Sie, dass diese Datei bei der Bank eingereicht wurde."),
                    reasonLabel = tr("Notiz"),
                    reasonRequired = false,
                    confirmLabel = tr("Als eingereicht markieren"),
                ) { note ->
                    runBatchAction(button, onChanged) { rpcService<ISepaService>().markBatchSubmitted(batch.id, note) }
                }
            }
        }
        SepaBatchAction.SETTLE -> {
            val button = row.button(tr("Abrechnen"), style = ButtonStyle.SUCCESS)
            button.onClick {
                runGuardedAction(button) {
                    val result = sepaGuarded(tr(SEPA_WRITE_CONFLICT_MESSAGE)) { rpcService<ISepaService>().settleBatch(batch.id) }
                    if (result != null) {
                        if (result.failedItemIds.isEmpty()) {
                            notifySuccess(tr("Lauf abgerechnet."))
                        } else {
                            notifyError(gettext("%1 Positionen konnten nicht gebucht werden.", result.failedItemIds.size))
                        }
                        // MAJOR fix (see [showDetail] KDoc): pass the RESULT itself, never just
                        // `onChanged()` -- `onChanged()` alone would re-fetch via `getBatch()`,
                        // which always reports an empty `failedItemIds`.
                        onSettled(result)
                    }
                }
            }
        }
    }
}

private fun runBatchAction(
    button: Button,
    onChanged: () -> Unit,
    conflictMessage: String = tr(SEPA_WRITE_CONFLICT_MESSAGE),
    call: suspend () -> SepaDebitBatchDto,
) {
    runGuardedAction(button) {
        val result = sepaGuarded(conflictMessage) { call() }
        if (result != null) {
            notifySuccess(tr("Gespeichert."))
            onChanged()
        }
    }
}

/** Spalten der Positionen-Tabelle im Detailbereich; das Mitglied ist die Identität der Zeile. */
private fun sepaItemColumns(failedItemIds: List<String>): List<DataColumn<SepaDebitItemDto>> =
    listOf(
        textColumn(title = tr("Mitglied"), primary = true) { item: SepaDebitItemDto -> item.memberDisplayName },
        DataColumn(
            title = tr("Betrag"),
            numeric = true,
            cell = { container, item -> container.moneySpan(item.amount) },
        ),
        textColumn(title = tr("Mandatsreferenz")) { item: SepaDebitItemDto -> item.mandateReference },
        textColumn(title = tr("IBAN"), numeric = true) { item: SepaDebitItemDto -> formatIbanLast4(item.debtorIbanLast4) },
        DataColumn(
            title = tr("Status"),
            cell = { container, item ->
                container.statusBadge(sepaItemStatusLabel(item.status), sepaItemStatusColor(item.status))
                if (item.id in failedItemIds) {
                    container.div(tr("fehlgeschlagen")) { addCssClasses("text-danger small") }
                }
            },
        ),
    )

// ================================================================================================
// Rücklastschriften (Plan §4.3)
// ================================================================================================

internal fun renderSepaReturnsSection(
    root: SimplePanel,
    canRecordReturn: Boolean,
) {
    root.h2(tr("Rücklastschriften")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val fromInput = filterRow.text(label = tr("Von"))
    val toInput = filterRow.text(label = tr("Bis"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)
    // Das Datumsformat steht im Hinweis, nie im Label (W4c). Hinweis und Fehler hängen per `aria-describedby` an beiden Feldern; ein
    // nicht lesbares Datum ("13.03.2026") ist ein Feldfehler -- vorher lief der Abruf still OHNE Filter, die volle Liste stand da und
    // bei einer leeren Antwort behauptete die Leermeldung "Keine Rücklastschrift im gewählten Zeitraum".
    val filterHintId = "sepa-returns-filter-hint"
    val filterErrorId = "sepa-returns-filter-error"
    root.div(tr("Beispiel: 2026-03-14.")) {
        addCssClasses("text-muted small")
        id = filterHintId
    }
    val filterError =
        root.div {
            addCssClasses("invalid-feedback lapis-field-error")
            id = filterErrorId
        }
    listOf(fromInput, toInput).forEach { input ->
        (input.input as? Widget)?.let {
            it.setAttribute("aria-describedby", "$filterHintId $filterErrorId")
            it.setAttribute("aria-invalid", "false")
        }
    }
    val returnsStatus = root.dataStatusRegion()
    val returnsPanel = root.vPanel(spacing = 6)

    // Welle V1.4.26 (W2): eigener Abruf mit Generation-Guard (analog `DataLoadController`) -- Lade-, Fehler- und zwei
    // getrennte Leerzustände. Der Zeitraum ist ein echter Filter des Lesers, deshalb ist eine leere
    // Antwort mit gesetztem Datum „nichts gefunden", ohne Datum „noch keine Rücklastschriften".
    // Generation-Zähler: bei zwei schnellen Klicks auf `Filtern` (oder Filtern gleichzeitig mit dem
    // Erfassen einer Rücklastschrift) darf die ältere Antwort die des neueren Zeitraums nicht überschreiben.
    var returnsGeneration = 0

    fun loadReturns() {
        val fromRaw = fromInput.value.orEmpty().trim()
        val toRaw = toInput.value.orEmpty().trim()
        val from = runCatching { LocalDate.parse(fromRaw) }.getOrNull()
        val to = runCatching { LocalDate.parse(toRaw) }.getOrNull()
        val badInputs =
            listOfNotNull(
                fromInput.takeIf { fromRaw.isNotEmpty() && from == null },
                toInput.takeIf {
                    toRaw.isNotEmpty() &&
                        to == null
                },
            )
        listOf(fromInput, toInput).forEach { input ->
            val invalid = input in badInputs
            if (invalid) input.addCssClass("is-invalid") else input.removeCssClass("is-invalid")
            (input.input as? Widget)?.setAttribute("aria-invalid", invalid.toString())
        }
        if (badInputs.isNotEmpty()) {
            filterError.content = gettext("Bitte ein gültiges Datum angeben.")
            filterError.addCssClass("lapis-field-error--shown")
            return // KEIN Abruf: eine unlesbare Eingabe ist kein Filter
        }
        filterError.content = null
        filterError.removeCssClass("lapis-field-error--shown")
        returnsGeneration++
        val mine = returnsGeneration
        returnsPanel.removeAll()
        returnsStatus.showLoading()
        AppScope.launch {
            val returns = sepaGuarded(tr(SEPA_READ_CONFLICT_MESSAGE)) { rpcService<ISepaService>().listReturns(from, to) }
            if (mine != returnsGeneration) return@launch // ein neuerer Abruf hat übernommen
            returnsStatus.clearStatus()
            returnsPanel.removeAll()
            if (returns == null) {
                returnsPanel.dataErrorState(onRetry = { loadReturns() })
                return@launch
            }
            if (returns.isEmpty()) {
                val hasRange = fromRaw.isNotEmpty() || toRaw.isNotEmpty()
                val text =
                    if (hasRange) {
                        tr("Keine Rücklastschrift im gewählten Zeitraum.")
                    } else {
                        tr("Noch keine Rücklastschriften erfasst.")
                    }
                returnsPanel.p(text) { addCssClasses("text-muted") }
                return@launch
            }
            returnsPanel.dataTable(columns = sepaReturnColumns(), rows = returns)
        }
    }
    filterButton.onClick { loadReturns() }
    loadReturns()

    if (canRecordReturn) {
        root.h2(tr("Rücklastschrift erfassen")) { addCssClass("h6") }
        renderRecordReturnForm(root) { loadReturns() }
    }
}

/** Spalten der Rücklastschriften-Tabelle / Kartenliste; das Mitglied ist die Identität der Zeile. */
private fun sepaReturnColumns(): List<DataColumn<SepaReturnDto>> =
    listOf(
        textColumn(title = tr("Mitglied"), primary = true) { sepaReturn: SepaReturnDto -> sepaReturn.memberDisplayName },
        textColumn(title = tr("Datum"), numeric = true) { sepaReturn: SepaReturnDto -> sepaReturn.returnedAt.toString() },
        DataColumn(
            title = tr("Grund"),
            cell = { container, sepaReturn -> container.sepaReturnReasonBadge(sepaReturn.reasonCode) },
        ),
        DataColumn(
            title = tr("Gebühr"),
            numeric = true,
            cell = { container, sepaReturn ->
                sepaReturn.returnFee?.let { container.moneySpan(it) } ?: container.span("–")
            },
        ),
        textColumn(title = tr("Mandat widerrufen")) { sepaReturn: SepaReturnDto ->
            if (sepaReturn.mandateRevoked) tr("Ja") else tr("Nein")
        },
    )

internal fun renderRecordReturnForm(
    root: SimplePanel,
    onRecorded: () -> Unit,
) {
    val form = root.lapisForm()
    val batchField = form.selectField(label = tr("Lauf"), options = emptyList(), value = null, required = true)
    val itemPlaceholder = listOf("" to tr("-- Position wählen --"))
    val itemField =
        form.selectField(
            label = tr("Position"),
            options = itemPlaceholder,
            value = "",
            required = true,
            requiredMessage = tr("Bitte eine Position auswählen."),
        )
    val returnedAtField =
        form.textField(
            label = tr("Rücklastschrift-Datum"),
            value = todayLocalDate().toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            rule = { FormRules.isoDate(it) },
        )
    val reasonOptions = SepaReturnReason.entries.map { it.name to sepaReturnReasonLabel(it) }
    val reasonField =
        form.selectField(
            label = tr("Grund"),
            options = reasonOptions,
            value = reasonOptions.first().first,
            required = true,
            requiredMessage = tr("Bitte einen Grund auswählen."),
        )
    val revocationNote =
        form.panel.div().apply {
            addCssClasses("text-danger small")
            content = tr("Dieser Grund führt automatisch zum Widerruf des Mandats.")
            hide()
        }
    // Der Freitext ist Pflicht NUR bei "Sonstiger Grund" -- eine Regel, die Pflicht wird nicht gelockert. Ein blanker Wert erreicht
    // eine Feldregel nie (die Grammatik behandelt Leere selbst), deshalb steht die Bedingung als Querregel in der Sammelfläche und
    // der Hinweis am Feld nennt sie.
    val reasonTextField =
        form.textField(label = tr("Freitext"), hint = tr("Pflicht bei \"Sonstiger Grund\"."))
    // `focusOn` ist das <input> selbst (`control.input`), nicht der Wrapper-<div> des Textfeldes -- ein `focus()` auf den Wrapper
    // fokussiert nichts. `watch` nennt den Auslöser (den Grund) UND das Freitext-Input: dessen `change` räumt die Sammelmeldung.
    form.crossFieldRule(
        focusOn = reasonTextField.control.input as? Widget,
        watch = listOfNotNull(reasonField.control.input as? Widget, reasonTextField.control.input as? Widget),
    ) {
        if (reasonField.value == SepaReturnReason.OTHER.name && reasonTextField.value.isBlank()) {
            FieldCheck.Invalid(gettext("Bei \"Sonstiger Grund\" ist ein Freitext erforderlich."))
        } else {
            FieldCheck.Ok
        }
    }
    // Die Rücklastschriftgebühr: der Server lehnt `<= 0` und mehr als zwei Nachkommastellen ab (`recordReturn`). Optional.
    // Leere Werte erreichen die Regel nie (die Grammatik behandelt sie selbst); `FormRules.returnFee` liefert die echte Begründung
    // (positiv, höchstens zwei Nachkommastellen, Obergrenze der Spalte `DECIMAL(12,2)`) -- ein eigener Umweg über
    // `parseAmountInput` verschluckte sie und meldete für "3,005" oder "0" fälschlich "muss ein positiver Betrag sein".
    val feeField =
        form.textField(
            label = tr("Rücklastschriftgebühr in EUR"),
            rule = { FormRules.returnFee(it) },
        )
    val submitButton = Button(tr("Rücklastschrift erfassen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton)

    fun updateRevocationNote() {
        val reason = runCatching { SepaReturnReason.valueOf(reasonField.value) }.getOrNull()
        if (reason != null && reason in SepaReturnReasonSets.FORCES_MANDATE_REVOCATION) revocationNote.show() else revocationNote.hide()
    }
    reasonField.subscribe { updateRevocationNote() }
    updateRevocationNote()

    // MINOR (Review Round 2, 2026-08-20): TWO calls, one per eligible status, instead of
    // `listBatches(status = null, limit = 100)` filtered client-side afterwards -- the server's
    // `limit` applies BEFORE any client-side filtering, newest-first (SepaService.kt:1473), so with
    // more than 100 newer DRAFT/NOTIFIED batches the single-call version would silently return zero
    // SUBMITTED/SETTLED batches even though eligible ones exist further back. A transient failure on
    // one call no longer discards the other's results (`.orEmpty()`, not `?: return@launch`).
    AppScope.launch {
        val submitted =
            guarded { rpcService<ISepaService>().listBatches(status = SepaDebitBatchStatus.SUBMITTED, limit = 100) }.orEmpty()
        val settled = guarded { rpcService<ISepaService>().listBatches(status = SepaDebitBatchStatus.SETTLED, limit = 100) }.orEmpty()
        val eligible = (submitted + settled).sortedByDescending { it.createdAt }
        (batchField.control as Select).options =
            eligible.map { it.id to gettext("%1 (%2)", it.requestedCollectionDate, sepaBatchStatusLabel(it.status)) }
        batchField.setValue(eligible.firstOrNull()?.id)
        // Ein gesetzter Wert räumt einen stehenden Fehler nicht von selbst (siehe `LapisField.setValue`).
        batchField.validate(force = false)
    }

    // MINOR (Review Round 2, 2026-08-20): factored out of `batchField.subscribe` so the post-submit
    // success handler below can call it too -- without this, a just-recorded return's item stayed
    // selectable in the dropdown (its status only changes server-side), and a second submission for
    // the SAME item hit `ConflictException("Fuer diese Position ist bereits ein Rueckläufer
    // erfasst.")`, surfaced only as [SEPA_RECORD_RETURN_CONFLICT_MESSAGE]'s generic disjunction.
    // Also filters to `PENDING`/`SETTLEABLE` -- the only statuses `recordReturn` actually accepts
    // (SepaService.kt:1515) -- instead of offering every item and letting the same conflict surface
    // for one that was, say, already RETURNED or never debited at all.
    //
    // [preselectFirst] ist beim Wechsel des Laufs `true` (wie bisher: die erste Position steht bereit), nach einer ERFASSTEN
    // Rücklastschrift aber `false` -- sonst wählte der Nachlauf automatisch die Position des NÄCHSTEN Mitglieds vor, und ein zweiter
    // Klick erfasste ohne Bestätigung eine Rücklastschrift für eine andere Person.
    fun refreshItemOptions(
        batchId: String?,
        preselectFirst: Boolean = true,
    ) {
        (itemField.control as Select).options = itemPlaceholder
        itemField.setValue("")
        itemField.validate(force = false)
        if (batchId.isNullOrBlank()) return
        AppScope.launch {
            val detail = guarded { rpcService<ISepaService>().getBatch(batchId) } ?: return@launch
            // Eine späte Antwort für einen inzwischen abgewählten Lauf darf Optionen und Wert des Positionsfeldes nicht überschreiben.
            if (batchId != batchField.value) return@launch
            val returnable = detail.items.filter { it.status in setOf(SepaDebitItemStatus.PENDING, SepaDebitItemStatus.SETTLEABLE) }
            // Mit Platzhalter (`""`): ohne ihn zeigte ein leerer Wert (nach einer erfassten Rücklastschrift) im <select> trotzdem die
            // erste Position, obwohl das Feld leer ist.
            (itemField.control as Select).options =
                itemPlaceholder +
                returnable.map { item -> item.id to gettext("%1 -- %2", item.memberDisplayName, formatMoney(item.amount)) }
            itemField.setValue(if (preselectFirst) returnable.firstOrNull()?.id ?: "" else "")
            // Ein Lauf ohne rücklastschriftfähige Position ließ hier den Fehler "Bitte eine Position auswählen." stehen, obwohl der
            // Nutzer gerade einen Lauf MIT Positionen gewählt hat (siehe `LapisField.setValue`).
            itemField.validate(force = false)
        }
    }
    batchField.subscribe { refreshItemOptions(it) }

    submitButton.onClick {
        form.submit(submitButton) {
            val itemId = itemField.value
            val returnedAt = runCatching { LocalDate.parse(returnedAtField.value.trim()) }.getOrNull() ?: return@submit
            val reason = runCatching { SepaReturnReason.valueOf(reasonField.value) }.getOrNull() ?: return@submit
            val reasonText = reasonTextField.value.trim().takeIf { it.isNotBlank() }
            // Die Feldregel lässt nur einen Betrag mit höchstens zwei Nachkommastellen durch (`recordReturn` lehnt `scale > 2` ab).
            val fee: Decimal? = (parseAmountInput(feeField.value, allowZero = false, enforceMaxAmount = false) as? AmountInput.Valid)?.value
            val result =
                sepaGuarded(gettext(SEPA_RECORD_RETURN_CONFLICT_MESSAGE, MAX_OPEN_ITEM_AMOUNT_SCALE)) {
                    rpcService<ISepaService>().recordReturn(
                        SepaReturnInput(
                            debitItemId = itemId,
                            returnedAt = returnedAt,
                            reasonCode = reason,
                            reasonText = reasonText,
                            returnFee = fee,
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(tr("Rücklastschrift erfasst."))
                // Zurück in den Ausgangszustand des ersten Öffnens: Position leer (Platzhalter), Datum heute, Grund der Standardgrund,
                // Freitext und Gebühr leer. Nichts davon darf für die NÄCHSTE Rücklastschrift stehen bleiben.
                itemField.reset()
                returnedAtField.reset()
                returnedAtField.setValue(todayLocalDate().toString())
                reasonField.reset()
                reasonField.setValue(reasonOptions.first().first)
                reasonTextField.reset()
                feeField.reset()
                refreshItemOptions(batchField.value, preselectFirst = false)
                onRecorded()
            }
        }
    }
}
