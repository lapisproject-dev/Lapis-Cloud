package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.newsletter.MailingAttachmentStore
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.TEST_TRACKING_BASE_URL
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.232 -- the attachments in `MailingMessageDto` (list, create, send) and the send-time limit check. */
class MailingServiceAttachmentDtoTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val listIds = mutableListOf<Uuid>()
        val messageIds = mutableListOf<Uuid>()
        lateinit var boardId: Uuid

        fun createBoardMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Dto-Test"
                    it[email] = "dto-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            memberIds += id
            return id
        }

        beforeSpec {
            DatabaseConfig.connect()
            boardId = createBoardMember()
        }
        afterSpec {
            transaction {
                MailingDeliveryLogTable.deleteWhere { mailingMessageId inList messageIds }
                MailingMessageAttachmentTable.deleteWhere { mailingMessageId inList messageIds }
                MailingMessageLinkTable.deleteWhere { mailingMessageId inList messageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList messageIds }
                MailingListSubscriptionTable.deleteWhere { mailingListId inList listIds }
                MailingListTable.deleteWhere { MailingListTable.id inList listIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        fun newList(): Uuid {
            val id = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = id
                    it[name] = "Dto-Liste ${Uuid.random()}"
                    it[description] = null
                    it[createdBy] = boardId
                }
            }
            listIds += id
            return id
        }

        fun newMessage(listId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MailingMessageTable.insert {
                    it[MailingMessageTable.id] = id
                    it[mailingListId] = listId
                    it[subject] = "Dto"
                    it[bodyText] = "Text"
                    it[bodyHtml] = null
                    it[sentBy] = boardId
                    it[status] = MailingMessageStatus.DRAFT
                }
            }
            messageIds += id
            return id
        }

        fun attach(
            messageId: Uuid,
            name: String,
            size: Long = 10,
            second: Int = 0,
        ) {
            transaction {
                MailingAttachmentStore.insertInTx(
                    attachmentId = Uuid.random(),
                    messageId = messageId,
                    storageKey = "mailing/$messageId/${Uuid.random()}.bin",
                    fileName = name,
                    nameKey = name.lowercase(),
                    mimeType = "application/pdf",
                    textCharset = null,
                    sizeBytes = size,
                    sha256 = "0".repeat(64),
                    uploadedBy = boardId,
                    uploadedAt = LocalDateTime(DbClock.nowLocalDateTime().date, LocalTime(10, 0, second)),
                )
            }
        }

        fun testApp(block: suspend io.ktor.client.HttpClient.() -> Unit) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause ->
                            call.respondText("CONFLICT:${cause.message}", status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        fun service(call: ApplicationCall) =
                            MailingService(
                                call = call,
                                deliveryWorker =
                                    MailingDeliveryWorker(
                                        transport = NoOpMailTransport(),
                                        branding = MailBranding.notConfigured(),
                                        mode = MailingDeliveryMode.LOG,
                                        trackingToken = testTrackingToken(),
                                        baseUrl = TEST_TRACKING_BASE_URL,
                                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                                        sendDelay = 0.milliseconds,
                                    ),
                                deliveryMode = MailingDeliveryMode.LOG,
                                branding = MailBranding.notConfigured(),
                                attachmentStorageRoot = TEST_ATTACHMENT_ROOT,
                                budget = MailBudgetStore(null),
                                sendDelay = 250.milliseconds,
                                sendDisplayRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                            )
                        get("/test/list/{id}") {
                            val dtos = service(call).listMailingMessages(call.parameters["id"]!!)
                            call.respondText(Json.encodeToString(ListSerializer(MailingMessageDto.serializer()), dtos))
                        }
                        post("/test/draft") {
                            val dto =
                                service(
                                    call,
                                ).createDraftMessage(
                                    mailingListId = call.request.queryParameters["listId"]!!,
                                    subject = "Betreff",
                                    bodyText = "Text",
                                )
                            call.respondText(Json.encodeToString(MailingMessageDto.serializer(), dto))
                        }
                        post("/test/send/{id}") {
                            val dto = service(call).sendMailingMessage(call.parameters["id"]!!)
                            call.respondText(Json.encodeToString(MailingMessageDto.serializer(), dto))
                        }
                    }
                }
                runBlocking { client.block() }
            }
        }

        test("listMailingMessages returns every message with its attachments in upload order; messages without any get an empty list") {
            val list = newList()
            val withTwo = newMessage(list)
            val without = newMessage(list)
            attach(withTwo, "zweite.pdf", second = 2)
            attach(withTwo, "erste.pdf", second = 1)
            testApp {
                val body = get("/test/list/$list") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val dtos = Json.decodeFromString(ListSerializer(MailingMessageDto.serializer()), body)
                dtos.single { it.id == withTwo.toString() }.attachments.map { it.fileName } shouldContainExactly
                    listOf("erste.pdf", "zweite.pdf")
                dtos.single { it.id == without.toString() }.attachments shouldBe emptyList()
                dtos.single { it.id == withTwo.toString() }.attachments.all { it.fileAvailable } shouldBe true
            }
        }

        test("a new draft has an empty attachment list") {
            val list = newList()
            testApp {
                val body = post("/test/draft?listId=$list") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val dto = Json.decodeFromString(MailingMessageDto.serializer(), body)
                messageIds += Uuid.parse(dto.id)
                dto.attachments shouldBe emptyList()
            }
        }

        test("sendMailingMessage returns the QUEUED message together with its attachments") {
            val list = newList()
            val message = newMessage(list)
            attach(message, "einladung.pdf")
            testApp {
                val response = post("/test/send/$message") { header("X-Member-Id", boardId.toString()) }
                val dto = Json.decodeFromString(MailingMessageDto.serializer(), response.bodyAsText())
                dto.status shouldBe MailingMessageStatus.QUEUED
                dto.attachments.single().fileName shouldBe "einladung.pdf"
            }
        }

        test("send-time defence in depth: more than 5 attachments (or more than 20 MiB) is a Conflict and the message stays DRAFT") {
            val list = newList()
            val tooMany = newMessage(list)
            repeat(6) { attach(tooMany, "f$it.pdf") }
            val tooBig = newMessage(list)
            repeat(3) { attach(tooBig, "g$it.pdf", size = 10L * 1024 * 1024) }
            testApp {
                listOf(tooMany, tooBig).forEach { id ->
                    val response = post("/test/send/$id") { header("X-Member-Id", boardId.toString()) }
                    response.status shouldBe HttpStatusCode.Conflict
                    response.bodyAsText() shouldContain "CONFLICT:"
                    transaction {
                        MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.single()[MailingMessageTable.status]
                    } shouldBe
                        MailingMessageStatus.DRAFT
                }
            }
        }

        test("the older client wire format without the attachments field still deserialises") {
            val json = """{"id":"1","mailingListId":"2","subject":"s","bodyText":"t","sentBy":"3","sentAt":null,"status":"DRAFT"}"""
            Json.decodeFromString(MailingMessageDto.serializer(), json).attachments shouldBe emptyList()
        }
    })
