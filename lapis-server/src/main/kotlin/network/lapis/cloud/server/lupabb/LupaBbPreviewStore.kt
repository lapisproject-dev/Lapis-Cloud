package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDateTime
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.226 -- the in-memory store between "load preview" and "apply" (nothing is persisted; a restart drops every preview and the ADMIN loads it
 * again). The token is 32 random bytes (base64url); only its SHA-256 is kept and compared in constant time. An entry belongs to ONE admin, lives
 * [TTL] on the monotonic clock, and the whole store holds at most [MAX_ENTRIES] entries (a new preview of the same admin replaces the old one).
 * [take] is single use and removes the entry whatever the outcome (a foreign admin's guess burns the token as well); [peek] does not remove it
 * (paging through the rows, linking a member) but applies the same owner and time rules.
 */
internal class LupaBbPreviewStore(
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttl: Duration = TTL,
) {
    class Entry(
        val adminId: Uuid,
        val plan: LupaBbPlan,
        val startedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
        internal val createdNanos: Long,
        internal val tokenHash: ByteArray,
    ) {
        /** Rows are replaced by [LupaBbPreviewStore.replaceRow] under the store lock only. */
        internal var rows: List<PlanRow> = plan.rows
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val random = SecureRandom()

    fun put(
        adminId: Uuid,
        plan: LupaBbPlan,
        startedAt: LocalDateTime,
        expiresAt: LocalDateTime,
    ): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        val entry =
            Entry(
                adminId = adminId,
                plan = plan,
                startedAt = startedAt,
                expiresAt = expiresAt,
                createdNanos = nanoTime(),
                tokenHash = hash(token),
            )
        synchronized(lock) {
            purgeExpired()
            entries.removeAll { it.adminId == adminId }
            entries += entry
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        return token
    }

    /** Single use; `null` for an unknown, expired, reused or foreign token. */
    fun take(
        token: String,
        adminId: Uuid,
    ): Entry? {
        if (token.length > MAX_TOKEN_LENGTH) return null
        val wanted = hash(token)
        synchronized(lock) {
            purgeExpired()
            val found = entries.firstOrNull { MessageDigest.isEqual(it.tokenHash, wanted) } ?: return null
            entries.remove(found)
            return found.takeIf { it.adminId == adminId }
        }
    }

    /** Like [take] but the entry stays; `null` for an unknown, expired or foreign token (a foreign guess does NOT burn it). */
    fun <T> peek(
        token: String,
        adminId: Uuid,
        block: (Entry) -> T,
    ): T? {
        if (token.length > MAX_TOKEN_LENGTH) return null
        val wanted = hash(token)
        synchronized(lock) {
            purgeExpired()
            val found = entries.firstOrNull { MessageDigest.isEqual(it.tokenHash, wanted) && it.adminId == adminId } ?: return null
            return block(found)
        }
    }

    /** Replaces the row of [personNumber] in the entry behind [token]; `false` when the token is not valid for [adminId]. */
    fun replaceRow(
        token: String,
        adminId: Uuid,
        personNumber: Long,
        row: PlanRow,
    ): Boolean =
        peek(token = token, adminId = adminId) { entry ->
            entry.rows = entry.rows.map { if (it.personNumber == personNumber) row else it }
            true
        } ?: false

    fun size(): Int = synchronized(lock) { entries.size }

    private fun purgeExpired() {
        val now = nanoTime()
        entries.removeAll { now - it.createdNanos >= ttl.inWholeNanoseconds }
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    companion object {
        const val MAX_ENTRIES = 2
        const val MAX_TOKEN_LENGTH = 64
        val TTL: Duration = 15.minutes
    }
}
