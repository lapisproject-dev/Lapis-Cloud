package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryStatus
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

private val DONOR =
    MemberDto(
        id = "00000000-0000-0000-0000-000000000005",
        displayName = "Dorothea Donatorin",
        email = "dorothea.donatorin@example.org",
        status = MemberStatus.AKTIV,
        joinedAt = LocalDate(2023, 1, 1),
        role = AccountRole.MEMBER,
        street = "Spenderweg 7",
        postalCode = "38108",
        city = "Braunschweig",
        country = "Deutschland",
    )

class SpendenbescheinigungPdfGeneratorTest :
    FunSpec({
        test(
            "generates a one-page Zuwendungsbestaetigung with donor/issuer identity, amount in " +
                "figures and words, tax-exemption reference, and required legal statements",
        ) {
            val journalEntry =
                JournalEntryDto(
                    id = "30000000-0000-0000-0000-000000000001",
                    entryDate = LocalDate(2026, 6, 15),
                    description = "Spende Dorothea Donatorin",
                    voucherReference = "BELEG-2026-001",
                    createdBy = "00000000-0000-0000-0000-000000000003",
                    createdByDisplayName = "Theresa Treasurer",
                    status = JournalEntryStatus.POSTED,
                    postedAt = LocalDateTime(2026, 6, 15, 12, 0),
                    createdAt = LocalDateTime(2026, 6, 15, 11, 0),
                    postings = emptyList(),
                    donorMemberId = DONOR.id,
                    donorMemberDisplayName = DONOR.displayName,
                )
            val donationAmount = BigDecimal("250.00")

            val bytes = SpendenbescheinigungPdfGenerator.generate(journalEntry, donationAmount, DONOR, ORGANIZATION)
            val document = Loader.loadPDF(bytes)
            val text =
                try {
                    document.numberOfPages shouldBe 1
                    PDFTextStripper().getText(document)
                } finally {
                    document.close()
                }

            text shouldContain "Bestaetigung"
            text shouldContain ORGANIZATION.name
            // Individual words only -- "Finanzamt Braunschweig-Wilhelmstrasse" is long enough to
            // straddle a hand-rolled word-wrap line break (never mid-word, but the space between
            // the two words can become a newline), so the whole phrase isn't a reliable substring.
            text shouldContain "Finanzamt"
            text shouldContain "Braunschweig-Wilhelmstrasse"
            text shouldContain DONOR.displayName
            text shouldContain DONOR.street!!
            text shouldContain "250,00"
            text shouldContain "zweihundertfünfzig"
            text shouldContain "15.06.2026"
            text shouldContain "Geldzuwendung"
            text shouldContain "keine Gegenleistung"
            text shouldContain "Unterschrift"
        }

        test("a long body still fits on one page (BMF Muster structure is short, not the long-body pagination case)") {
            // The dedicated long-input/pagination case lives in EinladungPdfGeneratorTest --
            // Spendenbescheinigung's own content is fixed-shape and short by design (see class
            // KDoc: one receipt per single JournalEntry, no aggregation), so it never needs to
            // paginate in normal use. This test just pins that current behaviour.
            val journalEntry =
                JournalEntryDto(
                    id = "30000000-0000-0000-0000-000000000002",
                    entryDate = LocalDate(2026, 1, 1),
                    description = "Spende",
                    voucherReference = null,
                    createdBy = "00000000-0000-0000-0000-000000000003",
                    createdByDisplayName = "Theresa Treasurer",
                    status = JournalEntryStatus.POSTED,
                    postedAt = LocalDateTime(2026, 1, 1, 12, 0),
                    createdAt = LocalDateTime(2026, 1, 1, 11, 0),
                    postings = emptyList(),
                    donorMemberId = DONOR.id,
                    donorMemberDisplayName = DONOR.displayName,
                )
            val bytes = SpendenbescheinigungPdfGenerator.generate(journalEntry, BigDecimal("1.00"), DONOR, ORGANIZATION)
            val document = Loader.loadPDF(bytes)
            try {
                document.numberOfPages shouldBe 1
            } finally {
                document.close()
            }
        }

        test(
            "isPoliticalParty=true cites § 34g EStG (political-party donation) instead of § 10b EStG " +
                "(association donation) -- see class KDoc \"Association vs. political party legal basis\"",
        ) {
            val journalEntry =
                JournalEntryDto(
                    id = "30000000-0000-0000-0000-000000000003",
                    entryDate = LocalDate(2026, 6, 15),
                    description = "Spende an Partei",
                    voucherReference = null,
                    createdBy = "00000000-0000-0000-0000-000000000003",
                    createdByDisplayName = "Theresa Treasurer",
                    status = JournalEntryStatus.POSTED,
                    postedAt = LocalDateTime(2026, 6, 15, 12, 0),
                    createdAt = LocalDateTime(2026, 6, 15, 11, 0),
                    postings = emptyList(),
                    donorMemberId = DONOR.id,
                    donorMemberDisplayName = DONOR.displayName,
                )
            val partyOrganization = ORGANIZATION.copy(isPoliticalParty = true)

            val partyBytes = SpendenbescheinigungPdfGenerator.generate(journalEntry, BigDecimal("50.00"), DONOR, partyOrganization)
            val partyDocument = Loader.loadPDF(partyBytes)
            val partyText =
                try {
                    PDFTextStripper().getText(partyDocument)
                } finally {
                    partyDocument.close()
                }
            partyText shouldContain "34g"
            (partyText.contains("10b")) shouldBe false

            // Regression guard: the default (isPoliticalParty=false, i.e. ORGANIZATION as-is)
            // still cites § 10b, never § 34g.
            val associationBytes = SpendenbescheinigungPdfGenerator.generate(journalEntry, BigDecimal("50.00"), DONOR, ORGANIZATION)
            val associationDocument = Loader.loadPDF(associationBytes)
            val associationText =
                try {
                    PDFTextStripper().getText(associationDocument)
                } finally {
                    associationDocument.close()
                }
            associationText shouldContain "10b"
            (associationText.contains("34g")) shouldBe false
        }
    })
