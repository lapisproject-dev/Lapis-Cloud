package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/** Audit snapshot of ONE value of a report sheet (V1.9.225). [cents] is `null` on the side where the value did not exist. */
@Serializable
data class PartgValueAuditSnapshot(
    val year: Int,
    val unitKey: String,
    val positionCode: String,
    val cents: Long?,
    val source: PartgValueSource?,
)

/** Audit snapshot of the hint of a position (V1.9.225). The hint is an organisation text, not personal data. */
@Serializable
data class PartgPositionHintSnapshot(
    val code: String,
    val hint: String?,
)

/** Collective audit entry of one applied Excel import (V1.9.225). No file content, no cell text. */
@Serializable
data class PartgImportAuditSnapshot(
    val sha256: String,
    val fileName: String,
    val changeCount: Int,
    val year: Int,
    val unitKey: String,
)

/** The file format of a report export (V1.9.256). */
@Serializable
enum class PartgReportFormat { PDF, XLSX }

/** Audit snapshot of ONE export of the report draft (V1.9.256). Counts and ids only: never an amount, never a name. */
@Serializable
data class PartgReportExportAuditSnapshot(
    val format: PartgReportFormat,
    val year: Int,
    /** `ALL` or a unit key. */
    val scope: String,
    val unitCount: Int,
    /** Not entered LEAF values of the report year over all units in scope. */
    val missingCount: Int,
    val byteSize: Int,
    /** V1.9.269: number of large-donor rows in the export (a counter, never a name). */
    val donorRowCount: Int = 0,
    /** V1.9.283: number of sponsor rows in the export (only sponsors that are part of statement C). */
    val sponsorRowCount: Int = 0,
    /** V1.9.283: number of youth organisation rows in the export. */
    val youthOrgRowCount: Int = 0,
    /** V1.9.287 (R3c): number of enterprise rows of statement D in the export. */
    val enterpriseRowCount: Int = 0,
    /** V1.9.269: number of plausibility hints shown in the export. */
    val findingCount: Int = 0,
)

/** V1.9.269 audit snapshot of a change of the separate statements: counters, ids and codes only. */
@Serializable
data class PartgAnnexAuditSnapshot(
    val year: Int,
    val unitKey: String,
    /**
     * `FIGURES`, `DONOR_ADDED`, `DONOR_UPDATED`, `DONOR_DELETED`; since V1.9.283 also `SPONSOR_ADDED`, `SPONSOR_UPDATED`, `SPONSOR_DELETED`,
     * `YOUTH_ORG_ADDED`, `YOUTH_ORG_UPDATED` and `YOUTH_ORG_DELETED`.
     */
    val change: String,
    val changedFields: List<String> = emptyList(),
    val donorId: String? = null,
    val donorCount: Int,
    val memberCountEntered: Boolean,
    val deductionsEntered: Int,
    /** V1.9.283: ids and counters only, never a name or an amount. */
    val sponsorId: String? = null,
    val sponsorCount: Int = 0,
    val sponsoringEntryCount: Int = 0,
    val youthOrgId: String? = null,
    val youthOrgCount: Int = 0,
    /** V1.9.287 (R3c): `ENTERPRISE_ADDED`, `ENTERPRISE_UPDATED`, `ENTERPRISE_REMOVED`; id and counter only. */
    val enterpriseId: String? = null,
    val enterpriseCount: Int = 0,
)

/** V1.9.269 audit snapshot of a change of the notes: which blocks, how many holdings. No text. */
@Serializable
data class PartgNotesAuditSnapshot(
    val year: Int,
    val includedBlocks: List<PartgNoteBlock>,
    val changedBlocks: List<PartgNoteBlock>,
    val holdingCount: Int,
)

/** V1.9.290 (R5) audit snapshot of a change of an intra-party transfer pair. `change`: `TRANSFER_ADDED`, `TRANSFER_UPDATED`, `TRANSFER_DELETED`. No amount, no note. */
@Serializable
data class PartgIntraTransferAuditSnapshot(
    val year: Int,
    val change: String,
    val kind: String,
    val payerUnit: String,
    val payeeUnit: String,
    val hasPayerAmount: Boolean,
    val hasPayeeAmount: Boolean,
)

/** V1.9.290 (R5) audit snapshot of a donor combination. `change`: `COMBINATION_CREATED`, `COMBINATION_DISSOLVED`. Ids and counters only: no name. */
@Serializable
data class PartgDonorCombinationAuditSnapshot(
    val year: Int,
    val change: String,
    val rowCount: Int,
    val unitKeys: List<String>,
    val largeDonorIds: List<String>,
)
