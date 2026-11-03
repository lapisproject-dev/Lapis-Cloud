package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.xlsx.PartgXlsxWriter
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgPercent
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgReportLimits
import network.lapis.cloud.shared.domain.PartgSection
import network.lapis.cloud.shared.domain.PartgTriState
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * V1.9.256 -- the report draft as an XLSX file, written by hand (no library), same content and order as the PDF.
 *
 * Safe by construction: EVERY string is an `inlineStr` (no shared strings), there is NO formula (`<f>`) anywhere, no macro part, content type
 * `.sheet`. A text that starts with `= + - @` gets the `quotePrefix` style, tab / CR / LF are replaced by a space and characters that are
 * invalid in XML are dropped ([PartgXlsxWriter.escape]). Amounts are real numbers (euro with two decimals, negative stays negative), shares
 * are fractions with the format `0.0%`. A missing value is the text "nicht erfasst", never 0. Deterministic: fixed ZIP time and part order.
 */
internal object PartgReportXlsxWriter {
    const val SHEET_COMPLETENESS = "Vollständigkeit"
    const val SHEET_SUMMARY = "Zusammenfassung"
    const val SHEET_INCOME = "Einnahmen"
    const val SHEET_EXPENSES = "Ausgaben"
    const val SHEET_ASSETS = "Vermögen"
    const val SHEET_NOTES = "Hinweise"
    const val SHEET_ANNEX_A = "Ausweis A"
    const val SHEET_ANNEX_B = "Ausweis B"
    const val SHEET_MEMBERS = "Mitglieder"
    const val SHEET_EXPLANATIONS = "Erläuterungen"

    private val SHEET_NAMES =
        listOf(
            SHEET_COMPLETENESS,
            SHEET_SUMMARY,
            SHEET_INCOME,
            SHEET_EXPENSES,
            SHEET_ASSETS,
            SHEET_ANNEX_A,
            SHEET_ANNEX_B,
            SHEET_MEMBERS,
            SHEET_EXPLANATIONS,
            SHEET_NOTES,
        )

    // cellXfs indices -- kept in ONE place, STYLES below must match
    const val S_DEFAULT = 0
    const val S_BOLD = 1
    const val S_EURO = 2
    const val S_PERCENT = 3
    const val S_QUOTE = 4
    const val S_BOLD_EURO = 5
    const val S_BOLD_PERCENT = 6
    const val NUM_FMT_EURO = 164
    const val NUM_FMT_PERCENT = 165

    private sealed interface X {
        data class Text(
            val value: String,
            val bold: Boolean = false,
        ) : X

        data class Euro(
            val cents: Long,
            val bold: Boolean = false,
        ) : X

        data class Pct(
            val tenths: Long,
            val bold: Boolean = false,
            /** Decimal places of [tenths] as a fraction: 3 for tenths of a percent, 4 for hundredths of a percent. */
            val scale: Int = 3,
        ) : X

        data class Num(
            val value: Long,
            val bold: Boolean = false,
        ) : X
    }

    /** Sparse row: column index (0-based) to cell. */
    private class Sheet(
        val widths: List<Int>,
    ) {
        val rows = mutableListOf<Map<Int, X>>()

        fun add(vararg cells: X?) {
            rows += cells.withIndex().filter { it.value != null }.associate { it.index to it.value!! }
        }

        fun blank() {
            rows += emptyMap()
        }
    }

    fun write(document: PartgReportDocument): ByteArray {
        val sheets =
            listOf(
                completeness(document),
                summary(document),
                income(document),
                expenses(document),
                assets(document),
                annexA(document),
                annexB(document),
                members(document),
                explanations(document),
                notes(document),
            )
        val header = headerLine(document)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entry(zip = zip, name = "[Content_Types].xml", content = contentTypes())
            entry(zip = zip, name = "_rels/.rels", content = ROOT_RELS)
            entry(zip = zip, name = "docProps/app.xml", content = APP_XML)
            entry(zip = zip, name = "xl/workbook.xml", content = workbook())
            entry(zip = zip, name = "xl/_rels/workbook.xml.rels", content = workbookRels())
            entry(zip = zip, name = "xl/styles.xml", content = STYLES)
            sheets.forEachIndexed {
                i,
                sheet,
                ->
                entry(zip = zip, name = "xl/worksheets/sheet${i + 1}.xml", content = sheetXml(header = header, sheet = sheet))
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size > PartgReportLimits.MAX_XLSX_BYTES) throw PartgReportTooLargeException("xlsx size limit")
        return bytes
    }

    private fun headerLine(doc: PartgReportDocument): String =
        "${doc.organizationName.take(MAX_NAME_CHARS)} · ${PartgReportTexts.TITLE} ${doc.year} · ${PartgReportTexts.HEADER_SUFFIX}"

    // ------------------------------------------------------------------ cells

    private fun cellOf(
        c: PartgCell,
        bold: Boolean = false,
    ): X =
        when (c) {
            is PartgCell.Value -> X.Euro(cents = c.cents, bold = bold)
            is PartgCell.Partial -> X.Euro(cents = c.cents, bold = bold)
            is PartgCell.NotEntered -> X.Text(value = PartgReportTexts.NOT_ENTERED_LONG, bold = bold)
        }

    private fun percentOf(
        p: PartgPercent?,
        bold: Boolean = false,
    ): X? =
        when (p) {
            null -> null
            is PartgPercent.NotComputable -> X.Text(value = PartgReportTexts.NOT_COMPUTABLE, bold = bold)
            is PartgPercent.Tenths -> X.Pct(tenths = p.tenths, bold = bold)
        }

    private fun noteOf(vararg parts: Pair<String, PartgCell>): X? {
        val text = parts.filter { it.second is PartgCell.Partial }.joinToString("; ") { "${it.first} ${PartgReportTexts.INCOMPLETE_LONG}" }
        return if (text.isEmpty()) null else X.Text(value = text)
    }

    private fun lineCells(line: ReportLine): Array<X?> =
        arrayOf(
            X.Text(value = line.numberText, bold = line.bold),
            X.Text(value = line.label, bold = line.bold),
            cellOf(c = line.current, bold = line.bold),
            percentOf(p = line.currentPercent, bold = line.bold),
            cellOf(c = line.prior, bold = line.bold),
            percentOf(p = line.priorPercent, bold = line.bold),
            noteOf("Berichtsjahr" to line.current, "Vorjahr" to line.prior),
        )

    private val lineHeader: Array<X?> =
        arrayOf(
            X.Text(value = PartgReportTexts.COL_NUMBER, bold = true),
            X.Text(value = PartgReportTexts.COL_POSITION, bold = true),
            X.Text(value = PartgReportTexts.COL_CURRENT, bold = true),
            X.Text(value = PartgReportTexts.COL_PERCENT, bold = true),
            X.Text(value = PartgReportTexts.COL_PRIOR, bold = true),
            X.Text(value = PartgReportTexts.COL_PERCENT, bold = true),
            X.Text(value = PartgReportTexts.COL_NOTE, bold = true),
        )

    // ------------------------------------------------------------------ sheets

    private fun completeness(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(48, 22, 40, 22, 30))
        s.add(X.Text(value = PartgReportTexts.COMPLETENESS, bold = true))
        s.add(
            X.Text(value = PartgReportTexts.COL_UNIT, bold = true),
            X.Text(value = "Positionen nicht erfasst", bold = true),
            X.Text(value = "Nicht erfasst: Nr.", bold = true),
            X.Text(value = "Vorjahr: Positionen erfasst", bold = true),
            X.Text(value = PartgReportTexts.COL_NOTE, bold = true),
        )
        val leafCount = doc.completeness.leafCount
        for (unit in doc.completeness.units) {
            val note =
                when {
                    unit.enteredPrior == 0 -> "Vorjahr nicht erfasst"
                    unit.enteredPrior < leafCount -> "Vorjahr unvollständig"
                    else -> null
                }
            s.add(
                X.Text(value = PartgReportAssembler.unitLabel(unit.unit)),
                X.Text(value = unit.missingCurrent.size.toString()),
                X.Text(value = unit.missingCurrent.joinToString(", ") { PartgReportLayout.numberText(it) }),
                X.Text(value = "${unit.enteredPrior} / $leafCount"),
                note?.let { X.Text(value = it) },
            )
        }
        return s
    }

    private fun summary(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(10, 70, 24, 10, 24, 10, 36))
        val page = doc.summaryPage
        s.add(X.Text(value = PartgReportTexts.SUMMARY, bold = true))
        s.add(X.Text(value = page.label, bold = true))
        s.add(*lineHeader)
        s.add(null, X.Text(value = "Einnahmen", bold = true))
        PartgReportLayout.summaryIncome(page).forEach { s.add(*lineCells(it)) }
        s.add(null, X.Text(value = "Ausgaben", bold = true))
        PartgReportLayout.summaryExpenses(page).forEach { s.add(*lineCells(it)) }
        s.blank()
        s.add(null, X.Text(value = PartgReportTexts.SUBSIDY_NOTE))
        if (doc.levels.isNotEmpty()) {
            s.blank()
            s.add(X.Text(value = "${PartgReportTexts.BY_LEVEL} (Berichtsjahr ${doc.year})", bold = true))
            s.add(
                X.Text(value = PartgReportTexts.COL_UNIT, bold = true),
                null,
                X.Text(value = "Gesamteinnahmen", bold = true),
                null,
                X.Text(value = "Gesamtausgaben", bold = true),
                null,
                X.Text(value = "Überschuss/Defizit", bold = true),
                X.Text(value = "Reinvermögen", bold = true),
            )
            doc.levels.forEach { level ->
                val cells = PartgReportLayout.LEVEL_CODES.map { cellOf(c = level.current.getValue(it)) }
                s.add(X.Text(value = level.label), null, cells[0], null, cells[1], null, cells[2], cells[3])
            }
        }
        return s
    }

    private fun income(doc: PartgReportDocument): Sheet {
        val codes = PartgReportLayout.MATRIX_CODES
        val s = Sheet(listOf(48) + codes.map { 20 } + listOf(40))
        s.add(X.Text(value = PartgReportTexts.INCOME, bold = true))
        for ((current, label) in listOf(true to "Berichtsjahr ${doc.year}", false to "Vorjahr ${doc.priorYear}")) {
            s.blank()
            s.add(X.Text(value = label, bold = true))
            s.add(
                X.Text(value = PartgReportTexts.COL_UNIT, bold = true),
                *codes.map<String, X?> { X.Text(value = PartgReportLayout.matrixTitle(it), bold = true) }.toTypedArray(),
                X.Text(value = PartgReportTexts.COL_NOTE, bold = true),
            )
            doc.pages.forEach { page ->
                val cells = codes.map { (if (current) page.current else page.prior).getValue(it) }
                val bold = page === doc.consolidated
                val partial =
                    codes.filter { (if (current) page.current else page.prior).getValue(it) is PartgCell.Partial }
                val note =
                    if (partial.isEmpty()) {
                        null
                    } else {
                        X.Text(
                            value =
                                "${PartgReportTexts.INCOMPLETE_LONG}: " + partial.joinToString(", ") { PartgReportLayout.numberText(it) },
                        )
                    }
                s.add(
                    X.Text(value = page.label, bold = bold),
                    *cells.map<PartgCell, X?> { cellOf(c = it, bold = bold) }.toTypedArray(),
                    note,
                )
            }
        }
        return s
    }

    private fun expenses(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(10, 70, 24, 10, 24, 10, 36))
        s.add(X.Text(value = PartgReportTexts.EXPENSES, bold = true))
        doc.pages.forEach { page ->
            s.blank()
            s.add(X.Text(value = page.label, bold = true))
            s.add(*lineHeader)
            PartgReportLayout.expenseLines(page).forEach { s.add(*lineCells(it)) }
        }
        return s
    }

    private fun assets(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(10, 70, 24, 24, 36))
        s.add(X.Text(value = PartgReportTexts.ASSETS, bold = true))
        doc.pages.forEach { page ->
            s.blank()
            s.add(X.Text(value = page.label, bold = true))
            s.add(
                X.Text(value = PartgReportTexts.COL_NUMBER, bold = true),
                X.Text(value = PartgReportTexts.COL_POSITION, bold = true),
                X.Text(value = PartgReportTexts.COL_CURRENT, bold = true),
                X.Text(value = PartgReportTexts.COL_PRIOR, bold = true),
                X.Text(value = PartgReportTexts.COL_NOTE, bold = true),
            )
            PartgReportLayout.assetLines(page).forEach { line ->
                s.add(
                    X.Text(value = line.numberText, bold = line.bold),
                    X.Text(value = line.label, bold = line.bold),
                    cellOf(c = line.current, bold = line.bold),
                    cellOf(c = line.prior, bold = line.bold),
                    noteOf("Berichtsjahr" to line.current, "Vorjahr" to line.prior),
                )
            }
        }
        return s
    }

    private fun deductionTitle(d: PartgDeduction): String =
        when (d) {
            PartgDeduction.UNASSIGNABLE -> PartgReportTexts.DEDUCTION_UNASSIGNABLE
            PartgDeduction.CASH_OVER_1000 -> PartgReportTexts.DEDUCTION_CASH
            PartgDeduction.OVER_3300 -> PartgReportTexts.DEDUCTION_OVER_3300
            PartgDeduction.WRONGLY_REPORTED -> PartgReportTexts.DEDUCTION_WRONG
        }

    private fun annexA(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(48, 28, 28, 28, 28))
        s.add(X.Text(value = PartgReportTexts.ANNEX_A, bold = true))
        s.add(
            X.Text(value = PartgReportTexts.COL_UNIT, bold = true),
            *PartgDeduction.entries.map<PartgDeduction, X?> { X.Text(value = deductionTitle(it), bold = true) }.toTypedArray(),
        )
        (doc.annex.units + listOfNotNull(doc.annex.wholeParty)).forEach { page ->
            val bold = page === doc.annex.wholeParty
            s.add(
                X.Text(value = page.label, bold = bold),
                *PartgDeduction.entries.map<PartgDeduction, X?> { cellOf(c = page.deductions.getValue(it), bold = bold) }.toTypedArray(),
            )
        }
        return s
    }

    private fun annexB(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(40, 8, 12, 40, 50, 8, 20, 18))
        s.add(X.Text(value = PartgReportTexts.ANNEX_B, bold = true))
        s.add(X.Text(value = PartgReportTexts.B_NOTE))
        s.add(
            X.Text(value = PartgReportTexts.COL_UNIT, bold = true),
            X.Text(value = PartgReportTexts.COL_ROW, bold = true),
            X.Text(value = PartgReportTexts.COL_PERSON_KIND, bold = true),
            X.Text(value = PartgReportTexts.COL_NAME, bold = true),
            X.Text(value = PartgReportTexts.COL_ADDRESS, bold = true),
            X.Text(value = PartgReportTexts.COL_COUNTRY, bold = true),
            X.Text(value = PartgReportTexts.COL_CONTRIBUTION, bold = true),
            X.Text(value = PartgReportTexts.COL_AMOUNT, bold = true),
        )
        if (doc.annex.donors.isEmpty()) s.add(X.Text(value = PartgReportTexts.NO_ENTRIES))
        doc.annex.donors.forEach { d ->
            s.add(
                X.Text(value = d.unitLabel),
                X.Text(value = d.rowNumber.toString()),
                X.Text(
                    value =
                        if (d.personKind ==
                            PartgPersonKind.LEGAL
                        ) {
                            PartgReportTexts.PERSON_LEGAL
                        } else {
                            PartgReportTexts.PERSON_NATURAL
                        },
                ),
                X.Text(value = d.name),
                X.Text(value = d.addressLine),
                X.Text(value = d.country),
                X.Text(
                    value =
                        when (d.contributionKind) {
                            PartgContributionKind.DONATION -> PartgReportTexts.CONTRIBUTION_DONATION
                            PartgContributionKind.FEE -> PartgReportTexts.CONTRIBUTION_FEE
                            PartgContributionKind.BOTH -> PartgReportTexts.CONTRIBUTION_BOTH
                        },
                ),
                X.Euro(cents = d.amountCents),
            )
        }
        return s
    }

    private fun members(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(48, 18))
        s.add(X.Text(value = PartgReportTexts.ANNEX_E, bold = true))
        s.add(X.Text(value = PartgReportTexts.E_NOTE + if (doc.annex.wholeParty != null) " " + PartgReportTexts.E_NOTE_ALL else ""))
        s.add(X.Text(value = PartgReportTexts.COL_UNIT, bold = true), X.Text(value = PartgReportTexts.COL_MEMBERS, bold = true))
        (doc.annex.units + listOfNotNull(doc.annex.wholeParty)).forEach { page ->
            val bold = page === doc.annex.wholeParty
            s.add(
                X.Text(value = page.label, bold = bold),
                page.memberCount?.let { X.Num(value = it.toLong(), bold = bold) }
                    ?: X.Text(value = PartgReportTexts.NOT_ENTERED_LONG, bold = bold),
            )
        }
        return s
    }

    private fun explanations(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(40, 24, 24, 20, 20, 120))
        s.add(X.Text(value = PartgReportTexts.NOTES_TITLE, bold = true))
        if (doc.notes.blocks.isEmpty()) s.add(X.Text(value = PartgReportTexts.NOT_ENTERED_LONG))
        doc.notes.blocks.forEach { note ->
            s.add(X.Text(value = noteTitle(note.block), bold = true))
            if (note.block == PartgNoteBlock.RELIEF_28) {
                s.add(
                    X.Text(value = PartgReportTexts.RELIEF_ABS1),
                    X.Text(value = triText(note.reliefAbs1)),
                    X.Text(value = PartgReportTexts.RELIEF_ABS3),
                    X.Text(value = triText(note.reliefAbs3)),
                )
            }
            note.body?.split('\n')?.forEach { line -> s.add(X.Text(value = line)) }
            if (note.block == PartgNoteBlock.HOLDINGS && doc.notes.holdings.isNotEmpty()) {
                s.add(
                    X.Text(value = PartgReportTexts.HOLDING_NAME, bold = true),
                    X.Text(value = PartgReportTexts.HOLDING_SEAT, bold = true),
                    X.Text(value = PartgReportTexts.HOLDING_SHARE, bold = true),
                    X.Text(value = PartgReportTexts.HOLDING_CAPITAL, bold = true),
                    X.Text(value = PartgReportTexts.HOLDING_RESULT, bold = true),
                )
                doc.notes.holdings.forEach { h ->
                    s.add(
                        X.Text(value = h.name),
                        X.Text(value = h.seat.orEmpty()),
                        h.shareBasisPoints?.let { X.Pct(tenths = it.toLong(), bold = false, scale = 4) }
                            ?: X.Text(value = PartgReportTexts.NOT_ENTERED_LONG),
                        h.capitalCents?.let { X.Euro(cents = it) } ?: X.Text(value = PartgReportTexts.NOT_ENTERED_LONG),
                        h.resultCents?.let { X.Euro(cents = it) } ?: X.Text(value = PartgReportTexts.NOT_ENTERED_LONG),
                    )
                }
            }
            s.blank()
        }
        return s
    }

    private fun noteTitle(block: PartgNoteBlock) =
        when (block) {
            PartgNoteBlock.GENERAL_ACCOUNTING -> PartgReportTexts.NOTE_GENERAL
            PartgNoteBlock.RELIEF_28 -> PartgReportTexts.NOTE_RELIEF
            PartgNoteBlock.HOLDINGS -> PartgReportTexts.NOTE_HOLDINGS
            PartgNoteBlock.MEDIA_PRODUCTS -> PartgReportTexts.NOTE_MEDIA
            PartgNoteBlock.REAL_ESTATE_VALUATION -> PartgReportTexts.NOTE_REAL_ESTATE
        }

    private fun triText(state: PartgTriState?) =
        when (state) {
            PartgTriState.YES -> PartgReportTexts.YES
            PartgTriState.NO -> PartgReportTexts.NO
            else -> PartgReportTexts.UNSPECIFIED
        }

    private fun notes(doc: PartgReportDocument): Sheet {
        val s = Sheet(listOf(120))
        s.add(X.Text(value = PartgReportTexts.NOTES, bold = true))
        s.add(X.Text(value = PartgReportTexts.DRAFT_NOTICE))
        s.add(X.Text(value = PartgReportTexts.SUBSIDY_NOTE))
        s.add(X.Text(value = PartgReportTexts.LEGEND))
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
            )?.let { s.add(X.Text(value = it)) }
        // R3: the plausibility hints. Codes, unit labels, row numbers and position numbers only: never a name, an address or an amount.
        s.blank()
        s.add(X.Text(value = "Plausibilitätshinweise", bold = true))
        if (doc.findings.isEmpty()) s.add(X.Text(value = PartgReportTexts.FINDINGS_NONE))
        val labels =
            doc.annex.units.indices
                .associate { i ->
                    doc.completeness.units[i]
                        .unit.unitKey to doc.annex.units[i].label
                }
        doc.findings.forEach { f ->
            val rows = if (f.rowNumbers.isEmpty()) "" else " (Zeilen " + f.rowNumbers.joinToString(", ") + ")"
            val position = f.positionCode?.let { " Position " + PartgReportLayout.numberText(it) }.orEmpty()
            val gap = f.gap?.let { " [$it]" }.orEmpty()
            val unit = f.unitKey?.let { labels[it] } ?: PartgReportTexts.ORGANIZATION
            val level = if (f.level == PartgFindingLevel.CHECK) PartgReportTexts.LEVEL_CHECK else PartgReportTexts.LEVEL_INFO
            s.add(X.Text(value = "${f.code.name} · $level · $unit · ${PartgReportTexts.finding(f.code)}$rows$position$gap"))
        }
        return s
    }

    // ------------------------------------------------------------------ xml

    private fun column(index: Int): String = ('A' + index).toString()

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun sheetXml(
        header: String,
        sheet: Sheet,
    ): String {
        val sb = StringBuilder()
        sb.append(XML)
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"/></sheetViews><cols>""")
        sheet.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        // row 1: the PDF header line, row 2 stays empty, the table starts in row 3
        sb.append(rowXml(r = 1, cells = mapOf(0 to X.Text(value = header, bold = false))))
        sheet.rows.forEachIndexed { i, cells -> if (cells.isNotEmpty()) sb.append(rowXml(r = i + 3, cells = cells)) }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun rowXml(
        r: Int,
        cells: Map<Int, X>,
    ): String {
        val sb = StringBuilder("""<row r="$r">""")
        cells.toSortedMap().forEach { (col, x) ->
            val ref = column(col) + r
            when (x) {
                is X.Text -> {
                    val text = clean(x.value)
                    val quote = text.firstOrNull()?.let { it in "=+-@" } == true
                    val style =
                        if (x.bold) {
                            S_BOLD
                        } else if (quote) {
                            S_QUOTE
                        } else {
                            S_DEFAULT
                        }
                    sb.append(
                        """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${PartgXlsxWriter.escape(text)}</t></is></c>""",
                    )
                }
                is X.Euro -> {
                    val style = if (x.bold) S_BOLD_EURO else S_EURO
                    sb.append("""<c r="$ref" s="$style"><v>${BigDecimal.valueOf(x.cents, 2).toPlainString()}</v></c>""")
                }
                is X.Pct -> {
                    val style = if (x.bold) S_BOLD_PERCENT else S_PERCENT
                    sb.append("""<c r="$ref" s="$style"><v>${BigDecimal.valueOf(x.tenths, x.scale).toPlainString()}</v></c>""")
                }
                is X.Num -> {
                    val style = if (x.bold) S_BOLD else S_DEFAULT
                    sb.append("""<c r="$ref" s="$style"><v>${x.value}</v></c>""")
                }
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    private fun entry(
        zip: ZipOutputStream,
        name: String,
        content: String,
    ) {
        val e = ZipEntry(name)
        e.timeLocal = LocalDateTime.of(2000, 1, 1, 0, 0)
        zip.putNextEntry(e)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private const val MAX_NAME_CHARS = 80
    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private fun contentTypes(): String =
        XML +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            SHEET_NAMES.indices.joinToString("") {
                """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" """ +
                    """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            } +
            """<Override PartName="/xl/styles.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            """<Override PartName="/docProps/app.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
            "</Types>"

    private fun workbook(): String =
        XML +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
            SHEET_NAMES.withIndex().joinToString("") {
                """<sheet name="${PartgXlsxWriter.escape(it.value)}" sheetId="${it.index + 1}" r:id="rId${it.index + 1}"/>"""
            } +
            "</sheets></workbook>"

    private fun workbookRels(): String =
        XML +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            SHEET_NAMES.indices.joinToString("") {
                """<Relationship Id="rId${it + 1}" """ +
                    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>"""
            } +
            """<Relationship Id="rId${SHEET_NAMES.size + 1}" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            "</Relationships>"

    private const val ROOT_RELS =
        XML +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """<Relationship Id="rId2" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" """ +
            """Target="docProps/app.xml"/>""" +
            "</Relationships>"

    private const val APP_XML =
        XML +
            """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">""" +
            "<Application>Lapis Cloud</Application></Properties>"

    private const val STYLES =
        XML +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="2">""" +
            """<numFmt numFmtId="164" formatCode="#,##0.00 &quot;€&quot;;-#,##0.00 &quot;€&quot;"/>""" +
            """<numFmt numFmtId="165" formatCode="0.0%"/></numFmts>""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="7">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" quotePrefix="1"/>""" +
            """<xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>""" +
            """<xf numFmtId="165" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>""" +
            "</cellXfs></styleSheet>"
}
