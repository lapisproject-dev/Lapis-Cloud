package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Systemic Consensus (V0.2.5): a third, orthogonal counting logic hung off the same
 * Motion/Meeting/resolution book governance spine as [VoteDto] (LTR-weighted eBay/Vickrey
 * basket auction) and [ElectionDto] (one-person-one-vote elections) -- here every participant rates
 * *every* option with a resistance value (Resistance) on a `0..scaleMax` scale, and the option
 * with the LOWEST cumulative resistance (Kumulierter Resistance / KW) wins. See
 * `network.lapis.cloud.server.rpc.SystemicConsensusService` KDoc for the full lifecycle and
 * `03 Bereiche/Lapis Cloud/Systemic Consensus.md` for the concept document this implements.
 *
 * [COLLECTION] (options collected) -> [RATING] (participants rate every frozen option) ->
 * [CLOSED] -> [EVALUATED], or [ABORTED] from any non-terminal state. A
 * [CLOSED]/[EVALUATED] SystemicConsensus may return to [RATING] via `reopenRating`
 * (discussion + revote), incrementing [SystemicConsensusDto.round] up to
 * [SystemicConsensusDto.maxRounds] times.
 */
@Serializable
enum class SystemicConsensusStatus { COLLECTION, RATING, CLOSED, EVALUATED, ABORTED }

/**
 * Display/cross-SystemicConsensus-comparability knob only -- within one SystemicConsensus, [MEAN]
 * (mean resistance, KW/n) and [SUM] (raw KW) always agree on the winner, because every option
 * is rated by the same `n` voters (mean is a monotone transform of the sum). See
 * `network.lapis.cloud.server.rpc.computeSystemicConsensusResult` KDoc.
 */
@Serializable
enum class SystemicConsensusAggregation { MEAN, SUM }

/**
 * How a Kumulierter-Resistance (KW) tie between two or more options is broken.
 * [LOWEST_MAX_RESISTANCE] (lowest per-option maximum single resistance rating wins -- the
 * strongest minority-protection rule, the concept document's recommended default) ->
 * [LOWEST_STD_DEV] (lowest standard deviation of resistance ratings wins) -> [REPEAT]
 * (no tiebreak at all -- the SystemicConsensus resolves to a tie with no winner, signalling a repeat
 * round, analogous to [ElectionStatus] having no direct POSTPONED state but the same "tie is the safe,
 * non-manipulable default" philosophy as `ElectionTally`).
 */
@Serializable
enum class SystemicConsensusTiebreakRule { LOWEST_MAX_RESISTANCE, LOWEST_STD_DEV, REPEAT }

/**
 * [ADVISORY] (advisory only -- `evaluate` never writes a [ResolutionDto]) vs. [BINDING]
 * (`evaluate` writes a [ResolutionDto] tagged [ResolutionMode.SYSTEMIC_CONSENSUS] and
 * transitions the hosting Motion, exactly like [ElectionDto]/[VoteDto] do). [ADVISORY] is the
 * safe default -- see `03 Bereiche/Lapis Cloud/Systemic Consensus.md`.
 */
@Serializable
enum class SystemicConsensusBindingness { ADVISORY, BINDING }

/**
 * A ratable option. [isStatusQuoOption] marks the (optionally auto-inserted, see
 * [SystemicConsensusOpenInput.statusQuoOptionAuto]) status-quo/"do nothing" option every participant can rate
 * alongside every real proposal -- a low status quo option resistance is a strong signal the group
 * would rather change nothing than accept any of the alternatives on offer.
 */
@Serializable
data class SystemicConsensusOptionDto(
    val id: String,
    val systemicConsensusId: String,
    val label: String,
    val position: Int,
    val isStatusQuoOption: Boolean,
    val createdById: String,
    val createdByDisplayName: String,
)

@Serializable
data class SystemicConsensusOptionInput(
    val label: String,
)

@Serializable
data class SystemicConsensusDto(
    val id: String,
    val motionId: String,
    val meetingId: String,
    val title: String,
    val status: SystemicConsensusStatus,
    val secret: Boolean,
    val scaleMax: Int,
    val aggregation: SystemicConsensusAggregation,
    val tiebreakRule: SystemicConsensusTiebreakRule,
    val groupConflictViableThreshold: Decimal,
    val groupConflictWarnThreshold: Decimal,
    val statusQuoOptionAuto: Boolean,
    val bindingness: SystemicConsensusBindingness,
    val maxRounds: Int,
    val round: Int,
    val winnerOptionId: String?,
    val openedById: String,
    val openedByDisplayName: String,
    val openedAt: LocalDateTime,
    val ratingOpenedAt: LocalDateTime?,
    val ratingClosedAt: LocalDateTime?,
    val tallyRunAt: LocalDateTime?,
    val resolutionId: String?,
    val options: List<SystemicConsensusOptionDto>,
    /** `true` once [SystemicConsensusOptionDto] count exceeds the server-side soft cap -- see `SystemicConsensusService.MAX_OPTIONS_SOFT`. */
    val tooManyOptionsWarning: Boolean,
)

/**
 * [secret] defaults to `true` per the concept document's recommendation (see
 * `network.lapis.cloud.server.rpc.SystemicConsensusService` KDoc for the anonymity mechanism --
 * identical DB-level table-split already used by [ElectionDto], no cryptography). [bindingness]
 * defaults to [SystemicConsensusBindingness.ADVISORY], the safe advisory default.
 */
@Serializable
data class SystemicConsensusOpenInput(
    val motionId: String,
    val secret: Boolean = true,
    val scaleMax: Int = 10,
    val aggregation: SystemicConsensusAggregation = SystemicConsensusAggregation.MEAN,
    val tiebreakRule: SystemicConsensusTiebreakRule = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
    val groupConflictViableThreshold: Decimal = 0.2.toDecimal(),
    val groupConflictWarnThreshold: Decimal = 0.5.toDecimal(),
    val statusQuoOptionAuto: Boolean = true,
    val bindingness: SystemicConsensusBindingness = SystemicConsensusBindingness.ADVISORY,
    val maxRounds: Int = 3,
)

/**
 * The ballot itself -- [resistances] MUST rate every option frozen at `freezeOptions` exactly
 * once, key = option id, value = resistance in `0..systemic_consensus.scaleMax`.
 * `SystemicConsensusService.castResistanceBallot` rejects any other shape.
 */
@Serializable
data class SystemicConsensusBallotInput(
    val systemicConsensusId: String,
    val resistances: Map<String, Int>,
)

/**
 * [receiptCode] is present only when the SystemicConsensus is [SystemicConsensusDto.secret] -- the one
 * time it is ever returned to a caller, mirroring [ElectionBallotCastResultDto].
 */
@Serializable
data class SystemicConsensusBallotCastResultDto(
    val id: String,
    val castAt: LocalDateTime,
    val receiptCode: String?,
)

/**
 * Transparency read of ballots cast so far. For a [SystemicConsensusDto.secret] SystemicConsensus,
 * [memberId]/[memberDisplayName] are always `null` (mirrors [ElectionBallotDto]), and
 * [resistances] is `emptyMap()` until [SystemicConsensusStatus.EVALUATED] -- the same pre-tally
 * secrecy gate [ElectionBallotDto.selectedOptionLabels] documents. Non-secret SystemicConsensusen always
 * reveal the resistance values.
 */
@Serializable
data class SystemicConsensusBallotDto(
    val id: String,
    val systemicConsensusId: String,
    val memberId: String?,
    val memberDisplayName: String?,
    /** option id -> resistance value. */
    val resistances: Map<String, Int>,
    val castAt: LocalDateTime,
    val round: Int,
)

/**
 * Per-option aggregate. [cumulativeResistance] is the classic KW (sum of every rated
 * resistance); [meanResistance] = KW/n; [consensusIndex] is the Gruppenkonflikt-Index (G-K) =
 * KW / (n * scaleMax), in `[0, 1]` -- 0 means unanimous full acceptance, 1 means unanimous
 * maximum resistance. [distribution] maps each distinct resistance value cast to how many voters
 * cast it (a rating histogram for this option).
 */
@Serializable
data class SystemicConsensusOptionResultDto(
    val optionId: String,
    val cumulativeResistance: Int,
    val meanResistance: Double,
    val maxResistance: Int,
    val standardDeviation: Double,
    val consensusIndex: Double,
    val distribution: Map<Int, Int>,
)

/**
 * [winnerOptionId] is `null` iff [tie] is `true` and [SystemicConsensusTiebreakRule.REPEAT] applies (or
 * [noRatings] is `true`) -- otherwise a deterministic fallback always yields a concrete
 * winner even under an unbroken KW tie. [tiebreakApplied] names the rule that actually decided
 * a KW tie (`null` when the raw KW comparison alone already gave a clear winner).
 * [consensusViable] is `true` when the winner's G-K is below
 * [SystemicConsensusDto.groupConflictViableThreshold]; [groupConflictWarning] is `true` when it exceeds
 * [SystemicConsensusDto.groupConflictWarnThreshold].
 */
@Serializable
data class SystemicConsensusResultDto(
    val systemicConsensusId: String,
    val optionResults: List<SystemicConsensusOptionResultDto>,
    val winnerOptionId: String?,
    val tie: Boolean,
    val tiebreakApplied: SystemicConsensusTiebreakRule?,
    val consensusViable: Boolean,
    val groupConflictWarning: Boolean,
    val noRatings: Boolean,
)
