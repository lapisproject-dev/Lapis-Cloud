package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Welle V1.9.7 "SuperMailer" -- covers [MailingService]'s Teil A (HTML drafts, previews) and the
 * async-send rewrite (D3 recipient filter, bounded DRAFT->QUEUED transition), same house style
 * [DsgvoServiceTest]/[PublicRankingConsentServiceTest] establish (throwaway routes calling the
 * service class directly, `X-Member-Id` trusted-header auth).
 */
class MailingServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdListIds = mutableListOf<Uuid>()
        lateinit var boardId: Uuid
        lateinit var plainMemberId: Uuid

        afterSpec {
            transaction {
                val messageIds =
                    MailingMessageTable
                        .selectAll()
                        .where {
                            MailingMessageTable.mailingListId inList createdListIds
                        }.map { it[MailingMessageTable.id] }
                MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId inList messageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList messageIds }
                MailingListSubscriptionTable.deleteWhere { MailingListSubscriptionTable.mailingListId inList createdListIds }
                MailingListTable.deleteWhere { MailingListTable.id inList createdListIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
            anonymizedAt: kotlinx.datetime.LocalDateTime? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Mailing-Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.anonymizedAt] = anonymizedAt
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

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            boardId = createMember(email = "board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
            plainMemberId = createMember(email = "plain-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
        }

        fun subscribe(
            listId: Uuid,
            memberId: Uuid,
        ) {
            transaction {
                MailingListSubscriptionTable.insert {
                    it[id] = Uuid.random()
                    it[mailingListId] = listId
                    it[MailingListSubscriptionTable.memberId] = memberId
                    it[subscribedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                }
            }
        }

        fun createList(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = id
                    it[name] = "Service-Testliste ${Uuid.random()}"
                    it[description] = null
                    it[MailingListTable.createdBy] = createdBy
                }
            }
            createdListIds += id
            return id
        }

        fun noOpWorker(mode: MailingDeliveryMode = MailingDeliveryMode.LOG) =
            MailingDeliveryWorker(
                transport = NoOpMailTransport(),
                branding = MailBranding.notConfigured(),
                mode = mode,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sendDelay = 0.milliseconds,
            )

        fun testApp(
            mode: MailingDeliveryMode = MailingDeliveryMode.LOG,
            worker: MailingDeliveryWorker? = null,
            block: suspend io.ktor.client.HttpClient.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, _ -> call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden) }
                        exception<ConflictException> {
                            call,
                            cause,
                            ->
                            call.respondText("CONFLICT:${cause.message}", status = HttpStatusCode.Conflict)
                        }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText("BAD_REQUEST:${cause.message}", status = HttpStatusCode.BadRequest)
                        }
                        exception<NotFoundException> { call, _ -> call.respondText("NOT_FOUND", status = HttpStatusCode.NotFound) }
                    }
                    routing {
                        fun service(call: ApplicationCall) =
                            MailingService(
                                call = call,
                                deliveryWorker = worker ?: noOpWorker(mode),
                                deliveryMode = mode,
                                branding = MailBranding.notConfigured(),
                            )
                        post("/test/draft") {
                            val q = call.request.queryParameters
                            val dto =
                                service(call).createDraftMessage(
                                    mailingListId = q["listId"]!!,
                                    subject = q["subject"]!!,
                                    bodyText = q["body"]!!,
                                )
                            call.respondText(dto.id)
                        }
                        post("/test/draft-html") {
                            val q = call.request.queryParameters
                            val dto =
                                service(call).createDraftMessageHtml(
                                    mailingListId = q["listId"]!!,
                                    subject = q["subject"]!!,
                                    bodyHtml = q["bodyHtml"]!!,
                                )
                            call.respondText("${dto.id}|${dto.bodyHtml}|${dto.bodyText}")
                        }
                        get("/test/preview/{id}") {
                            val dto = service(call).previewMailingMessage(messageId = call.parameters["id"]!!)
                            call.respondText("${dto.html}|${dto.plainText}")
                        }
                        post("/test/preview-html") {
                            val q = call.request.queryParameters
                            val dto = service(call).previewMailingHtml(subject = q["subject"]!!, bodyHtml = q["bodyHtml"]!!)
                            call.respondText(dto.html)
                        }
                        get("/test/mode") {
                            call.respondText(service(call).getMailingDeliveryMode().name)
                        }
                        post("/test/send/{id}") {
                            val dto = service(call).sendMailingMessage(call.parameters["id"]!!)
                            call.respondText(dto.status.name)
                        }
                    }
                }
                runBlocking { client.block() }
            }
        }

        test("createDraftMessage: BOARD can create, MEMBER cannot") {
            testApp {
                val listId = createList(createdBy = boardId)
                val ok = post("/test/draft?listId=$listId&subject=Betreff&body=Text") { header("X-Member-Id", boardId.toString()) }
                Uuid.parse(ok.bodyAsText()) // does not throw -- a real message id was returned

                val forbidden =
                    post("/test/draft?listId=$listId&subject=Betreff&body=Text") { header("X-Member-Id", plainMemberId.toString()) }
                forbidden.bodyAsText() shouldBe "FORBIDDEN"
            }
        }

        test("createDraftMessageHtml sanitizes the input and derives plain text") {
            testApp {
                val listId = createList(createdBy = boardId)
                val response =
                    post(
                        "/test/draft-html?listId=$listId&subject=Betreff&bodyHtml=" +
                            java.net.URLEncoder.encode("<p>Hallo <script>alert(1)</script><strong>Welt</strong>.</p>", "UTF-8"),
                    ) {
                        header("X-Member-Id", boardId.toString())
                    }
                val body = response.bodyAsText()
                body shouldContain "<strong>Welt</strong>"
                body shouldNotContain "<script>"
                body shouldContain "Hallo Welt."
            }
        }

        test("previewMailingMessage never contains a tracking-route URL") {
            testApp {
                val listId = createList(createdBy = boardId)
                val draft =
                    post(
                        "/test/draft-html?listId=$listId&subject=Betreff&bodyHtml=" +
                            java.net.URLEncoder.encode("<p><a href=\"https://example.org\">Link</a></p>", "UTF-8"),
                    ) {
                        header("X-Member-Id", boardId.toString())
                    }.bodyAsText()
                val messageId = draft.substringBefore("|")
                val preview = get("/test/preview/$messageId") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                preview shouldNotContain "/api/mailing/"
            }
        }

        test("previewMailingHtml is stateless -- does not create a message") {
            testApp {
                val before = transaction { MailingMessageTable.selectAll().count() }
                post("/test/preview-html?subject=Betreff&bodyHtml=" + java.net.URLEncoder.encode("<p>Text</p>", "UTF-8")) {
                    header("X-Member-Id", boardId.toString())
                }
                val after = transaction { MailingMessageTable.selectAll().count() }
                after shouldBe before
            }
        }

        test("getMailingDeliveryMode reflects the configured mode") {
            testApp(mode = MailingDeliveryMode.SMTP) {
                get("/test/mode") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "SMTP"
            }
            testApp(mode = MailingDeliveryMode.LOG) {
                get("/test/mode") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "LOG"
            }
        }

        test("sendMailingMessage: D3 filter excludes non-ACTIVE, anonymized subscribers; a second send call is a Conflict") {
            testApp {
                val listId = createList(createdBy = boardId)
                val activeSubscriber = createMember(email = "active-${Uuid.random()}@example.org")
                val guestSubscriber = createMember(email = "guest-${Uuid.random()}@example.org", status = MemberStatus.GUEST)
                val anonymizedSubscriber =
                    createMember(
                        email = "anon-${Uuid.random()}@example.org",
                        anonymizedAt =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime(),
                    )
                subscribe(listId = listId, memberId = activeSubscriber)
                subscribe(listId = listId, memberId = guestSubscriber)
                subscribe(listId = listId, memberId = anonymizedSubscriber)

                val draftId =
                    post("/test/draft?listId=$listId&subject=Betreff&body=Text") { header("X-Member-Id", boardId.toString()) }.bodyAsText()

                val firstSend = post("/test/send/$draftId") { header("X-Member-Id", boardId.toString()) }
                firstSend.bodyAsText() shouldBe "QUEUED"

                val deliveryMemberIds =
                    transaction {
                        MailingDeliveryLogTable
                            .selectAll()
                            .where {
                                MailingDeliveryLogTable.mailingMessageId eq Uuid.parse(draftId)
                            }.map { it[MailingDeliveryLogTable.memberId] }
                    }
                deliveryMemberIds shouldBe listOf(activeSubscriber)

                val secondSend = post("/test/send/$draftId") { header("X-Member-Id", boardId.toString()) }
                secondSend.bodyAsText().let { it.startsWith("CONFLICT") } shouldBe true
            }
        }

        test("sendMailingMessage: only BOARD/ADMIN may call it") {
            testApp {
                val listId = createList(createdBy = boardId)
                val draftId =
                    post("/test/draft?listId=$listId&subject=Betreff&body=Text") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val forbidden = post("/test/send/$draftId") { header("X-Member-Id", plainMemberId.toString()) }
                forbidden.bodyAsText() shouldBe "FORBIDDEN"
            }
        }

        test("sendMailingMessage on a non-existent message is NotFound") {
            testApp {
                val response = post("/test/send/${Uuid.random()}") { header("X-Member-Id", boardId.toString()) }
                response.bodyAsText() shouldBe "NOT_FOUND"
            }
        }

        // ── Review finding #3 (test coverage), W-SuperMailer round 1 ──────────────────────────────

        test("createDraftMessage rejects a blank subject") {
            testApp {
                val listId = createList(createdBy = boardId)
                val response =
                    post("/test/draft?listId=$listId&subject=" + java.net.URLEncoder.encode("   ", "UTF-8") + "&body=Text") {
                        header("X-Member-Id", boardId.toString())
                    }
                response.bodyAsText().let { it.startsWith("BAD_REQUEST") } shouldBe true
            }
        }

        test("createDraftMessage rejects a subject longer than MAX_SUBJECT_CHARS") {
            testApp {
                val listId = createList(createdBy = boardId)
                val tooLong = "x".repeat(MailingHtmlPolicy.MAX_SUBJECT_CHARS + 1)
                val response =
                    post("/test/draft?listId=$listId&subject=$tooLong&body=Text") { header("X-Member-Id", boardId.toString()) }
                response.bodyAsText().let { it.startsWith("BAD_REQUEST") } shouldBe true
            }
        }

        test("createDraftMessage rejects a subject containing a control character") {
            testApp {
                val listId = createList(createdBy = boardId)
                val response =
                    post(
                        "/test/draft?listId=$listId&subject=" +
                            java.net.URLEncoder.encode("Betreff\u0007", "UTF-8") + "&body=Text",
                    ) {
                        header("X-Member-Id", boardId.toString())
                    }
                response.bodyAsText().let { it.startsWith("BAD_REQUEST") } shouldBe true
            }
        }

        test("createDraftMessage on a non-existent mailing list is NotFound") {
            testApp {
                val response =
                    post("/test/draft?listId=${Uuid.random()}&subject=Betreff&body=Text") { header("X-Member-Id", boardId.toString()) }
                response.bodyAsText() shouldBe "NOT_FOUND"
            }
        }

        test("createDraftMessageHtml on a non-existent mailing list is NotFound") {
            testApp {
                val response =
                    post(
                        "/test/draft-html?listId=${Uuid.random()}&subject=Betreff&bodyHtml=" +
                            java.net.URLEncoder.encode("<p>Text</p>", "UTF-8"),
                    ) {
                        header("X-Member-Id", boardId.toString())
                    }
                response.bodyAsText() shouldBe "NOT_FOUND"
            }
        }

        test("previewMailingMessage on a non-existent message is NotFound") {
            testApp {
                val response = get("/test/preview/${Uuid.random()}") { header("X-Member-Id", boardId.toString()) }
                response.bodyAsText() shouldBe "NOT_FOUND"
            }
        }

        test(
            "sendMailingMessage rejects more than MAX_RECIPIENTS eligible recipients and rolls the DRAFT->QUEUED transition back",
        ) {
            testApp {
                val listId = createList(createdBy = boardId)
                val bulkMemberIds = (0 until MailingHtmlPolicy.MAX_RECIPIENTS + 1).map { Uuid.random() }
                transaction {
                    MemberTable.batchInsert(bulkMemberIds, shouldReturnGeneratedValues = false) { id ->
                        this[MemberTable.id] = id
                        this[MemberTable.displayName] = "Bulk-Testmitglied"
                        this[MemberTable.email] = "bulk-$id@example.org"
                        this[MemberTable.status] = MemberStatus.ACTIVE
                        this[MemberTable.joinedAt] = LocalDate(2020, 1, 1)
                    }
                    MailingListSubscriptionTable.batchInsert(bulkMemberIds, shouldReturnGeneratedValues = false) { id ->
                        this[MailingListSubscriptionTable.id] = Uuid.random()
                        this[MailingListSubscriptionTable.mailingListId] = listId
                        this[MailingListSubscriptionTable.memberId] = id
                        this[MailingListSubscriptionTable.subscribedAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                createdMemberIds += bulkMemberIds

                val draftId =
                    post("/test/draft?listId=$listId&subject=Betreff&body=Text") { header("X-Member-Id", boardId.toString()) }.bodyAsText()

                val response = post("/test/send/$draftId") { header("X-Member-Id", boardId.toString()) }
                response.bodyAsText().let { it.startsWith("BAD_REQUEST") } shouldBe true

                transaction {
                    MailingMessageTable
                        .selectAll()
                        .where { MailingMessageTable.id eq Uuid.parse(draftId) }
                        .single()[MailingMessageTable.status]
                } shouldBe MailingMessageStatus.DRAFT
                transaction {
                    MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.mailingMessageId eq Uuid.parse(draftId) }.count()
                } shouldBe 0
            }
        }

        test(
            "sendMailingMessage rolls the message back to DRAFT (and deletes its PENDING rows) when enqueue fails after commit " +
                "(review finding #2)",
        ) {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val hangingTransport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        started.complete(Unit)
                        gate.await()
                        return MailSendOutcome.Sent
                    }
                }
            val saturatedWorker =
                MailingDeliveryWorker(
                    transport = hangingTransport,
                    branding = MailBranding.notConfigured(),
                    mode = MailingDeliveryMode.SMTP,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    sendDelay = 0.milliseconds,
                )
            var blockerMessageId: Uuid? = null
            try {
                testApp(mode = MailingDeliveryMode.SMTP, worker = saturatedWorker) {
                    val blockerListId = createList(createdBy = boardId)
                    val blockerMemberId = createMember(email = "blocker-${Uuid.random()}@example.org")
                    subscribe(listId = blockerListId, memberId = blockerMemberId)
                    val blockerDraftId =
                        post("/test/draft?listId=$blockerListId&subject=Blocker&body=Text") {
                            header("X-Member-Id", boardId.toString())
                        }.bodyAsText()
                    blockerMessageId = Uuid.parse(blockerDraftId)
                    // Sends the blocker straight through the real worker (bypassing the queue
                    // saturation this test is about) -- once its own single coroutine is stuck
                    // awaiting `gate`, it stops draining the channel entirely.
                    post("/test/send/$blockerDraftId") { header("X-Member-Id", boardId.toString()) }
                    withTimeout(5_000) { started.await() }
                    repeat(MailingDeliveryWorker.QUEUE_CAPACITY) { saturatedWorker.enqueue(Uuid.random()) }

                    val listId = createList(createdBy = boardId)
                    val subscriber = createMember(email = "rollback-${Uuid.random()}@example.org")
                    subscribe(listId = listId, memberId = subscriber)
                    val draftId =
                        post("/test/draft?listId=$listId&subject=Betreff&body=Text") {
                            header("X-Member-Id", boardId.toString())
                        }.bodyAsText()

                    val response = post("/test/send/$draftId") { header("X-Member-Id", boardId.toString()) }
                    response.bodyAsText().let { it.startsWith("CONFLICT") } shouldBe true

                    transaction {
                        MailingMessageTable
                            .selectAll()
                            .where { MailingMessageTable.id eq Uuid.parse(draftId) }
                            .single()[MailingMessageTable.status]
                    } shouldBe MailingMessageStatus.DRAFT
                    transaction {
                        MailingDeliveryLogTable
                            .selectAll()
                            .where { MailingDeliveryLogTable.mailingMessageId eq Uuid.parse(draftId) }
                            .count()
                    } shouldBe 0
                }
            } finally {
                gate.complete(Unit)
                // Let the (now-unblocked) blocker message actually finish before tearing the
                // worker down -- otherwise afterSpec's cleanup can race a still-in-flight UPDATE
                // on rows it is about to delete.
                blockerMessageId?.let { id ->
                    runCatching {
                        runBlocking {
                            withTimeout(5_000) {
                                while (
                                    transaction {
                                        MailingMessageTable
                                            .selectAll()
                                            .where { MailingMessageTable.id eq id }
                                            .singleOrNull()
                                            ?.get(MailingMessageTable.status)
                                    } == MailingMessageStatus.QUEUED
                                ) {
                                    delay(20)
                                }
                            }
                        }
                    }
                }
                saturatedWorker.shutdown()
            }
        }
    })
