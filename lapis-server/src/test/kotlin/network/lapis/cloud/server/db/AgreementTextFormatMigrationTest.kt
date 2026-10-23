package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.196 -- `V112__agreement_text_format.sql` on the upgrade path V111 -> V112, on H2 AND on PostgreSQL: a version and a draft written
 * BEFORE the migration read as `plain` afterwards (the column default), the CHECK accepts `plain` and `markdown` and refuses anything else, the
 * hash column of an existing row is untouched, and a re-run is a no-op. V104 stays untouched (its checksum is already on running instances).
 */
abstract class AgreementTextFormatMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun <T> query(
            sql: String,
            read: (java.sql.ResultSet) -> T,
        ): T = h.connection { c -> c.createStatement().use { s -> s.executeQuery(sql).use(read) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val author = UUID.randomUUID().toString()
        val legacySha = "c".repeat(64)

        fun versionInsert(
            kind: String,
            label: String,
            seq: Int,
            formatColumn: String = "",
            formatValue: String = "",
        ): String =
            "INSERT INTO agreement_text_version (id, kind, sequence_no, version_label, valid_from, body, sha256, char_count, " +
                "author_member_id, published_at$formatColumn) VALUES ('${UUID.randomUUID()}', '$kind', $seq, '$label', " +
                "DATE '2026-10-01', " +
                "'hallo', '$legacySha', 5, '$author', TIMESTAMP '2026-10-01 10:00:00'$formatValue)"

        beforeSpec {
            h = newHarness()
            h.flyway("111").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$author', 'Autor', 'agreement-format-$author@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            // rows written exactly as the application did before V112 (no body_format column exists yet)
            exec(versionInsert(kind = "MEMBERSHIP", label = "alt.v1", seq = 1))
            exec(
                "INSERT INTO agreement_text_draft (kind, id, version_label, valid_from, body, sha256, char_count, updated_by, " +
                    "updated_at) " +
                    "VALUES ('FRIEND', '${UUID.randomUUID()}', 'alt-entwurf.v1', DATE '2026-10-01', 'hallo', '${"d".repeat(64)}', 5, " +
                    "'$author', TIMESTAMP '2026-10-01 10:00:00')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("rows from before the migration read as plain, and their hash and text are untouched") {
            query("SELECT body_format, sha256, body FROM agreement_text_version WHERE version_label = 'alt.v1'") { rs ->
                rs.next() shouldBe true
                rs.getString("body_format") shouldBe "plain"
                rs.getString("sha256") shouldBe legacySha
                rs.getString("body") shouldBe "hallo"
            }
            query("SELECT body_format FROM agreement_text_draft WHERE version_label = 'alt-entwurf.v1'") { rs ->
                rs.next() shouldBe true
                rs.getString("body_format") shouldBe "plain"
            }
        }

        test("a row written without the column (older code during a rolling deploy) gets plain; markdown is accepted") {
            rejection(versionInsert(kind = "FRIEND", label = "ohne.v1", seq = 1)) shouldBe null
            query("SELECT body_format FROM agreement_text_version WHERE version_label = 'ohne.v1'") { rs ->
                rs.next() shouldBe true
                rs.getString("body_format") shouldBe "plain"
            }
            rejection(
                versionInsert(kind = "FRIEND", label = "md.v1", seq = 2, formatColumn = ", body_format", formatValue = ", 'markdown'"),
            ) shouldBe
                null
        }

        test("the CHECK refuses every other value, on both tables") {
            val refused =
                rejection(
                    versionInsert(kind = "FRIEND", label = "html.v1", seq = 3, formatColumn = ", body_format", formatValue = ", 'html'"),
                )
            (refused?.contains("chk_agreement_text_version_format", ignoreCase = true) == true) shouldBe true
            val draftRefused =
                rejection(
                    "UPDATE agreement_text_draft SET body_format = 'html' WHERE kind = 'FRIEND'",
                )
            (draftRefused?.contains("chk_agreement_text_draft_format", ignoreCase = true) == true) shouldBe true
            rejection("UPDATE agreement_text_draft SET body_format = 'markdown' WHERE kind = 'FRIEND'") shouldBe null
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class AgreementTextFormatMigrationTest : AgreementTextFormatMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AgreementTextFormatMigrationPostgresTest : AgreementTextFormatMigrationScenarios({ PostgresMigrationHarness() })
