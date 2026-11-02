package network.lapis.cloud.server.pdf

import org.apache.pdfbox.pdmodel.font.PDFont

/**
 * Text clean-up shared by the PDF builders, which use the Standard-14 fonts with `WinAnsiEncoding` (no embedded Unicode font).
 * Replaces every character the font cannot encode with `?` and every control character with a space, so arbitrary member- or
 * organisation-supplied text can be measured and drawn without PDFBox throwing `IllegalArgumentException`.
 */
internal object PdfFontText {
    fun sanitize(
        text: String,
        font: PDFont,
    ): String =
        text
            .replace("\t", "  ")
            .map { ch ->
                if (ch.isISOControl()) {
                    ' '
                } else {
                    try {
                        font.encode(ch.toString())
                        ch
                    } catch (e: Exception) {
                        // Standard-14 Type1 fonts throw IllegalArgumentException for an unencodable code point; best-effort fallback.
                        '?'
                    }
                }
            }.joinToString("")

    fun width(
        text: String,
        font: PDFont,
        size: Float,
    ): Float = font.getStringWidth(text) / 1000f * size
}
