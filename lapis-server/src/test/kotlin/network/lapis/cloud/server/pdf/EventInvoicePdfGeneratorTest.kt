package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.math.BigDecimal

private val ORGANIZATION =
    OrganizationSettingsDto(
        id = "00000000-0000-0000-0000-0000000000f2",
        name = "Verein Testverein e.V.",
        street = "Vereinsstrasse 1",
        postalCode = "38100",
        city = "Braunschweig",
        country = "Deutschland",
        bankIban = "DE02120300000000202051",
        bankBic = "BYLADEM1001",
        taxExemptionAuthority = null,
        taxExemptionDate = null,
    )

/**
 * Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen" -- [EventInvoicePdfGenerator],
 * mirrors [BeitragsrechnungPdfGeneratorTest]'s house style. Covers both the MEMBER path (a full
 * billing address on file) and the GUEST path (no billing address at all, since it is optional --
 * see the generator's own KDoc) to prove the "DTO-agnostic" claim actually holds.
 */
class EventInvoicePdfGeneratorTest :
    FunSpec({
        test("generates a one-page PDF with letterhead, recipient address, amount in figures and words, due date and reference") {
            val bytes =
                EventInvoicePdfGenerator.generate(
                    recipientName = "Max Mitglied",
                    billingStreet = "Musterstrasse 5",
                    billingPostalCode = "38102",
                    billingCity = "Braunschweig",
                    billingCountry = "Deutschland",
                    eventTitle = "Sommerfest 2026",
                    amount = BigDecimal("42.50"),
                    dueDate = LocalDate(2026, 9, 30),
                    reference = "EVENT-11111111-1111-1111-1111-111111111111",
                    organization = ORGANIZATION,
                )
            val document = Loader.loadPDF(bytes)
            val text =
                try {
                    document.numberOfPages shouldBe 1
                    PDFTextStripper().getText(document)
                } finally {
                    document.close()
                }

            text shouldContain ORGANIZATION.name
            text shouldContain ORGANIZATION.street!!
            text shouldContain "Max Mitglied"
            text shouldContain "Musterstrasse 5"
            text shouldContain "Braunschweig"
            text shouldContain "Rechnung"
            text shouldContain "Sommerfest 2026"
            text shouldContain "42,50"
            // Individual words only -- see BeitragsrechnungPdfGeneratorTest's own comment for why
            // a hand-rolled word-wrap makes multi-word phrase assertions unreliable.
            text shouldContain "zweiundvierzig"
            text shouldContain "fünfzig"
            text shouldContain "Cent"
            text shouldContain "30.09.2026"
            text shouldContain ORGANIZATION.bankIban!!
            text shouldContain ORGANIZATION.bankBic!!
            text shouldContain "EVENT-11111111-1111-1111-1111-111111111111"
            text shouldContain "Der Vorstand"
        }

        test("a GUEST invoice with NO billing address on file still renders (address block optional)") {
            val bytes =
                EventInvoicePdfGenerator.generate(
                    recipientName = "Gast Testperson",
                    billingStreet = null,
                    billingPostalCode = null,
                    billingCity = null,
                    billingCountry = null,
                    eventTitle = "Infostand",
                    amount = BigDecimal("5.00"),
                    dueDate = LocalDate(2026, 10, 1),
                    reference = "EVENT-22222222-2222-2222-2222-222222222222",
                    organization = ORGANIZATION,
                )
            val document = Loader.loadPDF(bytes)
            val text =
                try {
                    document.numberOfPages shouldBe 1
                    PDFTextStripper().getText(document)
                } finally {
                    document.close()
                }
            text shouldContain "Gast Testperson"
            text shouldContain "Infostand"
            text shouldContain "5,00"
        }
    })
