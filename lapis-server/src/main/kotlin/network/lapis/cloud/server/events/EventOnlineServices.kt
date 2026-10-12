package network.lapis.cloud.server.events

import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import kotlin.time.Duration.Companion.minutes

/**
 * Welle V1.9.113 -- the module-scoped bundle behind the online participation in events: the provisioner, the join coordinator and the
 * rate limiters. `Application.module` builds ONE instance ([create]) and threads it into `EventService` and the guest routes -- the
 * limiters must be module-scoped singletons because the RPC factory builds a fresh `EventService` per call (a per-construction limiter
 * would be empty on every request). [DISABLED] (conferencing not configured) keeps every pre-V1.9.113 construction of `EventService`
 * working: every online action then answers `UNAVAILABLE`, and the lifecycle hooks that only touch the database still run.
 */
class EventOnlineServices internal constructor(
    internal val config: ConferenceConfig?,
    internal val join: EventOnlineJoin?,
    internal val provisioner: EventConferenceProvisioner?,
    internal val memberJoinRateLimiter: FederationInboxRateLimiter,
    internal val adminRateLimiter: FederationInboxRateLimiter,
) {
    /** `true` iff Lapis video conferencing is configured on this server (a precondition for the two Lapis online kinds). */
    val conferencesEnabled: Boolean get() = join != null

    companion object {
        private const val MEMBER_JOIN_MAX = 30
        private const val ADMIN_MAX = 60

        val DISABLED: EventOnlineServices =
            EventOnlineServices(
                config = null,
                join = null,
                provisioner = null,
                memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = MEMBER_JOIN_MAX, window = 1.minutes),
                adminRateLimiter = FederationInboxRateLimiter(maxRequests = ADMIN_MAX, window = 1.minutes),
            )

        fun create(
            config: ConferenceConfig,
            liveKitAdminClient: LiveKitAdminClient,
            whiteboardState: ConferenceWhiteboardState,
            notesState: ConferenceNotesState,
            audioGrantState: ConferenceAudioGrantState,
            memberJoinRateLimiter: FederationInboxRateLimiter,
            adminRateLimiter: FederationInboxRateLimiter,
        ): EventOnlineServices {
            if (!config.enabled) return DISABLED
            val provisioner =
                EventConferenceProvisioner(
                    config = config,
                    liveKit = liveKitAdminClient,
                    closer =
                        ConferenceRoomCloser(
                            liveKitAdminClient = liveKitAdminClient,
                            whiteboardState = whiteboardState,
                            notesState = notesState,
                            audioGrantState = audioGrantState,
                        ),
                )
            return EventOnlineServices(
                config = config,
                join =
                    EventOnlineJoin(
                        config = config,
                        liveKit = liveKitAdminClient,
                        provisioner = provisioner,
                        audioGrantState = audioGrantState,
                    ),
                provisioner = provisioner,
                memberJoinRateLimiter = memberJoinRateLimiter,
                adminRateLimiter = adminRateLimiter,
            )
        }
    }
}
