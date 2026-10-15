package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.shared.domain.AuditEntityType
import java.util.UUID

/**
 * Welle V1.9.134 -- `V104__agreement_texts.sql` on the upgrade path V103 -> V104, on H2 AND on PostgreSQL: the two tables, their CHECK, UNIQUE and
 * FOREIGN KEY constraints fire, the audit CHECK accepts `AGREEMENT_TEXT` AND every older entity type, and a re-run is a no-op. V103 stays untouched.
 */
abstract class AgreementTextsMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun refusedBy(
            sql: String,
            constraint: String,
        ): Boolean = rejection(sql)?.contains(constraint, ignoreCase = true) == true

        val author = UUID.randomUUID().toString()

        fun version(
            kind: String = "MEMBERSHIP",
            seq: Int = 1,
            label: String = "2026-10-01.v1",
            chars: Int = 5,
            sha: String = "a".repeat(64),
            member: String = author,
        ): String =
            "INSERT INTO agreement_text_version (id, kind, sequence_no, version_label, valid_from, body, sha256, char_count, " +
                "author_member_id, published_at) VALUES ('${UUID.randomUUID()}', '$kind', $seq, '$label', DATE '2026-10-01', 'hallo', " +
                "'$sha', $chars, '$member', TIMESTAMP '2026-10-01 10:00:00')"

        fun draft(
            kind: String = "MEMBERSHIP",
            id: String = UUID.randomUUID().toString(),
        ): String =
            "INSERT INTO agreement_text_draft (kind, id, version_label, valid_from, body, sha256, char_count, updated_by, updated_at) " +
                "VALUES ('$kind', '$id', 'x.v1', DATE '2026-10-01', 'hallo', '${"b".repeat(
                    64,
                )}', 5, '$author', TIMESTAMP '2026-10-01 10:00:00')"

        var sequence = 810_000_000L

        fun audit(type: String): String {
            sequence += 1
            return "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                "action, entry_hash, previous_entry_hash) VALUES ('${UUID.randomUUID()}', $sequence, TIMESTAMP '2026-10-14 10:00:00', " +
                "NULL, NULL, '$type', '${UUID.randomUUID()}', 'CREATE', '${sequence.toString().padStart(64, '0')}', NULL)"
        }

        beforeSpec {
            h = newHarness()
            h.flyway("103").migrate()
            h.flyway(null).migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$author', 'Autor', 'agreement-mig-$author@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
        }
        afterSpec { h.close() }

        test("the version table: a valid row passes; kind, sequence, character count, hash length and the author FK are enforced") {
            rejection(version()) shouldBe null
            refusedBy(version(kind = "OTHER", seq = 2, label = "k.v1"), "chk_agreement_text_version_kind") shouldBe true
            refusedBy(version(seq = 0, label = "s.v1"), "chk_agreement_text_version_seq") shouldBe true
            refusedBy(version(seq = 3, label = "c0.v1", chars = 0), "chk_agreement_text_version_chars") shouldBe true
            refusedBy(version(seq = 4, label = "c1.v1", chars = 60_001), "chk_agreement_text_version_chars") shouldBe true
            rejection(version(seq = 5, label = "c2.v1", chars = 60_000)) shouldBe null
            refusedBy(version(seq = 6, label = "h.v1", sha = "abc"), "chk_agreement_text_version_sha") shouldBe true
            refusedBy(version(seq = 7, label = "f.v1", member = UUID.randomUUID().toString()), "fk_agreement_text_version_author") shouldBe
                true
        }

        test("the version table: a label and a sequence number are unique per kind, but a label may repeat across kinds") {
            refusedBy(version(seq = 9, label = "2026-10-01.v1"), "uq_agreement_text_version_label") shouldBe true
            refusedBy(version(seq = 1, label = "other.v1"), "uq_agreement_text_version_seq") shouldBe true
            rejection(version(kind = "FRIEND", seq = 1, label = "2026-10-01.v1")) shouldBe null
            exec("DELETE FROM agreement_text_version")
        }

        test("the draft table: one draft per kind, a unique id, a valid kind and the updater FK") {
            rejection(draft()) shouldBe null
            (rejection(draft()) != null) shouldBe true
            refusedBy(draft(kind = "OTHER"), "chk_agreement_text_draft_kind") shouldBe true
            rejection(draft(kind = "FRIEND")) shouldBe null
            exec("DELETE FROM agreement_text_draft")
        }

        test("audit_log_entry accepts AGREEMENT_TEXT and EVERY entity type of the shared enum, and still refuses an unknown one") {
            refusedBy(audit("NOT_A_REAL_ENTITY"), "chk_audit_log_entry_entity_type") shouldBe true
            for (type in AuditEntityType.entries) {
                withClue(clue = "entity type ${type.name}") { rejection(audit(type.name)) shouldBe null }
            }
            exec("DELETE FROM audit_log_entry WHERE sequence_number > 810000000")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class AgreementTextsMigrationTest : AgreementTextsMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AgreementTextsMigrationPostgresTest : AgreementTextsMigrationScenarios({ PostgresMigrationHarness() })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
