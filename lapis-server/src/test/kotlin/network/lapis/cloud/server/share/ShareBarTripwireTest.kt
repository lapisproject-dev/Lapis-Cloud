package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.routes.SocialPublicHtml
import network.lapis.cloud.server.theme.repoFile

/** V1.9.217 tripwires: where the share bar may be rendered, how it is wired in production, and what its stylesheet and script may contain. */
class ShareBarTripwireTest :
    FunSpec({
        val sources = repoFile("lapis-server/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("renderShareBar is called only by the three detail-page renderers (plus its own definition)") {
            val allowed = setOf("EventPublicHtml.kt", "ArticlePublicHtml.kt", "SocialPublicHtml.kt", "ShareBar.kt")
            sources
                .filter { it.readText().contains("renderShareBar(") }
                .map { it.name }
                .toSet()
                .all { it in allowed } shouldBe true
        }

        test("share.js is referenced only by the share package and the social route file") {
            val offenders =
                sources
                    .filter { it.readText().contains("assets/share.js") || it.readText().contains("SHARE_SCRIPT_PATH") }
                    .map { it.name }
                    .filter { it !in setOf("ShareScript.kt", "ShareBar.kt", "SocialPublicRoutes.kt") }
            offenders shouldBe emptyList()
        }

        test("Application.kt hands the loaded ShareConfig to all three route registrations") {
            val app = sources.single { it.name == "Application.kt" }.readText()
            Regex("shareConfig = shareConfig").findAll(app).count() shouldBe 3
            for (fn in listOf("registerSocialPublicRoutes", "registerEventPublicRoutes", "registerArticlePublicRoutes")) {
                val start = app.indexOf("$fn(")
                val block = app.substring(start, app.indexOf("\n        )\n", start))
                block.contains("shareConfig = shareConfig") shouldBe true
            }
        }

        test(".share rules use theme tokens only: no colour literals, no var() fallbacks") {
            val css = SocialPublicHtml.STYLESHEET
            val rules =
                css
                    .lines()
                    .filter { it.contains(".share") && !it.contains("data:image") }
                    .joinToString("\n")
            Regex("#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsla?\\(").containsMatchIn(rules) shouldBe false
            rules.contains("var(--lapis-") shouldBe true
            Regex("var\\(--[a-z-]+,").containsMatchIn(rules) shouldBe false
        }

        test("share.js is small and touches neither the DOM markup API, the network nor storage") {
            (SHARE_SCRIPT.lines().size <= 40) shouldBe true
            for (forbidden in listOf(
                "innerHTML",
                "outerHTML",
                "eval",
                "Function(",
                "fetch",
                "XMLHttpRequest",
                "localStorage",
                "sessionStorage",
                "document.cookie",
                "insertAdjacentHTML",
            )) {
                SHARE_SCRIPT.contains(forbidden) shouldBe false
            }
        }
    })
