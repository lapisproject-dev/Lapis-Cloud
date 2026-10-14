package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.shared.domain.AuditEntityType
import java.util.UUID

/**
 * Welle V1.9.129 -- `V102__video_overlays.sql` on the upgrade path V101 -> V102, on H2 AND on PostgreSQL: the settings row keeps the option
 * off, the library table and its CHECK/UNIQUE constraints fire, the audit CHECK accepts `VIDEO_OVERLAY` AND every older entity type
 * (the list is the V100 list plus one), and a re-run is a no-op. V101 stays untouched (it is part of the released v0.35.0).
 */
abstract class VideoOverlaysMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun single(sql: String): String =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        rs.next()
                        rs.getString(1) ?: "null"
                    }
                }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun asset(
            kind: String = "LOGO",
            name: String = "Logo",
            format: String = "png",
            token: String = "T".repeat(43),
        ): String {
            val columns = "id, kind, name, format, width, height, size_bytes, sha256, access_token, sort_order, created_at"
            val values =
                "'${UUID.randomUUID()}', '$kind', '$name', '$format', 600, 300, 1000, '${"0".repeat(64)}', '$token', 0, " +
                    "TIMESTAMP '2026-10-14 10:00:00'"
            return "INSERT INTO video_overlay_asset ($columns) VALUES ($values)"
        }

        val rowId = "00000000-0000-0000-0000-0000000000f2"
        var sequence = 800_000_000L

        fun audit(type: String): String {
            sequence += 1
            return "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                "action, entry_hash, previous_entry_hash) VALUES ('${UUID.randomUUID()}', $sequence, TIMESTAMP '2026-10-14 10:00:00', " +
                "NULL, NULL, '$type', '${UUID.randomUUID()}', 'CREATE', '${sequence.toString().padStart(64, '0')}', NULL)"
        }

        beforeSpec {
            h = newHarness()
            h.flyway("101").migrate()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the existing settings row keeps the option off, the column is NOT NULL and can be switched") {
            single("SELECT video_overlays_offered FROM organization_settings WHERE id = '$rowId'").lowercase().take(1) shouldBe "f"
            exec("UPDATE organization_settings SET video_overlays_offered = TRUE WHERE id = '$rowId'")
            single("SELECT video_overlays_offered FROM organization_settings WHERE id = '$rowId'").lowercase().take(1) shouldBe "t"
            exec("UPDATE organization_settings SET video_overlays_offered = FALSE WHERE id = '$rowId'")
            (rejection("UPDATE organization_settings SET video_overlays_offered = NULL WHERE id = '$rowId'") != null) shouldBe true
        }

        fun refusedBy(
            sql: String,
            constraint: String,
        ): Boolean = rejection(sql)?.contains(constraint, ignoreCase = true) == true

        test("the library table: valid rows pass, kind, format, name length and a duplicate token are refused") {
            rejection(asset(token = "A".repeat(43))) shouldBe null
            refusedBy(asset(kind = "BANNER", token = "B".repeat(43)), "chk_video_overlay_asset_kind") shouldBe true
            refusedBy(asset(format = "svg", token = "C".repeat(43)), "chk_video_overlay_asset_format") shouldBe true
            refusedBy(asset(name = "", token = "D".repeat(43)), "chk_video_overlay_asset_name") shouldBe true
            rejection(asset(name = "x".repeat(60), token = "E".repeat(43))) shouldBe null
            refusedBy(asset(token = "A".repeat(43)), "uq_video_overlay_asset_token") shouldBe true
            exec("DELETE FROM video_overlay_asset")
        }

        test("audit_log_entry accepts VIDEO_OVERLAY and EVERY entity type of the shared enum, and still refuses an unknown one") {
            refusedBy(audit("NOT_A_REAL_ENTITY"), "chk_audit_log_entry_entity_type") shouldBe true
            for (type in AuditEntityType.entries) {
                withClue(clue = "entity type ${type.name}") { rejection(audit(type.name)) shouldBe null }
            }
            exec("DELETE FROM audit_log_entry WHERE sequence_number > 800000000")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class VideoOverlaysMigrationTest : VideoOverlaysMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class VideoOverlaysMigrationPostgresTest : VideoOverlaysMigrationScenarios({ PostgresMigrationHarness() })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
