package network.lapis.cloud.server.rpc

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/**
 * One member's stake into one basket — the pure-function input shape for
 * [computeVickreySettlement], deliberately decoupled from
 * [network.lapis.cloud.server.db.generated.VoteBallotTable] so this file has zero DB
 * dependency and can be property-tested directly (see `VoteVickreyTest`).
 */
data class Ballot(
    val memberId: Uuid,
    val optionId: Uuid,
    val stake: BigDecimal,
)

/**
 * Result of [computeVickreySettlement]. [charges] holds *winning* ballots only, keyed by
 * `memberId`; a losing ballot's settled amount is always `0` and is not present in this map —
 * callers (`GovernanceService.closeVote`) write `0` explicitly for every ballot not in
 * [charges] when persisting `settled_ltr`, since the DB column must never stay `null` once an
 * Vote is [network.lapis.cloud.shared.domain.VoteStatus.CLOSED].
 */
data class Settlement(
    val winnerOptionId: Uuid?,
    val secondPrice: BigDecimal,
    val charges: Map<Uuid, BigDecimal>,
)

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * The eBay/Vickrey basket-auction settlement — see the V0.2.3 implementation plan's "Canonical
 * mechanism decision" for why this, not a shareholder-style balance-weighted ballot, is the
 * correct read of the concept document. Pure function, no DB access, so its correctness (the
 * single most manipulation-sensitive piece of this wave — a bug here directly changes governance
 * outcomes and/or lets a member be over- or under-charged) can be exhaustively property-tested.
 *
 * Algorithm:
 * 1. `total(o)` = sum of [Ballot.stake] for every ballot targeting option `o`, for every `o` in
 *    [optionIds] (an option with zero ballots has `total == 0`).
 * 2. The option(s) with the strictly highest total are the "top options". If more than one option
 *    ties for the highest total (including the degenerate case where *no* ballot was cast at all,
 *    so every option ties at `0`), the vote is undecided: [Settlement.winnerOptionId] is `null`,
 *    [Settlement.secondPrice] is `0`, and [Settlement.charges] is empty — no LTR changes hands on
 *    an undecided vote. This is the safe, non-manipulable default (documented decision point,
 *    flagged for the reviewer); `GovernanceService.closeVote` maps this to
 *    [network.lapis.cloud.shared.domain.MotionStatus.POSTPONED].
 * 3. Otherwise the sole top option is the winner with total `w`; `secondPrice` (`s`) is the
 *    highest total among the *other* options (`0` if every other option has no ballots at all —
 *    an uncontested vote, where winners then pay nothing, which is intended).
 * 4. Each winning ballot with stake `b_i` (where `Σ b_i == w`) is charged `b_i · s / w`, rounded
 *    to the cent via [LargestRemainderApportionment] so `Σ charges == s` **exactly** — no LTR is
 *    created or destroyed by rounding. See that object's KDoc for the whole-cents/tie-break
 *    mechanism (V0.6.1 extracted it from this file so `CrowdfundingDistributionCalculator` can
 *    reuse the identical, already-tested rounding logic instead of a parallel implementation).
 *
 * Deterministic: the same [ballots]/[optionIds] input always produces the same [Settlement].
 */
fun computeVickreySettlement(
    ballots: List<Ballot>,
    optionIds: List<Uuid>,
): Settlement {
    require(optionIds.size >= 2) { "computeVickreySettlement requires at least 2 options, got ${optionIds.size}" }
    require(ballots.all { it.stake.signum() > 0 }) { "All ballot stakes must be strictly positive" }
    require(ballots.all { it.optionId in optionIds }) { "Every ballot must target one of optionIds" }

    val totals: Map<Uuid, BigDecimal> =
        optionIds.associateWith { optionId ->
            ballots.filter { it.optionId == optionId }.fold(BigDecimal.ZERO) { acc, b -> acc + b.stake }
        }
    val maxTotal = totals.values.maxWithOrNull(naturalOrder()) ?: BigDecimal.ZERO
    val topOptions = totals.filterValues { it.compareTo(maxTotal) == 0 }.keys

    if (topOptions.size != 1) {
        // Tie for the top spot (including "nobody staked anything anywhere") -- undecided, no
        // LTR moves. See KDoc point 2.
        return Settlement(winnerOptionId = null, secondPrice = ZERO_2DP, charges = emptyMap())
    }

    val winnerOptionId = topOptions.single()
    val winnerTotal = totals.getValue(winnerOptionId)
    val secondPrice =
        totals
            .filterKeys { it != winnerOptionId }
            .values
            .maxWithOrNull(naturalOrder())
            ?: ZERO_2DP

    val winningBallots = ballots.filter { it.optionId == winnerOptionId }
    val charges = allocateProportional(winningBallots = winningBallots, secondPrice = secondPrice)
    return Settlement(winnerOptionId = winnerOptionId, secondPrice = secondPrice.setScale(2, RoundingMode.UNNECESSARY), charges = charges)
}

/**
 * Delegates to [LargestRemainderApportionment.apportion], keyed by [Ballot.memberId]/
 * [Ballot.stake] -- the weight total [LargestRemainderApportionment] computes internally always
 * equals the winning option's own total by construction (every ballot in [winningBallots]
 * targets the same, already-determined winner option), so no separate `winnerTotal` parameter is
 * needed here anymore (V0.6.1 extraction; behavior is unchanged, see `VoteVickreyTest`).
 */
private fun allocateProportional(
    winningBallots: List<Ballot>,
    secondPrice: BigDecimal,
): Map<Uuid, BigDecimal> =
    LargestRemainderApportionment.apportion(
        weights =
            winningBallots.associate {
                it.memberId to it.stake
            },
        pool = secondPrice,
    )
