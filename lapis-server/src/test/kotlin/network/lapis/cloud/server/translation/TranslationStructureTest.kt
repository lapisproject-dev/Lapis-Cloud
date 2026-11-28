package network.lapis.cloud.server.translation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.ai.llm.LlmFailureKind
import network.lapis.cloud.server.config.SecretEnv
import network.lapis.cloud.server.translation.config.TranslateConfig
import network.lapis.cloud.server.translation.config.TranslateStartupCheck
import org.slf4j.LoggerFactory
import java.io.File

private val mainRoot: File =
    File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }.resolve("network/lapis/cloud/server")
private val translationDir: File = mainRoot.resolve("translation")
private val repoRoot: File = File("..").canonicalFile

private fun translationSources(): List<Pair<String, String>> {
    check(translationDir.isDirectory) { "translation source dir not found: ${translationDir.absolutePath}" }
    return translationDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map {
            it.relativeTo(translationDir).path to
                it.readText()
        }.toList()
}

private fun allMainSources(): List<Pair<String, String>> =
    mainRoot
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map {
            it.relativeTo(mainRoot).path.replace('\\', '/') to
                it.readText()
        }.toList()

/** Source scan guards of the translation layer (the layer is a package, not a module, so the compiler cannot enforce these rules). */
class TranslationStructureTest :
    FunSpec({
        test("the scan sees the sources (guard against a silently empty scan)") {
            (translationSources().size >= 12) shouldBe true
            (allMainSources().size > 500) shouldBe true
        }

        test("no Koog and no Ktor Logging plugin in the translation package") {
            translationSources()
                .flatMap { (_, t) ->
                    t.lines().filter { it.startsWith("import ") }
                }.filter { it.contains("ai.koog") }
                .shouldBeEmpty()
            translationSources()
                .filter { (_, t) -> t.contains("io.ktor.client.plugins.logging") || Regex("""install\(\s*Logging""").containsMatchIn(t) }
                .shouldBeEmpty()
        }

        test("only ContentTranslationStore knows ContentTranslationTable") {
            val offenders =
                allMainSources()
                    .filter { (path, t) -> t.contains("ContentTranslationTable") }
                    .map { it.first }
                    .filterNot { it == "translation/ContentTranslationStore.kt" || it == "db/generated/ContentTranslationTable.kt" }
            offenders shouldBe emptyList()
        }

        test("only EventTranslationConsent writes event.translation_consent*") {
            val write = Regex("""\[(EventTable\.)?translationConsent(By|At)?\]\s*=[^=]""")
            val offenders =
                allMainSources()
                    .filter { (_, t) -> t.contains("EventTable") && t.lines().any { write.containsMatchIn(it) } }
                    .map { it.first }
                    .filterNot { it == "translation/EventTranslationConsent.kt" }
            offenders.shouldBeEmpty()
            translationSources().single { it.first == "EventTranslationConsent.kt" }.second.lines().any {
                write.containsMatchIn(
                    it,
                )
            } shouldBe
                true
        }

        test("only EventStore writes the four translatable event columns, and its hooks are in place") {
            val write = Regex("""\[EventTable\.(title|summary|description|coverImageAlt)\]\s*=[^=]""")
            val offenders =
                allMainSources()
                    .filter { (_, t) -> t.contains("EventTable") && t.lines().any { write.containsMatchIn(it) } }
                    .map { it.first }
                    .filterNot { it == "events/EventStore.kt" }
            offenders.shouldBeEmpty()
            val store = allMainSources().single { it.first == "events/EventStore.kt" }.second
            store shouldContain "ContentTranslationStore.deleteStale("
            val hardDelete = store.substringAfter("fun deleteEventHard(")
            hardDelete shouldContain "ContentTranslationStore.deleteForContent("
            hardDelete.indexOf("ContentTranslationStore.deleteForContent(") shouldBe
                hardDelete.indexOf("ContentTranslationStore.deleteForContent(")
            (hardDelete.indexOf("ContentTranslationStore.deleteForContent(") < hardDelete.indexOf("EventTable.deleteWhere")) shouldBe true
            store.substringAfter("fun setStatus(").substringBefore("fun setCoverImageId") shouldContain
                "ContentTranslationStore.deleteForContent("
        }

        test("no ARTICLE content type and no import from the articles package") {
            TranslatableContentType.entries.map { it.name } shouldBe listOf("EVENT")
            translationSources()
                .filter { (_, t) ->
                    t.lines().any { it.startsWith("import ") && it.contains(".articles.") }
                }.shouldBeEmpty()
        }

        test("the prompt is never assembled by string templates around field values") {
            val prompt = translationSources().single { it.first == "TranslationPrompt.kt" }.second
            prompt shouldContain "buildJsonObject"
            Regex(
                """\$\{?(text|value|field|description|title|summary)""",
            ).containsMatchIn(prompt.substringAfter("fun userContent")) shouldBe
                false
        }

        test("the model is called in exactly one place, under the semaphore and outside every transaction block") {
            val service = translationSources().single { it.first == "TranslationService.kt" }.second
            service.lines().count { it.contains("client.complete(") } shouldBe 1
            service shouldContain "callPermits.withPermit {"
            // the consent/visibility/hash re-check runs inside the permit, directly before the call (outside the transaction block it opens)
            val serviceLines = service.lines()
            val permitAt = serviceLines.indexOfFirst { it.contains("callPermits.withPermit {") }
            val callAt = serviceLines.indexOfFirst { it.contains("client.complete(") }
            val recheckAt =
                serviceLines.indexOfFirst {
                    it.contains(
                        "stillAllowed(key = key, snapshot = snapshot, current = currentSnapshot(",
                    )
                }
            (recheckAt in (permitAt + 1) until callAt) shouldBe true
            // no other file calls the client at all
            translationSources()
                .filter { it.first != "TranslationService.kt" }
                .filter { (_, t) ->
                    t.contains(".complete(")
                }.shouldBeEmpty()
            // the line is not nested in an open transaction: the nearest preceding block opener at lower indentation is not `transaction`
            val lines = service.lines()
            val index = lines.indexOfFirst { it.contains("client.complete(") }
            val indent = lines[index].takeWhile { it == ' ' }.length
            var open = index - 1
            while (open >= 0 && (lines[open].isBlank() || lines[open].takeWhile { it == ' ' }.length >= indent)) open--
            lines[open] shouldNotContain "transaction"
        }

        test("neither compose file forwards LAPIS_TRANSLATE_") {
            listOf("deploy/example/docker-compose.yml", "deploy/local/docker-compose.yml").forEach { relative ->
                val file = File(repoRoot, relative)
                check(file.exists()) { "expected compose file at ${file.absolutePath}" }
                file.readText() shouldNotContain "LAPIS_TRANSLATE_"
            }
        }

        test("the API key is a secret name (so _FILE works)") {
            SecretEnv.SECRET_NAMES shouldContain TranslateConfig.ENV_API_KEY
        }

        test("the INFO floor for io.ktor.client in logback.xml is unchanged") {
            val logback =
                File("src/main/resources/logback.xml")
                    .let {
                        if (it.exists()) it else File("lapis-server/src/main/resources/logback.xml")
                    }.readText()
            logback shouldContain """<logger name="io.ktor.client" level="INFO" />"""
        }

        test("the API key header is still set in exactly the two provider clients") {
            val writers =
                allMainSources()
                    .filter { (_, t) -> t.contains("\"x-api-key\"") || t.contains("\"Authorization\"") }
                    .map { it.first }
                    .filter { it.startsWith("ai/") || it.startsWith("translation/") }
                    .toSet()
            writers shouldBe setOf("ai/llm/AnthropicMessagesLlmClient.kt", "ai/llm/OpenAiCompatibleLlmClient.kt")
        }
    })

class TranslationSecretsNotLoggedTest :
    FunSpec({
        val secret = "sk-translate-super-secret-key"

        fun <T> capture(block: () -> T): Pair<T, List<String>> {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val previous = root.level
            root.level = Level.ALL
            root.addAppender(appender)
            try {
                val result = block()
                return result to appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
            } finally {
                root.detachAppender(appender)
                root.level = previous
            }
        }

        test("the startup log names provider, model and host, never the key and never the path") {
            val env =
                mapOf(
                    TranslateConfig.ENV_ENABLED to "true",
                    TranslateConfig.ENV_PROVIDER to "openai_compatible",
                    TranslateConfig.ENV_MODEL to "model-x",
                    TranslateConfig.ENV_API_KEY to secret,
                    TranslateConfig.ENV_BASE_URL to "https://gateway.example.eu/private/path/v1",
                )
            val (_, messages) = capture { TranslateStartupCheck.log(TranslateConfig.load { env[it] }) }
            val translation = messages.filter { it.startsWith("Uebersetzung") }
            (translation.isNotEmpty()) shouldBe true
            translation.forEach {
                it shouldNotContain secret
                it shouldNotContain "/private/path"
            }
            translation.joinToString(" ") shouldContain "gateway.example.eu"
            translation.joinToString(" ") shouldContain "/v1"
        }

        test("no log statement or exception in the translation package mentions the key, the prompt, the answer or the URL") {
            val emitting = Regex("""(logger\.\w+|println|throw\s+\w+)""")
            val forbidden =
                Regex("""apiKey|api_key|API_KEY|baseUrl|systemPrompt|userContent|\braw\b|translatedText""", RegexOption.IGNORE_CASE)
            val offenders =
                translationSources().flatMap { (path, text) ->
                    text
                        .lines()
                        .withIndex()
                        .filter { (_, line) ->
                            emitting.containsMatchIn(line) &&
                                forbidden.containsMatchIn(line) &&
                                !line.trimStart().startsWith("*")
                        }.map { "$path:${it.index + 1}: ${it.value.trim()}" }
                }
            offenders.shouldBeEmpty()
        }

        test("a failing call whose exception carries the key, a timeout and a bad answer never put the key or the text into a log") {
            val fixtures = TranslationFixtures()
            network.lapis.cloud.server.db.DatabaseConfig
                .connect()
            try {
                val id = fixtures.event(description = "Geheimer Beschreibungstext 123")
                val llm = ScriptedLlm { error("provider says key=$secret") }
                val service =
                    TranslationService(config = TranslateConfig.operationalForTest(apiKey = secret, waitTimeoutMs = 20_000), llm = llm)
                val (_, messages) =
                    capture {
                        kotlinx.coroutines.runBlocking {
                            service.translateEvent(eventId = id, preferences = listOf("en"))
                            // a validation failure: the model answers with prose
                            val id2 = fixtures.event(description = "Zweiter geheimer Text 456")
                            val llm2 =
                                ScriptedLlm {
                                    network.lapis.cloud.server.ai.llm.LlmResult
                                        .Success(text = "Hallo $secret", tokensIn = 1, tokensOut = 1)
                                }
                            TranslationService(
                                config = TranslateConfig.operationalForTest(apiKey = secret, waitTimeoutMs = 20_000),
                                llm = llm2,
                            ).use {
                                it.translateEvent(eventId = id2, preferences = listOf("en"))
                            }
                            // an upstream failure kind
                            val id3 = fixtures.event()
                            TranslationService(
                                config = TranslateConfig.operationalForTest(apiKey = secret, waitTimeoutMs = 20_000),
                                llm =
                                    ScriptedLlm {
                                        failure(LlmFailureKind.TIMEOUT)
                                    },
                            ).use { it.translateEvent(eventId = id3, preferences = listOf("en")) }
                        }
                    }
                service.close()
                messages.forEach {
                    it shouldNotContain secret
                    it shouldNotContain "Geheimer Beschreibungstext"
                    it shouldNotContain "geheimer Text"
                }
            } finally {
                fixtures.cleanup()
            }
        }
    })
