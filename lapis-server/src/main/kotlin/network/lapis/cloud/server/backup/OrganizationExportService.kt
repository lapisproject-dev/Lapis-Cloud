package network.lapis.cloud.server.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import network.lapis.cloud.server.backup.OrganizationSchemaCatalog.TableMetadata
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.BackupOperationType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** JDBC `Statement.setFetchSize` hint -- see class KDoc "streaming" note for the load-bearing property this actually rests on. */
private const val JDBC_FETCH_SIZE = 500

/** `document_version` is the one table whose rows also trigger a filesystem blob copy -- see class KDoc. */
private const val DOCUMENT_VERSION_TABLE = "document_version"
private const val STORAGE_KEY_COLUMN = "storage_key"

/**
 * Full-organization, structured, streamed export -- the Sezessionsrecht data-portability mechanism
 * (see `CLAUDE.md`/roadmap "Backup-, Restore- und Datenexport-Garantie"). Produces one ZIP bundle:
 * `manifest.json` (written LAST, see KDoc below) + `data/<table>.jsonl` (one line per row, one
 * entry per table [OrganizationSchemaCatalog.exportableTables] returns) + `blobs/<storageKey>`
 * (the `document_version` rows' file bytes, copied verbatim from [documentStorageRoot]).
 *
 * **Scope, deliberately**: every table in the live schema except `flyway_schema_history` (see
 * [OrganizationSchemaCatalog.EXCLUDED_TABLES]) -- including `account.password_hash`/`oidc_subject`
 * and `dsgvo_audit_log`, unlike the per-member DSGVO export
 * (`network.lapis.cloud.server.dsgvo.PersonalDataRegistry`), which is scoped to one data subject's
 * *personal* data and deliberately never touches `dsgvo_audit_log`. **Review-Pflicht**: this makes
 * the resulting bundle as sensitive as a full database dump -- handle/store it accordingly. This
 * wave's security boundary is HTTPS transport (see `registerBackupRoutes`) + ADMIN-only access;
 * there is deliberately no at-rest encryption of the bundle file itself here, left to the operator
 * or a later wave, not silently assumed.
 *
 * **V0.8.1 addition**: the bundle now also carries `federation_actor_key.private_key_pem` -- this
 * server's own ActivityPub Actor private key, deliberately INCLUDED (not added to
 * [OrganizationSchemaCatalog.EXCLUDED_TABLES]), same sensitivity tier as `account.password_hash`
 * above. This restore mechanism exists for genuine organization secession/migration to a new
 * server (see class KDoc "Sezessionsrecht"), and a migrating organization should keep its
 * federation identity -- same actor URI, same keypair, same established relationships -- exactly
 * as it keeps its ledger/governance history.
 *
 * **Streaming / DoS**: no table's rows and no table's full JSONL text are ever collected into a
 * `List`/`String` in memory -- each row is read from the JDBC [java.sql.ResultSet], immediately
 * JSON-encoded, and immediately written to the ZIP output stream, one row at a time. [JDBC_FETCH_SIZE]
 * is a best-effort hint to the driver (H2's default driver may buffer regardless; a real Postgres
 * deployment honours it more meaningfully) -- but the load-bearing property that actually bounds
 * JVM heap regardless of driver-level buffering is this row-at-a-time processing loop itself, not
 * the fetch-size hint.
 *
 * **Consistency model**: each table's read runs inside its own short `transaction(database) {}`
 * block, not one giant transaction spanning the whole export (which would hold a DB connection for
 * as long as the slowest HTTP client takes to drain the response). This is a deliberate,
 * accepted tradeoff -- the export is "point-in-time-ish, not a single serializable snapshot": a
 * write to a table already dumped is invisible to the bundle, and a write to a not-yet-dumped table
 * that happens concurrently may or may not be visible depending on exact timing. A future wave
 * could upgrade to `SERIALIZABLE`/`REPEATABLE READ` isolation for a true snapshot if ever needed;
 * out of scope here.
 *
 * **Manifest ordering**: `manifest.json` is written LAST, once every table's true row count and
 * content checksum is known (never a pre-computed estimate) -- see [OrganizationRestoreService]
 * KDoc for why restore reads it first anyway (via random ZIP access on the already-complete file).
 *
 * [database] is an explicit constructor parameter, not the ambient `DatabaseConfig.connect()`
 * default -- this is what lets [OrganizationBackupRoundTripTest] (test-only) run two independently
 * migrated databases in one JVM, and keeps the door open for a future live instance-to-instance
 * migration mode without an intermediate file.
 */
class OrganizationExportService(
    private val database: Database,
    private val documentStorageRoot: File,
) {
    companion object {
        /** The bundle format this service writes and [OrganizationRestoreService] validates against -- bump on any incompatible bundle-shape change. */
        const val FORMAT_VERSION = 1
    }

    /**
     * Streams the full-organization ZIP bundle directly into [sink] -- never materializes the whole
     * bundle in memory (see class KDoc). Writes exactly one
     * `network.lapis.cloud.server.db.generated.BackupOperationLogTable` row (SUCCEEDED/FAILED) in
     * its own short transaction after the stream completes or fails, regardless of outcome.
     */
    fun streamExport(
        actor: CurrentMember,
        sink: OutputStream,
    ) {
        val startedAt = nowLocalDateTime()
        var status = BackupOperationStatus.FAILED
        var errorMessage: String? = null
        var tableCount = 0
        var totalRowCount = 0L
        var blobCount = 0
        var blobBytesTotal = 0L
        val countingSink = CountingOutputStream(sink)
        try {
            ZipOutputStream(countingSink).use { zip ->
                val tables = transaction(database) { OrganizationSchemaCatalog.exportableTables(this) }
                val schemaChecksum = OrganizationSchemaCatalog.schemaChecksum(tables)
                tableCount = tables.size

                val tableEntries = mutableListOf<TableManifestEntry>()
                val blobStorageKeys = mutableListOf<String>()

                for (table in tables) {
                    val (rowCount, contentSha256) = streamTable(zip = zip, table = table, blobStorageKeys = blobStorageKeys)
                    totalRowCount += rowCount
                    tableEntries +=
                        TableManifestEntry(
                            tableName = table.tableName,
                            columns = table.columns.map { it.name },
                            rowCount = rowCount,
                            contentSha256 = contentSha256,
                        )
                }

                blobStorageKeys.forEach { storageKey ->
                    val file = resolveBlobFile(storageKey)
                    if (file != null && file.isFile) {
                        zip.putNextEntry(ZipEntry(BLOB_ENTRY_PREFIX + storageKey))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        blobCount++
                        blobBytesTotal += file.length()
                    }
                    // else: a document_version row references a file that is not present on this
                    // server's storageRoot (e.g. dev/demo data seeded without ever uploading real
                    // bytes) -- skipped rather than failing the whole export; the row's metadata is
                    // still exported faithfully via the document_version table entry itself.
                }

                val manifest =
                    BackupManifest(
                        formatVersion = FORMAT_VERSION,
                        generatedAt = startedAt.toString(),
                        generatedByMemberId = actor.memberId.toString(),
                        schemaChecksum = schemaChecksum,
                        tables = tableEntries,
                        blobCount = blobCount,
                        blobBytesTotal = blobBytesTotal,
                    )
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                zip.write(Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            status = BackupOperationStatus.SUCCEEDED
        } catch (e: Exception) {
            errorMessage = e.message ?: e::class.simpleName
            throw e
        } finally {
            try {
                recordBackupOperation(
                    database = database,
                    type = BackupOperationType.EXPORT,
                    status = status,
                    actor = actor,
                    startedAt = startedAt,
                    finishedAt = nowLocalDateTime(),
                    bundleFormatVersion = FORMAT_VERSION,
                    tableCount = tableCount,
                    totalRowCount = totalRowCount,
                    blobCount = blobCount,
                    blobBytesTotal = blobBytesTotal,
                    bundleSizeBytes = countingSink.count,
                    errorMessage = errorMessage,
                )
            } catch (bookkeepingFailure: Exception) {
                // Same defensive guard as OrganizationRestoreService.restore's finally block --
                // never let a secondary failure to write the bookkeeping row itself mask a primary
                // export failure already propagating.
                if (errorMessage == null) throw bookkeepingFailure
            }
        }
    }

    /** Streams one table's rows as a `data/<table>.jsonl` ZIP entry; returns (rowCount, contentSha256 over the exact bytes written). */
    private fun streamTable(
        zip: ZipOutputStream,
        table: TableMetadata,
        blobStorageKeys: MutableList<String>,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var rowCount = 0L
        zip.putNextEntry(ZipEntry(DATA_ENTRY_PREFIX + table.tableName + ".jsonl"))
        transaction(database) {
            val connection = rawConnection()
            connection.prepareStatement(selectAllSql(table)).use { statement ->
                statement.fetchSize = JDBC_FETCH_SIZE
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val row = JdbcRowCodec.rowToJson(rs = rs, columns = table.columns)
                        if (table.tableName == DOCUMENT_VERSION_TABLE) {
                            (row[STORAGE_KEY_COLUMN] as? JsonPrimitive)?.contentOrNull?.let { blobStorageKeys += it }
                        }
                        val line = Json.encodeToString(JsonObject.serializer(), row)
                        val bytes = line.toByteArray(Charsets.UTF_8)
                        zip.write(bytes)
                        zip.write(NEWLINE)
                        digest.update(bytes)
                        digest.update(NEWLINE)
                        rowCount++
                    }
                }
            }
        }
        zip.closeEntry()
        return rowCount to digest.digest().toHexLower()
    }

    private fun selectAllSql(table: TableMetadata): String {
        // Table/column names come exclusively from information_schema enumeration
        // (OrganizationSchemaCatalog), never from user input -- no injection surface -- quoting is
        // defensive hygiene, not a security boundary being relied upon here.
        val columns = table.columns.joinToString(", ") { "\"${it.name}\"" }
        val orderBy = table.primaryKeyColumns.joinToString(", ") { "\"$it\"" }
        return "SELECT $columns FROM \"${table.tableName}\" ORDER BY $orderBy"
    }

    /** Zip-Slip-safe resolution of a `document_version.storage_key` value against [documentStorageRoot] -- storageKey values here are always server-generated (see `registerDocumentRoutes` KDoc), but this guard is defense in depth against a hand-edited/corrupted row. */
    private fun resolveBlobFile(storageKey: String): File? {
        if (storageKey.isBlank() || File(storageKey).isAbsolute) return null
        val candidate = documentStorageRoot.resolve(storageKey).canonicalFile
        val root = documentStorageRoot.canonicalFile
        return if (candidate == root || candidate.path.startsWith(root.path + File.separatorChar)) candidate else null
    }
}

private val NEWLINE = "\n".toByteArray(Charsets.UTF_8)

/** Counts every byte written to [delegate] without buffering any of it -- used to report `bundleSizeBytes` without a second pass over the data. */
private class CountingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
