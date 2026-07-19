package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import kotlin.time.Clock

/**
 * Mail-merges an Einladung (invitation letter, e.g. for a general-assembly invitation) --
 * deliberately the simplest of the three V0.4.1 templates: a free-text [title]/[eventDateTime]/
 * [location]/[bodyText] merged with a [recipients] list. One page per recipient (a long
 * [bodyText] may still overflow to further pages for that same recipient -- see
 * [LetterPdfBuilder.paragraph]).
 *
 * Unlike [BeitragsrechnungPdfGenerator]/[SpendenbescheinigungPdfGenerator], a recipient missing a
 * postal address is NOT hard-failed here -- there is no legal requirement that an invitation be
 * mailable, so that recipient still gets a page, with a `"(Adresse nicht hinterlegt)"` placeholder
 * line, so one missing address never blocks the whole batch.
 */
object EinladungPdfGenerator {
    fun generate(
        title: String,
        eventDateTime: LocalDateTime,
        location: String,
        bodyText: String,
        recipients: List<MemberDto>,
        organization: OrganizationSettingsDto,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        recipients.forEachIndexed { index, recipient ->
            if (index > 0) builder.newPage()
            builder.letterhead(organization.name, organization.addressLines())
            builder.recipientAddress(recipientAddressLines(recipient))
            builder.dateLine(organization.city ?: organization.name, today)
            builder.heading(title)
            builder.paragraph(
                "Termin: ${formatGermanDate(eventDateTime.date)} um " +
                    "%02d:%02d".format(eventDateTime.hour, eventDateTime.minute) + " Uhr",
            )
            builder.paragraph("Ort: $location")
            builder.paragraph(bodyText)
            builder.signatureLine("Der Vorstand")
        }
        return builder.toByteArray()
    }

    /** [recipient]'s address, or a `"(Adresse nicht hinterlegt)"` placeholder -- see class KDoc. */
    private fun recipientAddressLines(recipient: MemberDto): List<String> =
        if (recipient.street != null) {
            recipient.addressLines()
        } else {
            listOf(recipient.displayName, "(Adresse nicht hinterlegt)")
        }
}
