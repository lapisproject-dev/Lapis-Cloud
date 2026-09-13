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

        // Review fix (CRITICAL, finding #1) regression coverage -- see class KDoc
        // "bankAccountDiscriminator" for the full rationale.
        test("a null/blank bankAccountDiscriminator produces the EXACT pre-fix fingerprint (backward compat)") {
            val withoutParam = fingerprintOf()
            val withNullDiscriminator =
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
                    occurrenceIndex = 0,
                    bankAccountDiscriminator = null,
                )
            val withBlankDiscriminator =
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
                    occurrenceIndex = 0,
                    bankAccountDiscriminator = "",
                )
            withNullDiscriminator shouldBe withoutParam
            withBlankDiscriminator shouldBe withoutParam
        }

        test("two accounts with otherwise-identical lines get different fingerprints once a discriminator is supplied") {
            fun fingerprintFor(discriminator: String?) =
                BankStatementFingerprint.of(
                    accountIban = null, // CSV: no statement-level IBAN, the exact bug scenario
                    bookingDate = LocalDate(2026, 9, 1),
                    valueDate = null,
                    amount = BigDecimal("-4.90"),
                    currency = "EUR",
                    counterpartyName = null,
                    counterpartyIban = null,
                    purpose = "Kontofuehrungsgebuehr",
                    endToEndReference = null,
                    occurrenceIndex = 0,
                    bankAccountDiscriminator = discriminator,
                )
            val accountA = fingerprintFor("11111111-1111-1111-1111-111111111111")
            val accountB = fingerprintFor("22222222-2222-2222-2222-222222222222")
            accountA shouldNotBe accountB
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
