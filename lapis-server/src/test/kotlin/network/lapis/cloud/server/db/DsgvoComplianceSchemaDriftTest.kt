package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.DataBreachIncidentTable
import network.lapis.cloud.server.db.generated.DataProtectionImpactAssessmentTable
import network.lapis.cloud.server.db.generated.ProcessingAgreementTable
import network.lapis.cloud.server.db.generated.TechnicalOrganizationalMeasureTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — DSGVO-compliance domain (V0.5.5 DSGVO-Vollausbau).
 *
 * Verifies that `lapis-server/src/main/kuml/16-dsgvo-compliance.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`processing_agreement`/
 * `technical_organizational_measure`/`data_protection_impact_assessment`/`data_breach_incident`),
 * and (b) the hand-written Exposed `Table` objects. Mirrors [DsgvoSchemaDriftTest] (also a
 * multi-entity, multi-actor-FK domain) — see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class DsgvoComplianceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "16-dsgvo-compliance.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun entityNameOf(entityId: String?): String? = model.entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the four DSGVO-compliance entities plus the Member/Document stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "document",
                    "processing_agreement",
                    "technical_organizational_measure",
                    "data_protection_impact_assessment",
                    "data_breach_incident",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("processing_agreement table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "processing_agreement" }
            val real = transaction { introspectComplianceTable("processing_agreement") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["document_id"] shouldBe "document"
            real.foreignKeys["created_by"] shouldBe "member"
            real.foreignKeys["updated_by"] shouldBe "member"
            entityNameOf(entity.attributeByName("document_id")?.foreignKey?.targetEntityId) shouldBe "document"
            entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId) shouldBe "member"
            entityNameOf(entity.attributeByName("updated_by")?.foreignKey?.targetEntityId) shouldBe "member"
        }

        test("technical_organizational_measure table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "technical_organizational_measure" }
            val real = transaction { introspectComplianceTable("technical_organizational_measure") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["created_by"] shouldBe "member"
            real.foreignKeys["updated_by"] shouldBe "member"
        }

        test("data_protection_impact_assessment table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "data_protection_impact_assessment" }
            val real = transaction { introspectComplianceTable("data_protection_impact_assessment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["created_by"] shouldBe "member"
            real.foreignKeys["updated_by"] shouldBe "member"
        }

        test("data_breach_incident table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "data_breach_incident" }
            val real = transaction { introspectComplianceTable("data_breach_incident") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["reported_by"] shouldBe "member"
            real.foreignKeys["updated_by"] shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("processing_agreement entity column-name set matches the hand-written ProcessingAgreementTable 1:1") {
            model.entities
                .single { it.name == "processing_agreement" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ProcessingAgreementTable.columns.map { it.name }
        }

        test(
            "technical_organizational_measure entity column-name set matches the hand-written TechnicalOrganizationalMeasureTable 1:1",
        ) {
            model.entities
                .single { it.name == "technical_organizational_measure" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder TechnicalOrganizationalMeasureTable.columns.map { it.name }
        }

        test(
            "data_protection_impact_assessment entity column-name set matches the hand-written DataProtectionImpactAssessmentTable 1:1",
        ) {
            model.entities
                .single { it.name == "data_protection_impact_assessment" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DataProtectionImpactAssessmentTable.columns.map { it.name }
        }

        test("data_breach_incident entity column-name set matches the hand-written DataBreachIncidentTable 1:1") {
            model.entities
                .single { it.name == "data_breach_incident" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DataBreachIncidentTable.columns.map { it.name }
        }

        // ── (3) Enum literal-order pins ──────────────────────────────────────────

        test("processing_agreement.avv_status is modelled as a real ErmDataType.Enum column") {
            val attr = model.entities.single { it.name == "processing_agreement" }.attributeByName("avv_status")
            attr?.type shouldBe
                ErmDataType.Enum(
                    name = "AvvStatus",
                    values = listOf("NONE", "DRAFT", "SIGNED"),
                    externalFqName = "network.lapis.cloud.shared.domain.AvvStatus",
                )
        }

        test("technical_organizational_measure.category is modelled as a real ErmDataType.Enum column") {
            val attr = model.entities.single { it.name == "technical_organizational_measure" }.attributeByName("category")
            attr?.type shouldBe
                ErmDataType.Enum(
                    name = "TomCategory",
                    values =
                        listOf(
                            "PHYSICAL_ACCESS_CONTROL",
                            "SYSTEM_ACCESS_CONTROL",
                            "DATA_ACCESS_CONTROL",
                            "TRANSFER_CONTROL",
                            "INPUT_CONTROL",
                            "ORDER_CONTROL",
                            "AVAILABILITY_CONTROL",
                            "SEPARATION_CONTROL",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.TomCategory",
                )
        }

        test("data_protection_impact_assessment.risk_likelihood/risk_severity/status are modelled as real ErmDataType.Enum columns") {
            val entity = model.entities.single { it.name == "data_protection_impact_assessment" }
            val riskLevelType =
                ErmDataType.Enum(
                    name = "RiskLevel",
                    values = listOf("LOW", "MEDIUM", "HIGH"),
                    externalFqName = "network.lapis.cloud.shared.domain.RiskLevel",
                )
            entity.attributeByName("risk_likelihood")?.type shouldBe riskLevelType
            entity.attributeByName("risk_severity")?.type shouldBe riskLevelType
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "DsfaStatus",
                    values = listOf("DRAFT", "COMPLETED", "OUTDATED_REVIEW_DUE"),
                    externalFqName = "network.lapis.cloud.shared.domain.DsfaStatus",
                )
        }

        test("data_breach_incident.risk_level/status are modelled as real ErmDataType.Enum columns") {
            val entity = model.entities.single { it.name == "data_breach_incident" }
            entity.attributeByName("risk_level")?.type shouldBe
                ErmDataType.Enum(
                    name = "RiskLevel",
                    values = listOf("LOW", "MEDIUM", "HIGH"),
                    externalFqName = "network.lapis.cloud.shared.domain.RiskLevel",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "BreachStatus",
                    values =
                        listOf(
                            "REPORTED",
                            "UNDER_ASSESSMENT",
                            "NOTIFIED_AUTHORITY",
                            "NO_NOTIFICATION_REQUIRED",
                            "CLOSED",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.BreachStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedComplianceTable(
    val columns: Map<String, IntrospectedComplianceColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedComplianceColumn(
    val nullable: Boolean,
)

/** ANSI `information_schema` walk for a single table's columns, nullability and FK targets. Mirrors [DsgvoSchemaDriftTest]'s own. */
private fun JdbcTransaction.introspectComplianceTable(tableName: String): IntrospectedComplianceTable {
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
            IntrospectedComplianceColumn(nullable = nullable)
        }
    return IntrospectedComplianceTable(columns = columns, foreignKeys = fkByColumn)
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
