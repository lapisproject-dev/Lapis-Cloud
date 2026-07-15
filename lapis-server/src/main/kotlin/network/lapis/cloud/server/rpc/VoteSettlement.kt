package network.lapis.cloud.server.rpc

import java.math.BigDecimal
import java.math.BigInteger
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
 *    to the cent via the largest-remainder method so `Σ charges == s` **exactly** — no LTR is
 *    created or destroyed by rounding. The whole computation after the total/tie determination
 *    runs on [BigInteger] cent counts (`stake`/`secondPrice`/`winnerTotal` all have scale 2, i.e.
 *    are already whole numbers of cents), so there is no floating-point or repeating-decimal
 *    error to begin with — only the *final* apportionment of leftover cents needs a tie-break
 *    rule, which is deterministic (largest remainder first, member id ascending as the tie-break
 *    for exactly equal remainders).
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
    val charges = allocateProportional(winningBallots, secondPrice, winnerTotal)
    return Settlement(winnerOptionId = winnerOptionId, secondPrice = secondPrice.setScale(2, RoundingMode.UNNECESSARY), charges = charges)
}

/**
 * Largest-remainder apportionment of [secondPrice] among [winningBallots], proportional to each
 * ballot's stake out of [winnerTotal]. All three amounts have scale 2 (whole cents), so converting
 * to [BigInteger] cent counts is always exact (no rounding at that step) — every subsequent
 * computation (`stakeCents * secondPriceCents`, then integer-divided by `winnerTotalCents`) is
 * exact [BigInteger] arithmetic too, so the only place any rounding decision is made at all is the
 * final one-cent-at-a-time apportionment of the leftover remainder below.
 */
private fun allocateProportional(
    winningBallots: List<Ballot>,
    secondPrice: BigDecimal,
    winnerTotal: BigDecimal,
): Map<Uuid, BigDecimal> {
    if (winningBallots.isEmpty()) return emptyMap()
    if (secondPrice.signum() == 0 || winnerTotal.signum() == 0) {
        return winningBallots.associate { it.memberId to ZERO_2DP }
    }

    val secondPriceCents = secondPrice.movePointRight(2).toBigIntegerExact()
    val winnerTotalCents = winnerTotal.movePointRight(2).toBigIntegerExact()

    // Deterministic base order: member id, ascending. Both the floor pass and the
    // largest-remainder tie-break below iterate in this order so equal inputs always produce
    // identical output.
    val ordered = winningBallots.sortedBy { it.memberId.toString() }

    data class Share(
        val memberId: Uuid,
        val floorCents: BigInteger,
        val remainderCents: BigInteger,
    )

    val shares =
        ordered.map { ballot ->
            val stakeCents = ballot.stake.movePointRight(2).toBigIntegerExact()
            val numerator = stakeCents * secondPriceCents
            val floorCents = numerator / winnerTotalCents
            val remainderCents = numerator - floorCents * winnerTotalCents
            Share(ballot.memberId, floorCents, remainderCents)
        }

    val floorSumCents = shares.fold(BigInteger.ZERO) { acc, s -> acc + s.floorCents }
    var leftoverCents = (secondPriceCents - floorSumCents).toInt()

    val amounts = shares.associateTo(LinkedHashMap()) { it.memberId to it.floorCents }

    // Distribute the leftover cents one at a time to the largest-remainder ballots first (member
    // id ascending as the deterministic tie-break for equal remainders); wraps around if
    // leftoverCents ever exceeded the ballot count (cannot happen mathematically here, since each
    // floor loses strictly less than 1 cent, but the loop is written to stay correct regardless).
    val byRemainderDesc = shares.sortedWith(compareByDescending<Share> { it.remainderCents }.thenBy { it.memberId.toString() })
    var idx = 0
    while (leftoverCents > 0 && byRemainderDesc.isNotEmpty()) {
        val target = byRemainderDesc[idx % byRemainderDesc.size].memberId
        amounts[target] = amounts.getValue(target) + BigInteger.ONE
        leftoverCents--
        idx++
    }

    return amounts.mapValues { (_, cents) -> BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY) }
}
