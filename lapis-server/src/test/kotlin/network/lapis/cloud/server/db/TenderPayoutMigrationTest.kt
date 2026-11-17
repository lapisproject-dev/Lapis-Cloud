package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * V1.9.308 -- `V157__tender_payout.sql` on the upgrade path V156 -> V157, on H2 AND on PostgreSQL: an existing COMPLETED tender and a ledger row
 * of every older shape survive; the status column takes the two new 13-character statuses; the new CHECKs refuse a PAID tender without a ledger
 * entry, a failed payout without its three columns and an unknown reason; the ledger takes TENDER_PAYOUT / TENDER and still refuses the unknown;
 * `tender_payout` is one row per tender, per ledger row and per bid. V156 stays untouched; a re-run is a no-op.
 */
abstract class TenderPayoutMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun scalar(sql: String): String? =
            h.connection { c ->
                c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            }

        val member = UUID.randomUUID().toString()
        val winner = UUID.randomUUID().toString()
        val committee = UUID.randomUUID().toString()
        val motion = UUID.randomUUID().toString()
        val tender = UUID.randomUUID().toString()
        val bid = UUID.randomUUID().toString()
        val ts = "TIMESTAMP '2026-11-02 10:00:00'"

        fun ledger(
            id: String,
            type: String,
            reference: String? = "TENDER",
            amount: String = "5.00",
        ) = "INSERT INTO ltr_ledger_entry (id, entry_type, amount_ltr, reference_type, reference_id, note, created_by, created_at, " +
            "member_id) " +
            "VALUES ('$id', '$type', $amount, ${reference?.let { "'$it'" } ?: "NULL"}, '$tender', NULL, '$member', $ts, '$winner')"

        fun payout(
            tenderId: String,
            ledgerId: String,
            bidId: String,
            amount: String = "5.00",
        ) = "INSERT INTO tender_payout (tender_id, ledger_entry_id, bid_id, amount_ltr, executed_by, executed_at) " +
            "VALUES ('$tenderId', '$ledgerId', '$bidId', $amount, '$member', $ts)"

        beforeSpec {
            h = newHarness()
            h.flyway("156").migrate()
            for (m in listOf(member, winner)) {
                exec(
                    "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                        "('$m', 'Payout', 'payout-mig-$m@example.org', 'ACTIVE', DATE '2026-01-01')",
                )
            }
            exec("INSERT INTO committee (id, name, type, description, created_at) VALUES ('$committee', 'G', 'WORKING_GROUP', 'x', $ts)")
            exec(
                "INSERT INTO motion (id, target_committee_id, title, rationale, text, submitter_member_id, status, submitted_at) " +
                    "VALUES ('$motion', '$committee', 't', 'r', 'x', '$member', 'RESOLVED', $ts)",
            )
            exec(
                "INSERT INTO tender (id, title, description, expected_result, binding_motion_id, award_criterion, currency, " +
                    "bid_visibility, " +
                    "status, deadline, created_by, created_at) VALUES ('$tender', 'Alt', 'd', 'e', '$motion', 'LOWEST_PRICE', 'LTR', " +
                    "'OPEN', 'COMPLETED', $ts, '$member', $ts)",
            )
            exec(
                "INSERT INTO tender_bid (id, tender_id, bidder_member_id, price, currency, text, status, submitted_at, price_set_at) " +
                    "VALUES ('$bid', '$tender', '$winner', 5.00, 'LTR', 'x', 'AWARDED', $ts, $ts)",
            )
            // a ledger row of an old shape, written BEFORE the migration
            exec(ledger(UUID.randomUUID().toString(), "MINT", reference = null))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the existing tender and the older ledger row survive; the new columns are empty") {
            scalar("SELECT status FROM tender WHERE id = '$tender'") shouldBe "COMPLETED"
            scalar("SELECT payout_failure_reason FROM tender WHERE id = '$tender'") shouldBe null
            scalar("SELECT payout_failed_by FROM tender WHERE id = '$tender'") shouldBe null
            scalar("SELECT COUNT(*) FROM ltr_ledger_entry WHERE entry_type = 'MINT'") shouldBe "1"
            scalar("SELECT COUNT(*) FROM tender_payout") shouldBe "0"
        }

        test("the status column takes PAYOUT_FAILED only with its three columns, and refuses an unknown reason or status") {
            (
                rejection(
                    "UPDATE tender SET status = 'PAYOUT_FAILED' WHERE id = '$tender'",
                )!!
            ).shouldContain("chk_tender_payout_failed_consistent")
            (
                rejection(
                    "UPDATE tender SET status = 'PAYOUT_FAILED', payout_failure_reason = 'WINNER_DEAD', payout_failed_at = $ts, " +
                        "payout_failed_by = '$member' WHERE id = '$tender'",
                )!!
            ).shouldContain("chk_tender_payout_failure_reason")
            (rejection("UPDATE tender SET status = 'LIMBO' WHERE id = '$tender'")!!).shouldContain("chk_tender_status")
            rejection(
                "UPDATE tender SET status = 'PAYOUT_FAILED', payout_failure_reason = 'WINNER_NOT_LTR_ELIGIBLE', payout_failed_at = $ts, " +
                    "payout_failed_by = '$member' WHERE id = '$tender'",
            ) shouldBe null
            scalar("SELECT status FROM tender WHERE id = '$tender'") shouldBe "PAYOUT_FAILED"
            // the failed_by is a real member
            (
                rejection("UPDATE tender SET payout_failed_by = '${UUID.randomUUID()}' WHERE id = '$tender'")!!
            ).shouldContain("fk_tender_payout_failed_by")
        }

        test("PAID needs a ledger entry; the ledger takes TENDER_PAYOUT/TENDER and refuses unknown literals") {
            (rejection("UPDATE tender SET status = 'PAID' WHERE id = '$tender'")!!).shouldContain("chk_tender_paid_has_entry")
            val entry = UUID.randomUUID().toString()
            (rejection(ledger(UUID.randomUUID().toString(), "TENDER_BONUS"))!!).shouldContain("chk_ltr_ledger_entry_entry_type")
            (rejection(ledger(UUID.randomUUID().toString(), "TENDER_PAYOUT", reference = "CONTRACT"))!!)
                .shouldContain("chk_ltr_ledger_entry_reference_type")
            rejection(ledger(entry, "TENDER_PAYOUT")) shouldBe null
            // every older entry type and reference type is still valid
            rejection(ledger(UUID.randomUUID().toString(), "NOTICE_BOOST", reference = "NOTICE", amount = "-1.00")) shouldBe null
            rejection(ledger(UUID.randomUUID().toString(), "PEER_TRANSFER_IN", reference = "PEER_TRANSFER")) shouldBe null
            rejection("UPDATE tender SET status = 'PAID', payout_ledger_entry_id = '$entry' WHERE id = '$tender'") shouldBe null
            scalar("SELECT status FROM tender WHERE id = '$tender'") shouldBe "PAID"
        }

        test("tender_payout: one row per tender, per ledger row and per bid; an amount below 0.01 or a missing parent is refused") {
            val entry = scalar("SELECT id FROM ltr_ledger_entry WHERE entry_type = 'TENDER_PAYOUT'")!!
            (rejection(payout(tender, entry, bid, amount = "0.00"))!!).shouldContain("chk_tender_payout_amount")
            (rejection(payout(UUID.randomUUID().toString(), entry, bid))!!).shouldContain("tender_payout")
            rejection(payout(tender, entry, bid)) shouldBe null
            // the same tender again, the same ledger row again, the same bid again
            (rejection(payout(tender, entry, bid))!!).isNotEmpty() shouldBe true
            val second = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            exec(
                "INSERT INTO tender (id, title, description, expected_result, binding_motion_id, award_criterion, currency, " +
                    "bid_visibility, " +
                    "status, deadline, created_by, created_at) VALUES ('$second', 'Zwei', 'd', 'e', '$motion', 'LOWEST_PRICE', 'LTR', " +
                    "'OPEN', 'COMPLETED', $ts, '$member', $ts)",
            )
            exec(
                "INSERT INTO tender_bid (id, tender_id, bidder_member_id, price, currency, text, status, submitted_at, price_set_at) " +
                    "VALUES ('$other', '$second', '$winner', 5.00, 'LTR', 'x', 'AWARDED', $ts, $ts)",
            )
            (rejection(payout(second, entry, other))!!).isNotEmpty() shouldBe true // unique ledger_entry_id
            val entry2 = UUID.randomUUID().toString()
            exec(ledger(entry2, "TENDER_PAYOUT"))
            (rejection(payout(second, entry2, bid))!!).isNotEmpty() shouldBe true // unique bid_id
            rejection(payout(second, entry2, other)) shouldBe null
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class TenderPayoutMigrationTest : TenderPayoutMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TenderPayoutMigrationPostgresTest : TenderPayoutMigrationScenarios({ PostgresMigrationHarness() })
