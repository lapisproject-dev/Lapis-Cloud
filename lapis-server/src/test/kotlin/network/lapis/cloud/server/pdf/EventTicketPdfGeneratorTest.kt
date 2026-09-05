package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.events.QrCodeEncoder
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper

private val ORGANIZATION =
    OrganizationSettingsDto(
        id = "00000000-0000-0000-0000-0000000000f3",
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

/** Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [EventTicketPdfGenerator], one A4 page. */
class EventTicketPdfGeneratorTest :
    FunSpec({
        test("generates exactly one page containing the code, title, and location") {
            val code = "ABCD-EFGH-JKMN-PQRS"
            val matrix = QrCodeEncoder.encode("https://example.org/veranstaltung/sommerfest/ticket?code=ABCDEFGHJKMNPQRS")
            val bytes =
                EventTicketPdfGenerator.generate(
                    eventTitle = "Sommerfest 2026",
                    startsAt = LocalDateTime(2026, 8, 1, 18, 0),
                    endsAt = LocalDateTime(2026, 8, 1, 23, 0),
                    locationText = "Vereinsheim, Musterstrasse 1",
                    onlineUrl = null,
                    displayCode = code,
                    qr = matrix,
                    organization = ORGANIZATION,
                )
            (bytes.size > 1_000) shouldBe true
            val header = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            header shouldBe "%PDF"
            val document = Loader.loadPDF(bytes)
            val text =
                try {
                    document.numberOfPages shouldBe 1
                    PDFTextStripper().getText(document)
                } finally {
                    document.close()
                }
            text shouldContain "Sommerfest 2026"
            text shouldContain code
            text shouldContain "Vereinsheim"
        }

        test("the ticket code does not overlap the QR code beneath which it is drawn") {
            // Regression test for a review finding: `LetterPdfBuilder.qrCode()` used to leave only
            // half a line's worth of gap beneath the QR, not enough clearance for the 18pt bold
            // ticket code drawn right after it -- the code's cap height reached back up into the
            // QR's bottom modules, making the human-readable fallback code illegible exactly where
            // a door helper would need it (over black QR modules) and eating into the QR's own
            // error-correction budget. This reads the actual PDF content-stream operators (same
            // approach used to diagnose the original bug) rather than trusting text extraction,
            // which cannot see visual overlap at all.
            val code = "ABCD-EFGH-JKMN-PQRS"
            val codeFontSizePt = 18f
            val matrix = QrCodeEncoder.encode("https://example.org/veranstaltung/sommerfest/ticket?code=ABCDEFGHJKMNPQRS")
            val bytes =
                EventTicketPdfGenerator.generate(
                    eventTitle = "Sommerfest 2026",
                    startsAt = LocalDateTime(2026, 8, 1, 18, 0),
                    endsAt = LocalDateTime(2026, 8, 1, 23, 0),
                    locationText = "Vereinsheim, Musterstrasse 1",
                    onlineUrl = null,
                    displayCode = code,
                    qr = matrix,
                    organization = ORGANIZATION,
                )
            val document = Loader.loadPDF(bytes)
            val geometry =
                try {
                    extractQrAndCodeGeometry(page = document.getPage(0), codeText = code)
                } finally {
                    document.close()
                }
            checkNotNull(geometry.qrBottomY) { "no filled rectangles found on the ticket page -- expected the QR code" }
            checkNotNull(geometry.codeBaselineY) { "ticket code '$code' text was not drawn on the page" }

            val boldFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val codeCapHeight = boldFont.fontDescriptor.capHeight / 1000f * codeFontSizePt
            val codeGlyphTopY = geometry.codeBaselineY + codeCapHeight

            // The code's glyph top must stay at or below the QR's bottom edge (PDF y-axis grows
            // upward), with a real safety margin -- not merely "not touching by a fraction of a
            // point", which is how the original bug's own follow-up fix could silently regress to
            // "technically zero overlap, no visual breathing room" instead of a real fix.
            geometry.qrBottomY.toDouble() shouldBeGreaterThanOrEqual (codeGlyphTopY + 4f).toDouble()
        }

        test("a long title with non-Latin-1 characters does not throw (font-sanitization path)") {
            val longTitle = "Konferenz " + "Тест-Заглавие-ივენთი-".repeat(10)
            val matrix = QrCodeEncoder.encode("https://example.org/veranstaltung/x/ticket?code=WXYZ9876ABCD1234")
            val bytes =
                EventTicketPdfGenerator.generate(
                    eventTitle = longTitle,
                    startsAt = LocalDateTime(2026, 8, 1, 18, 0),
                    endsAt = LocalDateTime(2026, 8, 1, 23, 0),
                    locationText = null,
                    onlineUrl = "https://example.org/live",
                    displayCode = "WXYZ-9876-ABCD-1234",
                    qr = matrix,
                    organization = ORGANIZATION,
                )
            (bytes.size > 1_000) shouldBe true
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) shouldBe "%PDF"
        }
    })

private data class TicketPageGeometry(
    /** Lower-left y of the bottom-most filled rectangle on the page, i.e. the QR's bottom edge. */
    val qrBottomY: Float?,
    /** Baseline y (device space, same as [qrBottomY]) of the `Td` immediately before [codeText] is shown. */
    val codeBaselineY: Float?,
)

/**
 * Walks the raw content-stream operators of [page] (no CTM/graphics-state tracking needed --
 * [LetterPdfBuilder] never issues a `cm` operator, so operand values are already in the page's
 * default, bottom-left-origin coordinate space) to find:
 * - the lowest y of any `re` (rectangle) operator followed by a fill -- the QR is the only thing
 *   this generator draws with `addRect`/`fill`, so this is the QR's bottom edge, and
 * - the y operand of the `Td` operator that immediately precedes the `Tj`/`'` that shows [codeText]
 *   -- the ticket code's baseline, since `emphasisLine`'s `beginText()` resets the text matrix to
 *   identity right before its one `newLineAtOffset` call.
 */
private fun extractQrAndCodeGeometry(
    page: PDPage,
    codeText: String,
): TicketPageGeometry {
    val tokens = PDFStreamParser(page).parse()
    var operands = mutableListOf<Float>()
    var lastTdY: Float? = null
    var qrBottomY: Float? = null
    var codeBaselineY: Float? = null
    for (token in tokens) {
        when (token) {
            is COSNumber -> operands.add(token.floatValue())
            is COSString -> {
                if (token.string == codeText) {
                    codeBaselineY = lastTdY
                }
            }
            is Operator -> {
                when (token.name) {
                    "re" ->
                        if (operands.size >= 4) {
                            val y = operands[operands.size - 3]
                            qrBottomY = if (qrBottomY == null) y else minOf(qrBottomY, y)
                        }
                    "Td", "TD" -> if (operands.size >= 2) lastTdY = operands[operands.size - 1]
                }
                operands = mutableListOf()
            }
            else -> Unit
        }
    }
    return TicketPageGeometry(qrBottomY = qrBottomY, codeBaselineY = codeBaselineY)
}
