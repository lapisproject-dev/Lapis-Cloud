package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — document domain.
 *
 * Verifies that `lapis-server/src/main/kuml/02-document.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`document_folder`/`document`/`document_version`), and
 * (b) the hand-written `DocumentFolderTable`/`DocumentTable`/`DocumentVersionTable` Exposed
 * objects.
 *
 * Mirrors [SchemaDriftTest] (foundation domain) and [ContributionSchemaDriftTest] (contribution
 * domain) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B rationale
 * (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class DocumentSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "02-document.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly document_folder, document, document_version plus the Member stub") {
            // Both Member-referencing FKs (document.created_by, document_version.uploaded_by) are
            // modelled as plain «Column» UUID attributes rather than UML associations (see the
            // .kuml.kts file header comment for the naming-gap rationale), pinned instead via
            // «Column».fkEntity against a minimal id-only Member stub declared in this file.
            model.entities.map { it.name }.toSet() shouldBe
                setOf("document_folder", "document", "document_version", "member")
        }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun entityNameOf(entityId: String?): String? = model.entities.firstOrNull { it.id == entityId }?.name

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("document_folder table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "document_folder" }
            val real = transaction { introspectDocumentTable("document_folder") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // parent_folder_id is a genuinely self-referential FK. UmlToErmTransformer skips
            // self-referential UML associations, and it's deliberately left as a plain «Column»
            // attribute rather than pinned via «Column».fkEntity (which could technically resolve
            // it) — the real risk is Kotlin `object`-initializer circularity at the Exposed layer,
            // same as the hand-written DocumentFolderTable's own choice. Since the SQL/Flyway
            // baseline is now generated from this same model, the real schema (unlike the
            // pre-swap hand-written V3__documents.sql) consequently has no FK here either — a
            // deliberate, pre-existing trade-off, not a new regression. Pinned explicitly rather
            // than silently allowed to drift.
            real.foreignKeys["parent_folder_id"] shouldBe null
            model.entities
                .single { it.name == "document_folder" }
                .attributeByName("parent_folder_id")
                ?.foreignKey shouldBe null
        }

        test("document table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "document" }
            val real = transaction { introspectDocumentTable("document") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // folder_id and created_by both have real FKs in the migrated schema. Neither is
            // modelled as a UML association here (see the .kuml.kts file header comment): the
            // association-to-FK column-naming default ("document_folder_id" / "member_id") does
            // not match the real schema's actual column names ("folder_id" / "created_by").
            // Pinned instead via «Column».fkEntity — verified here against both the real schema
            // and the resolved ERM foreign key.
            real.foreignKeys["folder_id"] shouldBe "document_folder"
            real.foreignKeys["created_by"] shouldBe "member"
            entityNameOf(
                model.entities
                    .single { it.name == "document" }
                    .attributeByName("folder_id")
                    ?.foreignKey
                    ?.targetEntityId,
            ) shouldBe "document_folder"
            entityNameOf(
                model.entities
                    .single { it.name == "document" }
                    .attributeByName("created_by")
                    ?.foreignKey
                    ?.targetEntityId,
            ) shouldBe "member"
            // current_version_id deliberately has no FK at the Exposed layer (avoids a document
            // <-> document_version circular Kotlin `object`-initializer reference) — modelled here
            // as a plain «Column» attribute, matching the hand-written DocumentTable's own choice.
            // The pre-swap hand-written V3__documents.sql still had a real FK here (added via a
            // second ALTER TABLE after both tables existed, fk_document_current_version), but
            // since the SQL/Flyway baseline is now generated from this same model, the real schema
            // consequently lacks it too — a deliberate, pre-existing trade-off, not a new
            // regression. Pinned explicitly rather than silently allowed to drift.
            real.foreignKeys["current_version_id"] shouldBe null
            model.entities
                .single { it.name == "document" }
                .attributeByName("current_version_id")
                ?.foreignKey shouldBe null
        }

        test("document_version table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "document_version" }
            val real = transaction { introspectDocumentTable("document_version") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["document_id"] shouldBe "document"
            // uploaded_by has a real FK in the migrated schema. Same rationale as
            // document.folder_id/created_by above — the derived association default name would be
            // "member_id", not the real schema's "uploaded_by" — pinned instead via
            // «Column».fkEntity.
            real.foreignKeys["uploaded_by"] shouldBe "member"
            entityNameOf(
                model.entities
                    .single { it.name == "document_version" }
                    .attributeByName("uploaded_by")
                    ?.foreignKey
                    ?.targetEntityId,
            ) shouldBe "member"
        }

        test("document_version's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_document_version_number UNIQUE (document_id, version_number) in the generated
            // baseline (was V3__documents.sql pre-swap) — «Column».unique is single-column only,
            // so this is pinned via a class-level «Index» (composite, unique=true) instead, which
            // renders as a named CREATE UNIQUE INDEX rather than ErmAttribute.unique.
            val real = transaction { introspectDocumentTable("document_version") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("document_id", "version_number"))

            val entity = model.entities.single { it.name == "document_version" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_document_version_number" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("document_id")!!.id,
                        entity.attributeByName("version_number")!!.id,
                    )
            }
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("document_folder entity column-name set matches the hand-written DocumentFolderTable 1:1") {
            model.entities
                .single { it.name == "document_folder" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DocumentFolderTable.columns.map { it.name }
        }

        test("document entity column-name set matches the hand-written DocumentTable 1:1") {
            model.entities
                .single { it.name == "document" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DocumentTable.columns.map { it.name }
        }

        test("document_version entity column-name set matches the hand-written DocumentVersionTable 1:1") {
            model.entities
                .single { it.name == "document_version" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DocumentVersionTable.columns.map { it.name }
        }

        test("document.access_level is modelled as a real ErmDataType.Enum column") {
            // Same gap-closure as MemberStatus/AccountRole/BillingInterval/ContributionStatus in
            // the prior domains (see SchemaDriftTest's matching test) — with the
            // «Column».sqlType override removed, kUML's enum-to-Enum+CHECK fallback path applies.
            val accessLevel = model.entities.single { it.name == "document" }.attributeByName("access_level")
            accessLevel?.type shouldBe
                ErmDataType.Enum(
                    name = "DocumentAccessLevel",
                    values = listOf("PUBLIC_MEMBERS", "BOARD_ONLY", "ADMIN_ONLY"),
                    externalFqName = "network.lapis.cloud.shared.domain.DocumentAccessLevel",
                )
        }

        test("document_version.document_id has NO_ACTION referential action, matching the real schema") {
            // Associations cannot carry stereotype() calls from .kuml.kts script code today
            // (AssociationBuilder does not implement UmlElementScope — confirmed during the
            // foundation/contribution waves), so no explicit «FK».onDelete tag is set here.
            // UmlToErmTransformer's parseReferentialAction falls back to
            // ReferentialAction.NO_ACTION whenever no tag is present, which happens to already
            // match the real schema's non-default NO_ACTION override for this specific column —
            // pinned explicitly so a future default change in either the transformer or the real
            // schema is caught.
            val fk =
                model.entities
                    .single { it.name == "document_version" }
                    .attributeByName("document_id")
                    ?.foreignKey
            fk?.onDelete?.name shouldBe "NO_ACTION"
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedDocumentTable(
    val columns: Map<String, IntrospectedDocumentColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedDocumentColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors
 * [network.lapis.cloud.server.db.ContributionSchemaDriftTest]'s (private, contribution-domain-
 * scoped) `introspectContributionTable`.
 */
private fun JdbcTransaction.introspectDocumentTable(tableName: String): IntrospectedDocumentTable {
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

    // Detects both inline CONSTRAINT ... UNIQUE and standalone CREATE UNIQUE INDEX (generated via
    // a class-level «Index») — H2's information_schema.table_constraints only surfaces the
    // former, never a plain named unique index, so both sources are unioned.
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
            IntrospectedDocumentColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedDocumentTable(
        columns = columns,
        foreignKeys = fkByColumn,
        compositeUniqueConstraints = compositeUniques,
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
