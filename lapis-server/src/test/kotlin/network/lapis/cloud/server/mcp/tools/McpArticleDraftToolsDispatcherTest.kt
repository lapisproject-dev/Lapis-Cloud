package network.lapis.cloud.server.mcp.tools

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetrievers
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.mcp.auth.McpScopes
import network.lapis.cloud.server.mcp.ratelimit.McpToolCallRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

private const val WRITE_SCOPE = "mcp:member_read mcp:member_write"
private const val MARKER = "ZQX-MARKER-7731"

/**
 * Welle V1.9.100 "MCP-Artikelentwürfe" -- dispatcher-level coverage of `create_article_draft` and
 * `list_my_articles` (same construction as [McpWriteToolsDispatcherTest]: the real
 * [McpToolDispatcher], a hand-built [McpPrincipal], the shared H2 test database).
 */
class McpArticleDraftToolsDispatcherTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ArticleTable.deleteWhere { ArticleTable.authorId inList createdMemberIds }
                McpToolCallAuditTable.deleteWhere { McpToolCallAuditTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createMember(
            role: AccountRole = AccountRole.BOARD,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MCP Artikel Testmitglied"
                    it[email] = "mcp-article-tools-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun principal(
            memberId: Uuid,
            canWrite: Boolean = true,
            tokenId: Uuid = Uuid.random(),
            label: String = "Test Agent",
        ) = McpPrincipal(
            memberId = memberId,
            tokenId = tokenId,
            scope = if (canWrite) WRITE_SCOPE else McpScopes.MEMBER_READ,
            canWrite = canWrite,
            connectionLabel = label,
        )

        fun dispatcher(
            writeEnabled: Boolean = true,
            writeQuotas: Map<String, McpToolCallRateLimiter.WriteQuota> = emptyMap(),
        ): McpToolDispatcher =
            McpToolDispatcher(
                rateLimiter =
                    McpToolCallRateLimiter(
                        perTokenPerMinute = 1_000,
                        perMemberPerHour = 1_000,
                        perServerPerDay = 100_000,
                        writeQuotas = writeQuotas,
                    ),
                toolTimeoutMs = 5_000,
                maxResponseBytes = 65_536,
                retriever = KnowledgeRetrievers.forCurrentDatabase(),
                registrationSubmission =
                    EventRegistrationSubmission(
                        checkoutGateways = emptyMap(),
                        baseUrl = "https://mcp-article-tools-test.invalid",
                        mailDispatcher = MailDispatcher(transport = NoOpMailTransport()),
                    ),
                writeEnabled = writeEnabled,
            )

        fun args(
            title: String? = "Ein Titel",
            body: String? = "Ein Text",
            excerpt: String? = null,
            extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        ): JsonObject =
            buildJsonObject {
                if (title != null) put("title", JsonPrimitive(title))
                if (body != null) put("body", JsonPrimitive(body))
                if (excerpt != null) put("excerpt", JsonPrimitive(excerpt))
                extra.forEach { (k, v) -> put(k, v) }
            }

        suspend fun create(
            d: McpToolDispatcher,
            p: McpPrincipal,
            a: JsonObject,
        ): McpToolCallResult = d.dispatch(name = "create_article_draft", arguments = a, principal = p)

        fun rows(memberId: Uuid) = transaction { ArticleTable.selectAll().where { ArticleTable.authorId eq memberId }.toList() }

        fun auditOutcomes(memberId: Uuid): List<String> =
            transaction {
                McpToolCallAuditTable
                    .selectAll()
                    .where { McpToolCallAuditTable.memberId eq memberId }
                    .map { it[McpToolCallAuditTable.outcome] }
            }

        test(
            "create_article_draft: a BOARD member creates exactly one DRAFT with the label, no slug/submittedAt/publishedAt, no text echoed back",
        ) {
            val memberId = createMember(AccountRole.BOARD)
            val result =
                create(
                    dispatcher(),
                    principal(memberId, label = "Mein Agent"),
                    args(title = "Mein Artikel", body = "Inhalt", excerpt = "Kurz"),
                )

            val content = result.shouldBeInstanceOf<McpToolCallResult.Success>().content.jsonObject
            content.keys shouldBe setOf("articleId", "status", "duplicate")
            content["status"]!!.jsonPrimitive.content shouldBe "DRAFT"
            content["duplicate"]!!.jsonPrimitive.booleanOrNull shouldBe false
            val stored = rows(memberId).single()
            stored[ArticleTable.id].toString() shouldBe content["articleId"]!!.jsonPrimitive.content
            stored[ArticleTable.status] shouldBe ArticleStatus.DRAFT
            stored[ArticleTable.authorId] shouldBe memberId
            stored[ArticleTable.mcpAgentLabel] shouldBe "Mein Agent"
            stored[ArticleTable.title] shouldBe "Mein Artikel"
            stored[ArticleTable.slug].shouldBeNull()
            stored[ArticleTable.submittedAt].shouldBeNull()
            stored[ArticleTable.publishedAt].shouldBeNull()
            stored[ArticleTable.coverImageId].shouldBeNull()
            content.toString() shouldNotContain "Mein Artikel"
            content.toString() shouldNotContain "Inhalt"
        }

        test("create_article_draft: an ADMIN member is allowed too") {
            val memberId = createMember(AccountRole.ADMIN)
            create(dispatcher(), principal(memberId), args()).shouldBeInstanceOf<McpToolCallResult.Success>()
            rows(memberId).size shouldBe 1
        }

        test("create_article_draft: an ordinary MEMBER is Forbidden (audited FORBIDDEN), no row, no role name in the answer") {
            val memberId = createMember(AccountRole.MEMBER)
            val result = create(dispatcher(), principal(memberId), args())
            result.shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            rows(memberId).size shouldBe 0
            auditOutcomes(memberId) shouldBe listOf("FORBIDDEN")
            result.toString() shouldNotContain "BOARD"
            result.toString() shouldNotContain "ADMIN"
        }

        test("create_article_draft: a non-ACTIVE BOARD account (WITHDRAWN, FRIEND, GUEST) is Forbidden") {
            listOf(MemberStatus.WITHDRAWN, MemberStatus.FRIEND, MemberStatus.GUEST).forEach { status ->
                val memberId = createMember(AccountRole.BOARD, status)
                create(dispatcher(), principal(memberId), args()).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
                rows(memberId).size shouldBe 0
            }
        }

        test("create_article_draft: the role is read fresh -- demoting the account after the token was minted revokes the right") {
            val memberId = createMember(AccountRole.BOARD)
            create(dispatcher(), principal(memberId), args(title = "Vorher")).shouldBeInstanceOf<McpToolCallResult.Success>()
            transaction { AccountTable.update({ AccountTable.memberId eq memberId }) { it[role] = AccountRole.MEMBER } }
            create(dispatcher(), principal(memberId), args(title = "Nachher")).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            rows(memberId).size shouldBe 1
        }

        test("create_article_draft: a read-only token and a switched-off writing are Forbidden without a row and without an audit write") {
            val memberId = createMember(AccountRole.BOARD)
            create(dispatcher(), principal(memberId, canWrite = false), args()).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            create(dispatcher(writeEnabled = false), principal(memberId), args()).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            rows(memberId).size shouldBe 0
            auditOutcomes(memberId) shouldBe emptyList()
        }

        test("create_article_draft: invalid arguments are rejected as InvalidArguments and create nothing") {
            val memberId = createMember(AccountRole.BOARD)
            val p = principal(memberId)
            val d = dispatcher()
            val bad =
                listOf(
                    args(title = ""),
                    args(title = "   "),
                    args(title = "x".repeat(141)),
                    args(body = ""),
                    args(body = "y".repeat(20_001)),
                    args(excerpt = "z".repeat(301)),
                    args(title = null, extra = mapOf("title" to JsonPrimitive(42))),
                    args(title = null, extra = mapOf("title" to buildJsonObject { put("a", JsonPrimitive("b")) })),
                    args(extra = mapOf("status" to JsonPrimitive("PUBLISHED"))),
                    args(extra = mapOf("coverImageId" to JsonPrimitive(Uuid.random().toString()))),
                    args(body = "abc\u0000def"),
                    args(excerpt = "abc\u0000def"),
                    args(title = "Titel\u0007mit Steuerzeichen"),
                    args(title = "Titel\nmit Zeilenumbruch"),
                    args(title = null, body = null),
                )
            bad.forEach { a -> create(d, p, a).shouldBeInstanceOf<McpToolCallResult.InvalidArguments>() }
            rows(memberId).size shouldBe 0
        }

        test("create_article_draft: the boundary lengths (140 title, 20000 body, 300 excerpt) and newlines/tabs in body are accepted") {
            val memberId = createMember(AccountRole.BOARD)
            create(
                dispatcher(),
                principal(memberId),
                args(title = "t".repeat(140), body = "b".repeat(19_990) + "\n\r\t line", excerpt = "e".repeat(300)),
            ).shouldBeInstanceOf<McpToolCallResult.Success>()
        }

        test(
            "create_article_draft: the 11th open MCP article is ToolError(article_draft_limit_reached, 10); a SUBMITTED one still counts; manual ones do not",
        ) {
            val memberId = createMember(AccountRole.BOARD)
            val d = dispatcher()
            val p = principal(memberId)
            val now = DbClock.nowLocalDateTime()
            repeat(5) {
                transaction {
                    ArticleStore.insertDraft(
                        authorId = memberId,
                        input = ArticleDraftInput(title = "Manuell $it", excerpt = "x", body = "y"),
                        now = now,
                    )
                }
            }
            repeat(10) { create(d, p, args(title = "Entwurf $it")).shouldBeInstanceOf<McpToolCallResult.Success>() }
            val firstId = rows(memberId).first { it[ArticleTable.mcpAgentLabel] != null }[ArticleTable.id]
            transaction { ArticleTable.update({ ArticleTable.id eq firstId }) { it[status] = ArticleStatus.SUBMITTED } }

            val limited = create(d, p, args(title = "Entwurf 11")).shouldBeInstanceOf<McpToolCallResult.ToolError>()
            limited.code shouldBe "article_draft_limit_reached"
            limited.data["openDraftCount"]!!.jsonPrimitive.int shouldBe 10
            rows(memberId).count { it[ArticleTable.mcpAgentLabel] != null } shouldBe 10
            auditOutcomes(memberId).last() shouldBe "ERROR"
        }

        test("create_article_draft: the global per-author cap (100 editable articles) is reported the same way") {
            val memberId = createMember(AccountRole.BOARD)
            val now = DbClock.nowLocalDateTime()
            transaction {
                repeat(
                    ArticleStore.MAX_DRAFTS_PER_AUTHOR,
                ) {
                    ArticleStore.insertDraft(
                        authorId = memberId,
                        input = ArticleDraftInput(title = "Manuell $it", excerpt = "x", body = "y"),
                        now = now,
                    )
                }
            }
            val limited = create(dispatcher(), principal(memberId), args()).shouldBeInstanceOf<McpToolCallResult.ToolError>()
            limited.code shouldBe "article_draft_limit_reached"
            rows(memberId).size shouldBe ArticleStore.MAX_DRAFTS_PER_AUTHOR
        }

        test(
            "create_article_draft: identical input twice yields the same articleId with duplicate=true; a different excerpt or an edited original yields a new draft",
        ) {
            val memberId = createMember(AccountRole.BOARD)
            val d = dispatcher()
            val p = principal(memberId)
            val first =
                create(
                    d,
                    p,
                    args(title = "Doppelt", body = "Text", excerpt = "A"),
                ).shouldBeInstanceOf<McpToolCallResult.Success>().content.jsonObject
            val second =
                create(
                    d,
                    p,
                    args(title = "Doppelt", body = "Text", excerpt = "A"),
                ).shouldBeInstanceOf<McpToolCallResult.Success>().content.jsonObject
            second["articleId"] shouldBe first["articleId"]
            second["duplicate"]!!.jsonPrimitive.booleanOrNull shouldBe true
            rows(memberId).size shouldBe 1

            val other =
                create(
                    d,
                    p,
                    args(title = "Doppelt", body = "Text", excerpt = "B"),
                ).shouldBeInstanceOf<McpToolCallResult.Success>().content.jsonObject
            (other["articleId"] == first["articleId"]) shouldBe false
            other["duplicate"]!!.jsonPrimitive.booleanOrNull shouldBe false
            rows(memberId).size shouldBe 2

            val originalId = first["articleId"]!!.jsonPrimitive.content
            transaction {
                ArticleStore.updateDraft(
                    id = Uuid.parse(originalId),
                    input = ArticleDraftInput(title = "Doppelt", excerpt = "A", body = "Text, jetzt von Hand bearbeitet"),
                    now = DbClock.nowLocalDateTime(),
                )
            }
            val afterEdit =
                create(
                    d,
                    p,
                    args(title = "Doppelt", body = "Text", excerpt = "A"),
                ).shouldBeInstanceOf<McpToolCallResult.Success>().content.jsonObject
            afterEdit["duplicate"]!!.jsonPrimitive.booleanOrNull shouldBe false
            rows(memberId).size shouldBe 3
        }

        test(
            "create_article_draft: the 4th call within an hour with the same token is RateLimited; a read-only token never burns that quota",
        ) {
            val memberId = createMember(AccountRole.BOARD)
            val quotas =
                mapOf(
                    "create_article_draft" to
                        McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 3, perMemberPerDay = 10, perServerPerDay = 500),
                )
            val d = dispatcher(writeQuotas = quotas)
            val tokenId = Uuid.random()
            repeat(5) {
                create(
                    d,
                    principal(memberId, canWrite = false, tokenId = tokenId),
                    args(),
                ).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            }
            val p = principal(memberId, tokenId = tokenId)
            repeat(3) { create(d, p, args(title = "Rate $it")).shouldBeInstanceOf<McpToolCallResult.Success>() }
            create(d, p, args(title = "Rate 4")).shouldBeInstanceOf<McpToolCallResult.RateLimited>()
            rows(memberId).size shouldBe 3
        }

        test("create_article_draft: there is no publication path -- DRAFT only, never in the published list, no slug") {
            val memberId = createMember(AccountRole.BOARD)
            val id =
                create(dispatcher(), principal(memberId), args(title = "Nicht veröffentlichen"))
                    .shouldBeInstanceOf<McpToolCallResult.Success>()
                    .content.jsonObject["articleId"]!!
                    .jsonPrimitive.content
            transaction {
                ArticleStore.listPublishedNewestFirst(200).map { it[ArticleTable.id].toString() } shouldNotContain id
                ArticleStore.getOrThrow(Uuid.parse(id))[ArticleTable.status] shouldBe ArticleStatus.DRAFT
            }
            // The tool definition offers no status/cover parameter at all.
            CreateArticleDraftTool.definition.inputSchema["properties"]!!
                .jsonObject.keys shouldBe setOf("title", "body", "excerpt")
            CreateArticleDraftTool.definition.inputSchema["additionalProperties"]!!
                .jsonPrimitive.booleanOrNull shouldBe false
        }

        test("create_article_draft: hostile Markdown is stored verbatim but ArticleMarkdown.render neutralises it") {
            val memberId = createMember(AccountRole.BOARD)
            val hostile = "<script>alert(1)</script>\n\n[x](javascript:alert(1))"
            val id =
                create(dispatcher(), principal(memberId), args(body = hostile))
                    .shouldBeInstanceOf<McpToolCallResult.Success>()
                    .content.jsonObject["articleId"]!!
                    .jsonPrimitive.content
            val stored = transaction { ArticleStore.getOrThrow(Uuid.parse(id))[ArticleTable.body] }
            stored shouldBe hostile
            val html = ArticleMarkdown.render(markdown = stored)
            html shouldNotContain "<script"
            html shouldNotContain "javascript:"
        }

        test("create_article_draft: neither the audit table nor any log line contains the article text, also on the rejected paths") {
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                val board = createMember(AccountRole.BOARD)
                val plain = createMember(AccountRole.MEMBER)
                val d = dispatcher()
                create(d, principal(board), args(title = "Titel $MARKER", body = "Text $MARKER", excerpt = "Auszug $MARKER"))
                    .shouldBeInstanceOf<McpToolCallResult.Success>()
                create(
                    d,
                    principal(plain),
                    args(title = "Titel $MARKER", body = "Text $MARKER"),
                ).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
                create(
                    d,
                    principal(board),
                    args(title = "Titel $MARKER", body = "Text $MARKER\u0000"),
                ).shouldBeInstanceOf<McpToolCallResult.InvalidArguments>()
                val auditDump =
                    transaction {
                        McpToolCallAuditTable
                            .selectAll()
                            .where { McpToolCallAuditTable.memberId inList listOf(board, plain) }
                            .joinToString("\n") { row -> McpToolCallAuditTable.columns.joinToString("|") { col -> row[col].toString() } }
                    }
                auditDump shouldNotContain MARKER
                appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }.forEach { it shouldNotContain MARKER }
            } finally {
                root.detachAppender(appender)
            }
        }

        test("list_my_articles: only the caller's own articles, no body/excerpt/label keys, createdViaAssistant set correctly") {
            val a = createMember(AccountRole.BOARD)
            val b = createMember(AccountRole.BOARD)
            val d = dispatcher()
            create(d, principal(a, label = "Geheimer Agentenname"), args(title = "A per MCP", body = "Geheimer Text $MARKER"))
            create(d, principal(b), args(title = "B per MCP"))
            transaction {
                ArticleStore.insertDraft(
                    authorId = a,
                    input = ArticleDraftInput(title = "A manuell", excerpt = "Auszug", body = "Text"),
                    now = DbClock.nowLocalDateTime(),
                )
            }

            val result = d.dispatch(name = "list_my_articles", arguments = null, principal = principal(a, canWrite = false))
            val articles =
                result
                    .shouldBeInstanceOf<McpToolCallResult.Success>()
                    .content.jsonObject["articles"]!!
                    .jsonArray
            articles.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("A per MCP", "A manuell")
            articles.forEach {
                it.jsonObject.keys shouldBe setOf("articleId", "title", "status", "updatedAt", "createdViaAssistant")
            }
            articles
                .first { it.jsonObject["title"]!!.jsonPrimitive.content == "A per MCP" }
                .jsonObject["createdViaAssistant"]!!
                .jsonPrimitive.booleanOrNull shouldBe true
            articles
                .first { it.jsonObject["title"]!!.jsonPrimitive.content == "A manuell" }
                .jsonObject["createdViaAssistant"]!!
                .jsonPrimitive.booleanOrNull shouldBe false
            result.toString() shouldNotContain MARKER
            result.toString() shouldNotContain "Geheimer Agentenname"
            result.toString() shouldNotContain "B per MCP"
        }

        test("list_my_articles: an ordinary MEMBER is Forbidden") {
            val memberId = createMember(AccountRole.MEMBER)
            dispatcher()
                .dispatch(
                    name = "list_my_articles",
                    arguments = null,
                    principal = principal(memberId, canWrite = false),
                ).shouldBeInstanceOf<McpToolCallResult.Forbidden>()
        }

        test("list_my_articles: the limit is bounded (default 20, max 50, 0 becomes 1) and unknown arguments are rejected") {
            val memberId = createMember(AccountRole.BOARD)
            val now = DbClock.nowLocalDateTime()
            transaction {
                repeat(60) {
                    ArticleStore.insertDraft(
                        authorId = memberId,
                        input = ArticleDraftInput(title = "Artikel $it", excerpt = "x", body = "y"),
                        now = now,
                    )
                }
            }
            val p = principal(memberId, canWrite = false)
            val d = dispatcher()

            suspend fun size(limit: Int?): Int {
                val a = buildJsonObject { if (limit != null) put("limit", JsonPrimitive(limit)) }
                val r = d.dispatch(name = "list_my_articles", arguments = a, principal = p).shouldBeInstanceOf<McpToolCallResult.Success>()
                return (r.content.jsonObject["articles"] as JsonArray).size
            }
            size(null) shouldBe 20
            size(100) shouldBe 50
            size(0) shouldBe 1
            d
                .dispatch(
                    name = "list_my_articles",
                    arguments =
                        buildJsonObject {
                            put("memberId", JsonPrimitive(Uuid.random().toString()))
                        },
                    principal = p,
                ).shouldBeInstanceOf<McpToolCallResult.InvalidArguments>()
        }

        test("the manual editing path keeps the label: updateDraft never touches mcp_agent_label") {
            val memberId = createMember(AccountRole.BOARD)
            val id =
                create(dispatcher(), principal(memberId, label = "Bleibt"), args())
                    .shouldBeInstanceOf<McpToolCallResult.Success>()
                    .content.jsonObject["articleId"]!!
                    .jsonPrimitive.content
            transaction {
                ArticleStore.updateDraft(
                    id = Uuid.parse(id),
                    input = ArticleDraftInput(title = "Neu", excerpt = "Neu", body = "Neu"),
                    now = DbClock.nowLocalDateTime(),
                )
            }
            transaction { ArticleStore.getOrThrow(Uuid.parse(id))[ArticleTable.mcpAgentLabel] } shouldBe "Bleibt"
        }

        test("the tools are in the catalog with the right flags") {
            McpToolCatalog.TOOLS.map { it.name } shouldContain "create_article_draft"
            McpToolCatalog.TOOLS.first { it.name == "create_article_draft" }.writing shouldBe true
            McpToolCatalog.TOOLS.first { it.name == "list_my_articles" }.writing shouldBe false
            McpToolCatalog.TOOLS
                .first { it.name == "create_article_draft" }
                .description
                .shouldNotBeNull()
        }
    })
