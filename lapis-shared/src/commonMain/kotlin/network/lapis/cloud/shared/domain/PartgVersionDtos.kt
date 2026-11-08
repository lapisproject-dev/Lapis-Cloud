package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.291 (R6) -- where a kept version of the accountability report stands. Literal order is load-bearing (stored nowhere, but the client
 * orders by it): new literals are appended. A year's status is the highest status of its whole-party (`ALL`) versions.
 */
@Serializable
enum class PartgVersionStatus { DRAFT, FROZEN, DECLARED, FINAL }

/** V1.9.291 (R6): does the live working state still equal the newest kept whole-party version? `NOT_CHECKED` = not computed (rate limit, no version). */
@Serializable
enum class PartgDraftDeviation { SAME, DIFFERS, UNKNOWN_FORMAT, NOT_CHECKED }

/** V1.9.291 (R6): how loud the deadline notice is. Literal order is load-bearing. */
@Serializable
enum class PartgDeadlineLevel { NONE, INFO, WARNING, DANGER }

/** One kept version without its content. Names are resolved fresh from `member` (an anonymised member shows the anonymised name). */
@Serializable
data class PartgVersionDto(
    val id: String,
    val year: Int,
    /** `ALL` or a unit key. */
    val scopeKey: String,
    val versionNumber: Int,
    val status: PartgVersionStatus,
    val frozenByName: String,
    val frozenAt: LocalDateTime,
    val comment: String?,
    /** SHA-256 (hex, lower case) over the stored content. */
    val contentHash: String,
    val byteSize: Int,
    /** The content holds the names and addresses of statements B and C (third parties). */
    val hasThirdPartyData: Boolean,
    val declaredByName: String? = null,
    val declaredAt: LocalDateTime? = null,
    val declarationTemplateCode: String? = null,
    val finalizedByName: String? = null,
    val finalizedAt: LocalDateTime? = null,
)

@Serializable
data class PartgDeadlineDto(
    val year: Int,
    val deadline: LocalDate,
    /** `false` = a computed proposal that nobody has confirmed. */
    val confirmed: Boolean,
    val today: LocalDate,
    val level: PartgDeadlineLevel,
    /** Negative = overdue. */
    val daysUntil: Int,
)

@Serializable
data class PartgVersionOverviewDto(
    val year: Int,
    /** Newest first: scope `ALL` before units, then by version number descending. */
    val versions: List<PartgVersionDto>,
    val yearStatus: PartgVersionStatus,
    val deadline: PartgDeadlineDto,
    val draftDeviation: PartgDraftDeviation,
)

@Serializable
enum class PartgDiffMarker { SAME, CHANGED, ADDED, REMOVED }

/** The state of one cell in one of the two compared versions. `ABSENT` = the row or column did not exist in that version. */
@Serializable
enum class PartgDiffValueState { VALUE, PARTIAL, NOT_ENTERED, NOT_COMPUTABLE, ABSENT }

@Serializable
data class PartgDiffValueDto(
    val state: PartgDiffValueState,
    val cents: Long? = null,
)

@Serializable
data class PartgDiffCellDto(
    val code: String,
    val before: PartgDiffValueDto,
    val after: PartgDiffValueDto,
    /** `after - before`, only when both are plain values; otherwise `null` (shown as "n. b."). */
    val deltaCents: Long?,
    val marker: PartgDiffMarker,
)

/** `rowKind`: `UNIT` (key = unit key), `SUM` (sum of the units), `CONSOLIDATED`. [cells] holds only the cells that are not `SAME`. */
@Serializable
data class PartgDiffRowDto(
    val rowKind: String,
    val rowKey: String,
    val label: String,
    val marker: PartgDiffMarker,
    val cells: List<PartgDiffCellDto>,
)

@Serializable
data class PartgVersionDiffDto(
    val versionIdA: String,
    val versionIdB: String,
    val versionNumberA: Int,
    val versionNumberB: Int,
    val year: Int,
    val rows: List<PartgDiffRowDto>,
    /** `true` = more rows than the cap; the list was cut. */
    val truncated: Boolean,
    /** The two versions have different years or scopes; the comparison is then only about the cells both have. */
    val comparable: Boolean,
)

/** One candidate of the deletion routine (never the content). */
@Serializable
data class PartgVersionDeletionCandidateDto(
    val versionId: String,
    val year: Int,
    val scopeKey: String,
    val versionNumber: Int,
    val frozenAt: LocalDateTime,
    val finalized: Boolean,
)

@Serializable
data class PartgVersionDeletionPreviewDto(
    val olderThanYears: Int,
    val candidates: List<PartgVersionDeletionCandidateDto>,
    /** `true` = more candidates than [PartgVersionRules.MAX_DELETE_IDS]; they are removed in several rounds. */
    val truncated: Boolean,
)
