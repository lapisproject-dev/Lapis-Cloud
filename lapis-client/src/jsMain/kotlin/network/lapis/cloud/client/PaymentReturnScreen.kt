package network.lapis.cloud.client

import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.time.Duration.Companion.seconds

/** Poll interval and hard stop for [renderPaymentReturnScreen] -- see that function's own KDoc. */
private val POLL_INTERVAL = 2.seconds
private const val MAX_POLL_ATTEMPTS = 30

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `/payment-return`, reads
 * [checkoutSessionId] (`hashQueryParam("session")`) and [cancelledFlag]
 * (`hashQueryParam("cancelled")`) and polls `getCheckoutSession` (2s interval, hard stop after
 * [MAX_POLL_ATTEMPTS] * [POLL_INTERVAL] ≈ 60s) showing success/pending/cancelled/failed.
 *
 * **The webhook is authoritative, never this screen** -- a `PENDING`/still-`CREATED` state simply
 * means the webhook has not arrived (or been processed) yet and resolves on its own; this screen
 * only ever reads, it never mutates.
 */
fun renderPaymentReturnScreen(
    container: SimplePanel,
    checkoutSessionId: String?,
    cancelledFlag: String?,
) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 560.px
            marginTop = 24.px
        }
    root.h1(tr("Zahlung"))
    val statusHost = root.vPanel(spacing = 8)

    if (checkoutSessionId == null) {
        statusHost.p(tr("Keine Checkout-Session angegeben."))
        return
    }
    if (cancelledFlag == "true") {
        statusHost.p(tr("Die Zahlung wurde abgebrochen. Sie können es jederzeit erneut versuchen."))
        return
    }

    AppScope.launch {
        var attempt = 0
        while (attempt < MAX_POLL_ATTEMPTS) {
            val session =
                try {
                    rpcService<IPaymentGatewayService>().getCheckoutSession(checkoutSessionId)
                } catch (e: NotFoundException) {
                    statusHost.removeAll()
                    statusHost.p(tr("Checkout-Session nicht gefunden."))
                    return@launch
                }
            statusHost.removeAll()
            when (session.status) {
                PaymentCheckoutSessionStatus.COMPLETED -> {
                    statusHost.p(tr("Zahlung erfolgreich abgeschlossen. Vielen Dank!"))
                    return@launch
                }
                PaymentCheckoutSessionStatus.EXPIRED, PaymentCheckoutSessionStatus.FAILED -> {
                    statusHost.p(tr("Die Zahlung konnte nicht abgeschlossen werden. Bitte versuchen Sie es erneut."))
                    return@launch
                }
                PaymentCheckoutSessionStatus.CREATED -> {
                    statusHost.p(
                        tr(
                            "Zahlung wird verarbeitet -- dies kann einen Moment dauern. Diese Seite " +
                                "aktualisiert sich automatisch.",
                        ),
                    )
                }
            }
            attempt++
            delay(POLL_INTERVAL)
        }
        statusHost.removeAll()
        statusHost.p(
            tr(
                "Der Status konnte nicht rechtzeitig bestätigt werden. Bitte prüfen Sie Ihre " +
                    "Beitragsübersicht in einigen Minuten erneut.",
            ),
        )
    }
}
