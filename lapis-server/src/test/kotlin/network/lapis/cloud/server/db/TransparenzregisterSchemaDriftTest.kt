package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Transparenzregister domain (V0.5.2 §20 GwG beneficial-owner
 * reminders).
 *
 * Verifies that `lapis-server/src/main/kuml/13-transparenzregister.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`board_membership`/`transparenzregister_reminder`),
 * and (b) the hand-written [BoardMembershipTable]/[TransparenzregisterReminderTable] Exposed
 * objects. Mirrors [PostalMailSchemaDriftTest]'s shape -- see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class TransparenzregisterSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "13-transparenzregister.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly board_membership, transparenzregister_reminder and the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "board_membership", "transparenzregister_reminder")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("board_membership table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "board_membership" }
            val real = transaction { introspectTransparenzregisterTable("board_membership") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("transparenzregister_reminder table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "transparenzregister_reminder" }
            val real = transaction { introspectTransparenzregisterTable("transparenzregister_reminder") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // Two FKs to member -- member_id (the reminder's subject, NOT NULL) and resolved_by
            // (whoever acknowledged it, nullable). member_id is the real UML association claiming
            // the bare association-derived default; resolved_by is a plain «Column» attribute with
            // «Column».fkEntity -- see file header comment for the two-member-FK collision
            // workaround.
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            real.foreignKeys["resolved_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("resolved_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ─────────────────────

        test("board_membership entity column-name set matches the hand-written BoardMembershipTable 1:1") {
            model.entities
                .single { it.name == "board_membership" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder BoardMembershipTable.columns.map { it.name }
        }

        test("transparenzregister_reminder entity column-name set matches the hand-written TransparenzregisterReminderTable 1:1") {
            model.entities
                .single { it.name == "transparenzregister_reminder" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder TransparenzregisterReminderTable.columns.map { it.name }
        }

        test("board_membership.committee_role is modelled as a real ErmDataType.Enum column") {
            val committeeRole = model.entities.single { it.name == "board_membership" }.attributeByName("committee_role")
            committeeRole?.type shouldBe
                ErmDataType.Enum(
                    name = "CommitteeRole",
                    values = listOf("CHAIR", "DEPUTY_CHAIR", "SECRETARY", "MEMBER", "ASSESSOR"),
                    externalFqName = "network.lapis.cloud.shared.domain.CommitteeRole",
                )
        }

        test("transparenzregister_reminder.committee_role/change_type are modelled as real ErmDataType.Enum columns") {
            val entity = model.entities.single { it.name == "transparenzregister_reminder" }
            entity.attributeByName("committee_role")?.type shouldBe
                ErmDataType.Enum(
                    name = "CommitteeRole",
                    values = listOf("CHAIR", "DEPUTY_CHAIR", "SECRETARY", "MEMBER", "ASSESSOR"),
                    externalFqName = "network.lapis.cloud.shared.domain.CommitteeRole",
                )
            entity.attributeByName("change_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "BoardChangeType",
                    values = listOf("JOINED", "LEFT"),
                    externalFqName = "network.lapis.cloud.shared.domain.BoardChangeType",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedTransparenzregisterTable(
    val columns: Map<String, IntrospectedTransparenzregisterColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedTransparenzregisterColumn(
    val nullable: Boolean,
)

/** ANSI `information_schema` walk for a single table's columns, nullability and FK targets. Mirrors [PostalMailSchemaDriftTest]'s own (private, domain-scoped) `introspectPostalMailTable`. */
private fun JdbcTransaction.introspectTransparenzregisterTable(tableName: String): IntrospectedTransparenzregisterTable {
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
            IntrospectedTransparenzregisterColumn(nullable = nullable)
        }
    return IntrospectedTransparenzregisterTable(columns = columns, foreignKeys = fkByColumn)
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
