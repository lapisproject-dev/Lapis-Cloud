package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MeetingRewardLineDto
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.domain.MeetingRewardSettlementViewDto
import network.lapis.cloud.shared.rpc.IMeetingRewardService

/*
 * V1.9.316 -- the card "LTR-Anerkennung für Sitzungsteilnahme" on the detail page of a Sitzung (administrators only; the server checks every call).
 * It shows the state of the settlement of THIS Sitzung: unavailable (with the reason), the live preview, prepared by one administrator, released
 * (final). The buttons are driven by `canPrepare` / `canRelease` / `canDiscard` and `blockedReason` of the server view; a stale preview is refused
 * by the server (hash) and the card reloads. The tax and legal classification has not been reviewed -- the card says so.
 */

/** Builds the card into [panel] when the viewer is an administrator; the Sitzung id is the only input. */
internal fun renderMeetingAttendanceRecognitionCard(
    panel: SimplePanel,
    meetingId: String,
) {
    if (!canSeeMeetingReward()) return
    panel.h2(tr("LTR-Anerkennung für Sitzungsteilnahme")) { addCssClass("h5") }
    val host = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
    lateinit var section: DataSection
    section =
        host.dataSection<MeetingRewardSettlementViewDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IMeetingRewardService>().previewMeetingRewardSettlement(meetingId) } },
            render = { body, view -> renderRecognitionBody(body, meetingId, view) { section.reload() } },
        )
    section.reload()
}

private fun renderRecognitionBody(
    host: SimplePanel,
    meetingId: String,
    view: MeetingRewardSettlementViewDto,
    reload: () -> Unit,
) {
    host.div(tr("Die steuerliche und rechtliche Einordnung wurde nicht geprüft.")) { addCssClasses("text-muted small") }
    val unavailable = view.unavailable
    if (unavailable != null) {
        host.div { statusBadge(tr("Nicht verfügbar"), "secondary") }
        host.p(meetingRewardUnavailableText(unavailable)) { addCssClasses("mb-0") }
        return
    }
    host.div {
        when (view.status) {
            null -> statusBadge(tr("Vorschau"), "info")
            MeetingRewardSettlementStatus.PREPARED -> statusBadge(tr("Vorbereitet"), "warning")
            MeetingRewardSettlementStatus.RELEASED -> statusBadge(tr("Freigegeben"), "success")
        }
    }
    view.preparedByName?.let { by ->
        val at = view.preparedAt?.let { formatSystemDateTime(it) }.orEmpty()
        host.div(sanitizeUntrustedI18nText(gettext("Vorbereitet von %1 am %2", by, at))) { addCssClasses("small") }
    }
    view.releasedByName?.let { by ->
        val at = view.releasedAt?.let { formatSystemDateTime(it) }.orEmpty()
        host.div(sanitizeUntrustedI18nText(gettext("Freigegeben von %1 am %2", by, at))) { addCssClasses("small") }
    }
    host.dataTable(columns = lineColumns(), rows = view.lines)
    host.div(gettext("Summe: %1", formatLtr(view.total))) { addCssClasses("fw-bold") }
    if (view.callerIsBeneficiary) {
        host.div(tr("Sie erhalten aus dieser Abrechnung selbst LTR.")) { addCssClasses("text-warning-emphasis") }
    }
    val actions = host.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    if (view.canPrepare) {
        actions.actionButton(ActionIcon.NEXT, tr("Vorbereiten"), style = ButtonStyle.PRIMARY) {
            onClick {
                val hash = view.previewHash ?: return@onClick
                runGuardedAction(this) {
                    val saved = meetingRewardGuarded { rpcService<IMeetingRewardService>().prepareMeetingRewardSettlement(meetingId, hash) }
                    if (saved !=
                        null
                    ) {
                        notifySuccess(tr("Die Abrechnung wurde vorbereitet."))
                    } else {
                        notifyInfo(tr("Falls sich Daten geändert haben, bereiten Sie neu vor."))
                    }
                    reload()
                }
            }
        }
    }
    if (view.canRelease) {
        actions.actionButton(ActionIcon.APPROVE, tr("Freigeben"), style = ButtonStyle.SUCCESS) {
            onClick {
                val hash = view.previewHash ?: return@onClick
                confirmDialog(
                    title = tr("Anerkennung freigeben?"),
                    message =
                        gettext(
                            "Es werden insgesamt %1 LTR gutgeschrieben. Die Buchung lässt sich nicht rückgängig machen.",
                            formatLtr(view.total),
                        ),
                    confirmLabel = tr("Freigeben"),
                    confirmStyle = ButtonStyle.SUCCESS,
                ) {
                    AppScope.launch {
                        val released =
                            meetingRewardGuarded { rpcService<IMeetingRewardService>().releaseMeetingRewardSettlement(meetingId, hash) }
                        if (released !=
                            null
                        ) {
                            notifySuccess(tr("Die Anerkennung wurde gutgeschrieben."))
                        } else {
                            notifyInfo(tr("Falls sich Daten geändert haben, bereiten Sie neu vor."))
                        }
                        reload()
                    }
                }
            }
        }
    }
    if (view.canDiscard) {
        actions.actionButton(ActionIcon.REJECT, tr("Verwerfen"), style = ButtonStyle.OUTLINESECONDARY) {
            onClick {
                runGuardedAction(this) {
                    meetingRewardGuarded { rpcService<IMeetingRewardService>().discardMeetingRewardSettlement(meetingId) }
                    reload()
                }
            }
        }
    }
    val blocked = view.blockedReason
    if (blocked != null && !view.canRelease && !view.canPrepare && view.status != MeetingRewardSettlementStatus.RELEASED) {
        host.div(meetingRewardBlockedText(blocked)) { addCssClasses("text-muted small") }
    }
}

private fun lineColumns(): List<DataColumn<MeetingRewardLineDto>> =
    listOf(
        textColumn(title = tr("Mitglied"), primary = true) { line: MeetingRewardLineDto -> line.displayName },
        textColumn(title = tr("Anwesenheit")) { line: MeetingRewardLineDto -> attendanceStatusLabel(line.attendanceStatus) },
        textColumn(title = tr("Funktion")) { line: MeetingRewardLineDto -> line.functionRole?.let { committeeRoleLabel(it) } ?: "--" },
        textColumn(title = tr("Grundbetrag"), numeric = true) { line: MeetingRewardLineDto -> formatLtr(line.baseAmount) },
        textColumn(title = tr("Zuschlag"), numeric = true) { line: MeetingRewardLineDto -> formatLtr(line.bonusAmount) },
        textColumn(title = tr("Betrag"), numeric = true) { line: MeetingRewardLineDto -> formatLtr(line.amount) },
        textColumn(title = tr("Grund")) { line: MeetingRewardLineDto -> line.reason?.let { meetingRewardReasonLabel(it) } ?: "--" },
    )
