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
