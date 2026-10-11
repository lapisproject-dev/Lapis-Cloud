package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.articles.McpArticleDraftStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeArticleReviewNotificationMailer
import network.lapis.cloud.server.mcp.tools.CreateArticleDraftTool
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.BoardBylineInput
import network.lapis.cloud.shared.domain.BoardBylineMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val BOARD_MEMBER = Uuid.parse("00000000-0000-0000-0000-000000000002")
private val ADMIN_MEMBER = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * Welle V1.9.105 -- the author line (byline) of an article: consent by the author, board-set organization lines, revocation, status transitions
 * leaving it alone, the audit log never carrying the text, and the MCP draft path never touching it. H2 only (the trusted `X-Member-Id` header
 * that drives the throwaway routes exists exclusively on the in-memory test database).
 */
class ArticleBylineServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()
        val secret = "Geheimname-Nur-Hier-${Uuid.random().toString().take(8)}"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) ArticleTable.deleteWhere { ArticleTable.id inList createdArticleIds }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Byline Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createArticle(
            author: Uuid,
            status: ArticleStatus = ArticleStatus.DRAFT,
            imported: Boolean = false,
            byline: String? = null,
            kind: ArticleBylineKind? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = if (status == ArticleStatus.PUBLISHED) "byline-test-$id" else null
                    it[title] = "Ein Artikel für Byline-Tests"
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt"
                    it[authorId] = author
                    it[ArticleTable.status] = status
                    it[submittedAt] = if (status == ArticleStatus.DRAFT) null else now
                    it[publishedAt] = if (status == ArticleStatus.PUBLISHED) now else null
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ArticleTable.byline] = byline
                    it[bylineKind] = kind
                    it[bylineConsentAt] = if (kind == ArticleBylineKind.PERSONAL) now else null
                    it[ArticleTable.imported] = imported
                }
            }
            createdArticleIds += id
            return id
        }

        fun rowOf(id: Uuid) = transaction { ArticleTable.selectAll().where { ArticleTable.id eq id }.single() }

        fun auditTextOf(id: Uuid): String =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq id }
                    .joinToString("\n") { "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}" }
            }

        suspend fun app(
            draftUpdateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                        exception<RateLimitedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.TooManyRequests)
                        }
                    }
                    routing { bylineRoutes(draftUpdateLimiter) }
                }
                block()
            }
        }

        suspend fun ApplicationTestBuilder.own(
            who: Uuid,
            id: Uuid,
            consent: Boolean,
            text: String? = null,
        ) = client.post("/test/own-byline/$id?consent=$consent${text?.let { "&text=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""}") {
            header("X-Member-Id", who.toString())
        }

        suspend fun ApplicationTestBuilder.board(
            who: Uuid,
            id: Uuid,
            mode: BoardBylineMode,
            text: String? = null,
        ) = client.post(
            "/test/board-byline/$id?mode=${mode.name}${text?.let { "&text=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""}",
        ) {
            header("X-Member-Id", who.toString())
        }

        test("by default nobody is named, and an editable draft can be given a personal line only with a text") {
            val author = createMember("byline-default@example.org")
            val id = createArticle(author)
            rowOf(id)[ArticleTable.byline] shouldBe null
            rowOf(id)[ArticleTable.bylineKind] shouldBe null
            app {
                // consent without a text: 400, nothing is written
                own(author, id, consent = true, text = null).status shouldBe HttpStatusCode.BadRequest
                own(author, id, consent = true, text = "   ").status shouldBe HttpStatusCode.BadRequest
                own(author, id, consent = true, text = "x".repeat(121)).status shouldBe HttpStatusCode.BadRequest
                rowOf(id)[ArticleTable.byline] shouldBe null
                rowOf(id)[ArticleTable.bylineKind] shouldBe null
                rowOf(id)[ArticleTable.bylineConsentAt] shouldBe null
                auditTextOf(id) shouldBe ""
            }
        }

        test("consent with a text stores a PERSONAL line with the consent moment and the author as setter; the text is normalized") {
            val author = createMember("byline-personal@example.org")
            val id = createArticle(author)
            app {
                own(author, id, consent = true, text = "  Max‮   Muster\n").status shouldBe HttpStatusCode.OK
                val row = rowOf(id)
                row[ArticleTable.byline] shouldBe "Max Muster"
                row[ArticleTable.bylineKind] shouldBe ArticleBylineKind.PERSONAL
                (row[ArticleTable.bylineConsentAt] != null) shouldBe true
                row[ArticleTable.bylineSetBy] shouldBe author
                (row[ArticleTable.bylineSetAt] != null) shouldBe true
            }
        }

        test("the same text again creates no new consent moment and no audit entry; a changed text does") {
            val author = createMember("byline-same@example.org")
            val id = createArticle(author)
            app {
                own(author, id, consent = true, text = "Erika Muster")
                val old = LocalDateTime(2020, 1, 1, 8, 0)
                transaction { ArticleTable.update({ ArticleTable.id eq id }) { it[bylineConsentAt] = old } }
                val auditBefore = auditTextOf(id).lines().size
                own(author, id, consent = true, text = "Erika Muster").status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.bylineConsentAt] shouldBe old
                auditTextOf(id).lines().size shouldBe auditBefore
                own(author, id, consent = true, text = "Erika M.").status shouldBe HttpStatusCode.OK
                (rowOf(id)[ArticleTable.bylineConsentAt] != old) shouldBe true
                rowOf(id)[ArticleTable.byline] shouldBe "Erika M."
            }
        }

        test("consent = false clears the whole line (and records who withdrew)") {
            val author = createMember("byline-clear@example.org")
            val id = createArticle(author)
            app {
                own(author, id, consent = true, text = "Max Muster")
                own(author, id, consent = false).status shouldBe HttpStatusCode.OK
                val row = rowOf(id)
                row[ArticleTable.byline] shouldBe null
                row[ArticleTable.bylineKind] shouldBe null
                row[ArticleTable.bylineConsentAt] shouldBe null
                row[ArticleTable.bylineSetBy] shouldBe author
            }
        }

        test("another member cannot touch the line (404, no existence oracle), and a submitted article cannot be re-worded by the author") {
            val author = createMember("byline-owner@example.org")
            val stranger = createMember("byline-stranger@example.org")
            val id = createArticle(author)
            app {
                own(stranger, id, consent = true, text = "Fremd").status shouldBe HttpStatusCode.NotFound
                own(author, id, consent = true, text = "Max Muster")
                transaction { ArticleTable.update({ ArticleTable.id eq id }) { it[status] = ArticleStatus.SUBMITTED } }
                own(author, id, consent = true, text = "Anders").status shouldBe HttpStatusCode.Conflict
                rowOf(id)[ArticleTable.byline] shouldBe "Max Muster"
            }
        }

        test("revokeOwnByline works in every status, is idempotent, and is the author's alone") {
            val author = createMember("byline-revoke@example.org")
            val stranger = createMember("byline-revoke-stranger@example.org")
            app {
                for (status in listOf(ArticleStatus.SUBMITTED, ArticleStatus.PUBLISHED)) {
                    val id = createArticle(author, status = status, byline = "Max Muster", kind = ArticleBylineKind.PERSONAL)
                    client.post("/test/revoke-byline/$id") { header("X-Member-Id", stranger.toString()) }.status shouldBe
                        HttpStatusCode.NotFound
                    rowOf(id)[ArticleTable.byline] shouldBe "Max Muster"
                    client.post("/test/revoke-byline/$id") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.OK
                    rowOf(id)[ArticleTable.byline] shouldBe null
                    rowOf(id)[ArticleTable.bylineConsentAt] shouldBe null
                    // idempotent: a second call changes nothing and does not fail
                    client.post("/test/revoke-byline/$id") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("revokeOwnByline is Forbidden for a line the board set (organization): the board decides there") {
            val author = createMember("byline-revoke-org@example.org")
            val id = createArticle(author, status = ArticleStatus.PUBLISHED, byline = "Redaktion", kind = ArticleBylineKind.ORGANIZATION)
            app {
                client.post("/test/revoke-byline/$id") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.Forbidden
                rowOf(id)[ArticleTable.byline] shouldBe "Redaktion"
            }
        }

        test(
            "the board can set an ORGANIZATION line on a submitted or published article; it replaces a personal line and clears the consent",
        ) {
            val author = createMember("byline-board@example.org")
            val submitted =
                createArticle(author, status = ArticleStatus.SUBMITTED, byline = "Max Muster", kind = ArticleBylineKind.PERSONAL)
            val published = createArticle(author, status = ArticleStatus.PUBLISHED)
            app {
                board(BOARD_MEMBER, submitted, BoardBylineMode.ORGANIZATION, "Partei der Vernunft").status shouldBe HttpStatusCode.OK
                var row = rowOf(submitted)
                row[ArticleTable.byline] shouldBe "Partei der Vernunft"
                row[ArticleTable.bylineKind] shouldBe ArticleBylineKind.ORGANIZATION
                row[ArticleTable.bylineConsentAt] shouldBe null
                row[ArticleTable.bylineSetBy] shouldBe BOARD_MEMBER

                board(ADMIN_MEMBER, published, BoardBylineMode.ORGANIZATION, "Redaktion").status shouldBe HttpStatusCode.OK
                row = rowOf(published)
                row[ArticleTable.bylineKind] shouldBe ArticleBylineKind.ORGANIZATION

                // NONE clears the line again
                board(BOARD_MEMBER, published, BoardBylineMode.NONE).status shouldBe HttpStatusCode.OK
                row = rowOf(published)
                row[ArticleTable.byline] shouldBe null
                row[ArticleTable.bylineKind] shouldBe null
            }
        }

        test("the board path has no way to create a PERSONAL line: only NONE and ORGANIZATION exist, and the text is validated") {
            BoardBylineMode.entries.map { it.name } shouldBe listOf("NONE", "ORGANIZATION")
            val author = createMember("byline-board-valid@example.org")
            val id = createArticle(author, status = ArticleStatus.PUBLISHED)
            app {
                board(BOARD_MEMBER, id, BoardBylineMode.ORGANIZATION, null).status shouldBe HttpStatusCode.BadRequest
                board(BOARD_MEMBER, id, BoardBylineMode.ORGANIZATION, " ").status shouldBe HttpStatusCode.BadRequest
                board(BOARD_MEMBER, id, BoardBylineMode.ORGANIZATION, "x".repeat(121)).status shouldBe HttpStatusCode.BadRequest
                rowOf(id)[ArticleTable.byline] shouldBe null
            }
        }

        test("the board cannot change the line of a DRAFT or REJECTED article, and a member without a board role gets 403") {
            val author = createMember("byline-board-status@example.org")
            val draft = createArticle(author)
            val rejected = createArticle(author, status = ArticleStatus.REJECTED)
            val published = createArticle(author, status = ArticleStatus.PUBLISHED)
            val other = createMember("byline-not-board@example.org")
            app {
                board(BOARD_MEMBER, draft, BoardBylineMode.ORGANIZATION, "PdV").status shouldBe HttpStatusCode.Conflict
                board(BOARD_MEMBER, rejected, BoardBylineMode.NONE).status shouldBe HttpStatusCode.Conflict
                board(other, published, BoardBylineMode.ORGANIZATION, "PdV").status shouldBe HttpStatusCode.Forbidden
                board(author, published, BoardBylineMode.NONE).status shouldBe HttpStatusCode.Forbidden
                rowOf(published)[ArticleTable.byline] shouldBe null
            }
        }

        test("four eyes: a board member cannot set the line of their OWN article, but can for an imported one they executed") {
            val own = createArticle(BOARD_MEMBER, status = ArticleStatus.PUBLISHED)
            val imported =
                createArticle(
                    BOARD_MEMBER,
                    status = ArticleStatus.PUBLISHED,
                    imported = true,
                    byline = "PdV",
                    kind = ArticleBylineKind.IMPORTED,
                )
            app {
                board(BOARD_MEMBER, own, BoardBylineMode.ORGANIZATION, "PdV").status shouldBe HttpStatusCode.Forbidden
                board(BOARD_MEMBER, imported, BoardBylineMode.ORGANIZATION, "Partei der Vernunft").status shouldBe HttpStatusCode.OK
                rowOf(imported)[ArticleTable.byline] shouldBe "Partei der Vernunft"
                board(BOARD_MEMBER, imported, BoardBylineMode.NONE).status shouldBe HttpStatusCode.OK
                rowOf(imported)[ArticleTable.byline] shouldBe null
            }
        }

        test("status transitions leave the line alone: submit, withdraw, approve, reject, unpublish") {
            val author = createMember("byline-transitions@example.org")
            app {
                val id = createArticle(author)
                client.post("/test/save-draft/$id") { header("X-Member-Id", author.toString()) } // makes the draft valid for submit
                own(author, id, consent = true, text = "Max Muster")
                client.post("/test/submit/$id") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.byline] shouldBe "Max Muster"
                client.post("/test/withdraw/$id") { header("X-Member-Id", author.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.byline] shouldBe "Max Muster"
                client.post("/test/submit/$id") { header("X-Member-Id", author.toString()) }
                client.post("/test/approve/$id") { header("X-Member-Id", BOARD_MEMBER.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.status] shouldBe ArticleStatus.PUBLISHED
                rowOf(id)[ArticleTable.byline] shouldBe "Max Muster"
                rowOf(id)[ArticleTable.bylineKind] shouldBe ArticleBylineKind.PERSONAL
                client.post("/test/unpublish/$id") { header("X-Member-Id", ADMIN_MEMBER.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.byline] shouldBe "Max Muster"
            }
        }

        test("the audit log never contains the byline text, only kinds and the consent flag") {
            val author = createMember("byline-audit@example.org")
            val id = createArticle(author, status = ArticleStatus.PUBLISHED)
            val draft = createArticle(author)
            app {
                own(author, draft, consent = true, text = secret)
                own(author, draft, consent = false)
                board(BOARD_MEMBER, id, BoardBylineMode.ORGANIZATION, "$secret-Org")
                client.post("/test/revoke-byline/$id") { header("X-Member-Id", author.toString()) }
                board(BOARD_MEMBER, id, BoardBylineMode.NONE)
            }
            val audit = auditTextOf(draft) + auditTextOf(id)
            audit shouldNotContain secret
            audit.contains("bylineKind") shouldBe true
            audit.contains("PERSONAL") shouldBe true
            audit.contains("ORGANIZATION") shouldBe true
        }

        test("a personal line change is rate limited like a draft update") {
            val author = createMember("byline-rate@example.org")
            val id = createArticle(author)
            app(draftUpdateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                own(author, id, consent = true, text = "Eins").status shouldBe HttpStatusCode.OK
                own(author, id, consent = true, text = "Zwei").status shouldBe HttpStatusCode.TooManyRequests
                rowOf(id)[ArticleTable.byline] shouldBe "Eins"
            }
        }

        test(
            "an imported article is not 'mine': invisible in listMyArticles and neither editable nor submittable through the author path",
        ) {
            val importer = createMember("byline-importer@example.org")
            val id =
                createArticle(
                    importer,
                    status = ArticleStatus.PUBLISHED,
                    imported = true,
                    byline = "PdV",
                    kind = ArticleBylineKind.IMPORTED,
                )
            app {
                client.post("/test/list-mine") { header("X-Member-Id", importer.toString()) }.bodyAsText().contains(id.toString()) shouldBe
                    false
                client.post("/test/save-draft/$id") { header("X-Member-Id", importer.toString()) }.status shouldBe HttpStatusCode.NotFound
                client.post("/test/submit/$id") { header("X-Member-Id", importer.toString()) }.status shouldBe HttpStatusCode.NotFound
                own(importer, id, consent = true, text = "Ich").status shouldBe HttpStatusCode.NotFound
                client.post("/test/revoke-byline/$id") { header("X-Member-Id", importer.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("the importing board member can still depublish their own import (four-eyes does not apply to imports)") {
            val id = createArticle(BOARD_MEMBER, status = ArticleStatus.PUBLISHED, imported = true)
            bylineMailer.calls.clear()
            app {
                client.post("/test/unpublish/$id") { header("X-Member-Id", BOARD_MEMBER.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.status] shouldBe ArticleStatus.REJECTED
                // An import has no human author in Lapis: the importer must not get a "your article was depublished" mail.
                bylineMailer.calls.isEmpty() shouldBe true
            }
        }

        test(
            "an imported SUBMITTED article cannot be approved or rejected by its importer, only by another board member, keeping the historic date",
        ) {
            val historic = LocalDateTime(2019, 3, 4, 10, 0)
            val id = createArticle(BOARD_MEMBER, status = ArticleStatus.SUBMITTED, imported = true)
            transaction { ArticleTable.update({ ArticleTable.id eq id }) { it[publishedAt] = historic } }
            app {
                client.post("/test/approve/$id") { header("X-Member-Id", BOARD_MEMBER.toString()) }.status shouldBe HttpStatusCode.Forbidden
                rowOf(id)[ArticleTable.status] shouldBe ArticleStatus.SUBMITTED
                client.post("/test/approve/$id") { header("X-Member-Id", ADMIN_MEMBER.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(id)[ArticleTable.status] shouldBe ArticleStatus.PUBLISHED
                rowOf(id)[ArticleTable.publishedAt] shouldBe historic
                rowOf(id)[ArticleTable.reviewedBy] shouldBe ADMIN_MEMBER
            }
        }

        test("the MCP draft path never sets a byline: the tool has no byline parameter and a created draft has none") {
            CreateArticleDraftTool.definition.inputSchema
                .toString()
                .contains("byline") shouldBe false
            val result =
                McpArticleDraftStore.createDraft(
                    memberId = BOARD_MEMBER,
                    agentLabel = "Byline-Test-Agent",
                    title = "MCP-Entwurf ${Uuid.random()}",
                    excerpt = "Auszug",
                    body = "Inhalt",
                )
            createdArticleIds += result.articleId
            val row = rowOf(result.articleId)
            row[ArticleTable.byline] shouldBe null
            row[ArticleTable.bylineKind] shouldBe null
            row[ArticleTable.bylineConsentAt] shouldBe null
            row[ArticleTable.imported] shouldBe false
        }
    })

/** Shared across the per-request services of this file's test routes, so a test can assert that no mail was sent. */
private val bylineMailer = FakeArticleReviewNotificationMailer()

private fun Route.bylineRoutes(draftUpdateLimiter: FederationInboxRateLimiter) {
    fun service(call: ApplicationCall) =
        ArticleService(
            call = call,
            baseUrl = "https://test.invalid",
            previewRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
            draftCreateRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 10.minutes),
            draftUpdateRateLimiter = draftUpdateLimiter,
            reviewNotifier = bylineMailer,
            coverStorage = EventCoverStorage(createTempDirectory("article-covers-byline-test").toFile()),
        )
    post("/test/own-byline/{id}") {
        val q = call.request.queryParameters
        val a = service(call).setOwnByline(id = call.parameters["id"]!!, consent = q["consent"] == "true", text = q["text"])
        call.respondText("${a.bylineKind}:${a.byline}")
    }
    post("/test/revoke-byline/{id}") {
        val a = service(call).revokeOwnByline(call.parameters["id"]!!)
        call.respondText("${a.bylineKind}:${a.byline}")
    }
    post("/test/board-byline/{id}") {
        val q = call.request.queryParameters
        val s =
            service(call).setBylineAsBoard(
                id = call.parameters["id"]!!,
                input = BoardBylineInput(mode = BoardBylineMode.valueOf(q["mode"]!!), text = q["text"]),
            )
        call.respondText("${s.bylineKind}:${s.byline}")
    }
    post("/test/save-draft/{id}") {
        // fills title/excerpt/body of an existing draft so that it passes `submitArticle`'s validation
        val a =
            service(call).saveDraft(
                id = call.parameters["id"],
                input =
                    network.lapis.cloud.shared.domain
                        .ArticleDraftInput(title = "Ein guter Titel", excerpt = "Ein Auszug", body = "Textkörper"),
            )
        call.respondText(a.status.name)
    }
    post("/test/submit/{id}") { call.respondText(service(call).submitArticle(call.parameters["id"]!!).status.name) }
    post("/test/withdraw/{id}") { call.respondText(service(call).withdrawArticle(call.parameters["id"]!!).status.name) }
    post("/test/approve/{id}") { call.respondText(service(call).approveArticle(call.parameters["id"]!!).status.name) }
    post("/test/unpublish/{id}") {
        call.respondText(service(call).unpublishArticle(id = call.parameters["id"]!!, reason = "Ausreichend langer Grund").status.name)
    }
    post("/test/list-mine") { call.respondText(service(call).listMyArticles().joinToString(",") { it.id }) }
}
