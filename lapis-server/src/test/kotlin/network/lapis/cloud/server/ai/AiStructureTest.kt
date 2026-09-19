package network.lapis.cloud.server.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.config.AiStartupCheck
import network.lapis.cloud.server.ai.qa.AiTool
import org.slf4j.LoggerFactory
import java.io.File

private val aiSourceDir: File =
    File("src/main/kotlin/network/lapis/cloud/server/ai").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server/ai")
    }

private val repoRoot: File = File("..").canonicalFile

private val OWN_TABLES =
    setOf("AiKnowledgeReleaseTable", "AiKnowledgeIndexStateTable", "AiKnowledgeChunkTable", "AiMemberOptInTable", "AiCallAuditTable")

private fun sources(): List<Pair<String, String>> {
    check(aiSourceDir.isDirectory) { "AI source dir not found: ${aiSourceDir.absolutePath}" }
    return aiSourceDir
        .walkTopDown()
        .filter {
            it.isFile && it.extension == "kt"
        }.map { it.relativeTo(aiSourceDir).path to it.readText() }
        .toList()
}

private fun imports(text: String): List<String> = text.lines().filter { it.startsWith("import ") }

/**
 * Structural guard for the AI layer's boundary (R1-R4, see the KDoc of `AiLayerBoundary`).
 * A quelltext scan, same pattern as `AuditLogImmutabilityTest`/`PersonalDataCoverageTest` -- the AI
 * layer is a package, not a Gradle module, so the compiler cannot enforce these rules; this test does.
 */
class AiModuleBoundaryTest :
    FunSpec({
        test("the scan actually sees the source files (guard against a silently empty scan)") {
            (sources().size >= 20) shouldBe true
        }

        test("R1: qa/, llm/ and safety/ import no Exposed, no generated tables, no rpc layer") {
            val forbidden =
                listOf(
                    "import org.jetbrains.exposed",
                    "import network.lapis.cloud.server.db.generated",
                    "import network.lapis.cloud.server.rpc",
                )
            val offenders =
                sources()
                    .filter { (path, _) -> listOf("qa/", "llm/", "safety/").any { path.startsWith(it) } }
                    .flatMap { (path, text) -> imports(text).filter { line -> forbidden.any { line.startsWith(it) } }.map { "$path: $it" } }
            offenders.shouldBeEmpty()
            // ...and none of them opens a transaction either.
            sources()
                .filter { (path, _) -> listOf("qa/", "llm/", "safety/").any { path.startsWith(it) } }
                .filter { (_, text) -> Regex("""\btransaction\s*[({]""").containsMatchIn(text) }
                .map { it.first }
                .shouldBeEmpty()
        }

        test("R2: nothing below ai/ imports governance, market, visibility, payment or accounting code") {
            val denylist =
                listOf(
                    "LtrLedger",
                    "Auction",
                    "Election",
                    "Governance",
                    "SystemicConsensus",
                    "Politician",
                    "Crowdfunding",
                    "PeerTransfer",
                    "SocialNetwork",
                    "Boost",
                    "Vote",
                    "Ranking",
                    "PriceOracle",
                    "Payment",
                    "Sepa",
                    "Dunning",
                    "Accounting",
                )
            val offenders =
                sources().flatMap { (path, text) ->
                    imports(text).filter { line -> denylist.any { line.contains(it) } }.map { "$path: $it" }
                }
            offenders.shouldBeEmpty()
        }

        test("R3: nothing below ai/ writes to a table other than the five Ai* tables") {
            val write =
                Regex(
                    """\b(\w+Table)\s*\.\s*(insert|update|deleteWhere|batchInsert|upsert|deleteAll|deleteIgnoreWhere|replace|insertAndGetId)\b""",
                )
            val offenders =
                sources().flatMap { (path, text) ->
                    write
                        .findAll(text)
                        .map { it.groupValues[1] }
                        .filter { it !in OWN_TABLES }
                        .map { "$path writes $it" }
                        .toList()
                }
            offenders.shouldBeEmpty()
            // sanity: the scan does find the legitimate writers
            sources().any { (_, text) -> write.findAll(text).any { it.groupValues[1] in OWN_TABLES } } shouldBe true
        }

        test("R4: exactly one whitelisted tool, and no tool definition or dispatch loop in the pipeline") {
            AiTool.entries.size shouldBe 1
            val pipeline = sources().single { (path, _) -> path == "qa/StatuteQaPipeline.kt" }.second
            pipeline shouldNotContain "tool_use"
            pipeline shouldNotContain "tools ="
            Regex("""\b(while|for)\s*\(""").containsMatchIn(pipeline) shouldBe false
        }

        test("the layer has no Koog dependency (deliberate, see CHANGELOG/README)") {
            sources().flatMap { (_, text) -> imports(text) }.filter { it.contains("ai.koog") }.shouldBeEmpty()
        }
    })

class AiSecretsNotLoggedTest :
    FunSpec({
        test("no Ktor Logging plugin is installed or imported anywhere in the AI layer") {
            sources()
                .filter { (_, text) ->
                    text.contains("io.ktor.client.plugins.logging") ||
                        Regex("""install\(\s*Logging""").containsMatchIn(text)
                }.map { it.first }
                .shouldBeEmpty()
        }

        test("no log statement, exception or check message mentions the API key or interpolates the base URL") {
            val anyMessage = Regex("""(logger\.\w+|println|throw\s+\w+|error\(|require\(|check\(|IllegalArgumentException\()""")
            val emitting = Regex("""(logger\.\w+|println|throw\s+\w+)""")
            val key = Regex("""apiKey|api_key|API_KEY""", RegexOption.IGNORE_CASE)
            val url = Regex("""\$\{?\w*(baseUrl|urlString)""", RegexOption.IGNORE_CASE)
            val offenders =
                sources().flatMap { (path, text) ->
                    text
                        .lines()
                        .withIndex()
                        .filter { (_, line) ->
                            (anyMessage.containsMatchIn(line) && key.containsMatchIn(line)) ||
                                (emitting.containsMatchIn(line) && url.containsMatchIn(line))
                        }.map { "$path:${it.index + 1}: ${it.value.trim()}" }
                }
            offenders.shouldBeEmpty()
        }

        test("the API key header is set in exactly the two provider clients") {
            val writers =
                sources()
                    .filter { (_, text) ->
                        text.contains("\"x-api-key\"") || text.contains("\"Authorization\"")
                    }.map { it.first }
                    .toSet()
            writers shouldBe setOf("llm/AnthropicMessagesLlmClient.kt", "llm/OpenAiCompatibleLlmClient.kt")
        }

        test("logback.xml still floors io.ktor.client at INFO (Ktor logs request URLs at TRACE otherwise)") {
            val logback =
                File("src/main/resources/logback.xml")
                    .let {
                        if (it.exists()) it else File("lapis-server/src/main/resources/logback.xml")
                    }.readText()
            logback shouldContain """<logger name="io.ktor.client" level="INFO" />"""
        }

        test("the startup log names provider, model and host, but never the key or the path") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val previous = root.level
            root.level = Level.ALL
            root.addAppender(appender)
            try {
                val env =
                    mapOf(
                        AiConfig.ENV_ENABLED to "true",
                        AiConfig.ENV_PROVIDER to "openai_compatible",
                        AiConfig.ENV_MODEL to "model-x",
                        AiConfig.ENV_API_KEY to "sk-super-secret-startup-key",
                        AiConfig.ENV_BASE_URL to "https://gateway.example.eu/private/path",
                    )
                AiStartupCheck.log(AiConfig.load { env[it] })
                val messages = appender.list.map { it.formattedMessage }.filter { it.startsWith("AI-Assistenz") }
                messages.shouldNotBeEmptyList()
                messages.forEach {
                    it shouldNotContain "sk-super-secret"
                    it shouldNotContain "/private/path"
                }
                messages.joinToString(" ") shouldContain "gateway.example.eu"
            } finally {
                root.detachAppender(appender)
                root.level = previous
            }
        }

        test("a rejected tuning variable on an operational config logs 'default used', not 'feature stays off'") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val previous = root.level
            root.level = Level.ALL
            root.addAppender(appender)
            try {
                val env =
                    mapOf(
                        AiConfig.ENV_ENABLED to "true",
                        AiConfig.ENV_PROVIDER to "openai_compatible",
                        AiConfig.ENV_MODEL to "model-x",
                        AiConfig.ENV_API_KEY to "sk-super-secret-startup-key",
                        AiConfig.ENV_BASE_URL to "https://gateway.example.eu/v1",
                        AiConfig.ENV_TOP_K to "999",
                    )
                val config = AiConfig.load { env[it] }
                config.isOperational shouldBe true
                AiStartupCheck.log(config)
                val joined =
                    appender.list
                        .map { it.formattedMessage }
                        .filter { it.startsWith("AI-Assistenz") }
                        .joinToString("\n")
                joined shouldContain AiConfig.ENV_TOP_K
                joined shouldContain "Standardwert"
                joined shouldContain "aktiv"
                joined shouldNotContain "Feature bleibt aus"
            } finally {
                root.detachAppender(appender)
                root.level = previous
            }
        }
    })

private fun List<String>.shouldNotBeEmptyList() {
    isNotEmpty() shouldBe true
}

/** The deploy compose files must never forward `LAPIS_AI_*` -- an operator must add them on purpose (AVV first). */
class AiEnvNotForwardedInComposeTest :
    FunSpec({
        listOf(
            "deploy/production/docker-compose.yml",
            "deploy/production-elb/docker-compose.yml",
            "deploy/production-staging/docker-compose.yml",
            "deploy/local/docker-compose.yml",
        ).forEach { relativePath ->
            test("$relativePath does not forward LAPIS_AI_ vars into the container") {
                val composeFile = File(repoRoot, relativePath)
                check(composeFile.exists()) { "expected compose file at ${composeFile.absolutePath}" }
                composeFile.readText() shouldNotContain "LAPIS_AI_"
            }
        }
    })
