package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgSponsorQualification
import network.lapis.cloud.shared.domain.PartgUnit
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

private fun pdfText(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { pdf -> PDFTextStripper().apply { sortByPosition = true }.getText(pdf) }

/** V1.9.283 (R3b) -- statements C and F in the assembled document. */
class PartgReportR3bAssemblerTest :
    FunSpec({
        val doc = ReportFixtures.docR3b()

        test("a sponsor under both limits is not in the report; the others carry the right reason") {
            doc.annex.sponsors
                .map { it.name }
                .none { it == "Unter den Grenzen" } shouldBe true
            doc.annex.sponsors.associate { it.name to it.qualification }.let {
                it["=cmd|' /C calc'!A0"] shouldBe PartgSponsorQualification.SINGLE
                it["Jahres GmbH"] shouldBe PartgSponsorQualification.YEARLY
                it["Beide & Söhne <AG>"] shouldBe PartgSponsorQualification.BOTH
            }
        }

        test("only the services above 750.00 EUR are listed singly; the row number is the position in the full list of the unit") {
            val first = doc.annex.sponsors.single { it.name.startsWith("=cmd") }
            first.singleEntries.map { it.amountCents } shouldContainExactly listOf(75_001L)
            first.entryCount shouldBe 2
            first.yearlySumCents shouldBe 85_001L
            // sorted by yearly sum: Beide (700,000), Jahres (675,000), Unter den Grenzen (599,992), =cmd (85,001): the sponsor under both limits
            // is row 3, so the exported rows are 1, 2 and 4 -- the numbers of the user interface, with the gap
            doc.annex.sponsors.map { it.rowNumber } shouldContainExactly listOf(1, 2, 4)
        }

        test("a youth organisation without figures has no result; with both figures the result is income minus expenses") {
            doc.annex.youthOrgs
                .single { it.name == "-Jugend Alpha" }
                .resultCents shouldBe 100_000L
            doc.annex.youthOrgs
                .single { it.name == "Jugend Beta" }
                .resultCents shouldBe null
        }

        test("without statement data C and F are empty") {
            ReportFixtures.doc().annex.sponsors shouldBe emptyList()
            ReportFixtures.docR3().annex.youthOrgs shouldBe emptyList()
        }
    })

class PartgReportR3bPdfTest :
    FunSpec({
        val doc = ReportFixtures.docR3b()
        val bytes = PartgReportPdfRenderer().render(doc)
        val all = pdfText(bytes)

        test("C, the placeholder of D and F have their own headings, in this order, E in between") {
            all shouldContain "Ausweis C: Sponsoring (Entwurf)"
            all shouldContain "Ausweis D (Unternehmenstätigkeit) ist in diesem Entwurf nicht erfasst"
            all shouldContain "Ausweis F: Jugendorganisationen (Entwurf)"
            val order =
                listOf(
                    "Gesonderter Ausweis B",
                    "Gesonderter Ausweis C",
                    "Ausweis D: nicht erfasst",
                    "Mitgliederzahl zum 31.12.",
                    "Gesonderter Ausweis F",
                )
            order.map { all.indexOf(it) }.let { idx ->
                (idx.all { it >= 0 } && idx == idx.sorted()) shouldBe true
            }
        }

        test("the heading carries no section number: the official layout is not verified") {
            all.lines().filter { it.contains("Ausweis C") || it.contains("Ausweis F") }.forEach { it shouldNotContain "§" }
        }

        test("an exported sponsor shows the reason and the single service; a sponsor under both limits is absent") {
            all shouldContain "Einzelleistung"
            all shouldContain "Jahressumme"
            all shouldContain "beides"
            all shouldContain "750,01"
            all shouldNotContain "Unter den Grenzen"
        }

        test("Cyrillic is replaced (WinAnsi), Umlauts survive") {
            all shouldNotContain "Иван"
            all shouldContain "Söhne"
        }

        test("a youth organisation without a result says so, never a zero") {
            all shouldContain "Jugend Beta"
            all shouldContain "1.000,00"
            // both figures missing: 'n. e.' for the figures, 'n. b.' (not computable) for the result
            all.lines().single { it.contains("Jugend Beta") } shouldContain "n. b."
        }

        test("empty C and F say so") {
            val empty = pdfText(PartgReportPdfRenderer().render(ReportFixtures.doc()))
            empty.split("Keine Einträge erfasst").size - 1 shouldBe 3 // B, C, F
        }

        test("the same document gives the same bytes") {
            PartgReportPdfRenderer().render(ReportFixtures.docR3b()).contentEquals(bytes) shouldBe true
        }

        test("the page limit also bounds the new sections") {
            io.kotest.assertions.throwables.shouldThrow<network.lapis.cloud.server.pdf.PdfLimitExceeded> {
                PartgReportPdfRenderer(maxPages = 2).render(ReportFixtures.docR3b())
            }
        }
    })

class PartgReportR3bXlsxTest :
    FunSpec({
        val xlsx = readXlsx(PartgReportXlsxWriter.write(ReportFixtures.docR3b()))

        test("twelve sheets in the order of the statutes; D has no sheet of its own") {
            xlsx.sheetNames shouldContainExactly
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
            xlsx.sheetNames.size shouldBe 12
        }

        test("the completeness sheet says that D is not recorded") {
            xlsx.sheets.getValue("Vollständigkeit").values.any {
                it.value.startsWith("Ausweis D (Unternehmenstätigkeit) ist in diesem Entwurf nicht erfasst")
            } shouldBe
                true
        }

        test("formula starters in name, purpose and relationship are inline strings with the quote prefix, and no cell is a formula") {
            val c = xlsx.sheets.getValue("Ausweis C")
            val f = xlsx.sheets.getValue("Ausweis F")
            val hostile =
                c.values.filter { it.value.startsWith("=cmd") } +
                    f.values.filter { it.value.startsWith("=Vereinigung") || it.value.startsWith("-Jugend") }
            hostile.size shouldBe 3
            hostile.forEach {
                it.type shouldBe "inlineStr"
                it.style shouldBe PartgReportXlsxWriter.S_QUOTE
            }
            // purpose cells start with "Zweck: ", so they cannot start with a formula character at all
            c.values.filter { it.value.startsWith("Zweck: ") }.size shouldBe 2
            xlsx.parts
                .filterKeys { it.startsWith("xl/worksheets/") }
                .values
                .forEach { String(it, Charsets.UTF_8) shouldNotContain "<f>" }
        }

        test("sponsors under both limits are absent; reason texts and the single services appear") {
            val values =
                xlsx.sheets
                    .getValue("Ausweis C")
                    .values
                    .map { it.value }
            values.none { it == "Unter den Grenzen" } shouldBe true
            listOf("Einzelleistung", "Jahressumme", "beides").forEach { (it in values) shouldBe true }
            values.any { it.startsWith("Datum: 01.03.2025") } shouldBe true
        }

        test("amounts are numbers with the euro format; Cyrillic stays as typed") {
            val c = xlsx.sheets.getValue("Ausweis C")
            c.values.any { it.value == "Иван" || it.value.contains("Иван") } shouldBe true
            c.values.first { it.style == PartgReportXlsxWriter.S_EURO }.type shouldBe "n"
        }

        test("a missing youth figure shows 'nicht erfasst', the result without both figures 'nicht berechenbar', with both a number") {
            val f = xlsx.sheets.getValue("Ausweis F")
            f.values.any { it.type == "n" && it.value == "1000.00" } shouldBe true
            f.values.count { it.value == "nicht erfasst" } shouldBe 2
            f.values.count { it.value == "nicht berechenbar" } shouldBe 1
        }

        test("the same document gives the same bytes") {
            PartgReportXlsxWriter
                .write(
                    ReportFixtures.docR3b(),
                ).contentEquals(PartgReportXlsxWriter.write(ReportFixtures.docR3b())) shouldBe
                true
        }

        test("the single-unit export has the same twelve sheets") {
            readXlsx(
                PartgReportXlsxWriter.write(ReportFixtures.docR3b(PartgReportScope.Single(PartgUnit.Federal))),
            ).sheetNames.size shouldBe
                12
        }
    })

/** P07..P10 (V1.9.283): positive and negative cases, the numbering of the full list, and the content rule. */
class PartgPlausibilityR3bTest :
    FunSpec({
        val year = ReportFixtures.YEAR
        val units = ReportFixtures.units()

        fun findings(
            sponsors: List<network.lapis.cloud.server.partg.PartgStatementStore.SponsorRow> = emptyList(),
            youth: List<network.lapis.cloud.server.partg.PartgStatementStore.YouthOrgRow> = emptyList(),
        ): List<PartgFindingDto> {
            val base = ReportFixtures.annexData().getValue(year).getValue(PartgUnit.FEDERAL_KEY)
            val data = mapOf(year to mapOf(PartgUnit.FEDERAL_KEY to base.copy(sponsors = sponsors, youthOrgs = youth)))
            return PartgPlausibility.check(
                year = year,
                units = units,
                entered = emptyMap(),
                annex = data,
                notes = ReportFixtures.notesData(),
            )
        }

        fun byCode(
            list: List<PartgFindingDto>,
            code: PartgFindingCode,
        ) = list.filter { it.code == code }

        test("P07 for a sponsor without any address, P08 for a partial address, no finding for a complete one") {
            val sorted =
                listOf(
                    ReportFixtures.sponsor(
                        name = "Ohne",
                        entries = listOf(ReportFixtures.entry(cents = 900_000L)),
                        street = null,
                        postal = null,
                        city = null,
                    ),
                    ReportFixtures.sponsor(name = "Teil", entries = listOf(ReportFixtures.entry(cents = 800_000L)), street = null),
                    ReportFixtures.sponsor(name = "Voll", entries = listOf(ReportFixtures.entry(cents = 700_000L))),
                ).let(network.lapis.cloud.server.partg.PartgStatementStore::sortSponsors)
            val result = findings(sponsors = sorted)
            byCode(result, PartgFindingCode.P07).single().rowNumbers shouldContainExactly listOf(1)
            byCode(result, PartgFindingCode.P08).single().rowNumbers shouldContainExactly listOf(2)
            byCode(result, PartgFindingCode.P07).single().level shouldBe PartgFindingLevel.CHECK
            byCode(result, PartgFindingCode.P09) shouldBe emptyList()
        }

        test(
            "P09 (info) for a sponsor under both limits, with the row number of the full list; the boundary values are not under the limits",
        ) {
            val list =
                network.lapis.cloud.server.partg.PartgStatementStore.sortSponsors(
                    listOf(
                        ReportFixtures.sponsor(name = "Gross", entries = listOf(ReportFixtures.entry(cents = 900_000L))),
                        ReportFixtures.sponsor(name = "Grenze einzeln", entries = listOf(ReportFixtures.entry(cents = 75_000L))),
                        ReportFixtures.sponsor(name = "Grenze Jahr", entries = List(8) { ReportFixtures.entry(cents = 75_000L) }),
                        ReportFixtures.sponsor(name = "Knapp drueber", entries = listOf(ReportFixtures.entry(cents = 75_001L))),
                    ),
                )
            val p09 = byCode(findings(sponsors = list), PartgFindingCode.P09).single()
            p09.level shouldBe PartgFindingLevel.INFO
            // the single service of exactly 750.00 EUR is under the limit; eight of them sum to exactly 6,000.00 EUR: also under the limit
            p09.rowNumbers.size shouldBe 2
            list.filter { it.name in setOf("Grenze einzeln", "Grenze Jahr") }.map { list.indexOf(it) + 1 }.sorted() shouldBe p09.rowNumbers
        }

        test("P10 for a youth organisation with neither income nor expenses, not for one with a zero") {
            val list =
                network.lapis.cloud.server.partg.PartgStatementStore.sortYouthOrgs(
                    listOf(
                        network.lapis.cloud.server.partg.PartgStatementStore.YouthOrgRow(
                            id = kotlin.uuid.Uuid.random(),
                            annexId = kotlin.uuid.Uuid.random(),
                            name = "A",
                            relationship = null,
                            incomeCents = null,
                            expensesCents = null,
                        ),
                        network.lapis.cloud.server.partg.PartgStatementStore.YouthOrgRow(
                            id = kotlin.uuid.Uuid.random(),
                            annexId = kotlin.uuid.Uuid.random(),
                            name = "B",
                            relationship = null,
                            incomeCents = 0L,
                            expensesCents = null,
                        ),
                        network.lapis.cloud.server.partg.PartgStatementStore.YouthOrgRow(
                            id = kotlin.uuid.Uuid.random(),
                            annexId = kotlin.uuid.Uuid.random(),
                            name = "C",
                            relationship = null,
                            incomeCents = null,
                            expensesCents = 0L,
                        ),
                    ),
                )
            byCode(findings(youth = list), PartgFindingCode.P10).single().rowNumbers shouldContainExactly listOf(1)
        }

        test("no finding of R3b carries a name, a purpose or an amount (serialised DTO and field names)") {
            val result = findings(sponsors = ReportFixtures.federalSponsors(), youth = ReportFixtures.federalYouthOrgs())
            val json = result.joinToString { Json.encodeToString(PartgFindingDto.serializer(), it) }
            listOf("cmd", "Jahres", "Beide", "Unter den", "Jugend", "Zweck", "75001", "700000").forEach { json shouldNotContain it }
            PartgFindingDto::class.java.declaredFields.map { it.name }.none {
                it in
                    setOf("name", "amountCents", "purpose", "street", "city")
            } shouldBe
                true
        }

        test("without sponsors and youth organisations there is no P07..P10") {
            val result = findings()
            listOf(PartgFindingCode.P07, PartgFindingCode.P08, PartgFindingCode.P09, PartgFindingCode.P10).forEach {
                byCode(result, it) shouldBe emptyList()
            }
        }

        test("the overall limit of 2,000 hints holds with mixed hints") {
            val many =
                List(
                    2_500,
                ) { i ->
                    ReportFixtures.sponsor(
                        name = "S$i",
                        entries = listOf(ReportFixtures.entry(cents = 1L)),
                        street = null,
                        postal = null,
                        city = null,
                    )
                }
            val result = findings(sponsors = many)
            (result.size <= network.lapis.cloud.shared.domain.PartgAnnexRules.MAX_FINDINGS) shouldBe true
        }
    })
