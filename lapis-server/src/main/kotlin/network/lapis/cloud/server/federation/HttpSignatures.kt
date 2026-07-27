package network.lapis.cloud.server.federation

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val SIGNED_HEADERS = "(request-target) host date digest"
private const val SIGNATURE_ALGORITHM = "rsa-sha256"
private const val JCA_SIGNATURE_ALGORITHM = "SHA256withRSA"

const val REASON_MISSING_HEADER = "MISSING_HEADER"
const val REASON_MALFORMED = "MALFORMED"
const val REASON_STALE = "STALE"
const val REASON_DIGEST_MISMATCH = "DIGEST_MISMATCH"
const val REASON_SIGNATURE_MISMATCH = "SIGNATURE_MISMATCH"

/**
 * [draft-cavage-http-signatures-12](https://datatracker.ietf.org/doc/html/draft-cavage-http-signatures-12)
 * `Signature:` header sign/verify (V0.8.1 Federation-Grundgerüst) -- the HTTP-Signature scheme
 * this project deliberately chose over the newer RFC 9421 for server-to-server federation
 * delivery. See [network.lapis.cloud.shared.rpc.IFederationService] KDoc "HTTP Signatures" for the
 * full rationale (real-world Fediverse interoperability with Mastodon/Pleroma/Akkoma/Misskey,
 * which as of this wave still speak draft-cavage, not RFC 9421).
 *
 * **Algorithm choice**: RSA-2048 + `rsa-sha256` ([JCA_SIGNATURE_ALGORITHM] = `SHA256withRSA`) --
 * the near-universal Fediverse choice, unlike Ed25519's still-partial `hs2019` support. Matches
 * [FederationKeyPairGenerator]'s own key generation.
 *
 * **Signed headers**: [SIGNED_HEADERS] = `(request-target) host date digest` -- the exact header
 * set Mastodon requires for inbox delivery.
 *
 * **Structured, isolated for additive RFC 9421 support later**: [verify]'s signing-string
 * reconstruction is driven entirely by the `headers=` list parsed out of the caller's own
 * `Signature:` header value, not a hardcoded assumption -- a future wave adding RFC 9421 as an
 * *additional*, preferred scheme (once real-world adoption grows) can do so alongside this
 * function rather than rewriting it.
 *
 * **Never throws**: every parse/verify failure in [verify] maps to a typed
 * [VerificationResult.Invalid] reason -- callers (`FederationRoutes`' inbox handler) rely on this
 * to safely process attacker-controlled header values without a `try`/`catch` at the call site.
 */
object HttpSignatures {
    /** Freshness window for the `date` header -- both directions (reject stale AND far-future, small clock-skew allowance). Also the TTL [FederationReplayGuard] uses for its own seen-signature cache. */
    val FRESHNESS_WINDOW: Duration = 5.minutes

    private val HTTP_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    data class SignedRequest(
        val signatureHeader: String,
        val dateHeader: String,
        val digestHeader: String,
    )

    sealed interface VerificationResult {
        data object Valid : VerificationResult

        data class Invalid(
            val reason: String,
        ) : VerificationResult
    }

    /**
     * Builds the cavage signing string over `(request-target)`/`host`/`date`/`digest` and signs
     * it with [privateKeyPem] (RSA-2048, `SHA256withRSA`). `keyId` is conventionally
     * `"$actorUri#main-key"` per Fediverse convention -- the caller supplies the full value.
     */
    fun sign(
        method: String,
        path: String,
        host: String,
        body: ByteArray,
        keyId: String,
        privateKeyPem: String,
        now: Instant = Clock.System.now(),
    ): SignedRequest {
        val dateHeader = formatHttpDate(now)
        val digestHeader = digestOf(body)
        val signingString = buildSigningString(SIGNED_HEADERS, method, path, host, dateHeader, digestHeader)
        val privateKey = decodePrivateKeyPem(privateKeyPem)
        val signature = Signature.getInstance(JCA_SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(signingString.toByteArray(Charsets.UTF_8))
        val signatureB64 = Base64.getEncoder().encodeToString(signature.sign())
        val signatureHeader =
            "keyId=\"$keyId\",algorithm=\"$SIGNATURE_ALGORITHM\",headers=\"$SIGNED_HEADERS\",signature=\"$signatureB64\""
        return SignedRequest(signatureHeader = signatureHeader, dateHeader = dateHeader, digestHeader = digestHeader)
    }

    /**
     * Verifies [signatureHeader]/[dateHeader]/[digestHeader] against [publicKeyPem],
     * reconstructing the exact signing string the header's own `headers=` list names. Never
     * throws -- every failure maps to [VerificationResult.Invalid]. Check order (cheapest/most
     * information-revealing first): missing headers -> malformed structure -> stale date ->
     * digest-vs-body mismatch -> signature-vs-key mismatch.
     */
    fun verify(
        method: String,
        path: String,
        host: String,
        body: ByteArray,
        signatureHeader: String?,
        dateHeader: String?,
        digestHeader: String?,
        publicKeyPem: String,
        now: Instant = Clock.System.now(),
    ): VerificationResult {
        if (signatureHeader.isNullOrBlank() || dateHeader.isNullOrBlank() || digestHeader.isNullOrBlank()) {
            return VerificationResult.Invalid(REASON_MISSING_HEADER)
        }

        val params = parseSignatureHeader(signatureHeader) ?: return VerificationResult.Invalid(REASON_MALFORMED)
        val keyId = params["keyId"]
        val headersList = params["headers"]
        val signatureB64 = params["signature"]
        if (keyId.isNullOrBlank() || headersList.isNullOrBlank() || signatureB64.isNullOrBlank()) {
            return VerificationResult.Invalid(REASON_MALFORMED)
        }
        // Round-1 review fix (MAJOR): the sender's own `headers=` list, not [SIGNED_HEADERS], drove
        // signing-string reconstruction below -- an attacker who can produce ANY validly-signed
        // request (e.g. under their own, legitimately-provisioned key/actor) could claim a SHORTER
        // `headers=` list, e.g. `headers="date"` alone. The signature would then cover only the date
        // header -- NOT `(request-target)`/`host`/`digest` -- so it would say nothing about which
        // path was requested or, critically, protect the body at all (the `digestHeader`-vs-actual-body
        // check below is necessary but NOT sufficient for integrity, since a signature that never
        // covered `digest` lets an attacker swap `digest`/body freely while keeping a signature that
        // still verifies). Require the full [SIGNED_HEADERS] set -- extra/reordered entries are fine,
        // but every one of `(request-target)`/`host`/`date`/`digest` MUST be present.
        val signedHeaderNames =
            headersList
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .toSet()
        val requiredHeaderNames = SIGNED_HEADERS.split(" ").toSet()
        if (!signedHeaderNames.containsAll(requiredHeaderNames)) {
            return VerificationResult.Invalid(REASON_MALFORMED)
        }
        val signatureBytes =
            runCatching { Base64.getDecoder().decode(signatureB64) }.getOrNull()
                ?: return VerificationResult.Invalid(REASON_MALFORMED)

        val requestDate =
            runCatching { parseHttpDate(dateHeader) }.getOrNull()
                ?: return VerificationResult.Invalid(REASON_MALFORMED)
        val age = if (now > requestDate) now - requestDate else requestDate - now
        if (age > FRESHNESS_WINDOW) return VerificationResult.Invalid(REASON_STALE)

        val actualDigest = digestOf(body)
        if (!MessageDigest.isEqual(actualDigest.toByteArray(Charsets.UTF_8), digestHeader.toByteArray(Charsets.UTF_8))) {
            return VerificationResult.Invalid(REASON_DIGEST_MISMATCH)
        }

        val publicKey =
            runCatching { decodePublicKeyPem(publicKeyPem) }.getOrNull()
                ?: return VerificationResult.Invalid(REASON_MALFORMED)
        val signingString =
            runCatching { buildSigningString(headersList, method, path, host, dateHeader, digestHeader) }.getOrNull()
                ?: return VerificationResult.Invalid(REASON_MALFORMED)

        val signature = Signature.getInstance(JCA_SIGNATURE_ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(signingString.toByteArray(Charsets.UTF_8))
        val valid = runCatching { signature.verify(signatureBytes) }.getOrDefault(false)
        return if (valid) VerificationResult.Valid else VerificationResult.Invalid(REASON_SIGNATURE_MISMATCH)
    }

    /** Extracts the bare `keyId` from a raw `Signature:` header without verifying anything -- used to look up/fetch the right public key before calling [verify]. `null` on any parse failure. */
    fun extractKeyId(signatureHeader: String?): String? {
        if (signatureHeader.isNullOrBlank()) return null
        return parseSignatureHeader(signatureHeader)?.get("keyId")
    }

    private fun digestOf(body: ByteArray): String =
        "SHA-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))

    private fun buildSigningString(
        headersList: String,
        method: String,
        path: String,
        host: String,
        date: String,
        digest: String,
    ): String =
        headersList
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .also { require(it.isNotEmpty()) { "headers list must not be empty" } }
            .joinToString("\n") { header ->
                when (header) {
                    "(request-target)" -> "(request-target): ${method.lowercase()} $path"
                    "host" -> "host: $host"
                    "date" -> "date: $date"
                    "digest" -> "digest: $digest"
                    else -> throw IllegalArgumentException("Unsupported signed header: $header")
                }
            }

    /** `key="value"` comma-separated parser for the `Signature:` header -- `null` on any structural failure, never throws. */
    private fun parseSignatureHeader(signatureHeader: String): Map<String, String>? {
        val regex = Regex("""(\w+)="([^"]*)"""")
        val matches = regex.findAll(signatureHeader).toList()
        if (matches.isEmpty()) return null
        return matches.associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun formatHttpDate(instant: Instant): String =
        HTTP_DATE_FORMATTER.format(
            java.time.Instant
                .ofEpochMilli(instant.toEpochMilliseconds())
                .atZone(ZoneOffset.UTC),
        )

    private fun parseHttpDate(value: String): Instant {
        val zonedDateTime: ZonedDateTime = ZonedDateTime.parse(value, HTTP_DATE_FORMATTER)
        return Instant.fromEpochMilliseconds(zonedDateTime.toInstant().toEpochMilli())
    }

    private fun decodePublicKeyPem(pem: String): PublicKey {
        val der = stripPem(pem)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun decodePrivateKeyPem(pem: String): PrivateKey {
        val der = stripPem(pem)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun stripPem(pem: String): ByteArray {
        val base64 = pem.lineSequence().filterNot { it.isBlank() || it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(base64)
    }
}
