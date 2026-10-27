package network.lapis.cloud.server.partg.xlsx

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.articles.TestZipBuilder
import network.lapis.cloud.server.articles.TestZipEntry
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.YEAR
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.dataRow
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.f
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.mini
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.n
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.row
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.s
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.sheet
import network.lapis.cloud.server.partg.xlsx.XlsxTestFixtures.styles
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgImportErrorCode
import network.lapis.cloud.shared.domain.PartgUnit
import java.io.File

private fun parse(bytes: ByteArray) = PartgXlsxReader.parse(bytes = bytes, expectedUnit = PartgUnit.Federal, expectedYear = YEAR)

private fun codes(bytes: ByteArray) = parse(bytes).errors.map { it.code }

/** V1.9.225 -- the feared and the merely unusual: hostile containers, hostile XML, and the shapes Excel and LibreOffice really write. */
class PartgXlsxReaderHostileTest :
    FunSpec({
        // ---- the container -------------------------------------------------------------------------------------------------------
        test("not a ZIP: a PDF, random bytes, the empty file, and PK followed by garbage") {
            codes("%PDF-1.7\n%....".toByteArray()) shouldBe listOf(PartgImportErrorCode.NOT_A_XLSX)
            codes(ByteArray(500) { (it * 31).toByte() }) shouldBe listOf(PartgImportErrorCode.NOT_A_XLSX)
            codes(ByteArray(0)) shouldBe listOf(PartgImportErrorCode.NOT_A_XLSX)
            codes(byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(100)) shouldBe listOf(PartgImportErrorCode.NOT_A_XLSX)
        }

        test("a ZIP without the workbook parts is not an xlsx") {
            codes(TestZipBuilder.simple("hello.txt" to "x".toByteArray())) shouldBe listOf(PartgImportErrorCode.NOT_A_XLSX)
        }

        test("zip bomb by ratio, too many entries, too large an entry, too large a total") {
            val zeros = ByteArray(2 * 1024 * 1024)
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "xl/bomb.bin", data = zeros)))) shouldBe
                listOf(PartgImportErrorCode.TOO_LARGE)
            val many = (1..70).map { TestZipEntry(name = "xl/e$it.xml", data = "<a/>".toByteArray()) }
            codes(mini(sheetXml = sheet(), extra = many)) shouldBe listOf(PartgImportErrorCode.TOO_LARGE)
            val big = ByteArray(9 * 1024 * 1024).also { java.util.Random(1).nextBytes(it) }
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "xl/big.bin", data = big, method = 0)))) shouldBe
                listOf(PartgImportErrorCode.TOO_LARGE)
            val seven = ByteArray(7 * 1024 * 1024).also { java.util.Random(2).nextBytes(it) }
            val total = (1..3).map { TestZipEntry(name = "xl/t$it.bin", data = seven, method = 0) }
            codes(mini(sheetXml = sheet(), extra = total)) shouldBe listOf(PartgImportErrorCode.TOO_LARGE)
        }

        test("path tricks, duplicate names, encrypted entries and a lying local header are refused") {
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "xl/../evil.xml", data = "x".toByteArray())))) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "/abs.xml", data = "x".toByteArray())))) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "xl\\win.xml", data = "x".toByteArray())))) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "xl/workbook.xml", data = "<a/>".toByteArray())))) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
            val encrypted = TestZipEntry(name = "xl/x.xml".toByteArray(), data = "x".toByteArray(), flags = 1)
            codes(mini(sheetXml = sheet(), extra = listOf(encrypted))) shouldBe listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
            val lying = TestZipEntry(name = "xl/y.xml".toByteArray(), data = "x".toByteArray(), localName = "xl/z.xml".toByteArray())
            codes(mini(sheetXml = sheet(), extra = listOf(lying))) shouldBe listOf(PartgImportErrorCode.INCONSISTENT)
        }

        test("macros: vbaProject.bin and a macroEnabled content type") {
            codes(mini(sheetXml = sheet(), extra = listOf(TestZipEntry(name = "xl/vbaProject.bin", data = ByteArray(10))))) shouldBe
                listOf(PartgImportErrorCode.MACROS_NOT_ALLOWED)
            val macroTypes =
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override """ +
                    """PartName="/xl/workbook.xml" ContentType="application/vnd.ms-excel.sheet.macroEnabled.main+xml"/></Types>"""
            codes(mini(sheetXml = sheet(), contentTypes = macroTypes)) shouldBe listOf(PartgImportErrorCode.MACROS_NOT_ALLOWED)
        }

        test("the workbook relation must stay inside xl/worksheets") {
            codes(mini(sheetXml = sheet(), sheetTarget = "../evil/sheet1.xml", sheetPath = "evil/sheet1.xml")) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
            codes(mini(sheetXml = sheet(), sheetTarget = "/xl/other/sheet1.xml", sheetPath = "xl/other/sheet1.xml")) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
        }

        // ---- XML -----------------------------------------------------------------------------------------------------------------
        test("XXE with an external entity is rejected and the target file is never read") {
            val secret =
                File.createTempFile("xxe-secret", ".txt").apply {
                    writeText("TOP-SECRET-CONTENT")
                    deleteOnExit()
                }
            val xml =
                """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file://${secret.absolutePath}">]>""" +
                    """<worksheet """ +
                    """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="B1" t="inlineStr"><is><t>&e;</t></is></c></row></sheetData></worksheet>"""
            val result = parse(mini(sheetXml = xml))
            result.errors.map { it.code } shouldBe listOf(PartgImportErrorCode.INCONSISTENT)
            result.errors.joinToString { it.toString() }.contains("TOP-SECRET") shouldBe false
        }

        test("a lone DOCTYPE and a billion-laughs document are rejected quickly") {
            val doctype =
                """<?xml version="1.0"?><!DOCTYPE worksheet><worksheet """ +
                    """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>"""
            codes(mini(sheetXml = doctype)) shouldBe listOf(PartgImportErrorCode.INCONSISTENT)
            val laughs =
                """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol1 """ +
                    """"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">""" +
                    """<!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">]><worksheet """ +
                    """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="B1" t="inlineStr"><is><t>&lol2;</t></is></c></row></sheetData></worksheet>"""
            val started = System.nanoTime()
            codes(mini(sheetXml = laughs)) shouldBe listOf(PartgImportErrorCode.INCONSISTENT)
            ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true
        }

        test("malformed XML never escapes as an exception") {
            codes(mini(sheetXml = "<worksheet><sheetData><row")) shouldBe listOf(PartgImportErrorCode.INCONSISTENT)
        }

        test("more than 5000 shared strings, and a text above 200 characters") {
            val si = (1..5001).joinToString("") { "<si><t>x</t></si>" }
            val shared = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$si</sst>"""
            codes(mini(sheetXml = sheet(), sharedStrings = shared)) shouldBe listOf(PartgImportErrorCode.TOO_LARGE)
            val long = "x".repeat(201)
            codes(mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = long)))))) shouldBe
                listOf(PartgImportErrorCode.TOO_LARGE)
            val sharedLong = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>$long</t></si></sst>"""
            codes(mini(sheetXml = sheet(), sharedStrings = sharedLong)) shouldBe listOf(PartgImportErrorCode.TOO_LARGE)
        }

        test("the element counter stops a sheet with a million empty rows") {
            val rows = (1..200_001).joinToString("") { "<row/>" }
            codes(
                mini(
                    sheetXml =
                        """<worksheet """ +
                            """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""",
                ),
            ) shouldBe
                listOf(PartgImportErrorCode.TOO_LARGE)
        }

        // ---- rows, cells and numbers ------------------------------------------------------------------------------------------------
        test("content below row 500 is an error, empty rows below are ignored") {
            codes(mini(sheetXml = sheet(data = arrayOf(dataRow(r = 501, code = "E01", d = "5"))))) shouldBe
                listOf(PartgImportErrorCode.ROW_LIMIT)
            val blank = row(r = 900)
            parse(mini(sheetXml = sheet(data = arrayOf(blank)))).errors shouldBe emptyList()
        }

        test("template version, unit and year must match; a mismatch reports only the file's own unit and year") {
            codes(mini(sheetXml = sheet(version = "other"))) shouldBe listOf(PartgImportErrorCode.TEMPLATE_VERSION)
            val unit = "STATE:0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90"
            val wrongUnit = parse(mini(sheetXml = sheet(unit = unit)))
            wrongUnit.errors.single().code shouldBe PartgImportErrorCode.TARGET_MISMATCH
            wrongUnit.errors.single().detail shouldBe "$unit $YEAR"
            val wrongYear = parse(mini(sheetXml = sheet(year = "2019")))
            wrongYear.errors.single().code shouldBe PartgImportErrorCode.TARGET_MISMATCH
            wrongYear.errors.single().detail shouldBe "FEDERAL 2019"
            // a hostile unit text is never echoed
            parse(mini(sheetXml = sheet(unit = "<script>alert(1)</script>"))).errors.single().detail shouldBe "? $YEAR"
        }

        test("formulas: with a cached value taken and marked, without a value an error, in a sum row ignored") {
            val withValue =
                parse(
                    mini(
                        sheetXml =
                            sheet(
                                data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), f(ref = "D9", formula = "100+50", cached = "150"))),
                            ),
                    ),
                )
            withValue.errors shouldBe emptyList()
            withValue.values[PartgImportColumn.CURRENT to "E01"] shouldBe 15_000L
            withValue.fromFormula shouldBe setOf(PartgImportColumn.CURRENT to "E01")
            val rowE11 = PartgXlsxLayout.rowOf("E11")
            val withoutValue =
                parse(
                    mini(
                        sheetXml =
                            sheet(
                                data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), f(ref = "D9", formula = "A1+1", cached = null))),
                            ),
                    ),
                )
            withoutValue.errors.single().code shouldBe PartgImportErrorCode.FORMULA_WITHOUT_RESULT
            val sumRow =
                parse(
                    mini(
                        sheetXml =
                            sheet(
                                data =
                                    arrayOf(
                                        row(
                                            r = rowE11,
                                            s(ref = "A$rowE11", text = "E11"),
                                            f(ref = "D$rowE11", formula = "SUM(D9:D19)", cached = "99"),
                                            n(ref = "E$rowE11", value = "-5"),
                                        ),
                                    ),
                            ),
                    ),
                )
            sumRow.errors shouldBe emptyList()
            sumRow.values shouldBe emptyMap()
        }

        test("IEEE doubles are rounded when the deviation is tiny, refused otherwise; exponents are fine") {
            fun value(raw: String) =
                parse(mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), n(ref = "D9", value = raw))))))
            value("1234.5599999999999").values[PartgImportColumn.CURRENT to "E01"] shouldBe 123_456L
            value("0.30000000000000004").values[PartgImportColumn.CURRENT to "E01"] shouldBe 30L
            value("1.5E3").values[PartgImportColumn.CURRENT to "E01"] shouldBe 150_000L
            value("12").values[PartgImportColumn.CURRENT to "E01"] shouldBe 1_200L
            value("1.005").errors.single().code shouldBe PartgImportErrorCode.TOO_MANY_DECIMALS
            value("-5").errors.single().code shouldBe PartgImportErrorCode.NEGATIVE_VALUE
            value("1E+20").errors.single().code shouldBe PartgImportErrorCode.VALUE_TOO_LARGE
            value("1E+400000").errors.single().code shouldBe PartgImportErrorCode.VALUE_TOO_LARGE
            value("NaN").errors.single().code shouldBe PartgImportErrorCode.NOT_A_NUMBER
            value("Infinity").errors.single().code shouldBe PartgImportErrorCode.NOT_A_NUMBER
            value("0x10").errors.single().code shouldBe PartgImportErrorCode.NOT_A_NUMBER
            value("999999999999.99").values[PartgImportColumn.CURRENT to "E01"] shouldBe 99_999_999_999_999L
            value("1000000000000").errors.single().code shouldBe PartgImportErrorCode.VALUE_TOO_LARGE
        }

        test("huge negative exponents are refused fast (CPU DoS guard)") {
            fun value(raw: String) =
                parse(mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), n(ref = "D9", value = raw))))))
            val started = System.nanoTime()
            value("1E-100000000").errors.single().code shouldBe PartgImportErrorCode.NOT_A_NUMBER
            value("1E-10000000").errors.single().code shouldBe PartgImportErrorCode.NOT_A_NUMBER
            value("1E+100000000").errors.single().code shouldBe PartgImportErrorCode.VALUE_TOO_LARGE
            ((System.nanoTime() - started) / 1_000_000 < 1_000) shouldBe true
        }

        test("text cells go through the German parser; text, booleans and error cells are refused") {
            fun text(raw: String) =
                parse(mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), s(ref = "D9", text = raw))))))
            text("1.234,56 €").values[PartgImportColumn.CURRENT to "E01"] shouldBe 123_456L
            text("abc").errors.single().code shouldBe PartgImportErrorCode.NOT_A_NUMBER
            text("-3").errors.single().code shouldBe PartgImportErrorCode.NEGATIVE_VALUE
            text("1,234").errors.single().code shouldBe PartgImportErrorCode.TOO_MANY_DECIMALS
            text("9.999.999.999.999").errors.single().code shouldBe PartgImportErrorCode.VALUE_TOO_LARGE
            text("   ").errors shouldBe emptyList()
            val bool =
                parse(mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), """<c r="D9" t="b"><v>1</v></c>""")))))
            bool.errors.single().code shouldBe PartgImportErrorCode.CELL_ERROR
            val err =
                parse(
                    mini(
                        sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), """<c r="D9" t="e"><v>#DIV/0!</v></c>"""))),
                    ),
                )
            err.errors.single().code shouldBe PartgImportErrorCode.CELL_ERROR
        }

        test("date cells are refused: built-in and custom number formats, and a t=d cell") {
            val built = styles(0, 14)
            val dateCell = """<c r="D9" s="1"><v>45000</v></c>"""
            parse(
                mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), dateCell))), styles = built),
            ).errors.single().code shouldBe
                PartgImportErrorCode.DATE_VALUE
            val custom = styles(0, 170, custom = mapOf(170 to "dd.mm.yyyy"))
            parse(
                mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), dateCell))), styles = custom),
            ).errors.single().code shouldBe
                PartgImportErrorCode.DATE_VALUE
            val money = styles(0, 171, custom = mapOf(171 to """#,##0.00 "d.m.y" [${'$'}€-407]"""))
            parse(mini(sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), dateCell))), styles = money)).errors shouldBe
                emptyList()
            parse(
                mini(
                    sheetXml = sheet(data = arrayOf(row(r = 9, s(ref = "A9", text = "E01"), """<c r="D9" t="d"><v>2025-01-01</v></c>"""))),
                ),
            ).errors.single().code shouldBe
                PartgImportErrorCode.DATE_VALUE
        }

        test("codes: unknown, duplicate; a code echoed only when it looks like one of ours") {
            val unknown = parse(mini(sheetXml = sheet(data = arrayOf(dataRow(r = 9, code = "E99", d = "5")))))
            unknown.errors.single().code shouldBe PartgImportErrorCode.UNKNOWN_CODE
            unknown.errors.single().detail shouldBe "E99"
            parse(mini(sheetXml = sheet(data = arrayOf(dataRow(r = 9, code = "<b>evil</b>", d = "5"))))).errors.single().detail shouldBe
                null
            val dup =
                parse(mini(sheetXml = sheet(data = arrayOf(dataRow(r = 9, code = "E01", d = "5"), dataRow(r = 10, code = "E01", d = "6")))))
            dup.errors.single().code shouldBe PartgImportErrorCode.DUPLICATE_CODE
            dup.values[PartgImportColumn.CURRENT to "E01"] shouldBe 500L
        }

        test("errors are collected up to 50, then one marker ends the list") {
            val rows = (9..90).map { dataRow(r = it, code = "ZZ$it", d = "1") }
            val result = parse(mini(sheetXml = sheet(data = rows.toTypedArray())))
            result.errors.size shouldBe 51
            result.errors.last().code shouldBe PartgImportErrorCode.TOO_MANY_ERRORS
        }

        // ---- shapes real office programs write -----------------------------------------------------------------------------------
        test("LibreOffice shape: shared strings, no r attributes on rows and cells, values as strings with formula result t=str") {
            val shared =
                """<sst """ +
                    """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Vorlage</t></si>""" +
                    """<si><t>partg-r1-1</t></si>""" +
                    """<si><t>FEDERAL</t></si><si><t>Kennung</t></si><si><t>E01</t></si><si><r><t>1.234,5</t></r><r><t>0</t></r></si></sst>"""
            val xml =
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
                    """<row r="1"><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row><row r="3"><c t="s"><v>0</v></c><c """ +
                    """t="s"><v>2</v></c></row>""" +
                    """<row r="4"><c><v>0</v></c><c><v>$YEAR</v></c></row><row r="8"><c t="s"><v>3</v></c></row>""" +
                    """<row r="9"><c t="s"><v>4</v></c><c/><c/><c t="s"><v>5</v></c><c """ +
                    """t="str"><f>D9</f><v>7</v></c></row></sheetData></worksheet>"""
            val result = parse(mini(sheetXml = xml, sharedStrings = shared))
            result.errors shouldBe emptyList()
            result.values[PartgImportColumn.CURRENT to "E01"] shouldBe 123_450L
            result.values[PartgImportColumn.PRIOR to "E01"] shouldBe 700L
            result.fromFormula shouldBe setOf(PartgImportColumn.PRIOR to "E01")
        }

        test("a shared-string index out of range is an inconsistency, not a crash") {
            val shared = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>x</t></si></sst>"""
            codes(mini(sheetXml = sheet(data = arrayOf(row(r = 9, """<c r="A9" t="s"><v>7</v></c>"""))), sharedStrings = shared)) shouldBe
                listOf(PartgImportErrorCode.INCONSISTENT)
        }

        test("columns after E and rows without content are ignored") {
            val result =
                parse(
                    mini(
                        sheetXml =
                            sheet(
                                data =
                                    arrayOf(
                                        row(
                                            r = 9,
                                            s(ref = "A9", text = "E01"),
                                            n(ref = "D9", value = "1"),
                                            n(ref = "F9", value = "2"),
                                            n(ref = "Z9", value = "3"),
                                        ),
                                    ),
                            ),
                    ),
                )
            result.errors shouldBe emptyList()
            result.values shouldBe mapOf((PartgImportColumn.CURRENT to "E01") to 100L)
        }

        test("a ZIP64 marker and a truncated archive are refused as unsupported or inconsistent, never as an exception") {
            val ok = mini(sheetXml = sheet())
            codes(ok.copyOf(ok.size - 10)) shouldBe codes(ok.copyOf(ok.size - 10)) // deterministic
            (codes(ok.copyOf(ok.size - 10)).single() in listOf(PartgImportErrorCode.NOT_A_XLSX, PartgImportErrorCode.INCONSISTENT)) shouldBe
                true
            codes(
                TestZipBuilder.build(entries = listOf(TestZipEntry(name = "a.xml", data = "x".toByteArray())), zip64Marker = true),
            ) shouldBe
                listOf(PartgImportErrorCode.UNSUPPORTED_FILE)
        }
    })
