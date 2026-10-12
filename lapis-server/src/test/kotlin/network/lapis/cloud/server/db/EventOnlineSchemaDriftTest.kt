package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.113 "Online-Teilnahme an Veranstaltungen". Verifies that
 * `lapis-server/src/main/kuml/67-event-online.kuml.kts` (the new `event_conference` table) and the V1.9.113 addendum of
 * `39-events.kuml.kts` (`event.online_kind`/`event.encounter_space_id`) are faithful models of both (a) the real, Flyway-migrated H2 schema
 * (`V93__event_online_participation.sql`) and (b) the hand-written Exposed objects. Mirrors [EventRoomSchemaDriftTest].
 */
class EventOnlineSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val conferenceModel: ErmModel by lazy {
            KumlModelLoader.loadErmModel(
                File(KumlModelLoader.kumlSourceDir, "67-event-online.kuml.kts"),
            )
        }
        val eventModel: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "39-events.kuml.kts")) }

        test("the model declares exactly event_conference and its event/conference_room stubs") {
            conferenceModel.entities.map { it.name }.toSet() shouldBe setOf("event_conference", "event", "conference_room")
        }

        test("event_conference table shape matches the real migrated schema (columns, nullability, foreign keys)") {
            val entity = conferenceModel.entities.single { it.name == "event_conference" }
            val real = transaction { introspectEventOnlineTable("event_conference") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["event_id"] shouldBe "event"
            real.foreignKeys["conference_room_id"] shouldBe "conference_room"
        }

        test("event_conference entity column-name set matches the hand-written EventConferenceTable 1:1") {
            conferenceModel.entities
                .single { it.name == "event_conference" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder EventConferenceTable.columns.map { it.name }
        }

        test("the conference room of an event is unique (one conference per room)") {
            val real = transaction { introspectEventOnlineTable("event_conference") }
            real.uniqueConstraints.any { it == setOf("conference_room_id") } shouldBe true
        }

        test("event.online_kind is NOT NULL and event.encounter_space_id is a nullable foreign key to encounter_space") {
            val real = transaction { introspectEventOnlineTable("event") }
            real.columns.getValue("online_kind").nullable shouldBe false
            real.columns.getValue("encounter_space_id").nullable shouldBe true
            real.foreignKeys["encounter_space_id"] shouldBe "encounter_space"
            EventTable.onlineKind.name shouldBe "online_kind"
            EventTable.encounterSpaceId.name shouldBe "encounter_space_id"
        }

        test("the V1.9.113 addendum of the events model carries both new attributes with the right nullability") {
            val event = eventModel.entities.single { it.name == "event" }
            val kind = event.attributes.single { it.name == "online_kind" }
            val space = event.attributes.single { it.name == "encounter_space_id" }
            kind.nullable shouldBe false
            space.nullable shouldBe true
        }
    })

private data class IntrospectedEventOnlineTable(
    val columns: Map<String, IntrospectedEventOnlineColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedEventOnlineColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [BankAccountSchemaDriftTest]'s own `introspectBankAccountTable`. */
private fun JdbcTransaction.introspectEventOnlineTable(tableName: String): IntrospectedEventOnlineTable {
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
            uniqueColumnsByConstraint.getOrPut(rs.getString("name")) { mutableSetOf() }.add(rs.getString("column_name"))
        }
    }

    return IntrospectedEventOnlineTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedEventOnlineColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [BankAccountSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
