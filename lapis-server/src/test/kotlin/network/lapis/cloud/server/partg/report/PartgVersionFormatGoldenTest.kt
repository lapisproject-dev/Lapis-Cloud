package network.lapis.cloud.server.partg.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.partg.PartgVersionFormat
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import java.io.File

/**
 * V1.9.291 (R6) -- the stored format of a kept version is pinned by a golden file. A change of the report document model changes the bytes of this
 * document; then this test fails ON PURPOSE: bump `PartgVersionFormat.CURRENT`, keep a decoder for the old format (a kept version must stay readable
 * forever) and write the new golden file as `version-format-<n>.json`. Set the environment variable `PARTG_GOLDEN_WRITE=1` once to (re)write the file
 * for the CURRENT format; review the diff before committing it.
 */
class PartgVersionFormatGoldenTest :
    FunSpec({
        val file = File("src/test/resources/partg/version-format-${PartgVersionFormat.CURRENT}.json")

        fun document(): PartgReportDocument =
            PartgReportAssembler.assemble(
                organizationName = "Test Partei e.V.",
                year = ReportFixtures.YEAR,
                scope = PartgReportScope.All,
                units = ReportFixtures.units(),
                entered = ReportFixtures.entered(),
                annex = ReportFixtures.annexData(),
            )

        test("the golden file matches the bytes of the fixed document") {
            val encoded = PartgVersionFormat.encode(document())
            if (System.getenv("PARTG_GOLDEN_WRITE") == "1") {
                file.parentFile.mkdirs()
                file.writeText(encoded)
            }
            check(file.exists()) { "golden file missing: run once with PARTG_GOLDEN_WRITE=1" }
            val golden = file.readText()
            if (golden != encoded) {
                throw AssertionError(
                    "The stored form of the report document changed. Bump PartgVersionFormat.CURRENT and keep a decoder for the old " +
                        "format, then write the new golden file (PARTG_GOLDEN_WRITE=1). A kept version must stay readable forever.",
                )
            }
        }

        test("the golden file decodes to the fixed document and renders as PDF and XLSX") {
            check(file.exists()) { "golden file missing" }
            val decoded = PartgVersionFormat.decode(text = file.readText(), formatVersion = PartgVersionFormat.CURRENT)
            decoded shouldBe document()
            PartgReportPdfRenderer().render(document = decoded, footer = "Version 1").isNotEmpty() shouldBe true
            PartgReportXlsxWriter.write(document = decoded, footer = "Version 1").isNotEmpty() shouldBe true
        }

        test("building and encoding the same state twice gives the same bytes and the same hash") {
            val a = PartgVersionFormat.encode(document())
            val b = PartgVersionFormat.encode(document())
            a shouldBe b
            PartgVersionFormat.hash(a) shouldBe PartgVersionFormat.hash(b)
            PartgVersionFormat.hash(a).length shouldBe 64
        }

        test("an unknown format version and a damaged text are integrity errors, never a silent best effort") {
            shouldThrow<PartyReportVersionIntegrityException> { PartgVersionFormat.decode(text = "{}", formatVersion = 99) }
            shouldThrow<PartyReportVersionIntegrityException> {
                PartgVersionFormat.decode(text = "{not json", formatVersion = PartgVersionFormat.CURRENT)
            }
            shouldThrow<PartyReportVersionIntegrityException> {
                PartgVersionFormat.decode(text = file.readText().replace("\"ALL\"", "\"???\""), formatVersion = PartgVersionFormat.CURRENT)
            }
        }

        test("the stored text carries the figures and the scope key, and a hostile name stays plain text") {
            val text = PartgVersionFormat.encode(document())
            text shouldContain "\"scope\":\"ALL\""
            text shouldContain "=HYPERLINK"
        }
    })
