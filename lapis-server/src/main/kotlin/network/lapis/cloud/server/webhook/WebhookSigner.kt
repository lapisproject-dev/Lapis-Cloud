package network.lapis.cloud.server.webhook

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- pure HMAC-SHA256 signing of an outbound webhook body. No
 * I/O, no DB, fully unit-testable -- deliberately mirrors
 * `network.lapis.cloud.server.payment.psp.StripeSignatureVerifier`'s own grammar and algorithm
 * (Design-Team decision, see plan §4 "zeichengleich zu Stripes `Stripe-Signature`"), just the
 * SIGNING half rather than the VERIFYING half -- this codebase is now the party sending the
 * signature, not receiving it.
 */
internal object WebhookSigner {
    /** `Lapis-Signature` header name -- see [sign] KDoc for the value grammar. */
    const val SIGNATURE_HEADER = "Lapis-Signature"

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Returns `"t=<unixSeconds>,v1=<lowercase-hex>"` -- zeichengleich zu Stripes `Stripe-Signature`
     * (see `StripeSignatureVerifier.verify` KDoc for the receiving-side grammar this matches
     * exactly; `WebhookSignerTest`'s round-trip test against that verifier is the proof, not the
     * claim).
     *
     * **Signed material**: `HMAC-SHA256(key = secret.toByteArray(UTF_8), data =
     * "$timestampSeconds.".toByteArray(UTF_8) + payload)`, hex-encoded lowercase. Computed over the
     * RAW BYTES of [payload], never over a re-decoded/re-encoded `String` -- a payload that is not
     * valid UTF-8 (should never happen here since [WebhookPayloads] always emits ASCII-safe JSON,
     * but the discipline is deliberately unconditional) must not be silently mangled into a
     * different byte sequence than the one actually sent.
     *
     * **A fresh [Mac] instance is created on every call** -- `Mac.getInstance` is not thread-safe,
     * same house rule `StripeSignatureVerifier.hmacSha256Hex` already follows for the identical
     * reason.
     *
     * **[timestampSeconds] MUST be freshly captured per delivery ATTEMPT, the [payload] bytes
     * NEVER rebuilt per attempt** (S4 in the plan's Stolperfallen list) -- otherwise a retry
     * several hours after the first attempt would carry a `t` far outside any reasonable freshness
     * tolerance a receiver might apply, while a rebuilt payload risks a BigDecimal-scale/field-order
     * drift that silently invalidates the signature the receiver saw on an earlier attempt for the
     * SAME [network.lapis.cloud.shared.domain.WebhookDeliveryDto.id] (`Lapis-Webhook-Id`).
     */
    fun sign(
        payload: ByteArray,
        secret: String,
        timestampSeconds: Long,
    ): String {
        val signedPayload = "$timestampSeconds.".toByteArray(Charsets.UTF_8) + payload
        val hex = hmacSha256Hex(key = secret.toByteArray(Charsets.UTF_8), data = signedPayload)
        return "t=$timestampSeconds,v1=$hex"
    }

    private fun hmacSha256Hex(
        key: ByteArray,
        data: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
