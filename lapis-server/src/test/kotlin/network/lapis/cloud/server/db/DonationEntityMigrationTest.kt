package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * `V174__donation_entity.sql` on the upgrade path V173 -> V174, on H2 AND PostgreSQL: the backfill creates a donation only for a captured donation payment
 * of a member with exactly one POSTED bank line (id = payment id, currency upper case), earlier conversions are linked and keep their meaning, the legacy
 * justification text is written for manual dates, and the migration ABORTS (with the named constraint) on an inconsistent V173 row. All data is synthetic.
 */
abstract class DonationEntityMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        val memberId = UUID.randomUUID()
        val otherMemberId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val importId = UUID.randomUUID()

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
            flyway("173").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'dn-$memberId@example.org', " +
                    "'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$otherMemberId', 'O'," +
                    "'dn-$otherMemberId@example.org', " +
                    "'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO price_oracle_snapshot (id, anchor_asset, donation_currency, median_price, price_status, source_count, " +
                    "sources_used, price_timestamp, captured_at) VALUES ('$snapshotId', 'GOLD_XAU', 'EUR', 2000, 'LIVE', 2, 'a,b', " +
                    "TIMESTAMP '2026-11-20 10:00:00', TIMESTAMP '2026-11-20 10:00:00')",
            )
            exec(
                "INSERT INTO bank_statement_import (id, format, dialect, file_name, file_size_bytes, file_digest," +
                    "statement_from, statement_to, " +
                    "line_count, duplicate_count, auto_posted_count, uploaded_by, uploaded_at)" +
                    "VALUES ('$importId', 'CSV', 'SPARKASSE_CSV', 'x.csv', 1, " +
                    "'dg-$importId', DATE '2026-11-01', DATE '2026-11-25', 1, 0, 0, '$memberId', TIMESTAMP '2026-11-25 00:00:00')",
            )
        }

        var lineNo = 0

        fun MigrationHarness.payment(
            id: UUID,
            member: UUID? = memberId,
            status: String = "CAPTURED",
            intent: String = "DONATION",
            amount: String = "10.00",
            currency: String = "EUR",
        ) = exec(
            "INSERT INTO payment_transaction (id, provider, provider_event_id, provider_payment_id, status, amount, currency, intent," +
                "received_at, " +
                "raw_payload_digest, member_id) VALUES ('$id', 'MANUAL', 'e-$id', 'p-$id', '$status', $amount, '$currency', '$intent', " +
                "TIMESTAMP '2026-11-23 10:00:00', 'd-$id', ${member?.let { "'$it'" } ?: "NULL"})",
        )

        fun MigrationHarness.line(
            payment: UUID,
            booking: String = "2026-11-20",
            status: String = "POSTED",
        ) {
            lineNo++
            exec(
                "INSERT INTO bank_statement_line (id, import_id, fingerprint, line_ordinal, booking_date, amount, currency, status, " +
                    "payment_transaction_id)" +
                    "VALUES ('${UUID.randomUUID()}', '$importId', 'fp-${UUID.randomUUID()}', $lineNo, DATE '$booking', " +
                    "10.00, 'EUR', '$status', '$payment')",
            )
        }

        fun conversion(
            id: UUID,
            basis: String,
            extra: String = "",
            amount: String = "10.00",
            member: UUID = memberId,
        ): String {
            val provenance =
                if (basis ==
                    "SNAPSHOT"
                ) {
                    ", rate_basis, reference_date, effective_date, fallback_reason, snapshot_id, quorum_required"
                } else {
                    ""
                }
            val values =
                if (basis == "SNAPSHOT") ", 'SNAPSHOT', DATE '2026-11-20', DATE '2026-11-20', 'NONE', '$snapshotId', 2" else ""
            return "INSERT INTO price_oracle_conversion (id, donation_amount, donation_currency, anchor_asset, anchor_price, " +
                "anchor_units_per_ltr, ltr_minted, price_status, source_count, sources_used, price_timestamp, ltr_ledger_entry_id, " +
                "created_at, member_id$provenance$extra)" +
                "VALUES ('$id', $amount, 'EUR', 'GOLD_XAU', 2000, 0.0001, 50.00, 'LIVE', 2, 'a,b', " +
                "TIMESTAMP '2026-11-20 10:00:00', '${UUID.randomUUID()}', TIMESTAMP '2026-11-26 10:00:00', '$member'$values"
        }

        // ---- the happy upgrade path ----
        lateinit var h: MigrationHarness
        val goodBooking = UUID.randomUUID()
        val goodManual = UUID.randomUUID()
        val anonymous = UUID.randomUUID()
        val noLine = UUID.randomUUID()
        val twoLines = UUID.randomUUID()
        val refunded = UUID.randomUUID()
        val contribution = UUID.randomUUID()
        val unposted = UUID.randomUUID()
        val lowerCase = UUID.randomUUID()
        val convBooking = UUID.randomUUID()
        val convManualPaid = UUID.randomUUID()
        val convManualFree = UUID.randomUUID()
        val convCurrent = UUID.randomUUID()

        beforeSpec {
            h = newHarness()
            h.baseData()
            h.payment(goodBooking)
            h.line(goodBooking)
            h.payment(goodManual)
            h.line(goodManual, booking = "2026-11-21")
            h.payment(anonymous, member = null)
            h.line(anonymous)
            h.payment(noLine)
            h.payment(twoLines)
            h.line(twoLines)
            h.line(twoLines, booking = "2026-11-22")
            h.payment(refunded, status = "REFUNDED")
            h.line(refunded)
            h.payment(contribution, intent = "CONTRIBUTION")
            h.line(contribution)
            h.payment(unposted)
            h.line(unposted, status = "UNMATCHED")
            h.payment(lowerCase, currency = "eur")
            h.line(lowerCase)
            h.exec(
                conversion(
                    convBooking,
                    "SNAPSHOT",
                    ", reference_date_source, payment_transaction_id",
                ) + ", 'BANK_BOOKING', '$goodBooking')",
            )
            h.exec(conversion(convManualPaid, "SNAPSHOT", ", reference_date_source, payment_transaction_id") + ", 'MANUAL', '$goodManual')")
            h.exec(conversion(convManualFree, "SNAPSHOT", ", reference_date_source") + ", 'MANUAL')")
            h.exec(conversion(convCurrent, "CURRENT") + ")")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        fun donationIds() = h.rows("SELECT payment_transaction_id FROM donation").map { it.single() }.toSet()

        test("the backfill creates a donation only for a captured donation payment of a member with exactly one POSTED bank line") {
            donationIds() shouldBe setOf(goodBooking, goodManual, lowerCase).map { it.toString() }.toSet()
        }

        test("a backfilled donation has the payment id as its id, the booking day, an upper case currency and no actor") {
            h.rows(
                "SELECT id, member_id, amount, currency, booking_date, created_by FROM donation WHERE payment_transaction_id = '$goodBooking'",
            ) shouldBe
                listOf(listOf(goodBooking.toString(), memberId.toString(), "10.00", "EUR", "2026-11-20", "null"))
            h.rows("SELECT currency FROM donation WHERE payment_transaction_id = '$lowerCase'") shouldBe listOf(listOf("EUR"))
            h.rows("SELECT booking_date FROM donation WHERE payment_transaction_id = '$goodManual'") shouldBe listOf(listOf("2026-11-21"))
        }

        fun conv(id: UUID) =
            h
                .rows(
                    "SELECT donation_id, payment_transaction_id, reference_date_source, reference_date_override_reason " +
                        "FROM price_oracle_conversion WHERE id = '$id'",
                ).single()

        test("V173 conversions are linked to their donation; a manual date with a payment gets the legacy justification text") {
            conv(convBooking) shouldBe listOf(goodBooking.toString(), goodBooking.toString(), "BANK_BOOKING", "null")
            conv(convManualPaid) shouldBe
                listOf(goodManual.toString(), goodManual.toString(), "MANUAL", "Altbestand vor V174: Stichtag ohne Begründung erfasst")
        }

        test("a conversion without a payment keeps no donation and no justification; a CURRENT conversion stays untouched") {
            conv(convManualFree) shouldBe listOf("null", "null", "MANUAL", "null")
            conv(convCurrent) shouldBe listOf("null", "null", "null", "null")
        }

        test("the legacy justification text fits the 10..500 window") {
            val text = "Altbestand vor V174: Stichtag ohne Begründung erfasst"
            (text.length in 10..500) shouldBe true
        }

        test("a donation is converted once: a second conversion of the same donation or payment is rejected") {
            val pay = UUID.randomUUID()
            h.payment(pay)
            h.line(pay)
            h.exec(
                "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at) VALUES ('${UUID.randomUUID()}', '$pay', '$memberId', 10.00, 'EUR', DATE '2026-11-20', TIMESTAMP '2026-11-23 10:00:00')",
            )
            val donation = h.rows("SELECT id FROM donation WHERE payment_transaction_id = '$pay'").single().single()
            val cols = ", reference_date_source, payment_transaction_id, donation_id"
            h.rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols) + ", 'BANK_BOOKING', '$pay', '$donation')") shouldBe null
            (h.rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols) + ", 'BANK_BOOKING', '$pay', '$donation')") != null) shouldBe true
            // a donation row per payment only
            (
                h.rejection(
                    "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at) VALUES ('${UUID.randomUUID()}', '$pay', '$memberId', 10.00, 'EUR', DATE '2026-11-20', TIMESTAMP '2026-11-23 10:00:00')",
                ) !=
                    null
            ) shouldBe
                true
        }

        test("the constraints bite: amount, member, currency and the justification rules") {
            val pay = UUID.randomUUID()
            h.payment(pay)
            h.line(pay)
            val donation = UUID.randomUUID()
            h.exec(
                "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at) VALUES ('$donation', '$pay', '$memberId', 10.00, 'EUR', DATE '2026-11-20', TIMESTAMP '2026-11-23 10:00:00')",
            )
            val cols = ", reference_date_source, payment_transaction_id, donation_id"
            // a matching conversion is accepted; another amount / another member do not match the donation (composite FK)
            h.rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols) + ", 'BANK_BOOKING', '$pay', '$donation')") shouldBe null
            h.exec("DELETE FROM price_oracle_conversion WHERE donation_id = '$donation'")
            (
                h.rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols, amount = "11.00") + ", 'BANK_BOOKING', '$pay', '$donation')") !=
                    null
            ) shouldBe
                true
            (
                h.rejection(
                    conversion(UUID.randomUUID(), "SNAPSHOT", cols, member = otherMemberId) + ", 'BANK_BOOKING', '$pay', '$donation')",
                ) !=
                    null
            ) shouldBe
                true
            // donation and payment are set together
            (
                h.rejection(
                    conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source, donation_id") + ", 'MANUAL', '$donation')",
                ) !=
                    null
            ) shouldBe
                true
            // MANUAL with a donation needs a justification of 10..500 characters, BANK_BOOKING must not have one
            val withReason = "$cols, reference_date_override_reason"
            (h.rejection(conversion(UUID.randomUUID(), "SNAPSHOT", cols) + ", 'MANUAL', '$pay', '$donation')") != null) shouldBe true
            (
                h.rejection(
                    conversion(UUID.randomUUID(), "SNAPSHOT", withReason) + ", 'MANUAL', '$pay', '$donation', 'zu kurz')",
                ) != null
            ) shouldBe
                true
            (
                h.rejection(
                    conversion(UUID.randomUUID(), "SNAPSHOT", withReason) + ", 'MANUAL', '$pay', '$donation', '${"x".repeat(501)}')",
                ) !=
                    null
            ) shouldBe
                true
            (
                h.rejection(
                    conversion(UUID.randomUUID(), "SNAPSHOT", withReason) + ", 'BANK_BOOKING', '$pay', '$donation', 'nicht erlaubt hier')",
                ) !=
                    null
            ) shouldBe
                true
            // 10 characters with umlauts (counted as characters) and exactly 500 characters are accepted
            h.rejection(conversion(UUID.randomUUID(), "SNAPSHOT", withReason) + ", 'MANUAL', '$pay', '$donation', 'äöüäöüäöüä')") shouldBe
                null
            h.exec("DELETE FROM price_oracle_conversion WHERE donation_id = '$donation'")
            h.rejection(
                conversion(UUID.randomUUID(), "SNAPSHOT", withReason) + ", 'MANUAL', '$pay', '$donation', '${"y".repeat(500)}')",
            ) shouldBe
                null
            // a donation of a payment cannot be deleted while a conversion refers to it, nor its payment
            (h.rejection("DELETE FROM donation WHERE id = '$donation'") != null) shouldBe true
            (h.rejection("DELETE FROM payment_transaction WHERE id = '$pay'") != null) shouldBe true
        }

        test("a donation needs a positive amount and an upper case currency") {
            val pay = UUID.randomUUID()
            h.payment(pay)
            h.line(pay)

            fun insert(
                amount: String,
                currency: String,
            ) = "INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at) VALUES " +
                "('${UUID.randomUUID()}', '$pay', '$memberId', $amount, '$currency', DATE '2026-11-20', TIMESTAMP '2026-11-23 10:00:00')"
            (h.rejection(insert("0.00", "EUR")) != null) shouldBe true
            (h.rejection(insert("-1.00", "EUR")) != null) shouldBe true
            (h.rejection(insert("5.00", "eur")) != null) shouldBe true
        }

        // ---- the migration aborts on an inconsistent V173 row ----
        test(
            "abort 1: a conversion whose payment has no matching donation (two POSTED bank lines) stops the migration at chk_poc_donation_payment",
        ) {
            val harness = newHarness()
            try {
                harness.baseData()
                val pay = UUID.randomUUID()
                harness.payment(pay)
                harness.line(pay)
                harness.line(pay, booking = "2026-11-22")
                harness.exec(
                    conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source, payment_transaction_id") +
                        ", 'BANK_BOOKING', '$pay')",
                )
                val failure = runCatching { harness.flyway(null).migrate() }.exceptionOrNull()
                (failure != null) shouldBe true
                generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }.lowercase() shouldContain
                    "chk_poc_donation_payment"
            } finally {
                harness.close()
            }
        }

        test("abort 2: a conversion whose amount differs from its payment stops the migration at fk_poc_donation_link") {
            val harness = newHarness()
            try {
                harness.baseData()
                val pay = UUID.randomUUID()
                harness.payment(pay)
                harness.line(pay)
                harness.exec(
                    conversion(UUID.randomUUID(), "SNAPSHOT", ", reference_date_source, payment_transaction_id", amount = "11.00") +
                        ", 'BANK_BOOKING', '$pay')",
                )
                val failure = runCatching { harness.flyway(null).migrate() }.exceptionOrNull()
                (failure != null) shouldBe true
                generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }.lowercase() shouldContain
                    "fk_poc_donation_link"
            } finally {
                harness.close()
            }
        }
    })

class DonationEntityMigrationTest : DonationEntityMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DonationEntityMigrationPostgresTest : DonationEntityMigrationScenarios({ PostgresMigrationHarness() })
