package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import java.io.File

private val repoRoot: File = File("..").canonicalFile

/**
 * Welle V1.9.81 -- the deploy compose files must never forward the mail budget variables: an operator adds `LAPIS_MAIL_MAX_PER_HOUR` /
 * `LAPIS_MAIL_RESERVE_PER_HOUR` on purpose (the deploy README shows the block), same posture as `LAPIS_MCP_*` / `LAPIS_AI_*` /
 * `LAPIS_KEYCLOAK_*`. The reasons: a shared mailbox may be used by several instances, and the sensible number depends on the provider.
 */
class MailBudgetEnvNotForwardedInComposeTest :
    FunSpec({
        listOf("deploy/example/docker-compose.yml", "deploy/local/docker-compose.yml").forEach { relativePath ->
            test("$relativePath does not forward LAPIS_MAIL_ budget vars into the container") {
                val composeFile = File(repoRoot, relativePath)
                check(composeFile.exists()) { "expected compose file at ${composeFile.absolutePath}" }
                val text = composeFile.readText()
                text shouldNotContain MailBudgetConfig.ENV_MAX
                text shouldNotContain MailBudgetConfig.ENV_RESERVE
            }
        }
    })
