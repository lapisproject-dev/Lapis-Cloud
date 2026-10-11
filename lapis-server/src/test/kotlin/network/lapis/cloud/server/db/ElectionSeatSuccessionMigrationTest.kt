package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.103 -- `V84__election_seat_succession.sql` on the upgrade path V83 -> V84, on H2 AND on PostgreSQL: an existing
 * election and seat
 * survive with NULL in every new column (no backfill), the CHECKs pin the policy, the single end cause and the reason rule of the log,
 * the UNIQUE key of the log bites, the audit CHECK still accepts `COMMITTEE_MEMBERSHIP`, and a re-run is a no-op.
 */
abstract class ElectionSeatSuccessionMigrationScenarios(
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

        fun rejected(sql: String): Boolean = runCatching { exec(sql) }.isFailure

        val memberId = UUID.randomUUID()
        val committeeId = UUID.randomUUID()
        val meetingId = UUID.randomUUID()
        val oldSeat = UUID.randomUUID()
        val oldElection = UUID.randomUUID()
        var seq = 9700
        val dummyHash = "c".repeat(64)

        fun lit(value: Any?): String = if (value == null) "NULL" else "'$value'"

        fun electionInsert(
            id: UUID,
            type: String,
            policy: String?,
            withPolicyColumn: Boolean = true,
        ): String {
            val motionId = UUID.randomUUID()
            exec(
                "INSERT INTO motion (id, target_committee_id, title, rationale, text, submitter_member_id, status, submitted_at, " +
                    "meeting_id) " +
                    "VALUES ('$motionId', '$committeeId', 'A', 'R', 'T', '$memberId', 'SCHEDULED', " +
                    "TIMESTAMP '2026-01-01 00:00:00', '$meetingId')",
            )
            val policyColumn = if (withPolicyColumn) ", incumbent_policy" else ""
            val policyValue = if (withPolicyColumn) ", ${lit(policy)}" else ""
            return "INSERT INTO election (id, title, election_type, secret, seat_count, target_committee_id, target_role, " +
                "required_majority_percent, status, opened_by, opened_at, tally_threshold, motion_id, meeting_id, " +
                "active_motion_id$policyColumn) " +
                "VALUES ('$id', 'W', '$type', TRUE, 1, '$committeeId', 'MEMBER', 50, 'PREPARATION', " +
                "'$memberId', TIMESTAMP '2026-01-01 00:00:00', 2, '$motionId', '$meetingId', '$motionId'$policyValue)"
        }

        fun seatInsert(
            started: UUID? = null,
            ended: UUID? = null,
            until: String? = null,
            autoEnd: String? = null,
        ): String {
            val untilValue = if (until == null) "NULL" else "DATE '$until'"
            return "INSERT INTO committee_membership (id, role, since, until, committee_id, member_id, auto_end_reason, " +
                "started_by_election_id, ended_by_election_id) " +
                "VALUES ('${UUID.randomUUID()}', 'MEMBER', DATE '2026-01-01', $untilValue, '$committeeId', '$memberId', " +
                "${lit(autoEnd)}, ${lit(started)}, ${lit(ended)})"
        }

        fun outcomeInsert(
            election: UUID,
            outcome: String,
            reason: String?,
            member: UUID = memberId,
        ): String =
            "INSERT INTO election_seat_outcome (id, election_id, member_id, committee_id, role, outcome, reason, effective_on, " +
                "created_at) VALUES ('${UUID.randomUUID()}', '$election', '$member', '$committeeId', 'MEMBER', '$outcome', " +
                "${lit(reason)}, DATE '2026-10-11', TIMESTAMP '2026-10-11 10:00:00')"

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-11 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'UPDATE', '$dummyHash')"

        beforeSpec {
            h = newHarness()
            h.flyway("83").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) " +
                    "VALUES ('$memberId', 'M', 'succ-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO committee (id, name, type, description, active, quorum_percent, created_at) " +
                    "VALUES ('$committeeId', 'AG', 'WORKING_GROUP', 'x', TRUE, 50, TIMESTAMP '2026-01-01 00:00:00')",
            )
            exec(
                "INSERT INTO meeting (id, title, scheduled_at, location, format, status, created_at, committee_id) " +
                    "VALUES ('$meetingId', 'S', TIMESTAMP '2026-03-01 18:00:00', 'x', 'IN_PERSON', 'PLANNED', " +
                    "TIMESTAMP '2026-01-01 00:00:00', '$committeeId')",
            )
            exec(
                "INSERT INTO committee_membership (id, role, since, until, committee_id, member_id) " +
                    "VALUES ('$oldSeat', 'MEMBER', DATE '2026-01-01', NULL, '$committeeId', '$memberId')",
            )
            exec(electionInsert(oldElection, "SINGLE_CHOICE", null, withPolicyColumn = false))
            exec("UPDATE election SET status = 'PREPARATION' WHERE id = '$oldElection'")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing election and seat survive with NULL in every new column: nothing is backfilled") {
            rows("SELECT incumbent_policy FROM election WHERE id = '$oldElection'").single() shouldBe listOf("null")
            rows("SELECT started_by_election_id, ended_by_election_id FROM committee_membership WHERE id = '$oldSeat'").single() shouldBe
                listOf("null", "null")
        }

        test("incumbent_policy accepts NULL, REPLACE and KEEP for a personnel election and nothing else") {
            rejected(electionInsert(UUID.randomUUID(), "SINGLE_CHOICE", "REPLACE")) shouldBe false
            rejected(electionInsert(UUID.randomUUID(), "MULTI_CHOICE", "KEEP")) shouldBe false
            rejected(electionInsert(UUID.randomUUID(), "SINGLE_CHOICE", null)) shouldBe false
            rejected(electionInsert(UUID.randomUUID(), "SINGLE_CHOICE", "BOGUS")) shouldBe true
            rejected(electionInsert(UUID.randomUUID(), "SINGLE_CHOICE", "keep")) shouldBe true
        }

        test("a YES_NO election cannot carry a policy") {
            rejected(electionInsert(UUID.randomUUID(), "YES_NO", "KEEP")) shouldBe true
            rejected(electionInsert(UUID.randomUUID(), "YES_NO", null)) shouldBe false
        }

        test("a seat has at most one end cause, ended_by_election_id needs until, and both columns are real foreign keys") {
            val election = UUID.randomUUID()
            exec(electionInsert(election, "SINGLE_CHOICE", "REPLACE"))
            rejected(seatInsert(started = election)) shouldBe false
            rejected(seatInsert(ended = election, until = "2026-10-11")) shouldBe false
            rejected(seatInsert(ended = election, until = null)) shouldBe true
            rejected(seatInsert(ended = election, until = "2026-10-11", autoEnd = "WITHDRAWN")) shouldBe true
            rejected(seatInsert(until = "2026-10-11", autoEnd = "WITHDRAWN")) shouldBe false
            rejected(seatInsert(started = UUID.randomUUID())) shouldBe true
            rejected(seatInsert(ended = UUID.randomUUID(), until = "2026-10-11")) shouldBe true
        }

        test("the two provenance indexes exist") {
            val names =
                h.connection { c ->
                    buildSet {
                        c.metaData.getIndexInfo(null, null, "committee_membership", false, false).use { rs ->
                            while (rs.next()) rs.getString("INDEX_NAME")?.lowercase()?.let { add(it) }
                        }
                    }
                }
            (
                names.containsAll(
                    setOf("idx_committee_membership_started_by_election", "idx_committee_membership_ended_by_election"),
                )
            ) shouldBe
                true
        }

        test("election_seat_outcome: outcome and reason rules, the UNIQUE key and the foreign keys") {
            val election = UUID.randomUUID()
            exec(electionInsert(election, "SINGLE_CHOICE", "REPLACE"))
            rejected(outcomeInsert(election, "SEATED", null)) shouldBe false
            // UNIQUE (election, member, outcome)
            rejected(outcomeInsert(election, "SEATED", null)) shouldBe true
            // another outcome of the same person is fine
            rejected(outcomeInsert(election, "ENDED", null)) shouldBe false
            // a reason only for SKIPPED / PREDECESSOR_KEPT ...
            rejected(outcomeInsert(election, "CONTINUED", "WINNER_SKIPPED")) shouldBe true
            // ... and it is mandatory there
            rejected(outcomeInsert(election, "SKIPPED", null)) shouldBe true
            rejected(outcomeInsert(election, "PREDECESSOR_KEPT", null)) shouldBe true
            rejected(outcomeInsert(election, "SKIPPED", "MEMBER_NOT_ACTIVE")) shouldBe false
            rejected(outcomeInsert(election, "PREDECESSOR_KEPT", "SEAT_STARTS_LATER")) shouldBe false
            rejected(outcomeInsert(election, "SKIPPED", "BOGUS")) shouldBe true
            rejected(outcomeInsert(election, "BOGUS", null)) shouldBe true
            // unknown election and unknown member
            rejected(outcomeInsert(UUID.randomUUID(), "SEATED", null)) shouldBe true
            rejected(outcomeInsert(election, "SEATED", null, member = UUID.randomUUID())) shouldBe true
        }

        test("the audit-log CHECK still accepts COMMITTEE_MEMBERSHIP and rejects an unknown type") {
            rejected(auditInsert("COMMITTEE_MEMBERSHIP")) shouldBe false
            rejected(auditInsert("BOARD_MEMBERSHIP")) shouldBe false
            rejected(auditInsert("BOGUS")) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ElectionSeatSuccessionMigrationTest : ElectionSeatSuccessionMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ElectionSeatSuccessionMigrationPostgresTest : ElectionSeatSuccessionMigrationScenarios({ PostgresMigrationHarness() })
