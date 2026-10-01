package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IPollService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/*
 * V1.9.31 "Umfragen" -- the guards of the poll screens. Same rules as the consensus guards: Kilua RPC transmits only the exception TYPE,
 * a conflict can mean "poll already closed", "limit reached", "already answered", and the server's text must never be shown. So
 * `e.message` is NEVER read here; what a conflict means is found out by reading the state again (see `probePollState`).
 *
 * One deliberate difference: a [ForbiddenException] is SILENT. A GUEST/FRIEND who types /polls into the address bar is refused by the
 * server before it even looks at the poll (no existence oracle); the screen answers the same way -- no toast, no server text, just
 * back to the start page, as if the entry did not exist.
 */

/** Where a refused reader is sent. */
private const val POLL_REFUSED_ROUTE = Routes.DASHBOARD

/** How a refused reader is sent away; a seam only so a test can observe the silent redirect. */
internal var pollRefusedNavigator: (String) -> Unit = { route -> navigateTo(route) }

/** Where the fixed error texts of the write guard go (the toast); a seam only so a test can read exactly what a member would be told. */
internal var pollErrorSink: (String) -> Unit = { message -> notifyError(message) }

/** For READ calls: a refusal navigates away without any message, every other failure goes through [guarded] (toast, session expiry). */
internal suspend fun <T> pollReadGuarded(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ForbiddenException) {
        pollRefusedNavigator(POLL_REFUSED_ROUTE)
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/**
 * Like `guarded` for the WRITE calls of the poll screens. A [ConflictException] shows the fixed [conflictMessage] (never the server's text)
 * and runs [onConflict] (typically a reload); a [BadRequestException] shows [badRequestMessage]; a [ForbiddenException] is silent and runs
 * [onConflict] too (the screen re-reads what the member may still do).
 */
internal suspend fun <T> pollGuarded(
    conflictMessage: String,
    badRequestMessage: String = tr("Ungültige Anfrage."),
    onConflict: () -> Unit = {},
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnauthenticatedException) {
        guarded<T> { throw e }
    } catch (e: ForbiddenException) {
        onConflict()
        null
    } catch (e: NotFoundException) {
        pollErrorSink(tr("Nicht gefunden."))
        null
    } catch (e: ConflictException) {
        pollErrorSink(conflictMessage)
        onConflict()
        null
    } catch (e: BadRequestException) {
        pollErrorSink(badRequestMessage)
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/** What happened to an answer. No outcome carries a server message and none shows a toast: the booth explains each in place. */
internal sealed interface PollCastOutcome {
    data class Ok(
        val participation: PollParticipationDto,
    ) : PollCastOutcome

    /** Not open any more, or already answered, or the option did not belong to the poll -- the booth finds out which by reading. */
    data object Conflict : PollCastOutcome

    data object Forbidden : PollCastOutcome

    data object Failed : PollCastOutcome
}

/** Casts [input]. A refusal is an outcome, never a toast; a session expiry still routes to the login. */
internal suspend fun castPollResponseGuarded(input: PollResponseInput): PollCastOutcome =
    try {
        PollCastOutcome.Ok(rpcService<IPollService>().castPollResponse(input))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        PollCastOutcome.Conflict
    } catch (e: BadRequestException) {
        // The option does not belong to this poll: the poll changed under the member. Treated like a conflict (read the state, then say).
        PollCastOutcome.Conflict
    } catch (e: ForbiddenException) {
        PollCastOutcome.Forbidden
    } catch (e: UnauthenticatedException) {
        guarded<Unit> { throw e }
        PollCastOutcome.Failed
    } catch (e: Throwable) {
        // A network failure or an unexpected error: the answer may or may not have been stored. The booth reads the participation state.
        PollCastOutcome.Failed
    }
