package network.lapis.cloud.server.conference

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- mints LiveKit access tokens (HS256 JWS,
 * `nimbus-jose-jwt`'s [MACSigner]) in exactly the two shapes this wave ever needs. See
 * `gradle/libs.versions.toml` for why nimbus (already a dependency, see
 * [network.lapis.cloud.server.federation.OidcJwt]) rather than the `io.livekit:livekit-server`
 * SDK's own `AccessToken` builder: here we only ever SIGN, with ONE fixed algorithm (HS256), never
 * verify an incoming, attacker-controlled `alg` header -- exactly the narrow case
 * [network.lapis.cloud.server.federation.OidcJwt]'s own "why a library, not hand-rolled" KDoc says
 * does NOT need a JOSE library's alg-confusion defenses (this is a signer, not a verifier).
 *
 * **The two shapes are never mixed, and are structurally distinguishable by their `video` grant
 * alone**: [mintParticipantToken] sets `roomJoin`/`canPublish`/`canSubscribe`/`canPublishData` and
 * NEVER `roomCreate`/`roomAdmin`/`roomList`; [mintAdminToken] sets `roomCreate`/`roomAdmin`/
 * `roomList` and NEVER `roomJoin`. Only [mintParticipantToken]'s output is ever serialized into a
 * DTO reaching a browser (a future wave's `ConferenceJoinTokenDto.token`) -- [mintAdminToken]'s
 * output lives exclusively inside [LiveKitAdminClient], server-internal, one mint per Twirp call.
 *
 * **Empirically verified against a live LiveKit v1.13.5 instance (2026-08-09, via
 * `deploy/local/docker-compose.yml`)**, correcting an assumption from the LiveKit server-API docs
 * alone: a `roomAdmin: true` grant WITHOUT a matching `room` claim is REJECTED (`401
 * "permissions denied"`) by `ListParticipants`/`RemoveParticipant` -- the admin token must carry
 * `video.room` equal to the target room name for those two calls. `CreateRoom`/`DeleteRoom`
 * (gated on `roomCreate`, not `roomAdmin`) succeeded in the same live test with NO `room` claim at
 * all. [mintAdminToken]'s `room` parameter is therefore REQUIRED (not merely advisory) for any
 * [LiveKitAdminClient] call that ends up permission-checked against `roomAdmin` -- see
 * [LiveKitAdminClient]'s own call sites for which methods pass it.
 */
object LiveKitAccessToken {
    /**
     * Admin (server-internal) tokens are minted fresh per Twirp call, never cached or reused --
     * 60 seconds is ample for one HTTP round-trip and keeps the blast radius of a
     * theoretically-leaked admin token (e.g. via a logging bug elsewhere) to almost nothing. Never
     * confused with [network.lapis.cloud.server.conference.ConferenceConfig.tokenTtlMinutes], which
     * governs the much longer-lived participant join token.
     */
    const val ADMIN_TOKEN_TTL_SECONDS = 60L

    /** One minted participant token plus the [expiresAt] instant a caller needs to populate `ConferenceJoinTokenDto.expiresAt` without re-deriving it from [Clock] a second time. */
    data class ParticipantToken(
        val jwt: String,
        val expiresAt: Instant,
    )

    /**
     * Mints a room-pinned participant join token -- the ONLY LiveKit token shape ever sent to a
     * browser. Grants exactly `roomJoin`/`canPublish`/`canSubscribe`/`canPublishData` on
     * [roomName]; never `canUpdateOwnMetadata`, never `hidden`, never `recorder`, never any
     * `room*Create/Admin/List` admin grant. Because the grant is pinned to one room name, holding
     * this token grants nothing beyond the room the caller (a future wave's `ConferenceService`)
     * already authorized -- there is no broader capability to escalate to.
     *
     * @param identity the LiveKit participant identity -- always the member's UUID string (never a
     *   display name or email), matching `ConferenceJoinTokenDto.identity`'s documented contract so
     *   a receiving client can trust `RemoteParticipant.identity` as a server-verified member id.
     * @param displayName rendered as the JWT `name` claim, which `livekit-client` surfaces as
     *   `RemoteParticipant.name` -- purely cosmetic, never used for any authorization decision.
     */
    fun mintParticipantToken(
        apiKey: String,
        apiSecret: String,
        roomName: String,
        identity: String,
        displayName: String,
        ttl: Duration,
        now: Instant = Clock.System.now(),
    ): ParticipantToken {
        val expiresAt = now + ttl
        val videoGrant =
            linkedMapOf<String, Any>(
                "room" to roomName,
                "roomJoin" to true,
                "canPublish" to true,
                "canSubscribe" to true,
                "canPublishData" to true,
                "canUpdateOwnMetadata" to false,
            )
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(apiKey)
                .subject(identity)
                .notBeforeTime(toJavaDate(now))
                .expirationTime(toJavaDate(expiresAt))
                .jwtID(Uuid.random().toString())
                .claim("name", displayName)
                .claim("video", videoGrant)
                .build()
        return ParticipantToken(jwt = sign(claims, apiSecret), expiresAt = expiresAt)
    }

    /**
     * Mints a server-internal admin token for exactly one [LiveKitAdminClient] Twirp call. Grants
     * `roomCreate`/`roomAdmin`/`roomList` together (this wave's five Twirp methods span all three,
     * and a 60-second TTL makes over-granting a single call harmless) but NEVER `roomJoin`.
     *
     * @param room REQUIRED for any call permission-checked against `roomAdmin`
     *   (`ListParticipants`/`RemoveParticipant`) -- see class KDoc "Empirically verified". Optional
     *   (and ignored by the LiveKit server) for `CreateRoom`/`DeleteRoom`/`ListRooms`, but
     *   [LiveKitAdminClient] passes it whenever a target room is known regardless, so this function
     *   never has to guess which call site needs it.
     */
    fun mintAdminToken(
        apiKey: String,
        apiSecret: String,
        room: String? = null,
        ttlSeconds: Long = ADMIN_TOKEN_TTL_SECONDS,
        now: Instant = Clock.System.now(),
    ): String {
        val expiresAt = now + ttlSeconds.seconds
        val videoGrant =
            linkedMapOf<String, Any>(
                "roomCreate" to true,
                "roomAdmin" to true,
                "roomList" to true,
            )
        if (room != null) videoGrant["room"] = room
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(apiKey)
                .notBeforeTime(toJavaDate(now))
                .expirationTime(toJavaDate(expiresAt))
                .jwtID(Uuid.random().toString())
                .claim("video", videoGrant)
                .build()
        return sign(claims, apiSecret)
    }

    /**
     * The single choke point every mint in this file signs through -- [MACSigner]'s own
     * constructor is what throws `com.nimbusds.jose.KeyLengthException` for a sub-256-bit secret
     * (see [network.lapis.cloud.server.conference.ConferenceConfig]'s KDoc for why that case is
     * meant to be caught earlier, at config-load time, with a clearer message than this raw
     * exception would give).
     */
    private fun sign(
        claims: JWTClaimsSet,
        apiSecret: String,
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.HS256).build()
        val signedJwt = SignedJWT(header, claims)
        signedJwt.sign(MACSigner(apiSecret.toByteArray(Charsets.UTF_8)))
        return signedJwt.serialize()
    }

    private fun toJavaDate(instant: Instant): Date = Date.from(instant.toJavaInstant())
}
