package network.lapis.cloud.server.routes

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventRegistrationView
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.259 -- the signed-in visitor of the public event page, as far as the page needs to know. */
internal data class EventPublicViewer(
    val memberId: Uuid,
    val displayName: String,
    /** The visitor's own CURRENT registration for this event (`CONFIRMED`, `WAITLISTED` or a still-held `PENDING_PAYMENT`), or `null`. */
    val own: OwnRegistration?,
)

internal data class OwnRegistration(
    val status: EventRegistrationStatus,
)

/**
 * V1.9.259 -- who is looking at the public event page, from the SESSION COOKIE ONLY. Never the `Authorization: Bearer` header (an API
 * client is not a browser visitor) and never the test-only `X-Member-Id` header: this surface must behave in a test exactly as it does in
 * production, where neither exists. Read-only: it never sets a cookie.
 *
 * `null` -- indistinguishably, so nothing about the session leaks -- when
 * - [isEmbed]: the embed variant is framed on a foreign site and never looks at the cookie (the cookie is `SameSite=Strict` and would not
 *   arrive in a cross-site frame anyway; the check here makes that independent of the browser). No cookie access at all in that case.
 * - there is no cookie, or the session is unknown, revoked or expired,
 * - the account's status is outside [MemberStatusSets.EVENT_SELF_REGISTRANT] (applicants, withdrawn, ...), or the row is anonymised.
 *
 * A `PENDING_PAYMENT` registration whose hold has lapsed is NOT a registration (see [EventRegistrationView.effectiveStatus]); the visitor
 * can register again.
 */
internal fun ApplicationCall.resolveEventPublicViewer(
    isEmbed: Boolean,
    eventId: Uuid,
    now: LocalDateTime,
): EventPublicViewer? {
    if (isEmbed) return null
    val rawToken = request.cookies[SESSION_COOKIE_NAME]
    if (rawToken.isNullOrBlank()) return null
    val current = SessionStore.resolve(rawToken) ?: return null
    if (current.status !in MemberStatusSets.EVENT_SELF_REGISTRANT) return null
    return transaction {
        val member =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq current.memberId }
                .singleOrNull() ?: return@transaction null
        if (member[MemberTable.anonymizedAt] != null) return@transaction null
        val own =
            EventStore
                .findOwnActiveRegistration(eventId = eventId, memberId = current.memberId)
                ?.let { row -> EventRegistrationView.effectiveStatus(row = row, now = now) }
                ?.takeIf {
                    it == EventRegistrationStatus.CONFIRMED ||
                        it == EventRegistrationStatus.WAITLISTED ||
                        it == EventRegistrationStatus.PENDING_PAYMENT
                }?.let { OwnRegistration(status = it) }
        EventPublicViewer(memberId = current.memberId, displayName = member[MemberTable.displayName], own = own)
    }
}
