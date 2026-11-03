package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.263 -- level of the free-space indicator for administrators. Derived on the server only
 * (the client never recomputes thresholds). [UNKNOWN] means the volume could not be measured.
 */
@Serializable
enum class StorageSpaceLevel { INFO, HINT, WARNING, UNKNOWN }

/**
 * V1.9.263 -- free and total bytes of the volume holding the document store. [freeBytes] and
 * [totalBytes] are `null` exactly when [level] is [StorageSpaceLevel.UNKNOWN]. Never carries a
 * path, mount point or host name.
 */
@Serializable
data class StorageSpaceDto(
    val level: StorageSpaceLevel,
    val freeBytes: Long?,
    val totalBytes: Long?,
)
