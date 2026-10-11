package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto

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
 *
 * V1.9.101: [generate] takes an optional [partNote]; when set, a cover sheet ("Versandübersicht", the part
 * counter, the as-of time and the recipient count) precedes the letters of one slice of an "all active members"
 * invitation. Server PDFs are fixed German (like "Termin:" and "Der Vorstand"), so the cover sheet is not `tr()`-ed.
 */
object EinladungPdfGenerator {
    fun generate(
        title: String,
        eventDateTime: LocalDateTime,
        location: String,
        bodyText: String,
        recipients: List<MemberDto>,
        organization: OrganizationSettingsDto,
        partNote: String? = null,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        val today = pdfIssueDate()
        if (partNote != null) {
            // V1.9.101 -- cover sheet for a "send to all active members" part; fixed German like the rest of this server PDF.
            builder.letterhead(orgName = organization.name, orgAddressLines = organization.addressLines())
            builder.heading("Einladung \u2013 Versand\u00fcbersicht")
            builder.paragraph(partNote)
            if (recipients.isNotEmpty()) builder.newPage()
        }
        recipients.forEachIndexed { index, recipient ->
            if (index > 0) builder.newPage()
            builder.letterhead(orgName = organization.name, orgAddressLines = organization.addressLines())
            builder.recipientAddress(recipientAddressLines(recipient))
            builder.dateLine(place = organization.city ?: organization.name, date = today)
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
