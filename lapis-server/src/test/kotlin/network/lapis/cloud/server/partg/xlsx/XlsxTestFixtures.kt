package network.lapis.cloud.server.partg.xlsx

import network.lapis.cloud.server.articles.TestZipBuilder
import network.lapis.cloud.server.articles.TestZipEntry

/** Hand-built `.xlsx` files for the reader tests: real Excel/LibreOffice files are not available in the build, so the variants are written out. */
internal object XlsxTestFixtures {
    const val UNIT = "FEDERAL"
    const val YEAR = 2025

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    fun s(
        ref: String,
        text: String,
    ) = """<c r="$ref" t="inlineStr"><is><t>${PartgXlsxWriter.escape(text)}</t></is></c>"""

    fun n(
        ref: String,
        value: String,
        style: Int = 0,
    ) = """<c r="$ref" s="$style"><v>$value</v></c>"""

    fun f(
        ref: String,
        formula: String,
        cached: String?,
    ) = """<c r="$ref"><f>$formula</f>${if (cached != null) "<v>$cached</v>" else ""}</c>"""

    fun row(
        r: Int,
        vararg cells: String,
    ) = """<row r="$r">${cells.joinToString("")}</row>"""

    /** Rows 1-8 of the template (version, unit, year, header) with the given overrides, then [data] rows. */
    fun sheet(
        version: String = PartgXlsxLayout.TEMPLATE_VERSION,
        unit: String = UNIT,
        year: String = YEAR.toString(),
        vararg data: String,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$NS"><sheetData>""" +
            row(r = 1, s(ref = "A1", text = "Vorlage"), s(ref = "B1", text = version)) +
            row(r = 3, s(ref = "A3", text = "Gliederung"), s(ref = "B3", text = unit)) +
            row(r = 4, s(ref = "A4", text = "Jahr"), n(ref = "B4", value = year)) +
            row(
                r = 8,
                s(ref = "A8", text = "Kennung"),
                s(ref = "B8", text = "Nr."),
                s(ref = "C8", text = "Bezeichnung"),
                s(ref = "D8", text = "J"),
                s(ref = "E8", text = "J-1"),
            ) +
            data.joinToString("") +
            "</sheetData></worksheet>"

    fun dataRow(
        r: Int,
        code: String,
        d: String? = null,
        e: String? = null,
    ) = row(
        r = r,
        s(ref = "A$r", text = code),
        *listOfNotNull(
            d?.let {
                n(ref = "D$r", value = it)
            },
            e?.let { n(ref = "E$r", value = it) },
        ).toTypedArray(),
    )

    /** A complete minimal workbook around [sheetXml]. */
    fun mini(
        sheetXml: String,
        sharedStrings: String? = null,
        styles: String? = null,
        sheetTarget: String = "worksheets/sheet1.xml",
        sheetPath: String = "xl/worksheets/sheet1.xml",
        contentTypes: String = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
        extra: List<TestZipEntry> = emptyList(),
    ): ByteArray {
        val rels =
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$sheetTarget"/>""" +
                """<Relationship Id="rId2" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
                """<Relationship Id="rId3" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" """ +
                """Target="sharedStrings.xml"/>""" +
                "</Relationships>"
        val workbook =
            """<workbook xmlns="$NS" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                """<sheets><sheet name="A" sheetId="1" r:id="rId1"/><sheet name="B" sheetId="2" r:id="rId9"/></sheets></workbook>"""
        val entries =
            mutableListOf(
                TestZipEntry(name = "[Content_Types].xml", data = contentTypes.toByteArray()),
                TestZipEntry(name = "xl/workbook.xml", data = workbook.toByteArray()),
                TestZipEntry(name = "xl/_rels/workbook.xml.rels", data = rels.toByteArray()),
                TestZipEntry(name = sheetPath, data = sheetXml.toByteArray()),
            )
        sharedStrings?.let { entries += TestZipEntry(name = "xl/sharedStrings.xml", data = it.toByteArray()) }
        styles?.let { entries += TestZipEntry(name = "xl/styles.xml", data = it.toByteArray()) }
        entries += extra
        return TestZipBuilder.build(entries = entries)
    }

    fun styles(
        vararg numFmtIdsPerXf: Int,
        custom: Map<Int, String> = emptyMap(),
    ): String =
        """<styleSheet xmlns="$NS">""" +
            (
                if (custom.isEmpty()) {
                    ""
                } else {
                    "<numFmts>${custom.entries.joinToString(
                        "",
                    ) { """<numFmt numFmtId="${it.key}" formatCode="${PartgXlsxWriter.escape(it.value)}"/>""" }}</numFmts>"
                }
            ) +
            "<cellXfs>${numFmtIdsPerXf.joinToString("") { """<xf numFmtId="$it"/>""" }}</cellXfs></styleSheet>"
}
