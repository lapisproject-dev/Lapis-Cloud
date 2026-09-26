package network.lapis.cloud.server.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.mcp.tools.McpToolCatalog
import java.io.File

private val mcpSourceDir: File =
    File("src/main/kotlin/network/lapis/cloud/server/mcp").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server/mcp")
    }

private fun sources(): List<Pair<String, String>> {
    check(mcpSourceDir.isDirectory) { "MCP source dir not found: ${mcpSourceDir.absolutePath}" }
    return mcpSourceDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(mcpSourceDir).path to it.readText() }
        .toList()
}

private fun imports(text: String): List<String> = text.lines().filter { it.startsWith("import ") }

/**
 * Structural guard for the MCP layer's boundary (R1-R5, see `McpLayerBoundary` KDoc). A
 * source-text scan, same pattern as `ai.AiModuleBoundaryTest` -- the layer is a package, not a
 * Gradle module, so the compiler cannot enforce these rules; this test does.
 */
class McpStructureTest :
    FunSpec({
        test("the scan actually sees the source files (guard against a silently empty scan)") {
            (sources().size >= 15) shouldBe true
        }

        test("R1: mcp/ never imports session/API-key identity resolution or a server.rpc.*Service") {
            val forbidden =
                listOf(
                    "import network.lapis.cloud.server.security.SessionStore",
                    "import network.lapis.cloud.server.security.resolveCurrentMember",
                    "import network.lapis.cloud.server.security.CurrentMember",
                    "import network.lapis.cloud.server.security.ApiKeyStore",
                )
            val offenders =
                sources().flatMap { (path, text) ->
                    imports(text).filter { line -> forbidden.any { line.startsWith(it) } }.map { "$path: $it" }
                }
            offenders.shouldBeEmpty()

            // No "*Service" import from server.rpc, EXCEPT the shared-module (not server.rpc)
            // ForbiddenException/exception types, which are a different package entirely.
            val serviceImport = Regex("""^import network\.lapis\.cloud\.server\.rpc\.\w*Service$""")
            val serviceOffenders =
                sources().flatMap { (path, text) -> imports(text).filter { serviceImport.matches(it) }.map { "$path: $it" } }
            serviceOffenders.shouldBeEmpty()
        }

        test("R2: transport/ and tools/ never read call.request.cookies") {
            val offenders =
                sources()
                    .filter { (path, _) -> path.startsWith("transport/") || path.startsWith("tools/") }
                    .filter { (_, text) -> text.contains("request.cookies") }
                    .map { it.first }
            offenders.shouldBeEmpty()
        }

        test("R3: no Exposed write under mcp/ outside the four explicitly-scoped writers") {
            val write =
                Regex("""\b(\w+Table)\s*\.\s*(insert|update|deleteWhere|batchInsert|upsert|deleteAll|deleteIgnoreWhere|replace)\b""")
            val allowedFiles =
                setOf(
                    "optin/McpMemberBlockStore.kt",
                    "audit/McpToolCallAuditRecorder.kt",
                    "auth/McpTokenAuth.kt",
                    "auth/McpTokenRevoker.kt",
                )
            val offenders =
                sources()
                    .filter { (path, _) -> path !in allowedFiles }
                    .filter { (_, text) -> write.containsMatchIn(text) }
                    .map { it.first }
            offenders.shouldBeEmpty()
        }

        test("R4: the tool catalog has exactly seven entries (five reading, two writing), and no tool file declares a memberId parameter") {
            McpToolCatalog.TOOLS.size shouldBe 7
            McpToolCatalog.TOOLS.count { it.writing } shouldBe 2
            val memberIdParam = Regex("""fun\s+execute\s*\([^)]*memberId\s*:""")
            val offenders =
                sources()
                    .filter { (path, _) -> path.startsWith("tools/") }
                    .filter { (_, text) -> memberIdParam.containsMatchIn(text) }
                    .map { it.first }
            offenders.shouldBeEmpty()
        }

        test("R5: mcp/ never imports ai/qa or ai/llm -- the only allowed ai/ import is the retrieval interface/impl") {
            val allowedAiImports =
                setOf(
                    "import network.lapis.cloud.server.ai.retrieval.KnowledgeRetriever",
                    "import network.lapis.cloud.server.ai.retrieval.PostgresFullTextKnowledgeRetriever",
                    "import network.lapis.cloud.server.ai.config.AiConfig",
                )
            val offenders =
                sources().flatMap { (path, text) ->
                    imports(text)
                        .filter { it.startsWith("import network.lapis.cloud.server.ai.") }
                        .filter { it !in allowedAiImports }
                        .map { "$path: $it" }
                }
            offenders.shouldBeEmpty()
        }

        test(
            "R6 (Welle V1.8.2): mcp/ never IMPORTS SocialNetworkService/SocialPostState/SocialPostTable, and no non-comment line calls createPost(...) -- SocialPostVisibility is a named exception",
        ) {
            // Same "scan import lines" idiom as R1/R5 above -- deliberately NOT a raw-text scan,
            // which would also flag this rule's own KDoc explanations (in this file and in
            // McpLayerBoundary.kt) that name the forbidden types to explain why they are forbidden.
            val forbiddenImports =
                listOf(
                    "import network.lapis.cloud.server.rpc.SocialNetworkService",
                    "import network.lapis.cloud.shared.domain.SocialPostState",
                    "import network.lapis.cloud.server.db.generated.SocialPostTable",
                )
            val importOffenders =
                sources().flatMap { (path, text) ->
                    imports(text).filter { line -> forbiddenImports.any { line.startsWith(it) } }.map { "$path: $it" }
                }
            importOffenders.shouldBeEmpty()

            // createPost(...) as an actual call, on a non-comment line -- excludes both
            // "createPostRow" (a different identifier, lives in SocialNetworkService itself, not
            // under mcp/) and KDoc/`//` prose that merely NAMES the method to explain the boundary.
            val createPostCall = Regex("""\bcreatePost\s*\(""")
            val createPostOffenders =
                sources()
                    .filter { (_, text) ->
                        text.lines().any { line ->
                            !line.trimStart().startsWith("*") &&
                                !line.trimStart().startsWith("//") &&
                                createPostCall.containsMatchIn(line)
                        }
                    }.map { it.first }
            createPostOffenders.shouldBeEmpty()
        }
    })
