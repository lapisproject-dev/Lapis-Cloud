package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.events.QrCodeMatrix
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

private val PAGE_WIDTH = PDRectangle.A4.width
private val PAGE_HEIGHT = PDRectangle.A4.height

// ~2cm at 72pt/inch (2cm = 2 / 2.54 * 72 ≈ 56.7pt) -- comfortable, not DIN 5008-precise, margins.
private const val MARGIN_LEFT = 56.7f
private const val MARGIN_RIGHT = 56.7f
private const val MARGIN_TOP = 56.7f
private const val MARGIN_BOTTOM = 56.7f

private const val BODY_FONT_SIZE = 11f
private const val SMALL_FONT_SIZE = 9f
private const val HEADING_FONT_SIZE = 14f
private const val LINE_HEIGHT = 14f

private val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

/**
 * Shared low-level letter-layout helper for [BeitragsrechnungPdfGenerator]/
 * [SpendenbescheinigungPdfGenerator]/[EinladungPdfGenerator] -- built once, reused by all three,
 * so each template's own code is just "fill in the fields" rather than re-implementing PDF
 * primitives three times.
 *
 * **Library choice: Apache PDFBox (`org.apache.pdfbox:pdfbox`), not openhtmltopdf.** PDFBox is
 * unambiguously Apache-2.0 end to end -- this product (non-profit/political-party) has a hard
 * permissive-license-only constraint, and openhtmltopdf's own licensing is dual/LGPL-flavoured
 * per module, too ambiguous for that constraint. Trade-off accepted: PDFBox has no HTML/CSS
 * templating or automatic text flow, so word-wrap and page-break-on-overflow are hand-rolled here
 * (see [paragraph]) -- this is the single largest implementation-risk item in this wave; it is
 * covered by deliberately long-input test cases in `LetterPdfBuilder`'s callers' tests, not just
 * happy-path short strings.
 *
 * Uses the standard 14 Helvetica fonts (no embedding needed) with PDFBox's default
 * `WinAnsiEncoding` for non-Symbol/ZapfDingbats standard fonts, which covers German umlauts
 * (ä/ö/ü) and ß directly -- no transliteration needed.
 *
 * **Non-Latin-1 characters**: `WinAnsiEncoding` only covers a Latin-1-ish repertoire. A member
 * or organization display name/address containing Cyrillic, Georgian, CJK characters or emoji
 * (plausible given this product's Georgian/political-party user base) would otherwise make
 * PDFBox's `showText`/`getStringWidth` throw `IllegalArgumentException` ("is not available in
 * this font's encoding"), surfacing as an uncaught 500 for that specific member every time their
 * document is requested. Since embedding a Unicode font is out of scope this wave (see [sanitizeForFont]),
 * every text entry point in this class defensively replaces characters the active font cannot
 * encode with `?` before measuring/rendering, so document generation always succeeds even if a
 * glyph is lost.
 */
internal class LetterPdfBuilder {
    private val document = PDDocument()
    private val regularFont: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val boldFont: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private var page: PDPage = PDPage(PDRectangle.A4)
    private var contentStream: PDPageContentStream = PDPageContentStream(document, page)
    private var cursorY: Float = PAGE_HEIGHT - MARGIN_TOP

    init {
        document.addPage(page)
    }

    /** Starts a fresh page -- called automatically by [ensureSpace] on overflow, but also exposed directly. */
    fun newPage() {
        contentStream.close()
        page = PDPage(PDRectangle.A4)
        document.addPage(page)
        contentStream = PDPageContentStream(document, page)
        cursorY = PAGE_HEIGHT - MARGIN_TOP
    }

    /** Sender block, small font, top-left. */
    fun letterhead(
        orgName: String,
        orgAddressLines: List<String>,
    ) {
        writeLine(text = sanitizeForFont(text = orgName, font = boldFont), font = boldFont, size = SMALL_FONT_SIZE)
        orgAddressLines.forEach {
            writeLine(
                text = sanitizeForFont(text = it, font = regularFont),
                font = regularFont,
                size = SMALL_FONT_SIZE,
            )
        }
        cursorY -= LINE_HEIGHT
    }

    /** Recipient block, body font, below the letterhead. */
    fun recipientAddress(lines: List<String>) {
        lines.forEach { writeLine(text = sanitizeForFont(text = it, font = regularFont), font = regularFont, size = BODY_FONT_SIZE) }
        cursorY -= LINE_HEIGHT
    }

    /** e.g. "Braunschweig, 19.07.2026", right-aligned. */
    fun dateLine(
        place: String,
        date: LocalDate,
    ) {
        val text = sanitizeForFont(text = "$place, ${formatGermanDate(date)}", font = regularFont)
        val width = textWidth(text = text, font = regularFont, size = BODY_FONT_SIZE)
        writeLineAt(text = text, font = regularFont, size = BODY_FONT_SIZE, x = PAGE_WIDTH - MARGIN_RIGHT - width)
        cursorY -= LINE_HEIGHT
    }

    /** Bold heading, e.g. the letter/document title. */
    fun heading(text: String) {
        ensureSpace(LINE_HEIGHT * 2)
        writeLine(text = sanitizeForFont(text = text, font = boldFont), font = boldFont, size = HEADING_FONT_SIZE)
        cursorY -= LINE_HEIGHT / 2
    }

    /**
     * Body text, word-wrapped to [CONTENT_WIDTH] and paginated via [ensureSpace] -- the hand-
     * rolled flow-text logic this class's KDoc flags as the wave's largest implementation risk.
     * `"\n"` in [text] starts a new paragraph (blank line between); everything else is reflowed,
     * ignoring the caller's original line breaks (this is a mail-merge letter body, not
     * preformatted text).
     */
    fun paragraph(text: String) {
        // Split on the raw "\n" BEFORE sanitizing -- sanitizing first could turn the separator
        // itself into '?' if the font cannot encode a bare newline, corrupting paragraph breaks.
        val paragraphs = text.split("\n")
        paragraphs.forEachIndexed { index, para ->
            if (para.isBlank()) {
                ensureSpace(LINE_HEIGHT)
                cursorY -= LINE_HEIGHT
            } else {
                wrapLines(
                    text = sanitizeForFont(text = para, font = regularFont),
                    font = regularFont,
                    size = BODY_FONT_SIZE,
                    maxWidth = CONTENT_WIDTH,
                ).forEach { line ->
                    ensureSpace(LINE_HEIGHT)
                    writeLine(text = line, font = regularFont, size = BODY_FONT_SIZE)
                }
            }
            if (index < paragraphs.lastIndex) {
                ensureSpace(LINE_HEIGHT)
                cursorY -= LINE_HEIGHT / 2
            }
        }
    }

    /** A short horizontal rule plus [label] beneath it, e.g. for a signature. */
    fun signatureLine(label: String = "Unterschrift") {
        ensureSpace(LINE_HEIGHT * 4)
        cursorY -= LINE_HEIGHT * 2
        contentStream.moveTo(MARGIN_LEFT, cursorY)
        contentStream.lineTo(MARGIN_LEFT + 200f, cursorY)
        contentStream.stroke()
        cursorY -= LINE_HEIGHT
        writeLine(text = sanitizeForFont(text = label, font = regularFont), font = regularFont, size = SMALL_FONT_SIZE)
    }

    /**
     * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- draws [matrix] centered horizontally,
     * [sizePt] points square, as vector rectangles (`addRect`/`fill`, one PDFBox operation per
     * [QrCodeMatrix.horizontalRuns] run -- same run-length rasterization `EventTicketSvg` applies to
     * its own SVG output). Deliberately NOT a raster image -- no `PDImageXObject`/`BufferedImage`
     * anywhere in this path, keeping ticket-PDF generation exactly as headless-safe (no AWT
     * `Toolkit`/font-metrics-via-`Graphics2D` dependency) as the rest of this class already is. The
     * default PDFBox fill color is already black and this class never changes it, so no explicit
     * `setNonStrokingColor` call is made here -- see the wave plan's own "API-Verifikationsauflage"
     * note for why that call would need re-checking against the pinned PDFBox version if ever added.
     */
    fun qrCode(
        matrix: QrCodeMatrix,
        sizePt: Float,
    ) {
        ensureSpace(sizePt)
        val moduleSize = sizePt / matrix.size
        val left = MARGIN_LEFT + (CONTENT_WIDTH - sizePt) / 2f
        val top = cursorY
        for (run in matrix.horizontalRuns()) {
            val x = left + run.x * moduleSize
            // PDF y-axis grows upward; row 0 of the matrix is the TOP of the code, hence `top - (y+1)*moduleSize`.
            val y = top - (run.y + 1) * moduleSize
            contentStream.addRect(x, y, run.length * moduleSize, moduleSize)
        }
        contentStream.fill()
        // Deliberately a FULL `LINE_HEIGHT` gap plus the usual inter-element half-line (matching
        // the gap `heading()` leaves, not the smaller gap a same-size text line leaves after
        // itself) -- see [emphasisLine] KDoc for why a graphic's exact bottom edge needs a bigger
        // safety margin here than one text baseline leaves for the next.
        cursorY -= sizePt + LINE_HEIGHT + LINE_HEIGHT / 2
    }

    /**
     * A single, larger-than-body-text line, centered -- used for the ticket code beneath the QR.
     *
     * Every other text primitive in this class draws directly at `cursorY` as the baseline,
     * relying on the previous element having left enough clearance above it -- an assumption
     * that only holds because those primitives are never asked to draw text taller than
     * [LINE_HEIGHT]. [emphasisLine] is the one caller-facing exception (see
     * `EventTicketPdfGenerator`'s 18pt ticket code, bigger than [LINE_HEIGHT]'s 14pt), so it
     * additionally checks its own cap height against [LINE_HEIGHT] and, if [size] would make the
     * glyphs taller than that budget, nudges its own baseline further down first -- independent
     * of how much clearance the preceding element happened to leave. Without this, oversized
     * emphasis text can render on top of whatever was drawn immediately above it (found in review
     * for the QR-code/ticket-code pairing this method exists for).
     */
    fun emphasisLine(
        text: String,
        size: Float,
    ) {
        val capHeight = boldFont.fontDescriptor.capHeight / 1000f * size
        val extraAscent = (capHeight - LINE_HEIGHT).coerceAtLeast(0f)
        ensureSpace(LINE_HEIGHT * 1.5f + extraAscent)
        cursorY -= extraAscent
        val sanitized = sanitizeForFont(text = text, font = boldFont)
        val width = textWidth(text = sanitized, font = boldFont, size = size)
        val x = MARGIN_LEFT + (CONTENT_WIDTH - width) / 2f
        contentStream.beginText()
        contentStream.setFont(boldFont, size)
        contentStream.newLineAtOffset(x, cursorY)
        contentStream.showText(sanitized)
        contentStream.endText()
        cursorY -= size + LINE_HEIGHT / 2
    }

    /** A single centered body-text line -- e.g. the event title/date beneath the ticket code. */
    fun centeredParagraph(text: String) {
        ensureSpace(LINE_HEIGHT)
        val sanitized = sanitizeForFont(text = text, font = regularFont)
        val width = textWidth(text = sanitized, font = regularFont, size = BODY_FONT_SIZE)
        val x = MARGIN_LEFT + (CONTENT_WIDTH - width) / 2f
        writeLineAt(text = sanitized, font = regularFont, size = BODY_FONT_SIZE, x = x)
    }

    /** Closes the current content stream and serializes the whole document. Terminal -- do not reuse the builder after this. */
    fun toByteArray(): ByteArray {
        contentStream.close()
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    /** Starts a new page if [requiredHeight] would not fit above [MARGIN_BOTTOM] at the current [cursorY]. */
    private fun ensureSpace(requiredHeight: Float) {
        if (cursorY - requiredHeight < MARGIN_BOTTOM) {
            newPage()
        }
    }

    private fun writeLine(
        text: String,
        font: PDFont,
        size: Float,
    ) = writeLineAt(text = text, font = font, size = size, x = MARGIN_LEFT)

    private fun writeLineAt(
        text: String,
        font: PDFont,
        size: Float,
        x: Float,
    ) {
        contentStream.beginText()
        contentStream.setFont(font, size)
        contentStream.newLineAtOffset(x, cursorY)
        contentStream.showText(text)
        contentStream.endText()
        cursorY -= LINE_HEIGHT
    }

    /**
     * Replaces every character [font] cannot encode (per its `WinAnsiEncoding`, see class KDoc
     * "Non-Latin-1 characters") with `?`, so callers can safely measure/render arbitrary
     * member-/organization-supplied text without PDFBox throwing `IllegalArgumentException`.
     * Checked one character at a time (short letter-body strings, not a hot path) rather than
     * probing the whole string at once, so a single unsupported glyph does not force falling back
     * to replacing the entire line.
     */
    private fun sanitizeForFont(
        text: String,
        font: PDFont,
    ): String =
        text
            .map { ch ->
                try {
                    font.encode(ch.toString())
                    ch
                } catch (e: Exception) {
                    // PDFBox's Standard-14 Type1 fonts throw IllegalArgumentException for an
                    // unencodable code point (its own `encode` signature declares only the
                    // checked `IOException`, kept here too in case that ever changes) -- caught
                    // broadly since this is a best-effort rendering fallback, not logic whose
                    // correctness depends on the exact exception type.
                    '?'
                }
            }.joinToString("")

    private fun textWidth(
        text: String,
        font: PDFont,
        size: Float,
    ): Float = font.getStringWidth(text) / 1000f * size

    /** Greedy word-wrap -- splits [text] on spaces and packs words onto lines up to [maxWidth]. */
    private fun wrapLines(
        text: String,
        font: PDFont,
        size: Float,
        maxWidth: Float,
    ): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (textWidth(text = candidate, font = font, size = size) > maxWidth && current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
