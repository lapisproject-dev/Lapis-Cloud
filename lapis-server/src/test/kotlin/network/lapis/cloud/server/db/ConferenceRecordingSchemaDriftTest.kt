package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ConferenceRecordingSpeakerSpanTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Videokonferenzen domain (V1.0, Wave 2 "Aufzeichnung").
 *
 * Verifies that `lapis-server/src/main/kuml/28-conference-recording.kuml.kts` is a faithful model
 * of both (a) the real, Flyway-migrated H2 schema (`conference_recording`/
 * `conference_recording_track`), and (b) the hand-written [ConferenceRecordingTable]/
 * [ConferenceRecordingTrackTable] Exposed objects. Mirrors [ConferenceSchemaDriftTest]'s shape,
 * extended for a THIRD stub entity (Document, alongside Member/ConferenceRoom) since
 * `conference_recording.document_id` is a nullable FK into a domain owned by
 * `02-document.kuml.kts`.
 */
class ConferenceRecordingSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "28-conference-recording.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the three recording entities plus the Member/ConferenceRoom/Document stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "conference_room",
                    "document",
                    "conference_recording",
                    "conference_recording_track",
                    "conference_recording_speaker_span",
                )
        }

        // ── conference_recording ────────────────────────────────────────────

        test("conference_recording table shape matches the real migrated schema and ConferenceRecordingTable 1:1") {
            val entity = model.entities.single { it.name == "conference_recording" }
            val real = transaction { introspectConferenceRecordingTable("conference_recording") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceRecordingTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["room_id"] shouldBe "conference_room"
            model.entityNameOf(entity.attributeByName("room_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_room"
            entity.attributeByName("room_id")?.nullable shouldBe false

            real.foreignKeys["started_by_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("started_by_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("started_by_member_id")?.nullable shouldBe false

            real.foreignKeys["document_id"] shouldBe "document"
            model.entityNameOf(entity.attributeByName("document_id")?.foreignKey?.targetEntityId ?: "") shouldBe "document"
            entity.attributeByName("document_id")?.nullable shouldBe true

            entity.attributeByName("stopped_at")?.nullable shouldBe true
            entity.attributeByName("ready_at")?.nullable shouldBe true
            entity.attributeByName("duration_seconds")?.nullable shouldBe true
            entity.attributeByName("file_size_bytes")?.nullable shouldBe true
            entity.attributeByName("failure_reason")?.nullable shouldBe true
            entity.attributeByName("raw_dir")?.nullable shouldBe false
            entity.attributeByName("compose_attempts")?.nullable shouldBe false
            // V1.9.107 (V87): failure code and manual-retry bookkeeping.
            entity.attributeByName("failure_code")?.nullable shouldBe true
            entity.attributeByName("manual_retry_count")?.nullable shouldBe false
            entity.attributeByName("manual_retry_window_started_at")?.nullable shouldBe true
            entity.attributeByName("last_manual_retry_at")?.nullable shouldBe true
            // V1.9.109 (V89): recompute of a READY recording.
            entity.attributeByName("recompute_started_at")?.nullable shouldBe true
            entity.attributeByName("recomputed_at")?.nullable shouldBe true
            entity.attributeByName("last_recompute_failure_code")?.nullable shouldBe true
            // V1.9.110 (V90): the egress mode a recording runs in; literal order is load-bearing.
            entity.attributeByName("egress_mode")?.nullable shouldBe false
            entity.attributeByName("egress_mode")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRecordingEgressMode",
                    values = listOf("TRACK", "PARTICIPANT"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRecordingEgressMode",
                )
            // V1.9.115 (V94): the view frozen at the start and the speaker-timeline marker.
            entity.attributeByName("broadcast_view")?.nullable shouldBe false
            entity.attributeByName("broadcast_view")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceBroadcastView",
                    values = listOf("RASTER", "SPEAKER"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceBroadcastView",
                )
            entity.attributeByName("speaker_timeline_computed")?.nullable shouldBe false
            entity.attributeByName("failure_code")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRecordingFailureCode",
                    values = listOf("COMPOSE_FAILED", "EGRESS_TIMEOUT", "NO_TRACKS", "NO_TRACK_STORED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode",
                )

            // Literal order is load-bearing -- see 28-conference-recording.kuml.kts file header.
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRecordingStatus",
                    values = listOf("RECORDING", "STOPPING", "PROCESSING", "READY", "FAILED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRecordingStatus",
                )
            entity.attributeByName("access_level")?.type shouldBe
                ErmDataType.Enum(
                    name = "DocumentAccessLevel",
                    values = listOf("PUBLIC_MEMBERS", "BOARD_ONLY", "ADMIN_ONLY"),
                    externalFqName = "network.lapis.cloud.shared.domain.DocumentAccessLevel",
                )
        }

        // ── conference_recording_track ──────────────────────────────────────

        test(
            "conference_recording_track table shape matches the real migrated schema and ConferenceRecordingTrackTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "conference_recording_track" }
            val real = transaction { introspectConferenceRecordingTable("conference_recording_track") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceRecordingTrackTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")

            real.foreignKeys["recording_id"] shouldBe "conference_recording"
            model.entityNameOf(entity.attributeByName("recording_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_recording"
            entity.attributeByName("recording_id")?.nullable shouldBe false

            entity.attributeByName("egress_id")?.nullable shouldBe false
            entity.attributeByName("livekit_track_id")?.nullable shouldBe false
            entity.attributeByName("participant_identity")?.nullable shouldBe false
            entity.attributeByName("started_at_epoch_nanos")?.nullable shouldBe true
            entity.attributeByName("ended_at_epoch_nanos")?.nullable shouldBe true
            entity.attributeByName("file_name")?.nullable shouldBe true
            entity.attributeByName("duration_ms")?.nullable shouldBe true
            entity.attributeByName("size_bytes")?.nullable shouldBe true
            // V1.9.110 (V90): which kind of egress produced the file.
            entity.attributeByName("egress_kind")?.nullable shouldBe false
            entity.attributeByName("egress_kind")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRecordingEgressMode",
                    values = listOf("TRACK", "PARTICIPANT"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRecordingEgressMode",
                )

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("egress_id"))

            // Literal order is load-bearing -- see 28-conference-recording.kuml.kts file header.
            entity.attributeByName("track_source")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRecordingTrackSource",
                    values = listOf("CAMERA", "MICROPHONE", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO", "UNKNOWN"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRecordingTrackStatus",
                    values = listOf("STARTING", "ACTIVE", "COMPLETE", "FAILED", "ABORTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus",
                )
        }

        // ── conference_recording_speaker_span (V1.9.115, V94) ───────────────

        test("conference_recording_speaker_span table shape matches the real migrated schema and its Exposed object 1:1") {
            val entity = model.entities.single { it.name == "conference_recording_speaker_span" }
            val real = transaction { introspectConferenceRecordingTable("conference_recording_speaker_span") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                ConferenceRecordingSpeakerSpanTable.columns.map { it.name }
            // no name, no identity: only the recording, the track row and an offset range
            entity.attributes.map { it.name }.toSet() shouldBe setOf("id", "recording_id", "track_id", "start_ms", "end_ms")
            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["recording_id"] shouldBe "conference_recording"
            real.foreignKeys["track_id"] shouldBe "conference_recording_track"
            model.entityNameOf(entity.attributeByName("recording_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_recording"
            model.entityNameOf(entity.attributeByName("track_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_recording_track"
            entity.attributeByName("start_ms")?.nullable shouldBe false
            entity.attributeByName("end_ms")?.nullable shouldBe false
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [ConferenceSchemaDriftTest]'s own private helper. */
private data class IntrospectedConferenceRecordingTable(
    val columns: Map<String, IntrospectedConferenceRecordingColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedConferenceRecordingColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectConferenceRecordingTable(tableName: String): IntrospectedConferenceRecordingTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedConferenceRecordingColumn(nullable = nullable) }
    return IntrospectedConferenceRecordingTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
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
