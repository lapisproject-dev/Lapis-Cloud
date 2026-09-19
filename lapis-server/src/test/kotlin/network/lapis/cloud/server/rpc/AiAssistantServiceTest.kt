package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.ai.FakeLlmClient
import network.lapis.cloud.server.ai.RecordingAuditSink
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.kb.KnowledgeIndexer
import network.lapis.cloud.server.ai.llm.LlmResult
import network.lapis.cloud.server.ai.operationalAiConfig
import network.lapis.cloud.server.ai.qa.StatuteQaPipeline
import network.lapis.cloud.server.ai.ratelimit.AiQuestionRateLimiter
import network.lapis.cloud.server.ai.retrieval.SimpleLikeKnowledgeRetriever
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AiFeature
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.AiDocumentNotReleasableException
import network.lapis.cloud.shared.rpc.AiFeatureDisabledException
import network.lapis.cloud.shared.rpc.AiOptInMissingException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.uuid.Uuid

/**
 * RPC-surface coverage of [AiAssistantService] via throwaway test routes + `X-Member-Id`, the house
 * style [CateringServiceRpcTest] establishes. The model is a [FakeLlmClient] -- **no test in this
 * suite makes a network call**.
 */
class AiAssistantServiceTest :
    FunSpec({
        val fixtures = AiTestFixtures()
        val statute = "Der Mitgliedsbeitrag beträgt zehn Euro im Monat und ist zum Monatsersten fällig."

        beforeSpec { DatabaseConfig.connect() }

        // Per-test cleanup: released documents of one test must never be searchable by the next one.
        afterTest { fixtures.cleanup() }

        afterSpec { fixtures.dispose() }

        class Harness(
            val config: AiConfig = operationalAiConfig(),
            val llm: FakeLlmClient =
                FakeLlmClient { LlmResult.Success(text = "Zehn Euro monatlich.\nQUELLEN: 1", tokensIn = 3, tokensOut = 2) },
            val limiter: AiQuestionRateLimiter = AiQuestionRateLimiter(perMemberPerHour = 100, perServerPerDay = 1000),
        ) {
            val audit = RecordingAuditSink()
            val indexer = KnowledgeIndexer(storageRoot = fixtures.storageRoot)
            val pipeline =
                StatuteQaPipeline(retriever = SimpleLikeKnowledgeRetriever(), llmClient = llm, auditSink = audit, config = config)

            fun service(call: ApplicationCall) =
                AiAssistantService(call = call, config = config, pipeline = pipeline, indexer = indexer, rateLimiter = limiter)
        }

        fun Route.aiRoutes(h: Harness) {
            post("/test/ai/state") {
                val s = h.service(call).getAssistantState()
                call.respondText(
                    "${s.featureEnabled}|${s.optIn}|${s.indexedScope.joinToString(
                        ",",
                    ) { it.documentTitle }}|${s.unindexedScope.joinToString(",") { it.documentTitle + ":" + it.unindexedReason }}",
                )
            }
            post("/test/ai/optin") {
                val enabled = call.request.queryParameters["enabled"]!!.toBoolean()
                val s = h.service(call).setMemberOptIn(feature = AiFeature.STATUTE_QA, enabled = enabled)
                call.respondText(s.optIn.toString())
            }
            post("/test/ai/ask") {
                val a = h.service(call).askStatuteQuestion(call.request.queryParameters["q"]!!)
                call.respondText(
                    "${a.outcome}|${a.summary}|${a.citations.joinToString(
                        ",",
                    ) { it.documentTitle + "/" + it.locator }}|${a.retryAfterSeconds}|${a.searchedDocuments.size}",
                )
            }
            post("/test/ai/list") {
                call.respondText(
                    h
                        .service(call)
                        .listKnowledgeEntries()
                        .size
                        .toString(),
                )
            }
            post("/test/ai/list-titles") {
                call.respondText(h.service(call).listKnowledgeEntries().joinToString("|") { it.title + "@" + it.accessLevel })
            }
            post("/test/ai/release") {
                val e =
                    h
                        .service(
                            call,
                        ).setKnowledgeBaseRelease(
                            documentId = call.request.queryParameters["doc"]!!,
                            released = call.request.queryParameters["released"]!!.toBoolean(),
                        )
                call.respondText("${e.released}|${e.status}|${e.chunkCount}|${e.releasable}")
            }
            post("/test/ai/reindex") {
                val e = h.service(call).reindexKnowledgeDocument(call.request.queryParameters["doc"]!!)
                call.respondText("${e.status}|${e.chunkCount}")
            }
        }

        fun StatusPagesConfig.aiExceptionHandlers() {
            exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
            exception<AiOptInMissingException> {
                call,
                cause,
                ->
                call.respondText(cause.message, status = HttpStatusCode.PreconditionFailed)
            }
            exception<AiDocumentNotReleasableException> {
                call,
                cause,
                ->
                call.respondText(cause.message, status = HttpStatusCode.UnprocessableEntity)
            }
            exception<AiFeatureDisabledException> {
                call,
                cause,
                ->
                call.respondText(cause.message, status = HttpStatusCode.ServiceUnavailable)
            }
        }

        fun withApp(
            h: Harness = Harness(),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.(Harness) -> Unit,
        ) = testApplication {
            application {
                install(StatusPages) { aiExceptionHandlers() }
                routing { aiRoutes(h) }
            }
            block(h)
        }

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.call(
            path: String,
            member: Uuid,
        ) = client.post(path) { header("X-Member-Id", member.toString()) }

        /** Creates an admin-released, indexed PUBLIC_MEMBERS statute document containing [statute]. */
        fun releasedStatute(h: Harness): Pair<Uuid, network.lapis.cloud.server.ai.TestDocument> {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc =
                fixtures.document(
                    author = admin,
                    title = "Satzung ${Uuid.random()}",
                    fileBytes = "§ 7 Beitrag\n$statute".toByteArray(),
                )
            network.lapis.cloud.server.ai.kb.KnowledgeReleaseStore
                .release(documentId = doc.id, releasedBy = admin)
            h.indexer.indexDocument(doc.id)
            return admin to doc
        }

        test("a member without opt-in is refused, and the model is never called") {
            withApp { h ->
                releasedStatute(h)
                val member = fixtures.member()
                val response = call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag", member)
                response.status shouldBe HttpStatusCode.PreconditionFailed
                h.llm.callCount shouldBe 0
            }
        }

        test("setMemberOptIn persists and is idempotent") {
            withApp { _ ->
                val member = fixtures.member()
                call("/test/ai/state", member).bodyAsText() shouldContain "true|false|"
                call("/test/ai/optin?enabled=true", member).bodyAsText() shouldBe "true"
                call("/test/ai/optin?enabled=true", member).bodyAsText() shouldBe "true"
                call("/test/ai/state", member).bodyAsText() shouldContain "true|true|"
                call("/test/ai/optin?enabled=false", member).bodyAsText() shouldBe "false"
            }
        }

        test("question length is validated before anything else consumes quota") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 1, perServerPerDay = 1000)
            withApp(Harness(limiter = limiter)) { h ->
                val member = fixtures.member()
                call("/test/ai/optin?enabled=true", member)
                call("/test/ai/ask?q=kurz", member).status shouldBe HttpStatusCode.BadRequest
                call("/test/ai/ask?q=${"x".repeat(501)}", member).status shouldBe HttpStatusCode.BadRequest
                // Still has its single question left -> not RATE_LIMITED.
                call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag", member).bodyAsText() shouldNotContain "RATE_LIMITED"
                h.llm.callCount shouldBe 0 // nothing released -> NOTHING_FOUND, no call
            }
        }

        test("a non organization member (FRIEND) is forbidden") {
            withApp { _ ->
                val friend = fixtures.member(status = MemberStatus.FRIEND)
                call("/test/ai/state", friend).status shouldBe HttpStatusCode.Forbidden
                call("/test/ai/optin?enabled=true", friend).status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("an unauthenticated caller gets 401") {
            withApp { _ ->
                client.post("/test/ai/state").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("release/revoke/list/reindex are forbidden for MEMBER and for TREASURER, allowed for BOARD and ADMIN") {
            withApp { _ ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val doc = fixtures.document(author = admin, fileBytes = statute.toByteArray())
                listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEach { role ->
                    val member = fixtures.member(role = role)
                    call("/test/ai/list", member).status shouldBe HttpStatusCode.Forbidden
                    call("/test/ai/release?doc=${doc.id}&released=true", member).status shouldBe HttpStatusCode.Forbidden
                    call("/test/ai/reindex?doc=${doc.id}", member).status shouldBe HttpStatusCode.Forbidden
                }
                listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                    val privileged = fixtures.member(role = role)
                    call("/test/ai/list", privileged).status shouldBe HttpStatusCode.OK
                    call("/test/ai/release?doc=${doc.id}&released=true", privileged).bodyAsText() shouldContain "true|INDEXED"
                    call("/test/ai/reindex?doc=${doc.id}", privileged).bodyAsText() shouldContain "INDEXED"
                    call("/test/ai/release?doc=${doc.id}&released=false", privileged).bodyAsText() shouldContain "false|NOT_RELEASED|0|true"
                }
            }
        }

        test("listKnowledgeEntries never discloses documents above the caller's level (BOARD must not see ADMIN_ONLY)") {
            withApp { _ ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val board = fixtures.member(role = AccountRole.BOARD)

                fun docAt(level: DocumentAccessLevel) = fixtures.document(author = admin, level = level, fileBytes = statute.toByteArray())

                val publicDoc = docAt(level = DocumentAccessLevel.PUBLIC_MEMBERS)
                val boardDoc = docAt(level = DocumentAccessLevel.BOARD_ONLY)
                val adminDoc = docAt(level = DocumentAccessLevel.ADMIN_ONLY)

                val boardView = call("/test/ai/list-titles", board).bodyAsText()
                boardView shouldContain publicDoc.title
                boardView shouldContain boardDoc.title
                boardView shouldNotContain adminDoc.title

                val adminView = call("/test/ai/list-titles", admin).bodyAsText()
                adminView shouldContain adminDoc.title
                adminView shouldContain boardDoc.title
                adminView shouldContain publicDoc.title
            }
        }

        test("release/revoke/reindex of an ADMIN_ONLY document by BOARD is 404, not a title leak or 422") {
            withApp { _ ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val board = fixtures.member(role = AccountRole.BOARD)
                val adminDoc = fixtures.document(author = admin, level = DocumentAccessLevel.ADMIN_ONLY, fileBytes = statute.toByteArray())
                val revoke = call("/test/ai/release?doc=${adminDoc.id}&released=false", board)
                revoke.status shouldBe HttpStatusCode.NotFound
                revoke.bodyAsText() shouldNotContain adminDoc.title
                call("/test/ai/release?doc=${adminDoc.id}&released=true", board).status shouldBe HttpStatusCode.NotFound
                call("/test/ai/reindex?doc=${adminDoc.id}", board).status shouldBe HttpStatusCode.NotFound
                // ADMIN still gets the documented 422 for a non-releasable level.
                call("/test/ai/release?doc=${adminDoc.id}&released=true", admin).status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("only PUBLIC_MEMBERS documents are releasable") {
            withApp { _ ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                listOf(DocumentAccessLevel.BOARD_ONLY, DocumentAccessLevel.ADMIN_ONLY).forEach { level ->
                    val doc = fixtures.document(author = admin, level = level, fileBytes = statute.toByteArray())
                    call("/test/ai/release?doc=${doc.id}&released=true", admin).status shouldBe HttpStatusCode.UnprocessableEntity
                    call("/test/ai/reindex?doc=${doc.id}", admin).status shouldBe HttpStatusCode.UnprocessableEntity
                }
            }
        }

        test("releasing a missing document is 404, an invalid id is 400, reindexing an unreleased one is 409") {
            withApp { _ ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                call("/test/ai/release?doc=${Uuid.random()}&released=true", admin).status shouldBe HttpStatusCode.NotFound
                call("/test/ai/release?doc=not-a-uuid&released=true", admin).status shouldBe HttpStatusCode.BadRequest
                val doc = fixtures.document(author = admin, fileBytes = statute.toByteArray())
                call("/test/ai/reindex?doc=${doc.id}", admin).status shouldBe HttpStatusCode.Conflict
            }
        }

        test("getAssistantState lists indexed and not-indexable documents of the searchable scope") {
            withApp { h ->
                val (_, good) = releasedStatute(h)
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val bad = fixtures.document(author = admin, fileBytes = byteArrayOf(1), mimeType = "application/zip", fileName = "x.zip")
                network.lapis.cloud.server.ai.kb.KnowledgeReleaseStore
                    .release(documentId = bad.id, releasedBy = admin)
                h.indexer.indexDocument(bad.id)
                val member = fixtures.member()
                val body = call("/test/ai/state", member).bodyAsText()
                body shouldContain good.title
                body shouldContain "${bad.title}:UNSUPPORTED_FORMAT"
            }
        }

        test("end to end: an opted-in member gets a summary with a citation from the stored record") {
            withApp { h ->
                val (_, doc) = releasedStatute(h)
                val member = fixtures.member()
                call("/test/ai/optin?enabled=true", member)
                val body = call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag im Monat", member).bodyAsText()
                body shouldContain "ANSWERED|Zehn Euro monatlich.|${doc.title}/§ 7 Beitrag"
                h.llm.callCount shouldBe 1
                h.audit.entries
                    .single()
                    .retrievedChunkCount shouldBe 1
            }
        }

        test("the per-member rate limit surfaces as a DTO outcome with a wait time") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 1, perServerPerDay = 1000)
            withApp(Harness(limiter = limiter)) { _ ->
                val member = fixtures.member()
                call("/test/ai/optin?enabled=true", member)
                call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag", member)
                val second = call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag", member).bodyAsText()
                second shouldContain "RATE_LIMITED"
                (second.split("|")[3].toInt() >= 1) shouldBe true
            }
        }

        test("a provider failure surfaces as a neutral PROVIDER_UNAVAILABLE outcome") {
            val llm = FakeLlmClient { LlmResult.Failure(kind = network.lapis.cloud.server.ai.llm.LlmFailureKind.TIMEOUT) }
            withApp(Harness(llm = llm)) { h ->
                releasedStatute(h)
                val member = fixtures.member()
                call("/test/ai/optin?enabled=true", member)
                val body = call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag im Monat", member).bodyAsText()
                body shouldContain "PROVIDER_UNAVAILABLE|null"
                body shouldNotContain "TIMEOUT"
            }
        }

        test("a released document later re-classified upwards never reaches the model, even for a board member") {
            withApp { h ->
                val (_, doc) = releasedStatute(h)
                fixtures.setAccessLevel(document = doc, level = DocumentAccessLevel.BOARD_ONLY)
                val board = fixtures.member(role = AccountRole.BOARD)
                call("/test/ai/optin?enabled=true", board)
                val body = call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag im Monat", board).bodyAsText()
                body shouldContain "NOTHING_FOUND"
                h.llm.callCount shouldBe 0
            }
        }

        test("nothing found lists the searched documents and never calls the model") {
            withApp { h ->
                releasedStatute(h)
                val member = fixtures.member()
                call("/test/ai/optin?enabled=true", member)
                val body = call("/test/ai/ask?q=Wetterbericht Sonnenschein Regenbogen", member).bodyAsText()
                body shouldContain "NOTHING_FOUND"
                h.llm.callCount shouldBe 0
                (body.split("|")[4].toInt() >= 1) shouldBe true
            }
        }

        test("with the feature not operational every method refuses") {
            withApp(Harness(config = AiConfig.load { null })) { _ ->
                val member = fixtures.member()
                call("/test/ai/state", member).status shouldBe HttpStatusCode.ServiceUnavailable
                call("/test/ai/ask?q=Wie hoch ist der Mitgliedsbeitrag", member).status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })
