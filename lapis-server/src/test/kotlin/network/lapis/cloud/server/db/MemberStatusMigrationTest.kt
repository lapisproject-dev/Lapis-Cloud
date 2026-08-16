package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.h2.tools.RunScript
import java.io.InputStreamReader
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

private val GERMAN_TO_ENGLISH =
    mapOf(
        "ANTRAG" to "APPLICATION",
        "AKTIV" to "ACTIVE",
        "GAST" to "GUEST",
        "AUSGETRETEN" to "WITHDRAWN",
        "ABGELEHNT" to "REJECTED",
    )

/**
 * The critical migration test the wave's own plan calls out by name: boots a FRESH H2-in-
 * PostgreSQL-mode database, hand-builds the PRE-RENAME `member` schema (unnamed CHECK, German
 * literals, no FRIEND-related columns/tables -- exactly the shape `V1__baseline.sql` had BEFORE
 * this wave edited it in place, i.e. the shape `pdv2` is still running against per
 * `V3__member_status_english_and_friend.sql`'s own file-header note), inserts one row per German
 * literal, applies `V3` verbatim from the classpath resource, and asserts every one of that
 * migration's documented guarantees.
 *
 * Deliberately does NOT go through [DatabaseConfig]/Flyway -- [DatabaseConfig] always migrates
 * against the CURRENT (already-English, already-FRIEND-ready) `V1__baseline.sql`, which cannot
 * exercise `V3`'s real-work path (it is a no-op there by construction, see that file's own header
 * comment "Idempotent by construction"). This test is the only place in the suite that ever
 * simulates the actual pre-rename production shape `V3` is written for.
 */
class MemberStatusMigrationTest :
    FunSpec({
        test(
            "V3 migration rewrites every German MemberStatus literal to English, adds FRIEND, and a second run is a clean no-op",
        ) {
            val jdbcUrl = "jdbc:h2:mem:member-status-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreRenameBaselineSchema(connection)
                val germanRowIds = insertGermanLiteralRows(connection)

                applyV3Migration(connection)

                // (a) All five rows carry the English value now, none carry a German one anymore.
                GERMAN_TO_ENGLISH.forEach { (german, english) ->
                    statusOf(connection = connection, id = germanRowIds.getValue(german)) shouldBe english
                }
                countWhereStatusIn(connection = connection, statuses = GERMAN_TO_ENGLISH.keys) shouldBe 0L

                // (b) Inserting a German literal now violates the CHECK constraint.
                val violatesCheck = runCatching { insertMemberRow(connection = connection, status = "AKTIV") }
                violatesCheck.isFailure shouldBe true
                (violatesCheck.exceptionOrNull() is SQLException) shouldBe true

                // (c) Inserting 'FRIEND' succeeds.
                insertMemberRow(connection = connection, status = "FRIEND")
                countWhereStatusIn(connection = connection, statuses = setOf("FRIEND")) shouldBe 1L

                // (d) friend_since/email_verified_at columns and the two new tables exist and are usable.
                connection.createStatement().use { stmt ->
                    stmt.execute("UPDATE member SET friend_since = DATE '2026-08-15' WHERE status = 'FRIEND'")
                    stmt.execute("UPDATE member SET email_verified_at = TIMESTAMP '2026-08-15 12:00:00' WHERE status = 'FRIEND'")
                }
                tableRowCount(connection = connection, tableName = "friend_email_verification_token") shouldBe 0L
                tableRowCount(connection = connection, tableName = "friend_terms_acknowledgment") shouldBe 0L

                // (e) Running V3 a SECOND time is a clean no-op -- row count unchanged, no exception.
                val rowCountBeforeSecondRun = tableRowCount(connection = connection, tableName = "member")
                applyV3Migration(connection)
                tableRowCount(connection = connection, tableName = "member") shouldBe rowCountBeforeSecondRun
                GERMAN_TO_ENGLISH.forEach { (german, english) ->
                    statusOf(connection = connection, id = germanRowIds.getValue(german)) shouldBe english
                }
            }
        }
    })

/**
 * The PRE-RENAME `member` table shape (mirrors `V1__baseline.sql`'s `member`/
 * `conference_guest_consent_acknowledgment` tables as they existed BEFORE this wave, per `V3`'s
 * own file-header note) -- unnamed CHECK, German literal set, no `friend_since`/
 * `email_verified_at` columns, and a NOT NULL `homeserver_url` (V3 makes it nullable for FRIEND).
 *
 * The CHECK constraint is given the EXPLICIT name `member_status_check` here -- the exact name
 * PostgreSQL auto-generates for an inline, unnamed single-column CHECK (`<table>_<column>_check`),
 * which is the real shape `V3`'s dual-DROP logic targets on `pdv2`. H2 does NOT reproduce
 * PostgreSQL's auto-naming convention for an actually-unnamed CHECK (it generates its own internal
 * name like `CONSTRAINT_xyz`), so leaving this constraint unnamed here would test a naming
 * coincidence that never holds on H2 -- neither of `V3`'s two `DROP CONSTRAINT IF EXISTS`
 * statements would match, and the migration would incorrectly appear broken. Naming it explicitly
 * is what actually exercises the real `member_status_check` code path this migration is written
 * for, portably.
 */
private fun createPreRenameBaselineSchema(connection: Connection) {
    connection.createStatement().use { stmt ->
        stmt.execute(
            """
            CREATE TABLE member (
                id UUID NOT NULL PRIMARY KEY,
                display_name VARCHAR(200) NOT NULL,
                email VARCHAR(320) NOT NULL UNIQUE,
                status VARCHAR(11) NOT NULL,
                joined_at DATE NOT NULL,
                CONSTRAINT member_status_check CHECK (status IN ('ANTRAG', 'AKTIV', 'GAST', 'AUSGETRETEN', 'ABGELEHNT'))
            )
            """.trimIndent(),
        )
        stmt.execute(
            """
            CREATE TABLE conference_guest_consent_acknowledgment (
                id UUID NOT NULL PRIMARY KEY,
                member_id UUID NOT NULL REFERENCES member (id),
                homeserver_url VARCHAR(500) NOT NULL
            )
            """.trimIndent(),
        )
    }
}

private fun insertGermanLiteralRows(connection: Connection): Map<String, UUID> {
    val ids = mutableMapOf<String, UUID>()
    GERMAN_TO_ENGLISH.keys.forEach { literal ->
        val id = UUID.randomUUID()
        ids[literal] = id
        insertMemberRow(connection = connection, status = literal, id = id)
    }
    return ids
}

private fun insertMemberRow(
    connection: Connection,
    status: String,
    id: UUID = UUID.randomUUID(),
) {
    connection
        .prepareStatement(
            "INSERT INTO member (id, display_name, email, status, joined_at) VALUES (?, ?, ?, ?, DATE '2026-01-01')",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "Migration Test $status")
            ps.setString(3, "migration-test-${status.lowercase()}-$id@example.org")
            ps.setString(4, status)
            ps.executeUpdate()
        }
}

private fun statusOf(
    connection: Connection,
    id: UUID,
): String =
    connection.prepareStatement("SELECT status FROM member WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            check(rs.next()) { "member row $id not found" }
            rs.getString(1)
        }
    }

private fun countWhereStatusIn(
    connection: Connection,
    statuses: Set<String>,
): Long {
    val placeholders = statuses.joinToString(",") { "?" }
    return connection.prepareStatement("SELECT COUNT(*) FROM member WHERE status IN ($placeholders)").use { ps ->
        statuses.forEachIndexed { index, status -> ps.setString(index + 1, status) }
        ps.executeQuery().use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }
}

private fun tableRowCount(
    connection: Connection,
    tableName: String,
): Long =
    connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

/** Executes the REAL `V3__member_status_english_and_friend.sql` file from the classpath verbatim, via H2's own script runner -- never a hand-copied/abbreviated re-statement of it. */
private fun applyV3Migration(connection: Connection) {
    val resourceStream =
        requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V3__member_status_english_and_friend.sql"),
        ) {
            "V3__member_status_english_and_friend.sql not found on the test classpath"
        }
    resourceStream.use { stream ->
        RunScript.execute(connection, InputStreamReader(stream, Charsets.UTF_8))
    }
}
