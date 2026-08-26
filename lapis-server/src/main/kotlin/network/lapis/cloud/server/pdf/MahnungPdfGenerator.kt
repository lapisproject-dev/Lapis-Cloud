package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import java.math.BigDecimal

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Mail-merges one dunning letter from a [ContributionDto]
 * + the dunned [MemberDto] + the issuing [OrganizationSettingsDto] (letterhead + bank details) +
 * the specific escalation step's [levelName]/[levelNumber]/[feeAmount]/[respondBy]. Reuses
 * [LetterPdfBuilder] exactly like [BeitragsrechnungPdfGenerator] -- no new PDF stack.
 *
 * Two deliberate deviations from [BeitragsrechnungPdfGenerator], both load-bearing for this class'
 * own callers:
 * 1. [issuedOn] is a **parameter**, never `Clock.System.now()` read inside this function --
 *    testable/deterministic (`MahnungPdfGeneratorTest` pins the exact rendered date), and the
 *    caller ([network.lapis.cloud.server.payment.dunning.DunningIssuance]) already has the
 *    authoritative `now` in hand from its own single [network.lapis.cloud.server.db.DbClock] read.
 * 2. **Not anwaltlich geprueft** -- same disclosure [SpendenbescheinigungPdfGenerator] already
 *    carries for its own legal-form wording: the dunning-letter text below is a fachlich plausible
 *    formulation, not a reviewed legal document. See
 *    [network.lapis.cloud.server.rpc.DunningComplianceDisclaimer] KDoc for the full risk-area list
 *    an ADMIN must acknowledge before this generator's output can ever reach a member.
 *
 * The letter's total (`amountDue + (feeAmount ?: 0)`) is shown with the fee broken out separately
 * -- `contribution.amount_due` itself is NEVER modified anywhere in this wave (see
 * `34-dunning.kuml.kts` file header "Scope").
 */
object MahnungPdfGenerator {
    fun generate(
        contribution: ContributionDto,
        member: MemberDto,
        organization: OrganizationSettingsDto,
        levelName: String,
        levelNumber: Int,
        feeAmount: BigDecimal?,
        respondBy: LocalDate,
        issuedOn: LocalDate,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        builder.letterhead(orgName = organization.name, orgAddressLines = organization.addressLines())
        builder.recipientAddress(member.addressLines())
        builder.dateLine(place = organization.city ?: organization.name, date = issuedOn)
        builder.heading(levelName)

        val totalAmount = contribution.amountDue.add(feeAmount ?: BigDecimal.ZERO)

        val introduction =
            if (levelNumber <= 1) {
                "Liebe(r) ${member.displayName},\n\n" +
                    "trotz Faelligkeit ist der folgende Mitgliedsbeitrag noch nicht bei uns eingegangen. " +
                    "Bitte pruefen Sie, ob der Beitrag bereits versehentlich nicht ueberwiesen wurde."
            } else {
                "Liebe(r) ${member.displayName},\n\n" +
                    "trotz unserer vorherigen Erinnerung ist der folgende Mitgliedsbeitrag weiterhin offen."
            }
        builder.paragraph(introduction)

        builder.paragraph(
            "Zeitraum: ${formatGermanDate(contribution.periodStart)} bis ${formatGermanDate(contribution.periodEnd)} " +
                "(Tarif: ${contribution.membershipTierName})\n" +
                "Beitrag: ${formatEuro(contribution.amountDue)}" +
                (feeAmount?.let { "\nMahngebuehr: ${formatEuro(it)}" } ?: "") +
                "\nGesamtbetrag: ${formatEuro(totalAmount)} (in Worten: ${GermanAmountInWords.format(totalAmount)})",
        )

        builder.paragraph(
            "Bitte begleichen Sie den Gesamtbetrag bis zum ${formatGermanDate(respondBy)} auf folgendes Konto:",
        )
        val paymentLines =
            buildList {
                organization.bankIban?.let { add("IBAN: $it") }
                organization.bankBic?.let { add("BIC: $it") }
            }
        if (paymentLines.isNotEmpty()) builder.paragraph(paymentLines.joinToString("\n"))

        builder.paragraph(
            "Sollten Sie den Beitrag bereits beglichen haben, betrachten Sie dieses Schreiben bitte als " +
                "gegenstandslos. Bei Rueckfragen wenden Sie sich gerne an den Vorstand.",
        )
        builder.signatureLine("Der Vorstand")
        return builder.toByteArray()
    }
}
