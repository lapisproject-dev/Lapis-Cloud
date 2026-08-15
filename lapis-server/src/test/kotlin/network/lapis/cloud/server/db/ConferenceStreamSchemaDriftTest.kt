package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Videokonferenzen domain (V1.0, Wave 3 "Externes
 * Streaming"). Verifies that `lapis-server/src/main/kuml/29-conference-streaming.kuml.kts` is a
 * faithful model of both (a) the real, Flyway-migrated H2 schema
 * (`conference_stream_destination`/`conference_stream`/`conference_stream_target`), and (b) the
 * hand-written [ConferenceStreamDestinationTable]/[ConferenceStreamTable]/
 * [ConferenceStreamTargetTable] Exposed objects. Mirrors [ConferenceRecordingSchemaDriftTest]'s
 * shape, WITHOUT a third stub entity for Document -- unlike recording, streaming never produces a
 * Dokumentenablage row, so the only cross-domain stubs this wave's model needs are Member and
 * ConferenceRoom (same two `28-conference-recording.kuml.kts` already establishes).
 */
class ConferenceStreamSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "29-conference-streaming.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the three streaming entities plus the Member/ConferenceRoom stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "conference_room",
                    "conference_stream_destination",
                    "conference_stream",
                    "conference_stream_target",
                )
        }

        // ── conference_stream_destination ───────────────────────────────────

        test("conference_stream_destination table shape matches the real migrated schema and ConferenceStreamDestinationTable 1:1") {
            val entity = model.entities.single { it.name == "conference_stream_destination" }
            val real = transaction { introspectConferenceStreamTable("conference_stream_destination") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                ConferenceStreamDestinationTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["created_by_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("created_by_member_id")?.nullable shouldBe false

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("label"))

            entity.attributeByName("label")?.nullable shouldBe false
            entity.attributeByName("rtmp_url")?.nullable shouldBe false
            entity.attributeByName("stream_key_ciphertext")?.nullable shouldBe false
            entity.attributeByName("stream_key_set_at")?.nullable shouldBe false
            entity.attributeByName("created_at")?.nullable shouldBe false
            entity.attributeByName("enabled")?.nullable shouldBe false

            // Literal order is load-bearing -- see 29-conference-streaming.kuml.kts file header.
            entity.attributeByName("platform")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceStreamPlatform",
                    values = listOf("YOUTUBE", "TWITCH", "PEERTUBE", "OWNCAST", "GENERIC_RTMP"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceStreamPlatform",
                )
        }

        // ── conference_stream ────────────────────────────────────────────────

        test("conference_stream table shape matches the real migrated schema and ConferenceStreamTable 1:1") {
            val entity = model.entities.single { it.name == "conference_stream" }
            val real = transaction { introspectConferenceStreamTable("conference_stream") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceStreamTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")

            real.foreignKeys["room_id"] shouldBe "conference_room"
            model.entityNameOf(entity.attributeByName("room_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_room"
            entity.attributeByName("room_id")?.nullable shouldBe false

            real.foreignKeys["started_by_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("started_by_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("started_by_member_id")?.nullable shouldBe false

            entity.attributeByName("participant_identity")?.nullable shouldBe true
            entity.attributeByName("livekit_egress_id")?.nullable shouldBe true
            entity.attributeByName("started_at")?.nullable shouldBe false
            entity.attributeByName("paused_at")?.nullable shouldBe true
            entity.attributeByName("ended_at")?.nullable shouldBe true
            entity.attributeByName("restart_count")?.nullable shouldBe false
            entity.attributeByName("failure_reason")?.nullable shouldBe true
            // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- null unless
            // status is PAUSING/PAUSED, see ConferenceStreamTable.pauseReason KDoc.
            entity.attributeByName("pause_reason")?.nullable shouldBe true

            // Literal order is load-bearing -- see 29-conference-streaming.kuml.kts file header.
            // PAUSING (Wave 9) is APPENDED at the end, not reordered next to LIVE/PAUSED -- see
            // ConferenceStreamStatus KDoc.
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceStreamStatus",
                    values = listOf("STARTING", "LIVE", "PAUSED", "STOPPING", "ENDED", "FAILED", "PAUSING"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceStreamStatus",
                )
            // V1.0 Videokonferenzen, Wave 9 addition -- MANUAL vs SECRET_BALLOT, see
            // ConferenceStreamPauseReason KDoc.
            entity.attributeByName("pause_reason")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceStreamPauseReason",
                    values = listOf("MANUAL", "SECRET_BALLOT"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceStreamPauseReason",
                )
            entity.attributeByName("layout")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceStreamLayout",
                    values = listOf("GRID", "SPEAKER", "SINGLE_PARTICIPANT"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceStreamLayout",
                )
            entity.attributeByName("latency_mode")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceStreamLatencyMode",
                    values = listOf("LOW_LATENCY", "STANDARD"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode",
                )
        }

        // ── conference_stream_target ────────────────────────────────────────

        test("conference_stream_target table shape matches the real migrated schema and ConferenceStreamTargetTable 1:1") {
            val entity = model.entities.single { it.name == "conference_stream_target" }
            val real = transaction { introspectConferenceStreamTable("conference_stream_target") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                ConferenceStreamTargetTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")

            real.foreignKeys["stream_id"] shouldBe "conference_stream"
            model.entityNameOf(entity.attributeByName("stream_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_stream"
            entity.attributeByName("stream_id")?.nullable shouldBe false

            real.foreignKeys["destination_id"] shouldBe "conference_stream_destination"
            model.entityNameOf(entity.attributeByName("destination_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "conference_stream_destination"
            entity.attributeByName("destination_id")?.nullable shouldBe false

            entity.attributeByName("url_fingerprint")?.nullable shouldBe false
            entity.attributeByName("started_at_epoch_nanos")?.nullable shouldBe true
            entity.attributeByName("ended_at_epoch_nanos")?.nullable shouldBe true
            entity.attributeByName("retries")?.nullable shouldBe false
            entity.attributeByName("failure_reason")?.nullable shouldBe true

            // Literal order is load-bearing -- see 29-conference-streaming.kuml.kts file header.
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceStreamTargetStatus",
                    values = listOf("PENDING", "ACTIVE", "FINISHED", "FAILED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [ConferenceRecordingSchemaDriftTest]'s own private helper. */
private data class IntrospectedConferenceStreamTable(
    val columns: Map<String, IntrospectedConferenceStreamColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedConferenceStreamColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectConferenceStreamTable(tableName: String): IntrospectedConferenceStreamTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedConferenceStreamColumn(nullable = nullable) }
    return IntrospectedConferenceStreamTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors ConferenceRecordingSchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
