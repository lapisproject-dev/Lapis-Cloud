package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — systemic_consensus domain (Systemic Consensus, V0.2.5).
 *
 * Verifies that `lapis-server/src/main/kuml/09-systemic-consensus.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`systemic_consensus`/`systemic_consensus_option`/
 * `systemic_consensus_eligible_voter`/`systemic_consensus_participation`/`systemic_consensus_ballot`/
 * `systemic_consensus_resistance` — V1__baseline.sql), and (b) the generated `SystemicConsensusTable`/
 * `SystemicConsensusOptionTable`/`SystemicConsensusEligibleVoterTable`/`SystemicConsensusParticipationTable`/
 * `SystemicConsensusBallotTable`/`SystemicConsensusResistanceTable` Exposed objects
 * (`network.lapis.cloud.server.db.generated`).
 *
 * Mirrors [ElectionSchemaDriftTest] (election domain) — see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class SystemicConsensusSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "09-systemic-consensus.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the six systemic_consensus entities plus the Member/Motion/Meeting/Committee/Resolution stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "motion",
                    "meeting",
                    "committee",
                    "resolution",
                    "systemic_consensus",
                    "systemic_consensus_option",
                    "systemic_consensus_eligible_voter",
                    "systemic_consensus_participation",
                    "systemic_consensus_ballot",
                    "systemic_consensus_resistance",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("systemic_consensus table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "systemic_consensus" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus") }

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

            // opened_by -> member, NOT NULL: default would be "member_id" -- pinned via
            // «Column».fkEntity instead (same naming-gap class as every prior domain).
            real.foreignKeys["opened_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("opened_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // winner_option_id: no FK constraint in the real schema -- circular with
            // systemic_consensus_option, same workaround as vote.winner_option_id.
            real.foreignKeys.containsKey("winner_option_id") shouldBe false
            entity.attributeByName("winner_option_id")?.foreignKey shouldBe null
            entity.attributeByName("winner_option_id")?.nullable shouldBe true
        }

        test("systemic_consensus_option table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "systemic_consensus_option" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_option") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["systemic_consensus_id"] shouldBe "systemic_consensus"
            model.entityNameOf(entity.attributeByName("systemic_consensus_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "systemic_consensus"

            // created_by -> member, NOT NULL: default would be "member_id" -- pinned via
            // «Column».fkEntity instead.
            real.foreignKeys["created_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("systemic_consensus_eligible_voter table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "systemic_consensus_eligible_voter" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_eligible_voter") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["systemic_consensus_id"] shouldBe "systemic_consensus"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("systemic_consensus_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "systemic_consensus"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test(
            "systemic_consensus_eligible_voter's composite UNIQUE constraint is pinned via a class-level «Index», including the round dimension",
        ) {
            val entity = model.entities.single { it.name == "systemic_consensus_eligible_voter" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_eligible_voter") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("systemic_consensus_id", "member_id", "round"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_systemic_consensus_eligible_voter_member_round" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("systemic_consensus_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("round")!!.id,
                    )
            }
        }

        test("systemic_consensus_participation table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "systemic_consensus_participation" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_participation") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["systemic_consensus_id"] shouldBe "systemic_consensus"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("systemic_consensus_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "systemic_consensus"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test(
            "systemic_consensus_participation's composite UNIQUE constraint is pinned via a class-level «Index», including the round dimension",
        ) {
            // uq_systemic_consensus_participation_member_round UNIQUE (systemic_consensus_id, member_id, round)
            // -- the GEHEIM-path one-member-one-vote-per-round backstop (see file header comment
            // in 09-systemic-consensus.kuml.kts).
            val entity = model.entities.single { it.name == "systemic_consensus_participation" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_participation") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("systemic_consensus_id", "member_id", "round"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_systemic_consensus_participation_member_round" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("systemic_consensus_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("round")!!.id,
                    )
            }
        }

        test("systemic_consensus_ballot table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "systemic_consensus_ballot" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_ballot") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["systemic_consensus_id"] shouldBe "systemic_consensus"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("systemic_consensus_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "systemic_consensus"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // Ballot secrecy: member_id is nullable, always NULL for a secret SystemicConsensus --
            // pinned explicitly (see file header comment in 09-systemic-consensus.kuml.kts).
            entity.attributeByName("member_id")?.nullable shouldBe true
        }

        test("systemic_consensus_ballot's composite UNIQUE constraint is pinned via a class-level «Index», including the round dimension") {
            val entity = model.entities.single { it.name == "systemic_consensus_ballot" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_ballot") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("systemic_consensus_id", "member_id", "round"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_systemic_consensus_ballot_member_round" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("systemic_consensus_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("round")!!.id,
                    )
            }
        }

        test("systemic_consensus_resistance table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "systemic_consensus_resistance" }
            val real = transaction { introspectSystemicConsensusTable("systemic_consensus_resistance") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // ballot_id -> systemic_consensus_ballot, NOT NULL: default would be
            // "systemic_consensus_ballot_id" -- plain «Column» attribute pinned via «Column».fkEntity.
            real.foreignKeys["ballot_id"] shouldBe "systemic_consensus_ballot"
            model.entityNameOf(entity.attributeByName("ballot_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "systemic_consensus_ballot"

            // option_id -> systemic_consensus_option, NOT NULL: default would be
            // "systemic_consensus_option_id" -- plain «Column» attribute pinned via «Column».fkEntity.
            real.foreignKeys["option_id"] shouldBe "systemic_consensus_option"
            model.entityNameOf(entity.attributeByName("option_id")?.foreignKey?.targetEntityId ?: "") shouldBe "systemic_consensus_option"
        }

        // ── (2) Model vs. generated Exposed Table objects ────────────────────

        test("systemic_consensus entity column-name set matches the generated SystemicConsensusTable 1:1") {
            model.entities
                .single { it.name == "systemic_consensus" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SystemicConsensusTable.columns.map { it.name }
        }

        test("systemic_consensus_option entity column-name set matches the generated SystemicConsensusOptionTable 1:1") {
            model.entities
                .single { it.name == "systemic_consensus_option" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SystemicConsensusOptionTable.columns.map { it.name }
        }

        test("systemic_consensus_eligible_voter entity column-name set matches the generated SystemicConsensusEligibleVoterTable 1:1") {
            model.entities
                .single { it.name == "systemic_consensus_eligible_voter" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SystemicConsensusEligibleVoterTable.columns.map { it.name }
        }

        test("systemic_consensus_participation entity column-name set matches the generated SystemicConsensusParticipationTable 1:1") {
            model.entities
                .single { it.name == "systemic_consensus_participation" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SystemicConsensusParticipationTable.columns.map { it.name }
        }

        test("systemic_consensus_ballot entity column-name set matches the generated SystemicConsensusBallotTable 1:1") {
            model.entities
                .single { it.name == "systemic_consensus_ballot" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SystemicConsensusBallotTable.columns.map { it.name }
        }

        test("systemic_consensus_resistance entity column-name set matches the generated SystemicConsensusResistanceTable 1:1") {
            model.entities
                .single { it.name == "systemic_consensus_resistance" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SystemicConsensusResistanceTable.columns.map { it.name }
        }

        test("systemic_consensus.status/aggregation/tiebreak_rule/bindingness are modelled as real ErmDataType.Enum columns") {
            val entity = model.entities.single { it.name == "systemic_consensus" }
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "SystemicConsensusStatus",
                    values = listOf("COLLECTION", "RATING", "CLOSED", "EVALUATED", "ABORTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.SystemicConsensusStatus",
                )
            entity.attributeByName("aggregation")?.type shouldBe
                ErmDataType.Enum(
                    name = "SystemicConsensusAggregation",
                    values = listOf("MEAN", "SUM"),
                    externalFqName = "network.lapis.cloud.shared.domain.SystemicConsensusAggregation",
                )
            entity.attributeByName("tiebreak_rule")?.type shouldBe
                ErmDataType.Enum(
                    name = "SystemicConsensusTiebreakRule",
                    values = listOf("LOWEST_MAX_RESISTANCE", "LOWEST_STD_DEV", "REPEAT"),
                    externalFqName = "network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule",
                )
            entity.attributeByName("bindingness")?.type shouldBe
                ErmDataType.Enum(
                    name = "SystemicConsensusBindingness",
                    values = listOf("ADVISORY", "BINDING"),
                    externalFqName = "network.lapis.cloud.shared.domain.SystemicConsensusBindingness",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedSystemicConsensusTable(
    val columns: Map<String, IntrospectedSystemicConsensusColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedSystemicConsensusColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [ElectionSchemaDriftTest]'s (private, election-domain-scoped)
 * `introspectElectionTable`.
 */
private fun JdbcTransaction.introspectSystemicConsensusTable(tableName: String): IntrospectedSystemicConsensusTable {
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
            IntrospectedSystemicConsensusColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedSystemicConsensusTable(
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
