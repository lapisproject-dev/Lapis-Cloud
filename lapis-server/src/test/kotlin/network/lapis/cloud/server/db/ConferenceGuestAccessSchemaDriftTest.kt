package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Videokonferenzen domain (V1.0, Wave 5
 * "Föderations-Gastbeitritt").
 *
 * Verifies that `lapis-server/src/main/kuml/30-conference-guest-access.kuml.kts` is a faithful
 * model of both (a) the real, Flyway-migrated H2 schema
 * (`conference_guest_consent_acknowledgment`), and (b) the hand-written
 * [ConferenceGuestConsentAcknowledgmentTable] Exposed object. Mirrors [ConferenceSchemaDriftTest]'s
 * shape, with TWO cross-domain stubs (Member, ConferenceRoom -- no Document stub, unlike
 * [ConferenceRecordingSchemaDriftTest], since this table never produces a Dokumentenablage row).
 */
class ConferenceGuestAccessSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "30-conference-guest-access.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the one guest-access entity plus the Member/ConferenceRoom stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "conference_room", "conference_guest_consent_acknowledgment")
        }

        test(
            "conference_guest_consent_acknowledgment table shape matches the real migrated schema " +
                "and ConferenceGuestConsentAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "conference_guest_consent_acknowledgment" }
            val real = transaction { introspectGuestAccessTable("conference_guest_consent_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                ConferenceGuestConsentAcknowledgmentTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            real.foreignKeys["room_id"] shouldBe "conference_room"
            model.entityNameOf(entity.attributeByName("room_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_room"
            entity.attributeByName("room_id")?.nullable shouldBe false

            entity.attributeByName("acknowledged_at")?.nullable shouldBe false
            entity.attributeByName("consent_version")?.nullable shouldBe false
            entity.attributeByName("consent_sha256")?.nullable shouldBe false
            // V0.11.0: made nullable -- a FRIEND has no federated home server, see
            // 30-conference-guest-access.kuml.kts's own comment on this attribute.
            entity.attributeByName("homeserver_url")?.nullable shouldBe true
            entity.attributeByName("organization_name")?.nullable shouldBe false
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [ConferenceSchemaDriftTest]'s own private helper. */
private data class IntrospectedGuestAccessTable(
    val columns: Map<String, IntrospectedGuestAccessColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedGuestAccessColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectGuestAccessTable(tableName: String): IntrospectedGuestAccessTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedGuestAccessColumn(nullable = nullable) }
    return IntrospectedGuestAccessTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors ConferenceSchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
