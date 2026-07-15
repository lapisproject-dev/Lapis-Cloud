package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — election domain.
 *
 * Verifies that `lapis-server/src/main/kuml/07-election.kuml.kts` is a faithful model of both (a) the
 * real, Flyway-migrated H2 schema (`election`/`election_candidacy`/`election_option`/`election_board_member`/
 * `election_eligible_voter`/`election_participation`/`election_tally_approval`/`election_ballot`/
 * `election_ballot_selection` — V9__demokratische_electionen.sql), and (b) the hand-written `ElectionTable`/
 * `ElectionCandidacyTable`/`ElectionOptionTable`/`ElectionBoardMemberTable`/`ElectionEligibleVoterTable`/
 * `ElectionParticipationTable`/`ElectionTallyApprovalTable`/`ElectionBallotTable`/`ElectionBallotSelectionTable`
 * Exposed objects (`network.lapis.cloud.server.db.generated.ElectionTables.kt`).
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain), [CommunicationSchemaDriftTest]
 * (communication domain), [DsgvoSchemaDriftTest] (dsgvo domain), [GovernanceSchemaDriftTest]
 * (governance domain) and [VoteSchemaDriftTest] (vote domain) — see [SchemaDriftTest]'s
 * KDoc for the full designModelStrategy option B rationale (verification-only artifact;
 * hand-written `Table` objects remain the actually-compiled/actually-imported-by-N-files runtime
 * artifact).
 */
class ElectionSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "07-election.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the nine election entities plus the Member/Motion/Meeting/Committee/Resolution stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "motion",
                    "meeting",
                    "committee",
                    "resolution",
                    "election",
                    "election_candidacy",
                    "election_option",
                    "election_board_member",
                    "election_eligible_voter",
                    "election_participation",
                    "election_tally_approval",
                    "election_ballot",
                    "election_ballot_selection",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("election table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election" }
            val real = transaction { introspectElectionTable("election") }

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

            // target_committee_id -> committee, nullable: same naming-gap class as document/
            // communication/dsgvo/governance/vote's own mismatched FK columns (default
            // would be "committee_id") — pinned instead via «Column».fkEntity.
            real.foreignKeys["target_committee_id"] shouldBe "committee"
            model.entityNameOf(entity.attributeByName("target_committee_id")?.foreignKey?.targetEntityId ?: "") shouldBe "committee"

            // opened_by -> member, NOT NULL: same naming-gap class (default would be "member_id")
            // — pinned instead via «Column».fkEntity.
            real.foreignKeys["opened_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("opened_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("election_candidacy table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_candidacy" }
            val real = transaction { introspectElectionTable("election_candidacy") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("election_option table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_option" }
            val real = transaction { introspectElectionTable("election_option") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"

            // candidacy_id -> election_candidacy, nullable: default would be "election_candidacy_id",
            // not the real schema's "candidacy_id" — plain «Column» attribute pinned instead via
            // «Column».fkEntity.
            real.foreignKeys["candidacy_id"] shouldBe "election_candidacy"
            model.entityNameOf(entity.attributeByName("candidacy_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election_candidacy"
        }

        test("election_board_member table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_board_member" }
            val real = transaction { introspectElectionTable("election_board_member") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("election_board_member's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_election_board_member_member UNIQUE (election_id, member_id) — pinned via a class-level
            // «Index» (composite, unique=true), same mechanism as contribution's/document's/
            // communication's/governance's/vote's own composite UNIQUE constraints.
            val entity = model.entities.single { it.name == "election_board_member" }
            val real = transaction { introspectElectionTable("election_board_member") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("election_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_election_board_member_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(entity.attributeByName("election_id")!!.id, entity.attributeByName("member_id")!!.id)
            }
        }

        test("election_eligible_voter table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_eligible_voter" }
            val real = transaction { introspectElectionTable("election_eligible_voter") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("election_eligible_voter's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_election_eligible_voter_member UNIQUE (election_id, member_id) — pinned via a class-level
            // «Index» (composite, unique=true).
            val entity = model.entities.single { it.name == "election_eligible_voter" }
            val real = transaction { introspectElectionTable("election_eligible_voter") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("election_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_election_eligible_voter_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(entity.attributeByName("election_id")!!.id, entity.attributeByName("member_id")!!.id)
            }
        }

        test("election_participation table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_participation" }
            val real = transaction { introspectElectionTable("election_participation") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("election_participation's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_election_participation_member UNIQUE (election_id, member_id) — the GEHEIM-path
            // one-member-one-vote backstop (see file header comment in 07-election.kuml.kts), pinned
            // via a class-level «Index» (composite, unique=true).
            val entity = model.entities.single { it.name == "election_participation" }
            val real = transaction { introspectElectionTable("election_participation") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("election_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_election_participation_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(entity.attributeByName("election_id")!!.id, entity.attributeByName("member_id")!!.id)
            }
        }

        test("election_tally_approval table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_tally_approval" }
            val real = transaction { introspectElectionTable("election_tally_approval") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("election_tally_approval's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_election_tally_approval_member UNIQUE (election_id, member_id) — pinned via a class-level
            // «Index» (composite, unique=true).
            val entity = model.entities.single { it.name == "election_tally_approval" }
            val real = transaction { introspectElectionTable("election_tally_approval") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("election_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_election_tally_approval_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(entity.attributeByName("election_id")!!.id, entity.attributeByName("member_id")!!.id)
            }
        }

        test("election_ballot table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_ballot" }
            val real = transaction { introspectElectionTable("election_ballot") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["election_id"] shouldBe "election"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("election_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // Ballot secrecy: member_id is nullable, always NULL for a secret Election — pinned
            // explicitly (see file header comment in 07-election.kuml.kts for the full rationale).
            entity.attributeByName("member_id")?.nullable shouldBe true
        }

        test("election_ballot's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_election_ballot_member UNIQUE (election_id, member_id) — the non-secret-path
            // one-member-one-vote backstop (see file header comment in 07-election.kuml.kts), pinned
            // via a class-level «Index» (composite, unique=true).
            val entity = model.entities.single { it.name == "election_ballot" }
            val real = transaction { introspectElectionTable("election_ballot") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("election_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_election_ballot_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(entity.attributeByName("election_id")!!.id, entity.attributeByName("member_id")!!.id)
            }
        }

        test("election_ballot_selection table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "election_ballot_selection" }
            val real = transaction { introspectElectionTable("election_ballot_selection") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // ballot_id -> election_ballot, NOT NULL: default would be "election_ballot_id"
            // — plain «Column» attribute pinned instead via «Column».fkEntity.
            real.foreignKeys["ballot_id"] shouldBe "election_ballot"
            model.entityNameOf(entity.attributeByName("ballot_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election_ballot"

            // option_id -> election_option, NOT NULL: default would be "election_option_id" — plain
            // «Column» attribute pinned instead via «Column».fkEntity.
            real.foreignKeys["option_id"] shouldBe "election_option"
            model.entityNameOf(entity.attributeByName("option_id")?.foreignKey?.targetEntityId ?: "") shouldBe "election_option"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("election entity column-name set matches the hand-written ElectionTable 1:1") {
            model.entities
                .single { it.name == "election" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionTable.columns.map { it.name }
        }

        test("election_candidacy entity column-name set matches the hand-written ElectionCandidacyTable 1:1") {
            model.entities
                .single { it.name == "election_candidacy" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionCandidacyTable.columns.map { it.name }
        }

        test("election_option entity column-name set matches the hand-written ElectionOptionTable 1:1") {
            model.entities
                .single { it.name == "election_option" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionOptionTable.columns.map { it.name }
        }

        test("election_board_member entity column-name set matches the hand-written ElectionBoardMemberTable 1:1") {
            model.entities
                .single { it.name == "election_board_member" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionBoardMemberTable.columns.map { it.name }
        }

        test("election_eligible_voter entity column-name set matches the hand-written ElectionEligibleVoterTable 1:1") {
            model.entities
                .single { it.name == "election_eligible_voter" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionEligibleVoterTable.columns.map { it.name }
        }

        test("election_participation entity column-name set matches the hand-written ElectionParticipationTable 1:1") {
            model.entities
                .single { it.name == "election_participation" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionParticipationTable.columns.map { it.name }
        }

        test("election_tally_approval entity column-name set matches the hand-written ElectionTallyApprovalTable 1:1") {
            model.entities
                .single { it.name == "election_tally_approval" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionTallyApprovalTable.columns.map { it.name }
        }

        test("election_ballot entity column-name set matches the hand-written ElectionBallotTable 1:1") {
            model.entities
                .single { it.name == "election_ballot" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionBallotTable.columns.map { it.name }
        }

        test("election_ballot_selection entity column-name set matches the hand-written ElectionBallotSelectionTable 1:1") {
            model.entities
                .single { it.name == "election_ballot_selection" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ElectionBallotSelectionTable.columns.map { it.name }
        }

        test("election.election_typ/status/target_role are modelled as real ErmDataType.Enum columns") {
            // Same gap-closure as MemberStatus/AccountRole/.../ResolutionMode/VoteStatus in
            // the prior domains — with the «Column».sqlType overrides removed, kUML's
            // enum-to-Enum+CHECK fallback path applies.
            val entity = model.entities.single { it.name == "election" }
            entity.attributeByName("election_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "ElectionType",
                    values = listOf("YES_NO", "SINGLE_CHOICE", "MULTI_CHOICE", "LIST_VOTE", "RANKED_CHOICE"),
                    externalFqName = "network.lapis.cloud.shared.domain.ElectionType",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ElectionStatus",
                    values =
                        listOf(
                            "PREPARATION",
                            "CANDIDATE_LIST_RELEASED",
                            "OPEN",
                            "CLOSED",
                            "TALLIED",
                            "ABORTED",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.ElectionStatus",
                )
            entity.attributeByName("target_role")?.type shouldBe
                ErmDataType.Enum(
                    name = "CommitteeRole",
                    values = listOf("CHAIR", "DEPUTY_CHAIR", "SECRETARY", "MEMBER", "ASSESSOR"),
                    externalFqName = "network.lapis.cloud.shared.domain.CommitteeRole",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedElectionTable(
    val columns: Map<String, IntrospectedElectionColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedElectionColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [VoteSchemaDriftTest]'s (private,
 * vote-domain-scoped) `introspectVoteTable`.
 */
private fun JdbcTransaction.introspectElectionTable(tableName: String): IntrospectedElectionTable {
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
            IntrospectedElectionColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedElectionTable(
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
