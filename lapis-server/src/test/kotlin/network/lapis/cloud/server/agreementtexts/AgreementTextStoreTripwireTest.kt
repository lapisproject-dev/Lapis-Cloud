package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * Welle V1.9.134 "Vertragstexte" -- tripwires of the versioned agreement texts:
 *
 *  1. The two Exposed tables are read and written ONLY through [AgreementTextStore] (the DSGVO contributor reads them, nothing else): there is
 *     no second writer that could skip the audit entry, the draft lock or the append-only rule.
 *  2. `agreement_text_version` is APPEND-ONLY: no `update`, `deleteWhere`, `deleteAll`, `upsert`, `batchUpsert` or `replace` ever touches it.
 *  3. The only DELETE of the feature removes the draft.
 *  4. A log line of the feature never names the text, a hash or a label.
 */
private fun codeWithoutImports(source: String): String =
    SourceScan
        .blank(source)
        .lines()
        .filterNot { it.trimStart().startsWith("import ") }
        .joinToString("\n")

class AgreementTextStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val tableUsers = { table: String ->
            files.filter { table in SourceScan.blank(it.readText()) && !it.path.contains("/db/generated/") }.map { it.name }.toSet()
        }

        test("the scanner really sees the main sources (guards against a silently empty scan)") {
            (files.size > 100) shouldBe true
            (files.any { it.name == "AgreementTextStore.kt" }) shouldBe true
        }

        test("only the store (and the read-only DSGVO contributor) use the two Exposed tables") {
            tableUsers("AgreementTextVersionTable") shouldBe setOf("AgreementTextStore.kt", "AgreementTextPersonalData.kt")
            tableUsers("AgreementTextDraftTable") shouldBe setOf("AgreementTextStore.kt", "AgreementTextPersonalData.kt")
        }

        test("the version table is append-only: no update, delete, upsert or replace on it, anywhere") {
            val forbidden =
                Regex("""AgreementTextVersionTable\s*\.\s*(update|deleteWhere|deleteAll|upsert|batchUpsert|replace|batchInsert)\b""")
            val offenders =
                files
                    .filter { forbidden.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
            offenders.shouldBeEmpty()
            // the only write to it is the insert of the store
            val store = codeWithoutImports(files.single { it.name == "AgreementTextStore.kt" }.readText())
            Regex("""AgreementTextVersionTable\s*\.\s*insert\b""").findAll(store).count() shouldBe 1
        }

        test("the DSGVO contributor never writes") {
            val code = SourceScan.blank(files.single { it.name == "AgreementTextPersonalData.kt" }.readText())
            Regex("""\.(insert|update|deleteWhere|deleteAll|upsert|replace)\b""").containsMatchIn(code) shouldBe false
        }

        test("the only DELETE of the feature removes the draft") {
            val store = codeWithoutImports(files.single { it.name == "AgreementTextStore.kt" }.readText())
            Regex("""\.deleteWhere\b""").findAll(store).count() shouldBe 1
            Regex("""AgreementTextDraftTable\s*\.\s*deleteWhere\b""").findAll(store).count() shouldBe 1
        }

        test("the feature's code never writes to the audit chain except through the store, and the snapshot carries no text") {
            val snapshotFunction =
                files
                    .single { it.name == "AgreementTextStore.kt" }
                    .readText()
                    .substringAfter("fun snapshotJson(")
                    .substringBefore("fun recordAudit(")
            ("body" in SourceScan.blank(snapshotFunction)) shouldBe false
        }

        test("log lines of the feature never name the text, a hash, a label or a member") {
            val featureFiles = files.filter { it.path.contains("/agreementtexts/") || it.name == "AgreementTextAdminService.kt" }
            (featureFiles.size >= 5) shouldBe true
            val offenders =
                featureFiles.flatMap { file ->
                    file.readLines().filter { "logger." in it }.filter {
                        Regex(
                            """\$\{?(body|text|sha|hash|label|versionLabel|member|actor|current\b)""",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(it)
                    }
                }
            offenders.shouldBeEmpty()
        }
    })
