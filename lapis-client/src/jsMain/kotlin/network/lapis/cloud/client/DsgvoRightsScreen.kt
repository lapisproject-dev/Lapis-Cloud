package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureRequestDto
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.ExportManifestDto
import network.lapis.cloud.shared.rpc.IDsgvoService

/**
 * Compliance UI wave, screen 4 of 5 -- "Meine Daten" (member-facing DSGVO rights: Auskunft/Export
 * Art. 15/20 DSGVO, Löschung/Recht auf Vergessenwerden Art. 17 DSGVO) PLUS an ADMIN-only
 * decide/execute workflow for erasure requests and the DSGVO-specific audit trail, per the approved
 * plan + UI/UX-Design-Team review on `feature/compliance-ui`. See plan "Screen 4 --
 * DsgvoRightsScreen.kt" and design decisions D4 (three-step workflow visualization), D5 (erasure
 * mode disclosure), D6 (`legalHold`), D10 (stacked-sections layout, not tabs), D10(b) (the
 * un-fetchable prior-request-status gap, made honest rather than hidden).
 *
 * D10: self-service always renders first; the ADMIN "Anträge verwalten" queue is appended below,
 * conditionally, when [AppState.hasRole] ADMIN -- the exact `if (canManage) { root.h2(...) ; ... }`
 * idiom [LedgerScreen]/[DocumentsScreen] already establish, NOT a second tab. An ADMIN is very
 * likely to also want to exercise their own member rights on this same screen, so hiding self-
 * service behind a tab switch for that user would be actively worse than just always showing it.
 *
 * Role gating (verified against `DsgvoService.kt`'s actual `requireSelfOrAdmin`/`requireRole` call
 * sites, plan "Role-gating per action"): `Routing.kt` gates the whole `/dsgvo-rights` route on plain
 * `requireAuth` -- [IDsgvoService.exportManifest]/[IDsgvoService.requestErasure] are `requireSelfOrAdmin`
 * (any authenticated member acting on themselves, which is the only case this screen's self-service
 * tier ever calls -- an ADMIN exercising another member's rights on their behalf is out of scope for
 * this wave's UI, same reasoning "Anträge verwalten" exists as a *separate*, narrower ADMIN-only
 * surface, not a generalized on-behalf-of tool). [IDsgvoService.listErasureRequests]/
 * [IDsgvoService.decideErasure]/[IDsgvoService.executeErasure]/[IDsgvoService.listAuditLog] are all
 * `requireRole(ADMIN)`, uniformly, no BOARD exception anywhere on this interface -- gated inside the
 * screen via `AppState.hasRole(AccountRole.ADMIN)`.
 *
 * D10(b): [IDsgvoService] has no self-facing "get my own erasure request" read method
 * (`listErasureRequests` is ADMIN-only) -- adding one is a backend change this "standard frontend"
 * wave should not make on its own initiative (task brief: backend changes only for a genuine bug).
 * So the status card a member sees after calling [requestErasure] lives ONLY in this screen's local
 * in-memory state for the rest of that visit -- [ERASURE_SELF_STATUS_VISIBILITY_CAPTION] says this
 * out loud, permanently, directly under the submit button, rather than silently vanishing on reload
 * and looking like a bug. Flagged in CHANGELOG.md as a known follow-up.
 */
fun renderDsgvoRightsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("Meine Daten")
    root.div(
        "Ihr Recht auf Auskunft (Art. 15/20 DSGVO) und auf Löschung (\"Recht auf Vergessenwerden\", " +
            "Art. 17 DSGVO) -- eine Löschung wird hier nur BEANTRAGT, nie sofort ausgeführt. Über den " +
            "Antrag entscheidet danach eine Administratorin oder ein Administrator gesondert.",
    ) { addCssClasses("text-muted small") }

    renderSelfServiceSection(root)

    // D10: additive, not exclusive -- appended below the self-service section, never a second tab.
    if (AppState.hasRole(AccountRole.ADMIN)) {
        root.h2("Anträge verwalten")
        root.div(
            "Alle Löschanträge -- genehmigen/ablehnen, und einen genehmigten Antrag endgültig ausführen.",
        ) { addCssClasses("text-muted small") }
        renderAdminQueueSection(root)

        root.h2("DSGVO-Prüfprotokoll")
        root.div(
            "Metadaten aller Auskunfts-/Löschvorgänge -- rein informativ, keine Aktionen auf dieser Liste.",
        ) { addCssClasses("text-muted small") }
        renderDsgvoAuditLogSection(root)
    }
}

// ================================================================================================
// Self-service tier -- "Meine Daten": Auskunft + Löschung beantragen, own data only
// ================================================================================================

private fun renderSelfServiceSection(root: SimplePanel) {
    // Defensive only: the route already requires an authenticated session (`requireAuth`), so
    // `AppState.session` is always non-null by the time this screen renders -- see Routing.kt.
    val myMemberId = AppState.session?.memberId ?: return

    root.h2("Auskunft") { addCssClass("h4") }
    root.div(
        "Übersicht, wie viele Datensätze in welchem Bereich zu Ihrer Person gespeichert sind, sowie " +
            "ein vollständiger, maschinenlesbarer Export Ihrer Daten.",
    ) { addCssClasses("text-muted small") }
    val manifestPanel = root.vPanel(spacing = 4)
    val manifestButton = root.button("Auskunftsübersicht anzeigen", style = ButtonStyle.OUTLINEPRIMARY)
    manifestButton.onClick {
        manifestPanel.removeAll()
        manifestPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val manifest = guarded { rpcService<IDsgvoService>().exportManifest(myMemberId) }
            manifestPanel.removeAll()
            if (manifest != null) renderExportManifest(manifestPanel, manifest)
        }
    }
    root.link("Vollständigen Datenexport herunterladen (JSON)", url = dsgvoExportUrl(myMemberId), target = "_blank")

    root.h2("Löschung beantragen") { addCssClass("h4") }
    val formHolder = root.vPanel(spacing = 6)
    val statusPanel = root.vPanel(spacing = 6)
    renderErasureRequestForm(formHolder, myMemberId) { request -> renderOwnErasureStatusCard(statusPanel, request) }
}

private fun renderExportManifest(
    panel: SimplePanel,
    manifest: ExportManifestDto,
) {
    panel.div("Stand: ${manifest.generatedAt}") { addCssClasses("text-muted small") }
    if (manifest.sectionCounts.isEmpty()) {
        panel.div("Keine Daten in den registrierten Bereichen gefunden.") { addCssClasses("text-muted small") }
        return
    }
    manifest.sectionCounts.entries.sortedBy { it.key }.forEach { (section, count) ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("small") }
        row.div(section) { addCssClasses("flex-grow-1") }
        row.div(count.toString())
    }
}

/**
 * D5: erasure-mode disclosure as a `select` + a reactive caption beneath it that updates on
 * `.subscribe { }`, per [LedgerScreen]'s D11 reactive-gating idiom -- this is the FIRST of the
 * three times this workflow shows the mode's plain-language consequence (request time; read-only
 * again in the ADMIN queue row; a third and final time in [executeErasureConfirmDialog] at the
 * point of irrevocable commitment).
 */
private fun renderErasureRequestForm(
    root: SimplePanel,
    myMemberId: String,
    onRequested: (ErasureRequestDto) -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }

    val modeOptions = ErasureMode.entries.map { it.name to erasureModeLabel(it) }
    val modeSelect = panel.select(options = modeOptions, value = ErasureMode.ANONYMIZE.name, label = "Art der Löschung")
    val modeCaption = panel.div(erasureModeDescription(ErasureMode.ANONYMIZE)) { addCssClasses("text-muted small") }
    modeSelect.subscribe { value ->
        modeCaption.content = value?.let { erasureModeDescription(ErasureMode.valueOf(it)) } ?: ""
    }

    val reasonInput = panel.textArea(label = "Begründung", rows = 2)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val submitButton = panel.button("Löschung beantragen", style = ButtonStyle.PRIMARY)
    // D10(b): permanent, always visible directly under the submit button -- not conditional on
    // whether a request was just made this visit.
    panel.div(ERASURE_SELF_STATUS_VISIBILITY_CAPTION) { addCssClasses("text-muted small") }

    submitButton.onClick {
        errorBox.hide()
        val reason = reasonInput.value.orEmpty().trim()
        val modeValue = modeSelect.value

        if (!Validation.isNonBlank(reason) || modeValue == null) {
            errorBox.content = "Bitte eine Begründung angeben."
            errorBox.show()
            return@onClick
        }

        submitButton.disabled = true
        AppScope.launch {
            val result =
                guarded { rpcService<IDsgvoService>().requestErasure(myMemberId, reason, ErasureMode.valueOf(modeValue)) }
            submitButton.disabled = false
            if (result != null) {
                notifySuccess("Löschung wurde beantragt.")
                reasonInput.value = null
                onRequested(result)
            }
        }
    }
}

private fun renderOwnErasureStatusCard(
    panel: SimplePanel,
    request: ErasureRequestDto,
) {
    panel.removeAll()
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    card.div("Ihr Löschantrag") { addCssClass("fw-bold") }
    card.erasureStepTracker(request)
    card.div("Gewählter Modus: ${erasureModeLabel(request.mode)}") { addCssClasses("small") }
    card.div(erasureModeDescription(request.mode)) { addCssClasses("text-muted small") }
    card.div("Begründung: ${request.reason}") { addCssClasses("text-muted small") }
}

// ================================================================================================
// ADMIN tier -- "Anträge verwalten": listErasureRequests / decideErasure / executeErasure
// ================================================================================================

private fun renderAdminQueueSection(root: SimplePanel) {
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusOptions = listOf("" to "Alle Status") + ErasureStatus.entries.map { it.name to erasureStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = "Status")
    val filterButton = filterRow.button("Filtern", style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 8)

    fun refreshList() {
        listPanel.removeAll()
        val status = parseOptionalEnum<ErasureStatus>(statusSelect.value)
        AppScope.launch {
            val requests = guarded { rpcService<IDsgvoService>().listErasureRequests(status) } ?: return@launch
            if (requests.isEmpty()) {
                listPanel.p("Keine Anträge für diese Filter gefunden.")
                return@launch
            }
            requests.forEach { request -> renderAdminRequestRow(listPanel, request, ::refreshList) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()
}

private fun renderAdminRequestRow(
    panel: SimplePanel,
    request: ErasureRequestDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(request.subjectDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(erasureModeLabel(request.mode), erasureModeColor(request.mode))

    row.erasureStepTracker(request)
    row.div("Begründung: ${request.reason}") { addCssClasses("small") }
    // D5: the same plain-language consequence line the requesting member saw, read-only, verbatim
    // -- a deciding ADMIN sees exactly what the requester saw, not a re-paraphrased version.
    row.div(erasureModeDescription(request.mode)) { addCssClasses("text-muted small") }
    row.legalHoldIndicator(request.legalHold)

    if (request.status == ErasureStatus.REQUESTED) {
        renderDecidePanel(row, request, onChanged)
    }
    if (request.status == ErasureStatus.APPROVED) {
        val executeButton = row.button("Endgültig löschen", style = ButtonStyle.DANGER)
        executeButton.onClick {
            executeErasureConfirmDialog(request) {
                executeButton.disabled = true
                AppScope.launch {
                    val result = guarded { rpcService<IDsgvoService>().executeErasure(request.id) }
                    executeButton.disabled = false
                    if (result != null) {
                        notifySuccess("Löschung wurde ausgeführt.")
                        onChanged()
                    }
                }
            }
        }
    }
}

private fun renderDecidePanel(
    row: SimplePanel,
    request: ErasureRequestDto,
    onDecided: () -> Unit,
) {
    val decidePanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = "Entscheidungsnotiz (optional)", rows = 2)
    val buttonsRow = decidePanel.hPanel(spacing = 8)
    val approveButton = buttonsRow.button("Genehmigen", style = ButtonStyle.SUCCESS)
    val rejectButton = buttonsRow.button("Ablehnen", style = ButtonStyle.OUTLINEDANGER)

    fun decide(approve: Boolean) {
        approveButton.disabled = true
        rejectButton.disabled = true
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }
        AppScope.launch {
            val result = guarded { rpcService<IDsgvoService>().decideErasure(request.id, approve, note) }
            approveButton.disabled = false
            rejectButton.disabled = false
            if (result != null) {
                notifySuccess(if (approve) "Antrag wurde genehmigt." else "Antrag wurde abgelehnt.")
                onDecided()
            }
        }
    }
    approveButton.onClick { decide(true) }
    rejectButton.onClick { decide(false) }
}

/**
 * `executeErasure`'s irreversibility -- matches or exceeds Backup-restore's D3(b) bar, per the
 * task's own explicit requirement. Every line of copy below is the design review's exact wording;
 * the mode's plain-language consequence line is repeated here a third and final time (D5), right at
 * the point of irrevocable commitment (Rams: "as little as possible, but not less than needed").
 */
private fun executeErasureConfirmDialog(
    request: ErasureRequestDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Löschung endgültig ausführen")
    modal.div("Diese Löschung ist ENDGÜLTIG und kann nicht rückgängig gemacht werden.") { addCssClasses("fw-bold text-danger") }
    modal.div("Betroffenes Mitglied: ${request.subjectDisplayName}")
    modal.div("Gewählter Modus: ${erasureModeLabel(request.mode)}") { addCssClasses("fw-bold mt-1") }
    modal.div(erasureModeDescription(request.mode))
    request.decisionNote?.takeIf { it.isNotBlank() }?.let { note ->
        modal.div("Entscheidungsnotiz: $note") { addCssClasses("small text-muted mt-1") }
    }

    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Endgültig löschen", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * D6: read-only, always rendered -- `false` (today's only real value, see [ErasureRequestDto]
 * KDoc/`ErasureRequestTable.legalHold` column: no RPC method anywhere ever sets this to `true`)
 * shown quietly, no badge; `true` would render as a blocking banner. This branch is dead until a
 * future wave adds a `setLegalHold`-shaped method -- do not delete it as unreachable, it is a
 * deliberate guard so the code path exists and is correct even though it cannot fire today.
 */
private fun SimplePanel.legalHoldIndicator(legalHold: Boolean) {
    if (legalHold) {
        div("Legal Hold aktiv -- Löschung darf nicht ausgeführt werden.") { addCssClasses("alert alert-danger") }
    } else {
        div("Kein Legal Hold") { addCssClasses("text-muted small") }
    }
}

// ================================================================================================
// D4: three-step workflow visualization -- shared by the self-service status card and every ADMIN
// queue row, so a member and an ADMIN looking at the same request see the identical tracker.
// ================================================================================================

private fun SimplePanel.erasureStepTracker(request: ErasureRequestDto) {
    val states = erasureStepStates(request.status)
    val pillRow = hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }

    fun pill(
        label: String,
        state: ErasureStepState,
        color: String,
    ) {
        if (state == ErasureStepState.FUTURE) pillRow.typeBadge(label, "secondary") else pillRow.statusBadge(label, color)
    }
    pill("Beantragt", states[0], "secondary")
    pill(erasureStep2Label(request.status), states[1], erasureStep2Color(request.status))
    pill("Ausgeführt", states[2], "danger")

    val captionPanel = vPanel(spacing = 2) { addCssClasses("small text-muted") }
    captionPanel.div(erasureRequestedCaption(request))
    erasureDecidedCaption(request)?.let { captionPanel.div(it) }
    erasureExecutedCaption(request)?.let { captionPanel.div(it) }
}

// ================================================================================================
// DSGVO-specific audit trail (ADMIN only) -- rein informativ, keine Aktionen auf dieser Liste
// ================================================================================================

private fun renderDsgvoAuditLogSection(root: SimplePanel) {
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val subjectInput = filterRow.text(label = "Betroffenes Mitglied (ID, optional)")
    val filterButton = filterRow.button("Filtern", style = ButtonStyle.OUTLINESECONDARY)
    val listPanel = root.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        val subjectId = subjectInput.value?.trim()?.takeIf { it.isNotBlank() }
        AppScope.launch {
            val entries = guarded { rpcService<IDsgvoService>().listAuditLog(subjectId) } ?: return@launch
            if (entries.isEmpty()) {
                listPanel.p("Keine Einträge gefunden.")
                return@launch
            }
            entries.forEach { entry -> renderDsgvoAuditLogRow(listPanel, entry) }
        }
    }
    filterButton.onClick { refreshList() }
    refreshList()
}

private fun renderDsgvoAuditLogRow(
    panel: SimplePanel,
    entry: DsgvoAuditLogEntryDto,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.statusBadge(dsgvoAuditActionLabel(entry.action), dsgvoAuditActionColor(entry.action))
    headerRow.div(entry.occurredAt.toString()) { addCssClasses("flex-grow-1 text-muted small") }

    row.div("Akteur: ${dsgvoAuditActorDisplayText(entry)} · Betroffenes Mitglied: ${entry.subjectMemberId}") {
        addCssClasses("text-muted small")
    }
    entry.legalBasis?.let { row.div("Rechtsgrundlage: $it") { addCssClasses("text-muted small") } }
    if (entry.outcome.isNotEmpty()) {
        row.div("Ergebnis:") { addCssClasses("small mt-1") }
        entry.outcome.forEach { outcome ->
            row.div(
                "${outcome.table}: ${outcome.rowsAnonymized} anonymisiert, ${outcome.rowsDeleted} gelöscht, " +
                    "${outcome.rowsRetained} beibehalten" + (outcome.retentionReason?.let { " ($it)" } ?: ""),
            ) { addCssClasses("small text-muted") }
        }
    }
}

/** No display-name resolution exists for [DsgvoAuditLogEntryDto.actorMemberId] server-side (unlike
 * `AuditLogEntryDto.actorMemberDisplayName` on the GoBD ledger audit log) -- shown as the raw
 * member ID, honestly, rather than fabricating a lookup this wave's scope does not cover. */
fun dsgvoAuditActorDisplayText(entry: DsgvoAuditLogEntryDto): String {
    val actorId = entry.actorMemberId ?: return "Systemvorgang (kein Akteur hinterlegt)"
    return if (entry.actorRole != null) "$actorId (${entry.actorRole})" else actorId
}

// ================================================================================================
// Pure helpers -- covered by DsgvoRightsScreenTest.kt
// ================================================================================================

fun dsgvoExportUrl(memberId: String): String = "/api/dsgvo/members/$memberId/export"

/** D10(b)'s exact permanent caption -- honest about the un-fetchable prior-request-status gap
 * rather than silently losing the status card on a reload. */
const val ERASURE_SELF_STATUS_VISIBILITY_CAPTION =
    "Der Status eines Antrags ist nur für die aktuelle Sitzung sichtbar. Für den Stand eines " +
        "früheren Antrags wenden Sie sich bitte an eine Administratorin oder einen Administrator."

/** D4: which of the three tracker pills is filled-solid ("PAST"/"CURRENT", rendered via
 * [statusBadge]) vs. outlined-and-greyed ("FUTURE", rendered via [typeBadge]). [ErasureStatus.REJECTED]
 * is a terminal state with no execution step -- its third pill still renders, but stays FUTURE
 * forever, which reads correctly as "never reached" without needing a fourth, rejection-specific
 * pill shape. */
enum class ErasureStepState { PAST, CURRENT, FUTURE }

fun erasureStepStates(status: ErasureStatus): List<ErasureStepState> =
    when (status) {
        ErasureStatus.REQUESTED -> listOf(ErasureStepState.CURRENT, ErasureStepState.FUTURE, ErasureStepState.FUTURE)
        ErasureStatus.APPROVED -> listOf(ErasureStepState.PAST, ErasureStepState.CURRENT, ErasureStepState.FUTURE)
        ErasureStatus.REJECTED -> listOf(ErasureStepState.PAST, ErasureStepState.CURRENT, ErasureStepState.FUTURE)
        ErasureStatus.COMPLETED -> listOf(ErasureStepState.PAST, ErasureStepState.PAST, ErasureStepState.CURRENT)
    }

/** The second pill's label changes once a decision exists -- "Entschieden" (still pending) vs. the
 * actual verdict ("Genehmigt"/"Abgelehnt"), never the raw enum name. */
fun erasureStep2Label(status: ErasureStatus): String =
    when (status) {
        ErasureStatus.REQUESTED -> "Entschieden"
        ErasureStatus.APPROVED, ErasureStatus.COMPLETED -> "Genehmigt"
        ErasureStatus.REJECTED -> "Abgelehnt"
    }

/** Mirrors [erasureStatusColor]'s own scale for the equivalent status -- only used while the pill is
 * PAST/CURRENT (i.e. never for [ErasureStatus.REQUESTED], where the second pill is always FUTURE and
 * rendered in outline/grey regardless of this color). */
fun erasureStep2Color(status: ErasureStatus): String =
    when (status) {
        ErasureStatus.REQUESTED -> "secondary"
        ErasureStatus.APPROVED, ErasureStatus.COMPLETED -> "warning"
        ErasureStatus.REJECTED -> "dark"
    }

/**
 * [ErasureRequestDto.requestedBy] is a raw member-ID string, not a display name (only
 * [ErasureRequestDto.subjectDisplayName] is resolved server-side) -- deliberate deviation from the
 * design review's literal wording (which compared `subjectDisplayName == requestedBy`, two values of
 * different shape that can never actually be equal): the real, type-correct comparison is
 * `requestedBy == subjectMemberId`, both UUID strings. When they differ (an ADMIN requested on the
 * member's behalf), the raw ID is shown rather than a fabricated display-name lookup this wave's
 * scope does not cover -- same honesty posture as [dsgvoAuditActorDisplayText].
 */
fun erasureRequestedByDisplayText(request: ErasureRequestDto): String =
    if (request.requestedBy == request.subjectMemberId) "Mitglied selbst" else request.requestedBy

fun erasureRequestedCaption(request: ErasureRequestDto): String =
    "Beantragt von ${erasureRequestedByDisplayText(request)} am ${request.requestedAt}"

fun erasureDecidedCaption(request: ErasureRequestDto): String? {
    val decidedBy = request.decidedBy ?: return null
    val decidedAt = request.decidedAt ?: return null
    val notePart =
        request.decisionNote
            ?.takeIf { it.isNotBlank() }
            ?.let { ": $it" }
            .orEmpty()
    return "Entschieden von $decidedBy am $decidedAt$notePart"
}

/** [ErasureRequestDto] has no `executedBy` field (only [ErasureRequestDto.executedAt]) --
 * deliberate deviation from the design review's literal wording ("Ausgeführt von ${executedAt}",
 * which uses a timestamp as if it were an actor); `executeErasure` is ADMIN-only by role but the DTO
 * does not record which specific ADMIN pressed the button, so this caption says "am", not "von". */
fun erasureExecutedCaption(request: ErasureRequestDto): String? = request.executedAt?.let { "Ausgeführt am $it" }
