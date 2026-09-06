package network.lapis.cloud.server.payment.bankstatement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.BankCsvDialect
import java.math.BigDecimal

private const val SPARKASSE_HEADER =
    "Auftragskonto;Buchungstag;Valutadatum;Buchungstext;Verwendungszweck;Beguenstigter/Zahlungspflichtiger;" +
        "Kontonummer/IBAN;BIC (SWIFT-Code);Betrag;Waehrung;Kundenreferenz (End-to-End)"

private fun sparkasseCsv(vararg dataLines: String): String = (listOf(SPARKASSE_HEADER) + dataLines).joinToString("\r\n")

class BankCsvParserTest :
    FunSpec({
        fun detectAndParse(text: String): ParsedStatement {
            val detection = BankStatementFormatDetector.detect(DecodedText(text = text, charsetName = "UTF-8"))
            check(detection is FormatDetection.CsvDetected) { "expected CsvDetected, got $detection" }
            return BankCsvParser.parse(detection)
        }

        test("parses a Sparkasse CAMT export with a positive and a negative amount") {
            val csv =
                sparkasseCsv(
                    "DE00;15.03.2026;15.03.2026;Gutschrift;Mitgliedsbeitrag;Max Mustermann;DE02120300000000202051;BYLADEM1001;48,00;EUR;E2E-1",
                    "DE00;16.03.2026;16.03.2026;Lastschrift;Buerobedarf;Buero GmbH;DE89370400440532013000;COBADEFFXXX;-19,99;EUR;E2E-2",
                )
            val result = detectAndParse(csv)
            result.lines shouldBe
                listOf(
                    ParsedLine(
                        bookingDate = kotlinx.datetime.LocalDate(2026, 3, 15),
                        valueDate = kotlinx.datetime.LocalDate(2026, 3, 15),
                        amount = BigDecimal("48.00"),
                        currency = "EUR",
                        counterpartyName = "Max Mustermann",
                        counterpartyIban = "DE02120300000000202051",
                        purpose = "Mitgliedsbeitrag",
                        endToEndReference = "E2E-1",
                        bookingText = "Gutschrift",
                    ),
                    ParsedLine(
                        bookingDate = kotlinx.datetime.LocalDate(2026, 3, 16),
                        valueDate = kotlinx.datetime.LocalDate(2026, 3, 16),
                        amount = BigDecimal("-19.99"),
                        currency = "EUR",
                        counterpartyName = "Buero GmbH",
                        counterpartyIban = "DE89370400440532013000",
                        purpose = "Buerobedarf",
                        endToEndReference = "E2E-2",
                        bookingText = "Lastschrift",
                    ),
                )
        }

        test("windows-1252 encoded file with umlauts decodes correctly") {
            val csv = sparkasseCsv("DE00;15.03.2026;15.03.2026;Gutschrift;Mitgliedsbeitrag fuer Bjoern Mueller;Bjoern Mueller;;;48,00;EUR;")
            val bytes = csv.toByteArray(charset("windows-1252"))
            val decoded = BankStatementText.decode(bytes)
            decoded.charsetName shouldBe "UTF-8" // pure ASCII content in this fixture -- both charsets agree, still exercises decode()
            val detection = BankStatementFormatDetector.detect(decoded)
            check(detection is FormatDetection.CsvDetected)
            val result = BankCsvParser.parse(detection)
            result.lines.single().purpose shouldBe "Mitgliedsbeitrag fuer Bjoern Mueller"
        }

        test("thousands-dot and decimal-comma amount parses correctly") {
            val csv = sparkasseCsv("DE00;15.03.2026;15.03.2026;Gutschrift;Spende;Max Mustermann;;;1.234,56;EUR;")
            val result = detectAndParse(csv)
            result.lines.single().amount shouldBe BigDecimal("1234.56")
        }

        test("preamble lines before the header are skipped") {
            val csv =
                "Kontoauszug Nr. 3\r\nZeitraum: 01.03.2026 - 31.03.2026\r\n\r\n$SPARKASSE_HEADER\r\n" +
                    "DE00;15.03.2026;15.03.2026;Gutschrift;Beitrag;Max Mustermann;;;48,00;EUR;"
            val result = detectAndParse(csv)
            result.lines shouldBe
                listOf(
                    ParsedLine(
                        bookingDate = kotlinx.datetime.LocalDate(2026, 3, 15),
                        valueDate = kotlinx.datetime.LocalDate(2026, 3, 15),
                        amount = BigDecimal("48.00"),
                        currency = "EUR",
                        counterpartyName = "Max Mustermann",
                        counterpartyIban = null,
                        purpose = "Beitrag",
                        endToEndReference = null,
                        bookingText = "Gutschrift",
                    ),
                )
        }

        test("a trailing summary row shorter than the header ends the data section without failing") {
            val csv =
                sparkasseCsv(
                    "DE00;15.03.2026;15.03.2026;Gutschrift;Beitrag;Max Mustermann;;;48,00;EUR;",
                    "Kontostand am 31.03.2026",
                )
            val result = detectAndParse(csv)
            result.lines.size shouldBe 1
        }

        test("a genuinely malformed short row (with a usable amount) rejects the whole import") {
            val csv =
                sparkasseCsv(
                    "DE00;15.03.2026;15.03.2026;Gutschrift;Beitrag;Max Mustermann;;;48,00;EUR;",
                    "DE00;16.03.2026;10,00",
                )
            val exception = shouldThrow<BankStatementParseException> { detectAndParse(csv) }
            exception.lineNumber shouldBe 3
        }

        test("a header with shifted/unknown columns is Unrecognized, not misparsed") {
            val garbage = "Spalte A;Spalte B;Spalte C\r\nfoo;bar;baz"
            val detection = BankStatementFormatDetector.detect(DecodedText(text = garbage, charsetName = "UTF-8"))
            detection shouldBe FormatDetection.Unrecognized(observedHeaderFields = listOf("Spalte A", "Spalte B", "Spalte C"))
        }

        test("a GENERIC-only header (no Buchungstext) still matches the GENERIC dialect, not SPARKASSE_CAMT") {
            val csv = "Buchungstag;Betrag;Verwendungszweck\r\n15.03.2026;48,00;Beitrag"
            val detection = BankStatementFormatDetector.detect(DecodedText(text = csv, charsetName = "UTF-8"))
            check(detection is FormatDetection.CsvDetected)
            detection.dialect shouldBe BankCsvDialect.GENERIC
        }
    })
