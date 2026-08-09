package network.lapis.cloud.server.conference

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 audit-round-1 fix -- mints short-lived, scoped TURN
 * relay credentials using coturn's own "REST API for Access to TURN Services" shared-secret scheme
 * (`use-auth-secret`/`static-auth-secret` in `deploy/local/turnserver.conf`, the same mechanism
 * coturn's own docs describe and which every major WebRTC SFU vendor's TURN integration guide
 * recommends over [ConferenceConfig.turnSharedSecret] never leaving this process). This closes the
 * audit-round-1 gap in the OLD `rtc.turn_servers` static-credential approach (`username: lapis`,
 * `credential: lapis-dev-turn-secret`, formerly baked into `deploy/local/livekit.yaml` and handed to
 * EVERY client, valid forever, independent of room membership or session expiry) -- see
 * `deploy/local/livekit.yaml`'s own file header for the removed block's history.
 *
 * **The scheme**: `username = "<expiryEpochSeconds>:<label>"`, `credential =
 * base64(HMAC-SHA1(sharedSecret, username))`. coturn parses the numeric prefix before the FIRST `:`
 * out of `username` itself to determine expiry -- there is no separate "expires_in" wire field, the
 * timestamp IS the credential's own expiry, exactly mirroring how [LiveKitAccessToken]'s JWT carries
 * its own `exp` claim. [label] is cosmetic (coturn logs it, nothing else parses it) -- this codebase
 * always passes the LiveKit participant identity (the caller's member UUID), same identity
 * [LiveKitAccessToken.mintParticipantToken]'s `sub` claim carries, purely for correlating a coturn
 * log line back to a member if ever needed.
 *
 * **HMAC-SHA1, not SHA256** -- this is coturn's own wire protocol requirement (`lt-cred-mech`'s
 * REST-API variant), not a choice this codebase gets to make; a client/relay MUST agree on SHA1 here
 * or authentication silently fails. This is unrelated to, and does not weaken,
 * [LiveKitAccessToken]'s own HS256 JWT signing -- two independent credential systems (LiveKit
 * participant token vs. TURN relay credential) that happen to both be HMAC-based.
 */
object TurnCredentialMinter {
    /** One minted TURN credential -- [username]/[credential] are the exact `username`/`credential` fields `RTCIceServer` (WebRTC) expects; [urls] mirrors the [ConferenceConfig.turnUrls] this instance was minted for. */
    data class TurnCredential(
        val username: String,
        val credential: String,
        val urls: List<String>,
        val expiresAt: Instant,
    )

    /**
     * @param sharedSecret must equal `deploy/local/turnserver.conf`'s `static-auth-secret` value --
     *   see [ConferenceConfig.turnSharedSecret]. **Never logged, never included in any exception
     *   message** -- same discipline [ConferenceConfig.apiSecret] documents for the LiveKit secret.
     * @param label see class KDoc -- cosmetic, always the LiveKit participant identity at this
     *   codebase's one call site ([network.lapis.cloud.server.rpc.ConferenceService.joinRoom]).
     */
    fun mint(
        sharedSecret: String,
        urls: List<String>,
        label: String,
        ttl: Duration,
        now: Instant = Clock.System.now(),
    ): TurnCredential {
        val expiresAt = now + ttl
        val username = "${expiresAt.epochSeconds}:$label"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(sharedSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val credential = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(Charsets.UTF_8)))
        return TurnCredential(username = username, credential = credential, urls = urls, expiresAt = expiresAt)
    }
}
