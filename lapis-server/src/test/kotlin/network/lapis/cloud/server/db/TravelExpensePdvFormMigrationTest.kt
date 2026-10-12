package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.122 -- `V97__travel_expense_pdv_form.sql` on the upgrade path V96 -> V97, on H2 AND on
 * PostgreSQL: a pre-V97 report and its MILEAGE / PER_DIEM / RECEIPTED lines survive untouched and
 * still satisfy every (re-created) constraint, the widened `kind` column takes the new literals,
 * the new CHECKs reject what they must, and a re-run is a no-op.
 */
abstract class TravelExpensePdvFormMigrationScenarios(
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
        val reportId = UUID.randomUUID()
        val submittedReportId = UUID.randomUUID()
        val mileageLine = UUID.randomUUID()
        val perDiemLine = UUID.randomUUID()
        val receiptedLine = UUID.randomUUID()

        fun newLine(
            kind: String,
            columns: String,
            values: String,
            amount: String,
            report: UUID = reportId,
        ): String {
            val id = UUID.randomUUID()
            exec(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, $columns, amount, created_at) " +
                    "VALUES ('$id', '$report', '$kind', 'x', $values, $amount, TIMESTAMP '2026-01-10 10:00:00')",
            )
            return id.toString()
        }

        beforeSpec {
            h = newHarness()
            h.flyway("96").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) " +
                    "VALUES ('$memberId', 'M', 'te-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO travel_expense_report (id, subject_member_id, status, purpose, travel_from, travel_to, total_amount, " +
                    "created_at, requested_by) VALUES ('$reportId', '$memberId', 'DRAFT', 'Reise', DATE '2026-01-10', " +
                    "DATE '2026-01-12', 91.00, TIMESTAMP '2026-01-10 10:00:00', '$memberId')",
            )
            exec(
                "INSERT INTO travel_expense_report (id, subject_member_id, status, purpose, travel_from, travel_to, total_amount, " +
                    "created_at, requested_by, submitted_at) VALUES ('$submittedReportId', '$memberId', 'REQUESTED', 'Alt', " +
                    "DATE '2026-01-10', DATE '2026-01-10', 10.00, TIMESTAMP '2026-01-10 10:00:00', '$memberId', " +
                    "TIMESTAMP '2026-01-11 10:00:00')",
            )
            exec(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, kilometers, rate_snapshot, amount, created_at) " +
                    "VALUES ('$mileageLine', '$reportId', 'MILEAGE', 'Fahrt', 100.00, 0.3000, 30.00, TIMESTAMP '2026-01-10 10:00:00')",
            )
            exec(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, days, rate_snapshot, amount, created_at) " +
                    "VALUES ('$perDiemLine', '$reportId', 'PER_DIEM', 'Tage', 3, 14.0000, 42.00, TIMESTAMP '2026-01-10 10:00:00')",
            )
            exec(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, amount, created_at) " +
                    "VALUES ('$receiptedLine', '$reportId', 'RECEIPTED', 'Bahn', 19.00, TIMESTAMP '2026-01-10 10:00:00')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("pre-V97 rows survive: three legacy lines keep kind and amount, the new columns take their defaults") {
            rows("SELECT kind, amount FROM travel_expense_line WHERE report_id = '$reportId' ORDER BY kind")
                .map { it[0] } shouldBe listOf("MILEAGE", "PER_DIEM", "RECEIPTED")
            rows("SELECT breakfast_provided, deduction_amount, day_date FROM travel_expense_line WHERE id = '$mileageLine'")
                .single()[2] shouldBe "null"
            rows("SELECT advance_amount, payout_choice FROM travel_expense_report WHERE id = '$submittedReportId'")
                .single()
                .let { it[1] shouldBe "null" }
            // A legacy line can still be re-written under the re-created constraints.
            exec("UPDATE travel_expense_line SET amount = 31.00 WHERE id = '$mileageLine'")
        }

        test("organization_settings: tier defaults are the PdV template, the mileage rate keeps NO default") {
            rows(
                "SELECT travel_tier1_hours, travel_tier2_hours, travel_tier3_hours, travel_tier1_cents, travel_tier2_cents, " +
                    "travel_tier3_cents, travel_breakfast_pct, travel_lunch_pct, travel_dinner_pct, travel_passenger_cents_per_km, " +
                    "travel_mileage_rate_per_km FROM organization_settings",
            ).single() shouldBe listOf("8", "14", "24", "600", "1200", "2800", "20", "40", "40", "2", "null")
        }

        test("kind is widened: all five new literals can be inserted with their payload shape") {
            newLine("PUBLIC_TRANSPORT", "destination", "NULL", "12.00")
            newLine("HOSPITALITY", "destination", "NULL", "12.00")
            newLine("OTHER", "destination", "NULL", "12.00")
            newLine("ACCOMMODATION", "day_date, days, breakfast_included", "DATE '2026-01-10', 2, TRUE", "100.00")
            newLine("DAY_ALLOWANCE", "day_date, computed_minutes, base_amount", "DATE '2026-01-11', 1440, 28.00", "28.00")
        }

        test("a DAY_ALLOWANCE line may be 0, every other kind must stay strictly positive") {
            newLine("DAY_ALLOWANCE", "day_date, computed_minutes, base_amount", "DATE '2026-01-12', 0, 0.00", "0.00")
            rejected(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, amount, created_at) VALUES " +
                    "('${UUID.randomUUID()}', '$reportId', 'OTHER', 'x', 0.00, TIMESTAMP '2026-01-10 10:00:00')",
            ) shouldBe true
        }

        test("shape checks: a day line without day_date, an accommodation without nights, a stray day_date on OTHER are rejected") {
            rejected(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, computed_minutes, base_amount, amount, created_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$reportId', 'DAY_ALLOWANCE', 'x', 0, 0.00, 0.00, TIMESTAMP '2026-01-10 10:00:00')",
            ) shouldBe true
            rejected(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, day_date, breakfast_included, amount, created_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$reportId', 'ACCOMMODATION', 'x', DATE '2026-01-10', TRUE, 10.00, " +
                    "TIMESTAMP '2026-01-10 10:00:00')",
            ) shouldBe true
            rejected(
                "INSERT INTO travel_expense_line (id, report_id, kind, description, day_date, amount, created_at) VALUES " +
                    "('${UUID.randomUUID()}', '$reportId', 'OTHER', 'x', DATE '2026-01-10', 10.00, TIMESTAMP '2026-01-10 10:00:00')",
            ) shouldBe true
        }

        test("passengers 0..8, meal flags and times only on day lines, time_to >= time_from") {
            rejected("UPDATE travel_expense_line SET passenger_count = 9 WHERE id = '$mileageLine'") shouldBe true
            exec("UPDATE travel_expense_line SET passenger_count = 8 WHERE id = '$mileageLine'")
            rejected("UPDATE travel_expense_line SET breakfast_provided = TRUE WHERE id = '$mileageLine'") shouldBe true
            rejected("UPDATE travel_expense_line SET time_from = TIME '08:00:00' WHERE id = '$mileageLine'") shouldBe true
            val day =
                newLine("DAY_ALLOWANCE", "day_date, computed_minutes, base_amount", "DATE '2026-02-01', 0, 0.00", "0.00")
            rejected("UPDATE travel_expense_line SET time_from = TIME '10:00:00', time_to = TIME '09:00:00' WHERE id = '$day'") shouldBe
                true
            exec("UPDATE travel_expense_line SET time_from = TIME '09:00:00', time_to = TIME '10:00:00' WHERE id = '$day'")
        }

        test("report: advance must not be negative, nor exceed the total once submitted (a DRAFT may move freely)") {
            rejected("UPDATE travel_expense_report SET advance_amount = -1 WHERE id = '$reportId'") shouldBe true
            exec("UPDATE travel_expense_report SET advance_amount = 500.00 WHERE id = '$reportId'") // DRAFT: tolerated
            rejected("UPDATE travel_expense_report SET advance_amount = 10.01 WHERE id = '$submittedReportId'") shouldBe true
            exec("UPDATE travel_expense_report SET advance_amount = 10.00 WHERE id = '$submittedReportId'")
            // A DRAFT withdrawn with advance > total must not violate the check (review finding).
            exec("UPDATE travel_expense_report SET status = 'WITHDRAWN' WHERE id = '$reportId'")
            rejected("UPDATE travel_expense_report SET payout_choice = 'NOPE' WHERE id = '$reportId'") shouldBe true
            exec("UPDATE travel_expense_report SET payout_choice = 'DONATION_WAIVER' WHERE id = '$reportId'")
        }

        test("organization_settings checks: ascending tiers, 0..100 percentages, passenger rate, mileage rate at most 1.00") {
            rejected("UPDATE organization_settings SET travel_tier2_hours = 8") shouldBe true
            rejected("UPDATE organization_settings SET travel_tier3_hours = 25") shouldBe true
            rejected("UPDATE organization_settings SET travel_tier1_cents = 1300") shouldBe true
            rejected("UPDATE organization_settings SET travel_tier3_cents = 10001") shouldBe true
            rejected("UPDATE organization_settings SET travel_lunch_pct = 101") shouldBe true
            rejected("UPDATE organization_settings SET travel_passenger_cents_per_km = 21") shouldBe true
            rejected("UPDATE organization_settings SET travel_mileage_rate_per_km = 1.01") shouldBe true
            exec("UPDATE organization_settings SET travel_mileage_rate_per_km = 1.00")
            exec("UPDATE organization_settings SET travel_mileage_rate_per_km = NULL")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class TravelExpensePdvFormMigrationTest : TravelExpensePdvFormMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TravelExpensePdvFormMigrationPostgresTest : TravelExpensePdvFormMigrationScenarios({ PostgresMigrationHarness() })
