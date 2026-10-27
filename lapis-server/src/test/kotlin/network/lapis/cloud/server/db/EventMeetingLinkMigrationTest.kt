package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * V1.9.227 -- `V123__event_meeting_link.sql` on the upgrade path V122 -> V123, on H2 AND on PostgreSQL: the nullable `event.meeting_id`, the FK with
 * `ON DELETE SET NULL` (a purged meeting leaves the event standing), the unique index (1:1, several NULLs allowed), the audit entity type
 * MEETING_EVENT_LINK next to the older ones. V122 stays untouched; a re-run is a no-op.
 */
abstract class EventMeetingLinkMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun scalar(sql: String): String? =
            h.connection { c ->
                c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            }

        val member = UUID.randomUUID().toString()
        val committee = UUID.randomUUID().toString()
        val meetingA = UUID.randomUUID().toString()
        val meetingB = UUID.randomUUID().toString()
        var sequence = 840_000_000L

        fun event(
            id: String,
            meetingId: String?,
        ): String =
            "INSERT INTO event (id, slug, title, description, location_text, starts_at, ends_at, fee_amount, fee_currency, status, " +
                "visibility, created_at, created_by, meeting_id) VALUES ('$id', 'mel-mig-$id', 'T', 'd', 'Saal', " +
                "TIMESTAMP '2030-06-15 18:00:00', TIMESTAMP '2030-06-15 20:00:00', 0, 'EUR', 'DRAFT', 'MEMBERS_ONLY', " +
                "TIMESTAMP '2026-10-26 10:00:00', '$member', ${meetingId?.let { "'$it'" } ?: "NULL"})"

        fun meeting(id: String): String =
            "INSERT INTO meeting (id, committee_id, title, scheduled_at, location, format, status, created_at) VALUES " +
                "('$id', '$committee', 'S', TIMESTAMP '2030-06-15 18:00:00', 'Saal', 'IN_PERSON', 'PLANNED', TIMESTAMP '2026-10-26 10:00:00')"

        fun audit(type: String): String {
            sequence += 1
            return "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                "action, entry_hash, previous_entry_hash) VALUES ('${UUID.randomUUID()}', $sequence, TIMESTAMP '2026-10-26 10:00:00', " +
                "NULL, NULL, '$type', '${UUID.randomUUID()}', 'CREATE', '${sequence.toString().padStart(64, '0')}', NULL)"
        }

        beforeSpec {
            h = newHarness()
            h.flyway("122").migrate()
            // An event that exists BEFORE the migration must survive it with no link.
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$member', 'Link', 'mel-mig-$member@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(event("00000000-0000-0000-0000-00000000aa01", null).replace(", meeting_id)", ")").replace(", NULL)", ")"))
            h.flyway(null).migrate()
            exec(
                "INSERT INTO committee (id, name, type, description, active, quorum_percent, created_at, membership_mode) VALUES " +
                    "('$committee', 'MEL', 'WORKING_GROUP', 'x', TRUE, 50, TIMESTAMP '2026-10-26 10:00:00', 'MANUAL')",
            )
            exec(meeting(meetingA))
            exec(meeting(meetingB))
        }
        afterSpec { h.close() }

        test("an event from before the migration has no link; the column is nullable") {
            scalar("SELECT meeting_id FROM event WHERE id = '00000000-0000-0000-0000-00000000aa01'") shouldBe null
            scalar("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'event' AND column_name = 'meeting_id'") shouldBe
                "YES"
        }

        test("the link is 1:1: one event per meeting, several events without a link") {
            val e1 = UUID.randomUUID().toString()
            val e2 = UUID.randomUUID().toString()
            rejection(event(e1, meetingA)) shouldBe null
            (rejection(event(e2, meetingA))!!).lowercase().shouldContain("ux_event_meeting_id")
            rejection(event(UUID.randomUUID().toString(), null)) shouldBe null
            rejection(event(UUID.randomUUID().toString(), null)) shouldBe null
            rejection(event(e2, meetingB)) shouldBe null
        }

        test("the link points to a real meeting") {
            (rejection(event(UUID.randomUUID().toString(), UUID.randomUUID().toString()))!!).lowercase().shouldContain("fk_event_meeting")
        }

        test("deleting a meeting leaves the event and clears the link (ON DELETE SET NULL)") {
            val meetingC = UUID.randomUUID().toString()
            val e = UUID.randomUUID().toString()
            exec(meeting(meetingC))
            exec(event(e, meetingC))
            exec("DELETE FROM meeting WHERE id = '$meetingC'")
            scalar("SELECT COUNT(*) FROM event WHERE id = '$e'") shouldBe "1"
            scalar("SELECT meeting_id FROM event WHERE id = '$e'") shouldBe null
        }

        test("the audit entity type MEETING_EVENT_LINK is accepted, the older ones and unknown ones behave") {
            rejection(audit("MEETING_EVENT_LINK")) shouldBe null
            rejection(audit("LUPABB_SYNC_CONFIG")) shouldBe null
            rejection(audit("JOURNAL_ENTRY")) shouldBe null
            (rejection(audit("MEETING_EVENT_LINK_X"))!!).shouldContain("chk_audit_log_entry_entity_type")
            exec("DELETE FROM audit_log_entry WHERE sequence_number > 840000000")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class EventMeetingLinkMigrationTest : EventMeetingLinkMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventMeetingLinkMigrationPostgresTest : EventMeetingLinkMigrationScenarios({ PostgresMigrationHarness() })
