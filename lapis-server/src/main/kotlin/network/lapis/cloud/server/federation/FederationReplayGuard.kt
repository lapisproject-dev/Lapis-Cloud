package network.lapis.cloud.server.federation

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory replay guard for `POST /federation/inbox` (V0.8.1 Federation-Grundgerüst) -- a
 * signature freshness check ([HttpSignatures.FRESHNESS_WINDOW] on the `date` header) alone does
 * NOT stop "resend the exact same valid, still-fresh signed request" -- an attacker (or a buggy
 * remote server) replaying a captured request within the freshness window would otherwise be
 * re-accepted every time. This tracks `SHA-256(signatureHeaderValue)` -> first-seen time in a
 * bounded [ConcurrentHashMap] with opportunistic eviction, same idiom as
 * [network.lapis.cloud.server.security.LoginRateLimiter]/[FederationInboxRateLimiter] -- a
 * signature already seen within [HttpSignatures.FRESHNESS_WINDOW] is rejected as a replay.
 *
 * TTL equals [HttpSignatures.FRESHNESS_WINDOW] itself: a signature whose `date` header has aged
 * out of the freshness window is already rejected by [HttpSignatures.verify] on every subsequent
 * attempt regardless of this guard, so there is no need to remember it any longer than that.
 *
 * **Known scope-cut**: per-JVM-instance state, same documented limitation as every other
 * in-memory guard in this codebase (no shared store exists yet).
 */
class FederationReplayGuard(
    private val maxTrackedKeys: Int = 10_000,
) {
    private val seenAt = ConcurrentHashMap<String, Instant>()

    /** `true` iff [signatureHeader] has NOT been seen within [HttpSignatures.FRESHNESS_WINDOW] -- records it as seen either way (a repeat call with the same header, even if it was itself just rejected as a replay, must not "extend" the window). */
    fun checkAndRecord(signatureHeader: String): Boolean {
        val now = Clock.System.now()
        val key = fingerprint(signatureHeader)
        var isNew = false
        seenAt.compute(key) { _, existing ->
            val expired = existing == null || now - existing >= HttpSignatures.FRESHNESS_WINDOW
            isNew = expired
            if (expired) now else existing
        }
        evictExpiredIfOverCapacity(now)
        return isNew
    }

    private fun evictExpiredIfOverCapacity(now: Instant) {
        if (seenAt.size <= maxTrackedKeys) return
        seenAt.entries.removeIf { (_, seen) -> now - seen >= HttpSignatures.FRESHNESS_WINDOW }
    }

    private fun fingerprint(signatureHeader: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signatureHeader.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}
