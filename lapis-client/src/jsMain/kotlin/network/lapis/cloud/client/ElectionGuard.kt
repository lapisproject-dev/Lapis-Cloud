package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.ElectionBallotCastResultDto
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/*
 * V1.9.22 -- the guards of the elections screens. Kilua RPC transmits only the exception TYPE, never its message
 * (`AppState.guarded` KDoc), and "already voted", "stream not paused", "status changed" and "member sits on the executive board"
 * are all the same `ConflictException`. The server may still put a message on the wire (it can contain member UUIDs), so
 * `e.message` is NEVER shown anywhere in the elections code -- an `ElectionSecrecyTripwireTest` rule enforces that. What a
 * conflict means is found out by reloading the participation state, never by reading the exception.
 */

/**
 * Like `guarded`, but a [ConflictException] shows the fixed [conflictMessage] instead of the generic conflict toast -- for every
 * election WRITE call. [onConflict] runs right after that message (typically a reload of the screen). Any other exception
 * behaves exactly as in `guarded`.
 */
suspend fun <T> electionGuarded(
    conflictMessage: String,
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
        notifyError(tr("Keine Berechtigung für diese Aktion."))
        null
    } catch (e: NotFoundException) {
        notifyError(tr("Nicht gefunden."))
        null
    } catch (e: ConflictException) {
        notifyError(conflictMessage)
        // V1.9.23: a conflict now usually means another decision path got there first (an election, a vote, a
        // quorum resolution). The caller reloads, so the screen shows the real state instead of a stale one.
        onConflict()
        null
    } catch (e: BadRequestException) {
        notifyError(tr("Ungültige Anfrage."))
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/** What happened to a ballot. No outcome carries a server message, and none of them shows a toast: the booth explains each in place. */
sealed interface CastOutcome {
    data class Ok(
        val result: ElectionBallotCastResultDto,
    ) : CastOutcome

    data object Conflict : CastOutcome

    data object Forbidden : CastOutcome

    data object Failed : CastOutcome
}

/** Casts [input]. A conflict or a refusal is returned as an outcome (never toasted, never read for its message); a session expiry still routes to the login. */
suspend fun castBallotGuarded(input: ElectionBallotInput): CastOutcome =
    try {
        CastOutcome.Ok(rpcService<IElectionService>().castElectionBallot(input))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        CastOutcome.Conflict
    } catch (e: ForbiddenException) {
        CastOutcome.Forbidden
    } catch (e: UnauthenticatedException) {
        guarded<Unit> { throw e }
        CastOutcome.Failed
    } catch (e: Throwable) {
        // A network failure or an unexpected error: the ballot may or may not have been stored. The booth finds out by reloading
        // the participation state, so no toast with a guess is shown here.
        CastOutcome.Failed
    }
