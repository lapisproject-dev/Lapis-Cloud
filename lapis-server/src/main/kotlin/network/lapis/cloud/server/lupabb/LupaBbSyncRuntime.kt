package network.lapis.cloud.server.lupabb

import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.RegionalChapterEnforcementConfig
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.226 -- the process wide state of the LupaBB sync, created once in `Application.kt` and handed to every (per request) `LupaBbSyncService`:
 * the key to seal the credentials, the preview store and the rate limiter of "check and save credentials" (so Lapis is no oracle for guessing
 * LupaBB credentials). [endpoint] and [limits] differ from the defaults only in tests.
 */
internal class LupaBbSyncRuntime(
    val secretBox: SecretBox?,
    val endpoint: LupaBbEndpoint = LupaBbEndpoint.PRODUCTION,
    val limits: LupaBbLimits = LupaBbLimits.DEFAULT,
    val previewStore: LupaBbPreviewStore = LupaBbPreviewStore(),
    val credentialRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 5, window = 10.minutes),
    val chapterEnforced: () -> Boolean = { RegionalChapterEnforcementConfig.load().enabled },
)
