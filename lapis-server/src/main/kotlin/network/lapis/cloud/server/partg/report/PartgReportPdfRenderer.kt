package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.pdf.PdfFontText
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgReportLimits
import network.lapis.cloud.shared.domain.PartgSection
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

/**
 * V1.9.256 -- the report draft as a landscape A4 PDF (PDFBox, Standard-14 Helvetica, WinAnsi). Own table builder; [network.lapis.cloud.server.pdf.LetterPdfBuilder]
 * is portrait and has no tables.
 *
 * Two passes: first the content, then on every page the watermark (BEHIND the content, `PREPEND`, alpha 0.12), the header line and the footer
 * with "Seite x von y". Bounded work: [maxPages], [maxBytes] and a time budget throw [PdfLimitExceeded]. Deterministic: no creation date and an
 * explicit trailer `/ID` derived from the content, so the same document gives the same bytes.
 */
internal class PartgReportPdfRenderer(
    private val maxPages: Int = PartgReportLimits.MAX_PDF_PAGES,
    private val maxBytes: Int = PartgReportLimits.MAX_PDF_BYTES,
    private val deadline: Instant? = Instant.now().plusSeconds(PartgReportLimits.PDF_TIME_LIMIT_SECONDS),
) {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private class Col(
        val title: String,
        val width: Float,
        val right: Boolean,
        val wrap: Boolean = false,
    )

    private class Row(
        val cells: List<String>,
        val bold: Boolean = false,
    )

    private inner class Canvas(
        val pdf: PDDocument,
    ) {
        var cs: PDPageContentStream? = null
        var y = 0f

        fun check() {
            if (deadline != null && Instant.now().isAfter(deadline)) throw PdfLimitExceeded("time limit")
        }

        fun newPage() {
            check()
            cs?.close()
            cs = null
            if (pdf.numberOfPages >= maxPages) throw PdfLimitExceeded("page limit")
            val page = PDPage(PAGE)
            pdf.addPage(page)
            cs = PDPageContentStream(pdf, page)
            y = TOP
        }

        /** Starts a new page unless [height] still fits; returns `true` when a page was started. */
        fun need(height: Float): Boolean {
            if (cs == null || y - height < BOTTOM) {
                newPage()
                return true
            }
            return false
        }

        fun text(
            value: String,
            x: Float,
            baseline: Float,
            font: PDFont,
            size: Float,
        ) {
            check()
            val t = PdfFontText.sanitize(text = value, font = font)
            val stream = checkNotNull(cs)
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, baseline)
            stream.showText(t)
            stream.endText()
        }

        fun line(
            x1: Float,
            x2: Float,
            at: Float,
        ) {
            val stream = checkNotNull(cs)
            stream.setLineWidth(0.4f)
            stream.moveTo(x1, at)
            stream.lineTo(x2, at)
            stream.stroke()
        }

        fun close() {
            cs?.close()
            cs = null
        }
    }

    fun render(document: PartgReportDocument): ByteArray {
        PDDocument().use { pdf ->
            val c = Canvas(pdf)
            try {
                completenessSection(c = c, doc = document)
                summarySection(c = c, doc = document)
                matrixSection(c = c, doc = document)
                expenseSection(c = c, doc = document)
                assetSection(c = c, doc = document)
            } finally {
                c.close()
            }
            decorate(pdf = pdf, doc = document)
            val info = PDDocumentInformation()
            info.title = PdfFontText.sanitize(text = "${PartgReportTexts.TITLE} ${document.year} (Entwurf)", font = regular)
            info.producer = "Lapis Cloud"
            pdf.documentInformation = info
            val digest = MessageDigest.getInstance("SHA-256").digest(document.toString().toByteArray(Charsets.UTF_8)).copyOf(ID_BYTES)
            val id = COSArray()
            id.add(COSString(digest))
            id.add(COSString(digest))
            pdf.document.setDocumentID(id)
            val out = ByteArrayOutputStream()
            pdf.save(out)
            if (out.size() > maxBytes) throw PdfLimitExceeded("size limit")
            return out.toByteArray()
        }
    }

    // ---------------------------------------------------------------- text helpers

    private fun width(
        text: String,
        font: PDFont,
        size: Float,
    ): Float = PdfFontText.width(text = PdfFontText.sanitize(text = text, font = font), font = font, size = size)

    /** The largest of 8, 7, 6 pt at which [text] fits; below that the size shrinks until it fits (an amount is never cut off). */
    private fun fit(
        text: String,
        font: PDFont,
        maxWidth: Float,
    ): Float {
        for (size in listOf(8f, 7f, 6f)) if (width(text = text, font = font, size = size) <= maxWidth) return size
        val at6 = width(text = text, font = font, size = 6f)
        return if (at6 <= 0f) 6f else maxOf(1f, 6f * maxWidth / at6)
    }

    private fun truncate(
        text: String,
        font: PDFont,
        size: Float,
        maxWidth: Float,
    ): String {
        val clean = PdfFontText.sanitize(text = text, font = font)
        if (width(text = clean, font = font, size = size) <= maxWidth) return clean
        var end = clean.length
        while (end > 0 && width(text = clean.substring(0, end) + "…", font = font, size = size) > maxWidth) end -= 1
        return clean.substring(0, end) + "…"
    }

    private fun wrap(
        text: String,
        font: PDFont,
        size: Float,
        maxWidth: Float,
    ): List<String> {
        val clean = PdfFontText.sanitize(text = text, font = font).trim()
        val lines = mutableListOf<String>()
        var current = ""
        for (raw in clean.split(' ').filter { it.isNotEmpty() }) {
            var word = raw
            while (width(text = word, font = font, size = size) > maxWidth && word.length > 1) {
                var cut = word.length - 1
                while (cut > 1 && width(text = word.substring(0, cut), font = font, size = size) > maxWidth) cut -= 1
                if (current.isNotEmpty()) {
                    lines += current
                    current = ""
                }
                lines += word.substring(0, cut)
                word = word.substring(cut)
            }
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (width(text = candidate, font = font, size = size) > maxWidth && current.isNotEmpty()) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf("") }
    }

    private fun paragraph(
        c: Canvas,
        text: String,
        font: PDFont = regular,
        size: Float = 8f,
        indent: Float = 0f,
    ) {
        for (line in wrap(text = text, font = font, size = size, maxWidth = CONTENT_WIDTH - indent)) {
            c.need(LEAD)
            c.text(value = line, x = MARGIN + indent, baseline = c.y - size, font = font, size = size)
            c.y -= LEAD
        }
    }

    private fun heading(
        c: Canvas,
        text: String,
        size: Float = 11f,
    ) {
        c.need(size + 10f)
        c.text(
            value = truncate(text = text, font = bold, size = size, maxWidth = CONTENT_WIDTH),
            x = MARGIN,
            baseline = c.y - size,
            font = bold,
            size = size,
        )
        c.y -= size + 6f
    }

    // ---------------------------------------------------------------- tables

    private fun drawHeader(
        c: Canvas,
        title: String?,
        cols: List<Col>,
    ) {
        if (title != null) {
            c.text(
                value = truncate(text = title, font = bold, size = 9f, maxWidth = CONTENT_WIDTH),
                x = MARGIN,
                baseline = c.y - 9f,
                font = bold,
                size = 9f,
            )
            c.y -= 13f
        }
        var x = MARGIN
        for (col in cols) {
            val size = 7.5f
            val t = truncate(text = col.title, font = bold, size = size, maxWidth = col.width - 4f)
            if (col.right) {
                c.text(
                    value = t,
                    x = x + col.width - 2f - width(text = t, font = bold, size = size),
                    baseline = c.y - 8f,
                    font = bold,
                    size = size,
                )
            } else {
                c.text(value = t, x = x + 2f, baseline = c.y - 8f, font = bold, size = size)
            }
            x += col.width
        }
        c.y -= 11f
        c.line(x1 = MARGIN, x2 = MARGIN + cols.sumOf { it.width.toDouble() }.toFloat(), at = c.y)
        c.y -= 2f
    }

    private fun rowLines(
        row: Row,
        cols: List<Col>,
    ): List<List<String>> {
        val font = if (row.bold) bold else regular
        return row.cells.mapIndexed { i, cell ->
            val col = cols[i]
            if (col.wrap) wrap(text = cell, font = font, size = 8f, maxWidth = col.width - 4f).take(MAX_WRAPPED_LINES) else listOf(cell)
        }
    }

    /**
     * Draws a table; the header (and the optional [title]) repeats after a page break, a break only ever falls between rows. With
     * [numericSize] every right-aligned cell uses that size, otherwise each one is fitted to its column.
     */
    private fun table(
        c: Canvas,
        title: String?,
        cols: List<Col>,
        rows: List<Row>,
        numericSize: Float? = null,
    ) {
        if (rows.isEmpty()) return
        val headerHeight = (if (title != null) 13f else 0f) + 13f
        val first = rowLines(row = rows.first(), cols = cols).maxOf { it.size } * LEAD + 3f
        c.need(headerHeight + first)
        drawHeader(c = c, title = title, cols = cols)
        for (row in rows) {
            val lines = rowLines(row = row, cols = cols)
            val height = lines.maxOf { it.size } * LEAD + 3f
            if (c.need(height)) drawHeader(c = c, title = title?.let { "$it (Forts.)" }, cols = cols)
            val font = if (row.bold) bold else regular
            var x = MARGIN
            cols.forEachIndexed { i, col ->
                val cellLines = lines[i]
                cellLines.forEachIndexed { k, text ->
                    val baseline = c.y - 8.5f - k * LEAD
                    if (col.right) {
                        val size = numericSize ?: fit(text = text, font = font, maxWidth = col.width - 4f)
                        c.text(
                            value = text,
                            x = x + col.width - 2f - width(text = text, font = font, size = size),
                            baseline = baseline,
                            font = font,
                            size = size,
                        )
                    } else {
                        c.text(
                            value = if (col.wrap) text else truncate(text = text, font = font, size = 8f, maxWidth = col.width - 4f),
                            x =
                                x + 2f,
                            baseline = baseline,
                            font = font,
                            size = 8f,
                        )
                    }
                }
                x += col.width
            }
            c.y -= height
        }
        c.y -= 6f
    }

    // ---------------------------------------------------------------- sections

    private fun completenessSection(
        c: Canvas,
        doc: PartgReportDocument,
    ) {
        c.newPage()
        c.text(
            value = "${PartgReportTexts.TITLE} ${doc.year} – ${PartgReportTexts.DRAFT_MARK}",
            x = MARGIN,
            baseline = c.y - 15f,
            font = bold,
            size = 15f,
        )
        c.y -= 22f
        c.text(
            value = truncate(text = doc.organizationName.take(MAX_NAME_CHARS), font = regular, size = 10f, maxWidth = CONTENT_WIDTH),
            x = MARGIN,
            baseline =
                c.y - 10f,
            font = regular,
            size = 10f,
        )
        c.y -= 14f
        val scopeText =
            if (doc.consolidated != null) {
                "Umfang: ${PartgReportTexts.WHOLE_PARTY} (${doc.units.size} Einheiten)"
            } else {
                "Umfang: ${doc.units.single().label}"
            }
        c.text(
            value = truncate(text = scopeText, font = regular, size = 8f, maxWidth = CONTENT_WIDTH),
            x = MARGIN,
            baseline = c.y - 8f,
            font = regular,
            size = 8f,
        )
        c.y -= 14f
        paragraph(c = c, text = PartgReportTexts.DRAFT_NOTICE)
        c.y -= 6f
        heading(c = c, text = PartgReportTexts.COMPLETENESS)
        val leafCount = doc.completeness.leafCount
        val units = doc.completeness.units
        val complete = units.filter { it.missingCurrent.isEmpty() && it.enteredPrior == leafCount }
        val open = units.filter { it !in complete }
        for (unit in open) {
            val parts = mutableListOf<String>()
            if (unit.missingCurrent.isNotEmpty()) parts += "${unit.missingCurrent.size} von $leafCount Positionen nicht erfasst"
            if (unit.enteredPrior == 0) {
                parts += "Vorjahr nicht erfasst"
            } else if (unit.enteredPrior < leafCount) {
                parts += "Vorjahr: ${leafCount - unit.enteredPrior} von $leafCount Positionen nicht erfasst"
            }
            paragraph(c = c, text = PartgReportAssembler.unitLabel(unit.unit) + ": " + parts.joinToString(" · "))
        }
        if (complete.isNotEmpty()) {
            paragraph(c = c, text = if (complete.size == 1) "1 Einheit vollständig" else "${complete.size} Einheiten vollständig")
        }
        if (open.isEmpty()) paragraph(c = c, text = PartgReportTexts.ALL_COMPLETE)
    }

    private val standardCols =
        listOf(
            Col(title = PartgReportTexts.COL_NUMBER, width = 55f, right = false),
            Col(title = PartgReportTexts.COL_POSITION, width = 330f, right = false, wrap = true),
            Col(title = PartgReportTexts.COL_CURRENT, width = 130f, right = true),
            Col(title = PartgReportTexts.COL_PERCENT, width = 45f, right = true),
            Col(title = PartgReportTexts.COL_PRIOR, width = 130f, right = true),
            Col(title = PartgReportTexts.COL_PERCENT, width = 45f, right = true),
        )

    private val balanceCols =
        listOf(
            Col(title = PartgReportTexts.COL_NUMBER, width = 55f, right = false),
            Col(title = PartgReportTexts.COL_POSITION, width = 380f, right = false, wrap = true),
            Col(title = PartgReportTexts.COL_CURRENT, width = 150f, right = true),
            Col(title = PartgReportTexts.COL_PRIOR, width = 150f, right = true),
        )

    private fun lineRow(line: ReportLine) =
        Row(
            cells =
                listOf(
                    line.numberText,
                    line.label,
                    PartgReportNumberFormat.cell(line.current),
                    PartgReportNumberFormat.percent(line.currentPercent),
                    PartgReportNumberFormat.cell(line.prior),
                    PartgReportNumberFormat.percent(line.priorPercent),
                ),
            bold = line.bold,
        )

    private fun sectionRow(title: String) = Row(cells = listOf("", title, "", "", "", ""), bold = true)

    private fun summarySection(
        c: Canvas,
        doc: PartgReportDocument,
    ) {
        c.newPage()
        heading(c = c, text = PartgReportTexts.SUMMARY)
        val page = doc.summaryPage
        val rows =
            listOf(sectionRow("Einnahmen")) + PartgReportLayout.summaryIncome(page).map(::lineRow) +
                sectionRow("Ausgaben") + PartgReportLayout.summaryExpenses(page).map(::lineRow)
        table(c = c, title = page.label, cols = standardCols, rows = rows)
        paragraph(c = c, text = PartgReportTexts.SUBSIDY_NOTE, size = 7.5f)
        PartgReportLayout
            .missingFootnote(
                document = doc,
                relevant =
                    PartgReportLayout.leafCodes(
                        PartgSection.INCOME,
                        PartgSection.EXPENSE,
                        PartgSection.ASSET,
                        PartgSection.LIABILITY,
                    ),
            )?.let { paragraph(c = c, text = it, size = 7f) }
        if (doc.levels.isNotEmpty()) {
            c.y -= 8f
            val cols =
                listOf(
                    Col(title = PartgReportTexts.COL_UNIT, width = 210f, right = false),
                    Col(title = "Gesamteinnahmen", width = 130f, right = true),
                    Col(title = "Gesamtausgaben", width = 130f, right = true),
                    Col(title = "Überschuss/Defizit", width = 130f, right = true),
                    Col(title = "Reinvermögen", width = 130f, right = true),
                )
            val levelRows =
                doc.levels.map { level ->
                    Row(
                        cells =
                            listOf(
                                level.label,
                            ) + PartgReportLayout.LEVEL_CODES.map { PartgReportNumberFormat.cell(level.current.getValue(it)) },
                    )
                }
            table(
                c = c,
                title = "${PartgReportTexts.BY_LEVEL} (Berichtsjahr ${doc.year}, einschließlich innerparteilicher Zuschüsse)",
                cols = cols,
                rows = levelRows,
            )
        }
    }

    private fun matrixSection(
        c: Canvas,
        doc: PartgReportDocument,
    ) {
        c.newPage()
        heading(c = c, text = PartgReportTexts.INCOME)
        for (code in PartgReportLayout.MATRIX_CODES) {
            val position =
                checkNotNull(
                    network.lapis.cloud.shared.domain.PartgPositions
                        .find(code),
                )
            paragraph(c = c, text = "${PartgReportLayout.numberText(code)}  ${position.labelDe}", size = 7f)
        }
        c.y -= 6f
        val pages = doc.pages
        for (period in listOf(true, false)) {
            val year = if (period) doc.year else doc.priorYear

            fun cellOf(
                page: UnitPage,
                code: String,
            ): PartgCell = (if (period) page.current else page.prior).getValue(code)

            val texts = pages.flatMap { page -> PartgReportLayout.MATRIX_CODES.map { PartgReportNumberFormat.cellShort(cellOf(page, it)) } }
            val perBlock12 = (CONTENT_WIDTH - UNIT_COL_WIDTH) / PartgReportLayout.MATRIX_CODES.size
            val fits12 = texts.all { width(text = it, font = regular, size = 6f) <= perBlock12 - 4f }
            val blocks =
                if (fits12) {
                    listOf(PartgReportLayout.MATRIX_CODES)
                } else {
                    PartgReportLayout.MATRIX_CODES.chunked(PartgReportLayout.MATRIX_CODES.size / 2)
                }
            for (block in blocks) {
                val colWidth = (CONTENT_WIDTH - UNIT_COL_WIDTH) / block.size
                val size =
                    listOf(8f, 7f, 6f).firstOrNull { s ->
                        pages.all { page ->
                            block.all {
                                width(text = PartgReportNumberFormat.cellShort(cellOf(page, it)), font = regular, size = s) <=
                                    colWidth - 4f
                            }
                        }
                    } ?: 6f
                val cols =
                    listOf(Col(title = PartgReportTexts.COL_UNIT, width = UNIT_COL_WIDTH, right = false)) +
                        block.map { Col(title = PartgReportLayout.matrixTitle(it), width = colWidth, right = true) }
                val rows =
                    pages.map { page ->
                        Row(
                            cells = listOf(page.label) + block.map { PartgReportNumberFormat.cellShort(cellOf(page, it)) },
                            bold = page === doc.consolidated,
                        )
                    }
                val suffix =
                    if (blocks.size >
                        1
                    ) {
                        " – ${PartgReportLayout.matrixTitle(block.first())} bis ${PartgReportLayout.matrixTitle(block.last())}"
                    } else {
                        ""
                    }
                val label = if (period) "Berichtsjahr $year" else "Vorjahr $year"
                table(c = c, title = label + suffix, cols = cols, rows = rows, numericSize = size)
            }
        }
        paragraph(c = c, text = PartgReportTexts.MATRIX_STAR, size = 7f)
        PartgReportLayout.missingFootnote(document = doc, relevant = PartgReportLayout.leafCodes(PartgSection.INCOME))?.let {
            paragraph(c = c, text = it, size = 7f)
        }
    }

    private fun unitFootnote(
        doc: PartgReportDocument,
        index: Int,
        relevant: Set<String>,
    ): String? {
        if (index >= doc.units.size) return PartgReportLayout.missingFootnote(document = doc, relevant = relevant)
        val codes =
            doc.completeness.units[index]
                .missingCurrent
                .filter { it in relevant }
        if (codes.isEmpty()) return null
        return PartgReportTexts.INCOMPLETE_SHORT + ": fehlend: " + codes.joinToString(", ") { PartgReportLayout.numberText(it) }
    }

    private fun expenseSection(
        c: Canvas,
        doc: PartgReportDocument,
    ) {
        c.newPage()
        heading(c = c, text = PartgReportTexts.EXPENSES)
        val relevant = PartgReportLayout.leafCodes(PartgSection.EXPENSE)
        doc.pages.forEachIndexed { index, page ->
            table(c = c, title = page.label, cols = standardCols, rows = PartgReportLayout.expenseLines(page).map(::lineRow))
            unitFootnote(doc = doc, index = index, relevant = relevant)?.let { paragraph(c = c, text = it, size = 7f) }
        }
    }

    private fun assetSection(
        c: Canvas,
        doc: PartgReportDocument,
    ) {
        c.newPage()
        heading(c = c, text = PartgReportTexts.ASSETS)
        val relevant = PartgReportLayout.leafCodes(PartgSection.ASSET, PartgSection.LIABILITY)
        doc.pages.forEachIndexed { index, page ->
            val rows =
                PartgReportLayout.assetLines(page).map { line ->
                    Row(
                        cells =
                            listOf(
                                line.numberText,
                                line.label,
                                PartgReportNumberFormat.cell(line.current),
                                PartgReportNumberFormat.cell(line.prior),
                            ),
                        bold = line.bold,
                    )
                }
            table(c = c, title = page.label, cols = balanceCols, rows = rows)
            unitFootnote(doc = doc, index = index, relevant = relevant)?.let { paragraph(c = c, text = it, size = 7f) }
        }
    }

    // ---------------------------------------------------------------- second pass

    private fun decorate(
        pdf: PDDocument,
        doc: PartgReportDocument,
    ) {
        val total = pdf.numberOfPages
        val header =
            PdfFontText.sanitize(
                text = "${doc.organizationName.take(
                    MAX_NAME_CHARS,
                )} · ${PartgReportTexts.TITLE} ${doc.year} · ${PartgReportTexts.HEADER_SUFFIX}",
                font = regular,
            )
        for (i in 0 until total) {
            val page = pdf.getPage(i)
            // the watermark goes BEHIND the content
            PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.PREPEND, true, true).use { cs ->
                // q ... Q: the grey colour and the alpha must not leak into the content that follows
                cs.saveGraphicsState()
                val gs = PDExtendedGraphicsState()
                gs.nonStrokingAlphaConstant = WATERMARK_ALPHA
                cs.setGraphicsStateParameters(gs)
                cs.setNonStrokingColor(0.5f)
                val size = 60f
                val w = width(text = PartgReportTexts.WATERMARK, font = bold, size = size)
                val angle = Math.toRadians(30.0)
                val cx = PAGE.width / 2f
                val cy = PAGE.height / 2f
                val x = cx - (w / 2f) * Math.cos(angle).toFloat()
                val y = cy - (w / 2f) * Math.sin(angle).toFloat()
                cs.beginText()
                cs.setFont(bold, size)
                cs.setTextMatrix(Matrix.getRotateInstance(angle, x, y))
                cs.showText(PartgReportTexts.WATERMARK)
                cs.endText()
                cs.restoreGraphicsState()
            }
            PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.beginText()
                cs.setFont(regular, 8f)
                cs.newLineAtOffset(MARGIN, PAGE.height - 24f)
                cs.showText(header)
                cs.endText()
                cs.setLineWidth(0.4f)
                cs.moveTo(MARGIN, PAGE.height - 30f)
                cs.lineTo(PAGE.width - MARGIN, PAGE.height - 30f)
                cs.stroke()
                cs.beginText()
                cs.setFont(regular, 6.5f)
                cs.newLineAtOffset(MARGIN, 22f)
                cs.showText(PdfFontText.sanitize(text = PartgReportTexts.LEGEND, font = regular))
                cs.endText()
                val pageText = "Seite ${i + 1} von $total"
                cs.beginText()
                cs.setFont(regular, 7f)
                cs.newLineAtOffset(PAGE.width - MARGIN - width(text = pageText, font = regular, size = 7f), 22f)
                cs.showText(pageText)
                cs.endText()
            }
        }
    }

    private companion object {
        val PAGE = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
        const val MARGIN = 36f
        val CONTENT_WIDTH = PAGE.width - 2 * MARGIN
        val TOP = PAGE.height - 50f
        const val BOTTOM = 40f
        const val LEAD = 10f
        const val UNIT_COL_WIDTH = 150f
        const val MAX_WRAPPED_LINES = 6
        const val MAX_NAME_CHARS = 80
        const val WATERMARK_ALPHA = 0.12f
        const val ID_BYTES = 16
    }
}
