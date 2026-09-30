package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.h2.tools.RunScript
import java.io.InputStreamReader
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Welle V1.9.18 -- `V60__membership_tier_admin.sql` against the shape a real instance (PdV/ELB/Staging,
 * or any self-hosted one) is running BEFORE it: a `membership_tier` table without `name_key`, possibly
 * holding two tiers whose names differ only in case/whitespace. Same technique as
 * [PaymentsMigrationBackwardCompatibilityTest]: a fresh H2-in-PostgreSQL-mode database, the pre-V60
 * shape built by hand, the migration applied verbatim from the classpath. Deliberately NOT through
 * [DatabaseConfig]/Flyway, which always migrates the CURRENT baseline (where every V60 statement is a
 * no-op by construction).
 */
class MembershipTierMigrationTest :
    FunSpec({
        test(
            "V60 backfills name_key, de-duplicates case-variant names keeping the display names, creates the unique index, and a second run is a no-op",
        ) {
            val url = "jdbc:h2:mem:membership-tier-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(url, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreV60Schema(connection)
                // Ids chosen so that the ORDER of the three duplicates is unambiguous: the lowest id keeps the plain key.
                val first = UUID.fromString("00000000-0000-0000-0000-00000000000a")
                val second = UUID.fromString("00000000-0000-0000-0000-00000000000b")
                val third = UUID.fromString("00000000-0000-0000-0000-00000000000c")
                val unique = UUID.fromString("00000000-0000-0000-0000-00000000000d")
                insertTier(connection = connection, id = third, name = "  STANDARD ")
                insertTier(connection = connection, id = first, name = "Standard")
                insertTier(connection = connection, id = second, name = "standard")
                insertTier(connection = connection, id = unique, name = "Ermaessigt")

                applyV60(connection)

                // display names are untouched
                nameOf(connection = connection, id = first) shouldBe "Standard"
                nameOf(connection = connection, id = second) shouldBe "standard"
                nameOf(connection = connection, id = third) shouldBe "  STANDARD "
                // the lowest id keeps the plain key, the others are disambiguated by their id
                keyOf(connection = connection, id = first) shouldBe "standard"
                keyOf(connection = connection, id = second) shouldBe "standard#$second"
                keyOf(connection = connection, id = third) shouldBe "standard#$third"
                keyOf(connection = connection, id = unique) shouldBe "ermaessigt"
                (listOf(first, second, third, unique).map { keyOf(connection = connection, id = it) }.toSet().size) shouldBe 4

                // the column is NOT NULL now, the unique index exists and bites
                isNotNull(connection = connection, table = "membership_tier", column = "name_key") shouldBe true
                val duplicate =
                    runCatching { insertTierWithKey(connection = connection, id = UUID.randomUUID(), name = "x", key = "ermaessigt") }
                duplicate.isFailure shouldBe true
                (duplicate.exceptionOrNull() is SQLException) shouldBe true
                val missingKey = runCatching { insertTier(connection = connection, id = UUID.randomUUID(), name = "no key") }
                missingKey.isFailure shouldBe true

                // the widened audit CHECK accepts the new literal, still accepts an old one, still rejects a bogus one
                insertAudit(connection = connection, entityType = "MEMBERSHIP_TIER")
                insertAudit(connection = connection, entityType = "REGIONAL_CHAPTER")
                runCatching { insertAudit(connection = connection, entityType = "BOGUS") }.isFailure shouldBe true

                // a second run is a clean no-op
                applyV60(connection)
                keyOf(connection = connection, id = first) shouldBe "standard"
                keyOf(connection = connection, id = second) shouldBe "standard#$second"
            }
        }

        test("V60 on a table that already has a unique name_key for every row changes no key") {
            val url = "jdbc:h2:mem:membership-tier-migration-clean-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(url, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreV60Schema(connection)
                val a = UUID.randomUUID()
                val b = UUID.randomUUID()
                insertTier(connection = connection, id = a, name = "Vollmitglied")
                insertTier(connection = connection, id = b, name = "Foerdermitglied")
                applyV60(connection)
                keyOf(connection = connection, id = a) shouldBe "vollmitglied"
                keyOf(connection = connection, id = b) shouldBe "foerdermitglied"
            }
        }

        test("V60 on an empty table (a fresh instance) succeeds") {
            val url = "jdbc:h2:mem:membership-tier-migration-empty-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(url, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreV60Schema(connection)
                applyV60(connection)
                isNotNull(connection = connection, table = "membership_tier", column = "name_key") shouldBe true
            }
        }
    })

private fun createPreV60Schema(connection: Connection) {
    connection.createStatement().use { stmt ->
        stmt.execute(
            """
            CREATE TABLE membership_tier (
                id UUID NOT NULL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                description VARCHAR(1000) NOT NULL,
                contribution_amount DECIMAL(12, 2) NOT NULL,
                billing_interval VARCHAR(9) NOT NULL,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                payment_term_days INT NOT NULL DEFAULT 14
            )
            """.trimIndent(),
        )
        stmt.execute(
            """
            CREATE TABLE audit_log_entry (
                id UUID NOT NULL PRIMARY KEY,
                entity_type VARCHAR(29) NOT NULL,
                CONSTRAINT chk_audit_log_entry_entity_type CHECK (entity_type IN (
                    'JOURNAL_ENTRY', 'REGIONAL_CHAPTER', 'REGIONAL_CHAPTER_OFFICER'
                ))
            )
            """.trimIndent(),
        )
    }
}

private fun applyV60(connection: Connection) {
    val stream =
        requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V60__membership_tier_admin.sql")) {
            "V60__membership_tier_admin.sql not found on the test classpath"
        }
    stream.use { RunScript.execute(connection, InputStreamReader(it, Charsets.UTF_8)) }
}

private fun insertTier(
    connection: Connection,
    id: UUID,
    name: String,
) {
    connection
        .prepareStatement(
            "INSERT INTO membership_tier (id, name, description, contribution_amount, billing_interval) VALUES (?, ?, '', 10.00, 'MONTHLY')",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, name)
            ps.executeUpdate()
        }
}

private fun insertTierWithKey(
    connection: Connection,
    id: UUID,
    name: String,
    key: String,
) {
    connection
        .prepareStatement(
            "INSERT INTO membership_tier (id, name, description, contribution_amount, billing_interval, name_key) " +
                "VALUES (?, ?, '', 10.00, 'MONTHLY', ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, name)
            ps.setString(3, key)
            ps.executeUpdate()
        }
}

private fun insertAudit(
    connection: Connection,
    entityType: String,
) {
    connection.prepareStatement("INSERT INTO audit_log_entry (id, entity_type) VALUES (?, ?)").use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, entityType)
        ps.executeUpdate()
    }
}

private fun stringOf(
    connection: Connection,
    column: String,
    id: UUID,
): String =
    connection.prepareStatement("SELECT $column FROM membership_tier WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            check(rs.next())
            rs.getString(1)
        }
    }

private fun nameOf(
    connection: Connection,
    id: UUID,
) = stringOf(connection = connection, column = "name", id = id)

private fun keyOf(
    connection: Connection,
    id: UUID,
) = stringOf(connection = connection, column = "name_key", id = id)

private fun isNotNull(
    connection: Connection,
    table: String,
    column: String,
): Boolean =
    connection
        .prepareStatement("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?")
        .use { ps ->
            ps.setString(1, table)
            ps.setString(2, column)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "column $table.$column not found" }
                rs.getString(1) == "NO"
            }
        }
