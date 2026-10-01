package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.SystemicConsensusBallotCastResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ISystemicConsensusService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/*
 * V1.9.28 -- the guards of the consensus screens. The same rule as for the elections: Kilua RPC transmits only the exception TYPE, a
 * conflict can mean "already rated", "stream not paused yet", "status changed", and the server's text may carry member UUIDs. So
 * `e.message` is NEVER shown here; what a conflict means is found out by reading the state again (see `probeConsensusState`).
 */

/**
 * Like `guarded`, but a [ConflictException] shows the fixed [conflictMessage] instead of the generic conflict toast -- for every
 * consensus WRITE call except the ballot. [onConflict] runs right after that message (typically a reload of the screen).
 */
suspend fun <T> consensusGuarded(
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
        onConflict()
        null
    } catch (e: BadRequestException) {
        notifyError(tr("Ungültige Anfrage."))
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/** What happened to a rating. No outcome carries a server message and none shows a toast: the booth explains each in place. */
sealed interface ConsensusCastOutcome {
    data class Ok(
        val result: SystemicConsensusBallotCastResultDto,
    ) : ConsensusCastOutcome

    data object Conflict : ConsensusCastOutcome

    data object Forbidden : ConsensusCastOutcome

    data object Failed : ConsensusCastOutcome
}

/** Casts [input]. A conflict or a refusal is an outcome (never toasted, never read for its message); a session expiry still routes to the login. */
suspend fun castConsensusBallotGuarded(input: SystemicConsensusBallotInput): ConsensusCastOutcome =
    try {
        ConsensusCastOutcome.Ok(rpcService<ISystemicConsensusService>().castResistanceBallot(input))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        ConsensusCastOutcome.Conflict
    } catch (e: ForbiddenException) {
        ConsensusCastOutcome.Forbidden
    } catch (e: UnauthenticatedException) {
        guarded<Unit> { throw e }
        ConsensusCastOutcome.Failed
    } catch (e: Throwable) {
        // A network failure or an unexpected error: the rating may or may not have been stored. The booth finds out by reading the
        // participation state, so no toast with a guess is shown here.
        ConsensusCastOutcome.Failed
    }
