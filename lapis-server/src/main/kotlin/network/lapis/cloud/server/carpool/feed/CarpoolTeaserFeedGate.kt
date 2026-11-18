package network.lapis.cloud.server.carpool.feed

import network.lapis.cloud.server.carpool.CarpoolFederationSettings
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import kotlin.time.Instant

/** A request whose signature verified against the PINNED key of exactly one partner row. NOT yet approved (see [CarpoolTeaserFeedGate.approve]). */
internal class VerifiedPartner(
    val row: PartnerRow,
    val keyId: String,
    val publicKeyPem: String,
)

/**
 * A throw-away RSA-2048 public key, created once. A request with an unknown key id is verified against it, so an unknown key id costs the same
 * RSA operation as a known one (no timing oracle on which key ids exist). It never verifies anything: nobody holds its private half.
 */
internal object DummyVerificationKey {
    val publicKeyPem: String by lazy { FederationKeyPairGenerator.generate().publicKeyPem }
}

/**
 * V1.9.312 "Mitfahrzentrale-Foederation" W2 -- the authentication of the teaser feed, free of HTTP so it can be tested directly.
 *
 * Order (cheap to expensive, nothing before the signature reveals anything about a partner):
 *  1. header sizes, no query string, no body;
 *  2. the key id of the signature -> the partner rows that PINNED exactly that key id (one short read transaction, nothing else);
 *  3. the signature, over `GET <constant path>` and the server's OWN host ([ownHost], NEVER the Host header of the request: a signature a partner
 *     made for ANOTHER server must not be replayable here), against the pinned key -- or the [DummyVerificationKey] when there is no unique hit;
 *  4. the replay guard (only a valid signature is remembered).
 * [approve] then checks the consent and the switches inside the caller's transaction.
 *
 * No key is ever fetched here: the pinned key comes from the partner poll (`PartnerServerStore.applyFetchSuccess`).
 */
internal class CarpoolTeaserFeedGate(
    private val replayGuard: FederationReplayGuard,
    private val ownHost: String,
    private val clock: () -> Instant,
    private val dummyKey: () -> String = { DummyVerificationKey.publicKeyPem },
) {
    fun authenticate(
        signature: String?,
        date: String?,
        digest: String?,
        contentLength: String?,
        transferEncoding: String?,
        queryString: String,
    ): VerifiedPartner? {
        if (queryString.isNotEmpty() || transferEncoding != null) return null
        if (contentLength != null && contentLength.trim() != "0") return null
        if ((signature?.length ?: 0) > MAX_SIGNATURE || (date?.length ?: 0) > MAX_DATE || (digest?.length ?: 0) > MAX_DIGEST) return null
        val keyId = HttpSignatures.extractKeyId(signature)?.takeIf { it.length <= MAX_KEY_ID } ?: return null
        val hits = transaction { PartnerServerStore.findBySigningKeyId(keyId) }
        val hit = hits.singleOrNull()?.takeIf { it.signingPublicKeyPem != null && it.signingKeyId == keyId }
        val result =
            HttpSignatures.verify(
                method = "GET",
                path = CARPOOL_TEASER_FEED_PATH,
                host = ownHost,
                body = ByteArray(0),
                signatureHeader = signature,
                dateHeader = date,
                digestHeader = digest,
                publicKeyPem = hit?.signingPublicKeyPem ?: dummyKey(),
                now = clock(),
            )
        if (hit == null || result !is HttpSignatures.VerificationResult.Valid) return null
        if (!replayGuard.checkAndRecord(requireNotNull(signature))) return null
        return VerifiedPartner(row = hit, keyId = keyId, publicKeyPem = requireNotNull(hit.signingPublicKeyPem))
    }

    /**
     * Inside the caller's transaction: re-reads the partner row (the pin may have changed or the partner may have been removed or blocked since
     * [authenticate]) and returns it only if it is still approved: the pinned key is exactly the verified one and belongs to the row's own host,
     * the partner is active, not blocked and has carpool sharing on, and the instance switch is on. `null` otherwise.
     */
    fun approve(
        verified: VerifiedPartner,
        settings: CarpoolFederationSettings,
    ): PartnerRow? {
        if (!settings.enabled) return null
        val row = PartnerServerStore.findPartner(verified.row.id) ?: return null
        if (row.signingKeyId != verified.keyId || row.signingPublicKeyPem != verified.publicKeyPem) return null
        if (keyHost(verified.keyId) != row.host) return null
        if (!row.active || !row.carpoolSharing) return null
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = PartnerServerStore.blockPatterns())) return null
        return row
    }

    private fun keyHost(keyId: String): String? =
        runCatching { URI(keyId).host }.getOrNull()?.trimEnd('.')?.let { PartnerHost.normalizeHostOnly(it) }

    private companion object {
        const val MAX_SIGNATURE = 2048
        const val MAX_DATE = 64
        const val MAX_DIGEST = 128
        const val MAX_KEY_ID = 512
    }
}
