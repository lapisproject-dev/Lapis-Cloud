package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.LupaBbSyncSuppressionTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * V1.9.226 -- the erasure suppression list. A member erased under Art. 17 GDPR loses the person number (the member row is anonymised), so the next
 * sync would find the LupaBB record without a counterpart and CREATE the person again. [add] therefore keeps the HMAC-SHA256 of the person number
 * (key derived from `LAPIS_SECRET_ENCRYPTION_KEY`, see [SecretBox.hmacSha256Hex]); the sync skips every record whose HMAC is on the list. The
 * number itself is not kept, and without the key the list cannot be matched.
 *
 * The box is set once at start-up by `Application.kt` ([configure]); without a key no credentials can be stored and no sync can run, so there is
 * nothing to suppress either.
 */
internal object LupaBbSuppression {
    private const val PURPOSE = "lupabb-suppression"

    @Volatile
    private var box: SecretBox? = null

    fun configure(secretBox: SecretBox?) {
        box = secretBox
    }

    fun hmac(
        secretBox: SecretBox,
        personNumber: Long,
    ): String = secretBox.hmacSha256Hex(purpose = PURPOSE, data = personNumber.toString())

    /** The person number a CSV-import reference stands for: a plain positive number, else `null`. */
    fun referenceNumber(reference: String?): Long? =
        reference
            ?.trim()
            ?.takeIf { it.length in 1..MAX_REFERENCE_DIGITS && it.all { c -> c in '0'..'9' } }
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

    private const val MAX_REFERENCE_DIGITS = 18

    /** All HMACs on the list. Inside a transaction. */
    fun allHmacs(): Set<String> = LupaBbSyncSuppressionTable.selectAll().map { it[LupaBbSyncSuppressionTable.personNumberHmac] }.toSet()

    /** Adds [personNumber] (idempotent). Called by the erasure of a member; a no-op when the server has no key. Inside a transaction. */
    fun add(
        personNumber: Long,
        now: LocalDateTime,
    ) {
        val secretBox = box ?: return
        val value = hmac(secretBox = secretBox, personNumber = personNumber)
        val present = LupaBbSyncSuppressionTable.selectAll().where { LupaBbSyncSuppressionTable.personNumberHmac eq value }.any()
        if (!present) {
            LupaBbSyncSuppressionTable.insert {
                it[personNumberHmac] = value
                it[createdAt] = now
            }
        }
    }
}
