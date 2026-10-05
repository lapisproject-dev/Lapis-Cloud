package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.shared.domain.DisclosureRules
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ResolutionDto
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.53 -- minimum participation for secret elections: below [DisclosureRules.MIN_ANONYMOUS_RESPONSES] ballots
 * the per-option figures of a secret election are never disclosed (same constant as polls and consensus). The
 * decision (winners, tie, majority) is made on the full data; only the figures leave reduced. No role exception.
 *
 * This file is the single decision point; `ServerElectionResultDisclosureTripwireTest` pins that no other file
 * builds the affected DTOs or reads the figures directly.
 *
 * True iff the per-option figures of this election must be withheld. Pure. The status is deliberately not part of
 * the rule: figures exist only from TALLIED on, so the rule is never looser than the status-qualified one.
 */
internal fun electionFiguresWithheld(
    secret: Boolean,
    ballotCount: Int,
): Boolean = secret && ballotCount < DisclosureRules.MIN_ANONYMOUS_RESPONSES

/** Ballot count of one election (rows in [ElectionBallotTable]). MUST run inside a transaction. */
internal fun electionBallotCount(electionId: Uuid): Int =
    ElectionBallotTable
        .selectAll()
        .where { ElectionBallotTable.electionId eq electionId }
        .count()
        .toInt()

/** Full, internal outcome -- never serialized. Built only by `ElectionService.computeOutcome`. */
internal data class ElectionOutcomeFigures(
    val electionId: Uuid,
    val winnerOptionIds: List<String>,
    val tie: Boolean,
    val majorityMet: Boolean?,
    val perOptionVotes: Map<String, Int>,
)

/**
 * The ONLY builder of [ElectionResultDto]: the disclosure decision is taken from ([secret], [ballotCount]) before
 * any figure is copied.
 */
internal fun disclosedElectionResult(
    figures: ElectionOutcomeFigures,
    secret: Boolean,
    ballotCount: Int,
): ElectionResultDto {
    val withheld = electionFiguresWithheld(secret = secret, ballotCount = ballotCount)
    return ElectionResultDto(
        electionId = figures.electionId.toString(),
        winnerOptionIds = figures.winnerOptionIds,
        tie = figures.tie,
        majorityMet = figures.majorityMet,
        perOptionVotes = if (withheld) emptyMap() else figures.perOptionVotes,
        figuresWithheld = withheld,
        minimumResponses = DisclosureRules.MIN_ANONYMOUS_RESPONSES,
    )
}

/**
 * Per-option vote counts for `ElectionOptionDto.voteCount`, plus the `figuresWithheld` flag. Empty (and flag
 * `false`) unless [status] is TALLIED. For a withheld election the decision is taken BEFORE
 * [ElectionBallotSelectionTable] is read. MUST run inside a transaction.
 */
internal fun disclosedOptionVoteCounts(
    electionId: Uuid,
    secret: Boolean,
    status: ElectionStatus,
    optionIds: List<Uuid>,
): Pair<Map<Uuid, Int>, Boolean> {
    if (status != ElectionStatus.TALLIED) return emptyMap<Uuid, Int>() to false
    if (electionFiguresWithheld(secret = secret, ballotCount = electionBallotCount(electionId))) return emptyMap<Uuid, Int>() to true
    if (optionIds.isEmpty()) return emptyMap<Uuid, Int>() to false
    val counts =
        ElectionBallotSelectionTable
            .selectAll()
            .where { ElectionBallotSelectionTable.optionId inList optionIds }
            .groupingBy { it[ElectionBallotSelectionTable.optionId] }
            .eachCount()
    return counts to false
}

/**
 * Batch: which of these election ids are secret AND below the minimum participation. One query for the secret
 * flags and one grouped count; no query for empty input. MUST run inside a transaction.
 */
internal fun withheldElectionIds(electionIds: Collection<Uuid>): Set<Uuid> {
    if (electionIds.isEmpty()) return emptySet()
    val secretIds =
        ElectionTable
            .selectAll()
            .where { (ElectionTable.id inList electionIds.toSet()) and (ElectionTable.secret eq true) }
            .map { it[ElectionTable.id] }
    if (secretIds.isEmpty()) return emptySet()
    val ballotCount = ElectionBallotTable.id.count()
    val countByElection =
        ElectionBallotTable
            .select(ElectionBallotTable.electionId, ballotCount)
            .where { ElectionBallotTable.electionId inList secretIds }
            .groupBy(ElectionBallotTable.electionId)
            .associate { it[ElectionBallotTable.electionId] to it[ballotCount].toInt() }
    return secretIds.filterTo(mutableSetOf()) { electionFiguresWithheld(secret = true, ballotCount = countByElection[it] ?: 0) }
}

/** Masks one resolution's figures: votes 0/0/0 + `figuresWithheld = true` iff its election is in [withheld]. */
internal fun ResolutionDto.withElectionDisclosure(withheld: Set<Uuid>): ResolutionDto {
    val id = electionId?.let(Uuid::parse)
    return if (id != null && id in withheld) {
        copy(votesYes = 0, votesNo = 0, votesAbstain = 0, figuresWithheld = true)
    } else {
        this
    }
}
