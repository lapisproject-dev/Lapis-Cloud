package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgConsolidationStore
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnit
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.uuid.Uuid

private fun pdfPages(bytes: ByteArray): List<String> =
    Loader.loadPDF(bytes).use { pdf ->
        (1..pdf.numberOfPages).map { n ->
            PDFTextStripper()
                .apply {
                    startPage = n
                    endPage = n
                    sortByPosition = true
                }.getText(pdf)
        }
    }

/** The text of a page without the footer's "Seite x von y" (the total changes when a section is appended). */
private fun withoutPageCount(text: String): String = text.replace(Regex("""Seite \d+ von \d+"""), "")

/** V1.9.290 (R5) -- the consolidation in the PDF and the XLSX: appended after everything else, 16 sheets for the whole party, 13 for one unit, no formula, n. b. as text. */
class PartgReportR5ExportTest :
    FunSpec({
        val year = ReportFixtures.YEAR
        val units = ReportFixtures.units()
        val federal = PartgUnit.FEDERAL_KEY
        val stateA = PartgUnit.State(ReportFixtures.CHAPTER_A).key()
        val entered = ReportFixtures.entered()

        fun stateDonor() =
            PartgAnnexStore.DonorRow(
                id = Uuid.random(),
                annexId = Uuid.random(),
                personKind = PartgPersonKind.NATURAL,
                name = "=HYPERLINK Zweitzeile",
                street = "Weg 2",
                postalCode = "38100",
                city = "Ort",
                country = "DE",
                contributionKind = PartgContributionKind.FEE,
                amountCents = 1_100_000L,
            )

        val annex: Map<Int, Map<String, PartgAnnexStore.AnnexData>> =
            run {
                val base = ReportFixtures.annexData().getValue(year)
                val topFederal = base.getValue(federal).donors.first()
                val second = stateDonor()
                mapOf(
                    year to
                        mapOf(
                            federal to base.getValue(federal),
                            stateA to base.getValue(stateA).copy(donors = listOf(second)),
                        ),
                ) to Pair(topFederal.id, second.id)
            }.let { (map, ids) ->
                groupIds = ids
                map
            }

        fun consolidated(scope: PartgReportScope = PartgReportScope.All): PartgReportDocument {
            val data =
                mapOf(
                    year to
                        PartgConsolidationStore.ConsolidationData(
                            revision = 3,
                            transfers =
                                listOf(
                                    PartgConsolidationStore.TransferRow(
                                        id = Uuid.random(),
                                        kind = PartgTransferKind.SUBSIDY,
                                        payerUnitKey = federal,
                                        payeeUnitKey = stateA,
                                        payerAmountCents = 5_000L,
                                        payeeAmountCents = 4_000L,
                                        note = "Notiz darf nicht in den Export",
                                    ),
                                ),
                            combinations =
                                listOf(
                                    PartgConsolidationStore.CombinationRow(
                                        id = Uuid.random(),
                                        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                                        donorIds = listOf(groupIds.first, groupIds.second),
                                    ),
                                ),
                        ),
                )
            return PartgReportAssembler.assemble(
                organizationName = "Test Partei e.V.",
                year = year,
                scope = scope,
                units = units,
                entered = entered,
                annex = annex,
                consolidation = PartgConsolidation.compute(year = year, units = units, entered = entered, consolidation = data),
                consolidatedDonors =
                    PartgDonorConsolidation.compute(
                        units = units,
                        annex = annex.getValue(year),
                        combinations = data.getValue(year).combinations,
                    ),
            )
        }

        test("PDF: the consolidation is appended after everything else; the pages before it are unchanged") {
            val plain =
                pdfPages(
                    PartgReportPdfRenderer().render(
                        PartgReportAssembler.assemble(
                            organizationName = "Test Partei e.V.",
                            year = year,
                            scope = PartgReportScope.All,
                            units = units,
                            entered = entered,
                            annex = annex,
                        ),
                    ),
                )
            val withConsolidation = pdfPages(PartgReportPdfRenderer().render(consolidated()))
            (withConsolidation.size > plain.size) shouldBe true
            plain.indices.forEach { i -> withoutPageCount(withConsolidation[i]) shouldBe withoutPageCount(plain[i]) }
            val tail = withConsolidation.drop(plain.size).joinToString("\n")
            tail shouldContain "Gesamtpartei (konsolidiert) – Entwurf, nicht geprüft"
            tail shouldContain "Abgleich der Übertragungen"
            tail shouldContain "Ausweis B (konsolidiert)"
            tail shouldContain "Eliminiert EUR"
            tail shouldContain "weicht ab"
            // the group is one person: 9.000.000 + 1.100.000 cents = 101.000,00 EUR
            tail shouldContain "101.000,00"
            // the free-text note never leaves the system
            withConsolidation.joinToString("\n") shouldNotContain "Notiz darf nicht"
            // the draft marks of every page are still there
            withConsolidation.all { it.contains("ENTWURF") } shouldBe true
        }

        test("PDF: a single unit report has no consolidation") {
            val single =
                pdfPages(
                    PartgReportPdfRenderer().render(consolidated(PartgReportScope.Single(PartgUnit.Federal))),
                ).joinToString("\n")
            single shouldNotContain "Abgleich der Übertragungen"
            single shouldNotContain "Gesamtpartei (konsolidiert)"
        }

        test("PDF: no amount or header cell is cut off in the new tables") {
            val text = pdfPages(PartgReportPdfRenderer().render(consolidated())).joinToString("\n")
            // the long header of the first table is whole, and the unit labels of the matching table are not truncated with an ellipsis
            text shouldContain "Summe der Einheiten EUR"
            text shouldContain "Konsolidiert EUR"
            text shouldNotContain "..."
        }

        test("XLSX: 16 sheets for the whole party, 13 for one unit; the three new sheets come after 'Hinweise'") {
            val whole = readXlsx(PartgReportXlsxWriter.write(consolidated()))
            whole.sheetNames.size shouldBe 16
            whole.sheetNames.take(13).last() shouldBe "Hinweise"
            whole.sheetNames.drop(13) shouldContainExactly listOf("Konsolidierung", "Abgleich", "Ausweis B (konsolidiert)")
            readXlsx(PartgReportXlsxWriter.write(consolidated(PartgReportScope.Single(PartgUnit.Federal)))).sheetNames.size shouldBe 13
            readXlsx(PartgReportXlsxWriter.write(ReportFixtures.doc())).sheetNames.size shouldBe 13
        }

        test("XLSX: no formula anywhere, a text that starts with = is quote-prefixed, n. b. is text and never 0") {
            val xlsx = readXlsx(PartgReportXlsxWriter.write(consolidated()))
            xlsx.parts
                .filterKeys { it.startsWith("xl/worksheets/") }
                .values
                .forEach { String(it, Charsets.UTF_8) shouldNotContain "<f>" }
            val donors = xlsx.sheets.getValue("Ausweis B (konsolidiert)")
            val named = donors.values.first { it.value.startsWith("=HYPERLINK") }
            named.style shouldBe PartgReportXlsxWriter.S_QUOTE
            val positions = xlsx.sheets.getValue("Konsolidierung").values
            // state B and the summaries have no data and are excluded, so every cell that every included unit entered is a number; a missing value is text
            positions.any { it.value == "n. b." && it.type == "inlineStr" } shouldBe true
            positions.filter { it.value == "n. b." }.all { it.type == "inlineStr" } shouldBe true
            xlsx.sheets.values
                .flatMap { it.values }
                .none { it.value.contains("Notiz darf nicht") } shouldBe true
        }
    })

// the ids of the two donor rows of the shared fixture (set while the annex is built)
private var groupIds: Pair<Uuid, Uuid> = Uuid.random() to Uuid.random()
