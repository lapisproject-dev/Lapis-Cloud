package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * `V173__price_oracle_conversion_payment_link.sql` on the upgrade path V172 -> V173, on H2 AND PostgreSQL: the backfill gives every earlier reference-date
 * conversion the date source MANUAL (before the new CHECK could reject it), an ordinary conversion stays untouched, and the constraints bite:
 * BANK_BOOKING needs a payment, CURRENT has neither source nor payment, SNAPSHOT/MANUAL need a source, one payment is converted once, a linked payment
 * cannot be deleted. All data is synthetic. Since V174 the upgrade runs on to the latest schema, so a payment link also carries its donation row.
 */
abstract class PriceOracleConversionPaymentLinkMigrationScenarios(
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

        fun rejection(sql: String): String? = runCatching { exec(sql) }.exceptionOrNull()?.let { it.message ?: it.javaClass.name }

        val memberId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val rateId = UUID.randomUUID()
        val currentConv = UUID.randomUUID()
        val snapshotConv = UUID.randomUUID()
        val manualConv = UUID.randomUUID()

        fun conversion(
            id: UUID,
            basis: String,
            extra: String = "",
        ): String {
            val provenance =
                when (basis) {
                    "SNAPSHOT" -> ", rate_basis, reference_date, effective_date, fallback_reason, snapshot_id, quorum_required"
                    "MANUAL" -> ", rate_basis, reference_date, effective_date, fallback_reason, reference_rate_id, quorum_required"
                    else -> ""
                }
            val values =
                when (basis) {
                    "SNAPSHOT" -> ", 'SNAPSHOT', DATE '2026-11-20', DATE '2026-11-20', 'NONE', '$snapshotId', 2"
                    "MANUAL" -> ", 'MANUAL', DATE '2026-11-20', DATE '2026-11-20', 'NONE', '$rateId', 2"
                    else -> ""
                }
            return "INSERT INTO price_oracle_conversion (id, donation_amount, donation_currency, anchor_asset, anchor_price, " +
                "anchor_units_per_ltr, ltr_minted, price_status, source_count, sources_used, price_timestamp, ltr_ledger_entry_id, " +
                "created_at, member_id$provenance$extra) VALUES ('$id', 10.00, 'EUR', 'GOLD_XAU', 2000, 0.0001, 50.00, 'LIVE', 2, 'a,b', " +
                "TIMESTAMP '2026-11-20 10:00:00', '${UUID.randomUUID()}', TIMESTAMP '2026-11-26 10:00:00', '$memberId'$values"
        }

        fun payment(id: UUID) =
            exec(
                "INSERT INTO payment_transaction (id, provider, provider_event_id, provider_payment_id, status, amount, currency, " +
                    "intent, received_at, raw_payload_digest, member_id) VALUES ('$id', 'MANUAL', 'e-$id', 'p-$id', " +
                    "'CAPTURED', 10.00, 'EUR', 'DONATION', TIMESTAMP '2026-11-23 10:00:00', 'd-$id', '$memberId')",
            )

        // Since V174 a payment link needs the donation row too (id = payment id, as the V174 backfill does).
        fun donation(id: UUID) =
            exec(
                "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at) " +
                    "VALUES ('$id', '$id', '$memberId', 10.00, 'EUR', DATE '2026-11-23', TIMESTAMP '2026-11-23 10:00:00')",
            )

        beforeSpec {
            h = newHarness()
            h.flyway("172").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'pl-$memberId@example.org', " +
                    "'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO price_oracle_snapshot (id, anchor_asset, donation_currency, median_price, price_status, source_count, " +
                    "sources_used, price_timestamp, captured_at) VALUES ('$snapshotId', 'GOLD_XAU', 'EUR', 2000, 'LIVE', 2, 'a,b', " +
                    "TIMESTAMP '2026-11-20 10:00:00', TIMESTAMP '2026-11-20 10:00:00')",
            )
            exec(
                "INSERT INTO price_oracle_reference_rate (id, anchor_asset, donation_currency, effective_date, price, evidence_text, " +
                    "confirmed_by, confirmed_at) VALUES ('$rateId', 'GOLD_XAU', 'EUR', DATE '2026-11-20', 2000, " +
                    "'Evidence text of twenty chars plus', '$memberId', TIMESTAMP '2026-11-21 10:00:00')",
            )
            exec(conversion(currentConv, "CURRENT") + ")")
            exec(conversion(snapshotConv, "SNAPSHOT") + ")")
            exec(conversion(manualConv, "MANUAL") + ")")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        fun source(id: UUID) =
            rows("SELECT reference_date_source, payment_transaction_id FROM price_oracle_conversion WHERE id = '$id'").single()

        test(
            "the backfill gives earlier SNAPSHOT and MANUAL conversions the source MANUAL without a payment; an ordinary conversion stays NULL",
        ) {
            source(snapshotConv) shouldBe listOf("MANUAL", "null")
            source(manualConv) shouldBe listOf("MANUAL", "null")
            source(currentConv) shouldBe listOf("null", "null")
        }

        test("BANK_BOOKING without a payment is rejected, with a payment it is accepted") {
            (rejection(conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source") + ", 'BANK_BOOKING')") != null) shouldBe true
            val pay = UUID.randomUUID()
            payment(pay)
            donation(pay)
            val id = UUID.randomUUID()
            rejection(
                conversion(id, "SNAPSHOT", ", reference_date_source, payment_transaction_id, donation_id") +
                    ", 'BANK_BOOKING', '$pay', '$pay')",
            ) shouldBe null
            source(id) shouldBe listOf("BANK_BOOKING", pay.toString())
        }

        test("a CURRENT conversion carries neither a source nor a payment; SNAPSHOT and MANUAL need a source") {
            (rejection(conversion(UUID.randomUUID(), "CURRENT", ", reference_date_source") + ", 'MANUAL')") != null) shouldBe true
            val pay = UUID.randomUUID()
            payment(pay)
            (rejection(conversion(UUID.randomUUID(), "CURRENT", ", payment_transaction_id") + ", '$pay')") != null) shouldBe true
            (rejection(conversion(UUID.randomUUID(), "SNAPSHOT") + ")") != null) shouldBe true
            (rejection(conversion(UUID.randomUUID(), "MANUAL") + ")") != null) shouldBe true
        }

        test("an unknown source literal is rejected") {
            (rejection(conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source") + ", 'GUESSED')") != null) shouldBe true
        }

        test("one payment is converted once, and a payment with a conversion cannot be deleted") {
            val pay = UUID.randomUUID()
            payment(pay)
            donation(pay)
            val reason = "Spende am Vortag zugesagt"
            val cols = ", reference_date_source, payment_transaction_id, donation_id, reference_date_override_reason"
            rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols) + ", 'MANUAL', '$pay', '$pay', '$reason')") shouldBe null
            (rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols) + ", 'MANUAL', '$pay', '$pay', '$reason')") != null) shouldBe true
            (rejection("DELETE FROM payment_transaction WHERE id = '$pay'") != null) shouldBe true
        }

        test("several conversions without a payment are fine (NULLs do not collide)") {
            rejection(conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source") + ", 'MANUAL')") shouldBe null
            rejection(conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source") + ", 'MANUAL')") shouldBe null
        }
    })

class PriceOracleConversionPaymentLinkMigrationTest : PriceOracleConversionPaymentLinkMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PriceOracleConversionPaymentLinkMigrationPostgresTest :
    PriceOracleConversionPaymentLinkMigrationScenarios({
        PostgresMigrationHarness()
    })
