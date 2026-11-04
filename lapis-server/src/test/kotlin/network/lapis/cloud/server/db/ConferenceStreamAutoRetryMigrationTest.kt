package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.275 -- `V141__stream_target_auto_retry.sql` on the upgrade path V140 -> V141, on H2 AND on PostgreSQL: an existing target gets attempt 0
 * and no plan, `failure_kind` and `auto_retry_attempt` are guarded by one named CHECK each, the timestamps are nullable, and a re-run is a no-op.
 */
abstract class ConferenceStreamAutoRetryMigrationScenarios(
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
        val destId = UUID.randomUUID()
        val streamId = UUID.randomUUID()
        val roomId = UUID.randomUUID()
        val legacyTargetId = UUID.randomUUID()

        fun insertTarget(
            id: UUID,
            extra: String,
        ) = "INSERT INTO conference_stream_target (id, stream_id, destination_id, status, url_fingerprint, retries${extra.substringBefore(
            "|",
        )}) " +
            "VALUES ('$id', '$streamId', '$destId', 'PENDING', 'fp-$id', 0${extra.substringAfter("|")})"

        beforeSpec {
            h = newHarness()
            h.flyway("140").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'x-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_stream_destination (id, label, platform, rtmp_url, key_kind, stream_key_ciphertext, " +
                    "stream_key_set_at, created_by_member_id, created_at, enabled) VALUES ('$destId', 'dest-$destId', 'GENERIC_RTMP', " +
                    "'rtmps://ingest.example.org/live', 'PERSISTENT', 'dummy-ciphertext', TIMESTAMP '2026-10-14 09:00:00', '$memberId', " +
                    "TIMESTAMP '2026-10-14 09:00:00', TRUE)",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-14 09:00:00', 25)",
            )
            exec(
                "INSERT INTO conference_stream (id, room_id, started_by_member_id, status, layout, latency_mode, started_at, " +
                    "restart_count) " +
                    "VALUES ('$streamId', '$roomId', '$memberId', 'ENDED', 'GRID', 'STANDARD', TIMESTAMP '2026-10-14 09:00:00', 0)",
            )
            exec(
                "INSERT INTO conference_stream_target (id, stream_id, destination_id, status, url_fingerprint, retries) " +
                    "VALUES ('$legacyTargetId', '$streamId', '$destId', 'FINISHED', 'fp', 0)",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing target gets attempt 0 and no failure kind, no plan, no window") {
            rows(
                "SELECT failure_kind, auto_retry_attempt, auto_retry_next_at, auto_retry_issued_at, retry_window_started_at " +
                    "FROM conference_stream_target WHERE id = '$legacyTargetId'",
            ).single() shouldBe listOf("null", "0", "null", "null", "null")
        }

        test("the four failure kinds and attempts 0 to 3 are accepted") {
            listOf("REJECTED", "TIMEOUT", "CONNECTION", "UNKNOWN").forEachIndexed { n, kind ->
                exec(insertTarget(UUID.randomUUID(), ", failure_kind, auto_retry_attempt|, '$kind', $n"))
            }
        }

        test("an unknown failure kind and an attempt above 3 or below 0 are rejected") {
            (rejection(insertTarget(UUID.randomUUID(), ", failure_kind|, 'FOO'")) != null) shouldBe true
            (rejection(insertTarget(UUID.randomUUID(), ", failure_kind|, 'rejected'")) != null) shouldBe true
            (rejection(insertTarget(UUID.randomUUID(), ", auto_retry_attempt|, 4")) != null) shouldBe true
            (rejection(insertTarget(UUID.randomUUID(), ", auto_retry_attempt|, -1")) != null) shouldBe true
        }

        test("the timestamps accept values") {
            exec(
                insertTarget(
                    UUID.randomUUID(),
                    ", auto_retry_next_at, auto_retry_issued_at, retry_window_started_at" +
                        "|, TIMESTAMP '2026-11-03 10:00:00', TIMESTAMP '2026-11-03 10:00:01', TIMESTAMP '2026-11-03 09:59:00'",
                ),
            )
        }

        test("exactly one CHECK guards each new column, and it is the named one") {
            fun checksOn(column: String) =
                rows(
                    "SELECT tc.constraint_name FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.check_constraints cc ON cc.constraint_name = tc.constraint_name " +
                        "AND cc.constraint_schema = tc.constraint_schema " +
                        "WHERE LOWER(tc.table_name) = 'conference_stream_target' AND tc.constraint_type = 'CHECK' " +
                        "AND LOWER(cc.check_clause) LIKE '%$column%' AND LOWER(cc.check_clause) NOT LIKE '%is not null%'",
                ).map { it.single() }
            checksOn("failure_kind") shouldBe listOf("chk_conference_stream_target_failure_kind")
            checksOn("auto_retry_attempt") shouldBe listOf("chk_conference_stream_target_auto_retry_attempt")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceStreamAutoRetryMigrationTest : ConferenceStreamAutoRetryMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceStreamAutoRetryMigrationPostgresTest : ConferenceStreamAutoRetryMigrationScenarios({ PostgresMigrationHarness() })
