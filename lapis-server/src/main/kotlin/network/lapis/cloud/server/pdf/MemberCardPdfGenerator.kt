package network.lapis.cloud.server.pdf

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.events.QrCodeMatrix
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

private val logger = KotlinLogging.logger {}

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the printable membership card, exactly ONE page in
 * ISO/IEC 7810 ID-1 (85,6 x 54 mm = 242,65 x 153,07 pt), landscape. No A4 variant, no crop marks,
 * no back side: the card is meant to live in a phone's wallet or, printed, in a wallet slot -- a
 * layout decision of the design-team session that produced this wave's specification, not an
 * implementation shortcut.
 *
 * **Deliberately its own builder ([MemberCardPdfBuilder]), NOT [LetterPdfBuilder].** That class is
 * a top-down, A4, flowing-text letter layout with page-break logic; a credit-card-sized card has
 * a fixed two-column grid and must never paginate. Only the QR rasterization idea is carried over
 * (the run-length `addRect`/`fill` loop, see [MemberCardPdfBuilder.qrBlock]) -- [QrCodeMatrix]
 * itself is reused unchanged, and `LetterPdfBuilder` is not touched by this wave at all.
 *
 * **Font: an embedded Unicode TTF, not a Standard-14 Type1 font.** [LetterPdfBuilder] resolves
 * unencodable characters by substituting `?` ([LetterPdfBuilder]'s own `sanitizeForFont`). On a
 * membership CARD that behavior is a correctness defect, not a cosmetic one: this product's user
 * base includes Georgian and Cyrillic member names, and a card that reads `????? ?????` where the
 * member's name belongs is not a membership card. DejaVu Sans (Bitstream Vera / public-domain
 * derivative license, permissive -- the same hard constraint that picked PDFBox over openhtmltopdf,
 * see [LetterPdfBuilder] KDoc) is embedded as a subset via [PDType0Font.load], covering Latin,
 * Latin Extended, Cyrillic and Georgian Mkhedruli.
 *
 * **Fallback posture -- generation must never fail.** Two escalation stages, both logged:
 * 1. A character the embedded font cannot encode (e.g. Georgian MTAVRULI, U+1C90..U+1CBF, which
 *    DejaVu 2.37 does not carry, or an emoji) is replaced -- per character -- by U+FFFD. The rest
 *    of the name still renders in its own script.
 * 2. If the font resource itself cannot be loaded at all (a broken/stripped jar), the whole card
 *    falls back to Helvetica with `?` substitution, i.e. exactly [LetterPdfBuilder]'s behavior.
 *    Degraded, but a card is still produced.
 */
internal object MemberCardPdfGenerator {
    /** ISO/IEC 7810 ID-1: 85,6 mm x 54 mm at 72 pt/inch. Landscape -- width is the long edge. */
    const val CARD_WIDTH_PT: Float = 242.65f
    const val CARD_HEIGHT_PT: Float = 153.07f

    /**
     * A FRESH [PDRectangle] per call, never a shared constant. A `PDRectangle` is a thin wrapper
     * around a `COSArray`, and handing the same instance to pages of different [PDDocument]s would
     * splice one COS object into several documents' object trees -- the kind of aliasing that
     * produces corrupt output only under concurrent generation, i.e. exactly when it is hardest to
     * diagnose. Two floats and an allocation per card are not worth that risk.
     */
    fun cardSize(): PDRectangle = PDRectangle(CARD_WIDTH_PT, CARD_HEIGHT_PT)

    /**
     * One card's data -- assembled by the route handler, never a `ResultRow`. Deliberately flat and
     * already-formatted-free (formatting happens here, so the tests can pin it).
     */
    data class Card(
        val brandTitle: String,
        val displayName: String,
        val memberNumber: String,
        val joinedAt: LocalDate,
        val statusLabel: String,
        val membershipTierName: String?,
        val qr: QrCodeMatrix,
    )

    fun generate(card: Card): ByteArray {
        val builder = MemberCardPdfBuilder()
        return try {
            builder.render(card)
        } finally {
            builder.close()
        }
    }

    /** `Max Mustermann, Mitglied seit 01.02.2026` -- the one German date format this whole codebase uses (see [formatGermanDate]). */
    fun joinedLine(joinedAt: LocalDate): String = "Mitglied seit ${formatGermanDate(joinedAt)}"
}

/**
 * The card's fixed two-column grid -- left column text, right column QR. Nothing here flows or
 * paginates; every element's position is computed from the page box and [MARGIN_PT].
 *
 * Not a general-purpose builder despite the name: it renders exactly one shape, the one
 * [MemberCardPdfGenerator] specifies. The class exists (rather than one long function) so the
 * content stream / document lifecycle has a single owner and [close] is exception-safe.
 */
internal class MemberCardPdfBuilder {
    private val document = PDDocument()
    private val page = PDPage(MemberCardPdfGenerator.cardSize())
    private var closed = false

    /**
     * `null` only if the embedded font resource could not be loaded -- see
     * [MemberCardPdfGenerator] KDoc "Fallback posture", stage 2. Loaded eagerly in `init` because
     * both the regular and the bold face must come from the same family: a card with a DejaVu body
     * and a Helvetica name would be worse than a consistently degraded one.
     */
    private val unicodeFonts: Pair<PDFont, PDFont>? = loadUnicodeFonts(document)

    private val regularFont: PDFont = unicodeFonts?.first ?: PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val boldFont: PDFont = unicodeFonts?.second ?: PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    init {
        document.addPage(page)
    }

    fun render(card: MemberCardPdfGenerator.Card): ByteArray {
        PDPageContentStream(document, page).use { stream ->
            qrBlock(stream = stream, matrix = card.qr)
            textColumn(stream = stream, card = card)
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        return out.toByteArray()
    }

    fun close() {
        if (closed) return
        closed = true
        document.close()
    }

    /**
     * Draws [matrix] into the right-hand column as vector rectangles, one PDFBox `addRect` per
     * [QrCodeMatrix.horizontalRuns] run -- the same rasterization idea (and the same deliberate
     * avoidance of `PDImageXObject`/AWT, keeping this path headless-safe) that
     * `LetterPdfBuilder.qrCode` uses, re-expressed for a fixed right-aligned, vertically centered
     * block instead of a centered, cursor-driven one.
     *
     * **[QR_BLOCK_PT] is the WHOLE block including the 4-module quiet zone**, not the module grid
     * alone. Stating it the other way round would make the card's right column's total width
     * depend on the QR version (which depends on the URL length, which depends on the operator's
     * `publicBaseUrl`) -- the text column's width would then silently shrink on a deployment with
     * a long hostname. Fixing the outer block and letting the module size fall out of it keeps the
     * grid stable for every deployment.
     */
    private fun qrBlock(
        stream: PDPageContentStream,
        matrix: QrCodeMatrix,
    ) {
        val moduleSize = QR_BLOCK_PT / (matrix.size + 2 * QR_QUIET_MODULES)
        val quiet = moduleSize * QR_QUIET_MODULES
        val blockLeft = MemberCardPdfGenerator.CARD_WIDTH_PT - MARGIN_PT - QR_BLOCK_PT
        val blockBottom = (MemberCardPdfGenerator.CARD_HEIGHT_PT - QR_BLOCK_PT) / 2f
        val gridLeft = blockLeft + quiet
        val gridTop = blockBottom + QR_BLOCK_PT - quiet
        for (run in matrix.horizontalRuns()) {
            val x = gridLeft + run.x * moduleSize
            // PDF's y-axis grows upward; matrix row 0 is the TOP row.
            val y = gridTop - (run.y + 1) * moduleSize
            stream.addRect(x, y, run.length * moduleSize, moduleSize)
        }
        stream.fill()
    }

    /**
     * The left column, top to bottom: brand title, member name, "Mitglied seit ...", status word,
     * optional membership tier, and -- anchored to the bottom edge rather than to the flow above it
     * -- the member number.
     *
     * The member number is bottom-anchored on purpose: it is the one field a human reads aloud on
     * the phone, and it must sit in the same place on every card regardless of whether that member
     * has a membership tier. A flowing layout would move it by one line for tier-less members.
     */
    private fun textColumn(
        stream: PDPageContentStream,
        card: MemberCardPdfGenerator.Card,
    ) {
        val columnWidth = MemberCardPdfGenerator.CARD_WIDTH_PT - MARGIN_PT - QR_BLOCK_PT - COLUMN_GUTTER_PT - MARGIN_PT
        var y = MemberCardPdfGenerator.CARD_HEIGHT_PT - MARGIN_PT - BRAND_FONT_SIZE

        drawFitted(
            stream = stream,
            text = card.brandTitle,
            font = regularFont,
            size = BRAND_FONT_SIZE,
            x = MARGIN_PT,
            baselineY = y,
            maxWidth = columnWidth,
        )
        y -= BRAND_FONT_SIZE + 8f

        // Name: never wrapped -- a wrapped two-line name on a 54-mm-tall card eats the whole grid,
        // a smaller name does not. Stepped down through [NAME_FONT_SIZES] until it fits, then
        // truncated with an ellipsis as the last resort (see [drawFitted]).
        val nameSize =
            NAME_FONT_SIZES.firstOrNull { textWidth(text = card.displayName, font = boldFont, size = it) <= columnWidth }
                ?: NAME_FONT_SIZES.last()
        drawFitted(
            stream = stream,
            text = card.displayName,
            font = boldFont,
            size = nameSize,
            x = MARGIN_PT,
            baselineY = y,
            maxWidth = columnWidth,
        )
        y -= nameSize + 6f

        drawFitted(
            stream = stream,
            text = MemberCardPdfGenerator.joinedLine(card.joinedAt),
            font = regularFont,
            size = META_FONT_SIZE,
            x = MARGIN_PT,
            baselineY = y,
            maxWidth = columnWidth,
        )
        y -= META_FONT_SIZE + 5f

        drawFitted(
            stream = stream,
            text = card.statusLabel,
            font = boldFont,
            size = STATUS_FONT_SIZE,
            x = MARGIN_PT,
            baselineY = y,
            maxWidth = columnWidth,
        )
        y -= STATUS_FONT_SIZE + 5f

        card.membershipTierName?.let { tier ->
            drawFitted(
                stream = stream,
                text = tier,
                font = regularFont,
                size = META_FONT_SIZE,
                x = MARGIN_PT,
                baselineY = y,
                maxWidth = columnWidth,
            )
        }

        drawFitted(
            stream = stream,
            text = card.memberNumber,
            font = boldFont,
            size = NUMBER_FONT_SIZE,
            x = MARGIN_PT,
            baselineY = MARGIN_PT,
            maxWidth = columnWidth,
            characterSpacing = NUMBER_CHARACTER_SPACING_PT,
        )
    }

    /**
     * Draws [text] at ([x], [baselineY]), sanitized for [font] and -- as a last resort -- truncated
     * with an ellipsis so it never runs under the QR column. Returns silently for blank text.
     *
     * Truncation is deliberately the LAST stage, below the caller's own 14pt -> 12pt step-down: a
     * cut-off name is worse than a small one, so it only happens for input that cannot fit either
     * way (a pathological 120-character display name).
     */
    private fun drawFitted(
        stream: PDPageContentStream,
        text: String,
        font: PDFont,
        size: Float,
        x: Float,
        baselineY: Float,
        maxWidth: Float,
        characterSpacing: Float = 0f,
    ) {
        val sanitized = sanitize(text = text, font = font)
        if (sanitized.isBlank()) return
        if (sanitized != text) {
            // Logged HERE, once per drawn field, rather than inside [sanitize] -- that function is
            // also called for pure measurement (the name's step-down ladder measures the same
            // string up to three times), and a measurement is not an event worth a log line. No
            // member name, no member number in the message: this is a rendering diagnostic, not an
            // audit record, and PII must not leak into logs (repo-wide logging rule).
            logger.warn {
                "MemberCardPdfBuilder: ${text.length - sanitized.length + 1} or more code points were not encodable by the card font"
            }
        }
        val fitted =
            truncateToWidth(
                text = sanitized,
                font = font,
                size = size,
                maxWidth = maxWidth,
                characterSpacing = characterSpacing,
            )
        stream.beginText()
        stream.setFont(font, size)
        if (characterSpacing != 0f) stream.setCharacterSpacing(characterSpacing)
        stream.newLineAtOffset(x, baselineY)
        stream.showText(fitted)
        stream.endText()
        if (characterSpacing != 0f) stream.setCharacterSpacing(0f)
    }

    private fun truncateToWidth(
        text: String,
        font: PDFont,
        size: Float,
        maxWidth: Float,
        characterSpacing: Float,
    ): String {
        fun width(s: String) = textWidth(text = s, font = font, size = size) + characterSpacing * (s.length - 1).coerceAtLeast(0)
        if (width(text) <= maxWidth) return text
        var candidate = text
        while (candidate.isNotEmpty() && width("$candidate…") > maxWidth) {
            candidate = candidate.dropLast(1)
        }
        return if (candidate.isEmpty()) "" else "$candidate…"
    }

    private fun textWidth(
        text: String,
        font: PDFont,
        size: Float,
    ): Float = font.getStringWidth(sanitize(text = text, font = font)) / 1000f * size

    /**
     * Per-character encodability probe, mirroring `LetterPdfBuilder.sanitizeForFont`'s idiom but
     * with U+FFFD instead of `?` as the replacement when the EMBEDDED font is in use: `?` reads as
     * an intentional character (and as the letter substitution this wave exists to eliminate),
     * whereas U+FFFD is unambiguously "this glyph is missing". When the Helvetica fallback is
     * active (font resource unavailable), `?` is used instead -- DejaVu's U+FFFD glyph does not
     * exist in WinAnsiEncoding, so U+FFFD would itself be unencodable there.
     */
    private fun sanitize(
        text: String,
        font: PDFont,
    ): String {
        val replacement = if (unicodeFonts != null) "�" else "?"
        val builder = StringBuilder(text.length)
        // Iterate by code point, not by Char: a surrogate pair (emoji) must be probed and replaced
        // as ONE unit -- probing its halves individually would emit two replacement characters for
        // one missing glyph, and `font.encode` on a lone surrogate is not a meaningful question.
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val chunk = text.substring(index, index + charCount)
            val encodable =
                try {
                    font.encode(chunk)
                    true
                } catch (e: Exception) {
                    // PDFBox throws IllegalArgumentException for an unencodable code point and
                    // declares only the checked IOException -- caught broadly, exactly as
                    // LetterPdfBuilder does, because this is a best-effort rendering fallback.
                    false
                }
            builder.append(if (encodable) chunk else replacement)
            index += charCount
        }
        return builder.toString()
    }

    private companion object {
        /** 12 pt on all four sides -- the card's only margin value. */
        const val MARGIN_PT = 12f

        /** Whole right-hand QR block INCLUDING its quiet zone -- see [qrBlock] KDoc. */
        const val QR_BLOCK_PT = 88f

        /** Per ISO/IEC 18004 the quiet zone is 4 modules on every side. */
        const val QR_QUIET_MODULES = 4

        /** Horizontal gap between the text column and the QR block. */
        const val COLUMN_GUTTER_PT = 8f

        const val BRAND_FONT_SIZE = 8f

        /**
         * The name's step-down ladder, largest first.
         *
         * The specification named exactly two steps, 14pt and 12pt. Measurement against the actual
         * grid forced a third: the text column is only
         * `242,65 - 2x12 (margins) - 88 (QR block) - 8 (gutter) = 122,65 pt` wide, and "Erika
         * Mustermann" -- sixteen characters, an unremarkable German name -- already measures wider
         * than that at 12pt in DejaVu Sans Bold, which is a comparatively wide face. Stopping at
         * 12pt would have meant truncating ordinary members' names on their own membership card;
         * the alternatives (wrapping, or shrinking the QR) were both excluded by the same
         * specification. 10pt is the floor: below that the name would no longer dominate the card.
         */
        val NAME_FONT_SIZES = listOf(14f, 12f, 10f)
        const val META_FONT_SIZE = 7f
        const val STATUS_FONT_SIZE = 8f
        const val NUMBER_FONT_SIZE = 10f

        /** The member number is letterspaced -- it is read out digit by digit, not as a word. */
        const val NUMBER_CHARACTER_SPACING_PT = 0.6f

        const val REGULAR_FONT_RESOURCE = "/fonts/DejaVuSans.ttf"
        const val BOLD_FONT_RESOURCE = "/fonts/DejaVuSans-Bold.ttf"

        /**
         * Loads the embedded regular+bold pair, or `null` if either resource is missing/unreadable
         * -- see [MemberCardPdfGenerator] KDoc "Fallback posture", stage 2. Both faces are loaded
         * together and fail together on purpose: a mixed-family card is worse than a uniformly
         * degraded one.
         */
        fun loadUnicodeFonts(document: PDDocument): Pair<PDFont, PDFont>? =
            try {
                val regular =
                    MemberCardPdfBuilder::class.java.getResourceAsStream(REGULAR_FONT_RESOURCE)
                        ?: error("Missing card font resource $REGULAR_FONT_RESOURCE")
                val bold =
                    MemberCardPdfBuilder::class.java.getResourceAsStream(BOLD_FONT_RESOURCE)
                        ?: error("Missing card font resource $BOLD_FONT_RESOURCE")
                regular.use { r ->
                    bold.use { b ->
                        PDType0Font.load(document, r, true) to PDType0Font.load(document, b, true)
                    }
                }
            } catch (e: Exception) {
                logger.error(
                    e,
                ) { "MemberCardPdfBuilder: embedded Unicode font unavailable, falling back to Helvetica (non-Latin names will degrade)" }
                null
            }
    }
}
