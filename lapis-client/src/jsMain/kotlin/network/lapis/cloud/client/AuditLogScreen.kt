package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AuditChainVerificationResultDto
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditLogListQuery
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.OrganizationSettingsPaymentMappingSnapshot
import network.lapis.cloud.shared.domain.PartyDonationVerdictSnapshot
import network.lapis.cloud.shared.domain.PaymentTransactionSnapshot
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.domain.SocialPostModerationSnapshot
import network.lapis.cloud.shared.rpc.IAuditLogService

/**
 * Compliance UI wave, screen 1 of 5 -- "Prüfprotokoll" (GoBD hash-chain audit log: browsable/
 * filterable listing + on-demand chain-integrity verification), per the approved plan + UI/UX-
 * Design-Team review on `feature/compliance-ui`. See plan "Screen 1 -- AuditLogScreen.kt" and design
 * decisions D1 (chain-verification result panel), D2 (structured snapshot rendering), D3 (no edit
 * affordance anywhere on this screen -- structurally enforced, see below).
 *
 * Role gating (verified against `AuditLogService.kt`'s `AUDIT_READ_ROLES` constant):
 * `Routing.kt` gates the whole `/audit-log` route on TREASURER/BOARD/ADMIN -- every one of
 * [IAuditLogService]'s three methods requires exactly that tier server-side, uniformly, with no
 * narrower write tier to additionally gate inside the screen (there is no write method on this
 * interface at all, by design -- GoBD Unveraenderbarkeit: every audit-log row is written exclusively
 * by `AuditLogRecorder` from inside the fachlich transaction it accompanies, never via a directly
 * callable RPC method). Unlike every other Compliance/Accounting/Governance screen in this client,
 * there is therefore no `canManage`-style split anywhere on this screen -- every caller who can see
 * this screen at all sees the identical, fully read-only view.
 *
 * D3, Steve Jobs' explicit instruction from the design review: *this screen must never grow a stray
 * button that happens to open an editable form*. The list row and the detail view below render
 * exactly three kinds of buttons -- "Details anzeigen" (opens the read-only detail panel), "Mehr
 * laden" (keyset pagination), and "Kette prüfen" (D1's verification action) -- and nothing else.
 * This file was written from scratch rather than copy-pasted from another screen's detail view for
 * exactly this reason (see plan/design D3).
 *
 * [beforeSnapshot]/[afterSnapshot] are opaque JSON strings server-side ([AuditLogEntryDto] KDoc);
 * D2 renders them structured, not as raw JSON, by decoding against the four snapshot DTOs the
 * backend already defines for exactly this purpose ([decodeAuditSnapshot]) -- with a raw-text
 * fallback ([renderRawSnapshotFallback]) for a future `entityType` this client predates, or
 * malformed data, so the detail view never crashes and never silently drops the data.
 *
 * Pagination is real server-side keyset pagination via [AuditLogListQuery.beforeSequenceNumber] --
 * never an offset/page-number control, matching [IAuditLogService.listAuditLog]'s own driftsafe-
 * under-concurrent-inserts contract.
 */
fun renderAuditLogScreen(container: SimplePanel) {
    val root =
        container.dataScreenRoot(spacing = 14)
    root.h1(tr("Prüfprotokoll"))
    root.div(
        tr(
            "GoBD-revisionssicheres Protokoll aller Buchungen, Beschlüsse, Vorstandsmitgliedschaften und " +
                "Spendenprüfungen -- unveränderlich und ausschließlich lesbar. Es gibt auf dieser Seite keine " +
                "Funktion zum Bearbeiten oder Löschen von Einträgen.",
        ),
    ) { addCssClasses("text-muted small") }

    // ---- D1: chain-integrity verification ---------------------------------------------------
    root.h2(tr("Ketten-Integrität prüfen"))
    renderChainVerificationPanel(root)

    // ---- List: filters + keyset-paginated entries --------------------------------------------
    root.h2(tr("Einträge"))
    val filterRow1 = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val entityTypeOptions = listOf("" to tr("Alle Entitätstypen")) + AuditEntityType.entries.map { it.name to auditEntityTypeLabel(it) }
    val entityTypeSelect = filterRow1.select(options = entityTypeOptions, value = "", label = tr("Entitätstyp"))
    val entityIdInput = filterRow1.text(label = tr("Entitäts-ID (optional)"))
    val actorMemberIdInput = filterRow1.text(label = tr("Akteur-Mitglieds-ID (optional)"))

    val filterRow2 = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val fromInput = filterRow2.text(label = tr("Von (JJJJ-MM-TTTHH:MM:SS, optional)"))
    val toInput = filterRow2.text(label = tr("Bis (JJJJ-MM-TTTHH:MM:SS, optional)"))
    val filterButton = filterRow2.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val statusRegion = root.dataStatusRegion()
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    root.h2(tr("Details"))
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Eintrag oben auswählen, um Details zu sehen."))

    var lastLoadedSequenceNumber: Long? = null
    // Welle V1.4.27 (W3): the list is a `dataTable` over ALL entries loaded so far ("Mehr laden" appends a page and
    // re-renders the table). NOT sortable, on purpose: the list is keyset-paginated (`beforeSequenceNumber`) and the
    // server has no sort parameter, so re-ordering the loaded subset would contradict the order of the next page
    // (guideline 2.4 "a cursor or offset list gets none").
    var loadedEntries: List<AuditLogEntryDto> = emptyList()
    var generation = 0

    fun buildQuery(beforeSequenceNumber: Long?): AuditLogListQuery =
        AuditLogListQuery(
            entityType = entityTypeSelect.value?.takeIf { it.isNotBlank() }?.let { AuditEntityType.valueOf(it) },
            entityId = entityIdInput.value?.trim()?.takeIf { it.isNotBlank() },
            actorMemberId = actorMemberIdInput.value?.trim()?.takeIf { it.isNotBlank() },
            from = parseOptionalDateTime(fromInput.value),
            to = parseOptionalDateTime(toInput.value),
            limit = AUDIT_LOG_PAGE_SIZE,
            beforeSequenceNumber = beforeSequenceNumber,
        )

    fun selectEntry(id: String) {
        renderAuditLogDetail(detailPanel, id)
    }

    fun renderEntries() {
        listPanel.removeAll()
        if (loadedEntries.isEmpty()) {
            listPanel.p(tr("Keine Einträge für diese Filter gefunden."))
            return
        }
        listPanel.dataTable(
            columns = auditLogColumns(),
            rows = loadedEntries,
            actions = { actions, entry ->
                actions.tableActionButton("fas fa-eye", tr("Details anzeigen")).onClick { selectEntry(entry.id) }
            },
        )
    }

    fun loadPage(reset: Boolean) {
        generation++
        val mine = generation
        if (reset) {
            loadedEntries = emptyList()
            lastLoadedSequenceNumber = null
            listPanel.removeAll()
            loadMoreButton.hide()
        }
        statusRegion.showLoading()
        AppScope.launch {
            val entries =
                guarded {
                    rpcService<IAuditLogService>().listAuditLog(buildQuery(if (reset) null else lastLoadedSequenceNumber))
                }
            if (mine != generation) return@launch // a newer load has taken over
            statusRegion.clearStatus()
            if (entries == null) {
                // A failed FIRST page is an error state with a retry (Lehre 7); a failed "Mehr laden" keeps the
                // pages already on screen and leaves the toast of `guarded` as the message.
                if (reset) listPanel.dataErrorState(onRetry = { loadPage(reset = true) })
                return@launch
            }
            if (entries.isEmpty()) {
                if (reset) renderEntries()
                loadMoreButton.hide()
                return@launch
            }
            loadedEntries = loadedEntries + entries
            renderEntries()
            lastLoadedSequenceNumber = entries.last().sequenceNumber
            if (entries.size < AUDIT_LOG_PAGE_SIZE) loadMoreButton.hide() else loadMoreButton.show()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)
}

/** Server-default page size ([AuditLogListQuery.limit]'s own default) -- passed explicitly rather
 * than relying on the DTO default so [renderAuditLogScreen]'s "does the next page likely exist"
 * heuristic (`entries.size < AUDIT_LOG_PAGE_SIZE`) always compares against the same number it asked
 * for, regardless of a future change to that DTO default. */
private const val AUDIT_LOG_PAGE_SIZE = 50

// ================================================================================================
// D1: chain-integrity verification panel
// ================================================================================================

/**
 * D1: two optional plain `text` inputs (mirrors [dateRangeFilter]'s two-optional-field shape, here
 * parsed with `toLongOrNull()`) + one `PRIMARY` "Kette prüfen" button -- this is the screen's one
 * load-bearing action, so it does not share [ButtonStyle.OUTLINESECONDARY] with the merely-navigational
 * buttons elsewhere on this screen. The result panel starts genuinely empty (not a hidden "assumed
 * valid" placeholder) -- nothing is claimed about chain integrity before this button has actually
 * been pressed and a real server response has come back (Norman, design review).
 */
private fun renderChainVerificationPanel(root: SimplePanel) {
    val row = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val fromSeqInput = row.text(label = tr("Von Sequenznummer (optional)"))
    val toSeqInput = row.text(label = tr("Bis Sequenznummer (optional)"))
    val verifyButton = row.button(tr("Kette prüfen"), style = ButtonStyle.PRIMARY)
    val resultPanel = root.vPanel(spacing = 2)

    verifyButton.onClick {
        val from =
            fromSeqInput.value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()
        val to =
            toSeqInput.value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()
        verifyButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IAuditLogService>().verifyChainIntegrity(from, to) }
            verifyButton.disabled = false
            if (result != null) renderChainVerificationResult(resultPanel, result)
        }
    }
}

/** D1: pass -> `alert-success`; fail -> `alert-danger` with the fixed "contact your developer"
 * guidance line -- see [chainVerificationPassDetailText]/[chainVerificationFailHeadline]/
 * [CHAIN_VERIFICATION_BROKEN_GUIDANCE] for the exact, individually-tested copy. */
private fun renderChainVerificationResult(
    panel: SimplePanel,
    result: AuditChainVerificationResultDto,
) {
    panel.removeAll()
    if (result.valid) {
        val box = panel.vPanel(spacing = 2) { addCssClasses("alert alert-success") }
        box.div(tr("✓ Kette intakt")) { addCssClass("fw-bold") }
        box.div(chainVerificationPassDetailText(result))
    } else {
        val box = panel.vPanel(spacing = 2) { addCssClasses("alert alert-danger") }
        box.div(chainVerificationFailHeadline(result)) { addCssClass("fw-bold") }
        box.div(result.reason.orEmpty())
        box.div(tr(CHAIN_VERIFICATION_BROKEN_GUIDANCE))
    }
}

/** D1's exact pass-state second line. Both bounds `null` (nothing matched the requested range) shows
 * the no-entries copy rather than ever fabricating a range from `null` values. */
fun chainVerificationPassDetailText(result: AuditChainVerificationResultDto): String =
    if (result.firstSequenceNumber != null && result.lastSequenceNumber != null) {
        gettext(
            "%1 Einträge geprüft (Sequenznummer %2–%3).",
            result.checkedCount,
            result.firstSequenceNumber,
            result.lastSequenceNumber,
        )
    } else {
        gettext("Keine Einträge im geprüften Bereich.")
    }

/** D1's exact fail-state headline. */
fun chainVerificationFailHeadline(result: AuditChainVerificationResultDto): String =
    gettext("✗ Kette gebrochen bei Sequenznummer %1", result.brokenAtSequenceNumber)

/** D1's exact fixed third line for the fail state. */
const val CHAIN_VERIFICATION_BROKEN_GUIDANCE =
    "Wenden Sie sich an Ihre Entwicklerin oder Ihren Entwickler -- dieses Ergebnis deutet auf eine " +
        "nachträgliche Veränderung der Aufzeichnungen hin."

// ================================================================================================
// List row
// ================================================================================================

/**
 * Columns of the audit list / card list. The timestamp is the identity of an entry and thus the card title; the
 * sequence number is numeric. (Before W3 every entry was a bordered card of its own with the same facts strung
 * together in one text line.)
 */
private fun auditLogColumns(): List<DataColumn<AuditLogEntryDto>> =
    listOf(
        textColumn(title = tr("Zeitpunkt"), primary = true) { entry: AuditLogEntryDto -> entry.occurredAt.toString() },
        DataColumn(
            title = tr("Entitätstyp"),
            cell = {
                container,
                entry,
                ->
                container.typeBadge(auditEntityTypeLabel(entry.entityType), auditEntityTypeColor(entry.entityType))
            },
        ),
        DataColumn(
            title = tr("Aktion"),
            cell = { container, entry -> container.statusBadge(auditActionLabel(entry.action), auditActionColor(entry.action)) },
        ),
        textColumn(title = tr("Seq."), numeric = true) { entry: AuditLogEntryDto -> entry.sequenceNumber.toString() },
        textColumn(title = tr("Akteur")) { entry: AuditLogEntryDto -> actorDisplayText(entry) },
        textColumn(title = tr("Entität")) { entry: AuditLogEntryDto -> entry.entityId },
    )

/** `actorMemberId`/`actorRole` are both `null` only for the reserved, currently-unused future
 * SYSTEM/job actor -- see [AuditLogEntryDto] KDoc; every V0.5.3 write path names a real member
 * actor today, so this branch is defensive, not dead-in-practice-only. */
private fun actorDisplayText(entry: AuditLogEntryDto): String {
    val name = entry.actorMemberDisplayName
    return if (name != null) gettext("%1 (%2)", name, entry.actorRole) else gettext("Systemvorgang (kein Akteur hinterlegt)")
}

// ================================================================================================
// Detail view
// ================================================================================================

internal fun renderAuditLogDetail(
    panel: SimplePanel,
    id: String,
) {
    panel.removeAll()
    // Audit V1.4.27 (F): `dataSection` instead of "Wird geladen ..." forever (`?: return@launch`) when the load fails --
    // a failed detail is an error state in the page with "Erneut versuchen" (a new selection builds a new section).
    panel
        .dataSection<AuditLogEntryDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IAuditLogService>().getAuditLogEntry(id) } },
            render = { body, entry -> renderAuditLogDetailBody(body, entry) },
        ).reload()
}

private fun renderAuditLogDetailBody(
    panel: SimplePanel,
    entry: AuditLogEntryDto,
) {
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.typeBadge(auditEntityTypeLabel(entry.entityType), auditEntityTypeColor(entry.entityType))
    headerRow.statusBadge(auditActionLabel(entry.action), auditActionColor(entry.action))
    headerRow.div(gettext("Sequenznummer %1", entry.sequenceNumber)) { addCssClasses("flex-grow-1 fw-bold") }

    panel.div(gettext("Zeitpunkt: %1", entry.occurredAt)) { addCssClasses("text-muted small") }
    panel.div(gettext("Akteur: %1", actorDisplayText(entry))) { addCssClasses("text-muted small") }
    panel.div(gettext("Entität: %1", entry.entityId)) { addCssClasses("text-muted small") }
    auditEntityRoute(entry.entityType, entry.entityId)?.let { route ->
        panel.button(tr("In „Offene Posten“ öffnen"), style = ButtonStyle.OUTLINESECONDARY).onClick { navigateTo(route) }
    }

    renderSnapshotSection(panel, entry)
}

/**
 * Welle V1.4.21: Deep-Link vom Prüfprotokoll-Eintrag auf den Screen, der die Entität zeigt -- bisher
 * nur für die Offene-Posten-Entitäten. In der URL steht ausschließlich die opake UUID, nie Name oder
 * Betrag. `OPEN_ITEM_NETTING` führt nur auf den Screen (die Netting-ID ist keine Posten-ID);
 * `RECEIVABLE_DUNNING_NOTICE` bleibt `null` (die Notice-ID lässt sich ohne Posten-ID nicht auflösen --
 * bewusst nicht geraten). `null` = kein Link.
 */
internal fun auditEntityRoute(
    entityType: AuditEntityType,
    entityId: String,
): String? =
    when (entityType) {
        AuditEntityType.OPEN_ITEM -> if (looksLikeOpenItemUuid(entityId)) "${Routes.OPEN_ITEMS}?item=${entityId.trim()}" else null
        AuditEntityType.OPEN_ITEM_NETTING -> Routes.OPEN_ITEMS
        else -> null
    }

/** D2: before/after render side-by-side when both are present (CREATE has only `after`,
 * UPDATE/POST have both) -- the same "before/after" visual grammar a diff view would use, without
 * building an actual diff. */
private fun renderSnapshotSection(
    panel: SimplePanel,
    entry: AuditLogEntryDto,
) {
    if (entry.beforeSnapshot == null && entry.afterSnapshot == null) {
        panel.p(tr("Keine Detaildaten für diesen Eintrag hinterlegt.")) { addCssClasses("text-muted small") }
        return
    }
    panel.h2(tr("Datenstand")) { addCssClass("h6") }
    val columns = panel.hPanel(spacing = 16) { addCssClasses("align-items-start flex-wrap") }
    entry.beforeSnapshot?.let { raw ->
        val column = columns.vPanel(spacing = 4) { addCssClasses("flex-grow-1") }
        column.div(tr("Vorher")) { addCssClass("fw-bold") }
        renderSnapshotBody(column, entry.entityType, raw)
    }
    entry.afterSnapshot?.let { raw ->
        val column = columns.vPanel(spacing = 4) { addCssClasses("flex-grow-1") }
        column.div(tr("Nachher")) { addCssClass("fw-bold") }
        renderSnapshotBody(column, entry.entityType, raw)
    }
}

private val lenientSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * D2: pure, DOM-free decode step covered by [AuditLogScreenTest] -- returns `null` when [raw]
 * doesn't decode against [entityType]'s expected snapshot shape (a future `entityType` this client
 * predates, or malformed data), in which case the caller falls back to a raw-text display rather
 * than crashing the screen or silently dropping the data (see [renderRawSnapshotFallback]).
 */
fun decodeAuditSnapshot(
    entityType: AuditEntityType,
    raw: String,
): Any? =
    runCatching {
        when (entityType) {
            AuditEntityType.JOURNAL_ENTRY -> lenientSnapshotJson.decodeFromString<JournalEntrySnapshot>(raw)
            AuditEntityType.RESOLUTION -> lenientSnapshotJson.decodeFromString<ResolutionSnapshot>(raw)
            AuditEntityType.BOARD_MEMBERSHIP -> lenientSnapshotJson.decodeFromString<BoardMembershipSnapshot>(raw)
            AuditEntityType.PARTY_DONATION_VERDICT -> lenientSnapshotJson.decodeFromString<PartyDonationVerdictSnapshot>(raw)
            // V1.0 Videokonferenzen, Wave 2 "Aufzeichnung" -- ConferenceRecordingService's
            // AuditLogRecorder.record calls never populate before/after (no dedicated snapshot
            // type exists for this entity type yet), so there is deliberately nothing to decode
            // here -- falls through to the same raw-text fallback a future/unknown entityType
            // already gets, see this function's own KDoc.
            AuditEntityType.CONFERENCE_RECORDING -> null
            // V1.0 Videokonferenzen, Wave 3 "Externes Streaming" -- same "no dedicated snapshot
            // type yet" reasoning as CONFERENCE_RECORDING above; the streaming RPC/audit-write
            // step (a later wave step) may introduce one.
            AuditEntityType.CONFERENCE_STREAM -> null
            AuditEntityType.CONFERENCE_STREAM_DESTINATION -> null
            // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- same "no dedicated
            // snapshot type yet" reasoning; ConferenceService.setRoomGuestAccess writes a raw
            // `{"allowFederationGuests":...}` JSON string as `after`, falls through to the raw-text
            // fallback.
            AuditEntityType.CONFERENCE_ROOM -> null
            // Soziales Netzwerk, Welle V1.1.5 -- `removePostForLegalReason`/`executeContentErasure`
            // schreiben beide SocialPostModerationSnapshot (siehe dessen KDoc: ausschließlich
            // Metadaten, NIEMALS den Post-Inhalt).
            AuditEntityType.SOCIAL_POST -> lenientSnapshotJson.decodeFromString<SocialPostModerationSnapshot>(raw)
            // Zahlungsverkehr, Welle V1.2.1, Security Round 1 (2026-08-19, MAJOR-2) --
            // OrganizationSettingsService.updateOrganizationSettings writes
            // OrganizationSettingsPaymentMappingSnapshot (only the three LedgerAccount ids, see that
            // type's own KDoc for the "no full-diff" rationale).
            AuditEntityType.ORGANIZATION_SETTINGS ->
                lenientSnapshotJson.decodeFromString<OrganizationSettingsPaymentMappingSnapshot>(raw)
            // Zahlungsverkehr, Welle V1.2.2 "SEPA-Lastschriftmandate" -- SepaMandateSnapshot/
            // SepaDebitBatchSnapshot exist server-side (see AuditLog.kt), but this client-side
            // decode/render pair is deliberately not extended for them this wave (frontend scope
            // cut, see CHANGELOG "Known limitations") -- same "no dedicated snapshot type decoded
            // yet" fallback as CONFERENCE_RECORDING/CONFERENCE_STREAM above, falls through to the
            // raw-text display.
            AuditEntityType.SEPA_MANDATE -> null
            AuditEntityType.SEPA_DEBIT_BATCH -> null
            // Zahlungsverkehr, Welle V1.2.7 "Automatisiertes Mahnwesen" -- DunningNoticeSnapshot
            // exists server-side (see AuditLog.kt), but this client-side decode/render pair is
            // deliberately not extended for it this wave (backend-only wave, see CHANGELOG "Known
            // gaps") -- same "no dedicated snapshot type decoded yet" fallback as SEPA_MANDATE/
            // SEPA_DEBIT_BATCH above, falls through to the raw-text display.
            AuditEntityType.DUNNING_NOTICE -> null
            // Welle V1.2.12 "Mitgliederverwaltung" -- MemberChangeSnapshot exists server-side (see
            // AuditLog.kt), but this client-side decode/render pair is deliberately not extended
            // for it this wave (backend-only wave, same posture DUNNING_NOTICE/SEPA_MANDATE already
            // establish) -- falls through to the raw-text display.
            AuditEntityType.MEMBER -> null
            // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- PspWebhookIngestion writes
            // PaymentTransactionSnapshot (see AuditLog.kt).
            AuditEntityType.PAYMENT_TRANSACTION -> lenientSnapshotJson.decodeFromString<PaymentTransactionSnapshot>(raw)
            // Welle V1.3.1 "API-Fundament, lesend" -- ApiKeyService writes ApiKeySnapshot (see
            // AuditLog.kt), but this client-side decode/render pair is deliberately not extended
            // for it this wave (backend+admin-screen wave, same posture MEMBER/DUNNING_NOTICE
            // already establish for their own audit entries) -- falls through to the raw-text
            // display, which already shows label/keyPrefix/timestamps safely (never a hash).
            AuditEntityType.API_KEY -> null
            // Welle V1.3.2 "Webhooks" (ausgehend) -- WebhookService/WebhookEndpointDeactivation
            // write WebhookEndpointSnapshot (see AuditLog.kt), but this client-side decode/render
            // pair is deliberately not extended for it this wave (backend+admin-screen wave, same
            // posture API_KEY already establishes) -- falls through to the raw-text display, which
            // already shows url/active/timestamps safely (never the signature secret).
            AuditEntityType.WEBHOOK_ENDPOINT -> null
            // Welle V1.4.5.1 "Kontoauszugs-Import" -- BankStatementImportService writes
            // BankStatementImportSnapshot (see AuditLog.kt), but this client-side decode/render
            // pair is deliberately not extended for it this wave (backend-only wave, same posture
            // API_KEY/WEBHOOK_ENDPOINT already establish) -- falls through to the raw-text display.
            AuditEntityType.BANK_STATEMENT_IMPORT -> null
            // Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- AccountingExportService writes no
            // dedicated snapshot type (setToken/removeToken/acknowledgeZeroVat carry no
            // before/after payload worth decoding -- the token itself must never appear in a
            // snapshot), same posture API_KEY/WEBHOOK_ENDPOINT/BANK_STATEMENT_IMPORT already
            // establish -- falls through to the raw-text display (which stays empty in practice).
            AuditEntityType.ACCOUNTING_EXPORT_CONNECTION -> null
            // Security review Runde 3, Befund 4 (Fund 2026-09-07) -- startExport/abortRun/
            // retryFailed/mapAccount write no dedicated snapshot type either, same posture as
            // ACCOUNTING_EXPORT_CONNECTION immediately above -- falls through to the raw-text
            // display (empty in practice).
            AuditEntityType.ACCOUNTING_EXPORT_RUN -> null
            AuditEntityType.ACCOUNTING_EXPORT_MAPPING -> null
            // Welle V1.4.10 "Beitragsvergünstigungen" -- ContributionReliefService writes
            // ContributionReliefSnapshot (see AuditLog.kt), but this client-side decode/render
            // pair is deliberately not extended for it this wave (backend-only wave, same posture
            // ACCOUNTING_EXPORT_RUN/API_KEY already establish) -- falls through to the raw-text
            // display.
            AuditEntityType.CONTRIBUTION_RELIEF_REQUEST -> null
            // Welle V1.4.11 "Reisekostenabrechnung" -- TravelExpenseService writes
            // TravelExpenseSnapshot (see AuditLog.kt), but this client-side decode/render pair is
            // deliberately not extended for it this wave (backend-only wave, same posture
            // CONTRIBUTION_RELIEF_REQUEST/ACCOUNTING_EXPORT_RUN already establish) -- falls
            // through to the raw-text display.
            AuditEntityType.TRAVEL_EXPENSE_REPORT -> null
            // Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- VolunteerAllowanceService
            // writes VolunteerAllowanceSnapshot/VolunteerAllowanceDeclarationSnapshot (see
            // AuditLog.kt), but this client-side decode/render pair is deliberately not extended
            // for either this wave (backend-only wave, same posture TRAVEL_EXPENSE_REPORT already
            // establishes) -- falls through to the raw-text display.
            AuditEntityType.VOLUNTEER_ALLOWANCE_PAYMENT -> null
            AuditEntityType.VOLUNTEER_DECLARATION -> null
            // Welle V1.4.14 "Mehrere Bankkonten" -- BankAccountStore writes BankAccountSnapshot
            // (see AuditLog.kt/BankAccounts.kt), but this client-side decode/render pair is
            // deliberately not extended for it this wave (backend-only wave, same posture
            // VOLUNTEER_ALLOWANCE_PAYMENT/VOLUNTEER_DECLARATION already establish) -- falls
            // through to the raw-text display.
            AuditEntityType.BANK_ACCOUNT -> null
            // Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- OpenItemService writes
            // OpenItemSnapshot/OpenItemNettingSnapshot (see AuditLog.kt), but this client-side
            // decode/render pair is deliberately not extended for either this wave (backend-only
            // wave, same posture BANK_ACCOUNT/VOLUNTEER_DECLARATION already establish) -- falls
            // through to the raw-text display.
            AuditEntityType.OPEN_ITEM -> null
            AuditEntityType.OPEN_ITEM_NETTING -> null
            // ReceivableDunningEngine writes ReceivableDunningNoticeSnapshot (see AuditLog.kt),
            // same "backend-only wave" posture as DUNNING_NOTICE above (the pre-existing
            // member-contribution dunning domain's own notice snapshot is likewise not decoded
            // here).
            AuditEntityType.RECEIVABLE_DUNNING_NOTICE -> null
        }
    }.getOrNull()

private fun renderSnapshotBody(
    panel: SimplePanel,
    entityType: AuditEntityType,
    raw: String,
) {
    when (val decoded = decodeAuditSnapshot(entityType, raw)) {
        is JournalEntrySnapshot -> renderJournalEntrySnapshotBody(panel, decoded)
        is ResolutionSnapshot -> renderResolutionSnapshotBody(panel, decoded)
        is BoardMembershipSnapshot -> renderBoardMembershipSnapshotBody(panel, decoded)
        is PartyDonationVerdictSnapshot -> renderPartyDonationVerdictSnapshotBody(panel, decoded)
        is OrganizationSettingsPaymentMappingSnapshot -> renderOrganizationSettingsPaymentMappingSnapshotBody(panel, decoded)
        is PaymentTransactionSnapshot -> renderPaymentTransactionSnapshotBody(panel, decoded)
        else -> renderRawSnapshotFallback(panel, raw)
    }
}

private fun Tag.labelValueRow(
    label: String,
    value: String,
) = detailEntry("$label:") { span(value) }

/** [statusBadge] grammar (filled) -- for a snapshot field that is a lifecycle status. */
private fun Tag.labelStatusBadgeRow(
    label: String,
    badgeText: String,
    badgeColor: String,
) = detailEntry("$label:") { statusBadge(badgeText, badgeColor) }

/** [typeBadge] grammar (outline) -- for a snapshot field that is a fixed classification. */
private fun Tag.labelTypeBadgeRow(
    label: String,
    badgeText: String,
    badgeColor: String,
) = detailEntry("$label:") { typeBadge(badgeText, badgeColor) }

internal fun renderJournalEntrySnapshotBody(
    panel: SimplePanel,
    snapshot: JournalEntrySnapshot,
) {
    val details = panel.detailList()
    details.labelValueRow(gettext("Datum"), snapshot.entryDate.toString())
    details.labelValueRow(gettext("Beschreibung"), snapshot.description)
    snapshot.voucherReference?.let { details.labelValueRow(gettext("Beleg"), it) }
    details.labelStatusBadgeRow(gettext("Status"), journalEntryStatusLabel(snapshot.status), journalEntryStatusColor(snapshot.status))
    snapshot.postedAt?.let { details.labelValueRow(gettext("Gebucht am"), it.toString()) }
    details.labelValueRow(gettext("Erfasst von (Mitglieds-ID)"), snapshot.createdBy)
    snapshot.donorMemberId?.let { details.labelValueRow(gettext("Spendendes Mitglied (ID)"), it) }
    snapshot.externalDonorId?.let { details.labelValueRow(gettext("Externer Spender (ID)"), it) }
    snapshot.donorCategory?.let { details.labelTypeBadgeRow(gettext("Spenderkategorie"), donorCategoryLabel(it), donorCategoryColor(it)) }

    if (snapshot.postings.isNotEmpty()) {
        val report = panel.reportTable(caption = tr("Buchungszeilen:"), headers = SNAPSHOT_POSTING_HEADERS)
        report.reportRows(journalPostingSnapshotRows(snapshot.postings), SNAPSHOT_POSTING_HEADERS)
    }
}

private val SNAPSHOT_POSTING_HEADERS =
    listOf(
        TableHeader(title = tr("Konto (ID)")),
        TableHeader(title = tr("Soll/Haben")),
        TableHeader(title = tr("Betrag"), numeric = true),
        TableHeader(title = tr("Sphäre")),
    )

private fun renderResolutionSnapshotBody(
    panel: SimplePanel,
    snapshot: ResolutionSnapshot,
) {
    val details = panel.detailList()
    details.labelValueRow(gettext("Sitzung (ID)"), snapshot.meetingId)
    details.labelValueRow(gettext("Nummer"), snapshot.number)
    details.labelValueRow(gettext("Titel"), snapshot.title)
    details.labelValueRow(gettext("Text"), snapshot.text)
    details.labelValueRow(
        gettext("Abstimmung"),
        gettext("Ja: %1 · Nein: %2 · Enthaltung: %3", snapshot.votesYes, snapshot.votesNo, snapshot.votesAbstain),
    )
    details.labelValueRow(gettext("Quorum erreicht"), if (snapshot.quorumMet) tr("Ja") else tr("Nein"))
    details.labelStatusBadgeRow(gettext("Status"), resolutionStatusLabel(snapshot.status), resolutionStatusColor(snapshot.status))
    details.labelTypeBadgeRow(
        gettext("Verfahren"),
        resolutionModeLabel(snapshot.resolutionMode),
        resolutionModeColor(snapshot.resolutionMode),
    )
    details.labelValueRow(gettext("Entschieden am"), snapshot.decidedAt.toString())
    details.labelValueRow(gettext("Protokolliert von (ID)"), snapshot.recordedBy)
}

private fun renderBoardMembershipSnapshotBody(
    panel: SimplePanel,
    snapshot: BoardMembershipSnapshot,
) {
    val details = panel.detailList()
    details.labelValueRow(gettext("Mitglied (ID)"), snapshot.memberId)
    details.labelTypeBadgeRow(gettext("Rolle"), committeeRoleLabel(snapshot.committeeRole), committeeRoleColor(snapshot.committeeRole))
    details.labelValueRow(gettext("Beginn"), snapshot.startedAt.toString())
    details.labelValueRow(gettext("Ende"), snapshot.endedAt?.toString() ?: tr("laufend"))
}

private fun renderPartyDonationVerdictSnapshotBody(
    panel: SimplePanel,
    snapshot: PartyDonationVerdictSnapshot,
) {
    val details = panel.detailList()
    details.labelTypeBadgeRow(
        gettext("Spenderkategorie"),
        donorCategoryLabel(snapshot.donorCategory),
        donorCategoryColor(snapshot.donorCategory),
    )
    details.labelValueRow(gettext("Spendenbetrag"), formatMoney(snapshot.donationAmount))
    details.labelValueRow(gettext("Bisherige Jahressumme (vor dieser Spende)"), formatMoney(snapshot.priorPostedTotalThisYear))
    details.labelValueRow(gettext("Prüfergebnis"), snapshot.verdict)
    if (snapshot.duties.isNotEmpty()) {
        panel.div(tr("Pflichten:")) { addCssClasses("text-muted small mt-1") }
        val row = panel.hPanel(spacing = 4) { addCssClasses("flex-wrap") }
        snapshot.duties.forEach { duty -> row.typeBadge(donationDutyLabel(duty), donationDutyColor(duty)) }
    }
}

/** Zahlungsverkehr, Welle V1.2.1, Security Round 1 (2026-08-19, MAJOR-2) -- id-only, same PII-
 * minimization convention as [renderJournalEntrySnapshotBody]'s `createdBy`/`donorMemberId` rows;
 * `null` means "unconfigured", shown explicitly rather than omitting the row. */
private fun renderOrganizationSettingsPaymentMappingSnapshotBody(
    panel: SimplePanel,
    snapshot: OrganizationSettingsPaymentMappingSnapshot,
) {
    val details = panel.detailList()
    details.labelValueRow(gettext("Bankkonto (LedgerAccount-ID)"), snapshot.paymentBankAccountId ?: tr("nicht konfiguriert"))
    details.labelValueRow(gettext("Gebührenkonto (LedgerAccount-ID)"), snapshot.paymentFeeAccountId ?: tr("nicht konfiguriert"))
    details.labelValueRow(
        gettext("Beitragserlöskonto (LedgerAccount-ID)"),
        snapshot.contributionIncomeAccountId ?: tr("nicht konfiguriert"),
    )
}

/** Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- see [PaymentTransactionSnapshot] KDoc: no card/IBAN/payload data, only ids and the pre-hashed digest. */
private fun renderPaymentTransactionSnapshotBody(
    panel: SimplePanel,
    snapshot: PaymentTransactionSnapshot,
) {
    val details = panel.detailList()
    details.labelTypeBadgeRow(gettext("Anbieter"), paymentProviderLabel(snapshot.provider), paymentProviderColor(snapshot.provider))
    details.labelStatusBadgeRow(
        gettext("Status"),
        paymentTransactionStatusLabel(snapshot.status),
        paymentTransactionStatusColor(snapshot.status),
    )
    details.labelValueRow(gettext("Betrag"), formatMoney(snapshot.amount))
    details.labelTypeBadgeRow(gettext("Art"), paymentIntentLabel(snapshot.intent), paymentIntentColor(snapshot.intent))
    snapshot.contributionId?.let { details.labelValueRow(gettext("Beitrag (ID)"), it) }
    snapshot.memberId?.let { details.labelValueRow(gettext("Mitglied (ID)"), it) }
    snapshot.donorCategory?.let { details.labelTypeBadgeRow(gettext("Spenderkategorie"), donorCategoryLabel(it), donorCategoryColor(it)) }
    snapshot.journalEntryId?.let { details.labelValueRow(gettext("Journalbuchung (ID)"), it) }
}

/** D2: never silently drop the data -- a future `entityType` this client predates, or malformed
 * snapshot data, still shows the raw string, collapsed behind a toggle rather than dumped inline. */
private fun renderRawSnapshotFallback(
    panel: SimplePanel,
    raw: String,
) {
    val detailPanel =
        panel.div(raw) {
            addCssClasses("border rounded p-2 small font-monospace")
            setStyle("white-space", "pre-wrap")
            hide()
        }
    val toggleButton = panel.button(tr("Rohdaten ein-/ausblenden"), style = ButtonStyle.OUTLINESECONDARY)
    var expanded = false
    toggleButton.onClick {
        expanded = !expanded
        if (expanded) detailPanel.show() else detailPanel.hide()
    }
}

// ================================================================================================
// Pure helpers -- covered by AuditLogScreenTest.kt
// ================================================================================================

/** Blank/unparsable input means "no filter on this side" -- never silently substitutes a default
 * range the user never asked for, matching [DateRangeFilterControls]'s own posture for `LocalDate`. */
fun parseOptionalDateTime(raw: String?): LocalDateTime? =
    raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
