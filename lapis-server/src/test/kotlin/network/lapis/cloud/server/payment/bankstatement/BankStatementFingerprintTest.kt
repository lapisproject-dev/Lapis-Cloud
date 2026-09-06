package network.lapis.cloud.server.payment.bankstatement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

class BankStatementFingerprintTest :
    FunSpec({
        fun fingerprintOf(occurrenceIndex: Int = 0): String =
            BankStatementFingerprint.of(
                accountIban = "DE02120300000000202051",
                bookingDate = LocalDate(2026, 3, 15),
                valueDate = LocalDate(2026, 3, 15),
                amount = BigDecimal("48.00"),
                currency = "EUR",
                counterpartyName = "Max Mustermann",
                counterpartyIban = "DE89370400440532013000",
                purpose = "Mitgliedsbeitrag",
                endToEndReference = "E2E-1",
                occurrenceIndex = occurrenceIndex,
            )

        test("identical input produces an identical fingerprint (re-import dedup)") {
            fingerprintOf() shouldBe fingerprintOf()
        }

        test("two otherwise-identical lines get different fingerprints via occurrenceIndex") {
            fingerprintOf(occurrenceIndex = 0) shouldNotBe fingerprintOf(occurrenceIndex = 1)
        }

        test("a single changed character in the purpose changes the fingerprint") {
            val base =
                BankStatementFingerprint.of(
                    accountIban = "DE02120300000000202051",
                    bookingDate = LocalDate(2026, 3, 15),
                    valueDate = null,
                    amount = BigDecimal("48.00"),
                    currency = "EUR",
                    counterpartyName = "Max Mustermann",
                    counterpartyIban = null,
                    purpose = "Mitgliedsbeitrag",
                    endToEndReference = null,
                    occurrenceIndex = 0,
                )
            val changed =
                BankStatementFingerprint.of(
                    accountIban = "DE02120300000000202051",
                    bookingDate = LocalDate(2026, 3, 15),
                    valueDate = null,
                    amount = BigDecimal("48.00"),
                    currency = "EUR",
                    counterpartyName = "Max Mustermann",
                    counterpartyIban = null,
                    purpose = "Mitgliedsbeitrags", // one extra character
                    endToEndReference = null,
                    occurrenceIndex = 0,
                )
            base shouldNotBe changed
        }

        test("case/whitespace-only differences in text fields still fingerprint identically") {
            val a =
                BankStatementFingerprint.of(
                    accountIban = "DE02120300000000202051",
                    bookingDate = LocalDate(2026, 3, 15),
                    valueDate = null,
                    amount = BigDecimal("48.00"),
                    currency = "eur",
                    counterpartyName = "  max   mustermann ",
                    counterpartyIban = null,
                    purpose = "Mitgliedsbeitrag",
                    endToEndReference = null,
                    occurrenceIndex = 0,
                )
            val b =
                BankStatementFingerprint.of(
                    accountIban = "DE02120300000000202051",
                    bookingDate = LocalDate(2026, 3, 15),
                    valueDate = null,
                    amount = BigDecimal("48.00"),
                    currency = "EUR",
                    counterpartyName = "MAX MUSTERMANN",
                    counterpartyIban = null,
                    purpose = "Mitgliedsbeitrag",
                    endToEndReference = null,
                    occurrenceIndex = 0,
                )
            a shouldBe b
        }
    })
