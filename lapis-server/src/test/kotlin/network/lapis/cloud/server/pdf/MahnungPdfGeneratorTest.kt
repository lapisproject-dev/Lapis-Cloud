package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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
        taxExemptionAuthority = null,
        taxExemptionDate = null,
    )

private val MEMBER =
    MemberDto(
        id = "00000000-0000-0000-0000-000000000004",
        displayName = "Max Mitglied",
        email = "max.mitglied@example.org",
        status = MemberStatus.ACTIVE,
        joinedAt = LocalDate(2024, 1, 1),
        role = AccountRole.MEMBER,
        street = "Musterstrasse 5",
        postalCode = "38102",
        city = "Braunschweig",
        country = "Deutschland",
    )

private fun contribution(amountDue: BigDecimal = BigDecimal("50.00")) =
    ContributionDto(
        id = "10000000-0000-0000-0000-000000000001",
        memberId = MEMBER.id,
        memberDisplayName = MEMBER.displayName,
        membershipTierId = "20000000-0000-0000-0000-000000000001",
        membershipTierName = "Standard",
        periodStart = LocalDate(2026, 1, 1),
        periodEnd = LocalDate(2026, 3, 31),
        amountDue = amountDue,
        status = ContributionStatus.OVERDUE,
        paidAt = null,
        paidAmount = null,
        note = null,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        dueDate = LocalDate(2026, 1, 15),
    )

class MahnungPdfGeneratorTest :
    FunSpec({
        test("non-empty PDF with the PDF magic header; deterministic for a fixed issuedOn") {
            val bytesA =
                MahnungPdfGenerator.generate(
                    contribution = contribution(),
                    member = MEMBER,
                    organization = ORGANIZATION,
                    levelName = "1. Mahnung",
                    levelNumber = 2,
                    feeAmount = BigDecimal("5.00"),
                    respondBy = LocalDate(2026, 2, 1),
                    issuedOn = LocalDate(2026, 1, 20),
                )
            bytesA.isNotEmpty() shouldBe true
            bytesA.take(5).toByteArray().toString(Charsets.US_ASCII) shouldBe "%PDF-"

            val bytesB =
                MahnungPdfGenerator.generate(
                    contribution = contribution(),
                    member = MEMBER,
                    organization = ORGANIZATION,
                    levelName = "1. Mahnung",
                    levelNumber = 2,
                    feeAmount = BigDecimal("5.00"),
                    respondBy = LocalDate(2026, 2, 1),
                    issuedOn = LocalDate(2026, 1, 20),
                )
            bytesA.size shouldBe bytesB.size
        }

        test("contains heading, amount, fee, total, response deadline and bank details") {
            val bytes =
                MahnungPdfGenerator.generate(
                    contribution = contribution(BigDecimal("50.00")),
                    member = MEMBER,
                    organization = ORGANIZATION,
                    levelName = "1. Mahnung",
                    levelNumber = 2,
                    feeAmount = BigDecimal("5.00"),
                    respondBy = LocalDate(2026, 2, 1),
                    issuedOn = LocalDate(2026, 1, 20),
                )
            val text = extractText(bytes)
            text shouldContain "1. Mahnung"
            text shouldContain MEMBER.displayName
            text shouldContain "50,00"
            text shouldContain "5,00"
            text shouldContain "55,00"
            text shouldContain ORGANIZATION.bankIban!!
            text shouldContain "01.02.2026"
        }

        test("feeAmount = null -> no fee line, total equals the plain contribution amount") {
            val bytes =
                MahnungPdfGenerator.generate(
                    contribution = contribution(BigDecimal("50.00")),
                    member = MEMBER,
                    organization = ORGANIZATION,
                    levelName = "Zahlungserinnerung",
                    levelNumber = 1,
                    feeAmount = null,
                    respondBy = LocalDate(2026, 2, 1),
                    issuedOn = LocalDate(2026, 1, 20),
                )
            val text = extractText(bytes)
            text shouldContain "Zahlungserinnerung"
            text.contains("Mahngebuehr") shouldBe false
        }

        test("very long member display name / address does not throw (hand-rolled LetterPdfBuilder word-wrap)") {
            val longNameMember = MEMBER.copy(displayName = "A".repeat(200), street = "B".repeat(200))
            val bytes =
                MahnungPdfGenerator.generate(
                    contribution = contribution(),
                    member = longNameMember,
                    organization = ORGANIZATION,
                    levelName = "1. Mahnung",
                    levelNumber = 2,
                    feeAmount = BigDecimal("5.00"),
                    respondBy = LocalDate(2026, 2, 1),
                    issuedOn = LocalDate(2026, 1, 20),
                )
            bytes.isNotEmpty() shouldBe true
        }

        test("non-Latin-1 member name (Georgian/Cyrillic) does not throw -- font fallback replaces unencodable glyphs") {
            val nonLatinMember = MEMBER.copy(displayName = "აირაკლი Мир")
            val bytes =
                MahnungPdfGenerator.generate(
                    contribution = contribution(),
                    member = nonLatinMember,
                    organization = ORGANIZATION,
                    levelName = "1. Mahnung",
                    levelNumber = 2,
                    feeAmount = BigDecimal("5.00"),
                    respondBy = LocalDate(2026, 2, 1),
                    issuedOn = LocalDate(2026, 1, 20),
                )
            bytes.isNotEmpty() shouldBe true
        }
    })

private fun extractText(bytes: ByteArray): String {
    val document = Loader.loadPDF(bytes)
    return try {
        PDFTextStripper().getText(document)
    } finally {
        document.close()
    }
}
