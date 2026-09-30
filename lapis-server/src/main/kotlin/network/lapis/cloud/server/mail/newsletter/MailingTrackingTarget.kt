package network.lapis.cloud.server.mail.newsletter

import java.net.URI

/**
 * Welle V1.9.15 -- the single definition of "a link that may be click-tracked". Used both when the
 * links are captured (so an unredirectable link is never rewritten into a dead tracking URL) and at
 * redirect time (defence in depth against a tampered row).
 */
internal object MailingTrackingTarget {
    const val MAX_TARGET_LENGTH = 2048

    /** Only http/https with a host, parseable, bounded, pure ASCII after encoding; else `null`. */
    fun safeRedirectTarget(raw: String): String? {
        if (raw.length > MAX_TARGET_LENGTH || raw.any { it.isISOControl() || it.isWhitespace() }) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        val ascii = uri.toASCIIString()
        return ascii.takeIf { it.length <= MAX_TARGET_LENGTH && ascii.none { c -> c.isISOControl() } }
    }
}
