package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.131 -- `V103__conference_stream_platform_x.sql` on the upgrade path V102 -> V103, on H2 AND on PostgreSQL: an existing
 * destination stays valid, `X` is accepted, an unknown platform is still rejected, exactly one CHECK guards `platform` afterwards (catches a
 * drifted generated constraint name), and a re-run is a no-op. The stream keys used here are dummy ciphertext values.
 */
abstract class ConferenceStreamPlatformXMigrationScenarios(
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

        fun insertDestination(
            id: UUID,
            platform: String,
            memberId: UUID,
        ) = "INSERT INTO conference_stream_destination (id, label, platform, rtmp_url, stream_key_ciphertext, stream_key_set_at, " +
            "created_by_member_id, created_at, enabled) VALUES ('$id', 'dest-$id', '$platform', 'rtmps://ingest.example.org/live', " +
            "'dummy-ciphertext', TIMESTAMP '2026-10-14 09:00:00', '$memberId', TIMESTAMP '2026-10-14 09:00:00', TRUE)"

        val memberId = UUID.randomUUID()
        val legacyId = UUID.randomUUID()

        beforeSpec {
            h = newHarness()
            h.flyway("102").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'x-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(insertDestination(legacyId, "GENERIC_RTMP", memberId))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing destination stays readable and valid") {
            rows("SELECT platform FROM conference_stream_destination WHERE id = '$legacyId'").single().single() shouldBe "GENERIC_RTMP"
            exec("UPDATE conference_stream_destination SET enabled = FALSE WHERE id = '$legacyId'")
        }

        test("X is accepted") {
            val id = UUID.randomUUID()
            exec(insertDestination(id, "X", memberId))
            rows("SELECT platform FROM conference_stream_destination WHERE id = '$id'").single().single() shouldBe "X"
        }

        test("the other platforms are still accepted") {
            listOf("YOUTUBE", "TWITCH", "PEERTUBE", "OWNCAST", "GENERIC_RTMP").forEach {
                exec(insertDestination(UUID.randomUUID(), it, memberId))
            }
        }

        test("an unknown platform is still rejected") {
            (rejection(insertDestination(UUID.randomUUID(), "FACEBOOK", memberId)) != null) shouldBe true
            (rejection(insertDestination(UUID.randomUUID(), "x", memberId)) != null) shouldBe true
        }

        test("exactly one CHECK guards the platform column, and it is the named one") {
            // PostgreSQL 17 lists NOT NULL constraints as CHECKs ("platform IS NOT NULL") -- those are excluded in the query.
            val checks =
                rows(
                    "SELECT tc.constraint_name FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.check_constraints cc ON cc.constraint_name = tc.constraint_name " +
                        "AND cc.constraint_schema = tc.constraint_schema " +
                        "WHERE LOWER(tc.table_name) = 'conference_stream_destination' AND tc.constraint_type = 'CHECK' " +
                        "AND LOWER(cc.check_clause) LIKE '%platform%' AND LOWER(cc.check_clause) NOT LIKE '%is not null%'",
                ).map { it.single() }
            checks shouldBe listOf("chk_conference_stream_destination_platform")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceStreamPlatformXMigrationTest : ConferenceStreamPlatformXMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceStreamPlatformXMigrationPostgresTest : ConferenceStreamPlatformXMigrationScenarios({ PostgresMigrationHarness() })
