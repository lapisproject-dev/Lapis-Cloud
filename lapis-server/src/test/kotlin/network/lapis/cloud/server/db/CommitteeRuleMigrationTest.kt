package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.112 -- `V92__committee_rule.sql` on the upgrade path V91 -> V92, on H2 AND on PostgreSQL: an existing committee stays a MANUAL one
 * without a rule, an existing vote has no frozen electorate (marker NULL: it keeps the live set, no backfill), the CHECKs tie mode, rule and
 * committee type together, the frozen electorate is unique per (vote, member), the seat CHECK accepts `RULE_MODE`, the audit-log CHECK accepts
 * `COMMITTEE`, and a re-run is a no-op.
 */
abstract class CommitteeRuleMigrationScenarios(
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
        val otherMember = UUID.randomUUID()
        val oldCommittee = UUID.randomUUID()
        val oldMotion = UUID.randomUUID()
        val oldMeeting = UUID.randomUUID()
        val oldVote = UUID.randomUUID()
        var seq = 9800
        val dummyHash = "d".repeat(64)
        val rule = """{"v":1,"op":"ALL","conditions":[]}"""

        fun committeeInsert(
            type: String,
            mode: String?,
            ruleJson: String?,
            id: UUID = UUID.randomUUID(),
        ) = "INSERT INTO committee (id, name, type, description, active, quorum_percent, created_at" +
            "${if (mode != null) ", membership_mode" else ""}, rule_json) " +
            "VALUES ('$id', 'C-$id', '$type', 'x', TRUE, 50, TIMESTAMP '2026-01-01 00:00:00'" +
            "${if (mode != null) ", '$mode'" else ""}, ${ruleJson?.let { "'$it'" } ?: "NULL"})"

        fun seatInsert(
            committee: UUID,
            reason: String?,
        ) = "INSERT INTO committee_membership (id, role, since, until, committee_id, member_id, auto_end_reason) " +
            "VALUES ('${UUID.randomUUID()}', 'MEMBER', DATE '2026-01-01', NULL, '$committee', '$memberId', ${reason?.let {
                "'$it'"
            } ?: "NULL"})"

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-12 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'UPDATE', '$dummyHash')"

        fun voterInsert(
            vote: UUID,
            member: UUID,
        ) = "INSERT INTO vote_eligible_voter (id, vote_id, member_id) VALUES ('${UUID.randomUUID()}', '$vote', '$member')"

        beforeSpec {
            h = newHarness()
            h.flyway("91").migrate()
            listOf(memberId, otherMember).forEach {
                exec(
                    "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$it', 'M', 'rule-mig-$it@example.org', 'ACTIVE', DATE '2026-01-01')",
                )
            }
            exec(
                "INSERT INTO committee (id, name, type, description, active, quorum_percent, created_at) " +
                    "VALUES ('$oldCommittee', 'AG', 'WORKING_GROUP', 'x', TRUE, 50, TIMESTAMP '2026-01-01 00:00:00')",
            )
            exec(
                "INSERT INTO meeting (id, title, scheduled_at, format, status, created_at, committee_id) " +
                    "VALUES ('$oldMeeting', 'S', TIMESTAMP '2026-02-01 18:00:00', 'ONLINE', 'PLANNED', TIMESTAMP '2026-01-01 00:00:00', '$oldCommittee')",
            )
            exec(
                "INSERT INTO motion (id, target_committee_id, title, rationale, text, submitter_member_id, status, submitted_at) " +
                    "VALUES ('$oldMotion', '$oldCommittee', 'A', 'R', 'T', '$memberId', 'SCHEDULED', TIMESTAMP '2026-01-02 00:00:00')",
            )
            exec(
                "INSERT INTO vote (id, title, status, opened_by, opened_at, motion_id, meeting_id) " +
                    "VALUES ('$oldVote', 'V', 'OPEN', '$memberId', TIMESTAMP '2026-02-01 18:05:00', '$oldMotion', '$oldMeeting')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing committee is a MANUAL committee without a rule") {
            rows("SELECT membership_mode, rule_json FROM committee WHERE id = '$oldCommittee'").single() shouldBe listOf("MANUAL", "null")
        }

        test("an existing vote has no frozen electorate: marker NULL, no electorate rows (nothing is backfilled)") {
            rows("SELECT electorate_frozen_at FROM vote WHERE id = '$oldVote'").single().single() shouldBe "null"
            rows("SELECT COUNT(*) FROM vote_eligible_voter WHERE vote_id = '$oldVote'").single().single() shouldBe "0"
        }

        test("the CHECKs tie mode, rule and committee type together") {
            rejection(committeeInsert(type = "WORKING_GROUP", mode = null, ruleJson = null)) shouldBe null
            rejection(committeeInsert(type = "WORKING_GROUP", mode = "MANUAL", ruleJson = null)) shouldBe null
            rejection(committeeInsert(type = "COMMISSION", mode = "RULE", ruleJson = rule)) shouldBe null
            rejection(committeeInsert(type = "OTHER", mode = "RULE", ruleJson = rule)) shouldBe null
            // RULE without a rule, a rule on a MANUAL committee, an unknown mode
            (rejection(committeeInsert(type = "WORKING_GROUP", mode = "RULE", ruleJson = null)) != null) shouldBe true
            (rejection(committeeInsert(type = "WORKING_GROUP", mode = "MANUAL", ruleJson = rule)) != null) shouldBe true
            (rejection(committeeInsert(type = "WORKING_GROUP", mode = "HYBRID", ruleJson = rule)) != null) shouldBe true
            // the General Assembly and the executive board can never be rule-based
            (rejection(committeeInsert(type = "GENERAL_ASSEMBLY", mode = "RULE", ruleJson = rule)) != null) shouldBe true
            (rejection(committeeInsert(type = "EXECUTIVE_BOARD", mode = "RULE", ruleJson = rule)) != null) shouldBe true
            rejection(committeeInsert(type = "GENERAL_ASSEMBLY", mode = "MANUAL", ruleJson = null)) shouldBe null
        }

        test("the frozen electorate is unique per (vote, member)") {
            rejection(voterInsert(oldVote, memberId)) shouldBe null
            (rejection(voterInsert(oldVote, memberId)) != null) shouldBe true
            rejection(voterInsert(oldVote, otherMember)) shouldBe null
            rows("SELECT COUNT(*) FROM vote_eligible_voter WHERE vote_id = '$oldVote'").single().single() shouldBe "2"
        }

        test("a seat accepts RULE_MODE as end reason next to the four statuses, and still rejects anything else") {
            listOf("WITHDRAWN", "REJECTED", "DECEASED", "DONOR", "RULE_MODE").forEach {
                rejection(seatInsert(oldCommittee, it)) shouldBe null
            }
            listOf("ACTIVE", "FRIEND", "BOGUS").forEach { (rejection(seatInsert(oldCommittee, it)) != null) shouldBe true }
        }

        test("the audit-log CHECK accepts COMMITTEE, keeps earlier types and rejects an unknown one") {
            rejection(auditInsert("COMMITTEE")) shouldBe null
            listOf(
                "MAILING_MESSAGE",
                "COMMITTEE_MEMBERSHIP",
                "MEMBER",
                "JOURNAL_ENTRY",
            ).forEach { rejection(auditInsert(it)) shouldBe null }
            (rejection(auditInsert("BOGUS")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class CommitteeRuleMigrationTest : CommitteeRuleMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CommitteeRuleMigrationPostgresTest : CommitteeRuleMigrationScenarios({ PostgresMigrationHarness() })
