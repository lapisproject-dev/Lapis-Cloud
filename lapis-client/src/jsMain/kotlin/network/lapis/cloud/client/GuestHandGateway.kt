package network.lapis.cloud.client

import network.lapis.cloud.client.livekit.conferenceHandsPacket
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto

/**
 * V1.9.281 -- the hand gateway of a person WITHOUT an account: only the two calls that concern the OWN hand exist (raise, lower), both through the
 * guest-action token. Reading the queue and every moderation call are not possible for a guest (`null`), so the controller never asks for them.
 *
 * A 200 answer carries the exact bytes of the broadcast, so it passes the very same strict validation ([conferenceHandsPacket]) -- a forged or
 * malformed answer yields `null`. A "lowered" (204) answer has no queue to read: [lower] yields `null` for it, but [lowerOwn] reports it as [OwnLowerResult.Lowered] so the controller knows the hand is down.
 */
internal class GuestHandGateway(
    private val api: EventGuestApi,
    private val slug: String,
    private val handToken: String,
) : ConferenceHandGateway {
    override suspend fun raise(): ConferenceHandQueueDto? = call(raise = true)

    override suspend fun lower(): ConferenceHandQueueDto? = call(raise = false)

    override suspend fun lowerOwn(): OwnLowerResult =
        when (val result = api.hand(slug = slug, handToken = handToken, raise = false)) {
            is EventGuestHandHttpResult.Queue -> {
                val dto = conferenceHandsPacket(fromParticipant = false, payload = result.bytes)
                if (dto == null) OwnLowerResult.Failed else OwnLowerResult.Queue(dto)
            }
            EventGuestHandHttpResult.Lowered -> OwnLowerResult.Lowered
            EventGuestHandHttpResult.Failed -> OwnLowerResult.Failed
        }

    override suspend fun queue(): ConferenceHandQueueDto? = null

    override suspend fun lowerOf(identity: String): ConferenceHandQueueDto? = null

    override suspend fun lowerAll(): ConferenceHandQueueDto? = null

    private suspend fun call(raise: Boolean): ConferenceHandQueueDto? =
        when (val result = api.hand(slug = slug, handToken = handToken, raise = raise)) {
            is EventGuestHandHttpResult.Queue -> conferenceHandsPacket(fromParticipant = false, payload = result.bytes)
            EventGuestHandHttpResult.Lowered, EventGuestHandHttpResult.Failed -> null
        }
}
