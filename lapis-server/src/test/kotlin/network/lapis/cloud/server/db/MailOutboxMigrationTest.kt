package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.81 -- `V80__mail_budget_and_outbox.sql` on the upgrade path V79 -> V80, on H2 AND on PostgreSQL: existing `PENDING` delivery
 * rows survive with the new defaults, `INTERRUPTED` is accepted while an unknown status is still rejected (this also pins the H2-only
 * drop of the unnamed inline CHECK of V1 -- a shifted generated name fails here), the singleton lock row exists exactly once, the payload
 * CHECKs of `mail_outbox` bite from both sides, and a re-run is a no-op.
 */
abstract class MailOutboxMigrationScenarios(
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
        val listId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val pendingRow = UUID.randomUUID()

        beforeSpec {
            h = newHarness()
            h.flyway("79").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'mo-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec("INSERT INTO mailing_list (id, name, created_by) VALUES ('$listId', 'L', '$memberId')")
            exec(
                "INSERT INTO mailing_message (id, mailing_list_id, subject, body_text, sent_by, status) " +
                    "VALUES ('$messageId', '$listId', 'S', 'B', '$memberId', 'QUEUED')",
            )
            exec(
                "INSERT INTO mailing_delivery_log (id, delivered_at, delivery_status, mailing_message_id, member_id) " +
                    "VALUES ('$pendingRow', TIMESTAMP '2026-10-08 10:00:00', 'PENDING', '$messageId', '$memberId')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing PENDING delivery row survives with claimed_at NULL, attempt_count 0 and next_attempt_at NULL") {
            rows("SELECT delivery_status, claimed_at, attempt_count, next_attempt_at FROM mailing_delivery_log WHERE id = '$pendingRow'")
                .single() shouldBe listOf("PENDING", "null", "0", "null")
            rows("SELECT queued_at FROM mailing_message WHERE id = '$messageId'").single() shouldBe listOf("null")
        }

        test("INTERRUPTED is accepted as a delivery status, an unknown status is still rejected") {
            val row = UUID.randomUUID()

            fun insert(status: String) =
                "INSERT INTO mailing_delivery_log (id, delivered_at, delivery_status, mailing_message_id, member_id) " +
                    "VALUES ('${UUID.randomUUID()}', TIMESTAMP '2026-10-08 10:00:00', '$status', '$messageId', '$memberId')"
            exec(
                "INSERT INTO mailing_delivery_log (id, delivered_at, delivery_status, mailing_message_id, member_id) " +
                    "VALUES ('$row', TIMESTAMP '2026-10-08 10:00:00', 'INTERRUPTED', '$messageId', '$memberId')",
            )
            rows("SELECT delivery_status FROM mailing_delivery_log WHERE id = '$row'").single() shouldBe listOf("INTERRUPTED")
            listOf("SENT", "BOUNCED", "SKIPPED_UNSUBSCRIBED", "PENDING", "FAILED", "SKIPPED_NO_ADDRESS").forEach {
                rejection(insert(it)) shouldBe null
            }
            (rejection(insert("NOT_A_STATUS")) != null) shouldBe true
        }

        test("mail_budget_lock holds exactly one row (id 1); another id is rejected") {
            rows("SELECT id, bulk_paused_until FROM mail_budget_lock") shouldBe listOf(listOf("1", "null"))
            (rejection("INSERT INTO mail_budget_lock (id) VALUES (2)") != null) shouldBe true
            (rejection("INSERT INTO mail_budget_lock (id) VALUES (1)") != null) shouldBe true
        }

        test("a re-run is a no-op and does not duplicate the lock row") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
            rows("SELECT COUNT(*) FROM mail_budget_lock").single() shouldBe listOf("1")
        }

        fun outbox(
            status: String,
            enc: String = "'x'",
            hash: String = "'h'",
            priority: Int = 0,
            attempts: Int = 0,
        ) = "INSERT INTO mail_outbox (id, purpose, priority, status, attempt_count, next_attempt_at, created_at, recipient_enc, " +
            "subject_enc, text_enc, html_enc, recipient_lookup_hash) VALUES ('${UUID.randomUUID()}', 'password-reset', " +
            "$priority, '$status', $attempts, TIMESTAMP '2026-10-08 10:00:00', TIMESTAMP '2026-10-08 10:00:00', " +
            "$enc, $enc, $enc, $enc, $hash)"

        test("mail_outbox: an open row needs its payload, a final row must not carry any") {
            rejection(outbox("QUEUED")) shouldBe null
            rejection(outbox("SENDING")) shouldBe null
            (rejection(outbox("QUEUED", enc = "NULL", hash = "NULL")) != null) shouldBe true
            (rejection(outbox("SENDING", enc = "NULL", hash = "NULL")) != null) shouldBe true
            listOf("SENT", "FAILED", "EXPIRED").forEach { status ->
                // payload present -> rejected
                (rejection(outbox(status)) != null) shouldBe true
                // only the lookup hash present -> rejected
                (rejection(outbox(status, enc = "NULL")) != null) shouldBe true
                // cleared -> accepted
                rejection(outbox(status, enc = "NULL", hash = "NULL")) shouldBe null
            }
        }

        test("mail_outbox: status, priority and attempt_count CHECKs") {
            (rejection(outbox("WHATEVER")) != null) shouldBe true
            (rejection(outbox("QUEUED", priority = 2)) != null) shouldBe true
            rejection(outbox("QUEUED", priority = 1)) shouldBe null
            (rejection(outbox("QUEUED", attempts = 21)) != null) shouldBe true
            rejection(outbox("QUEUED", attempts = 20)) shouldBe null
        }

        test("mail_send_slot: lane CHECK") {
            fun slot(lane: String) =
                "INSERT INTO mail_send_slot (id, reserved_at, lane) VALUES ('${UUID.randomUUID()}', TIMESTAMP '2026-10-08 10:00:00', '$lane')"
            rejection(slot("SYSTEM")) shouldBe null
            rejection(slot("BULK")) shouldBe null
            (rejection(slot("OTHER")) != null) shouldBe true
        }
    })

class MailOutboxMigrationTest : MailOutboxMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MailOutboxMigrationPostgresTest : MailOutboxMigrationScenarios({ PostgresMigrationHarness() })
