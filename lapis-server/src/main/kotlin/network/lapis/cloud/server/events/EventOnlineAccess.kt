package network.lapis.cloud.server.events

import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.rpc.requireConferenceEligibleMembership
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlinePhase
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.security.MessageDigest
import kotlin.time.Instant

/** Who asks to take part online: a signed-in person, or somebody without an account (optionally with the hash of a ticket code). */
internal sealed interface EventOnlineCaller {
    data class Session(
        val current: CurrentMember,
    ) : EventOnlineCaller

    /** [ticketCodeSha256] is the SHA-256 of the canonical ticket code, never the code itself. */
    data class Accountless(
        val ticketCodeSha256: String?,
    ) : EventOnlineCaller
}

/** [role] and [isGuest] are only meaningful for [EventOnlineOutcome.JOINED]. [isGuest]: no member (account-less, or a non-member account). */
internal data class EventOnlineDecision(
    val outcome: EventOnlineOutcome,
    val role: ConferenceRole? = null,
    val isGuest: Boolean = false,
    val window: EventOnlineWindow.Window? = null,
)

/**
 * Welle V1.9.113 -- the SINGLE answer to "may this caller take part online in this event right now". Pure decision, no side effects, must
 * run inside an open `transaction {}` (it reads registrations and the member status). Every refusal for an unknown / unpublished /
 * cancelled / not-online / unauthorized event is the SAME [EventOnlineOutcome.FORBIDDEN] -- there is no oracle that tells those apart.
 * Re-run inside the token-issuing transaction as the TOCTOU re-check (a registration can be cancelled in between).
 *
 * Order: (1) the event must be a published Lapis online event, (2) who is allowed (organizer / BOARD / ADMIN always as moderator; with a
 * registration deadline only CONFIRMED registrations; without a deadline everybody for a PUBLIC event, only members for MEMBERS_ONLY),
 * (3) only then the time window -- so an unauthorized caller never learns when the event opens.
 */
internal fun decideEventOnlineAccess(
    eventRow: ResultRow?,
    caller: EventOnlineCaller,
    now: Instant,
    orgZone: TimeZone,
): EventOnlineDecision {
    val forbidden = EventOnlineDecision(outcome = EventOnlineOutcome.FORBIDDEN)
    if (eventRow == null) return forbidden
    if (eventRow[EventTable.status] != EventStatus.PUBLISHED) return forbidden
    val kind = eventRow[EventTable.onlineKind]
    if (kind != EventOnlineKind.LAPIS_CONFERENCE && kind != EventOnlineKind.LAPIS_ENCOUNTER) return forbidden
    val eventId = eventRow[EventTable.id]
    val publicEvent = eventRow[EventTable.visibility] == EventVisibility.PUBLIC
    val hasDeadline = eventRow[EventTable.registrationClosesAt] != null

    var role: ConferenceRole = ConferenceRole.PARTICIPANT
    var isGuest = false
    when (caller) {
        is EventOnlineCaller.Accountless -> {
            // No account, no encounter space (the entry there needs an account), and never for a members-only event.
            if (!publicEvent || kind == EventOnlineKind.LAPIS_ENCOUNTER) return forbidden
            isGuest = true
            if (hasDeadline && !hasConfirmedTicket(eventId = eventId, ticketCodeSha256 = caller.ticketCodeSha256)) return forbidden
        }
        is EventOnlineCaller.Session -> {
            val current = caller.current
            val status: MemberStatus =
                try {
                    requireConferenceEligibleMembership(memberId = current.memberId)
                } catch (_: ForbiddenException) {
                    return forbidden
                }
            val nonMember = status != MemberStatus.ACTIVE
            val moderator = eventRow[EventTable.createdBy] == current.memberId || current.isPrivileged
            if (moderator && !nonMember) {
                role = ConferenceRole.MODERATOR
            } else if (hasDeadline) {
                if (!hasConfirmedMemberRegistration(eventId = eventId, memberId = current.memberId)) return forbidden
            } else if (!publicEvent && nonMember) {
                return forbidden
            }
            // A non-member with a session (GUEST/FRIEND) is a guest of the room and may only ever take part in a PUBLIC event.
            if (nonMember) {
                if (!publicEvent) return forbidden
                isGuest = true
            }
        }
    }
    val window = EventOnlineWindow.of(startsAt = eventRow[EventTable.startsAt], endsAt = eventRow[EventTable.endsAt], orgZone = orgZone)
    return when (EventOnlineWindow.phase(window = window, now = now)) {
        EventOnlinePhase.NOT_YET -> EventOnlineDecision(outcome = EventOnlineOutcome.NOT_YET, window = window)
        EventOnlinePhase.ENDED -> EventOnlineDecision(outcome = EventOnlineOutcome.ENDED, window = window)
        EventOnlinePhase.OPEN ->
            EventOnlineDecision(outcome = EventOnlineOutcome.JOINED, role = role, isGuest = isGuest, window = window)
    }
}

private fun hasConfirmedMemberRegistration(
    eventId: kotlin.uuid.Uuid,
    memberId: kotlin.uuid.Uuid,
): Boolean =
    EventRegistrationTable
        .selectAll()
        .where {
            (EventRegistrationTable.eventId eq eventId) and
                (EventRegistrationTable.memberId eq memberId) and
                (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED)
        }.limit(1)
        .any()

/** `true` iff [ticketCodeSha256] belongs to a CONFIRMED registration of exactly [eventId]. Constant-time compare on top of the hash lookup. */
private fun hasConfirmedTicket(
    eventId: kotlin.uuid.Uuid,
    ticketCodeSha256: String?,
): Boolean {
    if (ticketCodeSha256 == null) return false
    val row = EventStore.findByTicketCodeHash(ticketCodeSha256) ?: return false
    val stored = row[EventRegistrationTable.ticketCodeSha256] ?: return false
    if (!MessageDigest.isEqual(stored.toByteArray(Charsets.US_ASCII), ticketCodeSha256.toByteArray(Charsets.US_ASCII))) return false
    return row[EventRegistrationTable.eventId] == eventId && row[EventRegistrationTable.status] == EventRegistrationStatus.CONFIRMED
}
