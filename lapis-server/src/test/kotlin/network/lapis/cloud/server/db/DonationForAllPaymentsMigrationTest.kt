package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * `V175__donation_for_all_payments.sql` on the upgrade path V174 -> V175, on H2 AND PostgreSQL: every captured DONATION
 payment with a positive amount gets a
 * donation (bank with one POSTED line: the booking day; PayPal, Stripe, several lines: none; anonymous: no member), earlier
 unlinked conversions are
 * linked only when the match is unambiguous in both directions, the foreign key is back afterwards and the helper table is
 gone. All data is synthetic.
 */
abstract class DonationForAllPaymentsMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        val memberId = UUID.randomUUID()
        val otherMemberId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val importId = UUID.randomUUID()
        val legacyText = "Altbestand vor V175: nachträglich mit Spende verknüpft, Stichtag ohne Begründung erfasst"

        fun MigrationHarness.exec(sql: String) = connection { c -> c.createStatement().use { it.execute(sql) } }

        fun MigrationHarness.rows(sql: String): List<List<String>> =
            connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        fun MigrationHarness.rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun MigrationHarness.baseData() {
            flyway("174").migrate()
            listOf(memberId, otherMemberId).forEach {
                exec(
                    "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$it', 'M', 'dn-$it@example.org', " +
                        "'ACTIVE', DATE '2026-01-01')",
                )
            }
            exec(
                "INSERT INTO price_oracle_snapshot (id, anchor_asset, donation_currency, median_price, price_status, source_count, " +
                    "sources_used, price_timestamp, captured_at) VALUES ('$snapshotId', 'GOLD_XAU', 'EUR', 2000, 'LIVE', 2, 'a,b', " +
                    "TIMESTAMP '2026-11-20 10:00:00', TIMESTAMP '2026-11-20 10:00:00')",
            )
            exec(
                "INSERT INTO bank_statement_import (id, format, dialect, file_name, file_size_bytes, file_digest, " +
                    "statement_from, statement_to, " +
                    "line_count, duplicate_count, auto_posted_count, uploaded_by, uploaded_at) VALUES ('$importId', 'CSV', " +
                    "'SPARKASSE_CSV', " +
                    "'x.csv', 1, 'dg-$importId', DATE '2026-11-01', DATE '2026-11-25', 1, 0, 0, '$memberId', TIMESTAMP " +
                    "'2026-11-25 00:00:00')",
            )
        }

        var lineNo = 0

        fun MigrationHarness.payment(
            id: UUID,
            member: UUID? = memberId,
            provider: String = "MANUAL",
            status: String = "CAPTURED",
            intent: String = "DONATION",
            amount: String = "10.00",
            currency: String = "EUR",
            receivedAt: String = "2026-11-23 10:00:00",
            note: String? = null,
        ) {
            val noteColumn = if (note != null) ", reconciliation_note" else ""
            val noteValue = if (note != null) ", '$note'" else ""
            exec(
                "INSERT INTO payment_transaction (id, provider, provider_event_id, provider_payment_id, status, amount, currency, " +
                    "intent, received_at, raw_payload_digest, member_id$noteColumn) VALUES ('$id', '$provider', 'e-$id', 'p-$id', " +
                    "'$status', $amount, '$currency', " +
                    "'$intent', TIMESTAMP '$receivedAt', 'd-$id', ${member?.let { "'$it'" } ?: "NULL"}$noteValue)",
            )
        }

        fun MigrationHarness.line(
            payment: UUID,
            booking: String = "2026-11-20",
        ) {
            lineNo++
            exec(
                "INSERT INTO bank_statement_line (id, import_id, fingerprint, line_ordinal, booking_date, amount, currency, status, " +
                    "payment_transaction_id) VALUES ('${UUID.randomUUID()}', '$importId', 'fp-${UUID.randomUUID()}', " +
                    "$lineNo, DATE '$booking', " +
                    "10.00, 'EUR', 'POSTED', '$payment')",
            )
        }

        /** A V171-style conversion: SNAPSHOT, MANUAL date, no donation, no payment. */
        fun legacyConversion(
            id: UUID,
            member: UUID = memberId,
            amount: String = "10.00",
            basis: String = "SNAPSHOT",
        ): String =
            "INSERT INTO price_oracle_conversion (id, donation_amount, donation_currency, anchor_asset, anchor_price, " +
                "anchor_units_per_ltr, " +
                "ltr_minted, price_status, source_count, sources_used, price_timestamp, ltr_ledger_entry_id, created_at, member_id" +
                (
                    if (basis ==
                        "SNAPSHOT"
                    ) {
                        ", rate_basis, reference_date, effective_date, fallback_reason, snapshot_id, quorum_required, reference_date_source"
                    } else {
                        ""
                    }
                ) +
                ") VALUES ('$id', $amount, 'EUR', 'GOLD_XAU', 2000, 0.0001, 50.00, 'LIVE', 2, 'a,b', TIMESTAMP '2026-11-20 10:00:00', " +
                "'${UUID.randomUUID()}', TIMESTAMP '2026-11-26 10:00:00', '$member'" +
                (
                    if (basis ==
                        "SNAPSHOT"
                    ) {
                        ", 'SNAPSHOT', DATE '2026-11-20', DATE '2026-11-20', 'NONE', '$snapshotId', 2, 'MANUAL'"
                    } else {
                        ""
                    }
                ) +
                ")"

        /** A conversion that is tied to a donation (as the application writes it). */
        fun linkedConversion(
            amount: String,
            payment: UUID,
            donation: String,
        ): String =
            "INSERT INTO price_oracle_conversion (id, donation_amount, donation_currency, anchor_asset, anchor_price, " +
                "anchor_units_per_ltr, " +
                "ltr_minted, price_status, source_count, sources_used, price_timestamp, ltr_ledger_entry_id, created_at, " +
                "member_id, rate_basis, " +
                "reference_date, effective_date, fallback_reason, snapshot_id, quorum_required, reference_date_source, " +
                "payment_transaction_id, " +
                "donation_id) VALUES ('${UUID.randomUUID()}', $amount, 'EUR', 'GOLD_XAU', 2000, 0.0001, 50.00, 'LIVE', 2, 'a,b', " +
                "TIMESTAMP '2026-11-20 10:00:00', '${UUID.randomUUID()}', TIMESTAMP '2026-11-26 10:00:00', '$memberId', 'SNAPSHOT', " +
                "DATE '2026-11-20', DATE '2026-11-20', 'NONE', '$snapshotId', 2, 'BANK_BOOKING', '$payment', '$donation')"

        lateinit var h: MigrationHarness
        val bankOne = UUID.randomUUID()
        val bankTwo = UUID.randomUUID()
        val bankNone = UUID.randomUUID()
        val paypal = UUID.randomUUID()
        val stripe = UUID.randomUUID()
        val anonymous = UUID.randomUUID()
        val zero = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val refunded = UUID.randomUUID()
        val contribution = UUID.randomUUID()
        val existing = UUID.randomUUID()
        // linking
        val uniqPay = UUID.randomUUID()
        val uniqConv = UUID.randomUUID()
        val twoConvPay = UUID.randomUUID()
        val twoConvA = UUID.randomUUID()
        val twoConvB = UUID.randomUUID()
        val twoPayA = UUID.randomUUID()
        val twoPayB = UUID.randomUUID()
        val twoPayConv = UUID.randomUUID()
        val noMatchConv = UUID.randomUUID()
        val currentConv = UUID.randomUUID()
        val currentPay = UUID.randomUUID()
        val otherCurrencyPay = UUID.randomUUID()
        val otherCurrencyConv = UUID.randomUUID()
        val latePay = UUID.randomUUID()
        val lateConv = UUID.randomUUID()
        val unpostedPay = UUID.randomUUID()

        beforeSpec {
            h = newHarness()
            h.baseData()
            h.payment(bankOne, amount = "11.00")
            h.line(bankOne, booking = "2026-11-18")
            h.payment(bankTwo, amount = "12.00")
            h.line(bankTwo)
            h.line(bankTwo, booking = "2026-11-19")
            h.payment(bankNone, amount = "13.00")
            h.payment(paypal, provider = "PAYPAL", amount = "14.00")
            h.payment(stripe, provider = "STRIPE", amount = "15.00")
            h.payment(anonymous, member = null, provider = "STRIPE", amount = "16.00")
            h.payment(zero, amount = "0.00")
            h.payment(pending, status = "PENDING", amount = "17.00")
            h.payment(refunded, status = "REFUNDED", amount = "18.00")
            h.payment(contribution, intent = "CONTRIBUTION", amount = "19.00")
            h.payment(existing, amount = "20.00")
            h.line(existing, booking = "2026-11-17")
            h.exec(
                "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at) VALUES " +
                    "('$existing', '$existing', '$memberId', 20.00, 'EUR', DATE '2026-11-17', TIMESTAMP '2026-11-23 10:00:00')",
            )
            // linking scenarios (amounts 31..36 are unique per scenario)
            h.payment(uniqPay, amount = "31.00")
            h.exec(legacyConversion(uniqConv, amount = "31.00"))
            h.payment(twoConvPay, amount = "32.00")
            h.exec(legacyConversion(twoConvA, amount = "32.00"))
            h.exec(legacyConversion(twoConvB, amount = "32.00"))
            h.payment(twoPayA, amount = "33.00")
            h.payment(twoPayB, amount = "33.00")
            h.exec(legacyConversion(twoPayConv, amount = "33.00"))
            h.exec(legacyConversion(noMatchConv, amount = "34.00"))
            h.exec(legacyConversion(currentConv, amount = "35.00", basis = "CURRENT"))
            h.payment(currentPay, amount = "35.00")
            h.payment(otherCurrencyPay, amount = "36.00", currency = "USD")
            h.exec(legacyConversion(otherCurrencyConv, amount = "36.00"))
            // the conversion (created 2026-11-26) is OLDER than the only matching payment (received 2026-11-27): must stay unlinked
            h.payment(latePay, provider = "PAYPAL", amount = "37.00", receivedAt = "2026-11-27 10:00:00")
            h.exec(legacyConversion(lateConv, amount = "37.00"))
            // a PSP payment that was stored but not posted (reconciliation note, no journal entry) gets no donation
            h.payment(unpostedPay, provider = "STRIPE", amount = "38.00", note = "Betrag weicht ab")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        fun donation(paymentId: UUID) =
            h.rows("SELECT member_id, amount, currency, booking_date FROM donation WHERE payment_transaction_id = '$paymentId'")

        test("a bank donation with exactly one POSTED line gets the booking day, several or no lines give no booking day") {
            donation(bankOne) shouldBe listOf(listOf(memberId.toString(), "11.00", "EUR", "2026-11-18"))
            donation(bankTwo) shouldBe listOf(listOf(memberId.toString(), "12.00", "EUR", "null"))
            donation(bankNone) shouldBe listOf(listOf(memberId.toString(), "13.00", "EUR", "null"))
        }

        test("PayPal and Stripe donations get a donation without a booking day, an anonymous one without a member") {
            donation(paypal) shouldBe listOf(listOf(memberId.toString(), "14.00", "EUR", "null"))
            donation(stripe) shouldBe listOf(listOf(memberId.toString(), "15.00", "EUR", "null"))
            donation(anonymous) shouldBe listOf(listOf("null", "16.00", "EUR", "null"))
        }

        test("a zero amount, a pending or refunded payment and a contribution get no donation") {
            listOf(zero, pending, refunded, contribution).forEach { donation(it) shouldBe emptyList() }
        }

        test("a donation that exists already (V174) is not duplicated and keeps its values") {
            donation(existing) shouldBe listOf(listOf(memberId.toString(), "20.00", "EUR", "2026-11-17"))
        }

        fun link(conv: UUID) =
            h
                .rows(
                    "SELECT donation_id, payment_transaction_id, reference_date_source, reference_date_override_reason FROM " +
                        "price_oracle_conversion WHERE id = '$conv'",
                ).single()

        test("an unambiguous legacy conversion is linked to its donation, stays MANUAL and gets the legacy text") {
            val donationId = h.rows("SELECT id FROM donation WHERE payment_transaction_id = '$uniqPay'").single().single()
            link(uniqConv) shouldBe listOf(donationId, uniqPay.toString(), "MANUAL", legacyText)
            (legacyText.length in 10..500) shouldBe true
        }

        test("a conversion older than the matching payment stays unlinked and the payment keeps its donation unclaimed") {
            link(lateConv).take(2) shouldBe listOf("null", "null")
            donation(latePay) shouldBe listOf(listOf(memberId.toString(), "37.00", "EUR", "null"))
        }

        test("an unposted PSP payment with a reconciliation note gets no donation in the backfill") {
            donation(unpostedPay) shouldBe emptyList()
        }

        test("ambiguous matches, no match, other currency and a CURRENT conversion stay unlinked") {
            listOf(twoConvA, twoConvB, twoPayConv, noMatchConv, otherCurrencyConv).forEach {
                link(it).take(2) shouldBe listOf("null", "null")
            }
            link(currentConv).take(2) shouldBe listOf("null", "null")
        }

        test("the migration leaves no helper table, the foreign key is back and holds") {
            h.rows("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'v175_donation_link_plan'") shouldBe
                listOf(listOf("0"))
            h.rows(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE LOWER(constraint_name) = 'fk_poc_donation_link'",
            ) shouldBe
                listOf(listOf("1"))
            val donationId = h.rows("SELECT id FROM donation WHERE payment_transaction_id = '$bankOne'").single().single()
            // a conversion that deviates in the amount from its donation is rejected by the composite foreign key
            (h.rejection(linkedConversion("99.00", bankOne, donationId)) != null) shouldBe true
            h.rejection(linkedConversion("11.00", bankOne, donationId)) shouldBe null
        }

        test("an anonymous donation can never be converted: no conversion can point at a donation without a member") {
            val donationId = h.rows("SELECT id FROM donation WHERE payment_transaction_id = '$anonymous'").single().single()
            val sql = linkedConversion("16.00", anonymous, donationId)
            (h.rejection(sql) != null) shouldBe true
        }

        test("re-running the backfill and the link plan changes nothing") {
            val before = h.rows("SELECT COUNT(*) FROM donation")
            h.exec(
                "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at, created_by) " +
                    "SELECT pt.id, pt.id, pt.member_id, pt.amount, UPPER(pt.currency), NULL, pt.received_at, NULL FROM " +
                    "payment_transaction pt " +
                    "WHERE pt.intent = 'DONATION' AND pt.status = 'CAPTURED' AND pt.amount > 0 " +
                    "AND (pt.journal_entry_id IS NOT NULL OR pt.reconciliation_note IS NULL) " +
                    "AND NOT EXISTS (SELECT 1 FROM donation d WHERE d.payment_transaction_id = pt.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM donation d WHERE d.id = pt.id)",
            )
            h.rows("SELECT COUNT(*) FROM donation") shouldBe before
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class DonationForAllPaymentsMigrationTest : DonationForAllPaymentsMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DonationForAllPaymentsMigrationPostgresTest : DonationForAllPaymentsMigrationScenarios({ PostgresMigrationHarness() })
