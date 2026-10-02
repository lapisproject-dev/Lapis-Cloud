package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.math.BigDecimal

private val ORG =
    OrganizationSettingsDto(
        id = "00000000-0000-0000-0000-0000000000f2",
        name = "Verein Testverein e.V.",
        street = "Vereinsstrasse 1",
        postalCode = "38100",
        city = "Braunschweig",
        country = "Deutschland",
        bankIban = null,
        bankBic = null,
        taxExemptionAuthority = "Finanzamt Braunschweig",
        taxExemptionDate = LocalDate(2025, 1, 15),
    )

private val PERSON =
    MemberDto(
        id = "00000000-0000-0000-0000-000000000005",
        displayName = "Dorothea Donatorin",
        email = "dorothea@example.org",
        status = MemberStatus.ACTIVE,
        joinedAt = LocalDate(2023, 1, 1),
        role = AccountRole.MEMBER,
        street = "Spenderweg 7",
        postalCode = "38108",
        city = "Braunschweig",
        country = "Deutschland",
    )

private fun textOf(bytes: ByteArray): String {
    val document = Loader.loadPDF(bytes)
    return try {
        PDFTextStripper().getText(document)
    } finally {
        document.close()
    }
}

/**
 * V1.9.38 -- the date line of a letter is "today" in the ORGANIZATION zone (class D), not the UTC date of the server clock: a letter
 * generated at 00:30 in Berlin must not carry yesterday's date. A time typed in for an invitation (class B) is printed as typed.
 */
class PdfIssueDateTest :
    FunSpec({
        val berlin = TimeZone.of("Europe/Berlin")

        test("pdfIssueDate: at 23:30Z on New Year's Eve it is already 2027-01-01 in Berlin, still 2026-12-31 in UTC") {
            TimeTestSupport.withServerClock(instant = "2026-12-31T23:30:00Z") {
                pdfIssueDate(berlin) shouldBe LocalDate(2027, 1, 1)
                pdfIssueDate(TimeZone.UTC) shouldBe LocalDate(2026, 12, 31)
            }
        }

        test("a Spendenbescheinigung generated at 23:30Z carries the Berlin date 01.01.2027") {
            val entry =
                JournalEntryDto(
                    id = "30000000-0000-0000-0000-000000000001",
                    entryDate = LocalDate(2026, 6, 15),
                    description = "Spende",
                    voucherReference = "BELEG-1",
                    createdBy = "00000000-0000-0000-0000-000000000003",
                    createdByDisplayName = "Theresa Treasurer",
                    status = JournalEntryStatus.POSTED,
                    postedAt = LocalDateTime(2026, 6, 15, 12, 0),
                    createdAt = LocalDateTime(2026, 6, 15, 11, 0),
                    postings = emptyList(),
                    donorMemberId = PERSON.id,
                    donorMemberDisplayName = PERSON.displayName,
                )
            TimeTestSupport.resetOrganizationZone()
            val text =
                TimeTestSupport.withServerClock(instant = "2026-12-31T23:30:00Z") {
                    textOf(
                        SpendenbescheinigungPdfGenerator.generate(
                            journalEntry = entry,
                            donationAmount = BigDecimal("10.00"),
                            donor = PERSON,
                            organization = ORG,
                        ),
                    )
                }
            text shouldContain "Braunschweig, 01.01.2027"
            text shouldNotContain "31.12.2026"
        }

        test("an Einladung prints the typed-in time as typed -- the organization zone never shifts it") {
            TimeTestSupport.setOrganizationZone("Asia/Tbilisi")
            try {
                val text =
                    textOf(
                        EinladungPdfGenerator.generate(
                            title = "Einladung",
                            eventDateTime = LocalDateTime(2026, 9, 12, 18, 30),
                            location = "Vereinsheim",
                            bodyText = "Text",
                            recipients = listOf(PERSON),
                            organization = ORG,
                        ),
                    )
                text shouldContain "12.09.2026 um 18:30 Uhr"
            } finally {
                TimeTestSupport.resetOrganizationZone()
            }
        }
    })
