package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — vote domain.
 *
 * Verifies that `lapis-server/src/main/kuml/06-vote.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`vote`/`vote_option`/`vote_ballot` —
 * V8__meritokratische_voteen.sql), and (b) the hand-written `VoteTable`/
 * `VoteOptionTable`/`VoteBallotTable` Exposed objects (defined in the same
 * `GovernanceTables.kt` file as the governance domain's own tables — see
 * `06-vote.kuml.kts`'s file header comment for why this is nonetheless a separate
 * generation unit/domain wave).
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain), [CommunicationSchemaDriftTest]
 * (communication domain), [DsgvoSchemaDriftTest] (dsgvo domain) and [GovernanceSchemaDriftTest]
 * (governance domain) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B
 * rationale (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class VoteSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "06-vote.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the three vote entities plus the Member/Motion/Meeting/Resolution stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "motion",
                    "meeting",
                    "resolution",
                    "vote",
                    "vote_option",
                    "vote_ballot",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("vote table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "vote" }
            val real = transaction { introspectVoteTable("vote") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["motion_id"] shouldBe "motion"
            real.foreignKeys["meeting_id"] shouldBe "meeting"
            real.foreignKeys["resolution_id"] shouldBe "resolution"
            model.entityNameOf(entity.attributeByName("motion_id")?.foreignKey?.targetEntityId ?: "") shouldBe "motion"
            model.entityNameOf(entity.attributeByName("meeting_id")?.foreignKey?.targetEntityId ?: "") shouldBe "meeting"
            model.entityNameOf(entity.attributeByName("resolution_id")?.foreignKey?.targetEntityId ?: "") shouldBe "resolution"

            // opened_by -> member, NOT NULL: same naming-gap class as document/communication/
            // dsgvo/governance's own mismatched member-FK columns (default would be "member_id")
            // — pinned instead via «Column».fkEntity.
            real.foreignKeys["opened_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("opened_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // winner_option_id: real schema has no FK constraint on this column at all (circular
            // with vote_option, which itself FK-references vote) — pinned explicitly.
            real.foreignKeys.containsKey("winner_option_id") shouldBe false
            entity.attributeByName("winner_option_id")?.foreignKey shouldBe null
        }

        test("vote_option table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "vote_option" }
            val real = transaction { introspectVoteTable("vote_option") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["vote_id"] shouldBe "vote"
            model.entityNameOf(entity.attributeByName("vote_id")?.foreignKey?.targetEntityId ?: "") shouldBe "vote"
        }

        test("vote_ballot table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "vote_ballot" }
            val real = transaction { introspectVoteTable("vote_ballot") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["vote_id"] shouldBe "vote"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("vote_id")?.foreignKey?.targetEntityId ?: "") shouldBe "vote"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // option_id -> vote_option, NOT NULL: association-derived default would be
            // "vote_option_id", not the real schema's "option_id" — plain «Column» attribute
            // pinned instead via «Column».fkEntity.
            real.foreignKeys["option_id"] shouldBe "vote_option"
            model.entityNameOf(entity.attributeByName("option_id")?.foreignKey?.targetEntityId ?: "") shouldBe "vote_option"
        }

        test("vote_ballot's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_vote_ballot_member UNIQUE (vote_id, member_id) — pinned via a
            // class-level «Index» (composite, unique=true), same mechanism as contribution's/
            // document's/communication's/governance's own composite UNIQUE constraints.
            val entity = model.entities.single { it.name == "vote_ballot" }
            val real = transaction { introspectVoteTable("vote_ballot") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("vote_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_vote_ballot_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("vote_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                    )
            }
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("vote entity column-name set matches the hand-written VoteTable 1:1") {
            model.entities
                .single { it.name == "vote" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder VoteTable.columns.map { it.name }
        }

        test("vote_option entity column-name set matches the hand-written VoteOptionTable 1:1") {
            model.entities
                .single { it.name == "vote_option" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder VoteOptionTable.columns.map { it.name }
        }

        test("vote_ballot entity column-name set matches the hand-written VoteBallotTable 1:1") {
            model.entities
                .single { it.name == "vote_ballot" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder VoteBallotTable.columns.map { it.name }
        }

        test("vote.status is modelled as a real ErmDataType.Enum column") {
            // Same gap-closure as MemberStatus/AccountRole/.../ResolutionMode in the prior
            // domains — with the «Column».sqlType override removed, kUML's enum-to-Enum+CHECK
            // fallback path applies.
            val status = model.entities.single { it.name == "vote" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "VoteStatus",
                    values = listOf("OPEN", "CLOSED", "ABORTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.VoteStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedVoteTable(
    val columns: Map<String, IntrospectedVoteColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedVoteColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [GovernanceSchemaDriftTest]'s (private,
 * governance-domain-scoped) `introspectGovernanceTable`.
 */
private fun JdbcTransaction.introspectVoteTable(tableName: String): IntrospectedVoteTable {
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
            IntrospectedVoteColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedVoteTable(
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
