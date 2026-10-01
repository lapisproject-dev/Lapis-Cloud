package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.h2.tools.RunScript
import java.io.InputStreamReader
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

/**
 * Welle V1.9.23 -- `V64__election_integrity.sql` against the pre-V64 shape a real instance is running:
 * a hand-built `election`/`election_ballot`/`election_option` with legacy data (secret ballots with
 * their old per-day `cast_at`, running elections with `tally_threshold = 1`, aborted and active
 * elections). Same technique as [MembershipTierMigrationTest]: the migration is applied verbatim from
 * the classpath to a fresh H2-in-PostgreSQL-mode database, NOT through Flyway (which always migrates
 * the current baseline).
 */
class ElectionIntegrityMigrationTest :
    FunSpec({
        fun freshConnection(name: String): Connection {
            val c =
                DriverManager.getConnection(
                    "jdbc:h2:mem:election-integrity-$name-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                    "sa",
                    "",
                )
            c.autoCommit = true
            createPreV64Schema(c)
            return c
        }

        test(
            "V64 normalizes secret cast_at only, raises only running thresholds, backfills active_motion_id and enforces the new constraints",
        ) {
            freshConnection("data").use { c ->
                val motionA = UUID.randomUUID()
                val motionB = UUID.randomUUID()
                val motionC = UUID.randomUUID()
                val motionD = UUID.randomUUID()
                val secretOpen =
                    insertElection(
                        c = c,
                        motionId = motionA,
                        secret = true,
                        status = "OPEN",
                        threshold = 1,
                        votingOpenedAt = "2026-03-01 18:30:15",
                    )
                val openOpen =
                    insertElection(
                        c = c,
                        motionId = motionB,
                        secret = false,
                        status = "OPEN",
                        threshold = 1,
                        votingOpenedAt = "2026-03-02 18:30:15",
                    )
                val tallied =
                    insertElection(
                        c = c,
                        motionId = motionC,
                        secret = false,
                        status = "TALLIED",
                        threshold = 1,
                        votingOpenedAt = "2026-03-03 18:30:15",
                    )
                val aborted =
                    insertElection(c = c, motionId = motionD, secret = false, status = "ABORTED", threshold = 1, votingOpenedAt = null)
                val secretBallot = insertBallot(c = c, electionId = secretOpen, castAt = "2026-03-01 00:00:00")
                val openBallot = insertBallot(c = c, electionId = openOpen, castAt = "2026-03-02 18:41:07.123456")

                applyV64(c)

                // (4) secret: the constant voting_opened_at; non-secret: untouched
                timestampOf(c = c, table = "election_ballot", id = secretBallot) shouldBe Timestamp.valueOf("2026-03-01 18:30:15")
                timestampOf(c = c, table = "election_ballot", id = openBallot) shouldBe Timestamp.valueOf("2026-03-02 18:41:07.123456")
                // (3) running election raised, finished ones keep their historical threshold
                intOf(c = c, table = "election", column = "tally_threshold", id = secretOpen) shouldBe 2
                intOf(c = c, table = "election", column = "tally_threshold", id = openOpen) shouldBe 2
                intOf(c = c, table = "election", column = "tally_threshold", id = tallied) shouldBe 1
                intOf(c = c, table = "election", column = "tally_threshold", id = aborted) shouldBe 1
                // (1) shadow column
                activeMotionOf(c = c, id = secretOpen) shouldBe motionA
                activeMotionOf(c = c, id = tallied) shouldBe motionC
                activeMotionOf(c = c, id = aborted) shouldBe null

                // the unique index bites for a second non-aborted election on motion A, but not for an aborted one
                (
                    runCatching {
                        insertElectionWithActive(
                            c = c,
                            motionId = motionA,
                            status = "PREPARATION",
                            activeMotion = motionA,
                        )
                    }.exceptionOrNull() is SQLException
                ) shouldBe
                    true
                insertElectionWithActive(c = c, motionId = motionA, status = "ABORTED", activeMotion = null)
                // CHECKs: ABORTED with an active id, non-aborted without one, mismatching id
                runCatching {
                    insertElectionWithActive(
                        c = c,
                        motionId = UUID.randomUUID(),
                        status = "ABORTED",
                        activeMotion = UUID.randomUUID(),
                    )
                }.isFailure shouldBe
                    true
                runCatching {
                    insertElectionWithActive(c = c, motionId = UUID.randomUUID(), status = "PREPARATION", activeMotion = null)
                }.isFailure shouldBe
                    true
                runCatching {
                    insertElectionWithActive(
                        c = c,
                        motionId = UUID.randomUUID(),
                        status = "PREPARATION",
                        activeMotion = UUID.randomUUID(),
                    )
                }.isFailure shouldBe
                    true
                // threshold floor for a running election, but not for a TALLIED one
                runCatching {
                    insertElectionWithActive(
                        c = c,
                        motionId = UUID.randomUUID(),
                        status = "OPEN",
                        activeMotion = null,
                        threshold = 1,
                    )
                }.isFailure shouldBe
                    true
                val m = UUID.randomUUID()
                runCatching {
                    insertElectionWithActive(
                        c = c,
                        motionId = m,
                        status = "OPEN",
                        activeMotion = m,
                        threshold = 1,
                    )
                }.isFailure shouldBe
                    true

                // (2) majority fraction: valid 2/3 and 1/2, invalid 1/3, 101/100, 0/1, half-set
                fun fraction(
                    n: Int?,
                    d: Int?,
                ): Boolean {
                    val mm = UUID.randomUUID()
                    return runCatching {
                        insertElectionWithActive(
                            c = c,
                            motionId = mm,
                            status = "PREPARATION",
                            activeMotion = mm,
                            numerator = n,
                            denominator = d,
                        )
                    }.isSuccess
                }
                fraction(2, 3) shouldBe true
                fraction(1, 2) shouldBe true
                fraction(null, null) shouldBe true
                fraction(1, 3) shouldBe false
                fraction(101, 100) shouldBe false
                fraction(0, 1) shouldBe false
                fraction(3, null) shouldBe false
                // (5) option positions are unique within an election
                insertOption(c = c, electionId = secretOpen, position = 0)
                insertOption(c = c, electionId = secretOpen, position = 1)
                (runCatching { insertOption(c = c, electionId = secretOpen, position = 1) }.exceptionOrNull() is SQLException) shouldBe true
                insertOption(c = c, electionId = openOpen, position = 0)
            }
        }

        test("the V64 data UPDATE statements are idempotent") {
            freshConnection("idempotent").use { c ->
                val motion = UUID.randomUUID()
                val e =
                    insertElection(
                        c = c,
                        motionId = motion,
                        secret = true,
                        status = "OPEN",
                        threshold = 1,
                        votingOpenedAt = "2026-03-01 18:30:15",
                    )
                val b = insertBallot(c = c, electionId = e, castAt = "2026-03-01 00:00:00")
                applyV64(c)
                c.createStatement().use { s ->
                    s.execute("UPDATE election SET tally_threshold = 2 WHERE tally_threshold < 2 AND status NOT IN ('TALLIED', 'ABORTED')")
                    s.execute(
                        "UPDATE election_ballot SET cast_at = (SELECT COALESCE(e.voting_opened_at, e.opened_at) FROM election e " +
                            "WHERE e.id = election_ballot.election_id) WHERE election_id IN (SELECT id FROM election WHERE secret = TRUE)",
                    )
                }
                timestampOf(c = c, table = "election_ballot", id = b) shouldBe Timestamp.valueOf("2026-03-01 18:30:15")
                intOf(c = c, table = "election", column = "tally_threshold", id = e) shouldBe 2
            }
        }

        test("a secret election that never opened voting falls back to opened_at") {
            freshConnection("fallback").use { c ->
                val e =
                    insertElection(
                        c = c,
                        motionId = UUID.randomUUID(),
                        secret = true,
                        status = "CLOSED",
                        threshold = 2,
                        votingOpenedAt = null,
                    )
                val b = insertBallot(c = c, electionId = e, castAt = "2026-03-05 00:00:00")
                applyV64(c)
                timestampOf(c = c, table = "election_ballot", id = b) shouldBe Timestamp.valueOf("2026-03-01 10:00:00")
            }
        }

        test("two non-aborted elections for one motion make V64 fail instead of silently continuing") {
            freshConnection("duplicate").use { c ->
                val motion = UUID.randomUUID()
                insertElection(c = c, motionId = motion, secret = false, status = "PREPARATION", threshold = 2, votingOpenedAt = null)
                insertElection(
                    c = c,
                    motionId = motion,
                    secret = false,
                    status = "OPEN",
                    threshold = 2,
                    votingOpenedAt = "2026-03-01 18:30:15",
                )
                runCatching { applyV64(c) }.isFailure shouldBe true
            }
        }

        test("duplicate option positions within one election make V64 fail") {
            freshConnection("dup-position").use { c ->
                val e =
                    insertElection(
                        c = c,
                        motionId = UUID.randomUUID(),
                        secret = false,
                        status = "PREPARATION",
                        threshold = 2,
                        votingOpenedAt = null,
                    )
                insertOption(c = c, electionId = e, position = 0)
                insertOption(c = c, electionId = e, position = 0)
                runCatching { applyV64(c) }.isFailure shouldBe true
            }
        }

        test("V64 on an empty database succeeds") {
            freshConnection("empty").use { c ->
                applyV64(c)
                c.createStatement().use { it.execute("SELECT active_motion_id FROM election") }
            }
        }
    })

private fun createPreV64Schema(c: Connection) {
    c.createStatement().use { s ->
        s.execute(
            """
            CREATE TABLE election (
                id UUID NOT NULL PRIMARY KEY,
                title VARCHAR(300) NOT NULL,
                election_type VARCHAR(13) NOT NULL,
                secret BOOLEAN NOT NULL,
                seat_count INTEGER NOT NULL,
                target_committee_id UUID NULL,
                target_role VARCHAR(12) NULL,
                required_majority_percent INTEGER NOT NULL,
                status VARCHAR(23) NOT NULL,
                opened_by UUID NOT NULL,
                opened_at TIMESTAMP NOT NULL,
                candidate_list_approved_at TIMESTAMP NULL,
                voting_opened_at TIMESTAMP NULL,
                voting_closed_at TIMESTAMP NULL,
                tally_threshold INTEGER NOT NULL,
                tally_run_at TIMESTAMP NULL,
                motion_id UUID NOT NULL,
                meeting_id UUID NOT NULL,
                resolution_id UUID NULL
            )
            """.trimIndent(),
        )
        s.execute(
            "CREATE TABLE election_ballot (id UUID NOT NULL PRIMARY KEY, receipt_code VARCHAR(40) NOT NULL, " +
                "cast_at TIMESTAMP NOT NULL, election_id UUID NOT NULL, member_id UUID NULL)",
        )
        s.execute(
            "CREATE TABLE election_option (id UUID NOT NULL PRIMARY KEY, label VARCHAR(200) NOT NULL, position INTEGER NOT NULL, " +
                "candidacy_id UUID NULL, election_id UUID NOT NULL)",
        )
    }
}

private fun applyV64(c: Connection) {
    val stream =
        requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V64__election_integrity.sql")) {
            "V64__election_integrity.sql not found on the test classpath"
        }
    stream.use { RunScript.execute(c, InputStreamReader(it, Charsets.UTF_8)) }
}

private fun insertElection(
    c: Connection,
    motionId: UUID,
    secret: Boolean,
    status: String,
    threshold: Int,
    votingOpenedAt: String?,
): UUID {
    val id = UUID.randomUUID()
    c
        .prepareStatement(
            "INSERT INTO election (id, title, election_type, secret, seat_count, required_majority_percent, status, " +
                "opened_by, opened_at, " +
                "voting_opened_at, tally_threshold, motion_id, meeting_id) VALUES (?, 't', 'YES_NO', ?, 0, 50, ?, ?, '2026-03-01 10:00:00', ?, ?, ?, ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setBoolean(2, secret)
            ps.setString(3, status)
            ps.setObject(4, UUID.randomUUID())
            ps.setTimestamp(5, votingOpenedAt?.let { Timestamp.valueOf(it) })
            ps.setInt(6, threshold)
            ps.setObject(7, motionId)
            ps.setObject(8, UUID.randomUUID())
            ps.executeUpdate()
        }
    return id
}

/** Insert against the POST-V64 shape. */
private fun insertElectionWithActive(
    c: Connection,
    motionId: UUID,
    status: String,
    activeMotion: UUID?,
    threshold: Int = 2,
    numerator: Int? = null,
    denominator: Int? = null,
) {
    c
        .prepareStatement(
            "INSERT INTO election (id, title, election_type, secret, seat_count, required_majority_percent, status, " +
                "opened_by, opened_at, " +
                "tally_threshold, motion_id, meeting_id, active_motion_id, required_majority_numerator, required_majority_denominator) " +
                "VALUES (?, 't', 'YES_NO', FALSE, 0, 50, ?, ?, '2026-03-01 10:00:00', ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, status)
            ps.setObject(3, UUID.randomUUID())
            ps.setInt(4, threshold)
            ps.setObject(5, motionId)
            ps.setObject(6, UUID.randomUUID())
            ps.setObject(7, activeMotion)
            ps.setObject(8, numerator)
            ps.setObject(9, denominator)
            ps.executeUpdate()
        }
}

private fun insertBallot(
    c: Connection,
    electionId: UUID,
    castAt: String,
): UUID {
    val id = UUID.randomUUID()
    c.prepareStatement("INSERT INTO election_ballot (id, receipt_code, cast_at, election_id) VALUES (?, ?, ?, ?)").use { ps ->
        ps.setObject(1, id)
        ps.setString(2, id.toString().take(40))
        ps.setTimestamp(3, Timestamp.valueOf(castAt))
        ps.setObject(4, electionId)
        ps.executeUpdate()
    }
    return id
}

private fun insertOption(
    c: Connection,
    electionId: UUID,
    position: Int,
) {
    c.prepareStatement("INSERT INTO election_option (id, label, position, election_id) VALUES (?, 'o', ?, ?)").use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setInt(2, position)
        ps.setObject(3, electionId)
        ps.executeUpdate()
    }
}

private fun timestampOf(
    c: Connection,
    table: String,
    id: UUID,
): Timestamp =
    c.prepareStatement("SELECT cast_at FROM $table WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            rs.next()
            rs.getTimestamp(1)
        }
    }

private fun intOf(
    c: Connection,
    table: String,
    column: String,
    id: UUID,
): Int =
    c.prepareStatement("SELECT $column FROM $table WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

private fun activeMotionOf(
    c: Connection,
    id: UUID,
): UUID? =
    c.prepareStatement("SELECT active_motion_id FROM election WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            rs.next()
            rs.getObject(1, UUID::class.java)
        }
    }
