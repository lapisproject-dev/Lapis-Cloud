package network.lapis.cloud.server.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import java.io.File

private val repoRoot: File = File("..").canonicalFile

/**
 * The deploy compose files must never forward `LAPIS_MCP_*` -- an operator must add them on
 * purpose, same posture `ai.AiEnvNotForwardedInComposeTest` already establishes for `LAPIS_AI_*`.
 * See that test's own KDoc for why only these two example/local compose files (of the ones this
 * repository actually tracks) are checked.
 */
class McpEnvNotForwardedInComposeTest :
    FunSpec({
        listOf(
            "deploy/example/docker-compose.yml",
            "deploy/local/docker-compose.yml",
        ).forEach { relativePath ->
            test("$relativePath does not forward LAPIS_MCP_ vars into the container") {
                val composeFile = File(repoRoot, relativePath)
                check(composeFile.exists()) { "expected compose file at ${composeFile.absolutePath}" }
                composeFile.readText() shouldNotContain "LAPIS_MCP_"
            }
        }
    })
