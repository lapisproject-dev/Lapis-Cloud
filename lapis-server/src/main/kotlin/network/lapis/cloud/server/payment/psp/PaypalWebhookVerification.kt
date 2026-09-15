package network.lapis.cloud.server.payment.psp

import kotlin.time.Duration
import kotlin.time.Instant

/** Erlaubte Hosts für `PAYPAL-CERT-URL` -- diese URL wird NIEMALS selbst abgerufen (siehe Klassen-KDoc), aber an PayPals Verify-API weitergegeben, daher genügt ein Shape-Check. */
private val ALLOWED_CERT_HOSTS = setOf("api-m.paypal.com", "api-m.sandbox.paypal.com", "api.paypal.com", "api.sandbox.paypal.com")

/** Ein einzelner Header-Wert darf höchstens diese Länge haben -- reiner Garbage-Filter, bevor irgendetwas in die Verify-API-Anfrage wandert. */
private const val MAX_HEADER_LENGTH = 512

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- die fünf `PAYPAL-*`-Übertragungs-Header,
 * geparst und formgeprüft, BEVOR irgendein Outbound-Aufruf stattfindet.
 */
internal data class PaypalTransmissionHeaders(
    val transmissionId: String,
    val transmissionTime: String,
    val transmissionSig: String,
    val certUrl: String,
    val authAlgo: String,
) {
    companion object {
        /**
         * `null`, wenn irgendein Header fehlt/leer/übergroß ist (>[MAX_HEADER_LENGTH] Zeichen) oder
         * [certUrl] den Host-Allowlist-Check nicht besteht (https + Host in [ALLOWED_CERT_HOSTS]).
         * Diese URL wird NIEMALS abgerufen -- aber sie wird an PayPals Verify-API weitergegeben,
         * daher hält dieser Shape-Check Unrat aus dem Outbound-Request-Body.
         */
        fun parseOrNull(headerLookup: (String) -> String?): PaypalTransmissionHeaders? {
            fun header(name: String): String? {
                val value = headerLookup(name)?.trim()
                return value?.takeIf { it.isNotBlank() && it.length <= MAX_HEADER_LENGTH }
            }

            val transmissionId = header("PAYPAL-TRANSMISSION-ID") ?: return null
            val transmissionTime = header("PAYPAL-TRANSMISSION-TIME") ?: return null
            val transmissionSig = header("PAYPAL-TRANSMISSION-SIG") ?: return null
            val certUrl = header("PAYPAL-CERT-URL") ?: return null
            val authAlgo = header("PAYPAL-AUTH-ALGO") ?: return null

            val host = runCatching { java.net.URI(certUrl).host }.getOrNull()
            if (!certUrl.startsWith("https://") || host == null || host.lowercase() !in ALLOWED_CERT_HOSTS) {
                return null
            }

            return PaypalTransmissionHeaders(
                transmissionId = transmissionId,
                transmissionTime = transmissionTime,
                transmissionSig = transmissionSig,
                certUrl = certUrl,
                authAlgo = authAlgo,
            )
        }
    }
}

/** Ergebnis einer PayPal-Webhook-Signaturprüfung (Verify-API + Frische-Check zusammen). */
internal sealed interface PaypalSignatureResult {
    data object Valid : PaypalSignatureResult

    /** [reason] ∈ MISSING_HEADERS / MALFORMED_HEADER / NOT_VERIFIED / STALE_TIMESTAMP / FUTURE_TIMESTAMP / VERIFY_UNAVAILABLE. */
    data class Invalid(
        val reason: String,
    ) : PaypalSignatureResult
}

/**
 * Frische-Check auf `PAYPAL-TRANSMISSION-TIME` -- wird NUR AUFGERUFEN, NACHDEM die Verify-API
 * `SUCCESS` gemeldet hat, exakt spiegelnd [StripeSignatureVerifier.verify] Schritt 4's
 * Anti-Timing-Oracle-Reihenfolge. Rein, kein I/O. Erwartet ein RFC-3339-Zeitformat (PayPals
 * `create_time`/Transmission-Time-Format); ein nicht parsbarer Wert -> `Invalid("MALFORMED_HEADER")`.
 */
internal fun checkTransmissionFreshness(
    transmissionTime: String,
    now: Instant,
    tolerance: Duration,
): PaypalSignatureResult {
    val parsed = runCatching { Instant.parse(transmissionTime) }.getOrNull() ?: return PaypalSignatureResult.Invalid("MALFORMED_HEADER")
    val delta = now - parsed
    return when {
        delta > tolerance -> PaypalSignatureResult.Invalid("STALE_TIMESTAMP")
        delta < -tolerance -> PaypalSignatureResult.Invalid("FUTURE_TIMESTAMP")
        else -> PaypalSignatureResult.Valid
    }
}
