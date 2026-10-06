package network.lapis.cloud.server.backup

import kotlinx.serialization.Serializable

/** Path of the manifest entry within the export ZIP -- see [OrganizationExportService]/[OrganizationRestoreService]. */
internal const val MANIFEST_ENTRY_NAME = "manifest.json"

/** ZIP entry name prefix under which document blob bytes are stored -- see [OrganizationExportService]/[OrganizationRestoreService]. */
internal const val BLOB_ENTRY_PREFIX = "blobs/"

/** ZIP entry name prefix under which per-table JSONL row data is stored -- see [OrganizationExportService]/[OrganizationRestoreService]. */
internal const val DATA_ENTRY_PREFIX = "data/"

/**
 * One table's manifest entry -- [columns] pins the exact column-name set/order the bundle was
 * written with (checked against the target's live schema before [OrganizationRestoreService]
 * writes a single row of this table), [rowCount]/[contentSha256] let a corrupted or truncated
 * `data/<tableName>.jsonl` entry be detected rather than silently, partially applied.
 */
@Serializable
data class TableManifestEntry(
    val tableName: String,
    val columns: List<String>,
    val rowCount: Long,
    val contentSha256: String,
)

/**
 * The `manifest.json` entry of a full-organization export bundle -- written LAST by
 * [OrganizationExportService] (after every table/blob has actually been streamed and the real
 * counts/checksums are known, not a pre-computed estimate), read FIRST by
 * [OrganizationRestoreService] (via [java.util.zip.ZipFile] random access, since restore already
 * has the complete bundle file on disk -- see that class's KDoc for why export and restore
 * deliberately differ here).
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val generatedAt: String,
    val generatedByMemberId: String,
    val schemaChecksum: String,
    val tables: List<TableManifestEntry>,
    val blobCount: Int,
    val blobBytesTotal: Long,
    /** Bundle format 2 (V1.9.65): number and total size of the `assets/` entries (crests, event and article covers). Absent (0) in format 1. */
    val assetCount: Int = 0,
    val assetBytesTotal: Long = 0,
)
