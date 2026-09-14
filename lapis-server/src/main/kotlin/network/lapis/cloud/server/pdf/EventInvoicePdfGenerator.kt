package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import java.math.BigDecimal
import kotlin.time.Clock

/**
 * Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen" -- the invoice PDF for
 * [network.lapis.cloud.server.rpc.EventService.issueEventInvoice]. **Deliberately DTO-agnostic**
 * (takes [recipientName]/billing address fields directly, NOT a `MemberDto`) -- unlike
 * [BeitragsrechnungPdfGenerator], the recipient here may be a GUEST registration with no member
 * record at all (`EventRegistrationDto.memberId == null`), so this generator cannot depend on
 * [network.lapis.cloud.shared.domain.MemberDto.addressLines] the way that template does.
 *
 * Every billing-address field is optional (see [IEventService.issueEventInvoice] KDoc "every
 * billing-address field is optional") -- [recipientAddressLines] simply omits whichever component
 * is absent, same defensive posture [MemberDto.addressLines]/[OrganizationSettingsDto.addressLines]
 * already establish in [PdfMailmergeSupport].
 *
 * No legal-completeness review of this template's wording was performed (same caveat every other
 * generator in this package -- [SpendenbescheinigungPdfGenerator] KDoc -- documents): only the
 * Pflichtelemente named in the wave plan (Empfängeradresse, Betrag in Zahlen UND Worten,
 * Fälligkeitsdatum, Zahlungsreferenz) are covered.
 */
internal object EventInvoicePdfGenerator {
    fun generate(
        recipientName: String,
        billingStreet: String?,
        billingPostalCode: String?,
        billingCity: String?,
        billingCountry: String?,
        eventTitle: String,
        amount: BigDecimal,
        dueDate: LocalDate,
        reference: String,
        organization: OrganizationSettingsDto,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        builder.letterhead(orgName = organization.name, orgAddressLines = organization.addressLines())
        builder.recipientAddress(
            recipientAddressLines(
                recipientName = recipientName,
                billingStreet = billingStreet,
                billingPostalCode = billingPostalCode,
                billingCity = billingCity,
                billingCountry = billingCountry,
            ),
        )
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        builder.dateLine(place = organization.city ?: organization.name, date = today)
        builder.heading("Rechnung")
        builder.paragraph(
            "Liebe(r) $recipientName,\n\n" +
                "hiermit stellen wir Ihnen die Teilnahmegebühr für die Veranstaltung \"$eventTitle\" in Rechnung.",
        )
        builder.paragraph(
            "Rechnungsbetrag: ${formatEuro(amount)} (in Worten: ${GermanAmountInWords.format(amount)}).",
        )
        builder.paragraph(
            "Bitte begleichen Sie den Betrag bis zum ${formatGermanDate(dueDate)} auf folgendes Konto:\n" +
                buildList {
                    organization.bankIban?.let { add("IBAN: $it") }
                    organization.bankBic?.let { add("BIC: $it") }
                    add("Verwendungszweck: $reference")
                }.joinToString("\n"),
        )
        builder.paragraph("Vielen Dank für Ihre Teilnahme!")
        builder.signatureLine("Der Vorstand")
        return builder.toByteArray()
    }

    private fun recipientAddressLines(
        recipientName: String,
        billingStreet: String?,
        billingPostalCode: String?,
        billingCity: String?,
        billingCountry: String?,
    ): List<String> =
        buildList {
            add(recipientName)
            billingStreet?.let { add(it) }
            val cityLine = listOfNotNull(billingPostalCode, billingCity).joinToString(" ")
            if (cityLine.isNotBlank()) add(cityLine)
            billingCountry?.let { add(it) }
        }
}
