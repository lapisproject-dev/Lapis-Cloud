package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

private val ORGANIZATION =
    OrganizationSettingsDto(
        id = "00000000-0000-0000-0000-0000000000f2",
        name = "Verein Testverein e.V.",
        street = "Vereinsstrasse 1",
        postalCode = "38100",
        city = "Braunschweig",
        country = "Deutschland",
        bankIban = null,
        bankBic = null,
        taxExemptionAuthority = null,
        taxExemptionDate = null,
    )

private fun member(
    id: String,
    displayName: String,
    withAddress: Boolean,
) = MemberDto(
    id = id,
    displayName = displayName,
    email = "$displayName@example.org".lowercase().replace(" ", "."),
    status = MemberStatus.AKTIV,
    joinedAt = LocalDate(2024, 1, 1),
    role = AccountRole.MEMBER,
    street = if (withAddress) "Musterstrasse 5" else null,
    postalCode = if (withAddress) "38102" else null,
    city = if (withAddress) "Braunschweig" else null,
    country = if (withAddress) "Deutschland" else null,
)

class EinladungPdfGeneratorTest :
    FunSpec({
        test("one page per recipient, in order") {
            val recipients =
                listOf(
                    member("00000000-0000-0000-0000-000000000010", "Anna Erste", withAddress = true),
                    member("00000000-0000-0000-0000-000000000011", "Bruno Zweiter", withAddress = true),
                    member("00000000-0000-0000-0000-000000000012", "Clara Dritte", withAddress = true),
                )
            val eventDateTime = LocalDateTime(2026, 9, 12, 18, 30)

            val bytes =
                EinladungPdfGenerator.generate(
                    title = "Einladung zur Mitgliederversammlung",
                    eventDateTime = eventDateTime,
                    location = "Vereinsheim, Musterstrasse 1",
                    bodyText = "Wir laden Sie herzlich zur Mitgliederversammlung ein.",
                    recipients = recipients,
                    organization = ORGANIZATION,
                )
            val document = Loader.loadPDF(bytes)
            val text =
                try {
                    document.numberOfPages shouldBe 3
                    PDFTextStripper().getText(document)
                } finally {
                    document.close()
                }

            text shouldContain "Anna Erste"
            text shouldContain "Bruno Zweiter"
            text shouldContain "Clara Dritte"
            text shouldContain "Einladung zur Mitgliederversammlung"
            text shouldContain "18:30"
            text shouldContain "12.09.2026"
            text shouldContain "Vereinsheim"
        }

        test("a recipient with no address gets a placeholder line instead of being hard-failed") {
            val recipients = listOf(member("00000000-0000-0000-0000-000000000013", "Doris Ohneadresse", withAddress = false))
            val bytes =
                EinladungPdfGenerator.generate(
                    title = "Einladung",
                    eventDateTime = LocalDateTime(2026, 9, 12, 18, 30),
                    location = "Vereinsheim",
                    bodyText = "Text.",
                    recipients = recipients,
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
            text shouldContain "Doris Ohneadresse"
            text shouldContain "(Adresse nicht hinterlegt)"
        }

        test("a long bodyText paginates to more than one page for a single recipient") {
            val recipients = listOf(member("00000000-0000-0000-0000-000000000014", "Erik Langtext", withAddress = true))
            val longBody = (1..400).joinToString(" ") { "Wortnummer$it" }
            val bytes =
                EinladungPdfGenerator.generate(
                    title = "Einladung",
                    eventDateTime = LocalDateTime(2026, 9, 12, 18, 30),
                    location = "Vereinsheim",
                    bodyText = longBody,
                    recipients = recipients,
                    organization = ORGANIZATION,
                )
            val document = Loader.loadPDF(bytes)
            val text =
                try {
                    (document.numberOfPages > 1) shouldBe true
                    PDFTextStripper().getText(document)
                } finally {
                    document.close()
                }
            text shouldContain "Wortnummer1"
            text shouldContain "Wortnummer400"
        }
    })
