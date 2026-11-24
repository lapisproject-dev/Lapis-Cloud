package network.lapis.cloud.server.auction.federation.feed

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.auction.federation.AuctionFederationSettings
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.mayPullAuctionFeed
import network.lapis.cloud.server.carpool.feed.DummyVerificationKey
import network.lapis.cloud.server.carpool.feed.VerifiedPartner
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import kotlin.time.Instant

/**
 * V1.9.322 (step c2) -- the authentication of the auction teaser feed, free of HTTP so it can be tested directly. The same order as the carpool gate
 * (cheap to expensive, nothing before the signature reveals anything about a partner), but over the OWN path [AUCTION_TEASER_FEED_PATH]: a signature a
 * partner made for the carpool feed is not valid here. The server's OWN host is part of the signed string (never the Host header of the request).
 * No key is ever fetched here: the pinned key comes from the partner poll.
 */
internal class AuctionTeaserFeedGate(
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
                path = AUCTION_TEASER_FEED_PATH,
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
     * Inside the caller's transaction: re-reads the partner row and returns it only if it is still approved: the pinned key is exactly the verified
     * one and belongs to the row's own host, the partner is active, not blocked and has auction sharing on, the instance switch is on AND the auction
     * and Euro modules are on ([moduleOn]). `null` otherwise.
     */
    fun approve(
        verified: VerifiedPartner,
        settings: AuctionFederationSettings,
        moduleOn: Boolean,
    ): PartnerRow? {
        if (!settings.enabled || !moduleOn) return null
        val row = PartnerServerStore.findPartner(verified.row.id) ?: return null
        if (row.signingKeyId != verified.keyId || row.signingPublicKeyPem != verified.publicKeyPem) return null
        if (keyHost(verified.keyId) != row.host) return null
        if (!row.mayPullAuctionFeed(PartnerServerStore.blockPatterns())) return null
        return row
    }

    /**
     * The trailing answer. Called ONLY after [approve] said no. A partner that was served before and that is still a trusted, active, not blocked
     * partner (same pinned key, key host = own host) gets the withdrawals that are still live for it, even when a switch is off now: switching off must
     * not leave cached hints standing. Never carries items. Everything else stays the one refusal.
     */
    fun approveTrailing(
        verified: VerifiedPartner,
        now: LocalDateTime,
    ): PartnerRow? {
        val row = PartnerServerStore.findPartner(verified.row.id) ?: return null
        if (row.signingKeyId != verified.keyId || row.signingPublicKeyPem != verified.publicKeyPem) return null
        if (keyHost(verified.keyId) != row.host) return null
        if (!row.active) return null
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = PartnerServerStore.blockPatterns())) return null
        if (row.auctionFeedServedCount <= 0) return null
        if (!AuctionFederationStore.hasLiveWithdrawalsFor(partnerId = row.id, now = now)) return null
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
