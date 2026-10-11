package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.105 -- structural guarantees of the article import and the byline that no behavioural test can pin down by itself. Source-scanning
 * tripwires in the style of `EventImportTripwireTest`: they fail the build when the code drifts away from the design.
 */
class ArticleImportTripwireTest :
    FunSpec({
        val root = SourceScan.mainRoot()

        fun code(file: File) = SourceScan.blank(file.readText())

        val importedTrue = Regex("""imported\]?\s*=\s*true""")

        test("imported = true is written by ArticleStore.insertImported and nowhere else") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "ArticleStore.kt" }
                    .filter { file -> code(file).contains("ArticleTable") && importedTrue.containsMatchIn(code(file)) }
                    .map { it.name }
            offenders shouldBe emptyList()
            importedTrue.containsMatchIn(code(File(root, "network/lapis/cloud/server/articles/ArticleStore.kt"))) shouldBe true
        }

        test("only ArticleImporter calls insertImported") {
            val callers =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "ArticleStore.kt" }
                    .filter { code(it).contains("insertImported(") }
                    .map { it.name }
            callers shouldBe listOf("ArticleImporter.kt")
        }

        test("the draft writers never touch the byline or the imported flag: insertDraft, insertMcpDraft, updateDraft") {
            val store = code(File(root, "network/lapis/cloud/server/articles/ArticleStore.kt"))
            for ((from, to) in listOf(
                "fun insertDraft(" to "fun insertMcpDraft(",
                "fun insertMcpDraft(" to "fun updateDraft(",
                "fun updateDraft(" to "fun deleteById(",
            )) {
                val body = store.substring(store.indexOf(from), store.indexOf(to))
                body.contains("byline") shouldBe false
                body.contains("imported") shouldBe false
            }
        }

        test("ArticleDraftInput and the MCP draft tool have no byline field (the auto-save must never create a consent record)") {
            val shared =
                File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/Articles.kt")
                    .let {
                        if (it.exists()) {
                            it
                        } else {
                            File(
                                "lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/Articles.kt",
                            )
                        }
                    }.readText()
            val input = shared.substring(shared.indexOf("data class ArticleDraftInput("), shared.indexOf("data class ArticleSummaryDto("))
            input.lowercase().contains("byline") shouldBe false
            code(File(root, "network/lapis/cloud/server/mcp/tools/CreateArticleDraftTool.kt")).lowercase().contains("byline") shouldBe false
            code(File(root, "network/lapis/cloud/server/articles/McpArticleDraftStore.kt")).lowercase().contains("byline") shouldBe false
        }

        test("the board path can only ever write an ORGANIZATION kind (no PERSONAL anywhere in setBylineAsBoard)") {
            val service = code(File(root, "network/lapis/cloud/server/rpc/ArticleService.kt"))
            val board =
                service.substring(
                    service.indexOf("override suspend fun setBylineAsBoard("),
                    service.indexOf("private fun recordBylineAudit("),
                )
            board.contains("ArticleBylineKind.PERSONAL") shouldBe false
        }

        test("the byline text never reaches the audit log: recordBylineAudit takes kinds and a flag, no text") {
            val service = code(File(root, "network/lapis/cloud/server/rpc/ArticleService.kt"))
            val audit =
                service.substring(
                    service.indexOf("private fun recordBylineAudit("),
                    service.indexOf("override suspend fun listSubmittedArticles("),
                )
            audit.contains("byline:") shouldBe false
            audit.contains("text") shouldBe false
        }

        test("every method of ArticleImportService starts with the role check") {
            val service = code(File(root, "network/lapis/cloud/server/rpc/ArticleImportService.kt"))
            val methods =
                Regex("""override suspend fun (\w+)\([^)]*\)[^{]*\{(.*?)\n    }""", RegexOption.DOT_MATCHES_ALL).findAll(service).toList()
            methods.map { it.groupValues[1] } shouldBe listOf("previewArticleImport", "commitArticleImport")
            for (method in methods) {
                val statements =
                    method.groupValues[2]
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                statements[0] shouldBe "val current = resolveCurrentMember(call)"
                statements[1] shouldBe "current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)"
                statements[2].startsWith("requireWithinRate(") shouldBe true
            }
        }

        test("the import files contain no network client, no mail and no webhook (no SSRF surface, no external effect in a transaction)") {
            val files =
                listOf(
                    "network/lapis/cloud/server/articles/ArticleImporter.kt",
                    "network/lapis/cloud/server/articles/ArticleImportPolicy.kt",
                    "network/lapis/cloud/server/articles/ArticleImportText.kt",
                    "network/lapis/cloud/server/articles/ArticleBylinePolicy.kt",
                    "network/lapis/cloud/server/rpc/ArticleImportService.kt",
                )
            val forbidden =
                listOf(
                    "HttpClient",
                    "io.ktor.client",
                    "java.net.http",
                    "URL(",
                    ".toURL",
                    "openConnection",
                    "MailDispatcher",
                    "MailOutbox",
                    "Webhook",
                    "runBlocking",
                )
            val hits = mutableListOf<String>()
            for (name in files) {
                val text = code(File(root, name))
                forbidden.filter { text.contains(it) }.forEach { hits += "$name: $it" }
            }
            hits shouldBe emptyList()
        }

        test("the import and the byline rules log and print nothing (no names or texts in log lines)") {
            for (name in listOf("ArticleImporter.kt", "ArticleImportPolicy.kt", "ArticleImportText.kt", "ArticleBylinePolicy.kt")) {
                val text = code(File(root, "network/lapis/cloud/server/articles/$name"))
                text.contains("logger") shouldBe false
                text.contains("println") shouldBe false
            }
            val service = code(File(root, "network/lapis/cloud/server/rpc/ArticleService.kt"))
            val bylinePart =
                service.substring(
                    service.indexOf("override suspend fun setOwnByline("),
                    service.indexOf("override suspend fun listSubmittedArticles("),
                )
            bylinePart.contains("logger") shouldBe false
        }

        test("the import has no image field: coverImage/image appear in no import source as an accepted key") {
            val policy = code(File(root, "network/lapis/cloud/server/articles/ArticleImportPolicy.kt"))
            val allowed = Regex("""ALLOWED_KEYS\s*=\s*setOf\(([^)]*)\)""").find(policy)!!.groupValues[1]
            allowed.contains("cover") shouldBe false
            allowed.contains("image") shouldBe false
            allowed.contains("status") shouldBe false
            allowed.contains("author") shouldBe false
        }
    })
