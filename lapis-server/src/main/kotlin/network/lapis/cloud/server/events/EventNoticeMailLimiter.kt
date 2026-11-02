package network.lapis.cloud.server.events

import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.sha256Hex
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.259 -- bounds the two notice mails the public GUEST form can trigger (member notice, duplicate notice). Without it the form is a
 * mail amplifier: anybody can type anybody's address.
 *
 * - **Per key**: one mail per (event, address) per 24 hours. The key is `sha256("$eventId|$normalizedEmail")` -- the plain address is never
 *   held in memory by the limiter.
 * - **Global**: at most 100 notice mails per hour across the whole instance, checked only after the per-key check passed (so a repeated
 *   address does not eat the global budget).
 *
 * Both limits are the shared mail budget of the two notice kinds. The state lives in this process: a restart resets it, and a flood of
 * distinct keys evicts the oldest ones first ([FederationInboxRateLimiter] KDoc) -- the global cap bounds the damage in both cases. The
 * limiter only ever decides whether a MAIL is sent; the response to the visitor never depends on it.
 */
internal class EventNoticeMailLimiter(
    private val perKey: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = 1, window = 24.hours, maxTrackedKeys = 50_000),
    private val global: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = 100, window = 60.minutes, maxTrackedKeys = 1),
) {
    /** `true` = send the mail. */
    fun tryAcquire(
        eventId: Uuid,
        normalizedEmail: String,
    ): Boolean {
        val key = sha256Hex("$eventId|$normalizedEmail".toByteArray(Charsets.UTF_8))
        if (!perKey.checkAndRecord(key)) return false
        return global.checkAndRecord(GLOBAL_KEY)
    }

    private companion object {
        const val GLOBAL_KEY = "global"
    }
}
