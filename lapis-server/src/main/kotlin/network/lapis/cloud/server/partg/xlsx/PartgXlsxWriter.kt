package network.lapis.cloud.server.partg.xlsx

import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnit
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The fixed layout of the template; the reader and the writer agree on it (pinned by `PartgXlsxRoundTripTest`). */
internal object PartgXlsxLayout {
    const val TEMPLATE_VERSION = "partg-r1-1"
    const val ROW_VERSION = 1
    const val ROW_ORG = 2
    const val ROW_UNIT_KEY = 3
    const val ROW_YEAR = 4
    const val ROW_UNIT_NAME = 5
    const val ROW_HINT = 6
    const val ROW_HEADER = 8
    const val FIRST_POSITION_ROW = 9

    fun rowOf(code: String): Int = FIRST_POSITION_ROW + PartgPositions.ALL.indexOfFirst { it.code == code }
}

/**
 * V1.9.225 -- writes the Excel template of one unit and year (`partg-r1-1`), deterministic (fixed ZIP time, fixed part order).
 *
 * Sheet protection WITHOUT a password; only the value cells (columns D and E of the 35 typed-in rows) are unlocked. Strings are `inlineStr`,
 * XML-escaped, invalid XML characters removed. The organisation name and the unit name are NEVER written as a formula (`<f>`), so a name that
 * starts with `=` is plain text. The seven sum rows carry a formula without a cached value (`fullCalcOnLoad` makes Excel compute them).
 */
internal object PartgXlsxWriter {
    private const val S_TEXT = 4
    private const val S_BOLD = 1
    private const val S_EURO_LOCKED = 2
    private const val S_EURO_OPEN = 3

    fun write(
        organizationName: String,
        unit: PartgUnit,
        unitName: String,
        year: Int,
        values: Map<String, Long>,
        priorValues: Map<String, Long>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entry(zip = zip, name = "[Content_Types].xml", content = CONTENT_TYPES)
            entry(zip = zip, name = "_rels/.rels", content = ROOT_RELS)
            entry(zip = zip, name = "docProps/app.xml", content = APP_XML)
            entry(zip = zip, name = "xl/workbook.xml", content = WORKBOOK)
            entry(zip = zip, name = "xl/_rels/workbook.xml.rels", content = WORKBOOK_RELS)
            entry(zip = zip, name = "xl/styles.xml", content = STYLES)
            entry(
                zip = zip,
                name = "xl/worksheets/sheet1.xml",
                content =
                    sheet(
                        organizationName = organizationName,
                        unit = unit,
                        unitName = unitName,
                        year = year,
                        values = values,
                        priorValues = priorValues,
                    ),
            )
        }
        return out.toByteArray()
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

    private fun sheet(
        organizationName: String,
        unit: PartgUnit,
        unitName: String,
        year: Int,
        values: Map<String, Long>,
        priorValues: Map<String, Long>,
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"/></sheetViews>""")
        sb.append("""<cols><col min="1" max="1" width="12" customWidth="1"/><col min="2" max="2" width="8" customWidth="1"/>""")
        sb.append("""<col min="3" max="3" width="70" customWidth="1"/><col min="4" max="5" width="24" customWidth="1"/></cols>""")
        sb.append("<sheetData>")
        row(
            sb = sb,
            r = PartgXlsxLayout.ROW_VERSION,
            text(column = "A", r = PartgXlsxLayout.ROW_VERSION, value = "Vorlage", style = S_BOLD),
            text(column = "B", r = PartgXlsxLayout.ROW_VERSION, value = PartgXlsxLayout.TEMPLATE_VERSION),
        )
        row(
            sb = sb,
            r = PartgXlsxLayout.ROW_ORG,
            text(column = "A", r = PartgXlsxLayout.ROW_ORG, value = "Organisation", style = S_BOLD),
            text(column = "B", r = PartgXlsxLayout.ROW_ORG, value = organizationName),
        )
        row(
            sb = sb,
            r = PartgXlsxLayout.ROW_UNIT_KEY,
            text(column = "A", r = PartgXlsxLayout.ROW_UNIT_KEY, value = "Gliederung (Kennung)", style = S_BOLD),
            text(column = "B", r = PartgXlsxLayout.ROW_UNIT_KEY, value = unit.key()),
        )
        row(
            sb = sb,
            r = PartgXlsxLayout.ROW_YEAR,
            text(column = "A", r = PartgXlsxLayout.ROW_YEAR, value = "Rechnungsjahr", style = S_BOLD),
            number(column = "B", r = PartgXlsxLayout.ROW_YEAR, value = year.toString(), style = S_TEXT),
        )
        row(
            sb = sb,
            r = PartgXlsxLayout.ROW_UNIT_NAME,
            text(column = "A", r = PartgXlsxLayout.ROW_UNIT_NAME, value = "Gliederung (Name)", style = S_BOLD),
            text(column = "B", r = PartgXlsxLayout.ROW_UNIT_NAME, value = unitName),
        )
        row(
            sb = sb,
            r = PartgXlsxLayout.ROW_HINT,
            text(column = "A", r = PartgXlsxLayout.ROW_HINT, value = "Hinweis", style = S_BOLD),
            text(
                column = "B",
                r = PartgXlsxLayout.ROW_HINT,
                value =
                    "Nur die Spalten D und E ausfüllen. Leere Zellen werden beim Import nicht verändert. " +
                        "Beträge in Euro, nicht negativ.",
            ),
        )
        val h = PartgXlsxLayout.ROW_HEADER
        row(
            sb = sb,
            r = h,
            text(column = "A", r = h, value = "Kennung", style = S_BOLD),
            text(column = "B", r = h, value = "Nr.", style = S_BOLD),
            text(column = "C", r = h, value = "Bezeichnung", style = S_BOLD),
            text(column = "D", r = h, value = "Rechnungsjahr $year", style = S_BOLD),
            text(column = "E", r = h, value = "Vorjahr ${year - 1}", style = S_BOLD),
        )
        PartgPositions.ALL.forEachIndexed { index, position ->
            val r = PartgXlsxLayout.FIRST_POSITION_ROW + index
            val cells =
                mutableListOf(
                    text(column = "A", r = r, value = position.code),
                    text(column = "B", r = r, value = position.numberLabel),
                    text(column = "C", r = r, value = position.labelDe),
                )
            if (position.kind == PartgKind.LEAF) {
                cells += amount(column = "D", r = r, cents = values[position.code], style = S_EURO_OPEN)
                cells += amount(column = "E", r = r, cents = priorValues[position.code], style = S_EURO_OPEN)
            } else {
                val formula = formulaOf(position.code)
                cells += """<c r="D$r" s="$S_EURO_LOCKED"><f>${formula("D")}</f></c>"""
                cells += """<c r="E$r" s="$S_EURO_LOCKED"><f>${formula("E")}</f></c>"""
            }
            row(sb = sb, r = r, *cells.toTypedArray())
        }
        sb.append("</sheetData>")
        sb.append("""<sheetProtection sheet="1" objects="1" scenarios="1"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun formulaOf(code: String): (String) -> String {
        fun range(
            column: String,
            codes: List<String>,
        ): String {
            val rows = codes.map { PartgXlsxLayout.rowOf(it) }.sorted()
            val contiguous = rows.zipWithNext().all { (a, b) -> b == a + 1 }
            return if (contiguous &&
                rows.size > 1
            ) {
                "SUM($column${rows.first()}:$column${rows.last()})"
            } else {
                rows.joinToString("+") { "$column$it" }
            }
        }

        fun childrenOf(parent: String) = PartgPositions.ALL.filter { it.parent == parent && it.kind == PartgKind.LEAF }.map { it.code }
        return { c ->
            when (code) {
                PartgPositions.INCOME_TOTAL -> range(c, childrenOf(code))
                PartgPositions.EXPENSE_MATERIAL -> range(c, childrenOf(code))
                PartgPositions.EXPENSE_TOTAL -> "$c${PartgXlsxLayout.rowOf(
                    "A01",
                )}+$c${PartgXlsxLayout.rowOf("A02")}+$c${PartgXlsxLayout.rowOf("A03")}"
                PartgPositions.RESULT -> "$c${PartgXlsxLayout.rowOf("E11")}-$c${PartgXlsxLayout.rowOf("A04")}"
                PartgPositions.ASSET_TOTAL -> range(c, childrenOf(code))
                PartgPositions.LIABILITY_TOTAL -> range(c, childrenOf(code))
                else -> "$c${PartgXlsxLayout.rowOf("BP-SUM")}-$c${PartgXlsxLayout.rowOf("SP-SUM")}"
            }
        }
    }

    private fun row(
        sb: StringBuilder,
        r: Int,
        vararg cells: String,
    ) {
        sb.append("""<row r="$r">""")
        cells.forEach { sb.append(it) }
        sb.append("</row>")
    }

    private fun text(
        column: String,
        r: Int,
        value: String,
        style: Int = S_TEXT,
    ): String = """<c r="$column$r" s="$style" t="inlineStr"><is><t xml:space="preserve">${escape(value)}</t></is></c>"""

    private fun number(
        column: String,
        r: Int,
        value: String,
        style: Int,
    ): String = """<c r="$column$r" s="$style"><v>$value</v></c>"""

    private fun amount(
        column: String,
        r: Int,
        cents: Long?,
        style: Int,
    ): String =
        if (cents == null) {
            """<c r="$column$r" s="$style"/>"""
        } else {
            number(column = column, r = r, value = BigDecimal.valueOf(cents, 2).toPlainString(), style = style)
        }

    /** XML-escapes and drops every character that is not allowed in XML 1.0. */
    fun escape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            val valid =
                cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
            if (!valid) continue
            when (cp) {
                '&'.code -> sb.append("&amp;")
                '<'.code -> sb.append("&lt;")
                '>'.code -> sb.append("&gt;")
                '"'.code -> sb.append("&quot;")
                '\''.code -> sb.append("&apos;")
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private const val CONTENT_TYPES =
        XML +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """<Override PartName="/xl/styles.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            """<Override PartName="/docProps/app.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
            "</Types>"

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
            """<Properties """ +
            """xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Lapis Cloud</Application></Properties>"""

    private const val WORKBOOK =
        XML +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="Rechenschaftsbericht" sheetId="1" r:id="rId1"/></sheets><calcPr fullCalcOnLoad="1"/></workbook>"""

    private const val WORKBOOK_RELS =
        XML +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            "</Relationships>"

    private const val STYLES =
        XML +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00 &quot;€&quot;"/></numFmts>""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name """ +
            """val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="5">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" """ +
            """applyProtection="1"><protection locked="0"/></xf>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            "</cellXfs></styleSheet>"
}
