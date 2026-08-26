package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.h2.tools.RunScript
import java.io.InputStreamReader
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

private val PRE_V10_STATUSES = listOf("APPLICATION", "ACTIVE", "GUEST", "WITHDRAWN", "REJECTED", "FRIEND")

/**
 * V1.2.11 (PdV-CSV-Import): boots a FRESH H2-in-PostgreSQL-mode database, hand-builds the
 * PRE-V10 `member` schema (the six-literal CHECK, no `external_reference` column -- exactly the
 * shape `V1__baseline.sql` had BEFORE this wave edited it in place, i.e. the shape `pdv2`/ELB are
 * still running against per V10's own file-header note), inserts one row per existing literal,
 * applies `V10` verbatim from the classpath resource, and asserts every one of that migration's
 * documented guarantees.
 *
 * Deliberately does NOT go through [DatabaseConfig]/Flyway -- [DatabaseConfig] always migrates
 * against the CURRENT (already-DONOR/DECEASED-ready) `V1__baseline.sql`, which cannot exercise
 * `V10`'s real-work path (it is a no-op there by construction, see that file's own header comment
 * "Idempotent by construction"). Modeled directly on [MemberStatusMigrationTest] (the V3
 * equivalent) -- same reasoning, same structure.
 */
class MemberStatusDonorDeceasedMigrationTest :
    FunSpec({
        test(
            "V10 migration widens the member.status CHECK to DONOR/DECEASED, adds external_reference, leaves existing rows untouched, and a second run is a clean no-op",
        ) {
            val jdbcUrl = "jdbc:h2:mem:member-donor-deceased-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreV10Schema(connection = connection, checkConstraintName = "chk_member_status")
                val existingRowIds = insertStatusRows(connection)

                applyV10Migration(connection)

                // (a) Existing rows are untouched -- same values, same row count.
                PRE_V10_STATUSES.forEach { status ->
                    statusOf(connection = connection, id = existingRowIds.getValue(status)) shouldBe status
                }
                tableRowCount(connection = connection, tableName = "member") shouldBe PRE_V10_STATUSES.size.toLong()

                // (b) DONOR and DECEASED are now accepted.
                insertMemberRow(connection = connection, status = "DONOR")
                insertMemberRow(connection = connection, status = "DECEASED")
                countWhereStatusIn(connection = connection, statuses = setOf("DONOR", "DECEASED")) shouldBe 2L

                // (c) A literal outside the widened set still violates the CHECK constraint --
                // proves the CHECK is actually enforced, not accidentally dropped.
                val violatesCheck = runCatching { insertMemberRow(connection = connection, status = "FOERDERER") }
                violatesCheck.isFailure shouldBe true
                (violatesCheck.exceptionOrNull() is SQLException) shouldBe true

                // (d) external_reference exists and is usable.
                connection.createStatement().use { stmt ->
                    stmt.execute(
                        "UPDATE member SET external_reference = 'P-000123' WHERE status = 'DONOR'",
                    )
                }
                externalReferenceWhereStatus(connection = connection, status = "DONOR") shouldBe "P-000123"

                // (e) Running V10 a SECOND time is a clean no-op -- row count unchanged, no exception.
                val rowCountBeforeSecondRun = tableRowCount(connection = connection, tableName = "member")
                applyV10Migration(connection)
                tableRowCount(connection = connection, tableName = "member") shouldBe rowCountBeforeSecondRun
                PRE_V10_STATUSES.forEach { status ->
                    statusOf(connection = connection, id = existingRowIds.getValue(status)) shouldBe status
                }
            }
        }

        test("V10 also succeeds against the Postgres-auto-generated unnamed constraint name (member_status_check)") {
            val jdbcUrl = "jdbc:h2:mem:member-donor-deceased-migration-unnamed-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.autoCommit = true
                // Same PRE-V10 shape, but the CHECK constraint carries the OTHER name V10's
                // dual-DROP targets -- the exact name PostgreSQL auto-generates for an inline,
                // unnamed single-column CHECK (<table>_<column>_check). Exercises the second of
                // V10's two `DROP CONSTRAINT IF EXISTS` branches.
                createPreV10Schema(connection = connection, checkConstraintName = "member_status_check")

                applyV10Migration(connection)

                insertMemberRow(connection = connection, status = "DONOR")
                insertMemberRow(connection = connection, status = "DECEASED")
                countWhereStatusIn(connection = connection, statuses = setOf("DONOR", "DECEASED")) shouldBe 2L
            }
        }
    })

private fun createPreV10Schema(
    connection: Connection,
    checkConstraintName: String,
) {
    connection.createStatement().use { stmt ->
        stmt.execute(
            """
            CREATE TABLE member (
                id UUID NOT NULL PRIMARY KEY,
                display_name VARCHAR(200) NOT NULL,
                email VARCHAR(320) NOT NULL UNIQUE,
                status VARCHAR(11) NOT NULL,
                joined_at DATE NOT NULL,
                CONSTRAINT $checkConstraintName CHECK (status IN ('APPLICATION', 'ACTIVE', 'GUEST', 'WITHDRAWN', 'REJECTED', 'FRIEND'))
            )
            """.trimIndent(),
        )
    }
}

private fun insertStatusRows(connection: Connection): Map<String, UUID> {
    val ids = mutableMapOf<String, UUID>()
    PRE_V10_STATUSES.forEach { status ->
        val id = UUID.randomUUID()
        ids[status] = id
        insertMemberRow(connection = connection, status = status, id = id)
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

private fun externalReferenceWhereStatus(
    connection: Connection,
    status: String,
): String? =
    connection.prepareStatement("SELECT external_reference FROM member WHERE status = ?").use { ps ->
        ps.setString(1, status)
        ps.executeQuery().use { rs ->
            check(rs.next()) { "no member row with status $status found" }
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

/** Executes the REAL `V10__member_donor_deceased_and_external_reference.sql` file from the classpath verbatim, via H2's own script runner -- never a hand-copied/abbreviated re-statement of it. */
private fun applyV10Migration(connection: Connection) {
    val resourceStream =
        requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream(
                "db/migration/V10__member_donor_deceased_and_external_reference.sql",
            ),
        ) {
            "V10__member_donor_deceased_and_external_reference.sql not found on the test classpath"
        }
    resourceStream.use { stream ->
        RunScript.execute(connection, InputStreamReader(stream, Charsets.UTF_8))
    }
}
