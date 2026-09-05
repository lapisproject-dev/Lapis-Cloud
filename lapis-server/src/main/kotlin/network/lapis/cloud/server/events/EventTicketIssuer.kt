package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** ANSI SQL `unique_violation` SQLSTATE -- both PostgreSQL and H2 (this codebase's test dialect) report this for a UNIQUE-index conflict. Same idiom `EventRegistrationSubmission`'s own `submit()` establishes for `uq_event_registration_active_participant`. */
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

/** A freshly-minted ticket -- the raw code exists ONLY here, never in the database (see `39-events.kuml.kts` file header "Why ticketCodeSha256, not ticketCode in the clear"). */
internal data class IssuedTicket(
    val rawCode: String,
    val sha256: String,
)

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- mints/(re)issues ticket codes. Every
 * function here must run INSIDE an already-open `transaction {}` (same discipline `EventStore`
 * itself establishes) -- this object never opens one of its own, so the caller controls exactly
 * when the write commits relative to any mail dispatch (`MailDispatcher.enqueue` must never run
 * inside an open transaction, see `EventCapacityGuard` KDoc).
 */
internal object EventTicketIssuer {
    /** Bounded retry against the vanishingly unlikely `uq_event_registration_ticket_code` collision -- see class KDoc call sites' own retry loop. At 80 bits of entropy this is a structural safety net, not something expected to ever actually retry. */
    const val MAX_MINT_ATTEMPTS: Int = 3

    /** Generates a fresh raw code + its SHA-256 hash. Pure -- does not touch the database itself. */
    fun mint(): IssuedTicket {
        val rawCode = EventTicketPolicy.newRawCode()
        return IssuedTicket(rawCode = rawCode, sha256 = sha256Hex(rawCode.toByteArray(Charsets.US_ASCII)))
    }

    /**
     * Idempotent nachausstellung for one already-CONFIRMED registration: mints and writes a ticket
     * ONLY if the row is still CONFIRMED and does not already have one. Returns the minted
     * [IssuedTicket] on a genuine issuance, or `null` if the row already had a ticket (a no-op --
     * the caller should NOT mail anything in that case) or is not CONFIRMED at all. Retries
     * [MAX_MINT_ATTEMPTS] times on a unique-index collision (astronomically unlikely at 80 bits, but
     * see `EventStore.issueTicketIfMissing` KDoc "why retry rather than propagate").
     */
    fun issueIfMissing(
        registrationId: Uuid,
        now: LocalDateTime,
    ): IssuedTicket? {
        val row = EventStore.getRegistrationOrNull(registrationId) ?: return null
        if (row[EventRegistrationTable.status] != EventRegistrationStatus.CONFIRMED) return null
        if (row[EventRegistrationTable.ticketCodeSha256] != null) return null
        return mintWithRetry { candidate ->
            EventStore.issueTicketIfMissing(id = registrationId, ticketCodeSha256 = candidate.sha256, now = now)
        }
    }

    /**
     * Rotates the ticket code of a CONFIRMED registration -- the previously issued code stops
     * working immediately (its hash no longer matches any row). Returns `null` only if [registrationId]
     * is not CONFIRMED (a caller bug -- `IEventService.reissueTicket`'s RPC layer is expected to have
     * already loaded/validated the row).
     */
    fun reissue(
        registrationId: Uuid,
        now: LocalDateTime,
    ): IssuedTicket? {
        val row = EventStore.getRegistrationOrNull(registrationId) ?: return null
        if (row[EventRegistrationTable.status] != EventRegistrationStatus.CONFIRMED) return null
        return mintWithRetry { candidate -> EventStore.rotateTicket(id = registrationId, ticketCodeSha256 = candidate.sha256, now = now) }
    }

    /**
     * Fix (review MINOR): before this fix, ONLY the `affected == 0` branch existed, and its log line
     * called that "a ticket-code collision" -- but a plain guarded `UPDATE ... WHERE ...` (see
     * `EventStore.issueTicketIfMissing`/`rotateTicket`) never returns `affected == 0` on an actual
     * `uq_event_registration_ticket_code` violation; a real collision throws [ExposedSQLException]
     * straight out of the `UPDATE`, which used to propagate past this function entirely (aborting
     * the whole caller transaction instead of retrying), while `affected == 0` in truth only ever
     * meant the row's OWN guard predicate (`status = CONFIRMED`[, `ticket_code_sha256 IS NULL`]) no
     * longer matched -- a concurrent cancellation/expiry, or (for [issueIfMissing]) a concurrent
     * ticket already issued. Now the two cases are told apart: a genuine unique-index violation is
     * caught here and IS what gets retried (making [MAX_MINT_ATTEMPTS] mean what the class KDoc
     * always claimed it meant); a guard-predicate miss returns `null` immediately, since a fresh
     * random code cannot change that outcome and burning the remaining attempts on a guaranteed
     * repeat only produces a misleading "exhausted attempts, all collided" log for what is actually
     * an ordinary concurrent status change.
     */
    private inline fun mintWithRetry(write: (IssuedTicket) -> Int): IssuedTicket? {
        repeat(MAX_MINT_ATTEMPTS) { attempt ->
            val candidate = mint()
            val affected =
                try {
                    write(candidate)
                } catch (e: ExposedSQLException) {
                    if (e.sqlState != UNIQUE_VIOLATION_SQL_STATE) throw e
                    logger.warn {
                        "EventTicketIssuer: ticket-code collision on mint attempt ${attempt + 1}/$MAX_MINT_ATTEMPTS -- retrying."
                    }
                    return@repeat
                }
            if (affected > 0) return candidate
            logger.info {
                "EventTicketIssuer: mint attempt ${attempt + 1} affected 0 rows for a reason other than a " +
                    "ticket-code collision (the row's own guard condition no longer matched -- a concurrent " +
                    "status change or an already-issued ticket) -- not retrying."
            }
            return null
        }
        logger.error {
            "EventTicketIssuer: exhausted $MAX_MINT_ATTEMPTS mint attempts -- all were genuine ticket-code collisions."
        }
        return null
    }

    /** Every CONFIRMED row on [eventId] without a ticket yet -- the input set for `IEventService.openCheckIn`'s nachausstellung sweep. */
    fun listConfirmedWithoutTicket(eventId: Uuid): List<ResultRow> = EventStore.listConfirmedWithoutTicket(eventId)
}
