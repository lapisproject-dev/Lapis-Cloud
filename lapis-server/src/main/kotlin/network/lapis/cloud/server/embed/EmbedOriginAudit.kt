package network.lapis.cloud.server.embed

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * V1.9.261 -- guards for the partner origin recorded on embed registrations (`event_registration.embed_origin`, copied to
 * `payment_checkout_session.embed_origin`). The value is an audit trail only: nothing reads it to build a redirect or a
 * `frame-ancestors` header. Never log the value itself, only its length or the outcome.
 */
internal object EmbedOriginAudit {
    /** Column width of `event_registration.embed_origin` and `payment_checkout_session.embed_origin`. */
    const val MAX_STORED_LENGTH = 255

    /**
     * Write side: [canonicalOrigin] MUST already be an `EmbedCorsResult.Allowed.canonicalOrigin`. Returns null if it is null or
     * longer than the column (an operator-entered allowlist entry may be up to [EmbedOriginAllowlist.MAX_ORIGIN_LENGTH] long).
     */
    fun forStorage(canonicalOrigin: String?): String? {
        if (canonicalOrigin == null) return null
        if (canonicalOrigin.length > MAX_STORED_LENGTH) {
            logger.warn {
                "EmbedOriginAudit: dropping an embed origin of length ${canonicalOrigin.length} (column holds $MAX_STORED_LENGTH)"
            }
            return null
        }
        return canonicalOrigin
    }

    /**
     * Use side: re-validates a STORED value against the CURRENT config. Returns [stored] only if the embed feature is enabled,
     * the value fits the column and `config.allowlist.canonicalize(stored) == stored` (exact equality with a current canonical
     * entry). A removed partner, a disabled embed, a non-canonical spelling or any hostile string yields null.
     */
    fun revalidateForUse(
        stored: String?,
        config: EmbedConfig,
    ): String? {
        if (stored == null || !config.enabled) return null
        if (stored.length > MAX_STORED_LENGTH) return null
        return if (config.allowlist.canonicalize(stored) == stored) stored else null
    }
}
