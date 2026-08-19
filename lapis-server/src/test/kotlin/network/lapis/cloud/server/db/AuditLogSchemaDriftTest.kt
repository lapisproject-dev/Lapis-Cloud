package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AuditLogChainStateTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — GoBD audit-log domain (V0.5.3 Revisionssicherheit).
 *
 * Verifies that `lapis-server/src/main/kuml/14-audit-log.kuml.kts` is a faithful model of both (a)
 * the real, Flyway-migrated H2 schema (`audit_log_chain_state`/`audit_log_entry`), and (b) the
 * hand-written [AuditLogChainStateTable]/[AuditLogEntryTable] Exposed objects. Mirrors
 * [TransparenzregisterSchemaDriftTest]'s shape -- see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class AuditLogSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "14-audit-log.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly audit_log_chain_state, audit_log_entry and the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "audit_log_chain_state", "audit_log_entry")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("audit_log_chain_state table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "audit_log_chain_state" }
            val real = transaction { introspectAuditLogTable("audit_log_chain_state") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
        }

        test("audit_log_entry table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "audit_log_entry" }
            val real = transaction { introspectAuditLogTable("audit_log_entry") }

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

        test("audit_log_entry.entity_id has NO foreign key -- deliberately polymorphic, see file header") {
            val real = transaction { introspectAuditLogTable("audit_log_entry") }
            real.foreignKeys.containsKey("entity_id") shouldBe false
        }

        test("audit_log_entry.sequence_number's UNIQUE constraint is pinned via a class-level «Index»") {
            val entity = model.entities.single { it.name == "audit_log_entry" }
            val real = transaction { introspectAuditLogTable("audit_log_entry") }

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("sequence_number"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_audit_log_entry_sequence" }.let {
                it.unique shouldBe true
                it.attributeIds shouldBe listOf(entity.attributeByName("sequence_number")!!.id)
            }
        }

        test("audit_log_entry.previous_entry_hash is nullable -- NULL only for the very first (genesis) row") {
            val entity = model.entities.single { it.name == "audit_log_entry" }
            entity.attributeByName("previous_entry_hash")?.nullable shouldBe true

            val real = transaction { introspectAuditLogTable("audit_log_entry") }
            real.columns.getValue("previous_entry_hash").nullable shouldBe true
        }

        test("audit_log_entry.entry_hash/sequence_number/entity_type/entity_id/action are NOT NULL") {
            val entity = model.entities.single { it.name == "audit_log_entry" }
            listOf("entry_hash", "sequence_number", "entity_type", "entity_id", "action").forEach { name ->
                withClue(clue = "attribute '$name'") {
                    entity.attributeByName(name)?.nullable shouldBe false
                }
            }
        }

        // ── (2) Model vs. hand-written Exposed Table objects ─────────────────────

        test("audit_log_chain_state entity column-name set matches the hand-written AuditLogChainStateTable 1:1") {
            model.entities
                .single { it.name == "audit_log_chain_state" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AuditLogChainStateTable.columns.map { it.name }
        }

        test("audit_log_entry entity column-name set matches the hand-written AuditLogEntryTable 1:1") {
            model.entities
                .single { it.name == "audit_log_entry" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AuditLogEntryTable.columns.map { it.name }
        }

        test("audit_log_entry.actor_role/entity_type/action are modelled as real ErmDataType.Enum columns") {
            val entity = model.entities.single { it.name == "audit_log_entry" }

            entity.attributeByName("actor_role")?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountRole",
                    values = listOf("MEMBER", "BOARD", "TREASURER", "ADMIN"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountRole",
                )
            entity.attributeByName("entity_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "AuditEntityType",
                    values =
                        listOf(
                            "JOURNAL_ENTRY",
                            "PARTY_DONATION_VERDICT",
                            "RESOLUTION",
                            "BOARD_MEMBERSHIP",
                            "CONFERENCE_RECORDING",
                            "CONFERENCE_STREAM",
                            "CONFERENCE_STREAM_DESTINATION",
                            "CONFERENCE_ROOM",
                            "SOCIAL_POST",
                            "ORGANIZATION_SETTINGS",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.AuditEntityType",
                )
            entity.attributeByName("action")?.type shouldBe
                ErmDataType.Enum(
                    name = "AuditAction",
                    values = listOf("CREATE", "UPDATE", "POST"),
                    externalFqName = "network.lapis.cloud.shared.domain.AuditAction",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including single-column uniques. */
private data class IntrospectedAuditLogTable(
    val columns: Map<String, IntrospectedAuditLogColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one UNIQUE constraint (1+ columns). */
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedAuditLogColumn(
    val nullable: Boolean,
)

/** ANSI `information_schema` walk for a single table's columns, nullability, FK targets and UNIQUE constraints. Mirrors [AccountingSchemaDriftTest]'s own (private, domain-scoped) `introspectAccountingTable`. */
private fun JdbcTransaction.introspectAuditLogTable(tableName: String): IntrospectedAuditLogTable {
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

    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name AS name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedAuditLogColumn(nullable = nullable)
        }
    return IntrospectedAuditLogTable(
        columns = columns,
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors SchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
