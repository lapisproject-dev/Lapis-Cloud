package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.274 -- `V140__stream_key_per_broadcast.sql` on the upgrade path V139 -> V140, on H2 AND on PostgreSQL: an existing destination becomes
 * PERSISTENT and keeps its key, the key columns accept NULL, `key_kind` and `viewer_url` are guarded by exactly one named CHECK each, the new target
 * columns are nullable, and a re-run is a no-op. The key values are dummy ciphertext.
 */
abstract class ConferenceStreamKeyPerBroadcastMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rows(sql: String): List<List<String>> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val memberId = UUID.randomUUID()
        val legacyId = UUID.randomUUID()
        val streamId = UUID.randomUUID()
        val roomId = UUID.randomUUID()
        val legacyTargetId = UUID.randomUUID()

        fun insertDestination(
            id: UUID,
            kind: String?,
            key: String?,
        ) = "INSERT INTO conference_stream_destination (id, label, platform, rtmp_url, ${if (kind != null) "key_kind, " else ""}" +
            "stream_key_ciphertext, stream_key_set_at, created_by_member_id, created_at, enabled) " +
            "VALUES ('$id', 'dest-$id', 'GENERIC_RTMP', " +
            "'rtmps://ingest.example.org/live', ${if (kind != null) "'$kind', " else ""}${if (key != null) "'$key'" else "NULL"}, " +
            "${if (key != null) "TIMESTAMP '2026-10-14 09:00:00'" else "NULL"}, '$memberId', TIMESTAMP '2026-10-14 09:00:00', TRUE)"

        fun insertTarget(
            id: UUID,
            destination: UUID,
            viewerUrl: String?,
        ) = "INSERT INTO conference_stream_target (id, stream_id, destination_id, status, url_fingerprint, retries, viewer_url) " +
            "VALUES ('$id', '$streamId', '$destination', 'PENDING', 'fp-$id', 0, ${if (viewerUrl != null) "'$viewerUrl'" else "NULL"})"

        beforeSpec {
            h = newHarness()
            h.flyway("139").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'x-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            // V139 shape: no key_kind column yet.
            exec(
                "INSERT INTO conference_stream_destination (id, label, platform, rtmp_url, stream_key_ciphertext, stream_key_set_at, " +
                    "created_by_member_id, created_at, enabled) VALUES ('$legacyId', 'dest-$legacyId', 'GENERIC_RTMP', " +
                    "'rtmps://ingest.example.org/live', 'dummy-ciphertext', TIMESTAMP '2026-10-14 09:00:00', '$memberId', " +
                    "TIMESTAMP '2026-10-14 09:00:00', TRUE)",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-14 09:00:00', 25)",
            )
            exec(
                "INSERT INTO conference_stream (id, room_id, started_by_member_id, status, layout, latency_mode, started_at, " +
                    "restart_count) " +
                    "VALUES ('$streamId', '$roomId', '$memberId', 'ENDED', 'GRID', 'STANDARD', TIMESTAMP '2026-10-14 09:00:00', 0)",
            )
            exec(
                "INSERT INTO conference_stream_target (id, stream_id, destination_id, status, url_fingerprint, retries) " +
                    "VALUES ('$legacyTargetId', '$streamId', '$legacyId', 'FINISHED', 'fp', 0)",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing destination becomes PERSISTENT and keeps its key") {
            rows("SELECT key_kind, stream_key_ciphertext FROM conference_stream_destination WHERE id = '$legacyId'").single() shouldBe
                listOf("PERSISTENT", "dummy-ciphertext")
        }

        test("an existing target has no broadcast key and no viewer address") {
            rows("SELECT broadcast_key_ciphertext, viewer_url FROM conference_stream_target WHERE id = '$legacyTargetId'").single() shouldBe
                listOf("null", "null")
        }

        test("a per-broadcast destination without any key is accepted") {
            val id = UUID.randomUUID()
            exec(insertDestination(id, "PER_BROADCAST", null))
            rows(
                "SELECT key_kind, stream_key_ciphertext, stream_key_set_at FROM conference_stream_destination WHERE id = '$id'",
            ).single() shouldBe
                listOf("PER_BROADCAST", "null", "null")
        }

        test(
            "a persistent destination with a NULL key is accepted too (a restored backup) -- the service, not a CHECK, enforces the rule",
        ) {
            exec(insertDestination(UUID.randomUUID(), "PERSISTENT", null))
        }

        test("an unknown key kind is rejected") {
            (rejection(insertDestination(UUID.randomUUID(), "FOO", "k")) != null) shouldBe true
            (rejection(insertDestination(UUID.randomUUID(), "persistent", "k")) != null) shouldBe true
        }

        test("the viewer address must be https and at most 512 characters") {
            val dest = UUID.randomUUID()
            exec(insertDestination(dest, "PERSISTENT", "k"))
            exec(insertTarget(UUID.randomUUID(), dest, "https://www.youtube.com/live_chat?is_popout=1&v=abcdefghijk"))
            exec(insertTarget(UUID.randomUUID(), dest, null))
            (rejection(insertTarget(UUID.randomUUID(), dest, "http://example.org/x")) != null) shouldBe true
            (rejection(insertTarget(UUID.randomUUID(), dest, "javascript:alert(1)")) != null) shouldBe true
            exec(insertTarget(UUID.randomUUID(), dest, "https://example.org/" + "a".repeat(512 - "https://example.org/".length)))
            (
                rejection(
                    insertTarget(UUID.randomUUID(), dest, "https://example.org/" + "a".repeat(513 - "https://example.org/".length)),
                ) !=
                    null
            ) shouldBe
                true
        }

        test("exactly one CHECK guards each new column, and it is the named one") {
            // PostgreSQL 17 lists NOT NULL constraints as CHECKs ("... IS NOT NULL") -- those are excluded in the query.
            fun checksOn(
                table: String,
                column: String,
            ) = rows(
                "SELECT tc.constraint_name FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.check_constraints cc ON cc.constraint_name = tc.constraint_name " +
                    "AND cc.constraint_schema = tc.constraint_schema " +
                    "WHERE LOWER(tc.table_name) = '$table' AND tc.constraint_type = 'CHECK' " +
                    "AND LOWER(cc.check_clause) LIKE '%$column%' AND LOWER(cc.check_clause) NOT LIKE '%is not null%'",
            ).map { it.single() }
            checksOn("conference_stream_destination", "key_kind") shouldBe listOf("chk_conference_stream_destination_key_kind")
            checksOn("conference_stream_target", "viewer_url") shouldBe listOf("chk_conference_stream_target_viewer_url")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceStreamKeyPerBroadcastMigrationTest : ConferenceStreamKeyPerBroadcastMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceStreamKeyPerBroadcastMigrationPostgresTest :
    ConferenceStreamKeyPerBroadcastMigrationScenarios({
        PostgresMigrationHarness()
    })
