package network.lapis.cloud.server.events

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.259 -- the body of the former `EventService.registerSelfInternal` AFTER identity, rate limit and message check: reads the member's
 * address and display name in ONE transaction and hands over to [EventRegistrationSubmission.submit]. One implementation for the member RPC
 * (`registerSelf`) and the button on the public event page (`POST /veranstaltung/{slug}/anmeldung/konto`), so neither can drift.
 *
 * Identity, rate limiting and message validation are the CALLER's job (the RPC resolves the session through `resolveCurrentMember`, the
 * public route through its cookie-only viewer lookup).
 */
internal class MemberEventSelfRegistration(
    private val submission: EventRegistrationSubmission,
) {
    suspend fun register(
        memberId: Uuid,
        eventId: Uuid,
        message: String?,
    ): EventRegistrationResult {
        val (email, displayName) =
            transaction {
                (EventStore.memberEmailOrNull(memberId) ?: "") to (EventStore.memberDisplayNameOrNull(memberId) ?: "")
            }
        return submission.submit(
            eventId = eventId,
            participant = EventParticipant.Member(memberId = memberId, displayName = displayName, email = email),
            message = message,
        )
    }
}
