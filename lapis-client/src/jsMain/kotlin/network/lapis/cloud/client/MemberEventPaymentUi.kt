package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus

/**
 * V1.9.35 -- the withdrawal note on a paid event. Server truth only: shown iff the server says the caller PAID
 * ([EventDto.ownPaid]) AND there is an active own registration to withdraw. Replaces the old "paid fee + CONFIRMED" guess, which
 * also fired for a fee-bearing event that was confirmed without payment. The amount is the server's sum of the completed
 * payments ([EventDto.ownPaidAmount]), never the event fee.
 */
internal fun memberWithdrawRefundNote(e: EventDto): List<String> {
    val paid = e.ownPaidAmount
    return if (e.ownPaid && paid != null && canCancelOwn(e)) {
        listOf(
            gettext(
                "Sie haben für diese Veranstaltung %1 bezahlt. Der Betrag wird nicht automatisch zurückgezahlt. Ihre Abmeldung erscheint beim Vorstand als offene Erstattung.",
                formatMoney(paid),
            ),
        )
    } else {
        emptyList()
    }
}

/**
 * The refund state line after a withdrawal: only when there is NO active registration but the newest own one was paid.
 * Open until the board marks it; then it says when.
 */
internal fun SimplePanel.renderOwnRefundLine(e: EventDto) {
    if (e.ownRegistrationStatus != null || !e.ownPaid) return
    val markedAt = e.ownRefundMarkedAt
    val text =
        if (markedAt == null) {
            tr("Bezahlt – Erstattung noch offen.")
        } else {
            gettext("Erstattung vom Vorstand als erledigt vermerkt am %1.", formatDate(systemDate(markedAt)))
        }
    div(text) { addCssClasses("text-muted small") }
}

/**
 * "Zahlung fortsetzen": only for a `PENDING_PAYMENT` registration. One guarded call; the amount comes from the server. Any refusal
 * is the same fixed sentence followed by a reload; the redirect is followed only for `https:` URLs ([isSafeHttpsRedirect]).
 */
internal fun renderResumePaymentButton(
    actions: SimplePanel,
    e: EventDto,
    rpc: MemberEventsRpc,
    navigate: (String) -> Unit,
    toastError: (String) -> Unit,
    reload: () -> Unit,
) {
    if (e.ownRegistrationStatus != EventRegistrationStatus.PENDING_PAYMENT) return
    val button = Button(gettext("Zahlung fortsetzen (%1)", formatMoney(e.feeAmount)), style = ButtonStyle.PRIMARY)
    actions.add(button)
    button.onClick {
        runGuardedAction(button) {
            var url: String? = null
            val ok =
                runOrConflict(
                    conflictMessage = gettext("Die Zahlung kann gerade nicht fortgesetzt werden. Die Ansicht wurde aktualisiert."),
                    toast = toastError,
                ) { url = rpc.resumeOwnEventPayment(e.id).checkoutRedirectUrl }
            val target = url
            if (ok && target != null) {
                if (isSafeHttpsRedirect(target)) {
                    navigate(target)
                    return@runGuardedAction
                }
                toastError(gettext("Die Zahlungsseite konnte nicht geöffnet werden."))
            }
            reload()
        }
    }
}
