package network.lapis.cloud.server.auction.federation

import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserRules
import network.lapis.cloud.shared.domain.isCanonicalFederationHost
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * V1.9.322 (step c2) -- the signature of the ORIGIN over one auction hint. A receiver that gets a hint through a relay can check that the content
 * (id, origin host, revision, title, currency, price, end, hop limit ...) really comes from the origin and was not changed on the way. The ONLY place
 * that builds or checks the signed statement (pinned by `AuctionFederationTripwireTest`).
 *
 * Signed bytes, UTF-8, LF-separated, no trailing line feed, in this order:
 * `PURPOSE, teaserId, originHost, revision, base64url(UTF-8 title, no padding), currency, priceKind, priceMinor, buyNowMinor or "-", "1"|"0"
 * (offersPossible), endsAt, priceAsOf, maxHops`. The title is encoded so that no field can contain a line feed. Every field is validated strictly
 * first, so the encoding is unambiguous. RSA `SHA256withRSA` (deterministic, so the feed ETag stays stable), Base64 standard.
 *
 * Domain separation: with the same key this server otherwise signs HTTP signing strings (they begin with `(request-target): `) and the carpool
 * statement (a different PURPOSE). NOT signed: `hops` and `via` (they change at every relay; a dishonest relay can shorten the path or the hop count,
 * the content and the hop limit stay protected).
 *
 * The own key is the federation key of this server ([network.lapis.cloud.server.carpool.CarpoolOwnKeys], named after its first user).
 */
internal object AuctionOriginSignatures {
    const val PURPOSE = "lapis-auction-teaser-origin/v1"
    const val MAX_SIGNATURE_LENGTH = 1024
    private const val CACHE_SIZE = 5000
    private val ID = Regex("^[0-9a-f]{64}$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private val cache =
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > CACHE_SIZE
        }

    /** The signed bytes. Throws [IllegalArgumentException] when a field is not in its canonical form. */
    fun statementBytes(
        teaser: AuctionTeaser,
        originHost: String,
        maxHops: Int,
    ): ByteArray {
        require(ID.matches(teaser.teaserId)) { "teaser id is not 64 lower-case hex" }
        require(
            PartnerHost.normalizeHostOnly(originHost) == originHost && isCanonicalFederationHost(originHost),
        ) { "origin host is not canonical" }
        require(AuctionTeaserRules.titleProblem(teaser.title) == null) { "title" }
        require(teaser.currency in AuctionCurrencies.SUPPORTED) { "currency" }
        require(teaser.priceMinor in 1..AuctionCurrencies.MAX_MINOR) { "price" }
        val buyNow = teaser.buyNowMinor
        require(buyNow == null || (buyNow > teaser.priceMinor && buyNow <= AuctionCurrencies.MAX_MINOR)) { "buy now price" }
        require(maxHops in AuctionFederationLimits.MIN_HOPS..AuctionFederationLimits.MAX_HOPS) { "max hops" }
        require(teaser.revision >= 1) { "revision" }
        require(teaser.endsAt.nanosecondsOfSecond == 0 && teaser.endsAt.epochSeconds % 60L == 0L) { "endsAt is not floored to the minute" }
        require(
            teaser.priceAsOf.nanosecondsOfSecond == 0 && teaser.priceAsOf.epochSeconds % 60L == 0L,
        ) { "priceAsOf is not floored to the minute" }
        require(teaser.priceAsOf <= teaser.endsAt) { "priceAsOf after endsAt" }
        val titleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(teaser.title.toByteArray(Charsets.UTF_8))
        return listOf(
            PURPOSE,
            teaser.teaserId,
            originHost,
            teaser.revision.toString(),
            titleB64,
            teaser.currency,
            teaser.priceKind.name,
            teaser.priceMinor.toString(),
            buyNow?.toString() ?: "-",
            if (teaser.offersPossible) "1" else "0",
            teaser.endsAt.toString(),
            teaser.priceAsOf.toString(),
            maxHops.toString(),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** Signs with the own key; results are kept in a bounded LRU (deterministic, so the cache never changes a byte). */
    fun sign(
        teaser: AuctionTeaser,
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
        teaser: AuctionTeaser,
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
