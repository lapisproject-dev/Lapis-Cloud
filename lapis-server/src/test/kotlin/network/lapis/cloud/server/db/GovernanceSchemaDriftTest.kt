package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — governance domain.
 *
 * Verifies that `lapis-server/src/main/kuml/05-governance.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`committee`/`committee_membership`/`meeting`/
 * `agenda_item`/`attendance`/`resolution`/`motion` — V6__governance.sql +
 * V7__motionsverwaltung.sql, plus the resolution.resolution_mode/vote_id/election_id columns
 * added by V8/V9), and (b) the hand-written `CommitteeTable`/`CommitteeMembershipTable`/
 * `MeetingTable`/`AgendaItemTable`/`AttendanceTable`/`ResolutionTable`/`MotionTable`
 * Exposed objects (the `VoteTable`/`VoteOptionTable`/`VoteBallotTable` objects
 * in the same hand-written `GovernanceTables.kt` file are OUT of scope here — later
 * vote-domain wave).
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain), [CommunicationSchemaDriftTest]
 * (communication domain) and [DsgvoSchemaDriftTest] (dsgvo domain) — see [SchemaDriftTest]'s KDoc
 * for the full designModelStrategy option B rationale (verification-only artifact; hand-written
 * `Table` objects remain the actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class GovernanceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "05-governance.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the seven governance entities plus the Member and Document stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "document",
                    "committee",
                    "committee_membership",
                    "meeting",
                    "agenda_item",
                    "attendance",
                    "resolution",
                    "motion",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("committee table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "committee" }
            val real = transaction { introspectGovernanceTable("committee") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys.keys shouldBe emptySet()
        }

        test("committee_membership table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "committee_membership" }
            val real = transaction { introspectGovernanceTable("committee_membership") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // Both FKs match UmlToErmTransformer's association-derived default name exactly
            // ("committee_id" / "member_id"), so both are real UML associations.
            real.foreignKeys["committee_id"] shouldBe "committee"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("committee_id")?.foreignKey?.targetEntityId ?: "") shouldBe "committee"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("meeting table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "meeting" }
            val real = transaction { introspectGovernanceTable("meeting") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // committee_id matches the association-derived default and is a real UML association.
            real.foreignKeys["committee_id"] shouldBe "committee"
            model.entityNameOf(entity.attributeByName("committee_id")?.foreignKey?.targetEntityId ?: "") shouldBe "committee"

            // The N=4 multi-role-FK-collision case this domain was specifically flagged for in
            // the retrofit plan: all three real member-FKs on this table (called_by,
            // chair_member_id, minute_taker_member_id) are modelled as plain «Column» attributes,
            // NOT UML associations — see the .kuml.kts file header comment for the empirical
            // finding (the FIRST association processed for a (fkClass, refClass) pair always
            // claims the bare "member_id" default regardless of its own role, so no ordering of
            // these three real column names could all be reproduced via real associations) —
            // pinned instead via «Column».fkEntity.
            real.foreignKeys["called_by"] shouldBe "member"
            real.foreignKeys["chair_member_id"] shouldBe "member"
            real.foreignKeys["minute_taker_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("called_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            model.entityNameOf(entity.attributeByName("chair_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            model.entityNameOf(
                entity.attributeByName("minute_taker_member_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "member"

            // protocol_document_id -> document: plain «Column» attribute (naming-gap: derived
            // default would be "document_id", not "protocol_document_id") — pinned instead via
            // «Column».fkEntity against this file's Document stub.
            real.foreignKeys["protocol_document_id"] shouldBe "document"
            model.entityNameOf(
                entity.attributeByName("protocol_document_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "document"
        }

        test("agenda_item table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "agenda_item" }
            val real = transaction { introspectGovernanceTable("agenda_item") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["meeting_id"] shouldBe "meeting"
            model.entityNameOf(entity.attributeByName("meeting_id")?.foreignKey?.targetEntityId ?: "") shouldBe "meeting"

            // presenter_member_id -> member: plain «Column» attribute (default would be
            // "member_id", not "presenter_member_id") — pinned instead via «Column».fkEntity.
            real.foreignKeys["presenter_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("presenter_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("agenda_item's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_agenda_item_position UNIQUE (meeting_id, position) — pinned via a
            // class-level «Index» (composite, unique=true), same mechanism as contribution's/
            // document's/communication's own composite UNIQUE constraints.
            val real = transaction { introspectGovernanceTable("agenda_item") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("meeting_id", "position"))

            val entity = model.entities.single { it.name == "agenda_item" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_agenda_item_position" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("meeting_id")!!.id,
                        entity.attributeByName("position")!!.id,
                    )
            }
        }

        test("attendance table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "attendance" }
            val real = transaction { introspectGovernanceTable("attendance") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["meeting_id"] shouldBe "meeting"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("meeting_id")?.foreignKey?.targetEntityId ?: "") shouldBe "meeting"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // represented_by_member_id -> member: plain «Column» attribute (default "member_id"
            // already claimed by the real member_id association above) — pinned instead via
            // «Column».fkEntity.
            real.foreignKeys["represented_by_member_id"] shouldBe "member"
            model.entityNameOf(
                entity.attributeByName("represented_by_member_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "member"
        }

        test("attendance's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_attendance_member UNIQUE (meeting_id, member_id) — pinned via a class-level
            // «Index» (composite, unique=true).
            val real = transaction { introspectGovernanceTable("attendance") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("meeting_id", "member_id"))

            val entity = model.entities.single { it.name == "attendance" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_attendance_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("meeting_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                    )
            }
        }

        test("resolution table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "resolution" }
            val real = transaction { introspectGovernanceTable("resolution") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["meeting_id"] shouldBe "meeting"
            real.foreignKeys["agenda_item_id"] shouldBe "agenda_item"
            model.entityNameOf(entity.attributeByName("meeting_id")?.foreignKey?.targetEntityId ?: "") shouldBe "meeting"
            model.entityNameOf(
                entity.attributeByName("agenda_item_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "agenda_item"

            // recorded_by -> member: plain «Column» attribute (default would be "member_id", not
            // "recorded_by") — pinned instead via «Column».fkEntity.
            real.foreignKeys["recorded_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("recorded_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // vote_id / election_id: forward references into the later vote/election waves,
            // modelled as plain nullable UUID «Column» attributes rather than pinned via
            // «Column».fkEntity — resolution<->vote and resolution<->election are both genuinely
            // bidirectional, so the real risk is Kotlin `object`-initializer circularity at the
            // Exposed layer, same reasoning as document.current_version_id. Since the SQL/Flyway
            // baseline is now generated from this same model, the real schema (unlike the
            // pre-swap hand-written V6/V8/V9 migrations) consequently has no FK here either — a
            // deliberate, pre-existing trade-off, not a new regression.
            real.foreignKeys["vote_id"] shouldBe null
            real.foreignKeys["election_id"] shouldBe null
            entity.attributeByName("vote_id")?.foreignKey shouldBe null
            entity.attributeByName("election_id")?.foreignKey shouldBe null
        }

        test("motion table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "motion" }
            val real = transaction { introspectGovernanceTable("motion") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // meeting_id/agenda_item_id/resolution_id all match the association-derived
            // default and are real UML associations.
            real.foreignKeys["meeting_id"] shouldBe "meeting"
            real.foreignKeys["agenda_item_id"] shouldBe "agenda_item"
            real.foreignKeys["resolution_id"] shouldBe "resolution"
            model.entityNameOf(entity.attributeByName("meeting_id")?.foreignKey?.targetEntityId ?: "") shouldBe "meeting"
            model.entityNameOf(
                entity.attributeByName("agenda_item_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "agenda_item"
            model.entityNameOf(entity.attributeByName("resolution_id")?.foreignKey?.targetEntityId ?: "") shouldBe "resolution"

            // target_committee_id / submitter_member_id / reviewed_by: plain «Column» attributes —
            // none match the association-derived default ("committee_id" / "member_id" / "member_id")
            // — pinned instead via «Column».fkEntity.
            real.foreignKeys["target_committee_id"] shouldBe "committee"
            real.foreignKeys["submitter_member_id"] shouldBe "member"
            real.foreignKeys["reviewed_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("target_committee_id")?.foreignKey?.targetEntityId ?: "") shouldBe "committee"
            model.entityNameOf(
                entity.attributeByName("submitter_member_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "member"
            model.entityNameOf(entity.attributeByName("reviewed_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("committee entity column-name set matches the hand-written CommitteeTable 1:1") {
            model.entities
                .single { it.name == "committee" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder CommitteeTable.columns.map { it.name }
        }

        test("committee_membership entity column-name set matches the hand-written CommitteeMembershipTable 1:1") {
            model.entities
                .single { it.name == "committee_membership" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder CommitteeMembershipTable.columns.map { it.name }
        }

        test("meeting entity column-name set matches the hand-written MeetingTable 1:1") {
            model.entities
                .single { it.name == "meeting" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MeetingTable.columns.map { it.name }
        }

        test("agenda_item entity column-name set matches the hand-written AgendaItemTable 1:1") {
            model.entities
                .single { it.name == "agenda_item" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AgendaItemTable.columns.map { it.name }
        }

        test("attendance entity column-name set matches the hand-written AttendanceTable 1:1") {
            model.entities
                .single { it.name == "attendance" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AttendanceTable.columns.map { it.name }
        }

        test("resolution entity column-name set matches the hand-written ResolutionTable 1:1") {
            model.entities
                .single { it.name == "resolution" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ResolutionTable.columns.map { it.name }
        }

        test("motion entity column-name set matches the hand-written MotionTable 1:1") {
            model.entities
                .single { it.name == "motion" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MotionTable.columns.map { it.name }
        }

        // ── (3) Enum-fidelity gap closure ────────────────────────────────────────

        test("committee.type is modelled as a real ErmDataType.Enum column") {
            // Same gap-closure as all prior domains' enum columns — with the «Column».sqlType
            // override removed, kUML's enum-to-Enum+CHECK fallback path applies.
            val type = model.entities.single { it.name == "committee" }.attributeByName("type")
            type?.type shouldBe
                ErmDataType.Enum(
                    name = "CommitteeType",
                    values = listOf("EXECUTIVE_BOARD", "WORKING_GROUP", "COMMISSION", "OTHER", "GENERAL_ASSEMBLY"),
                    externalFqName = "network.lapis.cloud.shared.domain.CommitteeType",
                )
        }

        test("committee_membership.role is modelled as a real ErmDataType.Enum column") {
            val role = model.entities.single { it.name == "committee_membership" }.attributeByName("role")
            role?.type shouldBe
                ErmDataType.Enum(
                    name = "CommitteeRole",
                    values = listOf("CHAIR", "DEPUTY_CHAIR", "SECRETARY", "MEMBER", "ASSESSOR"),
                    externalFqName = "network.lapis.cloud.shared.domain.CommitteeRole",
                )
        }

        test("meeting.format/status are modelled as real ErmDataType.Enum columns") {
            val format = model.entities.single { it.name == "meeting" }.attributeByName("format")
            val status = model.entities.single { it.name == "meeting" }.attributeByName("status")
            format?.type shouldBe
                ErmDataType.Enum(
                    name = "MeetingFormat",
                    values = listOf("IN_PERSON", "ONLINE", "HYBRID"),
                    externalFqName = "network.lapis.cloud.shared.domain.MeetingFormat",
                )
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "MeetingStatus",
                    values = listOf("PLANNED", "HELD", "CANCELLED"),
                    externalFqName = "network.lapis.cloud.shared.domain.MeetingStatus",
                )
        }

        test("attendance.status is modelled as a real ErmDataType.Enum column") {
            val status = model.entities.single { it.name == "attendance" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "AttendanceStatus",
                    values = listOf("PRESENT", "EXCUSED", "UNEXCUSED", "REPRESENTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.AttendanceStatus",
                )
        }

        test("resolution.status/resolution_mode are modelled as real ErmDataType.Enum columns") {
            val status = model.entities.single { it.name == "resolution" }.attributeByName("status")
            val resolutionMode = model.entities.single { it.name == "resolution" }.attributeByName("resolution_mode")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "ResolutionStatus",
                    values = listOf("ADOPTED", "REJECTED", "POSTPONED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ResolutionStatus",
                )
            resolutionMode?.type shouldBe
                ErmDataType.Enum(
                    name = "ResolutionMode",
                    values = listOf("COMMITTEE_QUORUM", "MERITOCRATIC", "DEMOCRATIC", "SYSTEMIC_CONSENSUS"),
                    externalFqName = "network.lapis.cloud.shared.domain.ResolutionMode",
                )
        }

        test("motion.status is modelled as a real ErmDataType.Enum column") {
            val status = model.entities.single { it.name == "motion" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "MotionStatus",
                    values =
                        listOf(
                            "SUBMITTED",
                            "REVIEWED",
                            "REJECTED_PRELIMINARY",
                            "SCHEDULED",
                            "RESOLVED",
                            "REJECTED",
                            "POSTPONED",
                            "WITHDRAWN",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.MotionStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedGovernanceTable(
    val columns: Map<String, IntrospectedGovernanceColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedGovernanceColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [CommunicationSchemaDriftTest]'s (private,
 * communication-domain-scoped) `introspectCommunicationTable`.
 */
private fun JdbcTransaction.introspectGovernanceTable(tableName: String): IntrospectedGovernanceTable {
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
            IntrospectedGovernanceColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedGovernanceTable(
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
