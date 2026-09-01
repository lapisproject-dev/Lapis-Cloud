package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import java.io.File

/**
 * Structural, source-text-scan regression guards for Welle V1.2.1 "Zahlungs-Fundament" (vault plan
 * "Lapis Cloud V1.2 -- Zahlungsverkehr" § 8.9/§ 9.6/§ 0.12) -- same "structural coverage test, not a
 * behavioral one" idiom as `AuditLogImmutabilityTest`/`PersonalDataCoverageTest`. Scoped to what is
 * actually meaningful THIS sub-wave: no SEPA IBAN/PSP-secret-handling code exists yet (that is
 * V1.2.2/V1.2.4), so the corresponding scans arrive with THOSE waves -- see each test's own KDoc for
 * why it is/is not in scope now.
 */
class PaymentsRegressionScanTest :
    FunSpec({
        val sharedMainDir = resolveModuleDir("lapis-shared/src/commonMain/kotlin")
        val serverMainDir = resolveModuleDir("lapis-server/src/main/kotlin")
        val clientMainDir = resolveModuleDir("lapis-client/src/jsMain/kotlin")

        // Plan § 9.6 "Audit-Log darf niemals Zahlungsdaten aufnehmen" -- forward-looking guard: no
        // *Snapshot type in AuditLog.kt may EVER carry a field that looks like it holds account/card
        // data. Nothing in V1.2.1 violates this today (ContributionPostingBridge reuses the EXISTING
        // JournalEntrySnapshot, which only ever carried ledgerAccountId/side/amount/sphere/
        // costCenterId) -- this test exists so a LATER wave (V1.2.2's SepaMandateSnapshot, V1.2.4's
        // PaymentTransactionSnapshot) cannot silently add one.
        test("no *Snapshot data class in AuditLog.kt declares a field named iban/bic/sealed/payload/card/pan") {
            val auditLogFile = File(sharedMainDir, "network/lapis/cloud/shared/domain/AuditLog.kt")
            require(auditLogFile.exists()) { "AuditLog.kt not found at ${auditLogFile.absolutePath}" }
            val forbiddenNameFragments = listOf("iban", "bic", "sealed", "payload", "card", "pan")
            val fieldDeclarationPattern = Regex("""val\s+(\w+)\s*:""")

            val offenders =
                auditLogFile.readLines().mapIndexedNotNull { index, line ->
                    val match = fieldDeclarationPattern.find(line) ?: return@mapIndexedNotNull null
                    val fieldName = match.groupValues[1].lowercase()
                    val hit = forbiddenNameFragments.firstOrNull { fieldName.contains(it) }
                    if (hit != null) "AuditLog.kt:${index + 1}: field '${match.groupValues[1]}' matches forbidden fragment '$hit'" else null
                }
            offenders.shouldBeEmpty()
        }

        // Plan § 3.6 "PCI-DSS-Abgrenzung" -- no card-data field/variable name anywhere in the three
        // Kotlin modules. Meaningful NOW even though no checkout/card-adjacent code exists yet
        // (V1.2.4): it is cheap to assert today and catches the very first violation immediately,
        // same "guard exists before the feature that could violate it" posture this test class as a
        // whole takes.
        test("no cardNumber/pan/cvv/cvc/expiryMonth/expiryYear identifier anywhere in lapis-shared/lapis-server/lapis-client main source") {
            val forbiddenPattern = Regex("""\b(cardNumber|pan|cvv|cvc|expiryMonth|expiryYear)\b""", RegexOption.IGNORE_CASE)
            val offenders =
                listOf(sharedMainDir, serverMainDir, clientMainDir)
                    .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            // "company" contains no forbidden fragment, but guard against a bare
                            // substring match like "pandas"/"panel" by requiring word boundaries
                            // (already enforced by \b above) -- "companyIdentificationCode" etc.
                            // legitimately never match.
                            if (forbiddenPattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                        }
                    }
            offenders.shouldBeEmpty()
        }

        // Plan § 0.12 "LTR-Ledger: strikt getrennt, bleibt getrennt" -- echtes Geld darf niemals in
        // ltr_ledger_entry landen. Structural guard: none of this wave's new files import the LTR
        // ledger's own table/enum types, and the enum itself has not silently grown a Euro-adjacent
        // literal.
        test("no V1.2.1/V1.2.2/V1.2.8 payments file imports LtrLedgerEntryTable/LtrLedgerEntryType/LtrLedgerReferenceType") {
            val paymentsFiles =
                listOf(
                    "ContributionPostingBridge.kt",
                    "SepaComplianceDisclaimer.kt",
                    "SepaService.kt",
                    "PaymentGatewayComplianceDisclaimer.kt",
                    "PaymentGatewayService.kt",
                    "DonationPostingBridge.kt",
                ).map { File(serverMainDir, "network/lapis/cloud/server/rpc/$it") } +
                    File(serverMainDir, "network/lapis/cloud/server/dsgvo/PaymentsPersonalData.kt") +
                    File(serverMainDir, "network/lapis/cloud/server/routes/PspWebhookRoutes.kt") +
                    (
                        File(serverMainDir, "network/lapis/cloud/server/payment/sepa").listFiles { f ->
                            f.extension == "kt"
                        } ?: emptyArray()
                    ).toList() +
                    (
                        File(serverMainDir, "network/lapis/cloud/server/payment/psp").listFiles { f ->
                            f.extension == "kt"
                        } ?: emptyArray()
                    ).toList()
            val forbiddenPatterns = listOf("LtrLedgerEntryTable", "LtrLedgerEntryType", "LtrLedgerReferenceType")

            val offenders =
                paymentsFiles.flatMap { file ->
                    require(file.exists()) { "expected payments file not found: ${file.absolutePath}" }
                    file.readLines().mapIndexedNotNull { index, line ->
                        val hit = forbiddenPatterns.firstOrNull { line.contains(it) }
                        if (hit != null) "${file.path}:${index + 1}: matched '$hit'" else null
                    }
                }
            offenders.shouldBeEmpty()
        }

        // Welle V1.2.2 "SEPA-Lastschriftmandate" -- forward-looking guard, same "structural coverage
        // test" idiom as the two tests above: no server/payment/sepa/ file (nor SepaService.kt/
        // SepaRoutes.kt) ever interpolates a variable whose name suggests it holds an IBAN,
        // ciphertext, secret, key, or decrypted plaintext into a logger.* call -- the full IBAN must
        // never reach a log line, see SepaService KDoc rule 5 "the full IBAN never leaves this class".
        test("no Sepa* server file interpolates an iban/ciphertext/secret/key/plaintext-named variable in a logger.* call") {
            val sepaFiles =
                (File(serverMainDir, "network/lapis/cloud/server/payment/sepa").listFiles { f -> f.extension == "kt" } ?: emptyArray())
                    .toList() +
                    listOf(
                        File(serverMainDir, "network/lapis/cloud/server/rpc/SepaService.kt"),
                        File(serverMainDir, "network/lapis/cloud/server/routes/SepaRoutes.kt"),
                    )
            val loggerLinePattern =
                Regex("""logger\.\w+\s*\{[^}]*\$\{?\w*(iban|ciphertext|secret|key|plaintext)\w*""", RegexOption.IGNORE_CASE)
            val offenders =
                sepaFiles.flatMap { file ->
                    if (!file.exists()) return@flatMap emptyList()
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (loggerLinePattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                    }
                }
            offenders.shouldBeEmpty()
        }

        // Welle V1.2.8 "PSP-Checkout (Stripe)" -- forward-looking guard, same idiom as the Sepa* scan
        // above: no file under server/payment/psp/ nor PspWebhookRoutes.kt ever interpolates a
        // variable whose name suggests it holds a secret/key/signature/token/bearer value into a
        // logger.* call. Security-review checklist item 3 "Secret leakage".
        test("no psp file interpolates a secret/key/signature/token/bearer-named variable in a logger.* call") {
            val pspFiles =
                (File(serverMainDir, "network/lapis/cloud/server/payment/psp").listFiles { f -> f.extension == "kt" } ?: emptyArray())
                    .toList() +
                    File(serverMainDir, "network/lapis/cloud/server/routes/PspWebhookRoutes.kt")
            val loggerLinePattern =
                Regex("""logger\.\w+\s*\{[^}]*\$\{?\w*(secret|key|signature|token|bearer)\w*""", RegexOption.IGNORE_CASE)
            val offenders =
                pspFiles.flatMap { file ->
                    if (!file.exists()) return@flatMap emptyList()
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (loggerLinePattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                    }
                }
            offenders.shouldBeEmpty()
        }

        // Welle V1.2.8 -- PspConfig.load() is documented as the ONLY place any LAPIS_STRIPE_* value
        // is ever read (see that class's own KDoc "Read location"). A second reader would risk a
        // second, drifting validation path for the same secret.
        test("no System.getenv(\"LAPIS_STRIPE\") call exists outside PspConfig.kt") {
            val pattern = Regex("""System\.getenv\(\s*"LAPIS_STRIPE""")
            val offenders =
                listOf(sharedMainDir, serverMainDir, clientMainDir)
                    .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "PspConfig.kt" } }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (pattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                        }
                    }
            offenders.shouldBeEmpty()
        }

        test("LtrLedgerEntryType is unchanged by Welle V1.2.1 -- still exactly its pre-existing literal count") {
            // Not a hardcoded plan-authored number (the plan's own § 0.12 count may have drifted by
            // the time this wave landed, see its own file header disclaimer elsewhere) -- this pins
            // the count AS OF THIS WAVE so a future accidental Euro-adjacent addition to THIS enum
            // is caught the same way the other regression guards in this class are.
            LtrLedgerEntryType.entries.size shouldBe 13
        }
    })

/**
 * Resolves [relativePath] (e.g. `"lapis-shared/src/commonMain/kotlin"`) whether the test process's
 * working directory is the repo root or `lapis-server` itself -- same "try both, `require`/`error`
 * if neither exists" idiom [KumlModelLoader.kumlSourceDir] already establishes for the single-module
 * case.
 */
private fun resolveModuleDir(relativePath: String): File {
    val fromRepoRoot = File(relativePath)
    if (fromRepoRoot.exists()) return fromRepoRoot
    val fromModuleDir = File("../$relativePath")
    if (fromModuleDir.exists()) return fromModuleDir
    error("could not resolve '$relativePath' from working directory ${File(".").absolutePath}")
}
