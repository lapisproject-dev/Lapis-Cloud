package network.lapis.cloud.server.pdf

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import java.math.BigDecimal
import kotlin.time.Clock

/**
 * Mail-merges a Spendenbescheinigung (§ 50 EStDV Zuwendungsbestaetigung / donation receipt) for a
 * single [journalEntry] (one donation instance) -- structurally follows the official BMF Muster
 * "Zuwendungsbestaetigung" as closely as reasonably possible: donor name/address, issuing
 * organization name/address/tax-exemption reference, amount in figures AND words, date of the
 * donation, a statement that it is a cash donation (Geldzuwendung) used for the tax-privileged
 * purpose, a statement that no goods/services were provided in exchange, place/date of issuance,
 * and a signature line.
 *
 * **Association vs. political party legal basis (V0.4.1 fix wave):** [OrganizationSettingsDto.isPoliticalParty]
 * selects between two materially different legal bases -- a plain gemeinnuetziger Verein's
 * donation receipt cites § 10b EStG (Sonderausgabenabzug / tax DEDUCTION), whereas a donation to a
 * political party is governed by § 34g EStG (Steuerermaessigung / tax CREDIT, with its own,
 * narrower caps and a different official BMF Muster wording -- see BMF's separate
 * "Zuwendungsbestaetigung fuer Beitraege und Spenden an politische Parteien" pattern). Lapis
 * Cloud's own repo description ("Vereine und Parteien") makes both cases first-class, so this
 * generator branches on the flag rather than silently assuming the association-only wording for
 * every organization. **This branch's exact wording is, like the rest of this file, NOT verified
 * word-for-word official legal text -- a party using this feature must have the § 34g wording
 * checked against the current BMF Muster by a human/tax advisor before issuing a real receipt,
 * same caveat as the association path below.**
 *
 * **This is NOT verified, word-for-word official legal text.** The paragraphs below approximate
 * the BMF Muster's required structure and statements, but the *exact* wording must be checked
 * against the current official BMF Muster by a human/tax advisor before this is used to issue a
 * real receipt to a real donor -- green tests (`SpendenbescheinigungPdfGeneratorTest`) prove only
 * that the required *elements* are present, not that the phrasing is legally settled. This
 * mirrors the conservative-scoping approach the V0.3.4 Mittelverwendungsrechnung wave already
 * took for its own legally-sensitive derivations.
 *
 * Aggregation is explicitly out of scope: one receipt = one [journalEntry], never an aggregated
 * Sammelbestaetigung across a period -- that aggregation rule is a genuine legal-wording detail
 * needing a human/tax-advisor check, not a guess.
 *
 * The caller ([network.lapis.cloud.server.routes.registerMailmergeRoutes]) validates [donor] has
 * a complete postal address and [organization] has both tax-exemption fields set before calling
 * this -- see that route's KDoc for the completeness guards.
 */
object SpendenbescheinigungPdfGenerator {
    fun generate(
        journalEntry: JournalEntryDto,
        donationAmount: BigDecimal,
        donor: MemberDto,
        organization: OrganizationSettingsDto,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        builder.letterhead(organization.name, organization.addressLines())
        builder.recipientAddress(donor.addressLines())
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        builder.dateLine(organization.city ?: organization.name, today)
        builder.heading("Bestaetigung ueber Geldzuwendungen")
        builder.paragraph(legalBasisParagraph(organization.isPoliticalParty))
        builder.paragraph(
            "Name und Anschrift des Zuwendenden:\n" + donor.addressLines().joinToString("\n"),
        )
        builder.paragraph(
            "Betrag der Zuwendung: ${formatEuro(donationAmount)} " +
                "(in Worten: ${GermanAmountInWords.format(donationAmount)}).",
        )
        builder.paragraph("Tag der Zuwendung: ${formatGermanDate(journalEntry.entryDate)}.")
        builder.paragraph(
            "Es handelt sich um eine Geldzuwendung, die ausschliesslich zur Foerderung " +
                "steuerbeguenstigter Zwecke verwendet wird. Wir sind wegen Foerderung " +
                "steuerbeguenstigter Zwecke von der Koerperschaftsteuer befreit " +
                "(Freistellungsbescheid des Finanzamts ${organization.taxExemptionAuthority} " +
                "vom ${organization.taxExemptionDate?.let { formatGermanDate(it) }}).",
        )
        builder.paragraph(
            "Es wird bestaetigt, dass fuer die o.g. Zuwendung keine Gegenleistung erbracht wurde " +
                "und dass es sich nicht um einen Mitgliedsbeitrag handelt.",
        )
        builder.dateLine(organization.city ?: organization.name, today)
        builder.signatureLine("Unterschrift")
        return builder.toByteArray()
    }

    /**
     * See class KDoc "Association vs. political party legal basis" -- both branches are
     * unverified approximations of the respective official BMF Muster wording.
     */
    private fun legalBasisParagraph(isPoliticalParty: Boolean): String =
        if (isPoliticalParty) {
            "im Sinne des § 34g des Einkommensteuergesetzes (Zuwendungsbestaetigung fuer " +
                "Beitraege und Spenden an politische Parteien)."
        } else {
            "im Sinne des § 10b des Einkommensteuergesetzes (Zuwendungsbestaetigung)."
        }
}
