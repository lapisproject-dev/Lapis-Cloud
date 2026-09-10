package network.lapis.cloud.server.payment.bankstatement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import java.math.BigDecimal

private fun mt940(vararg lines: String): String = lines.joinToString("\r\n")

class Mt940ParserTest :
    FunSpec({
        test("parses a balanced two-line statement with :86: subfields") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":28C:1/1",
                    ":60F:C260301EUR1000,00",
                    ":61:2603150315C48,00NMSCNONREF",
                    ":86:?20Mitgliedsbeitrag?32Max Mustermann",
                    ":61:2603160316D19,99NMSCNONREF",
                    ":86:?20Buerobedarf?32Buero GmbH",
                    ":62F:C260331EUR1028,01",
                )
            val result = Mt940Parser.parse(text)
            result.lines shouldHaveSize 2

            val creditLine = result.lines[0]
            creditLine.bookingDate shouldBe LocalDate(2026, 3, 15)
            creditLine.amount shouldBe BigDecimal("48.00")
            creditLine.purpose shouldBe "Mitgliedsbeitrag"
            creditLine.counterpartyName shouldBe "Max Mustermann"

            val debitLine = result.lines[1]
            debitLine.bookingDate shouldBe LocalDate(2026, 3, 16)
            debitLine.amount shouldBe BigDecimal("-19.99")
            debitLine.purpose shouldBe "Buerobedarf"

            result.accountIban shouldBe "DE02120300000000202051"
        }

        test("multiple :20: blocks in one file are each independently balance-checked") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR0,00",
                    ":61:2603010301C10,00NMSCREF1",
                    ":86:?20Erste Buchung",
                    ":62F:C260315EUR10,00",
                    ":20:STMT002",
                    ":25:DE02120300000000202051",
                    ":60F:C260315EUR10,00",
                    ":61:2603200320C5,00NMSCREF2",
                    ":86:?20Zweite Buchung",
                    ":62F:C260331EUR15,00",
                )
            val result = Mt940Parser.parse(text)
            result.lines shouldHaveSize 2
            result.lines.map { it.amount } shouldBe listOf(BigDecimal("10.00"), BigDecimal("5.00"))
        }

        test("a broken balance check (opening + sum != closing) rejects the whole import with code MT940_BALANCE_MISMATCH") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR1000,00",
                    ":61:2603150315C48,00NMSCNONREF",
                    ":86:?20Mitgliedsbeitrag",
                    ":62F:C260331EUR9999,99", // deliberately wrong closing balance
                )
            val exception = shouldThrow<BankStatementParseException> { Mt940Parser.parse(text) }
            exception.code shouldBe BankStatementRejectionCode.MT940_BALANCE_MISMATCH
        }

        test("RD (reversal of debit) inverts the sign to positive") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR0,00",
                    ":61:2603150315RD10,00NMSCREF1",
                    ":86:?20Rueckbuchung",
                    ":62F:C260331EUR10,00",
                )
            val result = Mt940Parser.parse(text)
            result.lines.single().amount shouldBe BigDecimal("10.00")
        }

        // Welle V1.4.5.1.1 -- every OTHER BankStatementParseException throw site in this parser
        // stays on the default PARSE_FAILED code (plan §3.3: not flattened across all of them). A
        // missing :20: tag is one such ordinary parse failure, distinct from the balance-check case.
        test("a file with no :20: tag at all is rejected with the default code PARSE_FAILED") {
            val exception = shouldThrow<BankStatementParseException> { Mt940Parser.parse("some garbage that is not MT940 at all") }
            exception.code shouldBe BankStatementRejectionCode.PARSE_FAILED
        }

        test(":61: field order -- Valuta (Wertstellung) and Buchungsdatum are not swapped") {
            // Review fix (MEDIUM): :61: is <Valuta YYMMDD><Buchungsdatum MMDD> -- these two fields
            // used to be assigned cross-wise (bookingDate from the Valuta bytes, valueDate from the
            // Buchungsdatum bytes). Valuta 15.03., Buchungsdatum 18.03. here -- deliberately
            // DIFFERENT days so a swap would be caught (the existing fixtures elsewhere in this file
            // all use the same day for both, which is exactly why this bug shipped undetected).
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR1000,00",
                    ":61:2603150318C48,00NMSCNONREF",
                    ":86:?20Mitgliedsbeitrag",
                    ":62F:C260331EUR1048,00",
                )
            val result = Mt940Parser.parse(text)
            val line = result.lines.single()
            line.valueDate shouldBe LocalDate(2026, 3, 15) // Valuta -- the FIRST six digits
            line.bookingDate shouldBe LocalDate(2026, 3, 18) // Buchungsdatum -- the following MMDD
        }

        test("a non-EUR account's line currency comes from the block's own :60F:/:60M:, not hard-coded EUR") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:CH1234567890123456789",
                    ":60F:C260301CHF1000,00",
                    ":61:2603150315C48,00NMSCNONREF",
                    ":86:?20Mitgliedsbeitrag",
                    ":62F:C260331CHF1048,00",
                )
            val result = Mt940Parser.parse(text)
            result.lines.single().currency shouldBe "CHF"
        }

        test("opening/closing balance of the FIRST :20: block flow through to the returned ParsedStatement") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR1000,00",
                    ":61:2603150315C48,00NMSCNONREF",
                    ":86:?20Mitgliedsbeitrag",
                    ":62F:C260331EUR1048,00",
                )
            val result = Mt940Parser.parse(text)
            result.openingBalance shouldBe BigDecimal("1000.00")
            result.closingBalance shouldBe BigDecimal("1048.00")
        }

        test("a block missing its :62F:/:62M: closing balance is rejected") {
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR0,00",
                    ":61:2603150315C10,00NMSCREF1",
                    ":86:?20Beitrag",
                )
            shouldThrow<BankStatementParseException> { Mt940Parser.parse(text) }
        }

        test("an overlong :61: owner reference without a // bank reference is capped at 140 chars") {
            // Regression test for a review finding (Runde-3-Fund #1): endToEndReference used to be
            // taken from `rest` UNCAPPED. `rest` is not bounded to one physical line -- tokenizeTags
            // folds every continuation line up to the next `:NN:` tag into the SAME `:61:` value -- so
            // a `:61:` without a `//` bank reference can carry an arbitrarily long owner reference,
            // which then overflowed bank_statement_line.end_to_end_reference (VARCHAR(140)) /
            // payment_transaction.provider_payment_id (VARCHAR(255)) with an uncaught SQLSTATE 22001
            // deep in the Phase 2 auto-post loop. `.take(MAX_END_TO_END_REFERENCE_LENGTH)` (140, same
            // constant BankCsvParser's own endToEndReference cap uses) must survive future edits here.
            val longRef = "R".repeat(200)
            val text =
                mt940(
                    ":20:STMT001",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR1000,00",
                    ":61:2603150315C48,00NMSC$longRef",
                    ":62F:C260331EUR1048,00",
                )
            val result = Mt940Parser.parse(text)
            val line = result.lines.single()
            line.endToEndReference shouldBe "R".repeat(140)
        }
    })
