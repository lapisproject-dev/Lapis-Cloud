package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — foundation domain.
 *
 * Verifies that `lapis-server/src/main/kuml/00-foundation.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`member`/`account`/the `membership_tier_id` forward
 * reference), and (b) the hand-written `MemberTable`/`AccountTable` Exposed objects.
 *
 * This is the first domain's drift-check fixture proving the approach described in
 * `docs/architecture/domain-model.adoc` — designModelStrategy **option B**: `design.kuml.kts`
 * becomes the enforced source of truth for schema *shape*, while the hand-written `Table`
 * objects remain the actually-compiled/actually-imported-by-N-files runtime artifact (kUML's
 * enum-to-VARCHAR downgrade and lack of a Kotlin-object-name override tag make a full
 * generated-and-compiled swap lossy/disruptive for this codebase today — see the `.kuml.kts`
 * file's own header comment and CLAUDE.md's kUML-Repo-Konventionen for the full rationale).
 *
 * Two independent comparisons:
 *  1. [ErmModel] (from `UmlToErmTransformer`) vs. `information_schema` introspection of the real
 *     H2-migrated schema — table/column/type/nullable/FK shape.
 *  2. [ErmModel] vs. the hand-written `MemberTable`/`AccountTable` — column name set 1:1 (a
 *     future domain wave doing an actual `ErmToExposedTransformer` + import-rewrite compile-swap
 *     would extend this to full type-level comparison; for the verification-only artifact, the
 *     name-set/nullability/FK-target comparison already catches the drift class this test exists
 *     to catch: someone editing V10+.sql / the hand-written table without updating the model, or
 *     vice versa).
 */
class SchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "00-foundation.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly member, account and the membership_tier stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "account", "membership_tier")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("member table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "member" }
            val real = transaction { introspectTable("member") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["membership_tier_id"] shouldBe "membership_tier"
        }

        test("member.street/postal_code/city/country are nullable (V0.4.1 postal address)") {
            val entity = model.entities.single { it.name == "member" }
            val real = transaction { introspectTable("member") }

            listOf("street", "postal_code", "city", "country").forEach { column ->
                withClue("column '$column'") {
                    entity.attributeByName(column)?.nullable shouldBe true
                    real.columns.getValue(column).nullable shouldBe true
                }
            }
        }

        test("account table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "account" }
            val real = transaction { introspectTable("account") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["member_id"] shouldBe "member"
            // The real schema has member_id UNIQUE (1:1 association). UmlToErmTransformer's
            // association-to-FK rule always synthesizes unique=false on the attribute itself (no
            // «Column» stereotype is applicable to an association-derived attribute) — pinned
            // instead via a class-level «Index» (single-column, unique=true), which renders as a
            // separate ErmIndex rather than ErmAttribute.unique.
            real.columns.getValue("member_id").unique shouldBe true
            entity.attributeByName("member_id")?.unique shouldBe false
            entity.indexes.single { it.name == "uq_account_member_id" }.let {
                it.unique shouldBe true
                it.attributeIds shouldBe listOf(entity.attributeByName("member_id")!!.id)
            }
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("member entity column-name set matches the hand-written MemberTable 1:1") {
            model.entities
                .single { it.name == "member" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MemberTable.columns.map { it.name }
        }

        test("account entity column-name set matches the hand-written AccountTable 1:1") {
            model.entities
                .single { it.name == "account" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AccountTable.columns.map { it.name }
        }

        test("member.status and account.role are modelled as real ErmDataType.Enum columns") {
            // ADR-0016 gap #3 closed (see CLAUDE.md kUML-Repo-Konventionen, vault): with the
            // «Column».sqlType overrides removed, UmlToErmTransformer's enum-fallback path now
            // applies — it emits a typed ErmDataType.Enum(name, values) plus a generated CHECK
            // constraint restricting the column to the enum's literal set, instead of a plain
            // ErmDataType.Custom("VARCHAR(20)"). This is a closer structural match to
            // MemberTable/AccountTable's `enumerationByName<T>()` Exposed columns than the former
            // untyped VARCHAR override was.
            val status = model.entities.single { it.name == "member" }.attributeByName("status")
            val role = model.entities.single { it.name == "account" }.attributeByName("role")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "MemberStatus",
                    values = listOf("ANTRAG", "AKTIV", "GAST", "AUSGETRETEN"),
                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                )
            role?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountRole",
                    values = listOf("MEMBER", "BOARD", "TREASURER", "ADMIN"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountRole",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedTable(
    val columns: Map<String, IntrospectedColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedColumn(
    val nullable: Boolean,
    val unique: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * unique constraints. Mirrors the pattern established in
 * [network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest]'s `tablesReferencingMember()`.
 */
private fun JdbcTransaction.introspectTable(tableName: String): IntrospectedTable {
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

    // Detects both inline CONSTRAINT ... UNIQUE (e.g. member.email) and standalone CREATE UNIQUE
    // INDEX (e.g. account's uq_account_member_id, generated via «Column».unique / «Index» —
    // H2's information_schema.table_constraints only surfaces the former, never a plain named
    // unique index, so both sources are unioned here.
    val uniqueColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumns += rs.getString("column_name")
        }
    }

    val columns =
        nullableByColumn.mapValues { (name, nullable) ->
            IntrospectedColumn(nullable = nullable, unique = name in uniqueColumns)
        }
    return IntrospectedTable(columns = columns, foreignKeys = fkByColumn)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
