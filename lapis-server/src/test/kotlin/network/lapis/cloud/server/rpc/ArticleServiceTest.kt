package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * Exercises [ArticleService] end to end, mirroring [CrowdfundingServiceTest]'s house style
 * (throwaway routes calling the service class directly). DevSeedData's ADMIN/BOARD accounts are
 * used only as review ACTORS -- every article author is a fresh test member, so this file's
 * assertions never become order-dependent on other Spec classes sharing the same H2 instance.
 */
class ArticleServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) ArticleTable.deleteWhere { ArticleTable.id inList createdArticleIds }
                if (createdMemberIds.isNotEmpty()) {
                    // audit_log_entry.actor_member_id FKs to member -- every submit/approve/reject/
                    // unpublish call in this Spec wrote one, must go before the member rows do.
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Artikel Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
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

        test("saveDraft: create, then update while DRAFT, ceilings enforced even on a draft") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-draft@example.org")

                val created =
                    client
                        .post("/test/save-draft?title=Erster%20Entwurf&excerpt=Kurz&body=Hallo") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                val (id, status) = created.split(":")
                status shouldBe "DRAFT"
                createdArticleIds += Uuid.parse(id)

                val updated =
                    client
                        .post("/test/save-draft?id=$id&title=Ueberarbeitet&excerpt=Kurz2&body=Hallo2") {
                            header("X-Member-Id", author.toString())
                        }.bodyAsText()
                updated shouldBe "$id:DRAFT"

                val tooLongTitle = "x".repeat(200)
                val response =
                    client.post("/test/save-draft?id=$id&title=$tooLongTitle&excerpt=Kurz&body=Hallo") {
                        header("X-Member-Id", author.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("submitArticle: DRAFT -> SUBMITTED requires full validation; too-short title is rejected; withdraw returns it to DRAFT") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-submit@example.org")

                val incomplete =
                    client
                        .post("/test/save-draft?title=&excerpt=&body=") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                val (incompleteId, _) = incomplete.split(":")
                createdArticleIds += Uuid.parse(incompleteId)

                val submitTooShort =
                    client.post("/test/submit/$incompleteId") { header("X-Member-Id", author.toString()) }
                submitTooShort.status shouldBe HttpStatusCode.BadRequest

                val complete =
                    client
                        .post(
                            "/test/save-draft?title=Ein%20guter%20Titel&excerpt=Ein%20Auszug&body=Textkoerper",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                val (id, _) = complete.split(":")
                createdArticleIds += Uuid.parse(id)

                val submitted = client.post("/test/submit/$id") { header("X-Member-Id", author.toString()) }.bodyAsText()
                submitted shouldBe "SUBMITTED"

                val reSubmit = client.post("/test/submit/$id") { header("X-Member-Id", author.toString()) }
                reSubmit.status shouldBe HttpStatusCode.Conflict

                val withdrawn = client.post("/test/withdraw/$id") { header("X-Member-Id", author.toString()) }.bodyAsText()
                withdrawn shouldBe "DRAFT"
            }
        }

        test("approveArticle: assigns a slug exactly once, is idempotently stable on re-read, and audits the transition") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-approve@example.org")
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Ein%20Titel%20fuer%20Freigabe&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdArticleIds += Uuid.parse(draftId)
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }

                val approved =
                    client.post("/test/approve/$draftId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                approved shouldBe "PUBLISHED"

                val slug =
                    transaction { ArticleTable.selectAll().where { ArticleTable.id eq Uuid.parse(draftId) }.single()[ArticleTable.slug] }
                slug shouldBe "ein-titel-fuer-freigabe"

                val reApprove = client.post("/test/approve/$draftId") { header("X-Member-Id", BOARD_ID) }
                reApprove.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("Vier-Augen-Prinzip: an ADMIN who is also the author cannot approve/reject/unpublish their own article") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                // Reuse the seeded ADMIN account as author, exactly the scenario the plan requires
                // a dedicated test for -- role BOARD/ADMIN must not bypass the ownership check.
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Admin%20schreibt%20selbst&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split(":")[0]
                createdArticleIds += Uuid.parse(draftId)
                client.post("/test/submit/$draftId") { header("X-Member-Id", ADMIN_ID) }

                val selfApprove = client.post("/test/approve/$draftId") { header("X-Member-Id", ADMIN_ID) }
                selfApprove.status shouldBe HttpStatusCode.Forbidden

                val selfReject = client.post("/test/reject/$draftId") { header("X-Member-Id", ADMIN_ID) }
                selfReject.status shouldBe HttpStatusCode.Forbidden

                // A different board member CAN approve it.
                val approved = client.post("/test/approve/$draftId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                approved shouldBe "PUBLISHED"

                val selfUnpublish =
                    client.post("/test/unpublish/$draftId?reason=Testgrund%20zehn%20Zeichen") { header("X-Member-Id", ADMIN_ID) }
                selfUnpublish.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("rejectArticle: SUBMITTED -> REJECTED sets rejectionReason and audits the transition; reason may be omitted") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-reject@example.org")
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Ein%20abzulehnender%20Artikel&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdArticleIds += Uuid.parse(draftId)
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }

                val rejected =
                    client
                        .post("/test/reject/$draftId?reason=Thema%20ist%20bereits%20abgedeckt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                rejected shouldBe "REJECTED"

                val row = transaction { ArticleTable.selectAll().where { ArticleTable.id eq Uuid.parse(draftId) }.single() }
                row[ArticleTable.rejectionReason] shouldBe "Thema ist bereits abgedeckt"
                row[ArticleTable.reviewedBy] shouldBe Uuid.parse(BOARD_ID)
                (row[ArticleTable.reviewedAt] != null) shouldBe true

                val reReject = client.post("/test/reject/$draftId?reason=Erneut") { header("X-Member-Id", BOARD_ID) }
                reReject.status shouldBe HttpStatusCode.Conflict

                // A REJECTED/DRAFT article may be re-submitted and rejected again with NO reason
                // at all -- [reason] is optional on rejectArticle (unlike unpublishArticle's
                // mandatory one), see IArticleService KDoc.
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }
                val rejectedNoReason = client.post("/test/reject/$draftId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                rejectedNoReason shouldBe "REJECTED"
                val rowNoReason = transaction { ArticleTable.selectAll().where { ArticleTable.id eq Uuid.parse(draftId) }.single() }
                rowNoReason[ArticleTable.rejectionReason] shouldBe null
            }
        }

        test("rejectArticle: reason exceeding REJECTION_REASON_MAX (1000) characters is rejected before any row lock") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-reject-toolong@example.org")
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Artikel%20mit%20zu%20langem%20Grund&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdArticleIds += Uuid.parse(draftId)
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }

                val tooLongReason = "x".repeat(1001)
                val response =
                    client.post("/test/reject/$draftId?reason=$tooLongReason") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.BadRequest

                // Still SUBMITTED -- the oversized reason was refused before any state change.
                val row = transaction { ArticleTable.selectAll().where { ArticleTable.id eq Uuid.parse(draftId) }.single() }
                row[ArticleTable.status].name shouldBe "SUBMITTED"
            }
        }

        test("unpublishArticle: PUBLISHED -> REJECTED keeps slug+publishedAt, requires a reason of at least 10 characters") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-unpublish@example.org")
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Zu%20depublizierender%20Artikel&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdArticleIds += Uuid.parse(draftId)
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }
                client.post("/test/approve/$draftId") { header("X-Member-Id", BOARD_ID) }

                val tooShortReason = client.post("/test/unpublish/$draftId?reason=kurz") { header("X-Member-Id", BOARD_ID) }
                tooShortReason.status shouldBe HttpStatusCode.BadRequest

                val unpublished =
                    client
                        .post("/test/unpublish/$draftId?reason=Ausreichend%20langer%20Grund") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                unpublished shouldBe "REJECTED"

                val row = transaction { ArticleTable.selectAll().where { ArticleTable.id eq Uuid.parse(draftId) }.single() }
                row[ArticleTable.slug] shouldBe "zu-depublizierender-artikel"
                (row[ArticleTable.publishedAt] != null) shouldBe true
            }
        }

        test("deleteDraft: only DRAFT is deletable") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-delete@example.org")
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Zu%20loeschender%20Entwurf&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }

                val deleteWhileSubmitted = client.post("/test/delete-draft/$draftId") { header("X-Member-Id", author.toString()) }
                deleteWhileSubmitted.status shouldBe HttpStatusCode.Conflict
                createdArticleIds += Uuid.parse(draftId)

                client.post("/test/withdraw/$draftId") { header("X-Member-Id", author.toString()) }
                val deleteWhileDraft = client.post("/test/delete-draft/$draftId") { header("X-Member-Id", author.toString()) }
                deleteWhileDraft.status shouldBe HttpStatusCode.OK
            }
        }

        test("non-active membership cannot save a draft; non-board role cannot approve") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val guest = createTestMember("article-guest@example.org", status = MemberStatus.GUEST)
                val forbiddenDraft =
                    client.post("/test/save-draft?title=Gast&excerpt=x&body=y") { header("X-Member-Id", guest.toString()) }
                forbiddenDraft.status shouldBe HttpStatusCode.Forbidden

                val author = createTestMember("article-nonboard@example.org")
                val draftId =
                    client
                        .post(
                            "/test/save-draft?title=Von%20normalem%20Mitglied&excerpt=Auszug&body=Inhalt",
                        ) { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                        .split(":")[0]
                createdArticleIds += Uuid.parse(draftId)
                client.post("/test/submit/$draftId") { header("X-Member-Id", author.toString()) }

                val forbiddenApprove = client.post("/test/approve/$draftId") { header("X-Member-Id", author.toString()) }
                forbiddenApprove.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("previewArticle: renders Markdown, list/get roundtrip works for the author's own articles") {
            testApplication {
                application {
                    install(StatusPages) { installArticleExceptionHandlers() }
                    routing { registerArticleTestRoutes() }
                }
                val author = createTestMember("article-preview@example.org")
                val html =
                    client
                        .get("/test/preview?body=%23%20Titel") { header("X-Member-Id", author.toString()) }
                        .bodyAsText()
                html shouldBe "<h2>Titel</h2>\n"
            }
        }
    })

private fun StatusPagesConfig.installArticleExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerArticleTestRoutes() {
    fun service(call: io.ktor.server.application.ApplicationCall) = ArticleService(call = call, baseUrl = "https://test.invalid")

    post("/test/save-draft") {
        val q = call.request.queryParameters
        val a =
            service(call).saveDraft(
                id = q["id"],
                input = ArticleDraftInput(title = q["title"] ?: "", excerpt = q["excerpt"] ?: "", body = q["body"] ?: ""),
            )
        call.respondText("${a.id}:${a.status}")
    }
    post("/test/submit/{id}") {
        val a = service(call).submitArticle(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    post("/test/withdraw/{id}") {
        val a = service(call).withdrawArticle(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    post("/test/delete-draft/{id}") {
        service(call).deleteDraft(call.parameters["id"]!!)
        call.respondText("ok")
    }
    post("/test/approve/{id}") {
        val a = service(call).approveArticle(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    post("/test/reject/{id}") {
        val reason = call.request.queryParameters["reason"]
        val a = service(call).rejectArticle(id = call.parameters["id"]!!, reason = reason)
        call.respondText(a.status.name)
    }
    post("/test/unpublish/{id}") {
        val reason = call.request.queryParameters["reason"] ?: ""
        val a = service(call).unpublishArticle(id = call.parameters["id"]!!, reason = reason)
        call.respondText(a.status.name)
    }
    get("/test/preview") {
        val body = call.request.queryParameters["body"] ?: ""
        call.respondText(service(call).previewArticle(body))
    }
}
