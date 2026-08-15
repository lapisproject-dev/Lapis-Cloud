package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.BackupOperationLogTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — backup-export domain (V0.5.4 Backup-/Restore-/
 * Datenexport-Garantie).
 *
 * Verifies that `lapis-server/src/main/kuml/15-backup-export.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`backup_operation_log`), and (b) the hand-written
 * [BackupOperationLogTable] Exposed object. Mirrors [PostalMailSchemaDriftTest] (which also has a
 * cross-domain Member stub) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B
 * rationale.
 */
class BackupSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "15-backup-export.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the backup_operation_log entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "backup_operation_log")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("backup_operation_log table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "backup_operation_log" }
            val real = transaction { introspectBackupOperationLogTable("backup_operation_log") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["actor_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("actor_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table object ─────────────────────

        test("backup_operation_log entity column-name set matches the hand-written BackupOperationLogTable 1:1") {
            model.entities
                .single { it.name == "backup_operation_log" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder BackupOperationLogTable.columns.map { it.name }
        }

        test("backup_operation_log.operation_type is modelled as a real ErmDataType.Enum column") {
            val attr = model.entities.single { it.name == "backup_operation_log" }.attributeByName("operation_type")
            attr?.type shouldBe
                ErmDataType.Enum(
                    name = "BackupOperationType",
                    values = listOf("EXPORT", "RESTORE"),
                    externalFqName = "network.lapis.cloud.shared.domain.BackupOperationType",
                )
        }

        test("backup_operation_log.status is modelled as a real ErmDataType.Enum column") {
            val attr = model.entities.single { it.name == "backup_operation_log" }.attributeByName("status")
            attr?.type shouldBe
                ErmDataType.Enum(
                    name = "BackupOperationStatus",
                    values = listOf("SUCCEEDED", "FAILED"),
                    externalFqName = "network.lapis.cloud.shared.domain.BackupOperationStatus",
                )
        }
    })

/** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [ErmModel] -- mirrors `PostalMailSchemaDriftTest`'s own. */
private fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedBackupOperationLogTable(
    val columns: Map<String, IntrospectedBackupOperationLogColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedBackupOperationLogColumn(
    val nullable: Boolean,
)

/** ANSI `information_schema` walk for a single table's columns, nullability and FK targets. Mirrors [PostalMailSchemaDriftTest]'s own (private, postal-mail-domain-scoped) `introspectPostalMailTable`. */
private fun JdbcTransaction.introspectBackupOperationLogTable(tableName: String): IntrospectedBackupOperationLogTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedBackupOperationLogColumn(nullable = nullable)
        }
    return IntrospectedBackupOperationLogTable(columns = columns, foreignKeys = fkByColumn)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors `PostalMailSchemaDriftTest`'s). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
