package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — dsgvo domain.
 *
 * Verifies that `lapis-server/src/main/kuml/04-dsgvo.kuml.kts` is a faithful model of both (a)
 * the real, Flyway-migrated H2 schema (`erasure_request`/`dsgvo_audit_log`), and (b) the
 * hand-written `ErasureRequestTable`/`DsgvoAuditLogTable` Exposed objects.
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain) and [CommunicationSchemaDriftTest]
 * (communication domain) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B
 * rationale (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class DsgvoSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "04-dsgvo.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun entityNameOf(entityId: String?): String? = model.entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the two dsgvo entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("erasure_request", "dsgvo_audit_log", "member")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("erasure_request table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "erasure_request" }
            val real = transaction { introspectDsgvoTable("erasure_request") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // All three real FKs to member (subject_member_id NOT NULL, requested_by NOT NULL,
            // decided_by nullable) are modelled as plain «Column» attributes, not UML associations
            // — see the .kuml.kts file header comment (three-FKs-to-member collision case) —
            // pinned instead via «Column».fkEntity.
            real.foreignKeys["subject_member_id"] shouldBe "member"
            real.foreignKeys["requested_by"] shouldBe "member"
            real.foreignKeys["decided_by"] shouldBe "member"
            entityNameOf(entity.attributeByName("subject_member_id")?.foreignKey?.targetEntityId) shouldBe "member"
            entityNameOf(entity.attributeByName("requested_by")?.foreignKey?.targetEntityId) shouldBe "member"
            entityNameOf(entity.attributeByName("decided_by")?.foreignKey?.targetEntityId) shouldBe "member"
        }

        test("dsgvo_audit_log table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "dsgvo_audit_log" }
            val real = transaction { introspectDsgvoTable("dsgvo_audit_log") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // actor_member_id (nullable) and subject_member_id (NOT NULL) both -> member; neither
            // matches the association-derived "member_id" default. request_id (nullable) ->
            // erasure_request, but the derived default would be "erasure_request_id", not
            // "request_id". All three modelled as plain «Column» attributes — see the .kuml.kts
            // file header comment — pinned instead via «Column».fkEntity.
            real.foreignKeys["actor_member_id"] shouldBe "member"
            real.foreignKeys["subject_member_id"] shouldBe "member"
            real.foreignKeys["request_id"] shouldBe "erasure_request"
            entityNameOf(entity.attributeByName("actor_member_id")?.foreignKey?.targetEntityId) shouldBe "member"
            entityNameOf(entity.attributeByName("subject_member_id")?.foreignKey?.targetEntityId) shouldBe "member"
            entityNameOf(entity.attributeByName("request_id")?.foreignKey?.targetEntityId) shouldBe "erasure_request"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("erasure_request entity column-name set matches the hand-written ErasureRequestTable 1:1") {
            model.entities
                .single { it.name == "erasure_request" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ErasureRequestTable.columns.map { it.name }
        }

        test("dsgvo_audit_log entity column-name set matches the hand-written DsgvoAuditLogTable 1:1") {
            model.entities
                .single { it.name == "dsgvo_audit_log" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DsgvoAuditLogTable.columns.map { it.name }
        }

        test("erasure_request.mode/status are modelled as real ErmDataType.Enum columns") {
            // Same gap-closure as MemberStatus/AccountRole/.../DeliveryStatus in the prior
            // domains — with the «Column».sqlType overrides removed, kUML's enum-to-Enum+CHECK
            // fallback path applies.
            val mode = model.entities.single { it.name == "erasure_request" }.attributeByName("mode")
            val status = model.entities.single { it.name == "erasure_request" }.attributeByName("status")
            mode?.type shouldBe
                ErmDataType.Enum(
                    name = "ErasureMode",
                    values = listOf("ANONYMIZE", "HARD_DELETE_WHERE_UNCONSTRAINED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ErasureMode",
                )
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "ErasureStatus",
                    values = listOf("REQUESTED", "APPROVED", "REJECTED", "COMPLETED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ErasureStatus",
                )
        }

        test(
            "dsgvo_audit_log.actor_role/action are modelled as real ErmDataType.Enum columns",
        ) {
            val actorRole = model.entities.single { it.name == "dsgvo_audit_log" }.attributeByName("actor_role")
            val action = model.entities.single { it.name == "dsgvo_audit_log" }.attributeByName("action")
            actorRole?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountRole",
                    values = listOf("MEMBER", "BOARD", "TREASURER", "ADMIN"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountRole",
                )
            action?.type shouldBe
                ErmDataType.Enum(
                    name = "DsgvoAuditAction",
                    values =
                        listOf(
                            "EXPORT",
                            "ERASURE_REQUESTED",
                            "ERASURE_APPROVED",
                            "ERASURE_REJECTED",
                            "ERASURE_EXECUTED",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.DsgvoAuditAction",
                )
        }

        test("outcome_summary on both tables is modelled as unbounded text, not ErmDataType.Json") {
            // JSON-encoded-as-string, not an unsupported-json-type workaround — see the .kuml.kts
            // file header comment. Both real columns are unbounded `text` (VARCHAR(4000) ->
            // VARCHAR(8000) by V0.2.5/09-systemic-consensus.kuml.kts -> `text` by V0.6.6, once the
            // number of registered PersonalDataContributors made any fixed VARCHAR width just a
            // bigger deadline until the same overflow recurred -- see that file's own header
            // comment for the full history) with no JSON-specific DB feature (no CHECK, no native
            // JSON column type). The explicit «Column».sqlType override ("text") is parsed by
            // UmlErmTypeMapper.mapOverride into ErmDataType.Text, not the bare "varchar" keyword's
            // bounded default.
            val erasureOutcome = model.entities.single { it.name == "erasure_request" }.attributeByName("outcome_summary")
            val auditOutcome = model.entities.single { it.name == "dsgvo_audit_log" }.attributeByName("outcome_summary")
            erasureOutcome?.type shouldBe ErmDataType.Text
            auditOutcome?.type shouldBe ErmDataType.Text
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedDsgvoTable(
    val columns: Map<String, IntrospectedDsgvoColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedDsgvoColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability and FK targets.
 * Mirrors [CommunicationSchemaDriftTest]'s (private, communication-domain-scoped)
 * `introspectCommunicationTable`.
 */
private fun JdbcTransaction.introspectDsgvoTable(tableName: String): IntrospectedDsgvoTable {
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
            IntrospectedDsgvoColumn(nullable = nullable)
        }
    return IntrospectedDsgvoTable(columns = columns, foreignKeys = fkByColumn)
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
