package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
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
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 960.px
            marginTop = 24.px
        }
    root.h1(tr("SEPA-Lastschrift"))

    val role = AppState.session?.role
    val canTreasuryAct = SepaAuthzUi.canTreasuryAct(role)

    if (AppState.hasRole(AccountRole.ADMIN)) {
        renderAdminDisclaimerWarningBand(root)
    }

    root.h2(tr("Läufe")) { addCssClass("h5") }
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Lauf oben auswählen, um Details zu sehen.")) { addCssClasses("text-muted small") }

    var lastCreatedAt: LocalDateTime? = null
    var currentTable: Table? = null
    // Fresh per screen instance -- see [SelectedBatchState] KDoc.
    val batchState = SelectedBatchState()

    // Defined before `showDetail`/`selectBatch` below, purely so those closures can reference it --
    // Kotlin local functions, unlike top-level ones, are not forward-referenceable within the same
    // block.
    fun loadBatches(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            lastCreatedAt = null
            currentTable = null
        }
        AppScope.launch {
            val batches =
                sepaGuarded(tr(SEPA_READ_CONFLICT_MESSAGE)) {
                    rpcService<ISepaService>().listBatches(beforeCreatedAt = if (reset) null else lastCreatedAt)
                }
            if (batches == null) {
                loadMoreButton.hide()
                return@launch
            }
            if (batches.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Läufe vorhanden."))
                loadMoreButton.hide()
                return@launch
            }
            val table =
                currentTable ?: listPanel
                    .table(
                        headerNames =
                            listOf(
                                tr("Erstellt am"),
                                tr("Fälligkeit"),
                                tr("Sequenztyp"),
                                tr("Status"),
                                tr("Positionen"),
                                tr("Summe"),
                                "",
                            ),
                        types = setOf(TableType.STRIPED, TableType.HOVER),
                    ).also { currentTable = it }
            batches.forEach { batch ->
                renderSepaBatchRow(table, batch) { batchId ->
                    selectBatch(detailPanel, role, batchState, batchId) { loadBatches(true) }
                }
            }
            lastCreatedAt = batches.last().createdAt
            if (batches.size < SEPA_BATCHES_PAGE_SIZE) loadMoreButton.hide() else loadMoreButton.show()
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

private fun todayLocalDate(): LocalDate =
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

private fun renderNewBatchSection(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    root.h2(tr("Neuer Lauf")) { addCssClass("h5") }
    val formPanel = root.vPanel(spacing = 6)
    // MAJOR (Review Round 2, 2026-08-20): defaulting this to TODAY made the form's own standard
    // path fail every time -- `createDebitBatch` (SepaService.kt:552) rejects any
    // `requestedCollectionDate <= today` outright, but `previewDebitBatch` does NOT check the date
    // at all, so "Vorschau berechnen" would succeed with a real item count/total, inviting a click
    // on "Lauf anlegen" that was then guaranteed to fail. Defaulting to tomorrow is still not a
    // guarantee of success (the real floor is the notice period), but it at least clears the
    // unconditional server-side rejection every prior default hit.
    val collectionDateInput =
        formPanel.text(value = todayLocalDate().plus(1, DateTimeUnit.DAY).toString(), label = tr("Einzugsdatum (JJJJ-MM-TT)"))
    val dueOnOrBeforeInput = formPanel.text(value = todayLocalDate().toString(), label = tr("Fällig bis (JJJJ-MM-TT)"))
    val tierSelect = formPanel.select(options = emptyList(), value = null, label = tr("Beitragssatz (optional, leer = alle)"))
    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: return@launch
        tierSelect.options = listOf("" to tr("Alle Beitragssätze")) + tiers.map { it.id to it.name }
        tierSelect.value = ""
    }
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val previewButton = formPanel.button(tr("Vorschau berechnen"), style = ButtonStyle.OUTLINEPRIMARY)
    val previewPanel = formPanel.vPanel(spacing = 4)
    val createButtonHost = formPanel.vPanel(spacing = 4)

    fun buildInput(): SepaDebitBatchInput? {
        val collectionDate = runCatching { LocalDate.parse(collectionDateInput.value.orEmpty().trim()) }.getOrNull()
        val dueOnOrBefore = runCatching { LocalDate.parse(dueOnOrBeforeInput.value.orEmpty().trim()) }.getOrNull()
        if (collectionDate == null || dueOnOrBefore == null) {
            errorBox.content = tr("Bitte gültige Daten (JJJJ-MM-TT) für Einzugsdatum und Fälligkeit angeben.")
            errorBox.show()
            return null
        }
        // MAJOR (Review Round 2, 2026-08-20): mirrors `createDebitBatch`'s own
        // `requestedCollectionDate <= today` rejection (SepaService.kt:552) client-side --
        // `previewDebitBatch` does not check this at all, so without this check the preview step
        // would happily succeed for a same-day/past date and only the SUBSEQUENT "Lauf anlegen"
        // click would fail, with no indication the date field was the problem.
        if (collectionDate <= todayLocalDate()) {
            errorBox.content = tr("Das Einzugsdatum muss in der Zukunft liegen.")
            errorBox.show()
            return null
        }
        errorBox.hide()
        val tierId = tierSelect.value?.takeIf { it.isNotBlank() }
        return SepaDebitBatchInput(requestedCollectionDate = collectionDate, dueOnOrBefore = dueOnOrBefore, membershipTierId = tierId)
    }

    fun resetPreview() {
        previewPanel.removeAll()
        createButtonHost.removeAll()
    }
    collectionDateInput.subscribe { resetPreview() }
    dueOnOrBeforeInput.subscribe { resetPreview() }
    tierSelect.subscribe { resetPreview() }

    previewButton.onClick {
        val input = buildInput() ?: return@onClick
        previewButton.disabled = true
        AppScope.launch {
            val preview = sepaGuarded(tr(SEPA_WRITE_CONFLICT_MESSAGE)) { rpcService<ISepaService>().previewDebitBatch(input) }
            previewButton.disabled = false
            if (preview != null) {
                renderBatchPreview(previewPanel, preview)
                createButtonHost.removeAll()
                val createButton =
                    createButtonHost.button(
                        gettext("Lauf anlegen (%1 Positionen, %2)", preview.itemCount, formatMoney(preview.totalAmount)),
                        style = ButtonStyle.PRIMARY,
                    )
                createButton.disabled = preview.itemCount == 0
                createButton.onClick {
                    val currentInput = buildInput() ?: return@onClick
                    createButton.disabled = true
                    AppScope.launch {
                        val created =
                            sepaGuarded(
                                tr(SEPA_BATCH_CREATE_CONFLICT_MESSAGE),
                            ) { rpcService<ISepaService>().createDebitBatch(currentInput) }
                        createButton.disabled = false
                        if (created != null) {
                            notifySuccess(tr("Lauf angelegt."))
                            resetPreview()
                            onCreated()
                        }
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
        val table =
            panel.table(
                headerNames = listOf(tr("Mitglied"), tr("Betrag"), tr("Mandatsreferenz"), tr("IBAN"), tr("Erhöht?")),
                types = setOf(TableType.STRIPED, TableType.HOVER),
            )
        preview.items.forEach { item ->
            table.row {
                cell(item.memberDisplayName)
                cell(formatMoney(item.amount))
                cell(item.mandateReference)
                cell(formatIbanLast4(item.debtorIbanLast4))
                cell(if (item.amountIncreased) tr("Ja") else tr("Nein"))
            }
        }
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

private fun renderSepaBatchRow(
    table: Table,
    batch: SepaDebitBatchDto,
    onSelect: (String) -> Unit,
) {
    table.row {
        cell(batch.createdAt.toString())
        cell(batch.requestedCollectionDate.toString())
        val seqCell = cell()
        seqCell.typeBadge(sepaSequenceTypeLabel(batch.sequenceType), sepaSequenceTypeColor(batch.sequenceType))
        val statusCell = cell()
        statusCell.statusBadge(sepaBatchStatusLabel(batch.status), sepaBatchStatusColor(batch.status))
        cell(batch.itemCount.toString())
        cell(formatMoney(batch.totalAmount))
        val actionsCell = cell()
        val showButton = actionsCell.button(tr("Details anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
        showButton.onClick { onSelect(batch.id) }
    }
}

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
                cancelButton.disabled = true
                AppScope.launch {
                    val result =
                        sepaGuarded(tr(SEPA_WRITE_CONFLICT_MESSAGE)) {
                            rpcService<ISepaService>().cancelBatch(batch.id, reason.orEmpty())
                        }
                    cancelButton.disabled = false
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
        val itemsTable =
            panel.table(
                headerNames = listOf(tr("Mitglied"), tr("Betrag"), tr("Mandatsreferenz"), tr("IBAN"), tr("Status")),
                types = setOf(TableType.STRIPED, TableType.HOVER),
            )
        detail.items.forEach { item -> renderSepaItemRow(itemsTable, item, detail.failedItemIds) }
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
                    reasonLabel = tr("Notiz (optional)"),
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
                button.disabled = true
                AppScope.launch {
                    try {
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
                    } finally {
                        button.disabled = false
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
    button.disabled = true
    AppScope.launch {
        try {
            val result = sepaGuarded(conflictMessage) { call() }
            if (result != null) {
                notifySuccess(tr("Gespeichert."))
                onChanged()
            }
        } finally {
            button.disabled = false
        }
    }
}

private fun renderSepaItemRow(
    table: Table,
    item: SepaDebitItemDto,
    failedItemIds: List<String>,
) {
    table.row {
        cell(item.memberDisplayName)
        cell(formatMoney(item.amount))
        cell(item.mandateReference)
        cell(formatIbanLast4(item.debtorIbanLast4))
        val statusCell = cell()
        statusCell.statusBadge(sepaItemStatusLabel(item.status), sepaItemStatusColor(item.status))
        if (item.id in failedItemIds) {
            statusCell.div(tr("fehlgeschlagen")) { addCssClasses("text-danger small") }
        }
    }
}

// ================================================================================================
// Rücklastschriften (Plan §4.3)
// ================================================================================================

private fun renderSepaReturnsSection(
    root: SimplePanel,
    canRecordReturn: Boolean,
) {
    root.h2(tr("Rücklastschriften")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val fromInput = filterRow.text(label = tr("Von (JJJJ-MM-TT, optional)"))
    val toInput = filterRow.text(label = tr("Bis (JJJJ-MM-TT, optional)"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)
    val returnsPanel = root.vPanel(spacing = 6)

    fun loadReturns() {
        returnsPanel.removeAll()
        val from = runCatching { LocalDate.parse(fromInput.value.orEmpty().trim()) }.getOrNull()
        val to = runCatching { LocalDate.parse(toInput.value.orEmpty().trim()) }.getOrNull()
        AppScope.launch {
            val returns = sepaGuarded(tr(SEPA_READ_CONFLICT_MESSAGE)) { rpcService<ISepaService>().listReturns(from, to) }
            if (returns.isNullOrEmpty()) {
                returnsPanel.p(tr("Keine Rücklastschriften für diese Filter.")) { addCssClasses("text-muted small") }
                return@launch
            }
            val table =
                returnsPanel.table(
                    headerNames = listOf(tr("Mitglied"), tr("Datum"), tr("Grund"), tr("Gebühr"), tr("Mandat widerrufen")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            returns.forEach { renderSepaReturnRow(table, it) }
        }
    }
    filterButton.onClick { loadReturns() }
    loadReturns()

    if (canRecordReturn) {
        root.h2(tr("Rücklastschrift erfassen")) { addCssClass("h6") }
        renderRecordReturnForm(root) { loadReturns() }
    }
}

private fun renderSepaReturnRow(
    table: Table,
    sepaReturn: SepaReturnDto,
) {
    table.row {
        cell(sepaReturn.memberDisplayName)
        cell(sepaReturn.returnedAt.toString())
        val reasonCell = cell()
        reasonCell.sepaReturnReasonBadge(sepaReturn.reasonCode)
        cell(sepaReturn.returnFee?.let { formatMoney(it) } ?: "–")
        cell(if (sepaReturn.mandateRevoked) tr("Ja") else tr("Nein"))
    }
}

private fun renderRecordReturnForm(
    root: SimplePanel,
    onRecorded: () -> Unit,
) {
    val formPanel = root.vPanel(spacing = 6)
    val batchSelect = formPanel.select(options = emptyList(), value = null, label = tr("Lauf"))
    val itemSelect = formPanel.select(options = emptyList(), value = null, label = tr("Position"))
    val returnedAtInput = formPanel.text(value = todayLocalDate().toString(), label = tr("Rücklastschrift-Datum (JJJJ-MM-TT)"))
    val reasonOptions = SepaReturnReason.entries.map { it.name to sepaReturnReasonLabel(it) }
    val reasonSelect = formPanel.select(options = reasonOptions, value = reasonOptions.first().first, label = tr("Grund"))
    val revocationNote =
        formPanel.div().apply {
            addCssClasses("text-danger small")
            content = tr("Dieser Grund führt automatisch zum Widerruf des Mandats.")
            hide()
        }
    val reasonTextInput = formPanel.text(label = tr("Freitext (Pflicht bei \"Sonstiger Grund\")"))
    val feeInput = formPanel.text(label = tr("Rücklastschriftgebühr in EUR (optional)"))
    val errorBox =
        formPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = formPanel.button(tr("Rücklastschrift erfassen"), style = ButtonStyle.PRIMARY)

    fun updateRevocationNote() {
        val reason = runCatching { SepaReturnReason.valueOf(reasonSelect.value.orEmpty()) }.getOrNull()
        if (reason != null && reason in SepaReturnReasonSets.FORCES_MANDATE_REVOCATION) revocationNote.show() else revocationNote.hide()
    }
    reasonSelect.subscribe { updateRevocationNote() }
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
        batchSelect.options = eligible.map { it.id to gettext("%1 (%2)", it.requestedCollectionDate, sepaBatchStatusLabel(it.status)) }
        batchSelect.value = eligible.firstOrNull()?.id
    }

    // MINOR (Review Round 2, 2026-08-20): factored out of `batchSelect.subscribe` so the post-submit
    // success handler below can call it too -- without this, a just-recorded return's item stayed
    // selectable in the dropdown (its status only changes server-side), and a second submission for
    // the SAME item hit `ConflictException("Fuer diese Position ist bereits ein Rueckläufer
    // erfasst.")`, surfaced only as [SEPA_RECORD_RETURN_CONFLICT_MESSAGE]'s generic disjunction.
    // Also filters to `PENDING`/`SETTLEABLE` -- the only statuses `recordReturn` actually accepts
    // (SepaService.kt:1515) -- instead of offering every item and letting the same conflict surface
    // for one that was, say, already RETURNED or never debited at all.
    fun refreshItemOptions(batchId: String?) {
        itemSelect.options = emptyList()
        itemSelect.value = null
        if (batchId.isNullOrBlank()) return
        AppScope.launch {
            val detail = guarded { rpcService<ISepaService>().getBatch(batchId) } ?: return@launch
            val returnable = detail.items.filter { it.status in setOf(SepaDebitItemStatus.PENDING, SepaDebitItemStatus.SETTLEABLE) }
            itemSelect.options = returnable.map { item -> item.id to gettext("%1 -- %2", item.memberDisplayName, formatMoney(item.amount)) }
            itemSelect.value = returnable.firstOrNull()?.id
        }
    }
    batchSelect.subscribe { refreshItemOptions(it) }

    submitButton.onClick {
        errorBox.hide()
        val itemId = itemSelect.value
        val returnedAt = runCatching { LocalDate.parse(returnedAtInput.value.orEmpty().trim()) }.getOrNull()
        val reason = runCatching { SepaReturnReason.valueOf(reasonSelect.value.orEmpty()) }.getOrNull()
        val reasonText = reasonTextInput.value?.trim()?.takeIf { it.isNotBlank() }
        val feeText = feeInput.value.orEmpty().trim()
        // MINOR (Review Round 2, 2026-08-20): rounds to 2 decimal places BEFORE sending, same as
        // every other Decimal-producing input in this client (`Validation.roundToTwoDecimalPlaces`,
        // see e.g. `SocialNetworkScreen.kt`) -- `recordReturn` (SepaService.kt:1502) rejects a value
        // with `returnFee.scale() > 2` outright, and without this a plausible-looking input like
        // "3,005" passed `Validation.isPositiveDecimal` here only to be rejected server-side with the
        // same generic conflict.
        val fee: Decimal? =
            when {
                feeText.isBlank() -> null
                !Validation.isPositiveDecimal(feeText) -> null
                else -> Validation.roundToTwoDecimalPlaces(feeText.toDouble()).toDecimal()
            }

        val validationError =
            when {
                itemId == null -> tr("Bitte eine Position auswählen.")
                returnedAt == null -> tr("Bitte ein gültiges Datum (JJJJ-MM-TT) angeben.")
                reason == null -> tr("Bitte einen Grund auswählen.")
                reason == SepaReturnReason.OTHER && reasonText == null -> tr("Bei \"Sonstiger Grund\" ist ein Freitext erforderlich.")
                feeText.isNotBlank() && fee == null -> tr("Die Rücklastschriftgebühr muss, falls angegeben, ein positiver Betrag sein.")
                else -> null
            }
        if (validationError != null) {
            errorBox.content = validationError
            errorBox.show()
            return@onClick
        }

        submitButton.disabled = true
        AppScope.launch {
            try {
                val result =
                    sepaGuarded(tr(SEPA_RECORD_RETURN_CONFLICT_MESSAGE)) {
                        rpcService<ISepaService>().recordReturn(
                            SepaReturnInput(
                                debitItemId = itemId!!,
                                returnedAt = returnedAt!!,
                                reasonCode = reason!!,
                                reasonText = reasonText,
                                returnFee = fee,
                            ),
                        )
                    }
                if (result != null) {
                    notifySuccess(tr("Rücklastschrift erfasst."))
                    reasonTextInput.value = null
                    feeInput.value = null
                    refreshItemOptions(batchSelect.value)
                    onRecorded()
                }
            } finally {
                submitButton.disabled = false
            }
        }
    }
}
