package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.shared.domain.CarpoolTeaser
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * V1.9.314 "Mitfahrzentrale-Foederation" W4 -- the signature of the ORIGIN over one hint. It lets a server that receives a hint through one or more
 * relays check that the content (id, origin host, day, the two postcode areas, the hop limit) really comes from the origin and was not changed on
 * the way. The ONLY place that builds or checks the signed statement (pinned by `CarpoolFederationTripwireTest`).
 *
 * Signed bytes, UTF-8, exactly this and no trailing line feed:
 * `lapis-carpool-teaser-origin/v1 LF teaserId LF originHost LF departureDate LF fromArea LF toArea LF maxHops`
 * Every field is validated strictly first (id = 64 lower-case hex, host = canonical, areas = two digits, maxHops 1..3), so no field can contain a
 * line feed and the encoding is unambiguous. RSA `SHA256withRSA` (PKCS#1 v1.5, deterministic, so the feed ETag stays stable), Base64 standard.
 *
 * Domain separation: with the same key this server otherwise signs only HTTP signing strings, and those always begin with `(request-target): `.
 * A statement begins with [PURPOSE], so one can never be taken for the other.
 *
 * NOT signed: `hops` and `via` (they change at every relay). A dishonest relay can therefore shorten the path or the hop count; the content and the
 * hop limit stay protected.
 */
internal object CarpoolOriginSignatures {
    const val PURPOSE = "lapis-carpool-teaser-origin/v1"
    const val MAX_SIGNATURE_LENGTH = 1024
    private const val CACHE_SIZE = 5000
    private val ID = Regex("^[0-9a-f]{64}$")
    private val AREA = Regex("^[0-9]{2}$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private val cache =
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > CACHE_SIZE
        }

    /** The signed bytes. Throws [IllegalArgumentException] when a field is not in its canonical form. */
    fun statementBytes(
        teaser: CarpoolTeaser,
        originHost: String,
        maxHops: Int,
    ): ByteArray {
        require(ID.matches(teaser.teaserId)) { "teaser id is not 64 lower-case hex" }
        require(PartnerHost.normalizeHostOnly(originHost) == originHost) { "origin host is not canonical" }
        require(AREA.matches(teaser.fromPostcodeArea) && AREA.matches(teaser.toPostcodeArea)) { "postcode area" }
        require(maxHops in 1..3) { "max hops" }
        return listOf(
            PURPOSE,
            teaser.teaserId,
            originHost,
            teaser.departureDate.toString(),
            teaser.fromPostcodeArea,
            teaser.toPostcodeArea,
            maxHops.toString(),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** Signs with the own key; results are kept in a bounded LRU (the method is deterministic, so the cache never changes a byte). */
    fun sign(
        teaser: CarpoolTeaser,
        originHost: String,
        maxHops: Int,
        key: CarpoolOwnSigningKey,
    ): String {
        val statement = statementBytes(teaser = teaser, originHost = originHost, maxHops = maxHops)
        // The cache key binds the key MATERIAL (not only the key id): a rotated key under the same id must never get an old signature back.
        val cacheKey = sha256Hex(key.privateKeyPem.toByteArray(Charsets.UTF_8) + 0.toByte() + statement)
        synchronized(cache) { cache[cacheKey] }?.let { return it }
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(HttpSignatures.decodePrivateKeyPem(key.privateKeyPem))
        signer.update(statement)
        val value = Base64.getEncoder().encodeToString(signer.sign())
        synchronized(cache) { cache[cacheKey] = value }
        return value
    }

    /** Never throws: any malformed input or key is simply "not valid". A new [Signature] per call (not thread-safe). */
    fun verify(
        teaser: CarpoolTeaser,
        originHost: String,
        maxHops: Int,
        signatureB64: String,
        publicKeyPem: String,
    ): Boolean =
        runCatching {
            if (signatureB64.length > MAX_SIGNATURE_LENGTH || !BASE64.matches(signatureB64)) return false
            val statement = statementBytes(teaser = teaser, originHost = originHost, maxHops = maxHops)
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(HttpSignatures.decodePublicKeyPem(publicKeyPem))
            verifier.update(statement)
            verifier.verify(Base64.getDecoder().decode(signatureB64))
        }.getOrDefault(false)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
