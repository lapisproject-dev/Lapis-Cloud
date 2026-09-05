package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.events.QrCodeMatrix
import network.lapis.cloud.shared.domain.OrganizationSettingsDto

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- the printable ticket, one A4 page. Reached
 * from the PUBLIC (no-authentication) `GET /veranstaltung/{slug}/ticket.pdf?code=...` route -- the
 * bearer code itself is this endpoint's whole authorization, same posture as the ticket page and
 * its SVG sibling (see `network.lapis.cloud.server.routes.EventPublicRoutes` KDoc). Carries NO
 * personally identifying detail (no name, no email, no registration id) -- see
 * `EventPublicHtml.ticketPage` KDoc "strongest reading of the data-protection requirement" for why;
 * if this PDF leaks, it leaks nothing about who holds it.
 */
internal object EventTicketPdfGenerator {
    /** Comfortably scannable from a phone screen or a printed page -- see wave plan §6 "Kantenlänge ≥ 45 mm (≈ 128 pt)". */
    private const val QR_SIZE_PT = 140f

    /** ≥ 14pt, per wave plan §6 -- the code is the fallback path when a scanner cannot read the QR at all. */
    private const val CODE_FONT_SIZE = 18f

    fun generate(
        eventTitle: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        locationText: String?,
        onlineUrl: String?,
        displayCode: String,
        qr: QrCodeMatrix,
        organization: OrganizationSettingsDto,
    ): ByteArray {
        val builder = LetterPdfBuilder()
        builder.letterhead(orgName = organization.name, orgAddressLines = organization.addressLines())
        builder.heading("Eintrittskarte")
        builder.qrCode(matrix = qr, sizePt = QR_SIZE_PT)
        builder.emphasisLine(text = displayCode, size = CODE_FONT_SIZE)
        builder.centeredParagraph(eventTitle)
        builder.centeredParagraph(
            "${formatGermanDate(startsAt.date)}, ${"%02d:%02d".format(startsAt.hour, startsAt.minute)} – " +
                "${"%02d:%02d".format(endsAt.hour, endsAt.minute)} Uhr",
        )
        if (locationText != null) builder.centeredParagraph(locationText)
        if (onlineUrl != null) builder.centeredParagraph(onlineUrl)
        return builder.toByteArray()
    }
}
