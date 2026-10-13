package network.lapis.cloud.server.carpool

import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.125 -- opaque, short-lived tokens that stand in for a member id between `findInvitee` and `inviteParticipant`: the client must never
 * see (or be able to send) a raw member id of a person it merely searched for. 128 random bits, bound to the SEARCHING driver (a token of
 * another driver resolves to nothing), TTL 15 minutes, in memory (a restart drops them -- the driver just searches again; documented limit),
 * at most [MAX_ENTRIES] with opportunistic cleanup.
 */
internal class CarpoolInviteeRefStore(
    private val now: () -> Instant = { Clock.System.now() },
    private val random: SecureRandom = SecureRandom(),
) {
    private class Ref(
        val driverId: Uuid,
        val personId: Uuid,
        val expiresAt: Instant,
    )

    private val lock = Any()
    private val refs = HashMap<String, Ref>()

    fun issue(
        driverId: Uuid,
        personId: Uuid,
    ): String =
        synchronized(lock) {
            val current = now()
            if (refs.size >= MAX_ENTRIES) refs.entries.removeIf { it.value.expiresAt <= current }
            if (refs.size >= MAX_ENTRIES) refs.clear()
            val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            refs[token] = Ref(driverId = driverId, personId = personId, expiresAt = current + TTL)
            token
        }

    /** The person behind [token], or `null` for an unknown, expired or foreign (other driver) token. */
    fun resolve(
        driverId: Uuid,
        token: String,
    ): Uuid? =
        synchronized(lock) {
            val ref = refs[token] ?: return@synchronized null
            if (ref.expiresAt <= now()) {
                refs.remove(token)
                return@synchronized null
            }
            if (ref.driverId != driverId) return@synchronized null
            ref.personId
        }

    companion object {
        val TTL = 15.minutes
        const val MAX_ENTRIES = 10_000
        private const val TOKEN_BYTES = 16
    }
}
