package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRefundDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.rpc.IEventService

/** The RPC surface of the refund section -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface EventRefundsRpc {
    suspend fun listOpen(): List<EventRefundDto>

    suspend fun markRefunded(registrationId: String): EventRefundDto
}

internal fun liveEventRefundsRpc(): EventRefundsRpc =
    object : EventRefundsRpc {
        override suspend fun listOpen() = rpcService<IEventService>().listOpenEventRefunds()

        override suspend fun markRefunded(registrationId: String) = rpcService<IEventService>().markEventRefunded(registrationId)
    }

/**
 * V1.9.35 -- "Offene Erstattungen": paid registrations that were withdrawn (or whose reservation expired after a late payment) and
 * whose refund the board has not yet marked. Self-gated BOARD/ADMIN. An EMPTY list renders nothing at all (no heading, no
 * "nothing here" text); a load error is always shown (the standard error state with a retry).
 *
 * Lapis Cloud pays nothing out and books nothing: "Als erstattet markieren" only records that the refund was paid OUTSIDE the
 * system. Every mark asks first ("Abbrechen" has the focus) and runs through [runGuardedAction]; a refused or already changed
 * entry reloads the list. There is no undo. Names and titles are untrusted and reach the screen only through the `untrusted*`
 * helpers / [sanitizeUntrustedI18nText]; the amount shown is the server's sum of the completed payments. No value reaches a toast.
 */
internal fun renderEventRefundsSection(
    root: Container,
    rpc: EventRefundsRpc = liveEventRefundsRpc(),
    confirm: AdminActionConfirm = liveAdminActionConfirm,
    toastError: (String) -> Unit = { notifyError(it) },
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    if (!AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) return
    lateinit var section: DataSection
    section =
        root.dataSection<List<EventRefundDto>>(
            isEmpty = { false },
            load = { guarded { rpc.listOpen() } },
            render = { panel, refunds ->
                if (refunds.isEmpty()) return@dataSection
                panel.h2(gettext("Offene Erstattungen (%1)", refunds.size.toString())) { addCssClass("h5") }
                val list = panel.vPanel(spacing = 10)
                refunds.forEach { refund ->
                    val card = list.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
                    val header = card.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                    header.untrustedCardTitle(refund.eventTitle)
                    card.div(formatDateTime(refund.eventStartsAt)) { addCssClasses("text-muted small") }
                    card.untrustedDiv(refund.participantDisplayName)
                    card.div(formatMoney(refund.paidAmount))
                    val cancelledAt = refund.cancelledAt
                    if (refund.status == EventRegistrationStatus.CANCELLED && cancelledAt != null) {
                        card.div(gettext("Abgemeldet am %1", formatDate(systemDate(cancelledAt)))) { addCssClasses("text-muted small") }
                    } else {
                        card.div(tr("Reservierung abgelaufen")) { addCssClasses("text-muted small") }
                    }
                    if (refund.paymentCount > 1) {
                        card.div(gettext("Mehrfach bezahlt (%1 Zahlungen)", refund.paymentCount.toString())) {
                            addCssClasses("small fw-bold")
                        }
                    }
                    val mark = Button(tr("Als erstattet markieren"), style = ButtonStyle.OUTLINEPRIMARY)
                    card.add(mark)
                    mark.onClick {
                        if (mark.disabled) return@onClick
                        confirm.show(
                            gettext("Erstattung vermerken"),
                            gettext(
                                "Haben Sie %1 an %2 außerhalb von Lapis Cloud zurückgezahlt? Lapis Cloud zahlt nichts aus, es vermerkt nur die erledigte Erstattung.",
                                formatMoney(refund.paidAmount),
                                sanitizeUntrustedI18nText(refund.participantDisplayName),
                            ),
                            gettext("Als erstattet markieren"),
                        ) {
                            runGuardedAction(mark) {
                                val ok =
                                    runOrConflict(
                                        conflictMessage =
                                            gettext("Diese Erstattung hatte sich bereits geändert. Die Ansicht wurde aktualisiert."),
                                        toast = toastError,
                                    ) { rpc.markRefunded(refund.registrationId) }
                                if (ok) toastSuccess(gettext("Erstattung vermerkt."))
                                section.reload()
                            }
                        }
                    }
                }
            },
        )
    section.reload()
}
