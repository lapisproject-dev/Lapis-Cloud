package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

private fun pdfTextOf(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { pdf -> PDFTextStripper().apply { sortByPosition = true }.getText(pdf) }

/** V1.9.287 (R3c) -- statement D in the assembled document. */
class PartgReportR3cAssemblerTest :
    FunSpec({
        val doc = ReportFixtures.docR3c()

        test("the rows keep the order of the store (created at, id) and carry the row number of the unit") {
            doc.annex.enterprises.map { it.rowNumber } shouldContainExactly listOf(1, 2, 3, 4)
            doc.annex.enterprises.map { it.name } shouldContainExactly
                listOf("=SUM(A1) Verlag", "+1 Medien Иван", "-Gastro", "@x Immobilien")
        }

        test("the result is income minus expenses, may be negative and is null when a figure is missing") {
            doc.annex.enterprises.map { it.resultCents } shouldContainExactly listOf(60_000L, -2_000L, null, 10_000L)
        }

        test("one sum per unit that has a row; a missing figure makes that sum not computable; no whole-party sum") {
            val total = doc.annex.enterpriseTotals.single()
            total.incomeCents shouldBe null
            total.expensesCents shouldBe 58_000L
            total.resultCents shouldBe null
        }

        test("without statement data D is empty") {
            ReportFixtures.docR3b().annex.enterprises shouldBe emptyList()
            ReportFixtures.docR3b().annex.enterpriseTotals shouldBe emptyList()
        }

        test("the document reads no R1 value into D: the D figures are exactly the stored ones") {
            doc.annex.enterprises.mapNotNull { it.incomeCents } shouldContainExactly listOf(100_000L, 5_000L, 20_000L)
        }
    })

class PartgReportR3cPdfTest :
    FunSpec({
        val doc = ReportFixtures.docR3c()
        val bytes = PartgReportPdfRenderer().render(doc)
        val all = pdfTextOf(bytes)

        test("D has its own heading between C and E and no placeholder text any more") {
            all shouldContain "Gesonderter Ausweis D: Unternehmenstätigkeit (Entwurf)"
            all shouldNotContain "Ausweis D: nicht erfasst"
            all shouldNotContain "ist in diesem Entwurf nicht erfasst"
            val order = listOf("Gesonderter Ausweis C", "Gesonderter Ausweis D", "Mitgliederzahl zum 31.12.", "Gesonderter Ausweis F")
            order.map { all.indexOf(it) }.let { idx -> (idx.all { it >= 0 } && idx == idx.sorted()) shouldBe true }
        }

        test("the rows show legal form, seat, share and the computed result; a missing figure says so") {
            all shouldContain "GmbH"
            all shouldContain "Berlin"
            all shouldContain "25,00"
            all shouldContain "600,00"
            all shouldContain "-20,00"
            all.lines().single { it.contains("-Gastro") } shouldContain "n. b."
        }

        test("Cyrillic is replaced (WinAnsi) and hostile names do not break the file") {
            all shouldNotContain "Иван"
            all shouldContain "Medien"
            all shouldContain "=SUM(A1) Verlag"
        }

        test("the sum row stands under its unit and says 'nicht berechenbar' for the missing income") {
            all.lines().any { it.contains("Summe") && it.contains("580,00") && it.contains("n. b.") } shouldBe true
        }

        test("no adjustment against R1 appears in the PDF") {
            all shouldNotContain "stimmt überein"
            all shouldNotContain "weicht ab"
        }

        test("an empty D says so") {
            pdfTextOf(PartgReportPdfRenderer().render(ReportFixtures.docR3b())) shouldContain "Keine Einträge erfasst"
        }

        test("the same document gives the same bytes") {
            PartgReportPdfRenderer().render(ReportFixtures.docR3c()).contentEquals(bytes) shouldBe true
        }
    })

class PartgReportR3cXlsxTest :
    FunSpec({
        val xlsx = readXlsx(PartgReportXlsxWriter.write(ReportFixtures.docR3c()))

        test("the sheet 'Ausweis D' is the 9th of 13 sheets, directly after C") {
            xlsx.sheetNames.size shouldBe 13
            xlsx.sheetNames[8] shouldBe "Ausweis D"
            xlsx.sheetNames[7] shouldBe "Ausweis C"
            xlsx.sheetNames[9] shouldBe "Mitglieder"
        }

        test("formula starters in the name are inline strings with the quote prefix and no cell is a formula") {
            val d = xlsx.sheets.getValue("Ausweis D")
            val hostile =
                d.values.filter {
                    it.value.startsWith("=SUM") ||
                        it.value.startsWith("+1 ") ||
                        it.value.startsWith("-Gastro") ||
                        it.value.startsWith("@x")
                }
            hostile.size shouldBe 4
            hostile.forEach {
                it.type shouldBe "inlineStr"
                it.style shouldBe PartgReportXlsxWriter.S_QUOTE
            }
            xlsx.parts
                .filterKeys { it.startsWith("xl/worksheets/") }
                .values
                .forEach { String(it, Charsets.UTF_8) shouldNotContain "<f>" }
        }

        test("amounts are numbers with the euro format; the negative result stays negative; the share is a fraction") {
            val d = xlsx.sheets.getValue("Ausweis D")
            d.values.any { it.type == "n" && it.value == "-20.00" && it.style == PartgReportXlsxWriter.S_EURO } shouldBe true
            d.values.any { it.type == "n" && it.value == "0.2500" && it.style == PartgReportXlsxWriter.S_PERCENT } shouldBe true
        }

        test("the sum row has bold euro cells and the text 'nicht berechenbar' where a figure is missing") {
            val d = xlsx.sheets.getValue("Ausweis D")
            d.values.any { it.type == "n" && it.value == "580.00" && it.style == PartgReportXlsxWriter.S_BOLD_EURO } shouldBe true
            d.values.count { it.value == "nicht berechenbar" } shouldBe 3 // result of -Gastro, income sum, result sum
        }

        test("the completeness sheet carries only the count of enterprises, no amount, no placeholder") {
            val texts =
                xlsx.sheets
                    .getValue("Vollständigkeit")
                    .values
                    .map { it.value }
            ("Ausweis D: 4 Unternehmen erfasst" in texts) shouldBe true
            texts.none { it.contains("nicht erfasst. Beteiligungen") } shouldBe true
        }

        test("D holds no E05 value that does not come from the D rows") {
            val d =
                xlsx.sheets
                    .getValue("Ausweis D")
                    .values
                    .filter { it.type == "n" }
                    .map { it.value }
            val e05 =
                ReportFixtures
                    .entered()
                    .getValue(ReportFixtures.YEAR)
                    .getValue(PartgUnit.FEDERAL_KEY)
                    .getValue("E05")
            val e05Text =
                java.math.BigDecimal
                    .valueOf(e05, 2)
                    .toPlainString()
            (e05Text in d) shouldBe false
        }

        test("the single-unit export has the same thirteen sheets, and the same document gives the same bytes") {
            readXlsx(
                PartgReportXlsxWriter.write(ReportFixtures.docR3c(PartgReportScope.Single(PartgUnit.Federal))),
            ).sheetNames.size shouldBe
                13
            PartgReportXlsxWriter
                .write(ReportFixtures.docR3c())
                .contentEquals(PartgReportXlsxWriter.write(ReportFixtures.docR3c())) shouldBe true
        }
    })

/** P11..P13 (V1.9.287). */
class PartgPlausibilityR3cTest :
    FunSpec({
        val year = ReportFixtures.YEAR
        val units = ReportFixtures.units()

        fun findings(
            enterprises: List<network.lapis.cloud.server.partg.PartgStatementStore.EnterpriseRow>,
            entered: Map<String, Long>,
            withAnnex: Boolean = true,
        ): List<PartgFindingDto> {
            val base = ReportFixtures.annexData().getValue(year).getValue(PartgUnit.FEDERAL_KEY)
            val annex: Map<Int, Map<String, PartgAnnexStore.AnnexData>> =
                if (withAnnex) mapOf(year to mapOf(PartgUnit.FEDERAL_KEY to base.copy(enterprises = enterprises))) else emptyMap()
            return PartgPlausibility.check(
                year = year,
                units = units,
                entered = mapOf(year to mapOf(PartgUnit.FEDERAL_KEY to entered)),
                annex = annex,
                notes = ReportFixtures.notesData(),
            )
        }

        fun byCode(
            list: List<PartgFindingDto>,
            code: PartgFindingCode,
        ) = list.filter { it.code == code }

        fun row(
            income: Long?,
            expenses: Long?,
        ) = ReportFixtures.enterprise(name = "X", income = income, expenses = expenses)

        test("P11 only when the difference is strictly greater than 100 cents; E05 and A02F are checked separately") {
            val within = findings(listOf(row(100_100L, 50_100L)), mapOf("E05" to 100_000L, "A02F" to 50_000L))
            byCode(within, PartgFindingCode.P11) shouldBe emptyList()
            val over = findings(listOf(row(100_101L, 50_000L)), mapOf("E05" to 100_000L, "A02F" to 50_000L))
            val p11 = byCode(over, PartgFindingCode.P11).single()
            p11.positionCode shouldBe "E05"
            p11.level shouldBe PartgFindingLevel.CHECK
            p11.rowNumbers shouldBe emptyList()
            val under = findings(listOf(row(100_000L, 49_899L)), mapOf("E05" to 100_000L, "A02F" to 50_000L))
            byCode(under, PartgFindingCode.P11).single().positionCode shouldBe "A02F"
        }

        test("P11 also fires when the position is not entered in the report, and not when a figure is missing") {
            byCode(findings(listOf(row(5L, 5L)), emptyMap()), PartgFindingCode.P11).map { it.positionCode } shouldContainExactly
                listOf("A02F", "E05")
            byCode(findings(listOf(row(null, 5L)), mapOf("E05" to 1L, "A02F" to 5L)), PartgFindingCode.P11) shouldBe emptyList()
        }

        test("P12 lists the rows without complete figures") {
            val result = findings(listOf(row(1L, 1L), row(null, 1L), row(1L, null)), mapOf("E05" to 1L, "A02F" to 1L))
            byCode(result, PartgFindingCode.P12).single().rowNumbers shouldContainExactly listOf(2, 3)
            byCode(result, PartgFindingCode.P12).single().level shouldBe PartgFindingLevel.CHECK
        }

        test("P13 when E05 or A02F is above zero and D is empty, also without an annex row; not for an entered zero") {
            val both = findings(emptyList(), mapOf("E05" to 1L, "A02F" to 2L))
            byCode(both, PartgFindingCode.P13).map { it.positionCode } shouldContainExactly listOf("A02F", "E05")
            byCode(both, PartgFindingCode.P13).forEach { it.level shouldBe PartgFindingLevel.INFO }
            val noAnnex = findings(emptyList(), mapOf("E05" to 1L), withAnnex = false)
            byCode(noAnnex, PartgFindingCode.P13).map { it.positionCode } shouldContainExactly listOf("E05")
            byCode(findings(emptyList(), mapOf("E05" to 0L, "A02F" to 0L)), PartgFindingCode.P13) shouldBe emptyList()
            byCode(findings(listOf(row(1L, 1L)), mapOf("E05" to 1L)), PartgFindingCode.P13) shouldBe emptyList()
        }

        test("no finding of R3c carries a name or an amount (serialised DTO)") {
            val result =
                PartgPlausibility.check(
                    year = year,
                    units = units,
                    entered = ReportFixtures.entered(),
                    annex = ReportFixtures.annexDataR3c(),
                    notes = ReportFixtures.notesData(),
                )
            val json = result.joinToString { Json.encodeToString(PartgFindingDto.serializer(), it) }
            listOf("SUM(A1)", "Medien", "Gastro", "Immobilien", "100000", "58000").forEach { json shouldNotContain it }
            result.any { it.code in setOf(PartgFindingCode.P11, PartgFindingCode.P12, PartgFindingCode.P13) } shouldBe true
        }
    })
