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

        // Welle V1.2.2 "SEPA-Lastschriftmandate" (vault plan "sepa_v1.2.2_plan.md" Teil 15.3): the
        // SAME kind of test as V7 above, one migration later -- boots a fresh DB, builds the
        // POST-V7/PRE-V8 shape (pre-V7 baseline + the REAL V7 script applied verbatim), applies
        // `V8__sepa_mandates.sql` verbatim from the classpath, and asserts its documented guarantees.
        test(
            "V8 adds contribution.sepa_mandate_id, the three organization_settings SEPA columns, creates the four new " +
                "tables, widens audit_log_entry.entity_type for SEPA_MANDATE/SEPA_DEBIT_BATCH, and a second run is a clean no-op",
        ) {
            val jdbcUrl = "jdbc:h2:mem:sepa-migration-backcompat-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.autoCommit = true
                createPreV7BaselineSchema(connection)
                connection.createStatement().use { stmt ->
                    // V8's FK targets that neither the pre-V7 baseline nor V7 itself create.
                    stmt.execute("CREATE TABLE document (id UUID NOT NULL PRIMARY KEY)")
                }

                // Pre-existing rows inserted BEFORE V7 runs -- same sequence as the V7 test above --
                // because V7's own due_date backfill (period_start -> due_date) only ever runs once,
                // for rows that already exist AT migration time; a row inserted AFTER V7 has already
                // widened due_date to NOT NULL would need due_date supplied explicitly, which
                // insertContribution (written for the pre-V7 shape) deliberately does not do.
                val memberId = UUID.randomUUID()
                insertMember(connection = connection, id = memberId)
                val tierId = UUID.randomUUID()
                insertMembershipTier(connection = connection, id = tierId)
                val contributionId = UUID.randomUUID()
                insertContribution(
                    connection = connection,
                    id = contributionId,
                    memberId = memberId,
                    tierId = tierId,
                    periodStart = "2026-04-01",
                    status = "OPEN",
                )

                applyV7Migration(connection)
                applyV8Migration(connection)

                // (a) contribution.sepa_mandate_id exists, nullable, and the pre-existing row reads NULL.
                tableHasColumn(connection = connection, tableName = "contribution", columnName = "sepa_mandate_id") shouldBe true
                columnIsNotNull(connection = connection, tableName = "contribution", columnName = "sepa_mandate_id") shouldBe false
                sepaMandateIdOf(connection = connection, id = contributionId) shouldBe null

                // (b) organization_settings gains the three new columns, correct default.
                tableHasColumn(connection = connection, tableName = "organization_settings", columnName = "sepa_creditor_id") shouldBe true
                tableHasColumn(connection = connection, tableName = "organization_settings", columnName = "sepa_creditor_name") shouldBe
                    true
                tableHasColumn(
                    connection = connection,
                    tableName = "organization_settings",
                    columnName = "sepa_prenotification_days",
                ) shouldBe true
                sepaPrenotificationDaysOf(connection) shouldBe 14

                // (c) the four new tables exist and are usable.
                tableRowCount(connection = connection, tableName = "sepa_mandate") shouldBe 0L
                tableRowCount(connection = connection, tableName = "sepa_debit_batch") shouldBe 0L
                tableRowCount(connection = connection, tableName = "sepa_debit_item") shouldBe 0L
                tableRowCount(connection = connection, tableName = "sepa_return") shouldBe 0L

                // (d) audit_log_entry.entity_type's widened CHECK accepts both new literals, still
                // accepts a pre-existing one, and still rejects a bogus one (CHECK really re-set, not
                // just dropped).
                insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "SEPA_MANDATE")
                insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "SEPA_DEBIT_BATCH")
                insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "JOURNAL_ENTRY")
                insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "ORGANIZATION_SETTINGS")
                val auditCheckViolation =
                    runCatching { insertAuditLogEntry(connection = connection, id = UUID.randomUUID(), entityType = "BOGUS") }
                auditCheckViolation.isFailure shouldBe true
                (auditCheckViolation.exceptionOrNull() is SQLException) shouldBe true

                // (e) A genuine sepa_mandate row can be inserted and referenced from contribution.
                val mandateId = UUID.randomUUID()
                insertMinimalSepaMandate(connection = connection, id = mandateId, memberId = memberId)
                setContributionSepaMandateId(connection = connection, id = contributionId, mandateId = mandateId)
                sepaMandateIdOf(connection = connection, id = contributionId) shouldBe mandateId.toString()

                // (f) Running V8 a SECOND time is a clean no-op -- row counts unchanged, no exception.
                val mandateCountBefore = tableRowCount(connection = connection, tableName = "sepa_mandate")
                applyV8Migration(connection)
                tableRowCount(connection = connection, tableName = "sepa_mandate") shouldBe mandateCountBefore
                sepaMandateIdOf(connection = connection, id = contributionId) shouldBe mandateId.toString()
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

/** Executes the REAL `V8__sepa_mandates.sql` file from the classpath verbatim, via H2's own script runner -- never a hand-copied/abbreviated re-statement of it. */
private fun applyV8Migration(connection: Connection) {
    val resourceStream =
        requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V8__sepa_mandates.sql"),
        ) {
            "V8__sepa_mandates.sql not found on the test classpath"
        }
    resourceStream.use { stream ->
        RunScript.execute(connection, InputStreamReader(stream, Charsets.UTF_8))
    }
}

private fun sepaMandateIdOf(
    connection: Connection,
    id: UUID,
): String? =
    connection.prepareStatement("SELECT CAST(sepa_mandate_id AS VARCHAR) FROM contribution WHERE id = ?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { rs ->
            check(rs.next()) { "contribution row $id not found" }
            rs.getString(1)
        }
    }

private fun sepaPrenotificationDaysOf(connection: Connection): Int =
    connection.createStatement().use { stmt ->
        stmt
            .executeQuery(
                "SELECT sepa_prenotification_days FROM organization_settings WHERE id = '00000000-0000-0000-0000-0000000000f2'",
            ).use { rs ->
                check(rs.next()) { "organization_settings baseline row not found" }
                rs.getInt(1)
            }
    }

private fun insertMinimalSepaMandate(
    connection: Connection,
    id: UUID,
    memberId: UUID,
) {
    connection
        .prepareStatement(
            "INSERT INTO sepa_mandate (id, member_id, mandate_reference, debtor_name, debtor_iban_ciphertext, " +
                "debtor_iban_set_at, debtor_iban_last4, signature_date, sequence_type, status, granted_at, created_by) " +
                "VALUES (?, ?, ?, 'Erika Mustermann', 'v1:AAAA:BBBB', TIMESTAMP '2026-08-19 00:00:00', '3000', " +
                "DATE '2026-08-19', 'FRST', 'ACTIVE', TIMESTAMP '2026-08-19 00:00:00', ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, memberId)
            ps.setString(3, "LC-${id.toString().take(8)}-20260819-0001")
            ps.setObject(4, memberId)
            ps.executeUpdate()
        }
}

private fun setContributionSepaMandateId(
    connection: Connection,
    id: UUID,
    mandateId: UUID,
) {
    connection.prepareStatement("UPDATE contribution SET sepa_mandate_id = ? WHERE id = ?").use { ps ->
        ps.setObject(1, mandateId)
        ps.setObject(2, id)
        ps.executeUpdate()
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
