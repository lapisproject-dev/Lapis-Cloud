package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** V1.9.225 -- one position of the catalogue as the server serves it. The label comes from the client catalogue (`partgLabel`). */
@Serializable
data class PartgPositionDto(
    val code: String,
    val section: PartgSection,
    val kind: PartgKind,
    val parentCode: String?,
    val sortOrder: Int,
    val numberLabel: String,
    val legalRef: String,
    /** The organisation's own hint for this position (<= 500 characters, plain text). */
    val orgHint: String? = null,
)

/** One reporting unit the caller can pick: the federal level, a regional chapter, or the summary of its subordinate units. */
@Serializable
data class PartgUnitDto(
    val unitKey: String,
    val level: PartgUnitLevel,
    val chapterId: String? = null,
    val chapterName: String? = null,
)

/** One row of the overview. A `null` total means "nothing entered for this unit and year". */
@Serializable
data class PartgOverviewRowDto(
    val unit: PartgUnitDto,
    val filledCount: Int,
    val leafCount: Int,
    val incomeTotalCents: Long? = null,
    val expenseTotalCents: Long? = null,
    val netAssetsCents: Long? = null,
    val updatedAt: LocalDateTime? = null,
    /** V1.9.289 (R4a): the bookkeeping mode of the unit and year (a missing row = C; SUB units are always C). */
    val mode: PartgBookkeepingMode = PartgBookkeepingMode.C,
    /** V1.9.289 (R4a): number of POSTED bookings of the unit in the year (0 for SUB units). A count, never an amount. */
    val bookingCount: Int = 0,
    /** V1.9.289 (R4a): `true` iff the caller may change [mode] (federal roles only, never for SUB units). */
    val modeEditable: Boolean = false,
)

/** The values of one unit for year [year] and the year before, each with its own revision (optimistic locking). */
@Serializable
data class PartgSheetDto(
    val year: Int,
    val unit: PartgUnitDto,
    val values: Map<String, Long>,
    val revision: Long,
    val priorYear: Int,
    val priorValues: Map<String, Long>,
    val priorRevision: Long,
    /** V1.9.289 (R4a): where each value of [values] comes from (positions without an entry never occur: every stored value has a source). */
    val valueSources: Map<String, PartgValueSource> = emptyMap(),
)

/** One changed cell. [year] is the report year or the year before; a `null` [amountCents] deletes the value (manual input only). */
@Serializable
data class PartgValueChangeDto(
    val year: Int,
    val positionCode: String,
    val amountCents: Long?,
)

@Serializable
data class PartgSaveResultDto(
    val revision: Long,
    val priorRevision: Long,
    val changed: Int,
)

@Serializable
enum class PartgImportColumn { CURRENT, PRIOR }

/** One value the import would write. [oldCents] is `null` when nothing was entered before. */
@Serializable
data class PartgImportChangeDto(
    val positionCode: String,
    val column: PartgImportColumn,
    val oldCents: Long? = null,
    val newCents: Long,
    val fromFormula: Boolean = false,
)

/** The stable error codes of the Excel import. The client has one fixed text per code; free cell text never travels. */
@Serializable
enum class PartgImportErrorCode {
    NOT_A_XLSX,
    UNSUPPORTED_FILE,
    MACROS_NOT_ALLOWED,
    TOO_LARGE,
    TEMPLATE_VERSION,
    TARGET_MISMATCH,
    UNKNOWN_CODE,
    DUPLICATE_CODE,
    NEGATIVE_VALUE,
    NOT_A_NUMBER,
    TOO_MANY_DECIMALS,
    VALUE_TOO_LARGE,
    DATE_VALUE,
    FORMULA_WITHOUT_RESULT,
    CELL_ERROR,
    ROW_LIMIT,
    TOO_MANY_ERRORS,
    INCONSISTENT,
}

/** [detail] holds only an identifier or the unit and year of OUR template, cut to 64 characters; never free cell text. */
@Serializable
data class PartgImportErrorDto(
    val row: Int? = null,
    val column: String? = null,
    val code: PartgImportErrorCode,
    val detail: String? = null,
)

@Serializable
data class PartgImportPreviewDto(
    val token: String? = null,
    val recognized: Int = 0,
    val changes: List<PartgImportChangeDto> = emptyList(),
    val errors: List<PartgImportErrorDto> = emptyList(),
    val sheetRevision: Long = 0,
    val priorRevision: Long = 0,
)

/** The body of `POST /api/partg-report/import/apply`. */
@Serializable
data class PartgImportApplyDto(
    val token: String,
    val expectedRevision: Long,
    val expectedPriorRevision: Long,
)

@Serializable
data class PartgImportApplyResultDto(
    val changed: Int,
    val revision: Long,
    val priorRevision: Long,
)

/** Stable codes of a refused import route call. */
@Serializable
enum class PartgImportRejectionCode {
    FORBIDDEN,
    NOT_ENABLED,
    INVALID_INPUT,
    TOO_LARGE,
    RATE_LIMITED,
    TOKEN_INVALID,
    REVISION_CONFLICT,
    NOT_FOUND,

    /** V1.9.291 (R6): the stored content of a kept version no longer matches its checksum; nothing is rendered. Appended LAST. */
    INTEGRITY,
}

@Serializable
data class PartgImportRejectionDto(
    val code: PartgImportRejectionCode,
    val detail: String? = null,
)

/** The limits shared by client pre-checks and the server. */
object PartgImportLimits {
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
}

/** V1.9.256 -- the completeness of one unit: the not entered LEAF codes of the report year (catalogue order) and the count of the year before. */
@Serializable
data class PartgUnitCompletenessDto(
    val unit: PartgUnitDto,
    val missingCurrent: List<String>,
    val enteredPrior: Int,
)

@Serializable
data class PartgCompletenessDto(
    val year: Int,
    val priorYear: Int,
    val leafCount: Int,
    val units: List<PartgUnitCompletenessDto>,
)

/** V1.9.256 -- limits of the report export. */
object PartgReportLimits {
    const val MAX_PDF_PAGES = 600
    const val MAX_PDF_BYTES = 8 * 1024 * 1024
    const val MAX_XLSX_BYTES = 8 * 1024 * 1024
    const val PDF_TIME_LIMIT_SECONDS = 20L
}
