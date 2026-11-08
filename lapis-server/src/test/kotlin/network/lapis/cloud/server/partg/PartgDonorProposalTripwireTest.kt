package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.domain.PartgDonorProposalAuditSnapshot

/**
 * V1.9.293 (R5b) -- the proposal for statement B touches personal data of third parties and a pseudonymous donor key. These scans pin: only
 * `PartgDonorMasterData` reads `external_donor` inside the party-report package, only `PartgAnnexStore` writes the two new columns (and only
 * `PartgAnnexWriter` calls the three write functions), and no key, name or amount reaches a log line or an exception text.
 */
class PartgDonorProposalTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val partgFiles = files.filter { "/partg/" in it.path.replace('\\', '/') }

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("inside the package partg only PartgDonorMasterData names ExternalDonorTable") {
            partgFiles
                .filter { Regex("""\bExternalDonorTable\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus("PartgDonorMasterData.kt")
                .shouldBeEmpty()
        }

        test("the two new columns are written only by the annex store") {
            files
                .filter { "/db/generated/" !in it.path }
                .filter { Regex("""\bit\[(sourceDonorKey|sourceAppliedAt)]\s*=""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldContainExactlyInAnyOrder("PartgAnnexStore.kt")
            files
                .filter {
                    Regex(
                        """\b(insertDonorFromLedger|replaceDonorAmountFromLedger|linkDonor)\b""",
                    ).containsMatchIn(SourceScan.blank(it.readText()))
                }.map { it.name }
                .toSet()
                .minus(setOf("PartgAnnexStore.kt", "PartgAnnexWriter.kt"))
                .shouldBeEmpty()
        }

        test("the donor key and the donor fields are never in a log line or an exception text") {
            /** The R5b region of a file: the text between two markers of the blanked source. */
            fun region(
                file: String,
                from: String,
                to: String?,
            ): String {
                val text = SourceScan.blank(files.single { it.name == file }.readText())
                val start = text.indexOf(from)
                check(start >= 0) { "marker missing in $file" }
                return text.substring(start, if (to == null) text.length else text.indexOf(to, start).also { check(it >= 0) })
            }

            val regions =
                mapOf(
                    "PartgDonorProposals.kt" to region("PartgDonorProposals.kt", "internal object PartgDonorProposals", null),
                    "PartgDonorMasterData.kt" to region("PartgDonorMasterData.kt", "internal object PartgDonorMasterData", null),
                    "PartgAnnexWriter.kt" to region("PartgAnnexWriter.kt", "fun <T> applyDonorProposal(", "fun <T> saveNotes("),
                    "PartgLedgerReader.kt" to region("PartgLedgerReader.kt", "fun federalDonationTotals(", "private const val ID_CHUNK"),
                    "PartyReportService.kt" to
                        region(
                            "PartyReportService.kt",
                            "override suspend fun largeDonorProposal",
                            "private fun consolidationWithin",
                        ),
                )
            val forbidden = Regex("""\b(ref|key|Key|name|street|city|postalCode|Cents|amount|toString|id)\b|\$""")
            val offending =
                regions.flatMap { (file, text) ->
                    text
                        .lines()
                        .filter { "logger." in it || "Exception(" in it }
                        .map { line -> if ("Exception(" in line) "Exception(" + line.substringAfter("Exception(") else line }
                        .filter { forbidden.containsMatchIn(it.replace(Regex("""Exception\("[A-Z_a-z ]*"\)"""), "Exception()")) }
                        .map { "$file: ${it.trim()}" }
                }
            offending.shouldBeEmpty()
            regions.filterKeys { it in setOf("PartgDonorProposals.kt", "PartgDonorMasterData.kt") }.values.none { "logger." in it } shouldBe
                true
        }

        test("the audit snapshot carries counters and revisions only") {
            PartgDonorProposalAuditSnapshot::class.java.declaredFields
                .filter {
                    !it.isSynthetic &&
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                }.map { it.name }
                .shouldContainExactlyInAnyOrder(
                    "year",
                    "unitKey",
                    "created",
                    "replaced",
                    "linked",
                    "annexRevisionBefore",
                    "annexRevisionAfter",
                )
        }
    })
