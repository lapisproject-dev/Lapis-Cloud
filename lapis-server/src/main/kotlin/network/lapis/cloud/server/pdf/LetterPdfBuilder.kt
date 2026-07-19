package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDate
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
        writeLine(sanitizeForFont(orgName, boldFont), boldFont, SMALL_FONT_SIZE)
        orgAddressLines.forEach { writeLine(sanitizeForFont(it, regularFont), regularFont, SMALL_FONT_SIZE) }
        cursorY -= LINE_HEIGHT
    }

    /** Recipient block, body font, below the letterhead. */
    fun recipientAddress(lines: List<String>) {
        lines.forEach { writeLine(sanitizeForFont(it, regularFont), regularFont, BODY_FONT_SIZE) }
        cursorY -= LINE_HEIGHT
    }

    /** e.g. "Braunschweig, 19.07.2026", right-aligned. */
    fun dateLine(
        place: String,
        date: LocalDate,
    ) {
        val text = sanitizeForFont("$place, ${formatGermanDate(date)}", regularFont)
        val width = textWidth(text, regularFont, BODY_FONT_SIZE)
        writeLineAt(text, regularFont, BODY_FONT_SIZE, PAGE_WIDTH - MARGIN_RIGHT - width)
        cursorY -= LINE_HEIGHT
    }

    /** Bold heading, e.g. the letter/document title. */
    fun heading(text: String) {
        ensureSpace(LINE_HEIGHT * 2)
        writeLine(sanitizeForFont(text, boldFont), boldFont, HEADING_FONT_SIZE)
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
                wrapLines(sanitizeForFont(para, regularFont), regularFont, BODY_FONT_SIZE, CONTENT_WIDTH).forEach { line ->
                    ensureSpace(LINE_HEIGHT)
                    writeLine(line, regularFont, BODY_FONT_SIZE)
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
        writeLine(sanitizeForFont(label, regularFont), regularFont, SMALL_FONT_SIZE)
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
    ) = writeLineAt(text, font, size, MARGIN_LEFT)

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
            if (textWidth(candidate, font, size) > maxWidth && current.isNotEmpty()) {
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
