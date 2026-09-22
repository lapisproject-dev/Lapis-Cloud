package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
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
 *
 * **Kartenliste, keine Tabelle (Welle V1.4.27, W3 -- Richtlinie P3, ein Objekt hat nie beide Grammatiken):**
 * Kriterium: braucht eine Zeile ein Freitextfeld oder trägt sie eine Entscheidung mit Begründung, ist sie eine
 * Karte. Jede Karte dieser Warteschlange trägt ein Pflicht-Textfeld "Entscheidungsnotiz" und zwei
 * Entscheidungsknöpfe -- das ist ein Formular pro Antrag, keine Zeile eines Rasters. Deshalb stehen die Karten in
 * `.lapis-card-list` / `.lapis-data-card` (dieselben Klassen, die auch die Kartenliste von `dataTable` unter
 * 768 px nutzt) und nicht in einer `dataTable`. Die Entscheidungspanels selbst sind Formulare (W4).
 */
fun renderContributionReliefQueueScreen(container: SimplePanel) {
    val currentMemberId = AppState.session?.memberId
    val root = container.dataScreenRoot(spacing = 14)
    root.pageHeader(tr("Beitragsvergünstigungen"))

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val statusOptions = listOf("" to tr("Alle Status")) + ContributionReliefStatus.entries.map { it.name to reliefStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val kindOptions = listOf("" to tr("Alle Arten")) + ContributionReliefKind.entries.map { it.name to reliefKindLabel(it) }
    val kindSelect = filterRow.select(options = kindOptions, value = "", label = tr("Art"))
    val reviewDueOnlyCheck = filterRow.checkBox(label = tr("Nur zur Wiedervorlage fällig"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.simplePanel { addCssClass("lapis-card-list") }
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
                }
            if (page == null) {
                // Welle V1.4.27 (W3): a failed first page is an error state with a retry, not an empty list.
                if (reset) listPanel.dataErrorState(onRetry = { loadPage(reset = true) })
                return@launch
            }
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
    val card = panel.vPanel(spacing = 6) { addCssClass("lapis-data-card") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(reliefKindLabel(request.kind), reliefKindColor(request.kind))
    headerRow.untrustedCardTitle(request.subjectDisplayName)
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

internal fun renderReliefRequestedDecidePanel(
    card: SimplePanel,
    request: ContributionReliefRequestDto,
    onChanged: () -> Unit,
) {
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    // Formular-Grammatik (V1.4.29, W4b): ein einziges Pflichtfeld (Entscheidungsnotiz) => Stern + Legende "* Pflichtfeld" (ein einzelnes
    // Pflichtfeld ist nie "Fall (c)"; sonst wäre die Pflicht nicht erkennbar). "Genehmigen" ist die Primäraktion,
    // "Ablehnen" steht in der Gefahrenzone unter der Knopfzeile (Richtlinie 2.5).
    val form = decidePanel.lapisForm()
    val noteField = reliefDecisionNoteField(form)
    val approveButton = Button(tr("Genehmigen und ausführen"), style = ButtonStyle.SUCCESS)
    val rejectButton = Button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = approveButton, destructive = rejectButton)

    fun decide(
        approve: Boolean,
        pressed: Button,
        other: Button,
    ) {
        form.submit(pressed) {
            other.disabled = true
            try {
                val note = noteField.value.trim()
                val result = guarded { rpcService<IContributionReliefService>().decideReliefRequest(request.id, approve, note) }
                if (result != null) {
                    notifySuccess(if (approve) tr("Antrag genehmigt und ausgeführt.") else tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                other.disabled = false
            }
        }
    }
    approveButton.onClick { decide(true, approveButton, rejectButton) }
    rejectButton.onClick { decide(false, rejectButton, approveButton) }
}

/** Die Pflicht-Notiz einer Entscheidung: die Serverregel (`chk_crr_approved_needs_note`) bleibt Autorität. */
private fun reliefDecisionNoteField(
    form: LapisForm,
    hint: String? = null,
): LapisField =
    form.textAreaField(
        label = tr("Entscheidungsnotiz"),
        rows = 2,
        required = true,
        hint = hint,
        requiredMessage = gettext("Bitte eine Entscheidungsnotiz eingeben."),
        init = { it.maxlength = 1000 },
    )

/** F1: der einzige Kartenzustand mit zwei Buttons statt einem Genehmigen/Ablehnen-Paar. */
internal fun renderReliefApprovedRetryPanel(
    card: SimplePanel,
    request: ContributionReliefRequestDto,
    onChanged: () -> Unit,
) {
    card.div(reliefExecutionErrorMessage(request.executionError)) { addCssClasses("alert alert-danger") }
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val form = decidePanel.lapisForm()
    // Der Stern gilt nur für "Ablehnen": ohne den Hinweis läse sich das Feld, als brauchte auch die Wiederholung eine Notiz.
    val noteField = reliefDecisionNoteField(form, hint = gettext("Nur für \"Ablehnen\" erforderlich."))
    val retryButton = Button(tr("Ausführung wiederholen"), style = ButtonStyle.PRIMARY)
    val rejectButton = Button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = retryButton, destructive = rejectButton)

    // Die Wiederholung braucht keine Notiz: sie läuft OHNE Prüfung (`runBusy`), nur "Ablehnen" prüft das Pflichtfeld.
    retryButton.onClick {
        rejectButton.disabled = true
        form.runBusy(retryButton) {
            try {
                val result = guarded { rpcService<IContributionReliefService>().retryReliefExecution(request.id) }
                if (result != null) {
                    notifySuccess(tr("Ausführung wiederholt."))
                    onChanged()
                }
            } finally {
                rejectButton.disabled = false
            }
        }
    }
    rejectButton.onClick {
        form.submit(rejectButton) {
            retryButton.disabled = true
            try {
                val result =
                    guarded { rpcService<IContributionReliefService>().decideReliefRequest(request.id, false, noteField.value.trim()) }
                if (result != null) {
                    notifySuccess(tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                retryButton.disabled = false
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
