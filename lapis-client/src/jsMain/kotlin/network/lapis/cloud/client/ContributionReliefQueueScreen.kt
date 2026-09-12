package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.rpc.IContributionReliefService
import network.lapis.cloud.shared.rpc.IContributionService

/**
 * Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche", Punkt B -- die BOARD/ADMIN-
 * Vorstands-Warteschlange, Struktur 1:1 nach `SocialModerationScreen.renderReportQueueSection`/
 * `AuditLogScreen`s Keyset-Pagination-Vorbild. Route-Gate ist BOARD/ADMIN, **nicht** TREASURER --
 * siehe `Routes.CONTRIBUTION_RELIEF` KDoc, verifiziert gegen
 * [IContributionReliefService.listReliefRequests]'s eigenen Rollen-Check (anders als fast jede
 * andere FINANCE-Route).
 *
 * **Vier-Augen-Prinzip client-seitig sichtbar** ([reliefDecisionBlockedBySelf]): eine Karte, deren
 * `subjectMemberId` dem eingeloggten Vorstandsmitglied entspricht, zeigt gar kein Entscheidungs-Panel
 * -- der Klick würde ohnehin serverseitig mit [network.lapis.cloud.shared.rpc.ForbiddenException]
 * scheitern (`decideReliefRequest`s eigener Vier-Augen-Check), aber ein sichtbarer, aktiver Knopf,
 * der garantiert fehlschlägt, ist schlechter als gar kein Knopf (Forstall-Konvention, gleiche
 * Haltung wie `MemberPasswordResetDialog.passwordResetBlockReason`).
 *
 * **F1-Korrektur** (siehe Plan Abschnitt 1/5): der "Ausführung wiederholen"-Knopf hängt an
 * `status == APPROVED && executionError != null`, NICHT an `EXECUTED` -- ein
 * [ContributionReliefStatus.EXECUTED]-Antrag hat laut DB-CHECK (`chk_crr_execution_error_state`)
 * niemals einen gesetzten `executionError`, ein `when`/`if` über `EXECUTED` würde also niemals
 * feuern.
 */
fun renderContributionReliefQueueScreen(container: SimplePanel) {
    val currentMemberId = AppState.session?.memberId
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Beitragsvergünstigungen"))

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val statusOptions = listOf("" to tr("Alle Status")) + ContributionReliefStatus.entries.map { it.name to reliefStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val kindOptions = listOf("" to tr("Alle Arten")) + ContributionReliefKind.entries.map { it.name to reliefKindLabel(it) }
    val kindSelect = filterRow.select(options = kindOptions, value = "", label = tr("Art"))
    val reviewDueOnlyCheck = filterRow.checkBox(label = tr("Nur zur Wiedervorlage fällig"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 8)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    // DoS-Prüfliste "Kein N+1" (Plan Abschnitt 2.3/4): EIN `listMembershipTiers()`-Aufruf für den
    // gesamten Screen-Aufbau, nie eine Tier-Karte pro Zeile.
    var tierAmountLabels: Map<String, String> = emptyMap()

    var cursor: ReliefPageCursor? = null

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            cursor = null
        }
        val status = parseOptionalEnum<ContributionReliefStatus>(statusSelect.value)
        val kind = parseOptionalEnum<ContributionReliefKind>(kindSelect.value)
        val reviewDueOnly = reviewDueOnlyCheck.value
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IContributionReliefService>().listReliefRequests(
                        status = status,
                        kind = kind,
                        reviewDueOnly = reviewDueOnly,
                        afterRequestedAt = cursor?.requestedAt,
                        afterId = cursor?.id,
                    )
                } ?: return@launch
            if (page.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Anträge für diese Filter gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            page.forEach { request ->
                renderReliefRequestCard(listPanel, request, tierAmountLabels, currentMemberId) { loadPage(reset = true) }
            }
            cursor = nextReliefCursor(page)
            if (reliefHasMorePages(page.size)) loadMoreButton.show() else loadMoreButton.hide()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }

    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() }.orEmpty()
        tierAmountLabels = tiers.associate { it.id to tierOptionLabel(it) }
        loadPage(reset = true)
    }
}

private fun renderReliefRequestCard(
    panel: SimplePanel,
    request: ContributionReliefRequestDto,
    tierAmountLabels: Map<String, String>,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(reliefKindLabel(request.kind), reliefKindColor(request.kind))
    headerRow.div(request.subjectDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    if (request.requestedBy != request.subjectMemberId) {
        headerRow.typeBadge(gettext("Im Namen von %1 gestellt", request.requestedByDisplayName), "secondary")
    }

    card.reliefStepTracker(request)
    val tierAmountLabel = if (request.kind == ContributionReliefKind.REDUCTION) tierAmountLabels[request.reductionTargetTierId] else null
    card.div(reliefEffectDescription(request, tierAmountLabel)) { addCssClasses("small") }
    card.div(reliefReasonLabel(request.reasonCategory)) { addCssClasses("text-muted small") }
    if (request.reasonText != null) {
        card.div(gettext("Erläuterung: %1", request.reasonText)) { addCssClasses("text-muted small") }
    } else if (request.reasonRedactedAt != null) {
        card.div(gettext("Begründung am %1 automatisch gelöscht (12-Monats-Frist).", request.reasonRedactedAt)) {
            addCssClasses("text-muted small")
        }
    }
    request.reviewDueOn?.let { card.div(gettext("Wiedervorlage am %1", it)) { addCssClasses("text-muted small") } }

    // Bewusst unabhängig vom Status (Plan Abschnitt 2.3): auch eine bereits EXECUTED/REQUESTED/
    // APPROVED EXEMPTION-Karte bekommt diesen Hinweis -- ohne Zahl (Jobs' Ruling), reiner Verweis.
    if (request.kind == ContributionReliefKind.EXEMPTION) {
        val exemptionHintRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        exemptionHintRow.div(tr("Bereits entstandene offene Beiträge bleiben bestehen und müssen ggf. einzeln erlassen werden.")) {
            addCssClasses("text-muted small")
        }
        exemptionHintRow.link(tr("Zur Beitragsübersicht"), url = "#${Routes.CONTRIBUTIONS}")
    }

    if (reliefDecisionBlockedBySelf(request, currentMemberId)) {
        card.div(tr("Eigener Antrag — Entscheidung durch ein anderes Vorstandsmitglied.")) { addCssClasses("text-muted small fst-italic") }
        return
    }

    when (request.status) {
        ContributionReliefStatus.REQUESTED -> renderReliefRequestedDecidePanel(card, request, onChanged)
        // F1: der Retry-Pfad hängt an APPROVED+executionError, NICHT an EXECUTED (siehe Datei-KDoc).
        ContributionReliefStatus.APPROVED -> renderReliefApprovedRetryPanel(card, request, onChanged)
        ContributionReliefStatus.REJECTED, ContributionReliefStatus.EXECUTED, ContributionReliefStatus.WITHDRAWN -> Unit
    }
}

private fun renderReliefRequestedDecidePanel(
    card: SimplePanel,
    request: ContributionReliefRequestDto,
    onChanged: () -> Unit,
) {
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = tr("Entscheidungsnotiz (Pflicht)"), rows = 2) { maxlength = 1000 }
    val errorBox =
        decidePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonsRow = decidePanel.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val approveButton = buttonsRow.button(tr("Genehmigen und ausführen"), style = ButtonStyle.SUCCESS)
    val rejectButton = buttonsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

    fun decide(approve: Boolean) {
        errorBox.hide()
        val note = noteInput.value?.trim()
        if (!reliefDecisionNoteIsValid(note)) {
            errorBox.content = tr("Bitte eine Entscheidungsnotiz eingeben.")
            errorBox.show()
            return
        }
        approveButton.disabled = true
        rejectButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IContributionReliefService>().decideReliefRequest(request.id, approve, note) }
                if (result != null) {
                    notifySuccess(if (approve) tr("Antrag genehmigt und ausgeführt.") else tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                approveButton.disabled = false
                rejectButton.disabled = false
            }
        }
    }
    approveButton.onClick { decide(true) }
    rejectButton.onClick { decide(false) }
}

/** F1: der einzige Kartenzustand mit zwei Buttons statt einem Genehmigen/Ablehnen-Paar. */
private fun renderReliefApprovedRetryPanel(
    card: SimplePanel,
    request: ContributionReliefRequestDto,
    onChanged: () -> Unit,
) {
    card.div(reliefExecutionErrorMessage(request.executionError)) { addCssClasses("alert alert-danger") }
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = tr("Entscheidungsnotiz (Pflicht)"), rows = 2) { maxlength = 1000 }
    val errorBox =
        decidePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonsRow = decidePanel.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val retryButton = buttonsRow.button(tr("Ausführung wiederholen"), style = ButtonStyle.PRIMARY)
    val rejectButton = buttonsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

    retryButton.onClick {
        retryButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IContributionReliefService>().retryReliefExecution(request.id) }
                if (result != null) {
                    notifySuccess(tr("Ausführung wiederholt."))
                    onChanged()
                }
            } finally {
                retryButton.disabled = false
            }
        }
    }
    rejectButton.onClick {
        errorBox.hide()
        val note = noteInput.value?.trim()
        if (!reliefDecisionNoteIsValid(note)) {
            errorBox.content = tr("Bitte eine Entscheidungsnotiz eingeben.")
            errorBox.show()
            return@onClick
        }
        retryButton.disabled = true
        rejectButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IContributionReliefService>().decideReliefRequest(request.id, false, note) }
                if (result != null) {
                    notifySuccess(tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                retryButton.disabled = false
                rejectButton.disabled = false
            }
        }
    }
}

/**
 * Server-seitige Seitengröße (`ContributionReliefService.MAX_LIST_RESULTS`) -- NICHT client-
 * konfigurierbar, hier nur für die "gibt es wahrscheinlich eine weitere Seite"-Heuristik gespiegelt,
 * gleiche Rolle wie `SocialModerationScreen.REPORT_PAGE_SIZE`/`AuditLogScreen.AUDIT_LOG_PAGE_SIZE`.
 */
private const val RELIEF_PAGE_SIZE = 200

/** Pure -- see `ContributionReliefQueueScreenTest`. */
internal fun reliefHasMorePages(
    pageSize: Int,
    capacity: Int = RELIEF_PAGE_SIZE,
): Boolean = pageSize >= capacity

/** Pure -- see `ContributionReliefQueueScreenTest`. Composite keyset cursor, ASC-Sortierung (ältestes zuerst). */
internal data class ReliefPageCursor(
    val requestedAt: LocalDateTime,
    val id: String,
)

/** Pure -- see `ContributionReliefQueueScreenTest`. `null` for an empty page (no further cursor to advance). */
internal fun nextReliefCursor(page: List<ContributionReliefRequestDto>): ReliefPageCursor? =
    page.lastOrNull()?.let { ReliefPageCursor(requestedAt = it.requestedAt, id = it.id) }

/**
 * Pure -- see `ContributionReliefQueueScreenTest`. Vier-Augen-Prädikat, client-seitige Spiegelung
 * von `ContributionReliefService.decideReliefRequest`s `current.memberId == subjectId ->
 * ForbiddenException`-Check.
 */
internal fun reliefDecisionBlockedBySelf(
    request: ContributionReliefRequestDto,
    currentMemberId: String?,
): Boolean = request.subjectMemberId == currentMemberId

/**
 * Pure -- see `ContributionReliefQueueScreenTest`. Client-seitige UX-Vorprüfung, ersetzt nie die
 * Server-Pflicht (`chk_crr_approved_needs_note`).
 */
internal fun reliefDecisionNoteIsValid(note: String?): Boolean = !note.isNullOrBlank()
