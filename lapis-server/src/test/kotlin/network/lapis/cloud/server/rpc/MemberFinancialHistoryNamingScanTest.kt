package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- structural, source-text-scan regression guard, same
 * "structural coverage test, not a behavioral one" idiom as [PaymentsRegressionScanTest]/
 * [CrmRegressionScanTest]. Durchsetzt das Namensverbot der Design-Entscheidung: weder
 * "Lebenszeitwert" noch "lifetime value"/"LifetimeValue" darf irgendwo in dieser Domäne auftauchen
 * -- see `MemberFinancialHistoryDto` KDoc "Namensverbot".
 */
class MemberFinancialHistoryNamingScanTest :
    FunSpec({
        val repoRoot = resolveRepoRoot()
        val forbiddenPattern = Regex("""(?i)(lebenszeitwert|lifetime[ _-]?value)""")

        // `MemberFinancialHistoryDto`'s own KDoc "Namensverbot" paragraph must literally name the
        // two forbidden terms to document the ban in the first place -- that ONE line is the
        // canonical rule statement, not a violation of it. Recognized by carrying the marker word
        // "Namensverbot" on the SAME line, so an actual accidental rename elsewhere in this file
        // (which would not carry that marker) still fails the scan.
        fun isRuleStatementLine(line: String): Boolean = line.contains("Namensverbot")

        test("das Wort 'Lebenszeitwert'/'lifetime value' erscheint nirgends in lapis-shared/lapis-server/lapis-client Quelltext") {
            val sourceDirs =
                listOf(
                    File(repoRoot, "lapis-shared/src/commonMain"),
                    File(repoRoot, "lapis-server/src/main"),
                    File(repoRoot, "lapis-client/src/jsMain"),
                )
            val offenders =
                sourceDirs
                    .filter { it.exists() }
                    .flatMap { dir ->
                        dir
                            .walkTopDown()
                            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
                            .flatMap { file ->
                                file.readLines().mapIndexedNotNull { index, line ->
                                    if (forbiddenPattern.containsMatchIn(line) && !isRuleStatementLine(line)) {
                                        "${file.path}:${index + 1}: $line"
                                    } else {
                                        null
                                    }
                                }
                            }
                    }
            offenders.shouldBeEmpty()
        }

        test("das Wort 'Lebenszeitwert'/'lifetime value' erscheint nirgends in den acht i18n-Katalogen") {
            val i18nDir = File(repoRoot, "lapis-client/src/jsMain/resources/modules/i18n")
            val offenders =
                (i18nDir.listFiles { f -> f.extension == "po" || f.extension == "pot" } ?: emptyArray())
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (forbiddenPattern.containsMatchIn(line) && !isRuleStatementLine(line)) {
                                "${file.path}:${index + 1}: $line"
                            } else {
                                null
                            }
                        }
                    }
            offenders.shouldBeEmpty()
        }

        test("das Wort 'Lebenszeitwert'/'lifetime value' erscheint nirgends unter docs/") {
            val docsDir = File(repoRoot, "docs")
            val offenders =
                if (!docsDir.exists()) {
                    emptyList()
                } else {
                    docsDir
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "adoc" }
                        .flatMap { file ->
                            file.readLines().mapIndexedNotNull { index, line ->
                                if (forbiddenPattern.containsMatchIn(line) && !isRuleStatementLine(line)) {
                                    "${file.path}:${index + 1}: $line"
                                } else {
                                    null
                                }
                            }
                        }.toList()
                }
            offenders.shouldBeEmpty()
        }

        test("MemberFinancialHistoryDto deklariert kein Feld, dessen Name 'total' ohne Größenqualifikation trägt") {
            val dtoFile = File(repoRoot, "lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/MemberFinancialHistory.kt")
            val bareTotalFieldPattern = Regex("""val\s+total\s*:""")
            val offenders =
                dtoFile.readLines().mapIndexedNotNull { index, line ->
                    if (bareTotalFieldPattern.containsMatchIn(line)) "${dtoFile.path}:${index + 1}: $line" else null
                }
            offenders.shouldBeEmpty()
        }
    })

/** Mirrors [PaymentsRegressionScanTest]'s own `resolveModuleDir` -- works whether the test process's working directory is the repo root or `lapis-server` itself. */
private fun resolveRepoRoot(): File {
    val fromHere = File(".")
    if (File(fromHere, "lapis-shared").exists()) return fromHere
    val fromParent = File("..")
    if (File(fromParent, "lapis-shared").exists()) return fromParent
    error("could not resolve repo root from working directory ${fromHere.absolutePath}")
}
