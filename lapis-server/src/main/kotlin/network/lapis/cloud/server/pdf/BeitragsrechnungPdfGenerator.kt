package network.lapis.cloud.server.pdf

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import kotlin.time.Clock

/**
 * Mail-merges a Beitragsrechnung (membership dues invoice) from a [ContributionDto] + the billed
 * [MemberDto] + the issuing [OrganizationSettingsDto] (letterhead + bank details for the payment
 * instructions). The caller ([network.lapis.cloud.server.routes.registerMailmergeRoutes])
 * validates [member] has a complete postal address before calling this -- see that route's KDoc
 * for the completeness guard.
 */
object BeitragsrechnungPdfGenerator {
    fun generate(
        contribution: ContributionDto,
        member: MemberDto,
        organization: OrganizationSettingsDto,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        builder.letterhead(organization.name, organization.addressLines())
        builder.recipientAddress(member.addressLines())
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        builder.dateLine(organization.city ?: organization.name, today)
        builder.heading("Beitragsrechnung")
        builder.paragraph(
            "Liebe(r) ${member.displayName},\n\n" +
                "hiermit stellen wir Ihnen den Mitgliedsbeitrag fuer den Zeitraum " +
                "${formatGermanDate(contribution.periodStart)} bis ${formatGermanDate(contribution.periodEnd)} " +
                "(Tarif: ${contribution.membershipTierName}) in Rechnung.",
        )
        builder.paragraph(
            "Rechnungsbetrag: ${formatEuro(contribution.amountDue)} " +
                "(in Worten: ${GermanAmountInWords.format(contribution.amountDue)}).",
        )
        val paymentLines =
            buildList {
                add("Bitte ueberweisen Sie den Betrag auf folgendes Konto:")
                organization.bankIban?.let { add("IBAN: $it") }
                organization.bankBic?.let { add("BIC: $it") }
            }
        builder.paragraph(paymentLines.joinToString("\n"))
        builder.paragraph("Vielen Dank fuer Ihre Unterstuetzung!")
        builder.signatureLine("Der Vorstand")
        return builder.toByteArray()
    }
}
