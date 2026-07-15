package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.ElectionAnswer
import kotlin.uuid.Uuid

/**
 * One ballot's candidate selection(s) -- the pure-function input shape for
 * [computePersonnelElectionErgebnis], deliberately decoupled from
 * [network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable] so this file has zero DB
 * dependency and can be property-tested directly (see `ElectionTallyTest`). Size `1` for
 * [network.lapis.cloud.shared.domain.ElectionType.SINGLE_CHOICE], `1..seatCount` for
 * [network.lapis.cloud.shared.domain.ElectionType.MULTI_CHOICE].
 */
data class ElectionBallotData(
    val optionIds: List<Uuid>,
)

/**
 * Result of [computeJaNeinErgebnis]. [tie] is `true` whenever the decisive vote (`ja + nein`,
 * abstentions excluded) is dead-even -- including the degenerate all-abstain/no-ballots-cast case
 * (`ja == nein == 0`), mirroring `VoteSettlement.computeVickreySettlement`'s "tie is the
 * safe, non-manipulable default" contract. [majorityMet] is always `false` when [tie] is `true`.
 */
data class JaNeinErgebnis(
    val ja: Int,
    val nein: Int,
    val enthaltung: Int,
    val majorityMet: Boolean,
    val tie: Boolean,
)

/**
 * Result of [computePersonnelElectionErgebnis]. [winnerOptionIds] is empty whenever [tie] is `true` --
 * a seat-cutoff tie resolves the *whole* Election to "no winners", never a partial seating (see that
 * function's KDoc).
 */
data class PersonnelElectionErgebnis(
    val voteCounts: Map<Uuid, Int>,
    val winnerOptionIds: List<Uuid>,
    val tie: Boolean,
)

/**
 * Ja/Nein tally for [network.lapis.cloud.shared.domain.ElectionType.YES_NO] Electionen. Pure function, no
 * DB access -- exhaustively property-testable, same rationale as
 * `VoteSettlement.computeVickreySettlement` KDoc gives for why this matters (a bug here
 * directly changes a governance outcome).
 *
 * `majorityMet` requires `ja`'s share of the *decisive* vote (`ja + nein`, [ElectionAnswer.ABSTAIN]
 * excluded from the denominator, matching standard Vereinsrecht practice) to reach
 * [requiredMajorityPercent], computed via exact integer arithmetic (`ja * 100 >=
 * requiredMajorityPercent * decisive`) to avoid any floating-point rounding boundary error. A tie
 * (`ja == nein`, including the `0 == 0` no-ballots-cast case) is undecided regardless of
 * [requiredMajorityPercent] and is never reported as [JaNeinErgebnis.majorityMet].
 */
fun computeJaNeinErgebnis(
    ballots: List<ElectionAnswer>,
    requiredMajorityPercent: Int,
): JaNeinErgebnis {
    require(requiredMajorityPercent in 1..100) {
        "requiredMajorityPercent must be in 1..100, got $requiredMajorityPercent"
    }
    val ja = ballots.count { it == ElectionAnswer.YES }
    val nein = ballots.count { it == ElectionAnswer.NO }
    val enthaltung = ballots.count { it == ElectionAnswer.ABSTAIN }
    val decisive = ja + nein
    val tie = decisive == 0 || ja == nein
    val majorityMet = !tie && ja.toLong() * 100 >= requiredMajorityPercent.toLong() * decisive
    return JaNeinErgebnis(ja = ja, nein = nein, enthaltung = enthaltung, majorityMet = majorityMet, tie = tie)
}

/**
 * Plurality tally for [network.lapis.cloud.shared.domain.ElectionType.SINGLE_CHOICE]/
 * [network.lapis.cloud.shared.domain.ElectionType.MULTI_CHOICE] Electionen -- the `seatCount` candidates
 * with the most votes win, one vote per selection (no cumulative voting, no ranked ballots; a
 * MULTI_CHOICE ballot selecting `k` candidates casts exactly `k` one-point votes, matching
 * [network.lapis.cloud.shared.domain.ElectionBallotInput] KDoc). Pure function, no DB access.
 *
 * Two documented decision points not fully spelled out by the concept document:
 * 1. **Undersubscribed election** (`optionIds.size <= seatCount`): every candidate is elected
 *    regardless of vote count (even `0`), mirroring the real-world plurality convention that an
 *    uncontested seat needs no ballot to be filled. No cutoff comparison applies.
 * 2. **Seat-cutoff tie**: otherwise, if the vote count of the `seatCount`-th-ranked candidate
 *    exactly equals the `(seatCount + 1)`-th-ranked candidate's count, the boundary is
 *    ambiguous -- the *whole* Election resolves to no winners ([tie] `true`,
 *    [PersonnelElectionErgebnis.winnerOptionIds] empty), not a partial seating of the unambiguous
 *    top seats. Mirrors `VoteSettlement`'s "tie is undecided, no manipulable default"
 *    philosophy. Ranking uses a deterministic tie-break (option id string ascending) purely to
 *    make the comparison well-defined -- it never decides an actual winner, only which two
 *    counts sit at the cutoff boundary.
 */
fun computePersonnelElectionErgebnis(
    ballots: List<ElectionBallotData>,
    optionIds: List<Uuid>,
    seatCount: Int,
): PersonnelElectionErgebnis {
    require(seatCount >= 1) { "seatCount must be >= 1, got $seatCount" }
    require(optionIds.isNotEmpty()) { "computePersonnelElectionErgebnis requires at least 1 option" }
    require(optionIds.size == optionIds.toSet().size) { "optionIds must not contain duplicates" }
    require(ballots.all { ballot -> ballot.optionIds.all { it in optionIds } }) {
        "Every selected option must be one of optionIds"
    }
    require(ballots.all { it.optionIds.size == it.optionIds.toSet().size }) {
        "A single ballot may not select the same option twice"
    }

    val counts: Map<Uuid, Int> =
        optionIds.associateWith { optionId -> ballots.sumOf { ballot -> ballot.optionIds.count { it == optionId } } }

    if (optionIds.size <= seatCount) {
        return PersonnelElectionErgebnis(voteCounts = counts, winnerOptionIds = optionIds.toList(), tie = false)
    }

    // Deterministic ordering for equal counts: optionId string ascending, so identical input
    // always produces the same ordering (and therefore the same tie/no-tie determination).
    val ordered = optionIds.sortedWith(compareByDescending<Uuid> { counts.getValue(it) }.thenBy { it.toString() })
    val cutoffCount = counts.getValue(ordered[seatCount - 1])
    val nextCount = counts.getValue(ordered[seatCount])
    val tie = cutoffCount == nextCount

    return if (tie) {
        PersonnelElectionErgebnis(voteCounts = counts, winnerOptionIds = emptyList(), tie = true)
    } else {
        PersonnelElectionErgebnis(voteCounts = counts, winnerOptionIds = ordered.take(seatCount), tie = false)
    }
}
