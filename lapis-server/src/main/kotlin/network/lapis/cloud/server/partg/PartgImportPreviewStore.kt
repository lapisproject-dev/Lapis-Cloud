package network.lapis.cloud.server.partg

import network.lapis.cloud.shared.domain.PartgUnit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- the in-memory store between "preview" and "apply" of the Excel import. NOTHING is persisted: a restart of the server drops every
 * pending preview (the person uploads the file again; documented in `docs/architecture/partg-report.adoc`).
 *
 * At most one entry per member, at most [MAX_ENTRIES] in total (the oldest falls out), a time to live of [TTL] on the MONOTONIC clock.
 * The token is 32 random bytes (base64url); only its SHA-256 is kept and compared in constant time. [take] is single use: it removes the
 * entry whatever the outcome (a foreign member's guess burns the token as well).
 */
class PartgImportPreviewStore(
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttl: Duration = TTL,
) {
    /** What the apply step needs; the values are cents per leaf code. */
    class Entry(
        val memberId: Uuid,
        val year: Int,
        val unit: PartgUnit,
        val current: Map<String, Long>,
        val prior: Map<String, Long>,
        val sha256: String,
        val fileName: String,
        val revision: Long,
        val priorRevision: Long,
        internal val createdNanos: Long,
        internal val tokenHash: ByteArray,
    )

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val random = SecureRandom()

    fun put(
        memberId: Uuid,
        year: Int,
        unit: PartgUnit,
        current: Map<String, Long>,
        prior: Map<String, Long>,
        sha256: String,
        fileName: String,
        revision: Long,
        priorRevision: Long,
    ): String {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val entry =
            Entry(
                memberId = memberId,
                year = year,
                unit = unit,
                current = current,
                prior = prior,
                sha256 = sha256,
                fileName = fileName,
                revision = revision,
                priorRevision = priorRevision,
                createdNanos = nanoTime(),
                tokenHash = hash(token),
            )
        synchronized(lock) {
            purgeExpired()
            entries.removeAll { it.memberId == memberId }
            entries += entry
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        return token
    }

    /** Single use; `null` for an unknown, expired, reused or foreign token. */
    fun take(
        token: String,
        memberId: Uuid,
    ): Entry? {
        if (token.length > 64) return null
        val wanted = hash(token)
        synchronized(lock) {
            purgeExpired()
            var found: Entry? = null
            for (entry in entries) {
                // constant time per comparison; no early exit on the first byte
                if (MessageDigest.isEqual(entry.tokenHash, wanted)) found = entry
            }
            if (found != null) entries.remove(found)
            return found?.takeIf { it.memberId == memberId }
        }
    }

    /** Test hook. */
    fun size(): Int = synchronized(lock) { entries.size }

    private fun purgeExpired() {
        val now = nanoTime()
        entries.removeAll { now - it.createdNanos >= ttl.inWholeNanoseconds }
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    companion object {
        const val MAX_ENTRIES = 64
        val TTL: Duration = 15.minutes
    }
}
