package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.100 -- tripwire: the three public article surfaces have their own output formats and must never read
 * `mcp_agent_label` (the member-chosen connection name is neither public nor trustworthy).
 */
class PublicArticleOutputsNoAgentLabelTest :
    FunSpec({
        val routesDir =
            File("src/main/kotlin/network/lapis/cloud/server/routes").let {
                if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server/routes")
            }

        test("ArticlePublicRoutes, EmbedArticlesFeedRoutes and PublicArticlesOverviewRoutes never reference the agent label") {
            listOf("ArticlePublicRoutes.kt", "EmbedArticlesFeedRoutes.kt", "PublicArticlesOverviewRoutes.kt").forEach { name ->
                val file = File(routesDir, name)
                file.isFile shouldBe true
                val text = file.readText()
                text.contains("mcpAgentLabel") shouldBe false
                text.contains("mcp_agent_label") shouldBe false
            }
        }
    })
