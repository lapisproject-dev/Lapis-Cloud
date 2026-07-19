package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
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
        taxExemptionAuthority = "Finanzamt Braunschweig-Wilhelmstrasse",
        taxExemptionDate = LocalDate(2025, 1, 15),
    )

private val MEMBER =
    MemberDto(
        id = "00000000-0000-0000-0000-000000000004",
        displayName = "Max Mitglied",
        email = "max.mitglied@example.org",
        status = MemberStatus.AKTIV,
        joinedAt = LocalDate(2024, 1, 1),
        role = AccountRole.MEMBER,
        street = "Musterstrasse 5",
        postalCode = "38102",
        city = "Braunschweig",
        country = "Deutschland",
    )

class BeitragsrechnungPdfGeneratorTest :
    FunSpec({
        test("generates a one-page PDF with letterhead, recipient, amount in figures and words, and IBAN/BIC") {
            val contribution =
                ContributionDto(
                    id = "10000000-0000-0000-0000-000000000001",
                    memberId = MEMBER.id,
                    memberDisplayName = MEMBER.displayName,
                    membershipTierId = "20000000-0000-0000-0000-000000000001",
                    membershipTierName = "Standard",
                    periodStart = LocalDate(2026, 1, 1),
                    periodEnd = LocalDate(2026, 3, 31),
                    amountDue = BigDecimal("42.50"),
                    status = ContributionStatus.OPEN,
                    paidAt = null,
                    paidAmount = null,
                    note = null,
                    createdAt = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0),
                )

            val bytes = BeitragsrechnungPdfGenerator.generate(contribution, MEMBER, ORGANIZATION)
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
            text shouldContain MEMBER.displayName
            text shouldContain MEMBER.street!!
            text shouldContain "Beitragsrechnung"
            text shouldContain "42,50"
            // Individual words only, not the whole phrase -- a multi-word phrase could in
            // principle straddle a hand-rolled word-wrap line break (never mid-word, but the
            // space between two words could become a newline), so only single-token substrings
            // are reliable across an unknown wrap point.
            text shouldContain "zweiundvierzig"
            text shouldContain "fünfzig"
            text shouldContain "Cent"
            text shouldContain ORGANIZATION.bankIban!!
            text shouldContain ORGANIZATION.bankBic!!
            text shouldContain "Der Vorstand"
        }
    })
