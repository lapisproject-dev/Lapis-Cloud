package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SocialPostErasureDto
import network.lapis.cloud.shared.domain.SocialPostErasureStatus
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostReportDto
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import network.lapis.cloud.shared.rpc.ISocialNetworkService

/**
 * Welle V1.1.5 -- neuer Screen, ausschließlich für die BOARD/ADMIN-Moderationswarteschlangen dieser
 * Welle: DSA-Art.-16-Meldungen ([ISocialNetworkService.listReports]/`.decideReport`) und
 * post-bezogene DSGVO-Art.-17-Löschanträge ([ISocialNetworkService.listContentErasures]/
 * `.decideContentErasure`/`.executeContentErasure`). **Entscheidungspunkt E-E**: die
 * Löschantrags-Warteschlange lebt hier, NICHT als dritter Abschnitt in `DsgvoRightsScreen` --
 * eine Content-Löschung entsteht fast immer aus einer `PERSONAL_DATA`-Meldung, und der Kontext
 * (Post-Ausschnitt, Meldung) liegt hier bereits vor.
 *
 * Aufbau nach dem Vorbild [renderDsgvoRightsScreen] (`DsgvoRightsScreen.kt`): Statusfilter-`select`
 * + "Filtern" + `listPanel` + lokales `refreshList()` + status-bedingter Entscheidungs-Block, ein
 * hand-gebautes [Modal] für den irreversiblen "Inhalt endgültig entfernen"-Schritt (analog
 * `executeErasureConfirmDialog`).
 *
 * **Rollen-Split innerhalb des Screens** (verifiziert gegen `SocialNetworkService.kt`'s Rollen-
 * Gates): Meldungen (`listReports`/`decideReport`) sind BOARD ODER ADMIN;
 * Löschanträge (`listContentErasures`/`decideContentErasure`/`executeContentErasure`) sind ADMIN
 * ALLEIN (E-E) -- ein BOARD-Mitglied sieht deshalb nur den Meldungs-Abschnitt.
 */
fun renderSocialModerationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Moderation"))

    root.h2(tr("Meldungen"))
    renderReportQueueSection(root)

    if (AppState.hasRole(AccountRole.ADMIN)) {
        root.h2(tr("Beitrags-Löschanträge"))
        root.div(
            tr("Post-bezogene Löschanträge nach Art. 17 DSGVO -- Entscheidung und endgültige Ausführung sind ADMIN-only."),
        ) { addCssClasses("text-muted small") }
        renderErasureQueueSection(root)
    }
}

// ================================================================================================
// Meldungen (DSA Art. 16) -- BOARD oder ADMIN
// ================================================================================================

/**
 * Security-Audit-Fund MAJOR-2 (Runde 1, 2026-08-19): real server-side keyset pagination via
 * [ISocialNetworkService.listReports]'s `beforeReportedAt`/`beforeId` cursor -- the queue was
 * previously hard-capped at 200 rows with no way to page past that. "Mehr laden"/reset pattern
 * copied verbatim from `AuditLogScreen.renderAuditLogScreen`'s own keyset pagination (see that
 * function's KDoc "Pagination is real server-side keyset pagination"). Unlike
 * `AuditLogListQuery.limit`, the server-side page size here is NOT client-configurable --
 * `SocialNetworkService.MAX_MODERATION_PAGE_SIZE` is a fixed, private server constant -- so this
 * mirrors that exact number (200) purely for the "does a next page likely exist" heuristic below,
 * the same role `AUDIT_LOG_PAGE_SIZE` plays in `AuditLogScreen.kt`.
 */
private const val REPORT_PAGE_SIZE = 200

private fun renderReportQueueSection(root: SimplePanel) {
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusOptions = listOf("" to tr("Alle Status")) + SocialPostReportStatus.entries.map { it.name to socialPostReportStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 8)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var lastReportedAt: LocalDateTime? = null
    var lastId: String? = null

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            lastReportedAt = null
            lastId = null
        }
        val status = parseOptionalEnum<SocialPostReportStatus>(statusSelect.value)
        AppScope.launch {
            val reports =
                guarded {
                    rpcService<ISocialNetworkService>().listReports(
                        status = status,
                        beforeReportedAt = lastReportedAt,
                        beforeId = lastId,
                    )
                } ?: return@launch
            if (reports.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Meldungen für diese Filter gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            reports.forEach { report -> renderReportRow(listPanel, report) { loadPage(reset = true) } }
            lastReportedAt = reports.last().reportedAt
            lastId = reports.last().id
            if (reports.size < REPORT_PAGE_SIZE) loadMoreButton.hide() else loadMoreButton.show()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)
}

private fun renderReportRow(
    panel: SimplePanel,
    report: SocialPostReportDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(socialPostReportCategoryLabel(report.category), "secondary")
    headerRow.statusBadge(socialPostReportStatusLabel(report.status), socialPostReportStatusColor(report.status))
    headerRow.div(gettext("gemeldet am %1", report.reportedAt)) { addCssClasses("flex-grow-1 text-muted small") }

    row.div(gettext("Beitrag: %1", report.postExcerpt)) { addCssClasses("small") }
    row.div(gettext("Begründung: %1", report.description)) { addCssClasses("small") }
    report.reporterMemberId?.let { row.div(gettext("Gemeldet von: %1", it)) { addCssClasses("text-muted small") } }
        ?: row.div(tr("Anonyme öffentliche Meldung")) { addCssClasses("text-muted small") }
    // MINOR-4 (Security-Audit Runde 1, 2026-08-19): reporterContact is collected (and the public
    // report form promises it will be used to follow up with the reporter, Art. 16 Abs. 4/5 DSA)
    // but was previously invisible on this screen -- no code path could actually fulfil that
    // promise. Shown only when present -- absent for anonymous reports with no contact given.
    report.reporterContact?.takeIf { it.isNotBlank() }?.let {
        row.div(gettext("Kontakt für Rückmeldung: %1", it)) { addCssClasses("text-muted small") }
    }
    report.decisionNote?.takeIf { it.isNotBlank() }?.let {
        row.div(gettext("Entscheidungsnotiz: %1", it)) { addCssClasses("text-muted small") }
    }

    if (report.status == SocialPostReportStatus.OPEN || report.status == SocialPostReportStatus.UNDER_REVIEW) {
        renderReportDecidePanel(row, report, onChanged)
    }
}

private fun renderReportDecidePanel(
    row: SimplePanel,
    report: SocialPostReportDto,
    onChanged: () -> Unit,
) {
    val decidePanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = tr("Entscheidungsnotiz (optional, rein intern)"), rows = 2)
    val buttonsRow = decidePanel.hPanel(spacing = 8) { addCssClasses("flex-wrap") }

    fun decide(status: SocialPostReportStatus) {
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }
        AppScope.launch {
            val result = guarded { rpcService<ISocialNetworkService>().decideReport(report.id, status, note) }
            if (result != null) {
                notifySuccess(tr("Meldung aktualisiert."))
                onChanged()
            }
        }
    }
    if (report.status == SocialPostReportStatus.OPEN) {
        buttonsRow.button(tr("In Prüfung nehmen"), style = ButtonStyle.SUCCESS).onClick { decide(SocialPostReportStatus.UNDER_REVIEW) }
    }
    buttonsRow.button(tr("Abgelehnt"), style = ButtonStyle.OUTLINEDANGER).onClick { decide(SocialPostReportStatus.DISMISSED) }

    // "Beitrag entfernen" ruft removePostForLegalReason mit einer eigenen Pflicht-Begründung auf --
    // schließt die Meldung dabei automatisch (siehe removePostForLegalReason KDoc), ein separates
    // "ACTION_TAKEN" hier wäre redundant/inkonsistent.
    val removeButtonRow = decidePanel.hPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1 flex-wrap") }
    val reasonInput = removeButtonRow.textArea(label = tr("Begründung für Beitragsentfernung"), rows = 2)
    removeButtonRow.div(tr("Diese Begründung wird öffentlich sichtbar -- auch für nicht angemeldete Besucher.")) {
        addCssClasses("text-danger small fw-bold")
    }
    val removeButton = removeButtonRow.button(tr("Beitrag entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.onClick {
        val reason = reasonInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(reason)) {
            notifyError(tr("Bitte eine Begründung angeben."))
            return@onClick
        }
        confirmDialog(
            title = tr("Beitrag rechtlich entfernen"),
            message = tr("Diese Begründung wird öffentlich sichtbar -- auch für nicht angemeldete Besucher."),
            confirmLabel = tr("Entfernen"),
        ) {
            AppScope.launch {
                val result = guarded { rpcService<ISocialNetworkService>().removePostForLegalReason(report.postId, reason) }
                if (result != null) {
                    notifySuccess(tr("Beitrag entfernt."))
                    onChanged()
                }
            }
        }
    }
}

// ================================================================================================
// Post-bezogene DSGVO-Löschanträge (E-E: ADMIN allein)
// ================================================================================================

/** Security-Audit-Fund MAJOR-2 (Runde 1, 2026-08-19) -- see [REPORT_PAGE_SIZE] KDoc, same mirroring of `MAX_MODERATION_PAGE_SIZE`. */
private const val ERASURE_PAGE_SIZE = 200

private fun renderErasureQueueSection(root: SimplePanel) {
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusOptions =
        listOf("" to tr("Alle Status")) + SocialPostErasureStatus.entries.map { it.name to socialPostErasureStatusLabel(it) }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 8)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var lastRequestedAt: LocalDateTime? = null
    var lastId: String? = null

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            lastRequestedAt = null
            lastId = null
        }
        val status = parseOptionalEnum<SocialPostErasureStatus>(statusSelect.value)
        AppScope.launch {
            val erasures =
                guarded {
                    rpcService<ISocialNetworkService>().listContentErasures(
                        status = status,
                        beforeRequestedAt = lastRequestedAt,
                        beforeId = lastId,
                    )
                } ?: return@launch
            if (erasures.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Löschanträge für diese Filter gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            erasures.forEach { erasure -> renderErasureRow(listPanel, erasure) { loadPage(reset = true) } }
            lastRequestedAt = erasures.last().requestedAt
            lastId = erasures.last().id
            if (erasures.size < ERASURE_PAGE_SIZE) loadMoreButton.hide() else loadMoreButton.show()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)
}

private fun renderErasureRow(
    panel: SimplePanel,
    erasure: SocialPostErasureDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(socialPostErasureStatusLabel(erasure.status), socialPostErasureStatusColor(erasure.status))
    headerRow.div(gettext("beantragt am %1", erasure.requestedAt)) { addCssClasses("flex-grow-1 text-muted small") }

    row.div(gettext("Begründung: %1", erasure.reason)) { addCssClasses("small") }
    erasure.subjectMemberId?.let { row.div(gettext("Betroffene Person: %1", it)) { addCssClasses("text-muted small") } }
    erasure.decisionNote?.takeIf { it.isNotBlank() }?.let {
        row.div(gettext("Entscheidungsnotiz: %1", it)) { addCssClasses("text-muted small") }
    }

    if (erasure.status == SocialPostErasureStatus.REQUESTED) {
        renderErasureDecidePanel(row, erasure, onChanged)
    }
    if (erasure.status == SocialPostErasureStatus.APPROVED) {
        val executeButton = row.button(tr("Inhalt endgültig entfernen"), style = ButtonStyle.DANGER)
        executeButton.onClick {
            executeErasureConfirmDialog(erasure) {
                AppScope.launch {
                    val result = guarded { rpcService<ISocialNetworkService>().executeContentErasure(erasure.id) }
                    if (result != null) {
                        notifySuccess(tr("Inhalt wurde entfernt."))
                        onChanged()
                    }
                }
            }
        }
    }
}

private fun renderErasureDecidePanel(
    row: SimplePanel,
    erasure: SocialPostErasureDto,
    onChanged: () -> Unit,
) {
    val decidePanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = tr("Entscheidungsnotiz (optional)"), rows = 2)
    val buttonsRow = decidePanel.hPanel(spacing = 8)
    val approveButton = buttonsRow.button(tr("Genehmigen"), style = ButtonStyle.SUCCESS)
    val rejectButton = buttonsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

    fun decide(approve: Boolean) {
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }
        AppScope.launch {
            val result = guarded { rpcService<ISocialNetworkService>().decideContentErasure(erasure.id, approve, note) }
            if (result != null) {
                notifySuccess(if (approve) tr("Antrag genehmigt.") else tr("Antrag abgelehnt."))
                onChanged()
            }
        }
    }
    approveButton.onClick { decide(true) }
    rejectButton.onClick { decide(false) }
}

/** Irreversibel -- analog `DsgvoRightsScreen.executeErasureConfirmDialog`, inkl. Hinweis auf den manuellen Google-Search-Console-Schritt (Plan § 6.3). */
private fun executeErasureConfirmDialog(
    erasure: SocialPostErasureDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Inhalt endgültig entfernen"))
    modal.div(tr("Diese Löschung ist ENDGÜLTIG und kann nicht rückgängig gemacht werden.")) { addCssClasses("fw-bold text-danger") }
    modal.div(gettext("Begründung: %1", erasure.reason))
    modal.div(
        tr(
            "Nach der Löschung: prüfen Sie, ob die Beitrags-URL zusätzlich über die Google-Search-" +
                "Console als „Entfernung“ gemeldet werden sollte -- die Sitemap-Aktualisierung allein " +
                "garantiert keine schnelle Entfernung aus dem Suchmaschinen-Index.",
        ),
    ) { addCssClasses("small text-muted mt-1") }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Endgültig entfernen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Reine Label-/Farbtabellen -- exportiert für SocialModerationScreenTest
// ================================================================================================

fun socialPostReportCategoryLabel(category: SocialPostReportCategory): String =
    when (category) {
        SocialPostReportCategory.ILLEGAL_CONTENT -> gettext("Rechtswidriger Inhalt")
        SocialPostReportCategory.DEFAMATION -> gettext("Üble Nachrede/Verleumdung")
        SocialPostReportCategory.COPYRIGHT -> gettext("Urheberrechtsverletzung")
        SocialPostReportCategory.PERSONAL_DATA -> gettext("Personenbezogene Daten")
        SocialPostReportCategory.HATE_SPEECH -> gettext("Hassrede")
        SocialPostReportCategory.SPAM -> gettext("Spam")
        SocialPostReportCategory.OTHER -> gettext("Sonstiges")
    }

fun socialPostReportStatusLabel(status: SocialPostReportStatus): String =
    when (status) {
        SocialPostReportStatus.OPEN -> gettext("Offen")
        SocialPostReportStatus.UNDER_REVIEW -> gettext("In Prüfung")
        SocialPostReportStatus.ACTION_TAKEN -> gettext("Maßnahme ergriffen")
        SocialPostReportStatus.DISMISSED -> gettext("Abgelehnt")
    }

fun socialPostReportStatusColor(status: SocialPostReportStatus): String =
    when (status) {
        SocialPostReportStatus.OPEN -> "warning"
        SocialPostReportStatus.UNDER_REVIEW -> "info"
        SocialPostReportStatus.ACTION_TAKEN -> "danger"
        SocialPostReportStatus.DISMISSED -> "secondary"
    }

fun socialPostErasureStatusLabel(status: SocialPostErasureStatus): String =
    when (status) {
        SocialPostErasureStatus.REQUESTED -> gettext("Beantragt")
        SocialPostErasureStatus.APPROVED -> gettext("Genehmigt")
        SocialPostErasureStatus.REJECTED -> gettext("Abgelehnt")
        SocialPostErasureStatus.EXECUTED -> gettext("Ausgeführt")
    }

fun socialPostErasureStatusColor(status: SocialPostErasureStatus): String =
    when (status) {
        SocialPostErasureStatus.REQUESTED -> "secondary"
        SocialPostErasureStatus.APPROVED -> "warning"
        SocialPostErasureStatus.REJECTED -> "dark"
        SocialPostErasureStatus.EXECUTED -> "danger"
    }
