package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * `V168__bank_payment_journal_link.sql` on the upgrade path V167 -> V168, on H2 AND PostgreSQL: the journal back-reference of bank-statement
 * payments is filled only where exactly one journal entry matches; journal_entry and posting stay byte-identical; ambiguous and foreign cases
 * are skipped; a re-run of the SQL changes nothing. All data is synthetic.
 */
abstract class BankPaymentJournalLinkMigrationScenarios(
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

        fun linkOf(pt: UUID): String = rows("SELECT journal_entry_id FROM payment_transaction WHERE id = '$pt'").single().single()

        val memberId = UUID.randomUUID()
        val tierId = UUID.randomUUID()
        val bankLedger = UUID.randomUUID()
        val incomeLedger = UUID.randomUUID()
        val importId = UUID.randomUUID()
        var ordinal = 0

        var year = 2000

        fun contribution(): UUID {
            val id = UUID.randomUUID()
            year++
            exec(
                "INSERT INTO contribution (id, period_start, period_end, amount_due, status, created_at, member_id, membership_tier_id, " +
                    "due_date, payment_method) VALUES ('$id', DATE '$year-01-01', DATE '$year-12-31', 30.00, 'PAID', " +
                    "TIMESTAMP '2026-01-01 00:00:00', '$memberId', '$tierId', DATE '2026-01-15', 'MANUAL')",
            )
            return id
        }

        fun line(
            id: UUID,
            ptId: UUID?,
            status: String = "POSTED",
            fingerprint: String = "fp-$id",
        ) {
            ordinal++
            exec(
                "INSERT INTO bank_statement_line (id, import_id, fingerprint, line_ordinal, booking_date, amount, currency, status, " +
                    "payment_transaction_id) VALUES ('$id', '$importId', '$fingerprint', $ordinal, DATE '2026-09-16', 30.00, 'EUR', " +
                    "'$status', ${ptId?.let { "'$it'" } ?: "NULL"})",
            )
        }

        fun payment(
            id: UUID,
            intent: String,
            eventId: String,
            contributionId: UUID? = null,
            provider: String = "MANUAL",
            note: String? = null,
            journalEntryId: UUID? = null,
            amount: String = "30.00",
        ) {
            exec(
                "INSERT INTO payment_transaction (id, provider, provider_event_id, provider_payment_id, status, amount, currency, " +
                    "intent, " +
                    "contribution_id, received_at, reconciliation_note, raw_payload_digest, journal_entry_id) " +
                    "VALUES ('$id', '$provider', " +
                    "'$eventId', '$eventId', 'CAPTURED', $amount, 'EUR', '$intent', ${contributionId?.let { "'$it'" } ?: "NULL"}, " +
                    "TIMESTAMP '2026-11-23 10:00:00', ${note?.let { "'$it'" } ?: "NULL"}, '$eventId', " +
                    "${journalEntryId?.let { "'$it'" } ?: "NULL"})",
            )
        }

        fun journal(
            description: String,
            voucher: String?,
            status: String = "POSTED",
            debit: String = "30.00",
        ): UUID {
            val id = UUID.randomUUID()
            exec(
                "INSERT INTO journal_entry (id, entry_date, description, voucher_reference, created_by, status, posted_at, created_at) " +
                    "VALUES ('$id', DATE '2026-11-23', '$description', ${voucher?.let { "'$it'" } ?: "NULL"}, '$memberId', '$status', " +
                    "TIMESTAMP '2026-11-23 10:00:00', TIMESTAMP '2026-11-23 10:00:00')",
            )
            exec(
                "INSERT INTO posting (id, side, amount, sphere, vat_rate, vat_amount, journal_entry_id, ledger_account_id) VALUES " +
                    "('${UUID.randomUUID()}', 'DEBIT', $debit, 'IDEELLER_BEREICH', 'NOT_SUBJECT', 0.00, '$id', '$bankLedger'), " +
                    "('${UUID.randomUUID()}', 'CREDIT', $debit, 'IDEELLER_BEREICH', 'NOT_SUBJECT', 0.00, '$id', '$incomeLedger')",
            )
            return id
        }

        val donationPt = UUID.randomUUID()
        val donationJe = arrayOfNulls<UUID>(1)
        val manualNotePt = UUID.randomUUID()
        val manualNoteJe = arrayOfNulls<UUID>(1)
        val manualNoNotePt = UUID.randomUUID()
        val manualNoNoteJe = arrayOfNulls<UUID>(1)
        val autoPt = UUID.randomUUID()
        val autoJe = arrayOfNulls<UUID>(1)
        val ambiguousPt = UUID.randomUUID()
        val wrongAmountPt = UUID.randomUUID()
        val unpostedLinePt = UUID.randomUUID()
        val alreadyLinkedPt = UUID.randomUUID()
        val alreadyLinkedJe = arrayOfNulls<UUID>(1)
        val pspPt = UUID.randomUUID()
        val takenPt = UUID.randomUUID()
        val takenJe = arrayOfNulls<UUID>(1)
        val otherOwnerPt = UUID.randomUUID()
        val journalBefore = arrayOfNulls<List<List<String>>>(1)

        beforeSpec {
            h = newHarness()
            h.flyway("167").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'jl-$memberId@example.org', " +
                    "'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO membership_tier (id, name_key, name, description, contribution_amount, billing_interval) VALUES " +
                    "('$tierId', '$tierId', 'T', 'T', 30.00, 'YEARLY')",
            )
            for ((id, number, type) in listOf(Triple(bankLedger, "JL1", "ASSET"), Triple(incomeLedger, "JL2", "INCOME"))) {
                exec(
                    "INSERT INTO ledger_account (id, account_number, name, account_class, type, active, is_cash_register) VALUES " +
                        "('$id', '$number', 'K', 0, '$type', TRUE, FALSE)",
                )
            }
            exec(
                "INSERT INTO bank_statement_import (id, format, dialect, file_name, file_size_bytes, file_digest, line_count, " +
                    "duplicate_count, auto_posted_count, uploaded_by, uploaded_at) VALUES ('$importId', 'CSV', 'SPARKASSE', 'f.csv', 1, " +
                    "'d', 0, 0, 0, '$memberId', TIMESTAMP '2026-11-23 09:00:00')",
            )

            // unique donation (the reported case, nachgebildet)
            val l1 = UUID.randomUUID()
            payment(donationPt, "DONATION", "manual-donation:$l1")
            line(l1, donationPt)
            donationJe[0] = journal("Spende $donationPt (GATEWAY)", "SP-MANUAL-$l1")

            // unique manual contribution with a note
            val c1 = contribution()
            val l2 = UUID.randomUUID()
            payment(manualNotePt, "CONTRIBUTION", "manual-assign:$l2", c1, note = "Zuordnung Test")
            line(l2, manualNotePt)
            manualNoteJe[0] = journal("Mitgliedsbeitrag $c1 (manual)", "Zuordnung Test")

            // unique manual contribution without a note
            val c2 = contribution()
            val l3 = UUID.randomUUID()
            payment(manualNoNotePt, "CONTRIBUTION", "manual-assign:$l3", c2)
            line(l3, manualNoNotePt)
            manualNoNoteJe[0] = journal("Mitgliedsbeitrag $c2 (manual)", "KA-MANUAL-$l3")

            // auto-post from an import
            val c3 = contribution()
            val l4 = UUID.randomUUID()
            payment(autoPt, "CONTRIBUTION", "fp-$l4", c3)
            line(l4, autoPt)
            val ord4 = rows("SELECT line_ordinal FROM bank_statement_line WHERE id = '$l4'").single().single()
            autoJe[0] = journal("Mitgliedsbeitrag $c3 (manual)", "KA-${importId.toString().take(8)}-$ord4")

            // ambiguous: two identical journal entries
            val c4 = contribution()
            val l5 = UUID.randomUUID()
            payment(ambiguousPt, "CONTRIBUTION", "manual-assign:$l5", c4, note = "Doppelt")
            line(l5, ambiguousPt)
            journal("Mitgliedsbeitrag $c4 (manual)", "Doppelt")
            journal("Mitgliedsbeitrag $c4 (manual)", "Doppelt")

            // wrong amount in the journal
            val l6 = UUID.randomUUID()
            payment(wrongAmountPt, "DONATION", "manual-donation:$l6")
            line(l6, wrongAmountPt)
            journal("Spende $wrongAmountPt (GATEWAY)", "SP-MANUAL-$l6", debit = "31.00")

            // line not POSTED
            val l7 = UUID.randomUUID()
            payment(unpostedLinePt, "DONATION", "manual-donation:$l7")
            line(l7, unpostedLinePt, status = "UNMATCHED")
            journal("Spende $unpostedLinePt (GATEWAY)", "SP-MANUAL-$l7")

            // payment already linked stays unchanged
            alreadyLinkedJe[0] = journal("Anderes", "X-1")
            val l8 = UUID.randomUUID()
            payment(alreadyLinkedPt, "DONATION", "manual-donation:$l8", journalEntryId = alreadyLinkedJe[0])
            line(l8, alreadyLinkedPt)
            journal("Spende $alreadyLinkedPt (GATEWAY)", "SP-MANUAL-$l8")

            // PSP payment stays untouched
            val l9 = UUID.randomUUID()
            payment(pspPt, "DONATION", "manual-donation:$l9", provider = "STRIPE")
            line(l9, pspPt)
            journal("Spende $pspPt (GATEWAY)", "SP-MANUAL-$l9")

            // journal entry already owned by another payment
            val l10 = UUID.randomUUID()
            payment(takenPt, "DONATION", "manual-donation:$l10")
            line(l10, takenPt)
            takenJe[0] = journal("Spende $takenPt (GATEWAY)", "SP-MANUAL-$l10")
            payment(otherOwnerPt, "DONATION", "other-owner", journalEntryId = takenJe[0])

            journalBefore[0] =
                rows("SELECT id, description, voucher_reference, status FROM journal_entry ORDER BY id") +
                rows("SELECT id, side, amount, journal_entry_id FROM posting ORDER BY id")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("a unique donation is linked to its journal entry") { linkOf(donationPt) shouldBe donationJe[0].toString() }
        test("a unique manual contribution with a note is linked") { linkOf(manualNotePt) shouldBe manualNoteJe[0].toString() }
        test("a unique manual contribution without a note is linked") { linkOf(manualNoNotePt) shouldBe manualNoNoteJe[0].toString() }
        test("an auto-posted import line is linked") { linkOf(autoPt) shouldBe autoJe[0].toString() }
        test("ambiguous, wrong-amount, unposted-line and PSP payments are skipped") {
            for (pt in listOf(ambiguousPt, wrongAmountPt, unpostedLinePt, pspPt)) linkOf(pt) shouldBe "null"
        }
        test("an already linked payment is unchanged") { linkOf(alreadyLinkedPt) shouldBe alreadyLinkedJe[0].toString() }
        test("a journal entry owned by another payment is not stolen") {
            linkOf(takenPt) shouldBe "null"
            linkOf(otherOwnerPt) shouldBe takenJe[0].toString()
        }
        test("journal_entry and posting are byte-identical, the helper table is gone") {
            (
                rows("SELECT id, description, voucher_reference, status FROM journal_entry ORDER BY id") +
                    rows("SELECT id, side, amount, journal_entry_id FROM posting ORDER BY id")
            ) shouldBe journalBefore[0]
            rows("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'mig_v168_link_candidate'")
                .single()
                .single() shouldBe "0"
        }
        test("a re-run is a no-op") { h.flyway(null).migrate().migrationsExecuted shouldBe 0 }
    })

class BankPaymentJournalLinkMigrationTest : BankPaymentJournalLinkMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class BankPaymentJournalLinkMigrationPostgresTest : BankPaymentJournalLinkMigrationScenarios({ PostgresMigrationHarness() })
