package network.lapis.cloud.server.payment.psp

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Instant

/** Outcome of [StripeSignatureVerifier.verify]. */
sealed interface StripeSignatureResult {
    data object Valid : StripeSignatureResult

    /** [reason] is one of `MALFORMED_HEADER`/`NO_MATCHING_V1`/`STALE_TIMESTAMP`/`FUTURE_TIMESTAMP` -- see [StripeSignatureVerifier.verify] KDoc. */
    data class Invalid(
        val reason: String,
    ) : StripeSignatureResult
}

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- pure HMAC-SHA256 verification of a
 * Stripe `Stripe-Signature` webhook header. No I/O, no DB, fully unit-testable -- this is the whole
 * reason Stripe (not PayPal) was chosen for this wave, see `IPaymentGatewayService` class KDoc.
 *
 * Header shape: `t=<unix seconds>,v1=<hex hmac>[,v1=<hex hmac>...]` -- multiple `v1` candidates
 * occur during Stripe endpoint-secret rotation; ANY matching one is accepted.
 */
object StripeSignatureVerifier {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Algorithm, in this exact order (security-review-critical -- see `PspWebhookRoutes` KDoc for
     * the surrounding handler ordering this is embedded in):
     * 1. Parse [signatureHeader] as comma-separated `k=v` pairs. Require exactly one `t` (a
     *    parseable `Long`) and AT LEAST one `v1`. Anything else -> `Invalid("MALFORMED_HEADER")`.
     * 2. Compute `expected = hex(HMAC_SHA256(key = signingSecret, data = "$t." + body))` -- the
     *    signed payload is built from RAW BYTES (`"$t.".toByteArray(UTF_8) + body`), never from
     *    `String(body)` -- a body that is not valid UTF-8 must not be silently mangled into a
     *    different byte sequence than the one Stripe actually signed.
     * 3. Compare `expected` against EVERY `v1` candidate using [MessageDigest.isEqual] on the
     *    DECODED byte arrays (constant-time). A non-hex candidate is skipped, not an error. No
     *    match -> `Invalid("NO_MATCHING_V1")`.
     * 4. ONLY AFTER the HMAC matches, check freshness: `abs(now.epochSeconds - t) <= tolerance` --
     *    otherwise `Invalid("STALE_TIMESTAMP")`/`Invalid("FUTURE_TIMESTAMP")`. Checking freshness
     *    after the MAC prevents an unauthenticated timing oracle on the timestamp.
     *
     * A fresh [Mac] instance is created PER CALL (`Mac.getInstance` is not thread-safe) -- same
     * "MessageDigest neue Instanz pro Aufruf" house rule this codebase already applies to
     * [MessageDigest], extended here to [Mac] for the identical reason.
     */
    fun verify(
        body: ByteArray,
        signatureHeader: String,
        signingSecret: String,
        now: Instant,
        tolerance: Duration,
    ): StripeSignatureResult {
        val pairs =
            signatureHeader
                .split(",")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                }
        val timestampRaw = pairs.singleOrNull { it.first == "t" }?.second
        val v1Candidates = pairs.filter { it.first == "v1" }.map { it.second }
        val timestampSeconds = timestampRaw?.toLongOrNull()
        if (timestampSeconds == null || v1Candidates.isEmpty()) {
            return StripeSignatureResult.Invalid(reason = "MALFORMED_HEADER")
        }

        val signedPayload = "$timestampSeconds.".toByteArray(Charsets.UTF_8) + body
        val expected = hmacSha256Hex(key = signingSecret.toByteArray(Charsets.UTF_8), data = signedPayload)
        val expectedBytes = hexToBytesOrNull(expected) ?: return StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")

        val anyMatch =
            v1Candidates.any { candidate ->
                val candidateBytes = hexToBytesOrNull(candidate) ?: return@any false
                MessageDigest.isEqual(candidateBytes, expectedBytes)
            }
        if (!anyMatch) {
            return StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        val deltaSeconds = now.epochSeconds - timestampSeconds
        return when {
            deltaSeconds > tolerance.inWholeSeconds -> StripeSignatureResult.Invalid(reason = "STALE_TIMESTAMP")
            deltaSeconds < -tolerance.inWholeSeconds -> StripeSignatureResult.Invalid(reason = "FUTURE_TIMESTAMP")
            else -> StripeSignatureResult.Valid
        }
    }

    private fun hmacSha256Hex(
        key: ByteArray,
        data: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    // MINOR fix (code review, Welle V1.2.8): removed a dead `catch (e: IllegalArgumentException)`
    // that used to wrap this builder -- the `if (high < 0 || low < 0) return null` guard already
    // handles the only failure mode `Character.digit` can produce (it returns -1, it never throws),
    // so the catch block was unreachable.
    private fun hexToBytesOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            ((high shl 4) + low).toByte()
        }
    }
}
