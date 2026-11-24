package network.lapis.cloud.server.auction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.320 -- three structural guarantees of the Fiat (EUR, direct payment) auction path, enforced by scanning the source:
 *
 *  1. **No ledger, no member debit lock** in any Fiat-only method of `AuctionService` and in the whole `server/auction` package. The only Fiat
 *     method that may book LTR is `updateAuctionTerms` (EUR -> LTR charges the listing fee of the NEW LTR auction), and `writeLedger` refuses a
 *     non-LTR auction anyway.
 *  2. **The payment-details table is private to its store**: only `AuctionPaymentDetailsStore`, the retention, the data-subject contributor and the
 *     generated object name it -- never `AuctionService` (so no listing query can ever select it).
 *  3. **`AuctionDto` carries no payment data** (no IBAN, holder name or link field -- only the flag `hasPaymentLink`).
 */
class AuctionFiatLedgerTripwireTest :
    FunSpec({
        val serverFiles = SourceScan.mainFiles()
        val serviceCode = SourceScan.blank(serverFiles.single { it.name == "AuctionService.kt" }.readText())

        /** The body (between the matching braces) of the block-bodied function [name]. */
        fun bodyOf(
            code: String,
            name: String,
        ): String {
            val start = Regex("""\bfun\s+$name\s*\(""").find(code)?.range?.first ?: error("function $name not found")
            var i = code.indexOf('{', start)
            val open = i
            var depth = 0
            while (i < code.length) {
                when (code[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return code.substring(open, i + 1)
                    }
                }
                i++
            }
            error("unbalanced braces in $name")
        }

        val forbidden =
            listOf(
                "writeLedger(",
                "lockForDebit",
                "lockBothAccounts",
                "ltrBalanceProvider",
                "LtrLedgerEntryTable",
                "LtrLedgerEntryType",
                "freeBalance",
            )

        test("the scan sees the service") {
            serverFiles.size shouldBeGreaterThan 500
            serviceCode.contains("class AuctionService") shouldBe true
        }

        test("no Fiat-only method of AuctionService touches the LTR ledger or locks a member account for debit") {
            val fiatMethods =
                listOf(
                    "getAuctionFiatAvailability",
                    "acknowledgeFiatSellerDisclaimer",
                    "createFiatListing",
                    "placeFiatBid",
                    "updateAuctionPaymentDetails",
                    "getAuctionPaymentDetails",
                    "confirmFiat",
                    "getAuctionFiatOperatorDisclaimer",
                    "setAuctionFiatEnabled",
                    "settleFiatAuctionLocked",
                    "buyNowFiat",
                    "upsertFiatBid",
                    "fiatRowToDto",
                    "fiatAvailability",
                    "fiatEnabledNow",
                    "hasSellerAck",
                    "requireSellerAck",
                    "auditFiat",
                    // V1.9.321 price offers: negotiation records only, never money.
                    "makeOffer",
                    "counterOffer",
                    "acceptOffer",
                    "declineOffer",
                    "withdrawOffer",
                    "listOffersForAuction",
                    "listMyOffers",
                    "getAuctionOfferNotice",
                    "withdrawRefusal",
                    "lockOfferChain",
                    "offerDto",
                )
            val offenders =
                fiatMethods.flatMap { method ->
                    val body = bodyOf(serviceCode, method)
                    forbidden.filter { body.contains(it) }.map { "$method uses $it" }
                }
            offenders.shouldBeEmpty()
        }

        test("writeLedger refuses an auction that is not an LTR auction") {
            val body = bodyOf(serviceCode, "writeLedger")
            body.contains("""check(auctionCurrency == AuctionCurrencies.LTR)""") shouldBe true
            body.contains("AuctionTable") shouldBe true
        }

        test("nothing in the server/auction package names the ledger") {
            val offenders =
                serverFiles
                    .filter { it.path.contains("/server/auction/") }
                    .filter { f -> forbidden.any { SourceScan.blank(f.readText()).contains(it) } }
                    .map { it.name }
            offenders.shouldBeEmpty()
        }

        test("only the store, the retention, the data-subject contributor and the generated object name the payment-details table") {
            val users =
                serverFiles
                    .filter { "/db/generated/" !in it.path }
                    .filter { Regex("""\bAuctionPaymentDetailsTable\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            users shouldBe setOf("AuctionPaymentDetailsStore.kt", "AuctionPaymentDetailsRetention.kt", "AuctionPersonalData.kt")
            serviceCode.contains("detailsCiphertext") shouldBe false
        }

        test("AuctionDto declares no field that could carry payment data") {
            val shared =
                listOf("lapis-shared/src/commonMain", "../lapis-shared/src/commonMain")
                    .map { File(it) }
                    .first { it.exists() }
                    .walkTopDown()
                    .single { it.name == "Auction.kt" }
                    .readText()
            val dto =
                SourceScan
                    .blank(
                        shared,
                    ).let { it.substring(it.indexOf("data class AuctionDto("), it.indexOf("data class AuctionBidDto(")) }
            Regex("""\b(iban|accountHolder|paymentLink|detailsCiphertext)\b""").containsMatchIn(dto) shouldBe false
            dto.contains("hasPaymentLink") shouldBe true
        }
    })
