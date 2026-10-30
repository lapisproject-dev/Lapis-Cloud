package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.select.Select
import io.kvision.form.text.TextArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MeetingProtocolPreviewDto
import network.lapis.cloud.shared.domain.MeetingProtocolVersionsDto
import network.lapis.cloud.shared.domain.PROTOCOL_CORRECTION_NOTE_MAX
import network.lapis.cloud.shared.domain.PROTOCOL_CORRECTION_NOTE_MIN
import network.lapis.cloud.shared.domain.ProtocolApprovalDto
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolConfirmOutcome
import network.lapis.cloud.shared.domain.ProtocolConfirmResultDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolFinaliseOutcome
import network.lapis.cloud.shared.domain.ProtocolFinaliseResultDto
import network.lapis.cloud.shared.domain.ProtocolVersionDto
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import network.lapis.cloud.shared.rpc.IMeetingProtocolService

/** V1.9.240 -- the RPCs of finalising behind one thin seam, so a test can replace them (same shape as [ProtocolDraftRpc]). */
internal class MeetingProtocolRpc(
    val preview: suspend (
        meetingId: String,
    ) -> MeetingProtocolPreviewDto = { rpcService<IMeetingProtocolService>().previewMeetingProtocol(it) },
    val versions: suspend (meetingId: String) -> MeetingProtocolVersionsDto = {
        rpcService<IMeetingProtocolService>().getMeetingProtocolVersions(it)
    },
    val finalise: suspend (meetingId: String, hash: String) -> ProtocolFinaliseResultDto = { meeting, hash ->
        rpcService<IMeetingProtocolService>().finaliseMeetingProtocol(meeting, hash)
    },
    val confirm: suspend (meetingId: String, versionId: String) -> ProtocolConfirmResultDto = { meeting, version ->
        rpcService<IMeetingProtocolService>().confirmMeetingProtocolVersion(meeting, version)
    },
    val correct: suspend (meetingId: String, note: String) -> Unit = { meeting, note ->
        rpcService<IMeetingProtocolService>().startMeetingProtocolCorrection(meeting, note)
    },
    val recordApproval: suspend (agendaItemId: String, result: ProtocolApprovalResult) -> ProtocolApprovalDto = { item, result ->
        rpcService<IMeetingProtocolService>().recordProtocolApproval(item, result)
    },
)

internal class ProtocolFinalHandle(
    val root: Div,
    val reload: () -> Unit,
)

/** The URL of the PDF of one version (the route answers 404 for everything the caller may not get). */
internal fun protocolPdfUrl(versionId: String): String = "/api/meeting-protocol/$versionId/pdf"

/** The first twelve hex characters in groups of four ("a1b2 c3d4 e5f6"): enough to compare by eye; the full value is copied with a button. */
internal fun shortProtocolHash(hash: String): String = hash.take(SHORT_HASH_LENGTH).chunked(HASH_GROUP).joinToString(" ")

private const val SHORT_HASH_LENGTH = 12
private const val HASH_GROUP = 4

internal fun protocolRoleLabel(role: ProtocolConfirmationRole): String =
    when (role) {
        ProtocolConfirmationRole.LEAD -> gettext("Sitzungsleitung")
        ProtocolConfirmationRole.MINUTES -> gettext("Protokollführung")
    }

internal fun protocolRolesLabel(roles: Set<ProtocolConfirmationRole>): String =
    if (roles.size > 1) gettext("Sitzungsleitung und Protokollführung") else roles.firstOrNull()?.let { protocolRoleLabel(it) }.orEmpty()

internal fun protocolStatusLabel(status: ProtocolVersionStatus): String =
    when (status) {
        ProtocolVersionStatus.AWAITING_CONFIRMATION -> gettext("wartet auf Bestätigung")
        ProtocolVersionStatus.CONFIRMED -> gettext("bestätigt")
        ProtocolVersionStatus.SUPERSEDED -> gettext("ersetzt")
    }

internal fun protocolApprovalLabel(result: ProtocolApprovalResult): String =
    when (result) {
        ProtocolApprovalResult.APPROVED -> gettext("genehmigt")
        ProtocolApprovalResult.APPROVED_WITH_CORRECTIONS -> gettext("mit Korrekturen genehmigt")
        ProtocolApprovalResult.NOT_APPROVED -> gettext("nicht genehmigt")
    }

private fun protocolStatusColor(status: ProtocolVersionStatus): String =
    when (status) {
        ProtocolVersionStatus.AWAITING_CONFIRMATION -> "warning"
        ProtocolVersionStatus.CONFIRMED -> "success"
        ProtocolVersionStatus.SUPERSEDED -> "secondary"
    }

/** The one sentence for a reason that stops finalising; shared by the dialog of the preview and the answer of the finalise call. */
private fun blockedSentence(reason: ProtocolFinaliseOutcome?): String =
    when (reason) {
        ProtocolFinaliseOutcome.EMPTY ->
            gettext("Es ist noch nichts eingetragen: Kein Tagesordnungspunkt hat einen Verlauf, und es gibt keinen Beschluss.")
        ProtocolFinaliseOutcome.NOT_OPENED -> gettext("Die Sitzung wurde noch nicht eröffnet.")
        ProtocolFinaliseOutcome.ALREADY_FINALISED -> gettext("Das Protokoll ist bereits fertiggestellt.")
        else -> gettext("Das Protokoll kann gerade nicht fertiggestellt werden.")
    }

/**
 * V1.9.240 -- finalising, confirming and correcting the Protokoll of a Sitzung: the newest version with its state, the buttons the caller's roles
 * allow, the history of all versions and the correction form. Used in the "Protokoll" tab of the sitting panel AND on the Sitzung's own page (the
 * second confirmation and the reading happen after the room ended). It hides itself when the Sitzung is not visible to the caller (the server
 * answers not-found). Every text from the server is shown as text only.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun renderProtocolFinalSection(
    parent: Container,
    meetingId: String,
    rpc: MeetingProtocolRpc = MeetingProtocolRpc(),
    onChanged: () -> Unit = {},
    // V1.9.242: "Zum Entwurf" of the dialog that explains an empty Protokoll; the panel moves the focus into the draft. Without a draft on the page
    // (the Sitzung's own page) the default does nothing and the button is not offered.
    onJumpToDraft: (() -> Unit)? = null,
): ProtocolFinalHandle {
    val root = Div(className = "protocol-final")
    parent.add(root)
    val live =
        root.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    root.h3(tr("Fassungen des Protokolls")) { addCssClasses("h6 mb-1") }
    val statusHost = root.vPanel(spacing = 4)
    val actionsHost = Div(className = "protocol-final-actions")
    root.add(actionsHost)
    val correctionHost = root.vPanel(spacing = 4)
    correctionHost.hide()
    val historyHost = root.vPanel(spacing = 4)
    var historyOpen = false
    var current: MeetingProtocolVersionsDto? = null
    var loading = false

    lateinit var reload: () -> Unit

    fun announce(text: String) {
        live.content = text
    }

    fun changed() {
        reload()
        onChanged()
    }

    fun copyHash(hash: String) {
        val clipboard: dynamic = window.navigator.asDynamic().clipboard
        if (clipboard == null || clipboard == undefined) return
        val promise: dynamic = clipboard.writeText(hash)
        promise.then({ announce(gettext("Prüfsumme kopiert.")) }, {})
    }

    fun finaliseDialog(preview: MeetingProtocolPreviewDto) {
        val extra =
            if (preview.itemsWithoutCourse > 0) {
                listOf(gettext("%1 Tagesordnungspunkte ohne Verlauf", preview.itemsWithoutCourse))
            } else {
                emptyList()
            }
        confirmDialog(
            title = tr("Protokoll fertigstellen"),
            message =
                gettext(
                    "Damit bestätigen Sie das Protokoll als %1. Danach kann niemand mehr etwas ändern. " +
                        "Korrekturen sind nur als neue Fassung mit Vermerk möglich.",
                    protocolRolesLabel(preview.callerRoles),
                ),
            confirmLabel = tr("Fertigstellen und bestätigen"),
            confirmIcon = ActionIcon.APPROVE,
            confirmStyle = ButtonStyle.PRIMARY,
            extraLines = extra,
        ) {
            AppScope.launch {
                val result = guarded { rpc.finalise(meetingId, preview.contentSha256) } ?: return@launch
                when (result.outcome) {
                    ProtocolFinaliseOutcome.FINALISED, ProtocolFinaliseOutcome.CONFIRMED -> {
                        notifySuccess(tr("Das Protokoll ist fertiggestellt."))
                        announce(gettext("Das Protokoll ist fertiggestellt."))
                    }
                    ProtocolFinaliseOutcome.STALE ->
                        notifyWarning(tr("Das Protokoll wurde inzwischen geändert. Bitte prüfen Sie es erneut."))
                    // EMPTY after a preview that said otherwise is a race: somebody removed the last content in between.
                    ProtocolFinaliseOutcome.EMPTY,
                    ProtocolFinaliseOutcome.NOT_OPENED,
                    ProtocolFinaliseOutcome.ALREADY_FINALISED,
                    -> notifyWarning(blockedSentence(result.outcome))
                    ProtocolFinaliseOutcome.PDF_LIMIT -> notifyWarning(tr("Das Protokoll ist zu umfangreich für ein PDF."))
                }
                changed()
            }
        }
    }

    /**
     * V1.9.242 -- why finalising is not possible right now, in one sentence, with no confirm button at all (not even a disabled one). Only an empty
     * Protokoll offers a way on: "Zum Entwurf" (when the page has a draft to jump to).
     */
    fun blockedDialog(preview: MeetingProtocolPreviewDto) {
        val reason = preview.blockedReason
        val modal = Modal(caption = tr("Protokoll fertigstellen"))
        modal.div(blockedSentence(reason))
        val close = newActionButton(ActionIcon.CANCEL, tr("Schließen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
        modal.addButton(close)
        val jump = onJumpToDraft
        if (reason == ProtocolFinaliseOutcome.EMPTY && jump != null) {
            val toDraft = Button(tr("Zum Entwurf"), style = ButtonStyle.PRIMARY)
            toDraft.onClick {
                modal.hide()
                jump()
            }
            modal.addButton(toDraft)
        }
        modal.show()
    }

    fun startFinalise(button: Button) {
        runGuardedAction(button) {
            val preview = guarded { rpc.preview(meetingId) } ?: return@runGuardedAction
            if (!preview.canFinalise) {
                blockedDialog(preview)
                return@runGuardedAction
            }
            finaliseDialog(preview)
        }
    }

    fun confirmVersion(
        version: ProtocolVersionDto,
        button: Button,
    ) {
        runGuardedAction(button) {
            val result = guarded { rpc.confirm(meetingId, version.versionId) } ?: return@runGuardedAction
            when (result.outcome) {
                ProtocolConfirmOutcome.CONFIRMED_ROLE, ProtocolConfirmOutcome.FULLY_CONFIRMED -> {
                    notifySuccess(tr("Bestätigt."))
                    announce(gettext("Bestätigt."))
                }
                ProtocolConfirmOutcome.PDF_PENDING, ProtocolConfirmOutcome.PDF_LIMIT ->
                    notifyWarning(tr("Das PDF konnte noch nicht erstellt werden. Bitte versuchen Sie es erneut."))
                ProtocolConfirmOutcome.ALREADY_CONFIRMED, ProtocolConfirmOutcome.NOT_AWAITING ->
                    notifyWarning(tr("Diese Fassung wartet nicht mehr auf Ihre Bestätigung."))
            }
            changed()
        }
    }

    fun renderCorrection() {
        correctionHost.removeAll()
        correctionHost.show()
        val area = TextArea(rows = 3, label = gettext("Was wird korrigiert?")) { addCssClass("w-100") }
        correctionHost.add(area)
        val counter = correctionHost.div("") { addCssClasses("text-muted small") }

        fun valueOf(): String = (area.value ?: "").trim()

        lateinit var create: Button

        fun paint() {
            val length = valueOf().length
            counter.content = gettext("%1 / %2 Zeichen (mindestens %3)", length, PROTOCOL_CORRECTION_NOTE_MAX, PROTOCOL_CORRECTION_NOTE_MIN)
            create.disabled = length < PROTOCOL_CORRECTION_NOTE_MIN || length > PROTOCOL_CORRECTION_NOTE_MAX
        }
        correctionHost.actionGroup {
            create =
                actionButton(ActionIcon.EDIT, tr("Korrektur anlegen"), style = ButtonStyle.PRIMARY) {
                    onClick {
                        runGuardedAction(this) {
                            val note = valueOf()
                            val done = guarded { rpc.correct(meetingId, note) }
                            if (done != null) {
                                correctionHost.removeAll()
                                correctionHost.hide()
                                notifySuccess(tr("Die Korrektur wurde angelegt. Das Protokoll kann wieder bearbeitet werden."))
                                changed()
                            }
                        }
                    }
                }
            actionButton(ActionIcon.CANCEL, tr("Abbrechen")) {
                onClick {
                    correctionHost.removeAll()
                    correctionHost.hide()
                }
            }
        }
        area.subscribe { paint() }
        area.onEvent { input = { paint() } }
        paint()
    }

    fun renderHistory(dto: MeetingProtocolVersionsDto) {
        historyHost.removeAll()
        if (dto.versions.size < 2) return
        historyHost.actionGroup {
            actionButton(ActionIcon.VIEW, if (historyOpen) tr("Frühere Fassungen ausblenden") else tr("Frühere Fassungen anzeigen")) {
                onClick {
                    historyOpen = !historyOpen
                    renderHistory(dto)
                }
            }
        }
        if (!historyOpen) return
        val table = historyHost.standardTable(emptyList())
        listOf(tr("Fassung"), tr("Status"), tr("Vermerk"), tr("Prüfsumme"), tr("PDF")).forEach {
            table.addHeaderCell(HeaderCell(content = it, scope = Scope.COL))
        }
        dto.versions.forEach { version ->
            table.row {
                textCell(version.versionNo.toString())
                textCell(protocolStatusLabel(version.status))
                textCell(version.correctionNote.orEmpty())
                textCell(shortProtocolHash(version.contentSha256)) { addCssClass("protocol-hash") }
                cell {
                    if (version.pdfAvailable) {
                        link(gettext("PDF herunterladen"), url = protocolPdfUrl(version.versionId), dataNavigo = false) {
                            setAttribute("download", "")
                        }
                    } else {
                        span("–")
                    }
                }
            }
        }
    }

    fun render(dto: MeetingProtocolVersionsDto) {
        current = dto
        statusHost.removeAll()
        actionsHost.removeAll()
        val newest = dto.versions.firstOrNull()
        if (newest == null) {
            statusHost.div(tr("Noch keine Fassung fertiggestellt.")) { addCssClasses("text-muted") }
        } else {
            statusHost.div {
                span(gettext("Fassung %1", newest.versionNo), className = "fw-semibold me-2")
                statusBadge(protocolStatusLabel(newest.status), protocolStatusColor(newest.status))
            }
            when (newest.status) {
                ProtocolVersionStatus.AWAITING_CONFIRMATION ->
                    statusHost.div(gettext("Bestätigung von %1 ausstehend", protocolRolesLabel(newest.missingRoles)))
                ProtocolVersionStatus.CONFIRMED -> {
                    newest.confirmations.forEach {
                        statusHost.untrustedDiv(
                            "${protocolRoleLabel(it.role)}: ${it.confirmerName}, ${formatSystemTimestamp(it.confirmedAt)}",
                        )
                    }
                    statusHost.div {
                        span(tr("Prüfsumme"), className = "text-muted me-2")
                        span(shortProtocolHash(newest.contentSha256), className = "protocol-hash")
                    }
                }
                ProtocolVersionStatus.SUPERSEDED -> Unit
            }
            if (newest.status == ProtocolVersionStatus.CONFIRMED) {
                actionsHost.actionGroup {
                    if (newest.pdfAvailable) {
                        link(gettext("PDF herunterladen"), url = protocolPdfUrl(newest.versionId), dataNavigo = false) {
                            addCssClasses("btn btn-primary")
                            setAttribute("download", "")
                        }
                    } else {
                        span(tr("PDF wird erstellt …"), className = "text-muted align-self-center")
                        if (dto.callerRoles.isNotEmpty()) {
                            actionButton(ActionIcon.REFRESH, tr("Erneut versuchen")) {
                                onClick { confirmVersion(newest, this) }
                            }
                        }
                    }
                    actionButton(ActionIcon.COPY, tr("Prüfsumme vollständig kopieren")) { onClick { copyHash(newest.contentSha256) } }
                }
            }
            if (dto.canConfirm && newest.status == ProtocolVersionStatus.AWAITING_CONFIRMATION) {
                actionsHost.actionGroup {
                    actionButton(
                        ActionIcon.APPROVE,
                        tr("Bestätigen"),
                        style = ButtonStyle.PRIMARY,
                    ) { onClick { confirmVersion(newest, this) } }
                }
            }
        }
        val canFinalise =
            dto.callerRoles.isNotEmpty() && !dto.canStartCorrection && newest?.status != ProtocolVersionStatus.AWAITING_CONFIRMATION
        if (canFinalise) {
            actionsHost.actionGroup {
                actionButton(ActionIcon.APPROVE, tr("Protokoll fertigstellen …"), style = ButtonStyle.PRIMARY) {
                    onClick { startFinalise(this) }
                }
            }
        }
        if (dto.canStartCorrection) {
            actionsHost.actionGroup {
                actionButton(ActionIcon.EDIT, tr("Korrektur anlegen …")) { onClick { renderCorrection() } }
            }
        }
        renderHistory(dto)
    }

    reload = {
        if (!loading) {
            loading = true
            AppScope.launch {
                val dto =
                    try {
                        rpc.versions(meetingId)
                    } catch (_: Exception) {
                        null
                    }
                loading = false
                if (dto == null) {
                    // not visible to the caller (or not reachable): show nothing; an earlier reading stays
                    if (current == null) root.hide()
                } else {
                    root.show()
                    render(dto)
                }
            }
        }
    }
    reload()
    return ProtocolFinalHandle(root = root, reload = { reload() })
}

/**
 * V1.9.240 -- the "Genehmigung des Protokolls" block of an agenda item: what was decided, and for the chair / minute taker a select to record it.
 * Repaints itself with the server's answer (the item list of the draft does not rebuild for it).
 */
internal fun Container.protocolApprovalBlock(
    approval: ProtocolApprovalDto,
    agendaItemId: String,
    rpc: MeetingProtocolRpc,
    onRecorded: () -> Unit = {},
): Div {
    val host = Div(className = "protocol-approval")
    add(host)

    fun paint(shown: ProtocolApprovalDto) {
        host.removeAll()
        host.div(
            gettext("Genehmigung des Protokolls der Sitzung vom %1", formatDate(shown.sourceMeetingDate)),
        ) { addCssClass("fw-semibold") }
        shown.result?.let {
            host.statusBadge(
                protocolApprovalLabel(it),
                if (it ==
                    ProtocolApprovalResult.NOT_APPROVED
                ) {
                    "danger"
                } else {
                    "success"
                },
            )
        }
        if (shown.correctionPending) host.div(tr("Eine korrigierte Fassung steht noch aus.")) { addCssClasses("text-muted small") }
        if (!shown.editable) return
        val options =
            listOf("" to gettext("Ergebnis wählen")) + ProtocolApprovalResult.entries.map { it.name to protocolApprovalLabel(it) }
        val select = Select(options = options, value = shown.result?.name.orEmpty(), label = gettext("Genehmigung des Protokolls"))
        host.add(select)
        select.subscribe { value ->
            val result = value?.takeIf { it.isNotBlank() }?.let { ProtocolApprovalResult.valueOf(it) } ?: return@subscribe
            if (result == shown.result) return@subscribe
            AppScope.launch {
                val saved = guarded { rpc.recordApproval(agendaItemId, result) } ?: return@launch
                paint(saved)
                onRecorded()
            }
        }
    }
    paint(approval)
    return host
}
