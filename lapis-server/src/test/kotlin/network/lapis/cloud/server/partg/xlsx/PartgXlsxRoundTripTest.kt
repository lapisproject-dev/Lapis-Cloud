package network.lapis.cloud.server.partg.xlsx

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnit
import java.util.zip.ZipInputStream

class PartgXlsxRoundTripTest :
    FunSpec({
        val id = "0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90"

        fun parts(bytes: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { out[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
            }
            return out
        }

        test("writer output is read back to the same values; the file is deterministic") {
            val values = mapOf("E01" to 123_456L, "A01" to 7L, "BP07" to 0L)
            val prior = mapOf("E01" to 1L, "SP05" to 99_999_999_999_999L)
            val unit = PartgUnit.State(id)
            val bytes =
                PartgXlsxWriter.write(
                    organizationName = "PdV",
                    unit = unit,
                    unitName = "Landesverband Nord",
                    year = 2025,
                    values = values,
                    priorValues = prior,
                )
            bytes.contentEquals(
                PartgXlsxWriter.write(
                    organizationName = "PdV",
                    unit = unit,
                    unitName = "Landesverband Nord",
                    year = 2025,
                    values = values,
                    priorValues = prior,
                ),
            ) shouldBe
                true
            val parsed = PartgXlsxReader.parse(bytes = bytes, expectedUnit = unit, expectedYear = 2025)
            parsed.errors shouldBe emptyList()
            parsed.values[PartgImportColumn.CURRENT to "E01"] shouldBe 123_456L
            parsed.values[PartgImportColumn.CURRENT to "A01"] shouldBe 7L
            parsed.values[PartgImportColumn.CURRENT to "BP07"] shouldBe 0L
            parsed.values[PartgImportColumn.PRIOR to "E01"] shouldBe 1L
            parsed.values[PartgImportColumn.PRIOR to "SP05"] shouldBe 99_999_999_999_999L
            parsed.values.size shouldBe 5
            parsed.recognizedRows shouldBe 42
            parsed.fromFormula shouldBe emptySet()
        }

        test("an empty template reads as no values at all") {
            val parsed =
                PartgXlsxReader.parse(
                    bytes =
                        PartgXlsxWriter.write(
                            organizationName = "PdV",
                            unit = PartgUnit.Federal,
                            unitName = "Bund",
                            year = 2025,
                            values = emptyMap(),
                            priorValues = emptyMap(),
                        ),
                    expectedUnit = PartgUnit.Federal,
                    expectedYear = 2025,
                )
            parsed.errors shouldBe emptyList()
            parsed.values shouldBe emptyMap()
        }

        test("the sum rows carry a formula without a cached value, the typed-in cells do not") {
            val sheet =
                parts(
                    PartgXlsxWriter.write(
                        organizationName = "PdV",
                        unit = PartgUnit.Federal,
                        unitName = "Bund",
                        year = 2025,
                        values = emptyMap(),
                        priorValues = emptyMap(),
                    ),
                )["xl/worksheets/sheet1.xml"]!!
            val formulas = Regex("<f>([^<]*)</f>").findAll(sheet).map { it.groupValues[1] }.toList()
            formulas.size shouldBe 14 // 7 sum rows x 2 columns
            sheet shouldNotContain "<f></f>"
            Regex("<f>[^<]*</f><v>").containsMatchIn(sheet) shouldBe false
            val sums = PartgPositions.ALL.filter { it.kind == PartgKind.SUM }.map { PartgXlsxLayout.rowOf(it.code) }
            sums.forEach { r -> sheet shouldContain """<c r="D$r" s="2"><f>""" }
            val eRow = PartgXlsxLayout.rowOf("E11")
            sheet shouldContain "<f>SUM(D9:D19)</f>"
            sheet shouldContain "<c r=\"D$eRow\""
        }

        test("sheet protection without a password; only the value cells of typed-in rows are unlocked") {
            val bytes =
                PartgXlsxWriter.write(
                    organizationName = "PdV",
                    unit = PartgUnit.Federal,
                    unitName = "Bund",
                    year = 2025,
                    values = emptyMap(),
                    priorValues = emptyMap(),
                )
            val p = parts(bytes)
            p["xl/worksheets/sheet1.xml"]!! shouldContain """<sheetProtection sheet="1" objects="1" scenarios="1"/>"""
            p["xl/worksheets/sheet1.xml"]!! shouldNotContain "password"
            p["xl/styles.xml"]!! shouldContain """<protection locked="0"/>"""
            val sheet = p["xl/worksheets/sheet1.xml"]!!
            // style 3 = unlocked euro: exactly the D and E cells of the 35 typed-in rows
            Regex("""<c r="[DE]\d+" s="3"""").findAll(sheet).count() shouldBe 70
            Regex("""<c r="[ABC]\d+" s="3"""").findAll(sheet).count() shouldBe 0
        }

        test("an organisation name that looks like a formula is plain inline text, never a formula") {
            val evil = "=HYPERLINK(\"http://evil.example/\",\"x\")&<b>"
            val sheet =
                parts(
                    PartgXlsxWriter.write(
                        organizationName = evil,
                        unit = PartgUnit.Federal,
                        unitName = "=1+1",
                        year = 2025,
                        values = emptyMap(),
                        priorValues = emptyMap(),
                    ),
                )["xl/worksheets/sheet1.xml"]!!
            sheet shouldContain "t=\"inlineStr\""
            sheet shouldContain "=HYPERLINK(&quot;http://evil.example/&quot;,&quot;x&quot;)&amp;&lt;b&gt;"
            sheet shouldNotContain "<f>=HYPERLINK"
            sheet shouldNotContain "<f>=1+1"
            Regex("<f>[^<]*HYPERLINK").containsMatchIn(sheet) shouldBe false
        }

        test("invalid XML characters are removed from texts") {
            PartgXlsxWriter.escape("a\u0000b\u0008c\ud800d") shouldBe "abcd"
            PartgXlsxWriter.escape("<&>\"'") shouldBe "&lt;&amp;&gt;&quot;&apos;"
        }

        test("the file name row layout is what the reader expects") {
            PartgXlsxLayout.rowOf("E01") shouldBe PartgXlsxLayout.FIRST_POSITION_ROW
            PartgXlsxLayout.rowOf("NET") shouldBe PartgXlsxLayout.FIRST_POSITION_ROW + 41
        }
    })
