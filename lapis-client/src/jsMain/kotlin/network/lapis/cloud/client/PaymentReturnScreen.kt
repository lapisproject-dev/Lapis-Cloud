package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
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
 *
 * Welle V1.2.9 fixes:
 * - The status paragraph is created ONCE and updated in place (`statusText.content = ...`)
 *   instead of `removeAll()` + a fresh `p(...)` on every poll tick -- the old code re-announced
 *   the exact same "please wait" message to a screen reader up to [MAX_POLL_ATTEMPTS] times.
 * - The completion/failure/timeout text now branches on [network.lapis.cloud.shared.domain
 *   .CheckoutSessionDto.intent]: a donor and a member paying a contribution are different
 *   audiences and land on different follow-up screens.
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

    val statusText = statusHost.p("")
    val actionHost = statusHost.vPanel(spacing = 8)

    fun renderFollowUpAction(intent: PaymentIntent?) {
        when (intent) {
            PaymentIntent.CONTRIBUTION -> {
                val link = actionHost.button(tr("Zur Beitragsübersicht"), style = ButtonStyle.LINK)
                link.onClick { navigateTo(Routes.CONTRIBUTIONS) }
            }
            PaymentIntent.DONATION -> {
                val link = actionHost.button(tr("Zur Startseite"), style = ButtonStyle.LINK)
                link.onClick { navigateTo(Routes.DASHBOARD) }
            }
            null -> Unit
        }
    }

    AppScope.launch {
        var attempt = 0
        // Tracks the last SEEN session's intent so the timeout branch below (which never sees a
        // fresh session) can still pick the right follow-up wording/link -- `null` only if every
        // single poll attempt raced a NotFoundException, which returns@launch immediately anyway.
        var lastIntent: PaymentIntent? = null
        while (attempt < MAX_POLL_ATTEMPTS) {
            val session =
                try {
                    rpcService<IPaymentGatewayService>().getCheckoutSession(checkoutSessionId)
                } catch (e: NotFoundException) {
                    statusText.content = tr("Checkout-Session nicht gefunden.")
                    return@launch
                }
            lastIntent = session.intent
            when (session.status) {
                PaymentCheckoutSessionStatus.COMPLETED -> {
                    statusText.content =
                        when (session.intent) {
                            PaymentIntent.CONTRIBUTION -> tr("Zahlung erfolgreich abgeschlossen. Vielen Dank!")
                            PaymentIntent.DONATION ->
                                tr("Vielen Dank für Ihre Spende! Die Zahlung wurde erfolgreich abgeschlossen.")
                        }
                    renderFollowUpAction(session.intent)
                    return@launch
                }
                PaymentCheckoutSessionStatus.EXPIRED, PaymentCheckoutSessionStatus.FAILED -> {
                    statusText.content =
                        when (session.intent) {
                            PaymentIntent.CONTRIBUTION ->
                                tr("Die Zahlung konnte nicht abgeschlossen werden. Bitte versuchen Sie es erneut.")
                            PaymentIntent.DONATION ->
                                tr("Die Spende konnte nicht abgeschlossen werden. Bitte versuchen Sie es erneut.")
                        }
                    renderFollowUpAction(session.intent)
                    return@launch
                }
                PaymentCheckoutSessionStatus.CREATED -> {
                    statusText.content =
                        tr(
                            "Zahlung wird verarbeitet -- dies kann einen Moment dauern. Diese Seite " +
                                "aktualisiert sich automatisch.",
                        )
                }
            }
            attempt++
            delay(POLL_INTERVAL)
        }
        statusText.content =
            when (lastIntent) {
                PaymentIntent.CONTRIBUTION, null ->
                    tr(
                        "Der Status konnte nicht rechtzeitig bestätigt werden. Bitte prüfen Sie Ihre " +
                            "Beitragsübersicht in einigen Minuten erneut.",
                    )
                PaymentIntent.DONATION ->
                    tr(
                        "Der Status konnte nicht rechtzeitig bestätigt werden. Bitte prüfen Sie in einigen " +
                            "Minuten erneut, ob Ihre Spende verbucht wurde.",
                    )
            }
        renderFollowUpAction(lastIntent)
    }
}
