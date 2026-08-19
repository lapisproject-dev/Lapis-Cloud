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
 * The critical migration test Welle V1.2.1's own plan calls out by name (vault plan "Lapis Cloud
 * V1.2 -- Zahlungsverkehr" § 9.13 "Rückwärtskompatibilität für pdv2"): boots a FRESH H2-in-
 * PostgreSQL-mode database, hand-builds the PRE-`V7` `membership_tier`/`contribution`/
 * `organization_settings` schema shape (unnamed `contribution_status_check`, no `due_date`/
 * `payment_method`/`payment_term_days`/payment-related `organization_settings` columns -- exactly
 * the shape `pdv2` is still running per this wave's `V1__baseline.sql` in-place-edit comments),
 * inserts pre-existing rows the way a real deployment would have them, applies `V7__payments.sql`
 * verbatim from the classpath, and asserts every one of that migration's documented guarantees.
 * Mirrors [MemberStatusMigrationTest]'s shape exactly.
 *
 * Deliberately does NOT go through [DatabaseConfig]/Flyway -- that always migrates against the
 * CURRENT (already-V1.2.1-shaped) `V1__baseline.sql`, which cannot exercise `V7`'s real-work path
 * (the ADD COLUMN/backfill/ALTER COLUMN SET NOT NULL statements are all no-ops there by
 * construction). This test is the only place in the suite that ever simulates the actual
 * pre-V1.2.1 production shape `V7` is written for.
 */
class PaymentsMigrationBackwardCompatibilityTest :
    FunSpec({
        test(
            "V7 backfills due_date/payment_method/payment_term_days on pre-existing rows, widens the status CHECK, " +
                "adds the organization_settings columns, creates the three new tables, and a second run is a clean no-op",
        ) {
            val jdbcUrl = "jdbc:h2:mem:payments-migration-backcompat-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreV7BaselineSchema(connection)
                val memberId = UUID.randomUUID()
                insertMember(connection = connection, id = memberId)
                val tierId = UUID.randomUUID()
                insertMembershipTier(connection = connection, id = tierId)
                val contributionId = UUID.randomUUID()
                val periodStart = "2026-04-01"
                insertContribution(
                    connection = connection,
                    id = contributionId,
                    memberId = memberId,
                    tierId = tierId,
                    periodStart = periodStart,
                    status = "OPEN",
                )

                applyV7Migration(connection)

                // (a) due_date backfilled from period_start, column is now NOT NULL.
                dueDateOf(connection = connection, id = contributionId) shouldBe periodStart
                columnIsNotNull(connection = connection, tableName = "contribution", columnName = "due_date") shouldBe true

                // (b) payment_method defaults to MANUAL on the pre-existing row.
                paymentMethodOf(connection = connection, id = contributionId) shouldBe "MANUAL"

                // (c) The widened status CHECK accepts every one of the four new literals.
                listOf("DEBIT_SCHEDULED", "DEBIT_SUBMITTED", "RETURNED", "IN_DUNNING").forEach { literal ->
                    updateContributionStatus(connection = connection, id = contributionId, status = literal)
                    statusOf(connection = connection, id = contributionId) shouldBe literal
                }

                // (d) An invalid status literal still violates the CHECK.
                val violatesCheck = runCatching { updateContributionStatus(connection = connection, id = contributionId, status = "BOGUS") }
                violatesCheck.isFailure shouldBe true
                (violatesCheck.exceptionOrNull() is SQLException) shouldBe true

                // (e) membership_tier.payment_term_days exists, defaults to 14 for the pre-existing row.
                paymentTermDaysOf(connection = connection, id = tierId) shouldBe 14

                // (f) organization_settings gains all six new columns, correct defaults, correct types.
                tableHasColumn(connection = connection, tableName = "organization_settings", columnName = "sepa_debit_enabled") shouldBe
                    true
                tableHasColumn(
                    connection = connection,
                    tableName = "organization_settings",
                    columnName = "payment_gateway_enabled",
                ) shouldBe true
                tableHasColumn(
                    connection = connection,
                    tableName = "organization_settings",
                    columnName = "payment_gateway_provider",
                ) shouldBe true
                tableHasColumn(
                    connection = connection,
                    tableName = "organization_settings",
                    columnName = "payment_bank_account_id",
                ) shouldBe true
                tableHasColumn(
                    connection = connection,
                    tableName = "organization_settings",
                    columnName = "payment_fee_account_id",
                ) shouldBe true
                tableHasColumn(
                    connection = connection,
                    tableName = "organization_settings",
                    columnName = "contribution_income_account_id",
                ) shouldBe true

                // (g) The three new tables exist and are usable.
                tableRowCount(connection = connection, tableName = "payment_transaction") shouldBe 0L
                tableRowCount(connection = connection, tableName = "sepa_compliance_acknowledgment") shouldBe 0L
                tableRowCount(connection = connection, tableName = "payment_gateway_compliance_acknowledgment") shouldBe 0L

                // (h) Security Round 1 (2026-08-19, MAJOR-2): audit_log_entry.entity_type's widened
                // CHECK accepts the new ORGANIZATION_SETTINGS literal, still accepts a pre-existing
                // literal, and still rejects a bogus one.
                insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "ORGANIZATION_SETTINGS")
                insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "JOURNAL_ENTRY")
                val auditCheckViolation =
                    runCatching { insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "BOGUS") }
                auditCheckViolation.isFailure shouldBe true
                (auditCheckViolation.exceptionOrNull() is SQLException) shouldBe true

                // (i) Running V7 a SECOND time is a clean no-op -- row counts unchanged, no exception.
                val contributionCountBefore = tableRowCount(connection = connection, tableName = "contribution")
                applyV7Migration(connection)
                tableRowCount(connection = connection, tableName = "contribution") shouldBe contributionCountBefore
                dueDateOf(connection = connection, id = contributionId) shouldBe periodStart
            }
        }
    })

/**
 * The PRE-`V7` shape of the three tables this migration touches in place -- unnamed
 * `contribution_status_check`/`membership_tier_billing_interval_check`, no `due_date`/
 * `payment_method`/`payment_term_days`/payment-related `organization_settings` columns. Also
 * creates minimal `member`/`ledger_account`/`journal_entry` tables purely as FK targets `V7`'s new
 * constraints reference -- their own shape is irrelevant to this test.
 */
private fun createPreV7BaselineSchema(connection: Connection) {
    connection.createStatement().use { stmt ->
        stmt.execute("CREATE TABLE member (id UUID NOT NULL PRIMARY KEY)")
        stmt.execute("CREATE TABLE ledger_account (id UUID NOT NULL PRIMARY KEY)")
        stmt.execute("CREATE TABLE journal_entry (id UUID NOT NULL PRIMARY KEY)")
        stmt.execute(
            """
            CREATE TABLE membership_tier (
                id UUID NOT NULL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                description VARCHAR(1000) NOT NULL,
                contribution_amount DECIMAL(12, 2) NOT NULL,
                billing_interval VARCHAR(9) NOT NULL,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                CHECK (billing_interval IN ('MONTHLY', 'QUARTERLY', 'YEARLY'))
            )
            """.trimIndent(),
        )
        stmt.execute(
            """
            CREATE TABLE contribution (
                id UUID NOT NULL PRIMARY KEY,
                period_start DATE NOT NULL,
                period_end DATE NOT NULL,
                amount_due DECIMAL(12, 2) NOT NULL,
                status VARCHAR(7) NOT NULL,
                paid_at TIMESTAMP NULL,
                paid_amount DECIMAL(12, 2) NULL,
                note VARCHAR(1000) NULL,
                created_at TIMESTAMP NOT NULL,
                member_id UUID NOT NULL REFERENCES member(id),
                membership_tier_id UUID NOT NULL REFERENCES membership_tier(id),
                CONSTRAINT contribution_status_check CHECK (status IN ('OPEN', 'PAID', 'WAIVED', 'OVERDUE'))
            )
            """.trimIndent(),
        )
        stmt.execute(
            """
            CREATE TABLE organization_settings (
                id UUID NOT NULL PRIMARY KEY,
                name VARCHAR(300) NOT NULL,
                street VARCHAR(200) NULL,
                postal_code VARCHAR(20) NULL,
                city VARCHAR(200) NULL,
                country VARCHAR(100) NULL,
                bank_iban VARCHAR(34) NULL,
                bank_bic VARCHAR(11) NULL,
                tax_exemption_authority VARCHAR(300) NULL,
                tax_exemption_date DATE NULL,
                is_political_party BOOLEAN NOT NULL DEFAULT FALSE,
                postal_mail_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                politician_ranking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                auction_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                auction_max_value_ltr DECIMAL(18, 2) NULL
            )
            """.trimIndent(),
        )
        stmt.execute("INSERT INTO organization_settings (id, name) VALUES ('00000000-0000-0000-0000-0000000000f2', 'Test e.V.')")
        // Security Round 1 (2026-08-19, MAJOR-2) -- V7 now ALSO widens audit_log_entry.entity_type's
        // CHECK (to add ORGANIZATION_SETTINGS), same dual-DROP-then-ADD pattern as every other
        // in-place CHECK widening this migration performs. Minimal PRE-V7 shape: just the column and
        // the named constraint V7's DROP targets -- the full audit_log_entry shape (sequence_number/
        // occurred_at/etc.) is irrelevant to what V7 actually touches, same "minimal FK-target shape"
        // reasoning the member/ledger_account/journal_entry stub tables above already follow.
        stmt.execute(
            """
            CREATE TABLE audit_log_entry (
                id UUID NOT NULL PRIMARY KEY,
                entity_type VARCHAR(29) NOT NULL,
                CONSTRAINT chk_audit_log_entry_entity_type CHECK (entity_type IN (
                    'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
                    'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
                    'SOCIAL_POST'
                ))
            )
            """.trimIndent(),
        )
    }
}

private fun insertMember(
    connection: Connection,
    id: UUID,
) {
    connection.prepareStatement("INSERT INTO member (id) VALUES (?)").use { ps ->
        ps.setObject(1, id)
        ps.executeUpdate()
    }
}

private fun insertMembershipTier(
    connection: Connection,
    id: UUID,
) {
    connection
        .prepareStatement(
            "INSERT INTO membership_tier (id, name, description, contribution_amount, billing_interval, active) " +
                "VALUES (?, 'Standard', 'Standardbeitrag', 42.50, 'QUARTERLY', TRUE)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
        }
}

private fun insertContribution(
    connection: Connection,
    id: UUID,
    memberId: UUID,
    tierId: UUID,
    periodStart: String,
    status: String,
) {
    connection
        .prepareStatement(
            "INSERT INTO contribution (id, period_start, period_end, amount_due, status, created_at, member_id, membership_tier_id) " +
                "VALUES (?, ?, DATE '2026-06-30', 42.50, ?, TIMESTAMP '2026-04-01 00:00:00', ?, ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setDate(2, java.sql.Date.valueOf(periodStart))
            ps.setString(3, status)
            ps.setObject(4, memberId)
            ps.setObject(5, tierId)
            ps.executeUpdate()
        }
}

private fun updateContributionStatus(
    connection: Connection,
    id: UUID,
    status: String,
) {
    connection.prepareStatement("UPDATE contribution SET status = ? WHERE id = ?").use { ps ->
        ps.setString(1, status)
        ps.setObject(2, id)
        ps.executeUpdate()
    }
}

private fun statusOf(
    connection: Connection,
    id: UUID,
): String = singleStringColumn(connection = connection, sql = "SELECT status FROM contribution WHERE id = ?", id = id)

private fun dueDateOf(
    connection: Connection,
    id: UUID,
): String = singleStringColumn(connection = connection, sql = "SELECT CAST(due_date AS VARCHAR) FROM contribution WHERE id = ?", id = id)

private fun paymentMethodOf(
    connection: Connection,
    id: UUID,
): String = singleStringColumn(connection = connection, sql = "SELECT payment_method FROM contribution WHERE id = ?", id = id)

private fun paymentTermDaysOf(
    connection: Connection,
    id: UUID,
): Int =
    connection.prepareStatement("SELECT payment_term_days FROM membership_tier WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            check(rs.next()) { "membership_tier row $id not found" }
            rs.getInt(1)
        }
    }

private fun singleStringColumn(
    connection: Connection,
    sql: String,
    id: UUID,
): String =
    connection.prepareStatement(sql).use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            check(rs.next()) { "row $id not found" }
            rs.getString(1)
        }
    }

private fun columnIsNotNull(
    connection: Connection,
    tableName: String,
    columnName: String,
): Boolean =
    connection
        .prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        ).use { ps ->
            ps.setString(1, tableName)
            ps.setString(2, columnName)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "column $tableName.$columnName not found" }
                rs.getString(1) == "NO"
            }
        }

private fun tableHasColumn(
    connection: Connection,
    tableName: String,
    columnName: String,
): Boolean =
    connection
        .prepareStatement(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        ).use { ps ->
            ps.setString(1, tableName)
            ps.setString(2, columnName)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1) > 0
            }
        }

private fun insertAuditLogEntry(
    connection: Connection,
    id: UUID,
    entityType: String,
) {
    connection.prepareStatement("INSERT INTO audit_log_entry (id, entity_type) VALUES (?, ?)").use { ps ->
        ps.setObject(1, id)
        ps.setString(2, entityType)
        ps.executeUpdate()
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

/** Executes the REAL `V7__payments.sql` file from the classpath verbatim, via H2's own script runner -- never a hand-copied/abbreviated re-statement of it. */
private fun applyV7Migration(connection: Connection) {
    val resourceStream =
        requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V7__payments.sql"),
        ) {
            "V7__payments.sql not found on the test classpath"
        }
    resourceStream.use { stream ->
        RunScript.execute(connection, InputStreamReader(stream, Charsets.UTF_8))
    }
}
