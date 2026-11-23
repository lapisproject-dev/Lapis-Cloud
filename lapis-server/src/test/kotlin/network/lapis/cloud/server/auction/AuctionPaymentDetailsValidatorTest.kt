package network.lapis.cloud.server.auction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsField
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsInvalidException

private const val GOOD_IBAN = "DE89370400440532013000"

private fun input(
    holder: String = "Mira Mustermann",
    iban: String? = GOOD_IBAN,
    link: String? = null,
) = AuctionPaymentDetailsInput(accountHolder = holder, iban = iban, paymentLink = link)

private fun fieldOf(block: () -> Unit): AuctionPaymentDetailsField {
    val e = runCatching(block).exceptionOrNull()
    e.shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
    return e.field
}

/** V1.9.320 -- the seller's payment details: what is accepted, what is refused, and that no refusal carries a value. */
class AuctionPaymentDetailsValidatorTest :
    FunSpec({
        test("a German IBAN with blanks is normalised, the holder is trimmed") {
            val details =
                AuctionPaymentDetailsValidator.validate(
                    input(holder = "  Mira Mustermann ", iban = "de89 3704 0044 0532 0130 00"),
                )
            details.accountHolder shouldBe "Mira Mustermann"
            details.iban shouldBe GOOD_IBAN
            details.paymentLink shouldBe null
        }

        test("a link alone is enough; IBAN and link together are accepted") {
            AuctionPaymentDetailsValidator.validate(input(iban = null, link = "https://pay.example.org/abc?x=1")).paymentLink shouldBe
                "https://pay.example.org/abc?x=1"
            AuctionPaymentDetailsValidator.validate(input(iban = "", link = "https://pay.example.org/abc")).iban shouldBe null
            AuctionPaymentDetailsValidator.validate(input(link = "https://pay.example.org/abc")).iban shouldBe GOOD_IBAN
        }

        test("holder: 1 and 71 characters and control characters are refused, 2 and 70 are accepted") {
            fieldOf { AuctionPaymentDetailsValidator.validate(input(holder = "A")) } shouldBe AuctionPaymentDetailsField.ACCOUNT_HOLDER
            fieldOf { AuctionPaymentDetailsValidator.validate(input(holder = "A".repeat(71))) } shouldBe
                AuctionPaymentDetailsField.ACCOUNT_HOLDER
            fieldOf { AuctionPaymentDetailsValidator.validate(input(holder = "Mira\nMuster")) } shouldBe
                AuctionPaymentDetailsField.ACCOUNT_HOLDER
            AuctionPaymentDetailsValidator.validate(input(holder = "AB")).accountHolder shouldBe "AB"
            AuctionPaymentDetailsValidator.validate(input(holder = "A".repeat(70))).accountHolder.length shouldBe 70
        }

        test("IBAN: a wrong check digit, a wrong length, a foreign country and junk are refused") {
            listOf(
                "DE89370400440532013001",
                "DE8937040044053201300",
                "AT611904300234573201",
                "not an iban",
                "DE89 3704 0044 0532 0130 000",
            ).forEach {
                fieldOf { AuctionPaymentDetailsValidator.validate(input(iban = it)) } shouldBe AuctionPaymentDetailsField.IBAN
            }
        }

        test("link: http, javascript, data, user info, no host, an IP address, blanks, non-ASCII and over 500 characters are refused") {
            listOf(
                "http://pay.example.org/x",
                "javascript:alert(1)",
                "data:text/html,hi",
                "https://user:pw@pay.example.org/x",
                "https:///nohost",
                "https://127.0.0.1/x",
                "https://localhost/x",
                "https://pay.example.org/a b",
                "https://bücher.example.org/x",
                "https://pay.example.org/" + "a".repeat(500),
                "ftp://pay.example.org/x",
                "//pay.example.org/x",
            ).forEach {
                fieldOf { AuctionPaymentDetailsValidator.validate(input(link = it)) } shouldBe AuctionPaymentDetailsField.PAYMENT_LINK
            }
        }

        test("neither an IBAN nor a link is refused as MISSING") {
            fieldOf { AuctionPaymentDetailsValidator.validate(input(iban = null, link = null)) } shouldBe AuctionPaymentDetailsField.MISSING
            fieldOf { AuctionPaymentDetailsValidator.validate(input(iban = "  ", link = "  ")) } shouldBe AuctionPaymentDetailsField.MISSING
        }

        test("a refusal never carries the value it refuses, not in the message and not in toString") {
            val secretIban = "DE89370400440532013001"
            val e =
                runCatching { AuctionPaymentDetailsValidator.validate(input(holder = "Geheim Name", iban = secretIban)) }.exceptionOrNull()
            e.shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
            (e.message ?: "").contains(secretIban) shouldBe false
            e.toString().contains(secretIban) shouldBe false
            e.toString().contains("Geheim Name") shouldBe false
        }

        test("the values never appear in toString of the details or of the input") {
            val details = AuctionPaymentDetailsValidator.validate(input(link = "https://pay.example.org/abc"))
            listOf(details.toString(), input(link = "https://pay.example.org/abc").toString()).forEach {
                it.contains(GOOD_IBAN) shouldBe false
                it.contains("Mira") shouldBe false
                it.contains("pay.example.org") shouldBe false
            }
        }

        test("the sealed JSON round-trips the values") {
            val details = AuctionPaymentDetailsValidator.validate(input(link = "https://pay.example.org/abc"))
            val back = SellerPaymentDetails.fromSealedJson(details.toSealedJson())
            back.accountHolder shouldBe details.accountHolder
            back.iban shouldBe details.iban
            back.paymentLink shouldBe details.paymentLink
        }

        test("the retention rule: no sale at once, completed after 30 days, an unfinished award after 180 days, an open auction never") {
            val settled = LocalDateTime(2026, 1, 1, 12, 0)
            val now = LocalDateTime(2026, 6, 1, 12, 0)

            fun due(
                status: AuctionStatus,
                settledAt: LocalDateTime? = settled,
                paymentAt: LocalDateTime? = null,
                deliveryAt: LocalDateTime? = null,
                at: LocalDateTime = now,
            ) = AuctionPaymentDetailsRetention.isDue(
                status = status,
                settledAt = settledAt,
                paymentConfirmedAt = paymentAt,
                deliveryConfirmedAt = deliveryAt,
                now = at,
            )
            due(AuctionStatus.CLOSED_NO_SALE) shouldBe true
            due(AuctionStatus.OPEN, settledAt = null) shouldBe false
            // completed: 30 days after the LATER confirmation
            val paid = LocalDateTime(2026, 5, 1, 12, 0)
            val delivered = LocalDateTime(2026, 5, 10, 12, 0)
            due(AuctionStatus.COMPLETED, paymentAt = paid, deliveryAt = delivered, at = LocalDateTime(2026, 6, 8, 12, 0)) shouldBe false
            due(AuctionStatus.COMPLETED, paymentAt = paid, deliveryAt = delivered, at = LocalDateTime(2026, 6, 10, 12, 0)) shouldBe true
            // 29 days after the later confirmation is still kept, exactly 30 is due
            due(AuctionStatus.COMPLETED, paymentAt = paid, deliveryAt = delivered, at = LocalDateTime(2026, 6, 8, 11, 59)) shouldBe false
            // unfinished award: 180 days after the award
            due(AuctionStatus.AWAITING_PAYMENT, at = LocalDateTime(2026, 6, 20, 12, 0)) shouldBe false
            due(AuctionStatus.PAID, at = LocalDateTime(2026, 7, 1, 12, 0)) shouldBe true
        }
    })
