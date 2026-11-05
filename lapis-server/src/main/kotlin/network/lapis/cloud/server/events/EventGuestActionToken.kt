package network.lapis.cloud.server.events

import network.lapis.cloud.server.conference.HAND_IDENTITY_REGEX
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.281 -- the stateless "guest action" token of a person WITHOUT an account in the online conference of an event. It lets exactly one thing happen: the
 * holder raises or lowers THEIR OWN hand (`POST /api/events/{slug}/online-guest/hand`). It is not the LiveKit join JWT and grants nothing in LiveKit.
 *
 * Format: `v1.<identity>.<expEpochSec>.<macBase64Url>`. The MAC (HMAC-SHA256 under a domain-separated sub-key of the LiveKit API secret) covers
 * `hand|<eventId>|<roomId>|<identity>|<exp>`. **Neither the event id nor the room id is part of the token**: the server puts them in itself when it
 * verifies (event from the slug, room from the event), so the binding is complete and the room id (which is never part of an event DTO, see
 * [EventConferenceGuard]) is not handed to the guest. A token of another event, another room, another identity, another purpose or a later expiry
 * fails the MAC. Nothing is stored; a restart does not invalidate tokens (same secret).
 *
 * [verify] never throws and answers `null` for every defect. This file logs nothing.
 */
internal object EventGuestActionToken {
    const val VERSION = "v1"
    const val PURPOSE_HAND = "hand"
    private const val DOMAIN = "lapis-event-guest-action-v1"
    const val MAX_TOKEN_LENGTH = 256
    private const val MAC_BYTES = 32
    private const val PARTS = 4
    private const val MAX_IDENTITY_LENGTH = 100

    /** How long past the close of the online window a token stays good (a guest may still lower their hand in the trail). */
    val SLACK: Duration = 15.minutes
    val MAX_LIFETIME: Duration = 24.hours
    val MIN_LIFETIME: Duration = 1.minutes

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** `windowClosesAt + SLACK`, but at most [MAX_LIFETIME] and at least [MIN_LIFETIME] from [now]. */
    fun expiryFor(
        windowClosesAt: Instant,
        now: Instant,
    ): Instant {
        val wanted = windowClosesAt + SLACK
        val upper = now + MAX_LIFETIME
        val lower = now + MIN_LIFETIME
        return maxOf(lower, minOf(wanted, upper))
    }

    fun issue(
        serverSecret: String,
        eventId: Uuid,
        roomId: Uuid,
        identity: String,
        expiresAt: Instant,
    ): String {
        val exp = expiresAt.epochSeconds
        val mac = macOf(serverSecret = serverSecret, eventId = eventId, roomId = roomId, identity = identity, exp = exp)
        return "$VERSION.$identity.$exp.${encoder.encodeToString(mac)}"
    }

    /** The identity iff structure, version, `evg-` identity, expiry (exclusive) and MAC (bound to [eventId] and [roomId]) are all right; else `null`. */
    fun verify(
        serverSecret: String,
        token: String,
        eventId: Uuid,
        roomId: Uuid,
        now: Instant,
    ): String? =
        try {
            verifyOrThrow(serverSecret = serverSecret, token = token, eventId = eventId, roomId = roomId, now = now)
        } catch (e: Exception) {
            null
        }

    private fun verifyOrThrow(
        serverSecret: String,
        token: String,
        eventId: Uuid,
        roomId: Uuid,
        now: Instant,
    ): String? {
        if (token.length > MAX_TOKEN_LENGTH) return null
        val parts = token.split('.')
        if (parts.size != PARTS || parts[0] != VERSION) return null
        val identity = parts[1]
        if (identity.length > MAX_IDENTITY_LENGTH || !identity.startsWith(EVENT_GUEST_IDENTITY_PREFIX)) return null
        if (!HAND_IDENTITY_REGEX.matches(identity)) return null
        val exp = parts[2].toLongOrNull() ?: return null
        if (exp <= now.epochSeconds) return null
        val given = decoder.decode(parts[3])
        if (given.size != MAC_BYTES) return null
        val expected = macOf(serverSecret = serverSecret, eventId = eventId, roomId = roomId, identity = identity, exp = exp)
        return if (MessageDigest.isEqual(expected, given)) identity else null
    }

    private fun macOf(
        serverSecret: String,
        eventId: Uuid,
        roomId: Uuid,
        identity: String,
        exp: Long,
    ): ByteArray {
        val subKey =
            EventCaptureAcknowledgments.hmac(
                key = serverSecret.toByteArray(Charsets.UTF_8),
                data = DOMAIN.toByteArray(Charsets.UTF_8),
            )
        val input = "$PURPOSE_HAND|$eventId|$roomId|$identity|$exp"
        return EventCaptureAcknowledgments.hmac(key = subKey, data = input.toByteArray(Charsets.UTF_8))
    }
}
