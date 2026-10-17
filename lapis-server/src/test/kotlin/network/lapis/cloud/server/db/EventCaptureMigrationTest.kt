package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.156 -- `V110__event_capture.sql` on the upgrade path V109 -> V110, on H2 AND on PostgreSQL: the two settings columns and the event column with
 * their defaults, the new agreement-text kind EVENT_REC in both CHECK constraints (the older kinds stay), the audit entity type EVENT_CAPTURE, and every
 * constraint of `event_capture_acknowledgment` as a refusal (exactly ONE subject, one proof per event/subject/version, real events and members). V109 stays
 * untouched; a re-run is a no-op.
 */
abstract class EventCaptureMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun columnDefault(
            table: String,
            column: String,
        ): String? =
            h.connection { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT column_default FROM information_schema.columns WHERE table_name = '$table' AND column_name = '$column'",
                        ).use { rs -> if (rs.next()) rs.getString(1) else "<missing>" }
                }
            }

        val member = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        val author = UUID.randomUUID().toString()

        fun version(
            kind: String,
            label: String,
        ): String =
            "INSERT INTO agreement_text_version (id, kind, sequence_no, version_label, valid_from, body, sha256, char_count, " +
                "author_member_id, published_at) VALUES ('${UUID.randomUUID()}', '$kind', 1, '$label', DATE '2026-10-18', 'hallo', " +
                "'${"a".repeat(64)}', 5, '$author', TIMESTAMP '2026-10-18 10:00:00')"

        fun draft(kind: String): String =
            "INSERT INTO agreement_text_draft (kind, id, version_label, valid_from, body, sha256, char_count, updated_by, updated_at) " +
                "VALUES ('$kind', '${UUID.randomUUID()}', 'x.v1', DATE '2026-10-18', 'hallo', '${"b".repeat(64)}', 5, '$author', " +
                "TIMESTAMP '2026-10-18 10:00:00')"

        fun ack(
            eventId: String = event,
            memberId: String? = null,
            guestKey: String? = null,
            version: String = "v1",
        ): String =
            "INSERT INTO event_capture_acknowledgment (id, event_id, member_id, guest_key, consent_version, consent_sha256, " +
                "acknowledged_on) VALUES " +
                "('${UUID.randomUUID()}', '$eventId', ${memberId?.let { "'$it'" } ?: "NULL"}, ${guestKey?.let { "'$it'" } ?: "NULL"}, " +
                "'$version', '${"c".repeat(64)}', DATE '2026-10-18')"

        var sequence = 830_000_000L

        fun audit(type: String): String {
            sequence += 1
            return "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                "action, entry_hash, previous_entry_hash) VALUES ('${UUID.randomUUID()}', $sequence, TIMESTAMP '2026-10-18 10:00:00', " +
                "NULL, NULL, '$type', '${UUID.randomUUID()}', 'UPDATE', '${sequence.toString().padStart(64, '0')}', NULL)"
        }

        beforeSpec {
            h = newHarness()
            h.flyway("109").migrate()
            h.flyway(null).migrate()
            for (m in listOf(member, author)) {
                exec(
                    "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                        "('$m', 'Capture', 'capture-mig-$m@example.org', 'ACTIVE', DATE '2026-01-01')",
                )
            }
            exec(
                "INSERT INTO event (id, slug, title, description, location_text, starts_at, ends_at, fee_amount, fee_currency, " +
                    "status, visibility, created_at, created_by) " +
                    "VALUES ('$event', 'capture-mig-$event', 'T', 'd', 'Saal', TIMESTAMP '2030-06-15 18:00:00', " +
                    "TIMESTAMP '2030-06-15 20:00:00', 0, 'EUR', " +
                    "'PUBLISHED', 'PUBLIC', TIMESTAMP '2026-10-18 10:00:00', '$member')",
            )
        }
        afterSpec { h.close() }

        test("the settings columns and the event column default to 'off'; the notice version is nullable") {
            columnDefault("organization_settings", "event_capture_enabled")!!.uppercase() shouldContain "FALSE"
            columnDefault("organization_settings", "event_capture_notice_version").let { it == null || it.uppercase() == "NULL" } shouldBe
                true
            columnDefault("event", "capture_allowed")!!.uppercase() shouldContain "FALSE"
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT event_capture_enabled, event_capture_notice_version FROM organization_settings").use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean(1) shouldBe false
                        rs.getString(2) shouldBe null
                    }
                    st.executeQuery("SELECT capture_allowed FROM event WHERE id = '$event'").use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean(1) shouldBe false
                    }
                }
            }
        }

        test("both agreement tables accept EVENT_REC and still refuse an unknown kind; the older kinds are still there") {
            rejection(version("EVENT_REC", "event-rec.v1")) shouldBe null
            rejection(draft("EVENT_REC")) shouldBe null
            (rejection(version("EVENT_REC2", "x.v1"))!!).shouldContain("chk_agreement_text_version_kind")
            (rejection(draft("EVENT_REC2"))!!).shouldContain("chk_agreement_text_draft_kind")
            for (kind in listOf("MEMBERSHIP", "FRIEND", "REC_ROOM", "REC_PULPIT", "FINDER", "ENC_CHURCH", "ENC_ASSEM")) {
                rejection(version(kind, "old-$kind.v1")) shouldBe null
                rejection(draft(kind)) shouldBe null
            }
            exec("DELETE FROM agreement_text_version")
            exec("DELETE FROM agreement_text_draft")
        }

        test("the audit entity type EVENT_CAPTURE is accepted, an unknown one is not") {
            rejection(audit("EVENT_CAPTURE")) shouldBe null
            (rejection(audit("EVENT_CAPTURE_X"))!!).shouldContain("chk_audit_log_entry_entity_type")
            exec("DELETE FROM audit_log_entry WHERE sequence_number > 830000000")
        }

        test("a proof has exactly ONE subject: a member or a guest key, never both and never none") {
            rejection(ack(memberId = member)) shouldBe null
            rejection(ack(guestKey = "d".repeat(64))) shouldBe null
            (rejection(ack(memberId = member, guestKey = "e".repeat(64), version = "v2"))!!).shouldContain("chk_event_capture_ack_subject")
            (rejection(ack(version = "v3"))!!).shouldContain("chk_event_capture_ack_subject")
        }

        test("one proof per event, subject and version; a new version is a new proof") {
            (rejection(ack(memberId = member))!!).shouldContain("uq_event_capture_ack_member")
            (rejection(ack(guestKey = "d".repeat(64)))!!).shouldContain("uq_event_capture_ack_guest")
            rejection(ack(memberId = member, version = "v9")) shouldBe null
            rejection(ack(guestKey = "d".repeat(64), version = "v9")) shouldBe null
        }

        test("a proof refers to a real event and a real member (no cascade: the application deletes explicitly)") {
            (rejection(ack(eventId = UUID.randomUUID().toString(), memberId = member, version = "v7"))!!)
                .shouldContain("fk_event_capture_ack_event")
            (rejection(ack(memberId = UUID.randomUUID().toString(), version = "v7"))!!).shouldContain("fk_event_capture_ack_member")
            (rejection("DELETE FROM event WHERE id = '$event'")!!).shouldContain("fk_event_capture_ack_event")
        }

        test("the table has no column that could hold a name, a room or a time of day") {
            h.connection { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT column_name FROM information_schema.columns WHERE table_name = 'event_capture_acknowledgment'",
                        ).use { rs ->
                            val names = buildSet { while (rs.next()) add(rs.getString(1)) }
                            names shouldBe
                                setOf("id", "event_id", "member_id", "guest_key", "consent_version", "consent_sha256", "acknowledged_on")
                        }
                }
            }
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class EventCaptureMigrationTest : EventCaptureMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventCaptureMigrationPostgresTest : EventCaptureMigrationScenarios({ PostgresMigrationHarness() })
