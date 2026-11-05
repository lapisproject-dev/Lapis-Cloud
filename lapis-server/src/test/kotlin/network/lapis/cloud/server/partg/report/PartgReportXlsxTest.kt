package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel

class PartgReportXlsxGoldenTest :
    FunSpec({
        val doc = ReportFixtures.doc()
        val bytes = PartgReportXlsxWriter.write(doc)
        val xlsx = readXlsx(bytes)

        test("the sheets have fixed names in a fixed order") {
            xlsx.sheetNames shouldBe
                listOf(
                    "Vollständigkeit",
                    "Zusammenfassung",
                    "Einnahmen",
                    "Ausgaben",
                    "Vermögen",
                    "Ausweis A",
                    "Ausweis B",
                    "Ausweis C",
                    "Mitglieder",
                    "Ausweis F",
                    "Erläuterungen",
                    "Hinweise",
                )
        }

        test("A1 of every sheet is the header line of the PDF") {
            xlsx.sheets.values.forEach {
                it.getValue("A1").value shouldBe "Test Partei e.V. · Rechenschaftsbericht 2025 · ENTWURF – nicht amtlich"
            }
        }

        test("amounts are real numbers with the euro format, shares are fractions with the percent format") {
            val summary = xlsx.sheets.getValue("Zusammenfassung")
            // row 3 title, 4 label, 5 header, 6 'Einnahmen', row 7 is Nr. 1 (E01): the consolidated cell is partial, hence a number
            val e01 = summary.getValue("C7")
            e01.type shouldBe "n"
            e01.style shouldBe PartgReportXlsxWriter.S_EURO
            e01.value.matches(Regex("""-?\d+\.\d{2}""")) shouldBe true
            // the partial whole-party cell has no reliable share
            summary.getValue("D7").value shouldBe "n. b."
            val full =
                readXlsx(
                    PartgReportXlsxWriter.write(
                        ReportFixtures.doc(scope = PartgReportScope.Single(PartgUnit.State(ReportFixtures.CHAPTER_A))),
                    ),
                ).sheets.getValue("Zusammenfassung")
            full.getValue("D7").style shouldBe PartgReportXlsxWriter.S_PERCENT
            full.getValue("D7").value.startsWith("0.") shouldBe true
        }

        test("a value that was not entered is the text 'nicht erfasst', a partial sum has a number and a note") {
            val expenses = xlsx.sheets.getValue("Ausgaben")
            val texts = expenses.values.filter { it.type == "inlineStr" }.map { it.value }
            texts.contains("nicht erfasst") shouldBe true
            val summary = xlsx.sheets.getValue("Zusammenfassung")
            summary.values.any { it.value.contains("unvollständig") } shouldBe true
        }

        test("a negative result stays a negative number") {
            val federalPage =
                ReportFixtures.doc(scope = PartgReportScope.Single(PartgUnit.Federal))
            val sheet = readXlsx(PartgReportXlsxWriter.write(federalPage)).sheets.getValue("Zusammenfassung")
            // RES is the last expense line: title 3, label 4, header 5, 'Einnahmen' 6, 12 lines (7-18), 'Ausgaben' 19, 5 lines (20-24)
            sheet.getValue("C24").value.startsWith("-") shouldBe true
        }

        test("the matrix is in the order of the units plus the whole party, current year block first") {
            val income = xlsx.sheets.getValue("Einnahmen")
            income.getValue("A7").value shouldBe "Bundesverband"
            income.getValue("A12").value shouldBe "Gesamtpartei"
            income.values.any { it.value == "Vorjahr 2024" } shouldBe true
        }

        test("the same document gives the same bytes") {
            PartgReportXlsxWriter.write(ReportFixtures.doc()).contentEquals(bytes) shouldBe true
        }

        test("no formula, no shared strings, no macro part, plain sheet content type") {
            xlsx.parts.keys.none { it.contains("vbaProject") || it.contains("sharedStrings") } shouldBe true
            xlsx.parts.forEach { (name, data) ->
                if (name.endsWith(".xml")) Regex("<f[ >]").containsMatchIn(String(data, Charsets.UTF_8)) shouldBe false
            }
            String(xlsx.parts.getValue("[Content_Types].xml"), Charsets.UTF_8) shouldContain "spreadsheetml.sheet.main+xml"
        }
    })

class PartgReportXlsxInjectionTest :
    FunSpec({
        val hostile =
            listOf(
                "=HYPERLINK(\"http://x\")",
                "+1",
                "-1",
                "@SUM(A1)",
                "\tx",
                "\rx",
                "<script>alert(1)</script>",
                "a]]>b",
                "a\u0000b\u0001c",
            )

        test("hostile names are inline strings, formula starters carry the quotePrefix style, nothing is a formula") {
            hostile.forEach { name ->
                val units =
                    listOf(
                        ReportFixtures.unitDto(level = PartgUnitLevel.FEDERAL),
                        ReportFixtures.unitDto(level = PartgUnitLevel.STATE, chapter = ReportFixtures.CHAPTER_A, name = name),
                    )
                val doc = ReportFixtures.doc(org = name, units = units)
                val bytes = PartgReportXlsxWriter.write(doc)
                val xlsx = readXlsx(bytes) // well-formed (DTD-free StAX parse)
                xlsx.parts.forEach { (part, data) ->
                    if (part.endsWith(".xml")) {
                        val xml = String(data, Charsets.UTF_8)
                        xml.contains("<f>") shouldBe false
                        xml.contains("<f ") shouldBe false
                    }
                }
                val a1 = xlsx.sheets.getValue("Zusammenfassung").getValue("A1")
                a1.type shouldBe "inlineStr"
                // A1 starts with the organisation name: formula starters must be quote-prefixed
                val first =
                    name
                        .replace('\t', ' ')
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .firstOrNull()
                if (first != null && first in "=+-@") a1.style shouldBe PartgReportXlsxWriter.S_QUOTE
                val unitCell =
                    xlsx.sheets.getValue("Einnahmen").values.first {
                        it.type == "inlineStr" &&
                            it.value.startsWith("Landesverband")
                    }
                unitCell.type shouldBe "inlineStr"
                unitCell.value.contains("\u0000") shouldBe false
            }
        }
    })
