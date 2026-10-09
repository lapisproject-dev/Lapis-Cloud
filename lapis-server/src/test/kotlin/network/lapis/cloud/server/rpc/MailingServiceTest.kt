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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailBudgetConfig
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.TEST_TRACKING_BASE_URL
import network.lapis.cloud.server.mail.newsletter.TrackingFixture
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
                val deliveryIds =
                    MailingDeliveryLogTable
                        .selectAll()
                        .where { MailingDeliveryLogTable.mailingMessageId inList messageIds }
                        .map { it[MailingDeliveryLogTable.id] }
                MailingLinkClickTable.deleteWhere { MailingLinkClickTable.mailingDeliveryLogId inList deliveryIds }
                MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId inList messageIds }
                MailingMessageLinkTable.deleteWhere { MailingMessageLinkTable.mailingMessageId inList messageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList messageIds }
                MailingListSubscriptionTable.deleteWhere { MailingListSubscriptionTable.mailingListId inList createdListIds }
                MailingListTable.deleteWhere { MailingListTable.id inList createdListIds }
                AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
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
                trackingToken = testTrackingToken(),
                baseUrl = TEST_TRACKING_BASE_URL,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sendDelay = 0.milliseconds,
            )

        fun testApp(
            mode: MailingDeliveryMode = MailingDeliveryMode.LOG,
            worker: MailingDeliveryWorker? = null,
            budget: MailBudgetStore = MailBudgetStore(null),
            limiter: FederationInboxRateLimiter? = null,
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
                                budget = budget,
                                sendDelay = 250.milliseconds,
                                sendDisplayRateLimiter = limiter ?: FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                            )
                        get("/test/estimate/{id}") {
                            val dto = service(call).mailingSendEstimate(call.parameters["id"]!!)
                            call.respondText(
                                "${dto.recipientCount}|${dto.bulkBudgetPerHour}|${dto.estimatedSeconds}|${dto.spansMultipleHours}",
                            )
                        }
                        get("/test/progress/{id}") {
                            val dto = service(call).mailingSendProgress(call.parameters["id"]!!)
                            call.respondText(
                                "${dto.total}|${dto.sent}|${dto.failed}|${dto.interrupted}|${dto.skipped}|${dto.pending}|" +
                                    "${dto.pausedUntil}|${dto.pauseReason}|${dto.remainingSeconds}",
                            )
                        }
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
                        post("/test/consent") {
                            val q = call.request.queryParameters
                            val dto =
                                service(call).setTrackingConsent(
                                    mailingListId = q["listId"]!!,
                                    openTracking = q["open"] == "true",
                                    clickTracking = q["click"] == "true",
                                )
                            call.respondText(
                                "${dto.openTrackingConsentedAt != null}|${dto.clickTrackingConsentedAt != null}|${dto.openTrackingConsentedAt}",
                            )
                        }
                        get("/test/lists") {
                            val lists = service(call).listMailingLists()
                            call.respondText(
                                lists.joinToString(",") {
                                    "${it.id}:${it.currentMemberOpenTrackingConsentedAt != null}:${it.currentMemberClickTrackingConsentedAt != null}"
                                },
                            )
                        }
                        post("/test/unsubscribe") {
                            service(call).unsubscribe(call.request.queryParameters["listId"]!!)
                            call.respondText("OK")
                        }
                        post("/test/subscribe") {
                            service(call).subscribe(call.request.queryParameters["listId"]!!)
                            call.respondText("OK")
                        }
                        post("/test/admin-subscribe") {
                            val q = call.request.queryParameters
                            service(call).adminSubscribeMember(mailingListId = q["listId"]!!, memberId = q["memberId"]!!)
                            call.respondText("OK")
                        }
                        get("/test/subscribers") {
                            val subs = service(call).listSubscribers(call.request.queryParameters["listId"]!!)
                            call.respondText(subs.joinToString(",") { "${it.openTrackingConsentedAt}/${it.clickTrackingConsentedAt}" })
                        }
                        get("/test/stats/{id}") {
                            val dto = service(call).mailingMessageStats(call.parameters["id"]!!)
                            call.respondText(
                                kotlinx.serialization.json.Json
                                    .encodeToString(MailingMessageStatsDto.serializer(), dto),
                            )
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
                "(review finding #2; since V1.9.81 enqueue fails only when the worker has been shut down)",
        ) {
            // V1.9.81: the send queue is the database, so it can no longer be "saturated". The one remaining way for enqueue to
            // fail after the queuing transaction committed is a worker that was shut down -- same rollback contract.
            val deadWorker =
                MailingDeliveryWorker(
                    transport = NoOpMailTransport(),
                    branding = MailBranding.notConfigured(),
                    mode = MailingDeliveryMode.SMTP,
                    trackingToken = testTrackingToken(),
                    baseUrl = TEST_TRACKING_BASE_URL,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    sendDelay = 0.milliseconds,
                )
            deadWorker.shutdown()
            testApp(mode = MailingDeliveryMode.SMTP, worker = deadWorker) {
                val listId = createList(createdBy = boardId)
                val subscriber = createMember(email = "rollback-${Uuid.random()}@example.org")
                subscribe(listId = listId, memberId = subscriber)
                val draftId =
                    post(
                        "/test/draft-html?listId=$listId&subject=Betreff&bodyHtml=" +
                            java.net.URLEncoder.encode("<p><a href=\"https://example.org/r\">r</a></p>", "UTF-8"),
                    ) {
                        header("X-Member-Id", boardId.toString())
                    }.bodyAsText().substringBefore("|")

                val response = post("/test/send/$draftId") { header("X-Member-Id", boardId.toString()) }
                response.bodyAsText().let { it.startsWith("CONFLICT") } shouldBe true
                // V1.9.15: the frozen link rows are rolled back too, so the retry can capture them again.
                transaction {
                    MailingMessageLinkTable.selectAll().where { MailingMessageLinkTable.mailingMessageId eq Uuid.parse(draftId) }.count()
                } shouldBe 0
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

        // ── Welle V1.9.15: consent, link capture, stats ─────────────────────────────────────────────

        fun consentOf(
            listId: Uuid,
            memberId: Uuid,
        ): Pair<Boolean, Boolean> =
            transaction {
                val row =
                    MailingListSubscriptionTable
                        .selectAll()
                        .where {
                            (MailingListSubscriptionTable.mailingListId eq listId) and (MailingListSubscriptionTable.memberId eq memberId)
                        }.single()
                (row[MailingListSubscriptionTable.openTrackingConsentedAt] != null) to
                    (row[MailingListSubscriptionTable.clickTrackingConsentedAt] != null)
            }

        fun auditCountFor(memberId: Uuid): Int =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.actorMemberId eq memberId) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER)
                    }.count()
                    .toInt()
            }

        test("setTrackingConsent: opt-in sets the timestamp, a repeated opt-in keeps it, withdrawal clears it") {
            testApp {
                val listId = createList(createdBy = boardId)
                val member = createMember(email = "consent-${Uuid.random()}@example.org")
                subscribe(listId, member)
                val first =
                    post(
                        "/test/consent?listId=$listId&open=true&click=true",
                    ) { header("X-Member-Id", member.toString()) }.bodyAsText()
                first.startsWith("true|true|") shouldBe true
                val again =
                    post(
                        "/test/consent?listId=$listId&open=true&click=true",
                    ) { header("X-Member-Id", member.toString()) }.bodyAsText()
                again shouldBe first // timestamp unchanged
                auditCountFor(member) shouldBe 1 // repeated no-op change is not audited
                post("/test/consent?listId=$listId&open=false&click=true") { header("X-Member-Id", member.toString()) }
                consentOf(listId, member) shouldBe (false to true)
                post("/test/consent?listId=$listId&open=false&click=false") { header("X-Member-Id", member.toString()) }
                consentOf(listId, member) shouldBe (false to false)
                auditCountFor(member) shouldBe 3
            }
        }

        test("setTrackingConsent: the audit snapshot carries no PII (list id and booleans only)") {
            testApp {
                val listId = createList(createdBy = boardId)
                val member = createMember(email = "audit-pii-${Uuid.random()}@example.org")
                subscribe(listId, member)
                post("/test/consent?listId=$listId&open=true&click=false") { header("X-Member-Id", member.toString()) }
                val after =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.actorMemberId eq member }
                            .single()[AuditLogEntryTable.afterSnapshot]!!
                    }
                after shouldContain listId.toString()
                after shouldContain "\"openTracking\":true"
                after shouldNotContain "audit-pii-"
                after shouldNotContain "Mailing-Testmitglied"
            }
        }

        test("setTrackingConsent without an active subscription is a Conflict; a non-ACTIVE member is refused") {
            testApp {
                val listId = createList(createdBy = boardId)
                val notSubscribed = createMember(email = "nosub-${Uuid.random()}@example.org")
                post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", notSubscribed.toString()) }
                    .bodyAsText()
                    .startsWith("CONFLICT") shouldBe true
                val guest = createMember(email = "guest-consent-${Uuid.random()}@example.org", status = MemberStatus.GUEST)
                subscribe(listId, guest)
                post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", guest.toString()) }
                    .bodyAsText() shouldBe "FORBIDDEN"
                val unsub = createMember(email = "unsub-consent-${Uuid.random()}@example.org")
                subscribe(listId, unsub)
                post("/test/unsubscribe?listId=$listId") { header("X-Member-Id", unsub.toString()) }
                post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", unsub.toString()) }
                    .bodyAsText()
                    .startsWith("CONFLICT") shouldBe true
            }
        }

        test("withdrawing consent erases already-collected counts of exactly that kind; unsubscribe erases both") {
            testApp {
                val listId = createList(createdBy = boardId)
                val member = createMember(email = "erase-${Uuid.random()}@example.org")
                subscribe(listId, member)
                post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", member.toString()) }
                val fx = TrackingFixture()
                run {
                    val msg = fx.message(listId = listId, sentBy = boardId)
                    fx.link(messageId = msg, index = 0, url = "https://example.org/x")
                    val (delivery, _) = fx.delivery(messageId = msg, memberId = member, openTracked = true, clickTracked = true)
                    network.lapis.cloud.server.mail.newsletter.MailingTrackingData
                        .recordClick(
                            deliveryLogId = delivery,
                            linkIndex = 0,
                            now =
                                network.lapis.cloud.server.db.DbClock
                                    .nowLocalDateTime(),
                        )
                    network.lapis.cloud.server.mail.newsletter.MailingTrackingData
                        .recordOpen(
                            deliveryLogId = delivery,
                            now =
                                network.lapis.cloud.server.db.DbClock
                                    .nowLocalDateTime(),
                        )

                    post("/test/consent?listId=$listId&open=true&click=false") { header("X-Member-Id", member.toString()) }
                    fx.clicksOf(delivery) shouldBe emptyMap()
                    fx.openCountOf(delivery) shouldBe 1

                    post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", member.toString()) }
                    post("/test/unsubscribe?listId=$listId") { header("X-Member-Id", member.toString()) }
                    fx.openCountOf(delivery) shouldBe 0
                    consentOf(listId, member) shouldBe (false to false)
                    val auditBefore = auditCountFor(member)
                    // Unsubscribing again has nothing to withdraw -> no further audit entry.
                    post("/test/unsubscribe?listId=$listId") { header("X-Member-Id", member.toString()) }
                    auditCountFor(member) shouldBe auditBefore
                }
            }
        }

        test("re-subscribing starts without consent; adminSubscribeMember never sets consent; listSubscribers hides consent") {
            testApp {
                val listId = createList(createdBy = boardId)
                val member = createMember(email = "resub-${Uuid.random()}@example.org")
                subscribe(listId, member)
                post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", member.toString()) }
                post("/test/unsubscribe?listId=$listId") { header("X-Member-Id", member.toString()) }
                post("/test/subscribe?listId=$listId") { header("X-Member-Id", member.toString()) }
                consentOf(listId, member) shouldBe (false to false)

                val adminAdded = createMember(email = "admin-added-${Uuid.random()}@example.org")
                post("/test/admin-subscribe?listId=$listId&memberId=$adminAdded") { header("X-Member-Id", boardId.toString()) }
                consentOf(listId, adminAdded) shouldBe (false to false)

                post("/test/consent?listId=$listId&open=true&click=true") { header("X-Member-Id", member.toString()) }
                val subscribers = get("/test/subscribers?listId=$listId") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                subscribers shouldBe "null/null,null/null"
            }
        }

        test("listMailingLists exposes only the caller's own consent") {
            testApp {
                val listId = createList(createdBy = boardId)
                val a = createMember(email = "own-a-${Uuid.random()}@example.org")
                val b = createMember(email = "own-b-${Uuid.random()}@example.org")
                subscribe(listId, a)
                subscribe(listId, b)
                post("/test/consent?listId=$listId&open=true&click=false") { header("X-Member-Id", a.toString()) }
                val forA = get("/test/lists") { header("X-Member-Id", a.toString()) }.bodyAsText()
                val forB = get("/test/lists") { header("X-Member-Id", b.toString()) }.bodyAsText()
                forA shouldContain "$listId:true:false"
                forB shouldContain "$listId:false:false"
            }
        }

        test("sendMailingMessage freezes the trackable links; the enqueue-failure rollback removes them again") {
            testApp {
                val listId = createList(createdBy = boardId)
                val subscriber = createMember(email = "links-${Uuid.random()}@example.org")
                subscribe(listId, subscriber)
                val html =
                    "<p><a href=\"https://a.example/1\">a</a> <a href=\"https://b.example/2\">b</a> " +
                        "<a href=\"https://a.example/1\">c</a></p>"
                val draft =
                    post("/test/draft-html?listId=$listId&subject=Betreff&bodyHtml=" + java.net.URLEncoder.encode(html, "UTF-8")) {
                        header("X-Member-Id", boardId.toString())
                    }.bodyAsText()
                val messageId = Uuid.parse(draft.substringBefore("|"))
                post("/test/send/$messageId") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "QUEUED"
                val links =
                    transaction {
                        MailingMessageLinkTable
                            .selectAll()
                            .where { MailingMessageLinkTable.mailingMessageId eq messageId }
                            .associate { it[MailingMessageLinkTable.linkIndex] to it[MailingMessageLinkTable.targetUrl] }
                    }
                links shouldBe mapOf(0 to "https://a.example/1", 1 to "https://b.example/2")
            }
        }

        test("mailingMessageStats: BOARD only, unknown id is NotFound") {
            testApp {
                val listId = createList(createdBy = boardId)
                val fx = TrackingFixture()
                val msg = fx.message(listId = listId, sentBy = boardId)
                get("/test/stats/$msg") { header("X-Member-Id", plainMemberId.toString()) }.bodyAsText() shouldBe "FORBIDDEN"
                get("/test/stats/${Uuid.random()}") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "NOT_FOUND"
            }
        }

        test("mailingMessageStats: cohorts below the k-anonymity floor are suppressed, open and click separately") {
            testApp {
                val listId = createList(createdBy = boardId)
                val fx = TrackingFixture()
                val msg = fx.message(listId = listId, sentBy = boardId)
                fx.link(messageId = msg, index = 0, url = "https://example.org/x")
                repeat(MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS - 1) {
                    val m = createMember(email = "k-${Uuid.random()}@example.org")
                    val (d, _) = fx.delivery(messageId = msg, memberId = m, openTracked = true, clickTracked = true)
                    network.lapis.cloud.server.mail.newsletter.MailingTrackingData
                        .recordClick(
                            deliveryLogId = d,
                            linkIndex = 0,
                            now =
                                network.lapis.cloud.server.db.DbClock
                                    .nowLocalDateTime(),
                        )
                }
                val json = get("/test/stats/$msg") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val stats =
                    kotlinx.serialization.json.Json
                        .decodeFromString(MailingMessageStatsDto.serializer(), json)
                stats.delivered shouldBe MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS - 1
                stats.openSuppressed shouldBe true
                stats.clickSuppressed shouldBe true
                stats.suppressed shouldBe true
                stats.openedAtLeastOnce shouldBe null
                stats.links.single().uniqueRecipients shouldBe null
                stats.links.single().totalClicks shouldBe null
            }
        }

        test("mailingMessageStats: from 5 consents on, aggregates appear; unique recipients differ from total clicks; no ids leak") {
            testApp {
                val listId = createList(createdBy = boardId)
                val fx = TrackingFixture()
                val msg = fx.message(listId = listId, sentBy = boardId)
                fx.link(messageId = msg, index = 0, url = "https://example.org/x")
                fx.link(messageId = msg, index = 1, url = "https://example.org/y")
                val memberIds = mutableListOf<Uuid>()
                repeat(MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS) { i ->
                    val m = createMember(email = "agg-${Uuid.random()}@example.org")
                    memberIds += m
                    val (d, _) = fx.delivery(messageId = msg, memberId = m, openTracked = true, clickTracked = true)
                    val now =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    if (i < 3) {
                        repeat(2) {
                            network.lapis.cloud.server.mail.newsletter.MailingTrackingData
                                .recordClick(deliveryLogId = d, linkIndex = 0, now = now)
                        }
                        network.lapis.cloud.server.mail.newsletter.MailingTrackingData
                            .recordOpen(deliveryLogId = d, now = now)
                    }
                }
                val json = get("/test/stats/$msg") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val stats =
                    kotlinx.serialization.json.Json
                        .decodeFromString(MailingMessageStatsDto.serializer(), json)
                stats.suppressed shouldBe false
                stats.openCohort shouldBe 5
                stats.clickCohort shouldBe 5
                stats.openedAtLeastOnce shouldBe 3
                val link0 = stats.links.single { it.linkIndex == 0 }
                link0.uniqueRecipients shouldBe 3
                link0.totalClicks shouldBe 6
                stats.links.single { it.linkIndex == 1 }.uniqueRecipients shouldBe 0
                memberIds.forEach { json shouldNotContain it.toString() }
                json shouldNotContain "Mailing-Testmitglied"
                stats.retentionExpired shouldBe false
            }
        }

        test("mailingMessageStats: retentionExpired once the retention period has passed") {
            testApp {
                val listId = createList(createdBy = boardId)
                val fx = TrackingFixture()
                val old =
                    network.lapis.cloud.server.db.DbClock
                        .nowLocalDateTime()
                        .let {
                            kotlinx.datetime.LocalDateTime(
                                it.date.minus(
                                    kotlinx.datetime.DatePeriod(
                                        days =
                                            MailingHtmlPolicy.RETENTION_DAYS + 2,
                                    ),
                                ),
                                it.time,
                            )
                        }
                val msg = fx.message(listId = listId, sentBy = boardId, sentAt = old)
                val json = get("/test/stats/$msg") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                kotlinx.serialization.json.Json
                    .decodeFromString(MailingMessageStatsDto.serializer(), json)
                    .retentionExpired shouldBe true
            }
        }
        // ── Welle V1.9.81: send estimate and progress ──────────────────────────────────────────────

        fun resetBudget() {
            transaction {
                MailSendSlotTable.deleteWhere { MailSendSlotTable.id neq Uuid.random() }
                MailBudgetLockTable.update({ MailBudgetLockTable.id eq 1.toShort() }) { it[bulkPausedUntil] = null }
            }
        }

        fun insertDelivery(
            messageId: Uuid,
            memberId: Uuid,
            status: DeliveryStatus,
            nextAttemptAt: kotlinx.datetime.LocalDateTime? = null,
        ) {
            transaction {
                MailingDeliveryLogTable.insert {
                    it[id] = Uuid.random()
                    it[mailingMessageId] = messageId
                    it[MailingDeliveryLogTable.memberId] = memberId
                    it[deliveredAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[deliveryStatus] = status
                    it[MailingDeliveryLogTable.nextAttemptAt] = nextAttemptAt
                }
            }
        }

        test("mailingSendEstimate counts EXACTLY the recipients sendMailingMessage then queues, and applies the bulk budget") {
            testApp(budget = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = 10, reservePerHour = 2))) {
                val listId = createList(createdBy = boardId)
                // 3 eligible, plus every kind of member the send filter drops
                repeat(3) { subscribe(listId, createMember(email = "est-ok-${Uuid.random()}@example.org")) }
                subscribe(listId, createMember(email = "est-gone-${Uuid.random()}@example.org", status = MemberStatus.WITHDRAWN))
                subscribe(
                    listId,
                    createMember(
                        email = "est-anon-${Uuid.random()}@example.org",
                        anonymizedAt =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime(),
                    ),
                )
                val unsubscribed = createMember(email = "est-unsub-${Uuid.random()}@example.org")
                subscribe(listId, unsubscribed)
                transaction {
                    MailingListSubscriptionTable.update({ MailingListSubscriptionTable.memberId eq unsubscribed }) {
                        it[unsubscribedAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()

                val estimate = get("/test/estimate/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")
                estimate[0] shouldBe "3"
                estimate[1] shouldBe "8" // max 10 - reserve 2
                // 3 <= 8 per hour: a free sliding window sends them as one burst at the delay rate, ceil(3 * 250 ms) = 1 s
                estimate[2] shouldBe "1"
                estimate[3] shouldBe "false"

                post("/test/send/$draftId") { header("X-Member-Id", boardId.toString()) }
                transaction {
                    MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.mailingMessageId eq Uuid.parse(draftId) }.count()
                } shouldBe 3
            }
        }

        test(
            "mailingSendEstimate: more recipients than one budget hour -> spansMultipleHours; without a budget only the minimum delay counts",
        ) {
            testApp(budget = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = 10, reservePerHour = 2))) {
                val listId = createList(createdBy = boardId)
                repeat(9) { subscribe(listId, createMember(email = "span-${Uuid.random()}@example.org")) }
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                get("/test/estimate/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")[3] shouldBe "true"
            }
            testApp {
                val listId = createList(createdBy = boardId)
                repeat(4) { subscribe(listId, createMember(email = "nobudget-${Uuid.random()}@example.org")) }
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val estimate = get("/test/estimate/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")
                estimate[0] shouldBe "4"
                estimate[1] shouldBe "null"
                estimate[2] shouldBe "1" // 4 * 250 ms
                estimate[3] shouldBe "false"
            }
        }

        test("mailingSendEstimate and mailingSendProgress: BOARD/ADMIN only, unknown message is NOT_FOUND") {
            testApp {
                val listId = createList(createdBy = boardId)
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                get("/test/estimate/$draftId") { header("X-Member-Id", plainMemberId.toString()) }.bodyAsText() shouldBe "FORBIDDEN"
                get("/test/progress/$draftId") { header("X-Member-Id", plainMemberId.toString()) }.bodyAsText() shouldBe "FORBIDDEN"
                get("/test/estimate/${Uuid.random()}") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "NOT_FOUND"
                get("/test/progress/${Uuid.random()}") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "NOT_FOUND"
            }
        }

        test("the two display RPCs are rate limited per member (the 3rd call within the window is refused)") {
            testApp(limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                val listId = createList(createdBy = boardId)
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                get("/test/estimate/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().startsWith("0|") shouldBe true
                get("/test/progress/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().startsWith("0|") shouldBe true
                get("/test/estimate/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().startsWith("CONFLICT") shouldBe
                    true
            }
        }

        test("mailingSendProgress counts every delivery state; INTERRUPTED is reported separately and is never 'sent'") {
            resetBudget()
            testApp {
                val listId = createList(createdBy = boardId)
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val messageId = Uuid.parse(draftId)
                val m = createMember(email = "prog-${Uuid.random()}@example.org")
                transaction {
                    MailingMessageTable.update(
                        { MailingMessageTable.id eq messageId },
                    ) { it[status] = MailingMessageStatus.QUEUED }
                }
                repeat(2) { insertDelivery(messageId, m, DeliveryStatus.SENT) }
                insertDelivery(messageId, m, DeliveryStatus.FAILED)
                insertDelivery(messageId, m, DeliveryStatus.BOUNCED)
                repeat(2) { insertDelivery(messageId, m, DeliveryStatus.INTERRUPTED) }
                insertDelivery(messageId, m, DeliveryStatus.SKIPPED_NO_ADDRESS)
                insertDelivery(messageId, m, DeliveryStatus.SKIPPED_UNSUBSCRIBED)
                repeat(3) { insertDelivery(messageId, m, DeliveryStatus.PENDING) }

                val p = get("/test/progress/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")
                p[0] shouldBe "11" // total
                p[1] shouldBe "2" // sent
                p[2] shouldBe "2" // failed (FAILED + BOUNCED)
                p[3] shouldBe "2" // interrupted
                p[4] shouldBe "2" // skipped
                p[5] shouldBe "3" // pending
                p[6] shouldBe "null" // not paused: no budget, nothing deferred
                p[7] shouldBe "null"
                p[8] shouldBe "1" // 3 * 250 ms, rounded up
            }
        }

        test("mailingSendProgress reports why a send stands still: hourly budget, then provider pause / deferral") {
            resetBudget()
            val budget = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = 10, reservePerHour = 2))
            testApp(budget = budget) {
                val listId = createList(createdBy = boardId)
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val messageId = Uuid.parse(draftId)
                val m = createMember(email = "pause-${Uuid.random()}@example.org")
                transaction {
                    MailingMessageTable.update(
                        { MailingMessageTable.id eq messageId },
                    ) { it[status] = MailingMessageStatus.QUEUED }
                }
                insertDelivery(messageId, m, DeliveryStatus.PENDING)

                // 8 bulk slots used just now -> the bulk budget is exhausted
                val now =
                    network.lapis.cloud.server.db.DbClock
                        .nowLocalDateTime()
                transaction {
                    repeat(8) {
                        MailSendSlotTable.insert {
                            it[id] = Uuid.random()
                            it[reservedAt] = now
                            it[lane] = "BULK"
                        }
                    }
                }
                val budgetPause = get("/test/progress/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")
                budgetPause[7] shouldBe "HOURLY_BUDGET"
                budgetPause[6] shouldBe
                    now.let { budget.peek(lane = network.lapis.cloud.server.mail.budget.MailLane.BULK, now = it) }.let {
                        (it as network.lapis.cloud.server.mail.budget.BudgetDecision.WaitUntil).at.toString()
                    }

                // a provider pause wins over the budget
                budget.pauseBulk(now.plusDuration(10.minutes))
                val providerPause = get("/test/progress/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")
                providerPause[7] shouldBe "PROVIDER_DEFERRAL"
                providerPause[6] shouldBe now.plusDuration(10.minutes).toString()
            }
            resetBudget()
        }

        test("mailingSendProgress: a deferred recipient (next attempt in the future, nothing else due) is a provider deferral") {
            resetBudget()
            testApp {
                val listId = createList(createdBy = boardId)
                val draftId =
                    post(
                        "/test/draft?listId=$listId&subject=Betreff&body=Text",
                    ) { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val messageId = Uuid.parse(draftId)
                val m = createMember(email = "defer-${Uuid.random()}@example.org")
                transaction {
                    MailingMessageTable.update(
                        { MailingMessageTable.id eq messageId },
                    ) { it[status] = MailingMessageStatus.QUEUED }
                }
                val later =
                    network.lapis.cloud.server.db.DbClock
                        .nowLocalDateTime()
                        .plusDuration(5.minutes)
                insertDelivery(messageId, m, DeliveryStatus.PENDING, nextAttemptAt = later)

                val p = get("/test/progress/$draftId") { header("X-Member-Id", boardId.toString()) }.bodyAsText().split("|")
                p[7] shouldBe "PROVIDER_DEFERRAL"
                p[6] shouldBe later.toString()
            }
        }
    })
