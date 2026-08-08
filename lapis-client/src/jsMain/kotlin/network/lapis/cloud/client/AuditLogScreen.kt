package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
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
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AuditChainVerificationResultDto
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditLogListQuery
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.PartyDonationVerdictSnapshot
import network.lapis.cloud.shared.domain.ResolutionSnapshot
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
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("Prüfprotokoll")
    root.div(
        "GoBD-revisionssicheres Protokoll aller Buchungen, Beschlüsse, Vorstandsmitgliedschaften und " +
            "Spendenprüfungen -- unveränderlich und ausschließlich lesbar. Es gibt auf dieser Seite keine " +
            "Funktion zum Bearbeiten oder Löschen von Einträgen.",
    ) { addCssClasses("text-muted small") }

    // ---- D1: chain-integrity verification ---------------------------------------------------
    root.h2("Ketten-Integrität prüfen")
    renderChainVerificationPanel(root)

    // ---- List: filters + keyset-paginated entries --------------------------------------------
    root.h2("Einträge")
    val filterRow1 = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val entityTypeOptions = listOf("" to "Alle Entitätstypen") + AuditEntityType.entries.map { it.name to auditEntityTypeLabel(it) }
    val entityTypeSelect = filterRow1.select(options = entityTypeOptions, value = "", label = "Entitätstyp")
    val entityIdInput = filterRow1.text(label = "Entitäts-ID (optional)")
    val actorMemberIdInput = filterRow1.text(label = "Akteur-Mitglieds-ID (optional)")

    val filterRow2 = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val fromInput = filterRow2.text(label = "Von (JJJJ-MM-TTTHH:MM:SS, optional)")
    val toInput = filterRow2.text(label = "Bis (JJJJ-MM-TTTHH:MM:SS, optional)")
    val filterButton = filterRow2.button("Filtern", style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button("Mehr laden", style = ButtonStyle.OUTLINESECONDARY) { hide() }

    root.h2("Details")
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p("Eintrag oben auswählen, um Details zu sehen.")

    var lastLoadedSequenceNumber: Long? = null

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

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            lastLoadedSequenceNumber = null
        }
        AppScope.launch {
            val entries =
                guarded {
                    rpcService<IAuditLogService>().listAuditLog(buildQuery(if (reset) null else lastLoadedSequenceNumber))
                } ?: return@launch
            if (entries.isEmpty()) {
                if (reset) listPanel.p("Keine Einträge für diese Filter gefunden.")
                loadMoreButton.hide()
                return@launch
            }
            entries.forEach { entry -> renderAuditLogRow(listPanel, entry, ::selectEntry) }
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
    val fromSeqInput = row.text(label = "Von Sequenznummer (optional)")
    val toSeqInput = row.text(label = "Bis Sequenznummer (optional)")
    val verifyButton = row.button("Kette prüfen", style = ButtonStyle.PRIMARY)
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
        box.div("✓ Kette intakt") { addCssClass("fw-bold") }
        box.div(chainVerificationPassDetailText(result))
    } else {
        val box = panel.vPanel(spacing = 2) { addCssClasses("alert alert-danger") }
        box.div(chainVerificationFailHeadline(result)) { addCssClass("fw-bold") }
        box.div(result.reason.orEmpty())
        box.div(CHAIN_VERIFICATION_BROKEN_GUIDANCE)
    }
}

/** D1's exact pass-state second line. Both bounds `null` (nothing matched the requested range) shows
 * the no-entries copy rather than ever fabricating a range from `null` values. */
fun chainVerificationPassDetailText(result: AuditChainVerificationResultDto): String =
    if (result.firstSequenceNumber != null && result.lastSequenceNumber != null) {
        "${result.checkedCount} Einträge geprüft (Sequenznummer ${result.firstSequenceNumber}–${result.lastSequenceNumber})."
    } else {
        "Keine Einträge im geprüften Bereich."
    }

/** D1's exact fail-state headline. */
fun chainVerificationFailHeadline(result: AuditChainVerificationResultDto): String =
    "✗ Kette gebrochen bei Sequenznummer ${result.brokenAtSequenceNumber}"

/** D1's exact fixed third line for the fail state. */
const val CHAIN_VERIFICATION_BROKEN_GUIDANCE =
    "Wenden Sie sich an Ihre Entwicklerin oder Ihren Entwickler -- dieses Ergebnis deutet auf eine " +
        "nachträgliche Veränderung der Aufzeichnungen hin."

// ================================================================================================
// List row
// ================================================================================================

private fun renderAuditLogRow(
    panel: SimplePanel,
    entry: AuditLogEntryDto,
    onSelect: (String) -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.typeBadge(auditEntityTypeLabel(entry.entityType), auditEntityTypeColor(entry.entityType))
    headerRow.statusBadge(auditActionLabel(entry.action), auditActionColor(entry.action))
    headerRow.div("Seq. ${entry.sequenceNumber}") { addCssClasses("flex-grow-1 text-muted small") }

    row.div("${entry.occurredAt} · ${actorDisplayText(entry)} · Entität ${entry.entityId}") {
        addCssClasses("text-muted small")
    }

    val showButton = row.button("Details anzeigen", style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(entry.id) }
}

/** `actorMemberId`/`actorRole` are both `null` only for the reserved, currently-unused future
 * SYSTEM/job actor -- see [AuditLogEntryDto] KDoc; every V0.5.3 write path names a real member
 * actor today, so this branch is defensive, not dead-in-practice-only. */
private fun actorDisplayText(entry: AuditLogEntryDto): String {
    val name = entry.actorMemberDisplayName
    return if (name != null) "$name (${entry.actorRole})" else "Systemvorgang (kein Akteur hinterlegt)"
}

// ================================================================================================
// Detail view
// ================================================================================================

private fun renderAuditLogDetail(
    panel: SimplePanel,
    id: String,
) {
    panel.removeAll()
    panel.p("Wird geladen …") { addCssClasses("text-muted small") }
    AppScope.launch {
        val entry = guarded { rpcService<IAuditLogService>().getAuditLogEntry(id) } ?: return@launch
        panel.removeAll()

        val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        headerRow.typeBadge(auditEntityTypeLabel(entry.entityType), auditEntityTypeColor(entry.entityType))
        headerRow.statusBadge(auditActionLabel(entry.action), auditActionColor(entry.action))
        headerRow.div("Sequenznummer ${entry.sequenceNumber}") { addCssClasses("flex-grow-1 fw-bold") }

        panel.div("Zeitpunkt: ${entry.occurredAt}") { addCssClasses("text-muted small") }
        panel.div("Akteur: ${actorDisplayText(entry)}") { addCssClasses("text-muted small") }
        panel.div("Entität: ${entry.entityId}") { addCssClasses("text-muted small") }

        renderSnapshotSection(panel, entry)
    }
}

/** D2: before/after render side-by-side when both are present (CREATE has only `after`,
 * UPDATE/POST have both) -- the same "before/after" visual grammar a diff view would use, without
 * building an actual diff. */
private fun renderSnapshotSection(
    panel: SimplePanel,
    entry: AuditLogEntryDto,
) {
    if (entry.beforeSnapshot == null && entry.afterSnapshot == null) {
        panel.p("Keine Detaildaten für diesen Eintrag hinterlegt.") { addCssClasses("text-muted small") }
        return
    }
    panel.h2("Datenstand") { addCssClass("h6") }
    val columns = panel.hPanel(spacing = 16) { addCssClasses("align-items-start flex-wrap") }
    entry.beforeSnapshot?.let { raw ->
        val column = columns.vPanel(spacing = 4) { addCssClasses("flex-grow-1") }
        column.div("Vorher") { addCssClass("fw-bold") }
        renderSnapshotBody(column, entry.entityType, raw)
    }
    entry.afterSnapshot?.let { raw ->
        val column = columns.vPanel(spacing = 4) { addCssClasses("flex-grow-1") }
        column.div("Nachher") { addCssClass("fw-bold") }
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
        else -> renderRawSnapshotFallback(panel, raw)
    }
}

private fun SimplePanel.labelValueRow(
    label: String,
    value: String,
) {
    val row = hPanel(spacing = 8) { addCssClasses("small") }
    row.div("$label:") {
        addCssClasses("text-muted")
        width = 220.px
    }
    row.div(value) { addCssClasses("flex-grow-1") }
}

/** [statusBadge] grammar (filled) -- for a snapshot field that is a lifecycle status. */
private fun SimplePanel.labelStatusBadgeRow(
    label: String,
    badgeText: String,
    badgeColor: String,
) {
    val row = hPanel(spacing = 8) { addCssClasses("small align-items-center") }
    row.div("$label:") {
        addCssClasses("text-muted")
        width = 220.px
    }
    row.statusBadge(badgeText, badgeColor)
}

/** [typeBadge] grammar (outline) -- for a snapshot field that is a fixed classification. */
private fun SimplePanel.labelTypeBadgeRow(
    label: String,
    badgeText: String,
    badgeColor: String,
) {
    val row = hPanel(spacing = 8) { addCssClasses("small align-items-center") }
    row.div("$label:") {
        addCssClasses("text-muted")
        width = 220.px
    }
    row.typeBadge(badgeText, badgeColor)
}

private fun renderJournalEntrySnapshotBody(
    panel: SimplePanel,
    snapshot: JournalEntrySnapshot,
) {
    panel.labelValueRow("Datum", snapshot.entryDate.toString())
    panel.labelValueRow("Beschreibung", snapshot.description)
    snapshot.voucherReference?.let { panel.labelValueRow("Beleg", it) }
    panel.labelStatusBadgeRow("Status", journalEntryStatusLabel(snapshot.status), journalEntryStatusColor(snapshot.status))
    snapshot.postedAt?.let { panel.labelValueRow("Gebucht am", it.toString()) }
    panel.labelValueRow("Erfasst von (Mitglieds-ID)", snapshot.createdBy)
    snapshot.donorMemberId?.let { panel.labelValueRow("Spendendes Mitglied (ID)", it) }
    snapshot.externalDonorId?.let { panel.labelValueRow("Externer Spender (ID)", it) }
    snapshot.donorCategory?.let { panel.labelTypeBadgeRow("Spenderkategorie", donorCategoryLabel(it), donorCategoryColor(it)) }

    if (snapshot.postings.isNotEmpty()) {
        panel.div("Buchungszeilen:") { addCssClasses("text-muted small mt-1") }
        val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1 small") }
        headerRow.div("Konto (ID)") { addCssClasses("flex-grow-1") }
        headerRow.div("Soll/Haben") { width = 90.px }
        headerRow.div("Betrag") { width = 100.px }
        headerRow.div("Sphäre") { width = 190.px }
        snapshot.postings.forEach { posting ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 small align-items-center") }
            row.div(posting.ledgerAccountId) {
                addCssClasses("flex-grow-1 text-truncate")
                width = 200.px
            }
            row.div(postingSideLabel(posting.side)) { width = 90.px }
            row.div(formatMoney(posting.amount)) { width = 100.px }
            val sphereCell = row.div { width = 190.px }
            sphereCell.typeBadge(sphereLabel(posting.sphere), sphereColor(posting.sphere))
        }
    }
}

private fun renderResolutionSnapshotBody(
    panel: SimplePanel,
    snapshot: ResolutionSnapshot,
) {
    panel.labelValueRow("Sitzung (ID)", snapshot.meetingId)
    panel.labelValueRow("Nummer", snapshot.number)
    panel.labelValueRow("Titel", snapshot.title)
    panel.labelValueRow("Text", snapshot.text)
    panel.labelValueRow(
        "Abstimmung",
        "Ja: ${snapshot.votesYes} · Nein: ${snapshot.votesNo} · Enthaltung: ${snapshot.votesAbstain}",
    )
    panel.labelValueRow("Quorum erreicht", if (snapshot.quorumMet) "Ja" else "Nein")
    panel.labelStatusBadgeRow("Status", resolutionStatusLabel(snapshot.status), resolutionStatusColor(snapshot.status))
    panel.labelTypeBadgeRow("Verfahren", resolutionModeLabel(snapshot.resolutionMode), resolutionModeColor(snapshot.resolutionMode))
    panel.labelValueRow("Entschieden am", snapshot.decidedAt.toString())
    panel.labelValueRow("Protokolliert von (ID)", snapshot.recordedBy)
}

private fun renderBoardMembershipSnapshotBody(
    panel: SimplePanel,
    snapshot: BoardMembershipSnapshot,
) {
    panel.labelValueRow("Mitglied (ID)", snapshot.memberId)
    panel.labelTypeBadgeRow("Rolle", committeeRoleLabel(snapshot.committeeRole), committeeRoleColor(snapshot.committeeRole))
    panel.labelValueRow("Beginn", snapshot.startedAt.toString())
    panel.labelValueRow("Ende", snapshot.endedAt?.toString() ?: "laufend")
}

private fun renderPartyDonationVerdictSnapshotBody(
    panel: SimplePanel,
    snapshot: PartyDonationVerdictSnapshot,
) {
    panel.labelTypeBadgeRow("Spenderkategorie", donorCategoryLabel(snapshot.donorCategory), donorCategoryColor(snapshot.donorCategory))
    panel.labelValueRow("Spendenbetrag", formatMoney(snapshot.donationAmount))
    panel.labelValueRow("Bisherige Jahressumme (vor dieser Spende)", formatMoney(snapshot.priorPostedTotalThisYear))
    panel.labelValueRow("Prüfergebnis", snapshot.verdict)
    if (snapshot.duties.isNotEmpty()) {
        panel.div("Pflichten:") { addCssClasses("text-muted small mt-1") }
        val row = panel.hPanel(spacing = 4) { addCssClasses("flex-wrap") }
        snapshot.duties.forEach { duty -> row.typeBadge(donationDutyLabel(duty), donationDutyColor(duty)) }
    }
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
    val toggleButton = panel.button("Rohdaten ein-/ausblenden", style = ButtonStyle.OUTLINESECONDARY)
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
