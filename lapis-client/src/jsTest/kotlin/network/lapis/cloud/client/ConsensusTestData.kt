package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule

// V1.9.28 -- shared fixtures of the consensus tests.

internal val SK_AT = LocalDateTime(2026, 6, 1, 12, 0)

/** The status quo option carries the server's English label on purpose: the client must never show it. */
internal const val SK_SERVER_STATUS_QUO_LABEL = "Status quo (no change)"

internal fun skOption(
    id: String,
    label: String,
    position: Int,
    statusQuo: Boolean = false,
    createdById: String = "m-9",
    createdBy: String = "Paula Proposer",
    rationale: String? = null,
) = SystemicConsensusOptionDto(
    id = id,
    systemicConsensusId = "k1",
    label = label,
    position = position,
    isStatusQuoOption = statusQuo,
    createdById = createdById,
    createdByDisplayName = createdBy,
    rationale = rationale,
)

internal fun skOptions() =
    listOf(
        skOption("o-sq", SK_SERVER_STATUS_QUO_LABEL, 0, statusQuo = true, createdById = "chair-1", createdBy = "Clara Chair"),
        skOption("o-a", "Option A", 1),
        skOption("o-b", "Option B", 2, createdById = "m-1", createdBy = "Mia Mitglied"),
    )

internal fun consensus(
    status: SystemicConsensusStatus = SystemicConsensusStatus.COLLECTION,
    secret: Boolean = true,
    bindingness: SystemicConsensusBindingness = SystemicConsensusBindingness.ADVISORY,
    round: Int = 1,
    maxRounds: Int = 3,
    options: List<SystemicConsensusOptionDto> = skOptions(),
    title: String = "Neues Vereinsheim",
    winnerOptionId: String? = null,
    tooManyOptionsWarning: Boolean = false,
    scaleMax: Int = 10,
) = SystemicConsensusDto(
    id = "k1",
    motionId = "m1",
    meetingId = "s1",
    title = title,
    status = status,
    secret = secret,
    scaleMax = scaleMax,
    aggregation = SystemicConsensusAggregation.MEAN,
    tiebreakRule = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
    groupConflictViableThreshold = 0.2.toDecimal(),
    groupConflictWarnThreshold = 0.5.toDecimal(),
    statusQuoOptionAuto = true,
    bindingness = bindingness,
    maxRounds = maxRounds,
    round = round,
    winnerOptionId = winnerOptionId,
    openedById = "chair-1",
    openedByDisplayName = "Clara Chair",
    openedAt = SK_AT,
    ratingOpenedAt = if (status == SystemicConsensusStatus.COLLECTION) null else SK_AT,
    ratingClosedAt =
        if (status == SystemicConsensusStatus.CLOSED || status == SystemicConsensusStatus.EVALUATED) SK_AT else null,
    tallyRunAt = if (status == SystemicConsensusStatus.EVALUATED) SK_AT else null,
    resolutionId = null,
    options = options,
    tooManyOptionsWarning = tooManyOptionsWarning,
)

internal fun skParticipation(
    eligible: Boolean? = true,
    hasRated: Boolean = false,
    canPropose: Boolean = true,
    canManage: Boolean = false,
    canRate: Boolean = true,
    eligibleCount: Int? = 4,
    ballotCount: Int = 0,
    round: Int = 1,
    id: String = "k1",
) = SystemicConsensusParticipationDto(
    systemicConsensusId = id,
    round = round,
    eligible = eligible,
    hasRated = hasRated,
    canProposeOptions = canPropose,
    canManage = canManage,
    canRate = canRate,
    eligibleCount = eligibleCount,
    ballotCount = ballotCount,
)

internal fun skOptionResult(
    optionId: String,
    mean: Double,
    index: Double,
    max: Int = 5,
    distribution: Map<Int, Int> = mapOf(1 to 2, 5 to 1),
) = SystemicConsensusOptionResultDto(
    optionId = optionId,
    cumulativeResistance = (mean * 3).toInt(),
    meanResistance = mean,
    maxResistance = max,
    standardDeviation = 1.0,
    consensusIndex = index,
    distribution = distribution,
)

internal fun skResult(
    winner: String? = "o-a",
    noRatings: Boolean = false,
    tiebreak: SystemicConsensusTiebreakRule? = null,
    results: List<SystemicConsensusOptionResultDto> =
        listOf(
            skOptionResult("o-sq", mean = 7.5, index = 0.75, max = 10),
            skOptionResult("o-b", mean = 4.0, index = 0.4),
            skOptionResult("o-a", mean = 2.4, index = 0.24),
        ),
) = SystemicConsensusResultDto(
    systemicConsensusId = "k1",
    optionResults = results,
    winnerOptionId = winner,
    tie = false,
    tiebreakApplied = tiebreak,
    consensusViable = false,
    groupConflictWarning = false,
    noRatings = noRatings,
)
