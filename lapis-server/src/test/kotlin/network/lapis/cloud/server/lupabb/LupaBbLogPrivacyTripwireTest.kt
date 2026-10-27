package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.226 -- what a logger line of the sync may contain. Only the lambda form (so nothing is built when the level is off), never an exception
 * object, a message, a cause, an address, a name, a URL or a credential. The Kilua RPC layer logs every exception's message and cause chain, so
 * the typed exceptions are fixed texts (pinned in [LupaBbNoSecretLeakTest]).
 */
class LupaBbLogPrivacyTripwireTest :
    FunSpec({
        val files =
            SourceScan.mainFiles().filter {
                "/lupabb/" in it.path || it.name == "LupaBbSyncService.kt" || it.name == "LupaBbPersonalData.kt"
            }
        val forbiddenWord =
            Regex(
                """(?i)\b(email|password|apikey|api_key|username|displayname|credentials?|url|street|baseurl|token)\b|\.(message|cause|localizedMessage)\b|stackTrace""",
            )

        test("the scan sees the package") { (files.size >= 15) shouldBe true }

        test("every logger call uses the lambda form and names nothing sensitive") {
            val offenders =
                files.flatMap { file ->
                    SourceScan
                        .blank(file.readText())
                        .lines()
                        .withIndex()
                        .filter { (_, line) -> Regex("""\blogger\.\w+""").containsMatchIn(line) }
                        .filter { (_, line) ->
                            !Regex("""\blogger\.\w+\s*\{""").containsMatchIn(line) || forbiddenWord.containsMatchIn(line)
                        }.map { (i, _) -> "${file.name}:${i + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test("the raw text of the logger lines (strings are blanked above) names nothing sensitive either") {
            val offenders =
                files.flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> Regex("""\blogger\.\w+""").containsMatchIn(line) && !line.trimStart().startsWith("*") }
                        .filter { (_, line) -> forbiddenWord.containsMatchIn(line) }
                        .map { (i, _) -> "${file.name}:${i + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test("no println and no printStackTrace in the package") {
            files
                .filter { Regex("""\bprintln\(|printStackTrace""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("the Ktor client logging plugin is never installed and the application keeps io.ktor.client at INFO") {
            files.filter { Regex("""io\.ktor\.client\.plugins\.logging""").containsMatchIn(it.readText()) }.shouldBeEmpty()
            val logback =
                java.io.File("src/main/resources/logback.xml").takeIf { it.exists() }
                    ?: java.io.File("lapis-server/src/main/resources/logback.xml")
            Regex("""<logger name="io\.ktor\.client" level="INFO"\s*/>""").containsMatchIn(logback.readText()) shouldBe true
        }
    })
