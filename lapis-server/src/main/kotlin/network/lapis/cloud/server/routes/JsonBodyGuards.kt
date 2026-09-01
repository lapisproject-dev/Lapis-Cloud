package network.lapis.cloud.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import java.security.MessageDigest

// Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- extracted from `FederationRoutes.kt`'s
// own `readCappedInboxBody`/`exceedsMaxJsonNestingDepth`/`sha256Hex` (a pure move, no behavior
// change) so `PspWebhookRoutes` can reuse the identical DoS guards rather than duplicating them.
// `FederationRoutes.readCappedInboxBody` now delegates to [readCappedBody] unchanged --
// `FederationRoutesTest` stays green.

/**
 * Bounded streaming read, mirrors `network.lapis.cloud.server.routes.registerDocumentRoutes`'/
 * `registerBackupRoutes`'s own `MAX_UPLOAD_BYTES`/`MAX_RESTORE_BUNDLE_BYTES` byte-counting-loop
 * idiom -- returns `null` if [maxBytes] is exceeded, the body discarded rather than partially
 * processed.
 */
internal suspend fun readCappedBody(
    call: ApplicationCall,
    maxBytes: Int,
): ByteArray? {
    val channel = call.receiveChannel()
    val buffer = ByteArray(maxBytes + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > maxBytes) null else buffer.copyOf(total)
}

/**
 * `true` iff [text]'s `{`/`[`/`}`/`]` nesting ever exceeds [maxDepth] -- a single linear pass over
 * the raw characters (bracket counting with minimal string-literal awareness so a bracket inside a
 * quoted JSON string value is never miscounted), deliberately NOT using any JSON parser: even
 * building a `kotlinx.serialization.json.JsonElement` tree is itself a RECURSIVE-descent operation
 * that could overflow the stack on sufficiently deep (but small-in-bytes) attacker-crafted input
 * before this function would ever get a chance to reject it.
 */
internal fun exceedsMaxJsonNestingDepth(
    text: String,
    maxDepth: Int,
): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> {
                depth++
                if (depth > maxDepth) return true
            }
            '}', ']' -> depth--
        }
    }
    return false
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte -> "%02x".format(byte) }
