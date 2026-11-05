package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.269 (R3) -- the large-donor rows are personal data of third parties. The party-report code must never put a name, a street, a city, a
 * postal code or a note text into a log line or an exception message, and never log an exception together with its cause.
 */
class PartgReportPrivacyTripwireTest :
    FunSpec({
        val scanned =
            SourceScan.mainFiles().filter {
                val p = it.path.replace('\\', '/')
                "/server/partg/" in p || it.name == "PartyReportService.kt" || it.name == "PartgReportExportRoutes.kt"
            }
        val sensitive =
            Regex("""\b(name|street|city|postalCode|body|addressLine|donor|donors|sponsor|sponsors|purpose|relationship|entries)\b""")

        // raw text: the interpolated names live INSIDE string literals, which SourceScan.blank erases. Comment lines are dropped.
        fun codeLines(text: String): List<String> =
            text.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

        test("the scan sees the party-report code") { scanned.size shouldBeGreaterThan 10 }

        test("no log statement interpolates a name, an address or a note text") {
            scanned
                .flatMap { file ->
                    codeLines(file.readText()).filter { "logger." in it && sensitive.containsMatchIn(it) }.map {
                        file.name + ": " +
                            it.trim()
                    }
                }.shouldBeEmpty()
        }

        test("no log statement passes an exception as its first argument") {
            scanned
                .flatMap { file ->
                    codeLines(
                        file.readText(),
                    ).filter { Regex("""logger\.(warn|error|info|debug)\(\s*(e|ex|t|it)\b""").containsMatchIn(it) }.map { file.name }
                }.shouldBeEmpty()
        }

        // `it.name` is the NAME OF AN ENUM CODE (PartgAnnexViolation), the only thing allowed in a message
        test("no exception is constructed from an input field") {
            scanned
                .flatMap { file ->
                    codeLines(file.readText())
                        .filter {
                            Regex(
                                """throw \w+Exception\(.*(?<!it\.)\b(name|street|city|postalCode|body|purpose|relationship)\b""",
                            ).containsMatchIn(it)
                        }.map { file.name + ": " + it.trim() }
                }.shouldBeEmpty()
        }
    })
