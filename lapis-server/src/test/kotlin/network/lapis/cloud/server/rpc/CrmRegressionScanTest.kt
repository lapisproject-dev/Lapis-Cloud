package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- structural, source-text-scan regression
 * guards, same "structural coverage test, not a behavioral one" idiom as
 * [PaymentsRegressionScanTest]/`network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest`.
 */
class CrmRegressionScanTest :
    FunSpec({
        val serverMainDir = resolveModuleDir("lapis-server/src/main/kotlin")
        val crmDir = File(serverMainDir, "network/lapis/cloud/server/crm")
        val crmPersonalDataFile = File(serverMainDir, "network/lapis/cloud/server/dsgvo/CrmPersonalData.kt")
        val crmServiceFile = File(serverMainDir, "network/lapis/cloud/server/rpc/CrmService.kt")
        val crmRoutesFile = File(serverMainDir, "network/lapis/cloud/server/routes/CrmRoutes.kt")

        // Plan §5 "Kein zweiter Löschpfad" -- the ONLY place either CRM table is ever DELETEd is
        // CrmPersonalData.kt (Art. 17 erasure). A `.deleteWhere`/raw `DELETE FROM` against either
        // table anywhere else (e.g. a well-intentioned "cleanup helper" in CrmContactStore/
        // CrmService) would be a second, unaudited deletion path.
        test("no .deleteWhere against CrmContactTable/CrmInteractionTable outside dsgvo/CrmPersonalData.kt") {
            val filesToScan =
                (crmDir.listFiles { f -> f.extension == "kt" } ?: emptyArray()).toList() +
                    crmServiceFile +
                    crmRoutesFile
            val pattern = Regex("""(CrmContactTable|CrmInteractionTable)\.deleteWhere""")
            val offenders =
                filesToScan.flatMap { file ->
                    if (!file.exists()) return@flatMap emptyList()
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (pattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                    }
                }
            offenders.shouldBeEmpty()
        }

        // Plan §5 "Kein zweiter Löschpfad" -- same guard, the other direction: CrmPersonalData.kt
        // itself must never issue an .update against either table (its only writes are the two
        // deleteWhere calls in the CRM_CONTACT/MEMBER erase branches).
        test("CrmPersonalData.kt never .update()s CrmContactTable/CrmInteractionTable") {
            val pattern = Regex("""(CrmContactTable|CrmInteractionTable)\.update\(""")
            val offenders =
                crmPersonalDataFile.readLines().mapIndexedNotNull { index, line ->
                    if (pattern.containsMatchIn(line)) "${crmPersonalDataFile.path}:${index + 1}: $line" else null
                }
            offenders.shouldBeEmpty()
        }

        // Plan §7.7(b) "Kein Update-Pfad auf crm_interaction" -- append-only outside CrmPersonalData
        // erasure. CrmContactStore.recordInteraction only ever INSERTs into CrmInteractionTable and
        // UPDATEs CrmContactTable's denormalized columns -- never CrmInteractionTable itself.
        test("no .update() against CrmInteractionTable anywhere in lapis-server main source") {
            val pattern = Regex("""CrmInteractionTable\.update\(""")
            val offenders =
                serverMainDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (pattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                        }
                    }.toList()
            offenders.shouldBeEmpty()
        }

        // Plan §5 "PII in Logs/Exceptions" -- crm/*, CrmService.kt, CrmRoutes.kt never interpolate a
        // PII-bearing field into a logger.* call or an Exception(...) literal; contacts are
        // referenced by UUID only.
        test(
            "no CRM file interpolates displayName/email/phone/summary/consentSource/street/city/postalCode in a logger.* call or Exception literal",
        ) {
            val filesToScan =
                (crmDir.listFiles { f -> f.extension == "kt" } ?: emptyArray()).toList() +
                    crmPersonalDataFile +
                    crmServiceFile +
                    crmRoutesFile
            val forbiddenFragments = listOf("displayName", "email", "phone", "summary", "consentSource", "street", "city", "postalCode")
            val loggerOrExceptionPattern =
                Regex(
                    """(logger\.\w+\s*\{[^}]*\$\{?\w*(""" + forbiddenFragments.joinToString("|") + """)\w*|Exception\([^)]*\$\{?\w*(""" +
                        forbiddenFragments.joinToString("|") +
                        """)\w*)""",
                )
            val offenders =
                filesToScan.flatMap { file ->
                    if (!file.exists()) return@flatMap emptyList()
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (loggerOrExceptionPattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                    }
                }
            offenders.shouldBeEmpty()
        }
    })

/** Mirrors [PaymentsRegressionScanTest]'s own `resolveModuleDir` -- works whether the test process's working directory is the repo root or `lapis-server` itself. */
private fun resolveModuleDir(relativePath: String): File {
    val fromRepoRoot = File(relativePath)
    if (fromRepoRoot.exists()) return fromRepoRoot
    val fromModuleDir = File("../$relativePath")
    if (fromModuleDir.exists()) return fromModuleDir
    error("could not resolve '$relativePath' from working directory ${File(".").absolutePath}")
}
