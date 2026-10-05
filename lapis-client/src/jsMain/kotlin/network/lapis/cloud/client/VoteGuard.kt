package network.lapis.cloud.client

import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IGovernanceService

/*
 * V1.9.54 -- the bid form of a meritocratic vote on the motion page: what a conflict means is found out by reading the vote, never by
 * reading the exception (Kilua RPC transmits only its type, and `castVoteBallot` also answers `ConflictException` for a stake that is too
 * low, has too many decimals or exceeds the balance -- those can simply be corrected and sent again, so "already voted" would be false).
 */

/**
 * The vote whose last bid was refused because the vote is no longer open. Held outside the detail view, because the view is rebuilt by the
 * reload that follows the refusal -- the message must survive that reload, and stays until another motion is opened.
 */
internal object RejectedBidNotice {
    private var voteId: String? = null

    fun mark(id: String) {
        voteId = id
    }

    fun clear() {
        voteId = null
    }

    /** Drops a mark that belongs to a vote of another motion; returns `true` while the notice is to be shown for one of [votes]. */
    fun shownFor(votes: List<VoteDto>): Boolean {
        val marked = voteId ?: return false
        if (votes.none { it.id == marked }) {
            voteId = null
            return false
        }
        return true
    }
}

/** The lasting "not counted" message, a warning with `role="alert"`. */
internal fun renderRejectedBidNotice(panel: SimplePanel) {
    panel.p(tr("Ihr Gebot wurde nicht gezählt: Die Abstimmung ist nicht mehr offen. Die Ansicht wurde aktualisiert.")) {
        addCssClasses("alert alert-warning mb-0")
        setAttribute("role", "alert")
    }
}

/**
 * Casts a bid. A conflict is probed: when the vote is no longer OPEN the bid was not counted -- [onNotCounted] runs (the caller marks the
 * [RejectedBidNotice] and reloads) and nothing is toasted. Any other conflict keeps the generic behaviour of `guarded` (the stake can be
 * corrected, or a bid exists), as does every other exception.
 */
internal suspend fun castVoteGuarded(
    input: VoteBallotInput,
    onNotCounted: () -> Unit,
): VoteBallotDto? =
    guarded {
        try {
            rpcService<IGovernanceService>().castVoteBallot(input).also { RejectedBidNotice.clear() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConflictException) {
            val vote =
                try {
                    rpcService<IGovernanceService>().getVote(input.voteId)
                } catch (probe: CancellationException) {
                    throw probe
                } catch (probe: Throwable) {
                    null
                }
            if (vote != null && vote.status != VoteStatus.OPEN) {
                onNotCounted()
                null
            } else {
                throw e
            }
        }
    }
