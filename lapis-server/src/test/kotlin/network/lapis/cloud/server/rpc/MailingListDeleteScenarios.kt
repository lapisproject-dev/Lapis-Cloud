package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.dsgvo.CommunicationPersonalData
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.newsletter.MailingAttachmentStore
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.TEST_TRACKING_BASE_URL
import network.lapis.cloud.server.mail.newsletter.committeeRule
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingListDeletionSnapshot
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.MailingListNotDeletableException
import network.lapis.cloud.shared.rpc.MailingListSendingInProgressException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val DELETE_RACE_ROUNDS = 6

/**
 * Welle V1.9.234 -- deleting a MANUAL mailing list (soft delete), against H2 and PostgreSQL with the same assertions. Callers authenticate
 * through real sessions. Covers the happy path, every refusal (own type each), the evidence that must survive, the all-or-nothing behaviour
 * (rows AND files), the audit entry, the DSGVO export and the two races of the plan: delete against send (never a QUEUED message on a
 * deleted list) and delete against an attachment upload (no orphan row, no orphan file).
 */
abstract class MailingListDeleteScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val listIds = mutableListOf<Uuid>()
        val attachmentRoot: File = Files.createTempDirectory("lapis-list-delete").toFile()
        lateinit var boardId: Uuid
        lateinit var memberId: Uuid
        lateinit var boardToken: String
        lateinit var memberToken: String

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)

        fun member(role: AccountRole = AccountRole.MEMBER): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Listen-Loesch-Test"
                    it[email] = "list-delete-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[AccountTable.memberId] = id
                    it[AccountTable.role] = role
                }
            }
            memberIds += id
            return id
        }

        beforeSpec {
            boardId = member(AccountRole.BOARD)
            memberId = member()
            boardToken = SessionStore.createSession(boardId).rawToken
            memberToken = SessionStore.createSession(memberId).rawToken
        }

        afterSpec {
            // let a worker run of a message that survived a race finish before its rows are removed
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                transaction {
                    MailingMessageTable
                        .selectAll()
                        .where {
                            (MailingMessageTable.mailingListId inList listIds) and
                                (MailingMessageTable.status eq MailingMessageStatus.QUEUED)
                        }.count() > 0
                }
            ) {
                Thread.sleep(100)
            }
            transaction {
                val messageIds =
                    MailingMessageTable
                        .selectAll()
                        .where {
                            MailingMessageTable.mailingListId inList listIds
                        }.map { it[MailingMessageTable.id] }
                val deliveryIds =
                    MailingDeliveryLogTable
                        .selectAll()
                        .where { MailingDeliveryLogTable.mailingMessageId inList messageIds }
                        .map { it[MailingDeliveryLogTable.id] }
                MailingLinkClickTable.deleteWhere { MailingLinkClickTable.mailingDeliveryLogId inList deliveryIds }
                MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId inList messageIds }
                MailingMessageLinkTable.deleteWhere { MailingMessageLinkTable.mailingMessageId inList messageIds }
                MailingMessageAttachmentTable.deleteWhere { MailingMessageAttachmentTable.mailingMessageId inList messageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList messageIds }
                MailingListSubscriptionTable.deleteWhere { MailingListSubscriptionTable.mailingListId inList listIds }
                MailingListOptOutTable.deleteWhere { MailingListOptOutTable.mailingListId inList listIds }
                MailingListTable.deleteWhere { MailingListTable.id inList listIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
                SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
            attachmentRoot.deleteRecursively()
            db.deactivate()
        }

        // ---- fixtures -------------------------------------------------------------------------------------------------------------

        fun list(
            name: String = "Loesch-Testliste ${Uuid.random()}",
            kind: MailingListKind = MailingListKind.MANUAL,
            mandatory: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = id
                    it[MailingListTable.name] = name
                    it[description] = null
                    it[createdBy] = boardId
                    it[MailingListTable.kind] = kind
                    it[MailingListTable.mandatory] = mandatory
                    if (kind == MailingListKind.DYNAMIC) it[ruleJson] = committeeRule(Uuid.random())
                }
            }
            listIds += id
            return id
        }

        fun subscribe(
            listId: Uuid,
            member: Uuid,
            unsubscribed: Boolean = false,
        ) {
            transaction {
                MailingListSubscriptionTable.insert {
                    it[id] = Uuid.random()
                    it[mailingListId] = listId
                    it[MailingListSubscriptionTable.memberId] = member
                    it[subscribedAt] = DbClock.nowLocalDateTime()
                    if (unsubscribed) it[unsubscribedAt] = DbClock.nowLocalDateTime()
                }
            }
        }

        fun message(
            listId: Uuid,
            status: MailingMessageStatus = MailingMessageStatus.DRAFT,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingMessageTable.insert {
                    it[MailingMessageTable.id] = id
                    it[subject] = "Betreff"
                    it[bodyText] = "Text"
                    it[sentBy] = boardId
                    it[MailingMessageTable.status] = status
                    it[mailingListId] = listId
                    if (status != MailingMessageStatus.DRAFT) it[queuedAt] = DbClock.nowLocalDateTime()
                }
            }
            return id
        }

        /** An attachment row plus its real file; returns the storage key. */
        fun attachment(messageId: Uuid): String {
            val key = "${Uuid.random()}.bin"
            attachmentRoot.resolve(key).writeBytes(byteArrayOf(1, 2, 3))
            transaction {
                MailingAttachmentStore.insertInTx(
                    attachmentId = Uuid.random(),
                    messageId = messageId,
                    storageKey = key,
                    fileName = "a.pdf",
                    nameKey = "a.pdf",
                    mimeType = "application/pdf",
                    textCharset = null,
                    sizeBytes = 3,
                    sha256 = "0".repeat(64),
                    uploadedBy = boardId,
                    uploadedAt = DbClock.nowLocalDateTime(),
                )
            }
            return key
        }

        fun deliveryLog(
            messageId: Uuid,
            member: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingDeliveryLogTable.insert {
                    it[MailingDeliveryLogTable.id] = id
                    it[deliveredAt] = DbClock.nowLocalDateTime()
                    it[deliveryStatus] = DeliveryStatus.SENT
                    it[mailingMessageId] = messageId
                    it[MailingDeliveryLogTable.memberId] = member
                }
            }
            return id
        }

        fun listRow(id: Uuid) = transaction { MailingListTable.selectAll().where { MailingListTable.id eq id }.single() }

        fun messageCount(listId: Uuid) =
            transaction {
                MailingMessageTable.selectAll().where { MailingMessageTable.mailingListId eq listId }.count()
            }

        fun attachmentCount(listId: Uuid) =
            transaction {
                val ids =
                    MailingMessageTable
                        .selectAll()
                        .where {
                            MailingMessageTable.mailingListId eq listId
                        }.map { it[MailingMessageTable.id] }
                MailingMessageAttachmentTable.selectAll().where { MailingMessageAttachmentTable.mailingMessageId inList ids }.count()
            }

        fun subscriptionCount(listId: Uuid) =
            transaction { MailingListSubscriptionTable.selectAll().where { MailingListSubscriptionTable.mailingListId eq listId }.count() }

        fun auditRowsFor(listId: Uuid) =
            transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq listId }.toList() }

        fun lastSequence(): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .orderBy(
                        AuditLogEntryTable.sequenceNumber to SortOrder.DESC,
                    ).limit(1)
                    .firstOrNull()
            }?.get(AuditLogEntryTable.sequenceNumber) ?: 0L

        fun chainProblemSince(mark: Long): String? =
            transaction {
                val rows =
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.sequenceNumber greater mark }
                        .orderBy(AuditLogEntryTable.sequenceNumber)
                        .toList()
                var result = AuditChainVerifier.verify(rows)
                // Other specs hard-delete audit rows of their own members, so the anchor row can be missing in the shared H2: then the
                // check starts at the second new row; with a single new row there is no link left to verify.
                if (!result.valid && result.reason?.startsWith("Predecessor row") == true) {
                    result = if (rows.size > 1) AuditChainVerifier.verify(rows.drop(1)) else result.copy(valid = true)
                }
                if (result.valid) null else result.reason
            }

        // ---- HTTP surface ---------------------------------------------------------------------------------------------------------

        fun worker() =
            MailingDeliveryWorker(
                transport = NoOpMailTransport(),
                branding = MailBranding.notConfigured(),
                mode = MailingDeliveryMode.LOG,
                trackingToken = testTrackingToken(),
                baseUrl = TEST_TRACKING_BASE_URL,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sendDelay = 0.milliseconds,
            )

        fun service(call: ApplicationCall) =
            MailingService(
                call = call,
                deliveryWorker = worker(),
                deliveryMode = MailingDeliveryMode.LOG,
                branding = MailBranding.notConfigured(),
                attachmentStorageRoot = attachmentRoot,
            )

        fun Application.installRoutes() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, _ -> call.respondText("UNAUTH", status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, _ -> call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, _ -> call.respondText("NOT_FOUND", status = HttpStatusCode.NotFound) }
                exception<MailingListNotDeletableException> {
                    call,
                    _,
                    ->
                    call.respondText("NOT_DELETABLE", status = HttpStatusCode.Conflict)
                }
                exception<MailingListSendingInProgressException> {
                    call,
                    _,
                    ->
                    call.respondText("SENDING", status = HttpStatusCode.Conflict)
                }
                exception<ConflictException> { call, _ -> call.respondText("CONFLICT", status = HttpStatusCode.Conflict) }
            }
            routing {
                post("/t/delete/{id}") {
                    service(call).deleteMailingList(call.parameters["id"]!!)
                    call.respondText("ok")
                }
                post("/t/send/{id}") {
                    call.respondText(service(call).sendMailingMessage(call.parameters["id"]!!).status.name)
                }
                post("/t/draft/{id}") {
                    val svc = service(call)
                    val listId = call.parameters["id"]!!
                    call.respondText(svc.createDraftMessage(mailingListId = listId, subject = "Betreff", bodyText = "Text").id)
                }
                post("/t/drafthtml/{id}") {
                    val svc = service(call)
                    val listId = call.parameters["id"]!!
                    call.respondText(svc.createDraftMessageHtml(mailingListId = listId, subject = "Betreff", bodyHtml = "<p>Text</p>").id)
                }
                post("/t/subscribe/{id}") {
                    service(call).subscribe(call.parameters["id"]!!)
                    call.respondText("ok")
                }
                post("/t/unsubscribe/{id}") {
                    service(call).unsubscribe(call.parameters["id"]!!)
                    call.respondText("ok")
                }
                post("/t/consent/{id}") {
                    service(call).setTrackingConsent(mailingListId = call.parameters["id"]!!, openTracking = true, clickTracking = false)
                    call.respondText("ok")
                }
                post("/t/adminsub/{id}/{memberId}") {
                    service(call).adminSubscribeMember(
                        mailingListId = call.parameters["id"]!!,
                        memberId = call.parameters["memberId"]!!,
                    )
                    call.respondText("ok")
                }
                get("/t/subscribers/{id}") {
                    call.respondText(service(call).listSubscribers(call.parameters["id"]!!).size.toString())
                }
                get("/t/messages/{id}") {
                    call.respondText(service(call).listMailingMessages(call.parameters["id"]!!).size.toString())
                }
                get("/t/lists") {
                    call.respondText(service(call).listMailingLists().joinToString(",") { it.id })
                }
            }
        }

        suspend fun HttpClient.send(
            path: String,
            token: String? = boardToken,
            post: Boolean = true,
        ): HttpResponse {
            val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                if (token !=
                    null
                ) {
                    header("Authorization", "Bearer $token")
                }
            }
            return if (post) post(path, build) else get(path, build)
        }

        fun spec(block: suspend HttpClient.() -> Unit): suspend () -> Unit =
            {
                testApplication {
                    application { installRoutes() }
                    block(client)
                }
            }

        // ---- scenarios ------------------------------------------------------------------------------------------------------------

        test(
            "happy path: subscriptions and opt-outs are gone, the list row is archived and no longer listed, one audit entry without personal data",
        ) {
            spec {
                val subscriberA = member()
                val subscriberB = member()
                val subscriberC = member()
                val id = list()
                subscribe(id, subscriberA)
                subscribe(id, subscriberB)
                subscribe(id, subscriberC, unsubscribed = true)
                transaction {
                    MailingListOptOutTable.insert {
                        it[MailingListOptOutTable.id] = Uuid.random()
                        it[optedOutAt] = DbClock.nowLocalDateTime()
                        it[mailingListId] = id
                        it[MailingListOptOutTable.memberId] = subscriberA
                    }
                }
                send("/t/lists", boardToken, post = false).bodyAsText() shouldContain id.toString()
                val mark = lastSequence()

                send("/t/delete/$id").status shouldBe HttpStatusCode.OK

                subscriptionCount(id) shouldBe 0
                transaction { MailingListOptOutTable.selectAll().where { MailingListOptOutTable.mailingListId eq id }.count() } shouldBe 0
                val row = listRow(id)
                (row[MailingListTable.deletedAt] != null) shouldBe true
                row[MailingListTable.deletedBy] shouldBe boardId
                send("/t/lists", boardToken, post = false).bodyAsText() shouldNotContain id.toString()
                send("/t/lists", memberToken, post = false).bodyAsText() shouldNotContain id.toString()

                val audit = auditRowsFor(id)
                audit.size shouldBe 1
                audit.single()[AuditLogEntryTable.action] shouldBe AuditAction.VOID
                audit.single()[AuditLogEntryTable.entityType] shouldBe AuditEntityType.MAILING_LIST
                audit.single()[AuditLogEntryTable.actorMemberId] shouldBe boardId
                val snapshotText = audit.single()[AuditLogEntryTable.beforeSnapshot]!!
                val snapshot = Json.decodeFromString(MailingListDeletionSnapshot.serializer(), snapshotText)
                snapshot.removedSubscriptions shouldBe 3
                snapshot.removedOptOuts shouldBe 1
                snapshot.kind shouldBe MailingListKind.MANUAL
                snapshotText shouldNotContain "@"
                listOf(subscriberA, subscriberB, subscriberC, boardId).forEach { snapshotText shouldNotContain it.toString() }
                chainProblemSince(mark) shouldBe null
            }()
        }

        test("an automatic or mandatory list is refused with its own type; nothing changes, no audit entry; the database pins it too") {
            spec {
                val dynamic = list(kind = MailingListKind.DYNAMIC)
                val mandatory = list(kind = MailingListKind.DYNAMIC, mandatory = true)
                listOf(dynamic, mandatory).forEach { id ->
                    val response = send("/t/delete/$id")
                    response.status shouldBe HttpStatusCode.Conflict
                    response.bodyAsText() shouldBe "NOT_DELETABLE"
                    listRow(id)[MailingListTable.deletedAt] shouldBe null
                    auditRowsFor(id).shouldBeEmpty()
                    val failure =
                        runCatching {
                            transaction {
                                MailingListTable.update({ MailingListTable.id eq id }) {
                                    it[deletedAt] = DbClock.nowLocalDateTime()
                                    it[deletedBy] = boardId
                                }
                            }
                        }
                    failure.isFailure shouldBe true
                }
                // automatic lists would otherwise show up in the later list-selector scenarios
                transaction { MailingListTable.deleteWhere { MailingListTable.id inList listOf(dynamic, mandatory) } }
                listIds.removeAll(listOf(dynamic, mandatory))
            }()
        }

        test("only the board may delete: a member gets 403, no session 401; nothing changes") {
            spec {
                val subscriber = member()
                val id = list()
                subscribe(id, subscriber)
                send("/t/delete/$id", memberToken).status shouldBe HttpStatusCode.Forbidden
                send("/t/delete/$id", token = null).status shouldBe HttpStatusCode.Unauthorized
                listRow(id)[MailingListTable.deletedAt] shouldBe null
                subscriptionCount(id) shouldBe 1
                auditRowsFor(id).shouldBeEmpty()
            }()
        }

        test("unknown, malformed and already deleted ids answer 404") {
            spec {
                send("/t/delete/${Uuid.random()}").status shouldBe HttpStatusCode.NotFound
                send("/t/delete/not-a-uuid").status shouldBe HttpStatusCode.NotFound
                val id = list()
                send("/t/delete/$id").status shouldBe HttpStatusCode.OK
                send("/t/delete/$id").status shouldBe HttpStatusCode.NotFound
                auditRowsFor(id).size shouldBe 1
            }()
        }

        test("drafts go with their attachment rows and files; sent and failed messages stay with their evidence") {
            spec {
                val subscriber = member()
                val id = list()
                val draftOne = message(id)
                val draftTwo = message(id)
                val keys = listOf(attachment(draftOne), attachment(draftTwo))
                val sent = message(id, MailingMessageStatus.SENT)
                val failed = message(id, MailingMessageStatus.FAILED)
                val log = deliveryLog(sent, subscriber)
                transaction {
                    MailingMessageLinkTable.insert {
                        it[MailingMessageLinkTable.id] = Uuid.random()
                        it[linkIndex] = 0
                        it[targetUrl] = "https://example.org/"
                        it[mailingMessageId] = sent
                    }
                    MailingLinkClickTable.insert {
                        it[MailingLinkClickTable.id] = Uuid.random()
                        it[linkIndex] = 0
                        it[firstClickedAt] = DbClock.nowLocalDateTime()
                        it[clickCount] = 1
                        it[mailingDeliveryLogId] = log
                    }
                }
                val sentAttachmentKey = attachment(sent)
                send("/t/delete/$id").status shouldBe HttpStatusCode.OK

                transaction {
                    MailingMessageTable.selectAll().where { MailingMessageTable.id inList listOf(draftOne, draftTwo) }.count()
                } shouldBe 0
                keys.forEach { attachmentRoot.resolve(it).exists() shouldBe false }
                messageCount(id) shouldBe 2
                transaction {
                    MailingMessageTable.selectAll().where { MailingMessageTable.id inList listOf(sent, failed) }.count()
                } shouldBe 2
                transaction {
                    MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.mailingMessageId eq sent }.count()
                } shouldBe
                    1
                transaction {
                    MailingMessageLinkTable.selectAll().where { MailingMessageLinkTable.mailingMessageId eq sent }.count()
                } shouldBe
                    1
                transaction {
                    MailingLinkClickTable.selectAll().where { MailingLinkClickTable.mailingDeliveryLogId eq log }.count()
                } shouldBe
                    1
                attachmentCount(id) shouldBe 1
                attachmentRoot.resolve(sentAttachmentKey).exists() shouldBe true
                val snapshot =
                    Json.decodeFromString(
                        MailingListDeletionSnapshot.serializer(),
                        auditRowsFor(id).single()[AuditLogEntryTable.beforeSnapshot]!!,
                    )
                snapshot.removedDrafts shouldBe 2
                snapshot.removedAttachments shouldBe 2
                snapshot.retainedSentMessages shouldBe 2
            }()
        }

        test("a list with a message being sent cannot be deleted: its own refusal, nothing is touched") {
            spec {
                val subscriber = member()
                val id = list()
                subscribe(id, subscriber)
                val draft = message(id)
                val key = attachment(draft)
                message(id, MailingMessageStatus.QUEUED)
                val response = send("/t/delete/$id")
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "SENDING"
                listRow(id)[MailingListTable.deletedAt] shouldBe null
                subscriptionCount(id) shouldBe 1
                messageCount(id) shouldBe 2
                attachmentRoot.resolve(key).exists() shouldBe true
                auditRowsFor(id).shouldBeEmpty()
            }()
        }

        test("all or nothing: a draft with delivery evidence aborts the deletion with rows and files untouched") {
            spec {
                val subscriber = member()
                val id = list()
                subscribe(id, subscriber)
                val draft = message(id)
                val key = attachment(draft)
                deliveryLog(draft, subscriber) // impossible in operation: delivery rows exist from QUEUED on
                send("/t/delete/$id").status shouldBe HttpStatusCode.Conflict
                listRow(id)[MailingListTable.deletedAt] shouldBe null
                subscriptionCount(id) shouldBe 1
                messageCount(id) shouldBe 1
                attachmentCount(id) shouldBe 1
                attachmentRoot.resolve(key).exists() shouldBe true
                auditRowsFor(id).shouldBeEmpty()
            }()
        }

        test("every list path answers 404 for a deleted list") {
            spec {
                val subscriber = member()
                val id = list()
                subscribe(id, subscriber)
                val draft = message(id)
                send("/t/delete/$id").status shouldBe HttpStatusCode.OK
                listOf(
                    send("/t/subscribe/$id", memberToken),
                    send("/t/unsubscribe/$id", memberToken),
                    send("/t/consent/$id", memberToken),
                    send("/t/adminsub/$id/$subscriber"),
                    send("/t/subscribers/$id", post = false),
                    send("/t/messages/$id", post = false),
                    send("/t/draft/$id"),
                    send("/t/drafthtml/$id"),
                    send("/t/send/$draft"),
                ).forEach { it.status shouldBe HttpStatusCode.NotFound }
                subscriptionCount(id) shouldBe 0
                messageCount(id) shouldBe 0
            }()
        }

        test("the list selector is ordered by name, then id") {
            spec {
                val name = "Gleichnamig ${Uuid.random()}"
                val first = list(name = name)
                val second = list(name = name)
                val shown =
                    send("/t/lists", boardToken, post = false).bodyAsText().split(",").filter {
                        it == first.toString() ||
                            it == second.toString()
                    }
                shown shouldBe listOf(first.toString(), second.toString()).sorted()
            }()
        }

        test("the data export of the deleting board member names the archived list with its deletion stamp") {
            spec {
                val id = list(name = "Export-Liste ${Uuid.random()}")
                send("/t/delete/$id").status shouldBe HttpStatusCode.OK
                val export = transaction { CommunicationPersonalData.exportMember(boardId).toString() }
                export shouldContain id.toString()
                export shouldContain "deletedAt"
                export shouldContain "\"deletedByMe\":true"
            }()
        }

        test("race: deleting while the same list's draft is being sent -- exactly one wins, never a queued message on a deleted list") {
            spec {
                repeat(DELETE_RACE_ROUNDS) {
                    val id = list()
                    val draft = message(id)
                    val results =
                        coroutineScope {
                            listOf(
                                async { send("/t/delete/$id") },
                                async { send("/t/send/$draft") },
                            ).awaitAll()
                        }
                    val deleteOk = results[0].status == HttpStatusCode.OK
                    val sendOk = results[1].status == HttpStatusCode.OK
                    (deleteOk != sendOk) shouldBe true
                    if (deleteOk) {
                        results[1].status shouldBe HttpStatusCode.NotFound
                        messageCount(id) shouldBe 0
                    } else {
                        results[0].status shouldBe HttpStatusCode.Conflict
                        results[0].bodyAsText() shouldBe "SENDING"
                        listRow(id)[MailingListTable.deletedAt] shouldBe null
                    }
                }
            }()
        }

        test("race: an attachment upload holding the draft row while the list is deleted leaves neither an orphan row nor an orphan file") {
            spec {
                val id = list()
                val draft = message(id)
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val key = "${Uuid.random()}.bin"
                val holder =
                    thread(name = "upload-simulation") {
                        // what MailingAttachmentRoutes does: lock the MESSAGE row, then add the attachment
                        transaction {
                            MailingMessageTable
                                .selectAll()
                                .where { MailingMessageTable.id eq draft }
                                .forUpdate()
                                .single()
                            attachmentRoot.resolve(key).writeBytes(byteArrayOf(9))
                            MailingAttachmentStore.insertInTx(
                                attachmentId = Uuid.random(),
                                messageId = draft,
                                storageKey = key,
                                fileName = "late.pdf",
                                nameKey = "late.pdf",
                                mimeType = "application/pdf",
                                textCharset = null,
                                sizeBytes = 1,
                                sha256 = "1".repeat(64),
                                uploadedBy = boardId,
                                uploadedAt = DbClock.nowLocalDateTime(),
                            )
                            locked.countDown()
                            release.await(15, TimeUnit.SECONDS)
                        }
                    }
                withContext(Dispatchers.IO) { locked.await(15, TimeUnit.SECONDS) } shouldBe true
                val deletion = async { send("/t/delete/$id") }
                delay(300)
                release.countDown()
                withContext(Dispatchers.IO) { holder.join(20_000) }
                deletion.await().status shouldBe HttpStatusCode.OK
                attachmentCount(id) shouldBe 0
                messageCount(id) shouldBe 0
                attachmentRoot.resolve(key).exists() shouldBe false
            }()
        }
    })

class MailingListDeleteTest : MailingListDeleteScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MailingListDeletePostgresTest : MailingListDeleteScenarios(TestDatabase.Postgres())
