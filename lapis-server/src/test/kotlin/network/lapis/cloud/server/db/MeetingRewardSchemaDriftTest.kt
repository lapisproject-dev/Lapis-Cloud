package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MeetingRewardCommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyChangeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- LTR recognition for Sitzungen (V1.9.316). Verifies that `83-meeting-reward.kuml.kts` is a faithful model of the
 * real, Flyway-migrated H2 schema (V164) and of the Exposed objects, column for column: names, nullability, foreign keys, primary key, unique indexes.
 */
class MeetingRewardSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "83-meeting-reward.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        fun check(
            table: String,
            exposed: List<String>,
            primaryKey: Set<String> = setOf("id"),
            unique: List<Set<String>> = emptyList(),
        ) {
            val entity = model.entities.single { it.name == table }
            val real = transaction { introspectRewardTable(table) }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of $table") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposed
            real.primaryKeyColumns shouldBe primaryKey
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe real.foreignKeys
            real.uniqueConstraints shouldContainExactlyInAnyOrder unique
        }

        test(
            "the model declares the five reward entities plus the Member, Meeting, Committee, Resolution, ProtocolVersion and LtrLedgerEntry stubs",
        ) {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "meeting",
                    "committee",
                    "resolution",
                    "protocol_version",
                    "ltr_ledger_entry",
                    "meeting_reward_policy",
                    "meeting_reward_policy_change",
                    "meeting_reward_committee",
                    "meeting_reward_settlement",
                    "meeting_reward_line",
                )
        }

        test(
            "meeting_reward_policy matches the migrated schema and MeetingRewardPolicyTable 1:1; no foreign key on the two change pointers",
        ) {
            check("meeting_reward_policy", MeetingRewardPolicyTable.columns.map { it.name })
            val entity = model.entities.single { it.name == "meeting_reward_policy" }
            entity.attributeByName("last_change_id")?.foreignKey shouldBe null
            entity.attributeByName("open_change_id")?.foreignKey shouldBe null
            entity.attributeByName("cap_per_person_per_year")?.type shouldBe ErmDataType.Decimal(12, 2)
            entity.attributeByName("attendance_amount")?.type shouldBe ErmDataType.Decimal(9, 2)
        }

        test("meeting_reward_policy_change matches the migrated schema and its Exposed object 1:1") {
            check("meeting_reward_policy_change", MeetingRewardPolicyChangeTable.columns.map { it.name })
            val entity = model.entities.single { it.name == "meeting_reward_policy_change" }
            // sized by an explicit sqlType, so the model type is a Varchar; the Kotlin type is the enum
            entity.attributeByName("kind")?.type shouldBe ErmDataType.Varchar(length = 7)
            entity.attributeByName("new_attendance_amount")?.type shouldBe ErmDataType.Decimal(9, 2)
        }

        test("meeting_reward_committee matches the migrated schema and its Exposed object 1:1; the committee is the primary key") {
            check("meeting_reward_committee", MeetingRewardCommitteeTable.columns.map { it.name }, primaryKey = setOf("committee_id"))
        }

        test("meeting_reward_settlement matches the migrated schema 1:1; one settlement per meeting") {
            check(
                "meeting_reward_settlement",
                MeetingRewardSettlementTable.columns.map { it.name },
                unique = listOf(setOf("meeting_id")),
            )
        }

        test("meeting_reward_line matches the migrated schema 1:1; one line per meeting and member, per key and per ledger row") {
            check(
                "meeting_reward_line",
                MeetingRewardLineTable.columns.map { it.name },
                unique = listOf(setOf("meeting_id", "member_id"), setOf("idempotency_key"), setOf("ledger_entry_id")),
            )
            val entity = model.entities.single { it.name == "meeting_reward_line" }
            entity.attributeByName("ledger_entry_id")?.nullable shouldBe true
            entity.attributeByName("reason")?.type shouldBe ErmDataType.Varchar(length = 19)
            entity.attributeByName("reason")?.nullable shouldBe true
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedRewardTable(
    val columns: Map<String, IntrospectedRewardColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedRewardColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectRewardTable(tableName: String): IntrospectedRewardTable {
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

    val pkColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            pkColumns += rs.getString("column_name")
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedRewardColumn(nullable = nullable) }
    return IntrospectedRewardTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
