package network.lapis.cloud.server.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.backup.OrganizationSchemaCatalog.TableMetadata
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.BackupOperationType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Thrown when a restore bundle's `formatVersion`/`schemaChecksum` does not match this server's live schema, or its structure is otherwise unreadable/incomplete. */
class IncompatibleBundleException(
    message: String,
) : RuntimeException(message)

/** Thrown when a restore into a non-fresh target is attempted without explicitly opting in via `allowNonEmptyTarget`. */
class NonEmptyTargetException(
    message: String,
) : RuntimeException(message)

/** Thrown when a bundle's content checksum does not match what was actually restored -- see class KDoc "partial-write tradeoff". */
class RestoreIncompleteException(
    message: String,
) : RuntimeException(message)

/** One row's worth of PK/value bookkeeping the generic upsert needs -- see [OrganizationRestoreService.upsertRow]. */
private const val RESTORE_BATCH_SIZE = 200

/** Fixed sentinel id of the single `organization_settings` row Flyway seeds -- see `V1__baseline.sql`. */
private val ORGANIZATION_SETTINGS_SEED_ID = "00000000-0000-0000-0000-0000000000f2"

/** Fixed sentinel id of the single `audit_log_chain_state` row Flyway seeds -- see `V1__baseline.sql`/`AuditLogRecorder`. */
private val AUDIT_LOG_CHAIN_STATE_SEED_ID = "00000000-0000-0000-0000-0000000000f3"

/** Fixed sentinel id of the single `crowdfunding_submission_gate` row Flyway seeds -- see `V1__baseline.sql`/`CrowdfundingService`. */
private val CROWDFUNDING_SUBMISSION_GATE_SEED_ID = "00000000-0000-0000-0000-0000000000f4"

/** Fixed sentinel id of the single `price_oracle_config` row Flyway seeds -- see `V1__baseline.sql`/`PriceOracleService`. */
private val PRICE_ORACLE_CONFIG_SEED_ID = "00000000-0000-0000-0000-0000000000f5"

/**
 * Fixed sentinel id of the single `federation_actor_key` row -- UNLIKE every other id in this map,
 * NOT Flyway-seeded (see `24-federation.kuml.kts` file header): `FederationActorKeyProvisioner`
 * inserts it idempotently on first `Application.module()` boot instead, because `actor_uri`
 * depends on `LAPIS_PUBLIC_BASE_URL`, unknown at migration-authoring time. Registered here anyway
 * for the exact same reason every other singleton row is -- otherwise this row would always be
 * non-empty by the time any restore could run (provisioning happens unconditionally at boot,
 * before a restore call could ever execute), permanently blocking restore on every server without
 * `allowNonEmptyTarget=true`.
 */
private val FEDERATION_ACTOR_KEY_SEED_ID = "00000000-0000-0000-0000-0000000000f6"

/**
 * Fixed sentinel id of the single `oidc_signing_key` row -- UNLIKE most other ids in this map (but
 * LIKE `federation_actor_key` above), NOT Flyway-seeded (see `25-oidc-guest-federation.kuml.kts`
 * file header / `OidcSigningKeyProvisioner`): the keypair is generated at first
 * `Application.module()` boot instead. Registered here for the exact same reason
 * `federation_actor_key` is -- otherwise this row would always be non-empty by the time any
 * restore could run, permanently blocking restore on every server without
 * `allowNonEmptyTarget=true`.
 */
private val OIDC_SIGNING_KEY_SEED_ID = "00000000-0000-0000-0000-0000000000f7"

/** Tables that Flyway itself seeds exactly one singleton row into (or, for `federation_actor_key`/`oidc_signing_key`, a row provisioned at first boot instead -- see those entries' own comments) -- see [OrganizationRestoreService.findNonSeedRows]. */
private val SEEDED_SINGLETON_ROWS: Map<String, Pair<String, String>> =
    mapOf(
        "organization_settings" to ("id" to ORGANIZATION_SETTINGS_SEED_ID),
        "audit_log_chain_state" to ("id" to AUDIT_LOG_CHAIN_STATE_SEED_ID),
        "crowdfunding_submission_gate" to ("id" to CROWDFUNDING_SUBMISSION_GATE_SEED_ID),
        "price_oracle_config" to ("id" to PRICE_ORACLE_CONFIG_SEED_ID),
        "federation_actor_key" to ("id" to FEDERATION_ACTOR_KEY_SEED_ID),
        "oidc_signing_key" to ("id" to OIDC_SIGNING_KEY_SEED_ID),
    )

/** Result of a successful [OrganizationRestoreService.restore] call -- a failed restore throws instead of returning, see that method's KDoc. */
data class OrganizationRestoreResult(
    val tablesRestored: List<TableManifestEntry>,
    val blobsRestored: Int,
    val warnings: List<String>,
)

/**
 * Full-organization restore -- the counterpart to [OrganizationExportService], and the mechanism
 * that makes the Sezessionsrecht data-portability guarantee actually actionable (a bundle is only
 * as good as a server's ability to load it back in). See class-level scope notes below for exactly
 * what is (and is not) supported.
 *
 * **Primary supported path, tested end to end**: restoring a bundle produced by
 * [OrganizationExportService] into a freshly Flyway-migrated (i.e. empty beyond the two seeded
 * singleton rows, see [SEEDED_SINGLETON_ROWS]) target instance -- the real secession scenario ("a
 * new operator stands up a fresh server, loads the exported bundle"). Every table and every
 * document blob round-trips.
 *
 * **Also supported**: re-running the *same* restore a second time against a target that already
 * holds the result of the first run, via `allowNonEmptyTarget = true` -- upsert semantics
 * (UPDATE-by-primary-key, INSERT only if that matched zero rows) make this idempotent.
 *
 * **Deliberately NOT attempted in this wave** (stated honestly rather than silently assumed to
 * work):
 * - Restoring into a target that already holds *different* live organizational data (a genuine
 *   cross-org merge) -- the [SEEDED_SINGLETON_ROWS]-aware pre-flight emptiness check exists
 *   precisely to catch and reject this, not to silently corrupt/merge two organizations' data.
 * - Restoring a bundle produced by a different schema/format version -- detected via
 *   [BackupManifest.formatVersion] and [BackupManifest.schemaChecksum] and rejected with
 *   [IncompatibleBundleException] before a single row is touched, never silently/partially applied.
 * - A live two-database migration mode (streaming directly between two servers without an
 *   intermediate bundle file) -- [database] is an explicit constructor parameter (not the ambient
 *   default) specifically so this remains architecturally possible in a later wave, but this wave
 *   only wires the file-mediated HTTP export/import workflow (see `registerBackupRoutes`).
 *
 * **Restore's accepted partial-write tradeoff**: if a table's bundle content is corrupted/truncated
 * in transit, the mismatch is only detectable *after* that table's rows have already been written
 * (recomputing a streaming digest while restoring, mirroring the export side) -- [restore] then
 * throws [RestoreIncompleteException] rather than returning a success result, but the rows already
 * written for that table (and any earlier tables) remain in the target. This is safe under the
 * primary supported path above (a fresh target is cheap to discard/recreate on retry); it is NOT
 * safe to assume for a second, `allowNonEmptyTarget = true` restore of genuinely new data into an
 * already-populated target -- that asymmetry is deliberate, not an oversight.
 */
class OrganizationRestoreService(
    private val database: Database,
    private val documentStorageRoot: File,
) {
    /**
     * Restores [bundleFile] (a complete ZIP bundle already on disk -- callers, e.g.
     * `registerBackupRoutes`, are responsible for bounding/streaming the upload into such a file
     * before calling this; this method itself performs no additional network I/O) into [database].
     *
     * Returns a successful [OrganizationRestoreResult], or throws [IncompatibleBundleException] /
     * [NonEmptyTargetException] / [RestoreIncompleteException] -- there is no "successful-but-
     * failed" return value, matching this codebase's existing throw-on-failure house style
     * (`NotFoundException`/`ForbiddenException`/`ConflictException` etc.). Exactly one
     * `BackupOperationLogTable` row (SUCCEEDED/FAILED) is written in its own short transaction
     * regardless of outcome.
     */
    fun restore(
        actor: CurrentMember,
        bundleFile: File,
        allowNonEmptyTarget: Boolean = false,
    ): OrganizationRestoreResult {
        val startedAt = nowLocalDateTime()
        var status = BackupOperationStatus.FAILED
        var errorMessage: String? = null
        var tablesRestored = emptyList<TableManifestEntry>()
        var blobsRestored = 0
        var blobBytesRestored = 0L
        var totalRowCount = 0L
        val warnings = mutableListOf<String>()
        try {
            ZipFile(bundleFile).use { zip ->
                val manifest = readManifest(zip)
                if (manifest.formatVersion != OrganizationExportService.FORMAT_VERSION) {
                    throw IncompatibleBundleException(
                        "Bundle formatVersion ${manifest.formatVersion} is incompatible with this server's " +
                            "expected formatVersion ${OrganizationExportService.FORMAT_VERSION}",
                    )
                }

                val liveTables = transaction(database) { OrganizationSchemaCatalog.exportableTables(this) }
                val liveChecksum = OrganizationSchemaCatalog.schemaChecksum(liveTables)
                if (manifest.schemaChecksum != liveChecksum) {
                    throw IncompatibleBundleException(
                        "Bundle schemaChecksum ${manifest.schemaChecksum} does not match this server's live " +
                            "schema checksum $liveChecksum -- the bundle was produced by a different schema " +
                            "version and cannot be safely restored",
                    )
                }

                if (!allowNonEmptyTarget) {
                    val nonSeedRows = transaction(database) { findNonSeedRows(this, liveTables) }
                    if (nonSeedRows.isNotEmpty()) {
                        throw NonEmptyTargetException(
                            "Target database already holds data beyond the Flyway-seeded singleton rows " +
                                "(${nonSeedRows.joinToString(", ")}) -- pass allowNonEmptyTarget=true to " +
                                "restore into a non-fresh instance anyway (e.g. to idempotently re-run a " +
                                "previous restore)",
                        )
                    }
                }

                val liveByName = liveTables.associateBy { it.tableName }
                val manifestByName = manifest.tables.associateBy { it.tableName }
                val unknownTables = manifestByName.keys - liveByName.keys
                if (unknownTables.isNotEmpty()) {
                    warnings += "Bundle contains tables not present in this server's live schema (ignored): ${unknownTables.sorted()}"
                }
                val orderedTables = OrganizationSchemaCatalog.restoreOrder(manifest.tables.mapNotNull { liveByName[it.tableName] })

                val restored = mutableListOf<TableManifestEntry>()
                for (tableMeta in orderedTables) {
                    val manifestEntry = manifestByName.getValue(tableMeta.tableName)
                    if (manifestEntry.columns.toSet() != tableMeta.columns.map { it.name }.toSet()) {
                        throw IncompatibleBundleException(
                            "Bundle's column set for table '${tableMeta.tableName}' (${manifestEntry.columns}) " +
                                "does not match this server's live schema (${tableMeta.columns.map { it.name }})",
                        )
                    }
                    val zipEntry =
                        zip.getEntry(DATA_ENTRY_PREFIX + tableMeta.tableName + ".jsonl")
                            ?: throw IncompatibleBundleException("Bundle is missing data file for table '${tableMeta.tableName}'")
                    val rowCount = restoreTable(zip, zipEntry, tableMeta, manifestEntry)
                    totalRowCount += rowCount
                    restored += manifestEntry.copy(rowCount = rowCount)
                }
                tablesRestored = restored

                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith(BLOB_ENTRY_PREFIX) }
                    .forEach { blobEntry ->
                        val relativeKey = blobEntry.name.removePrefix(BLOB_ENTRY_PREFIX)
                        val targetFile = resolveSafeBlobPath(relativeKey)
                        if (targetFile == null) {
                            warnings += "Skipped blob entry with unsafe path: ${blobEntry.name}"
                        } else {
                            targetFile.parentFile.mkdirs()
                            zip.getInputStream(blobEntry).use { input -> targetFile.outputStream().use { output -> input.copyTo(output) } }
                            blobsRestored++
                            blobBytesRestored += targetFile.length()
                        }
                    }
                if (blobsRestored < manifest.blobCount) {
                    warnings += "Manifest declared ${manifest.blobCount} blob(s) but only $blobsRestored were present/restorable"
                }
            }
            status = BackupOperationStatus.SUCCEEDED
        } catch (e: IncompatibleBundleException) {
            errorMessage = e.message
            throw e
        } catch (e: NonEmptyTargetException) {
            errorMessage = e.message
            throw e
        } catch (e: RestoreIncompleteException) {
            errorMessage = e.message
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: e::class.simpleName
            throw RestoreIncompleteException("Restore failed: $errorMessage").also { it.initCause(e) }
        } finally {
            try {
                recordBackupOperation(
                    database = database,
                    type = BackupOperationType.RESTORE,
                    status = status,
                    actor = actor,
                    startedAt = startedAt,
                    finishedAt = nowLocalDateTime(),
                    bundleFormatVersion = OrganizationExportService.FORMAT_VERSION,
                    tableCount = tablesRestored.size,
                    totalRowCount = totalRowCount,
                    blobCount = blobsRestored,
                    blobBytesTotal = blobBytesRestored,
                    bundleSizeBytes = bundleFile.length(),
                    errorMessage = errorMessage,
                )
            } catch (bookkeepingFailure: Exception) {
                // A pathological bundle can fail so early/badly that the actor's own `member` row
                // itself never made it into the target (e.g. the `member` table's restore was the
                // one that failed its content-checksum check) -- this bookkeeping INSERT then hits
                // its own `fk_backup_operation_log_actor_member_id` FK violation. Never let that
                // secondary failure mask/replace a primary error already propagating (errorMessage
                // != null): the operation simply goes unrecorded in backup_operation_log rather than
                // surfacing a confusing FK-violation stack trace instead of the real cause. If there
                // was NO primary error (a genuinely successful restore whose bookkeeping write then
                // somehow still failed), surface this failure -- silently claiming success while
                // failing to log it would be worse.
                if (errorMessage == null) throw bookkeepingFailure
            }
        }
        return OrganizationRestoreResult(tablesRestored, blobsRestored, warnings)
    }

    private fun readManifest(zip: ZipFile): BackupManifest {
        val entry = zip.getEntry(MANIFEST_ENTRY_NAME) ?: throw IncompatibleBundleException("Bundle is missing $MANIFEST_ENTRY_NAME")
        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return try {
            Json.decodeFromString(BackupManifest.serializer(), text)
        } catch (e: Exception) {
            throw IncompatibleBundleException("Bundle's $MANIFEST_ENTRY_NAME is not valid JSON: ${e.message}")
        }
    }

    /**
     * Streams [entry]'s JSONL lines in batches of [RESTORE_BATCH_SIZE], each batch upserted inside
     * its own short `transaction(database) {}` (never one transaction for the whole table, let
     * alone the whole restore -- bounded transaction length even for a very large table). Recomputes
     * a running SHA-256 over the exact bytes read and compares it against [manifestEntry]'s
     * `contentSha256` once the entry is exhausted -- see class KDoc "accepted partial-write
     * tradeoff" for what a mismatch means.
     */
    private fun restoreTable(
        zip: ZipFile,
        entry: ZipEntry,
        table: TableMetadata,
        manifestEntry: TableManifestEntry,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        var rowCount = 0L
        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.chunked(RESTORE_BATCH_SIZE).forEach { batch ->
                transaction(database) {
                    val connection = rawConnection()
                    batch.forEach { line ->
                        digest.update(line.toByteArray(Charsets.UTF_8))
                        digest.update(NEWLINE_BYTE)
                        val row = Json.parseToJsonElement(line).jsonObject
                        upsertRow(connection, table, row)
                        rowCount++
                    }
                }
            }
        }
        val actualDigest = digest.digest().toHexLower()
        if (actualDigest != manifestEntry.contentSha256) {
            throw RestoreIncompleteException(
                "Content checksum mismatch for table '${table.tableName}' after restoring $rowCount row(s) -- " +
                    "the bundle's data for this table was corrupted or truncated in transit. The target " +
                    "instance now holds a partial restore of this table and should be discarded/recreated " +
                    "rather than trusted (see OrganizationRestoreService KDoc's documented partial-write " +
                    "tradeoff) -- expected $actualDigest, manifest declared ${manifestEntry.contentSha256}",
            )
        }
        return rowCount
    }

    /** UPDATE-by-primary-key first, INSERT only if that matched zero rows -- a portable upsert with no `ON CONFLICT` dialect dependency, working identically on H2 and Postgres. */
    private fun upsertRow(
        connection: Connection,
        table: TableMetadata,
        row: JsonObject,
    ) {
        val pkColumns = table.columns.filter { it.name in table.primaryKeyColumns }
        val nonPkColumns = table.columns.filter { it.name !in table.primaryKeyColumns }

        if (nonPkColumns.isNotEmpty()) {
            val setClause = nonPkColumns.joinToString(", ") { "\"${it.name}\" = ?" }
            val whereClause = table.primaryKeyColumns.joinToString(" AND ") { "\"$it\" = ?" }
            val updated =
                connection.prepareStatement("UPDATE \"${table.tableName}\" SET $setClause WHERE $whereClause").use { ps ->
                    JdbcRowCodec.bindRow(ps, row, nonPkColumns, startIndex = 1)
                    JdbcRowCodec.bindRow(ps, row, pkColumns, startIndex = nonPkColumns.size + 1)
                    ps.executeUpdate()
                }
            if (updated > 0) return
        }

        val columnList = table.columns.joinToString(", ") { "\"${it.name}\"" }
        val placeholders = table.columns.joinToString(", ") { "?" }
        connection.prepareStatement("INSERT INTO \"${table.tableName}\" ($columnList) VALUES ($placeholders)").use { ps ->
            JdbcRowCodec.bindRow(ps, row, table.columns, startIndex = 1)
            try {
                ps.executeUpdate()
            } catch (e: java.sql.SQLException) {
                // Only reachable for an all-primary-key table (no nonPkColumns branch to UPDATE
                // into first) whose row is already present -- treat as an idempotent no-op, same
                // spirit as the UPDATE-then-INSERT-if-absent path above. No table in the current
                // schema is all-primary-key (see V0.5.4 implementation notes), but this keeps the
                // upsert generic rather than silently wrong if a future domain ever adds one.
                if (nonPkColumns.isNotEmpty()) throw e
            }
        }
    }

    /**
     * Every live table's row count, excluding the exact Flyway-seeded singleton row where
     * [SEEDED_SINGLETON_ROWS] documents one exists for that table -- the pre-flight "is this
     * target actually fresh" check `restore` runs unless `allowNonEmptyTarget` is passed.
     */
    private fun findNonSeedRows(
        tx: JdbcTransaction,
        tables: List<TableMetadata>,
    ): List<String> {
        val findings = mutableListOf<String>()
        val connection = tx.rawConnection()
        for (table in tables) {
            val seed = SEEDED_SINGLETON_ROWS[table.tableName]
            val sql =
                if (seed != null) {
                    "SELECT COUNT(*) FROM \"${table.tableName}\" WHERE \"${seed.first}\" <> ?"
                } else {
                    "SELECT COUNT(*) FROM \"${table.tableName}\""
                }
            val count =
                connection.prepareStatement(sql).use { ps ->
                    if (seed != null) ps.setObject(1, UUID.fromString(seed.second))
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            if (count > 0) findings += "${table.tableName} ($count row(s))"
        }
        return findings
    }

    private fun resolveSafeBlobPath(relativeKey: String): File? {
        if (relativeKey.isBlank() || File(relativeKey).isAbsolute) return null
        val candidate = documentStorageRoot.resolve(relativeKey).canonicalFile
        val root = documentStorageRoot.canonicalFile
        return if (candidate == root || candidate.path.startsWith(root.path + File.separatorChar)) candidate else null
    }
}

private val NEWLINE_BYTE = "\n".toByteArray(Charsets.UTF_8)
