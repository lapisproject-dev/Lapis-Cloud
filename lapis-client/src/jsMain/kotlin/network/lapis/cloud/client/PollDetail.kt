package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.rpc.IPollService

/*
 * V1.9.31 -- the detail view of one poll: question, description, state, the answer booth while it is open and may be answered, the result
 * after it was closed, and -- for those who manage the poll -- "schließen" and "abbrechen". Every successful write reloads the whole detail
 * (one consistent snapshot); the screen never patches pieces of itself. The loading lives in `PollScreen.kt`.
 */
internal fun renderPollDetail(
    panel: SimplePanel,
    pollId: String,
    ctx: PollUiContext,
) {
    lateinit var section: DataSection
    section =
        panel.dataSection<PollDetailData>(
            isEmpty = { false },
            load = { loadPollDetail(pollId) },
            render = { host, data -> renderPollBody(host, data, ctx) { section.reload() } },
        )
    section.reload()
}

@Suppress("UNUSED_PARAMETER")
private fun renderPollBody(
    host: SimplePanel,
    data: PollDetailData,
    ctx: PollUiContext,
    reload: () -> Unit,
) {
    val poll = data.poll
    val body = host.vPanel(spacing = 10)
    val headerRow = body.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.untrustedHeading(poll.question, level = 2, className = "h5 flex-grow-1 text-break")
    headerRow.statusBadge(pollStatusLabel(poll.status), pollStatusColor(poll.status))
    poll.description?.takeIf { it.isNotBlank() }?.let { body.untrustedP(it, className = "text-break lapis-poll-description") }
    body.p(
        gettext(
            "Gestartet von %1 am %2",
            sanitizeUntrustedI18nText(poll.createdByDisplayName),
            formatDateTime(poll.createdAt),
        ),
    ) { addCssClasses("text-muted small mb-0") }
    body.p(pollDeadlineLabel(poll)) { addCssClasses("text-muted small mb-0") }
    body.div(tr("Unverbindlich – ein Stimmungsbild, kein Beschluss.")) { addCssClasses("alert alert-secondary mb-0") }

    // The actions row is created first (so it can be hidden while the check step of the booth is open) but mounted below the booth.
    val boothHost = body.vPanel(spacing = 10)
    val actions = body.hPanel(spacing = 8)
    when (poll.status) {
        PollStatus.OPEN -> renderOpenState(body, boothHost, actions, data, reload)
        PollStatus.CLOSED -> renderPollResult(body, poll, data.result)
        PollStatus.ABORTED -> {
            renderOptionList(body, poll)
            body.p(tr("Diese Umfrage wurde abgebrochen. Die Antworten werden nie ausgewertet."))
        }
    }
    renderPollActions(actions, data, reload)
}

private fun renderOpenState(
    body: SimplePanel,
    boothHost: SimplePanel,
    actions: SimplePanel,
    data: PollDetailData,
    reload: () -> Unit,
) {
    val poll = data.poll
    when {
        canRespondToPoll(poll, data.participation) ->
            renderPollBooth(
                panel = boothHost,
                poll = poll,
                onReview = { reviewing -> actions.visible = !reviewing },
                onExit = { refresh -> if (refresh) reload() else navigateTo(Routes.POLLS) },
            )
        else -> {
            renderOptionList(body, poll)
            if (data.participation.hasResponded) {
                body.p(tr("Sie haben an dieser Umfrage teilgenommen."))
            } else if (!data.participation.eligible) {
                body.p(tr("Nur aktive Mitglieder können antworten."))
            }
        }
    }
}

/** The options as a plain list (when there is no booth to show them). */
private fun renderOptionList(
    body: SimplePanel,
    poll: PollDto,
) {
    val list = body.tag(TAG.UL, className = "mb-0")
    poll.options.forEach { option -> list.tag(TAG.LI) { untrustedSpan(option.text, className = "text-break") } }
}

private fun renderPollActions(
    actions: SimplePanel,
    data: PollDetailData,
    reload: () -> Unit,
) {
    val poll = data.poll
    if (canClosePoll(poll) || canAbortPoll(poll)) actions.addCssClasses("mt-4")
    if (canClosePoll(poll)) {
        val close = Button(tr("Umfrage schließen"), style = ButtonStyle.OUTLINESECONDARY)
        actions.add(close)
        close.onClick {
            confirmDialog(
                title = tr("Umfrage schließen?"),
                message = tr("Danach sind keine Antworten mehr möglich. Das Ergebnis wird sichtbar, sofern genug Antworten vorliegen."),
                confirmLabel = tr("Umfrage schließen"),
                confirmStyle = ButtonStyle.PRIMARY,
            ) {
                runGuardedAction(close) {
                    val closed =
                        pollGuarded(conflictMessage = gettext("Der Stand hat sich geändert."), onConflict = reload) {
                            rpcService<IPollService>().closePoll(poll.id)
                        }
                    if (closed != null) reload()
                }
            }
        }
    }
    if (canAbortPoll(poll)) {
        val abort = Button(tr("Umfrage abbrechen"), style = ButtonStyle.OUTLINEDANGER)
        actions.add(abort)
        abort.onClick {
            confirmWithTypedConfirmationDialog(
                title = tr("Umfrage abbrechen?"),
                message = tr("Die Antworten werden nie ausgewertet. Das lässt sich nicht rückgängig machen."),
                expectedText = gettext("ABBRECHEN"),
                confirmLabel = tr("Umfrage endgültig abbrechen"),
                cancelLabel = tr("Zurück"),
            ) {
                runGuardedAction(abort) {
                    val aborted =
                        pollGuarded(conflictMessage = gettext("Der Stand hat sich geändert."), onConflict = reload) {
                            rpcService<IPollService>().abortPoll(poll.id)
                        }
                    if (aborted != null) reload()
                }
            }
        }
    }
}
