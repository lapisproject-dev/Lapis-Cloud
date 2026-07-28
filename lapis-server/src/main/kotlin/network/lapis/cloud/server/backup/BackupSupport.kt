package network.lapis.cloud.server.backup
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BackupOperationLogTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.BackupOperationType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.uuid.Uuid

/** Hard cap applied to [network.lapis.cloud.server.db.generated.BackupOperationLogTable.errorMessage]'s `VARCHAR(2000)` column. */
private const val ERROR_MESSAGE_MAX_LENGTH = 2000

internal fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

/** Lowercase hex encoding, byte-masked (`and 0xFF`) so negative [Byte] values never sign-extend into the string -- same idiom `registerDocumentRoutes`/`archiveGeneratedPdf` already use for their own SHA-256 checksums. */
internal fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * The raw JDBC [Connection] backing this Exposed transaction -- needed because the whole point of
 * [OrganizationSchemaCatalog]/[OrganizationExportService]/[OrganizationRestoreService] is to walk
 * *every* table generically (via `information_schema`/`DatabaseMetaData` and hand-built SQL), not
 * through Exposed's per-domain typed `Table` DSL, which by construction only knows about the
 * tables someone has hand-declared a Kotlin object for.
 */
internal fun JdbcTransaction.rawConnection(): Connection {
    @Suppress("UNCHECKED_CAST")
    return connection.connection as Connection
}

/**
 * Writes exactly one [BackupOperationLogTable] row -- the ONLY place any row is ever inserted into
 * that table. Always called from its own short, dedicated `transaction(database) {}` block by
 * [OrganizationExportService]/[OrganizationRestoreService] -- deliberately never nested inside the
 * long-lived, table-by-table export/restore transactions themselves (see those classes' KDoc "one
 * open transaction per table, not one giant transaction" pitfall note), so one row is written
 * whether the operation succeeded or failed.
 */
internal fun recordBackupOperation(
    database: Database,
    type: BackupOperationType,
    status: BackupOperationStatus,
    actor: CurrentMember,
    startedAt: LocalDateTime,
    finishedAt: LocalDateTime,
    bundleFormatVersion: Int,
    tableCount: Int,
    totalRowCount: Long,
    blobCount: Int,
    blobBytesTotal: Long,
    bundleSizeBytes: Long,
    errorMessage: String?,
) {
    transaction(database) {
        BackupOperationLogTable.insert {
            it[id] = Uuid.random()
            it[operationType] = type
            it[BackupOperationLogTable.status] = status
            it[BackupOperationLogTable.startedAt] = startedAt
            it[BackupOperationLogTable.finishedAt] = finishedAt
            it[BackupOperationLogTable.bundleFormatVersion] = bundleFormatVersion
            it[BackupOperationLogTable.tableCount] = tableCount
            it[BackupOperationLogTable.totalRowCount] = totalRowCount
            it[BackupOperationLogTable.blobCount] = blobCount
            it[BackupOperationLogTable.blobBytesTotal] = blobBytesTotal
            it[BackupOperationLogTable.bundleSizeBytes] = bundleSizeBytes
            // Never the exception's full stack trace / cause chain -- a short, human-readable
            // message only, truncated defensively even though VARCHAR(2000) would also reject an
            // overlong value outright (belt-and-suspenders, see BackupRoutes' Review-Pflicht note
            // on never logging bundle *content*, only metadata, here and everywhere else).
            it[BackupOperationLogTable.errorMessage] = errorMessage?.take(ERROR_MESSAGE_MAX_LENGTH)
            it[actorMemberId] = actor.memberId
        }
    }
}
