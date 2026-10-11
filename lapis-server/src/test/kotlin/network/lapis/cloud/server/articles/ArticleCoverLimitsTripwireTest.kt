package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.events.CoverDimensionRule
import network.lapis.cloud.server.events.CoverImageLimits
import java.io.File

/**
 * Welle V1.9.106 -- the 300 px minimum width applies to IMPORTED articles only, and only because the DB row says so. Structural guarantees in
 * the style of `ArticleImportTripwireTest`.
 */
class ArticleCoverLimitsTripwireTest :
    FunSpec({
        fun code(file: File) = SourceScan.blank(file.readText())

        val allowedFiles =
            setOf("ArticleCoverPolicy.kt", "ArticleCoverRoutes.kt", "ArticleImportImages.kt", "EventCoverImage.kt")

        test("the limits are what the design says; the event and crest limits are untouched") {
            CoverImageLimits.EVENT_COVER.rule shouldBe CoverDimensionRule.LongShortEdge(minLongEdgePx = 800, minShortEdgePx = 600)
            CoverImageLimits.CHAPTER_CREST.rule shouldBe CoverDimensionRule.LongShortEdge(minLongEdgePx = 64, minShortEdgePx = 64)
            CoverImageLimits.IMPORTED_ARTICLE_COVER.rule shouldBe
                CoverDimensionRule.WidthAndAspect(minWidthPx = 300, minHeightPx = 150, minAspect = 0.5, maxAspect = 4.0)
        }

        test("the factory picks by the imported flag only") {
            (ArticleCoverPolicy.limitsForArticle(false) === CoverImageLimits.EVENT_COVER) shouldBe true
            (ArticleCoverPolicy.limitsForArticle(true) === CoverImageLimits.IMPORTED_ARTICLE_COVER) shouldBe true
        }

        test(
            "IMPORTED_ARTICLE_COVER and limitsForArticle( appear only in the article cover policy, the article cover route and the import images",
        ) {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.name !in allowedFiles }
                    .filter { code(it).contains("IMPORTED_ARTICLE_COVER") || code(it).contains("limitsForArticle(") }
                    .map { it.name }
            offenders shouldBe emptyList()
        }

        test("every limitsForArticle( call takes an [ArticleTable.imported] row access, never a parameter or a request field") {
            // `target.imported` is the batch target of the image commit; ArticleStore fills it from `row[ArticleTable.imported]` only (pinned below).
            val call = Regex("""limitsForArticle\(([^)]*(?:\]|target\.imported))\)""")
            val bad = mutableListOf<String>()
            for (file in SourceScan.mainFiles().filter { it.name != "ArticleCoverPolicy.kt" }) {
                val text = code(file)
                var from = 0
                while (true) {
                    val at = text.indexOf("limitsForArticle(", from)
                    if (at < 0) break
                    val rest = text.substring(at, minOf(text.length, at + 120))
                    val match = call.find(rest)
                    val argument = match?.groupValues?.get(1).orEmpty()
                    if (match == null ||
                        !(argument.contains("[ArticleTable.imported]") || argument == "target.imported")
                    ) {
                        bad += "${file.name}: $rest"
                    }
                    from = at + 1
                }
            }
            bad shouldBe emptyList()
        }

        test("no request DTO in lapis-shared has an imported property; response DTOs are an allowlist") {
            val shared =
                listOf("../lapis-shared/src/commonMain/kotlin", "lapis-shared/src/commonMain/kotlin")
                    .map { File(it) }
                    .first { it.exists() }
            val allowedResponses = setOf("ArticleDto", "ArticleSummaryDto", "ArticleReviewDto")
            val hits = mutableListOf<String>()
            shared
                .walkTopDown()
                .filter { it.name == "Articles.kt" || it.name == "ArticleImport.kt" }
                .forEach { file ->
                    val text = SourceScan.blank(file.readText())
                    Regex("""data class (\w+)\(([^)]*?)\)""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
                        if (Regex("""\bval\s+imported\s*:""").containsMatchIn(m.groupValues[2]) && m.groupValues[1] !in allowedResponses) {
                            hits += "${file.name}: ${m.groupValues[1]}"
                        }
                    }
                }
            hits shouldBe emptyList()
        }

        test("no multipart field named imported is read anywhere in the server") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.readText().contains("PartData.FormItem") }
                    .filter { Regex("""name\s*==\s*"imported"""").containsMatchIn(it.readText()) }
                    .map { it.name }
            offenders shouldBe emptyList()
        }

        test("the imported flag is written in ArticleStore.insertImported only (immutable afterwards)") {
            val writer = Regex("""\[(ArticleTable\.)?imported\]\s*=""")
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "ArticleStore.kt" }
                    .filter { code(it).contains("ArticleTable") && writer.containsMatchIn(code(it)) }
                    .map { it.name }
            offenders shouldBe emptyList()
        }

        test("the batch target's imported flag comes from the DB row only") {
            val store = code(File(SourceScan.mainRoot(), "network/lapis/cloud/server/articles/ArticleStore.kt"))
            store.contains("imported = row[ArticleTable.imported]") shouldBe true
        }

        test("the image commit does no image processing and no file writing inside its database transaction") {
            val images = code(File(SourceScan.mainRoot(), "network/lapis/cloud/server/articles/ArticleImportImages.kt"))
            val from = images.indexOf("transaction {\n                    relaxSessionTimeouts")
            val to = images.indexOf("CommitOutcome(applied")
            (from > 0 && to > from) shouldBe true
            val block = images.substring(from, to)
            listOf("storage.", "processImage", "EventCoverImageProcessor", "delete(").forEach { block.contains(it) shouldBe false }
        }
    })
