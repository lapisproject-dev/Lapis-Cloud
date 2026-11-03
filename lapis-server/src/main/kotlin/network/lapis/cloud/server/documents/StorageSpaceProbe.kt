package network.lapis.cloud.server.documents

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * V1.9.263 -- the single place in the server that measures free/total space of a volume. One
 * statvfs pair per call, no tree walk, no cache. `RecordingDiskBudget` and its callers use the same
 * [usable] probe, guarded by `StorageSpaceTripwireTest`.
 *
 * `File.usableSpace` returns 0 for a missing path and on I/O errors, so existence is checked first.
 * A remaining blind spot: an I/O error on an existing directory may yield `free = 0` with
 * `total > 0` and thus a false WARNING; that cannot be told apart.
 */
object StorageSpaceProbe {
    const val BYTES_PER_GB = 1_000_000_000L
    const val HINT_PERCENT = 20L
    const val HINT_BYTES = 10 * BYTES_PER_GB
    const val WARNING_PERCENT = 5L
    const val WARNING_BYTES = 2 * BYTES_PER_GB

    val usable: (File) -> Long = { it.usableSpace }
    val total: (File) -> Long = { it.totalSpace }

    private val unknown = StorageSpaceDto(level = StorageSpaceLevel.UNKNOWN, freeBytes = null, totalBytes = null)

    /** Pure, Long arithmetic only. Overflow of `free * 100` / `total * p` yields UNKNOWN. Exact boundaries belong to the milder level. */
    fun level(
        free: Long,
        total: Long,
    ): StorageSpaceLevel {
        if (total <= 0 || free < 0 || free > total) return StorageSpaceLevel.UNKNOWN
        return try {
            val freeScaled = Math.multiplyExact(free, 100L)
            when {
                freeScaled < Math.multiplyExact(total, WARNING_PERCENT) || free < WARNING_BYTES -> StorageSpaceLevel.WARNING
                freeScaled < Math.multiplyExact(total, HINT_PERCENT) || free < HINT_BYTES -> StorageSpaceLevel.HINT
                else -> StorageSpaceLevel.INFO
            }
        } catch (_: ArithmeticException) {
            StorageSpaceLevel.UNKNOWN
        }
    }

    fun measure(
        root: File?,
        usable: (File) -> Long = this.usable,
        total: (File) -> Long = this.total,
    ): StorageSpaceDto =
        try {
            if (root == null || !root.exists() || !root.isDirectory) {
                unknown
            } else {
                val free = usable(root)
                val totalBytes = total(root)
                val level = level(free = free, total = totalBytes)
                if (level == StorageSpaceLevel.UNKNOWN) {
                    unknown
                } else {
                    StorageSpaceDto(level = level, freeBytes = free, totalBytes = totalBytes)
                }
            }
        } catch (e: Exception) {
            logger.warn { "storage space probe failed: ${e::class.simpleName}" }
            unknown
        }
}
