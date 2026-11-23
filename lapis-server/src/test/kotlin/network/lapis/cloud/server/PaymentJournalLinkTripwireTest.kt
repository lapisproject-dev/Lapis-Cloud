package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Tripwire: every production file that inserts a `payment_transaction` AND posts its journal entry through one of the posting bridges must
 * also write the back-reference `payment_transaction.journal_entry_id` (via `PaymentJournalLink.link` or by setting the column itself).
 * A path that forgets it leaves the payment "not booked" in the payment list (the bank-statement paths did, until V168).
 */
class PaymentJournalLinkTripwireTest :
    FunSpec({
        test("every payment_transaction writer that posts a journal entry also writes the journal link") {
            val offenders =
                File("src/main/kotlin")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { f ->
                        val text = f.readText()
                        text.contains("PaymentTransactionTable.insert") &&
                            (text.contains("postDonationPayment(") || text.contains("postContributionPayment("))
                    }.filterNot { f ->
                        val text = f.readText()
                        text.contains("PaymentJournalLink.link(") || text.contains("PaymentTransactionTable.journalEntryId")
                    }.map { it.path }
                    .toList()
            offenders.shouldBeEmpty()
        }
    })
