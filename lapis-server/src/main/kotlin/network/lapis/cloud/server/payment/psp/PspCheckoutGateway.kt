package network.lapis.cloud.server.payment.psp

import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.shared.domain.PaymentProvider
import java.math.BigDecimal
import java.net.URLEncoder
import kotlin.time.Duration

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- provider-neutrale Return-URL-Paare, aus
 * `StripeCheckoutClient.kt` hierher verschoben (vormals `StripeReturnUrls`) und in `PspReturnUrls`
 * umbenannt, damit BEIDE Anbieter dieselbe Fabrik nutzen. Reines Rename+Move -- keine
 * Verhaltensänderung, alle drei Fabrikmethoden und ihr KDoc bleiben unverändert.
 */
data class PspReturnUrls(
    val successUrl: String,
    val cancelUrl: String,
) {
    companion object {
        /**
         * Das unveränderte V1.2.8-Verhalten für den Mitglieder-Pfad: die Session-Id reist im HASH
         * FRAGMENT, nie als Query-Parameter -- siehe [PspCheckoutGateway.createCheckout] KDoc
         * "`success_url`/`cancel_url`". Welle V1.4.6: `/app`-Präfix, da die Mitglieder-SPA nicht mehr
         * unter `$baseUrl/` liegt -- siehe `network.lapis.cloud.server.routes.PublicLandingRoutes` KDoc.
         */
        fun memberSpa(
            baseUrl: String,
            checkoutSessionId: String,
        ): PspReturnUrls =
            PspReturnUrls(
                successUrl = "$baseUrl/app#/payment-return?session=$checkoutSessionId",
                cancelUrl = "$baseUrl/app#/payment-return?session=$checkoutSessionId&cancelled=true",
            )

        /**
         * Welle V1.4.1b -- zwei feste, öffentliche Pfade OHNE Session-Identifikator: keine neue
         * Token-/Polling-Oberfläche für einen anonymen Spender. [canonicalOrigin] ist IMMER der
         * [network.lapis.cloud.server.embed.EmbedOriginAllowlist]-aufgelöste kanonische Eintrag, nie
         * ein roher, vom Request gelieferter Wert.
         */
        fun embedDonation(
            baseUrl: String,
            canonicalOrigin: String,
        ): PspReturnUrls {
            val encodedOrigin = URLEncoder.encode(canonicalOrigin, Charsets.UTF_8)
            return PspReturnUrls(
                successUrl = "$baseUrl/embed/v1/spende/danke?origin=$encodedOrigin",
                cancelUrl = "$baseUrl/embed/v1/spende/abgebrochen?origin=$encodedOrigin",
            )
        }

        /**
         * Welle V1.4.3.1 -- die serverseitig gerenderten, same-origin Event-Anmeldungs-Rückkehrseiten.
         * [registrationId] reist als normaler Query-Parameter (nicht der Hash-Fragment-Weg der
         * Mitglieder-SPA): anders als [memberSpa] ist dieses Ziel ein klassischer Multi-Page
         * `<form>`-Flow ohne Client-Router, der ein Hash-Fragment überhaupt lesen könnte. Kein Geheimnis
         * -- eine Checkout-Rückkehrseite für eine zufällige UUID, die ein Fremder nicht bereits besitzt,
         * ist funktional dieselbe "leere Bestätigung" wie eine nicht gefundene Anmeldung.
         */
        fun eventRegistration(
            baseUrl: String,
            slug: String,
            registrationId: String,
        ): PspReturnUrls {
            val encodedRegistrationId = URLEncoder.encode(registrationId, Charsets.UTF_8)
            return PspReturnUrls(
                successUrl = "$baseUrl/veranstaltung/$slug/danke?r=$encodedRegistrationId",
                cancelUrl = "$baseUrl/veranstaltung/$slug/abgebrochen?r=$encodedRegistrationId",
            )
        }
    }
}

/** Ergebnis von [PspCheckoutGateway.createCheckout] -- provider-neutrales Gegenstück zu (vormals) `StripeCheckoutResult`. */
sealed interface PspCheckoutResult {
    data class Success(
        val sessionId: String,
        val redirectUrl: String,
        /** Der tatsächlich gesendete Idempotenz-Header-Wert -- vom Aufrufer auf `payment_checkout_session.provider_idempotency_key` persistiert. */
        val idempotencyKey: String,
    ) : PspCheckoutResult

    /** [statusCode] ist der rohe HTTP-Status; [message] die sanitisierte Fehlermeldung des Anbieters -- NIEMALS die Request-Header/Zugangsdaten. */
    data class Failure(
        val statusCode: Int,
        val message: String,
    ) : PspCheckoutResult
}

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- die EINE Abstraktion, von der jede
 * Checkout-Aufrufstelle abhängt, statt von einem konkreten PSP-Client. [StripeCheckoutClient] und
 * [PaypalOrdersClient] sind die beiden Implementierungen. `internal` -- kein anderes Modul
 * (`lapis-client`) darf gegen diese Server-Abstraktion linken.
 */
interface PspCheckoutGateway {
    val provider: PaymentProvider

    /**
     * Erzeugt eine Checkout-Sitzung beim Anbieter für [amount] (EUR, exakter Dezimalwert -- jede
     * Implementierung wandelt in ihre eigene Draht-Form um, NIEMALS über [Double]).
     * [checkoutSessionId] ist diese Server-eigene `payment_checkout_session.id`
     * (Stripe: `client_reference_id`; PayPal: `custom_id`). [returnUrls] trägt
     * `success_url`/`cancel_url` -- kein Default-Wert, jeder Aufrufer muss explizit angeben, wohin
     * sein Zahler zurückkehrt (ein Geldpfad -- ein versteckter Default wäre hier die falsche
     * Ergonomie).
     */
    suspend fun createCheckout(
        checkoutSessionId: String,
        amount: BigDecimal,
        currency: String,
        description: String,
        returnUrls: PspReturnUrls,
    ): PspCheckoutResult

    /** Der serverseitige Abuse-/DoS-Deckel auf `createDonationCheckout` -- vom jeweiligen `*Config.maxCheckoutAmountEur` gespeist. */
    val maxCheckoutAmountEur: BigDecimal

    /** Treibt NUR `payment_checkout_session.expires_at` dieses Servers -- siehe `PspConfig.checkoutTtlMinutes` KDoc. */
    val checkoutTtlMinutes: Long

    /**
     * Welle V1.2.8b, Pitfall §6.11 des Implementierungsplans -- die Lebensdauer, mit der der
     * Anbieter selbst seine eigene Checkout-Sitzung ablaufen lässt: Stripe ~24h, PayPal ~3h. Nutzt
     * `EventPolicy`, um `event_registration.expires_at` NIE höher als die reale Anbieter-Ablaufzeit
     * zu deckeln -- ein `findReusableForRegistration`-Treffer darf sonst eine bereits vom Anbieter
     * abgelaufene, tote `redirectUrl` zurückliefern.
     */
    val sessionLifetimeCap: Duration
        get() = EventPolicy.STRIPE_SESSION_LIFETIME_CAP
}

/**
 * Welle V1.2.8b -- ein PSP-neutrales, BEREITS signaturgeprüftes "das Geld ist angekommen"-Ereignis.
 * Von der jeweiligen Provider-Webhook-Route NACH erfolgreicher Verifikation gebaut; der
 * Ingestion-Pfad selbst führt KEINE Signaturprüfung durch (unveränderter Vertrag, siehe
 * [PspWebhookIngestion] KDoc).
 */
internal data class PspPaymentEvent(
    val provider: PaymentProvider,
    /** Idempotenz-Anker -- `uq_payment_transaction_provider_event`. Stripe: `event.id`. PayPal: `event.id`. */
    val providerEventId: String,
    /** Join-Schlüssel zu `payment_checkout_session.provider_session_id`. Stripe: `session.id`. PayPal: Order-Id. */
    val providerSessionId: String,
    /** `payment_transaction.provider_payment_id`. Stripe: `payment_intent ?: session.id`. PayPal: Capture-Id. */
    val providerPaymentId: String,
    /** Bereits von der Provider-Adapter-Schicht auf ein skala-2 [BigDecimal] umgerechnet -- NIEMALS über [Double]. */
    val amount: BigDecimal?,
    val currency: String?,
    /** Normalisiert auf "paid" / abweichend / `null`. Stripe: `payment_status`. PayPal: Capture-Status `COMPLETED` -> "paid". */
    val paymentStatus: String?,
    /** `payment_transaction.payer_reference`. Stripe: `session.customer`. PayPal: `payer.payer_id`. */
    val payerReference: String?,
)
