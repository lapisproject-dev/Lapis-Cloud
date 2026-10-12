package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.newsletter.DynamicFixture
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.TEST_TRACKING_BASE_URL
import network.lapis.cloud.server.mail.newsletter.committeeRule
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import network.lapis.cloud.shared.domain.RecipientPreviewDto
import network.lapis.cloud.shared.domain.RuleExplanationKind
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.111 "Dynamische Mailinglisten" -- the service surface: who may create/change/preview, strict rule validation, immutable kind and
 * mandatory flag, opt-out without enumeration, list visibility, the audit trail and that sending writes no rows before the worker resolves.
 */
class DynamicMailingListServiceTest :
    FunSpec({
        val fx = DynamicFixture()
        lateinit var boardId: Uuid
        lateinit var adminId: Uuid
        lateinit var treasurerId: Uuid
        lateinit var plainId: Uuid
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            boardId = fx.member(role = AccountRole.BOARD)
            adminId = fx.member(role = AccountRole.ADMIN)
            treasurerId = fx.member(role = AccountRole.TREASURER)
            plainId = fx.member(role = AccountRole.MEMBER)
        }
        afterSpec { fx.cleanup() }

        // A worker that never runs (its scope is cancelled before it starts): sending queues, nothing resolves.
        fun idleWorker() =
            MailingDeliveryWorker(
                transport = NoOpMailTransport(),
                branding = MailBranding.notConfigured(),
                mode = MailingDeliveryMode.LOG,
                trackingToken = testTrackingToken(),
                baseUrl = TEST_TRACKING_BASE_URL,
                scope = CoroutineScope(Job().apply { cancel() }),
                sendDelay = 0.milliseconds,
            )

        fun testApp(
            worker: MailingDeliveryWorker = idleWorker(),
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            block: suspend HttpClient.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, _ -> call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden) }
                        exception<ConflictException> {
                            call,
                            e,
                            ->
                            call.respondText("CONFLICT:${e.message}", status = HttpStatusCode.Conflict)
                        }
                        exception<BadRequestException> { call, e ->
                            call.respondText("BAD_REQUEST:${e.message}", status = HttpStatusCode.BadRequest)
                        }
                        exception<NotFoundException> {
                            call,
                            e,
                            ->
                            call.respondText("NOT_FOUND:${e.message}", status = HttpStatusCode.NotFound)
                        }
                    }
                    routing {
                        fun service(call: ApplicationCall) =
                            MailingService(
                                call = call,
                                deliveryWorker = worker,
                                deliveryMode = MailingDeliveryMode.LOG,
                                branding = MailBranding.notConfigured(),
                                sendDelay = 250.milliseconds,
                                sendDisplayRateLimiter = limiter,
                            )
                        post("/t/create") {
                            val q = call.request.queryParameters
                            val dto =
                                service(call).createDynamicMailingList(
                                    name = q["name"] ?: "Dyn",
                                    description = null,
                                    ruleJson = call.receiveText(),
                                    mandatory = q["mandatory"] == "true",
                                )
                            call.respondText(Json.encodeToString(MailingListDto.serializer(), dto))
                        }
                        post("/t/create-manual") {
                            val dto = service(call).createMailingList(name = "Manuell ${Uuid.random()}", description = null)
                            call.respondText(Json.encodeToString(MailingListDto.serializer(), dto))
                        }
                        post("/t/update/{id}") {
                            val dto =
                                service(
                                    call,
                                ).updateMailingListRule(mailingListId = call.parameters["id"]!!, ruleJson = call.receiveText())
                            call.respondText(Json.encodeToString(MailingListDto.serializer(), dto))
                        }
                        post("/t/preview") {
                            val q = call.request.queryParameters
                            val dto =
                                service(call).previewDynamicRecipients(
                                    ruleJson = call.receiveText(),
                                    mailingListId = q["listId"],
                                    mandatory =
                                        q["mandatory"] == "true",
                                )
                            call.respondText(Json.encodeToString(RecipientPreviewDto.serializer(), dto))
                        }
                        post("/t/optout/{id}") {
                            service(call).optOutOfDynamicList(call.parameters["id"]!!)
                            call.respondText("OK")
                        }
                        post("/t/revoke/{id}") {
                            service(call).revokeOptOut(call.parameters["id"]!!)
                            call.respondText("OK")
                        }
                        get("/t/lists") {
                            call.respondText(
                                Json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(MailingListDto.serializer()),
                                    service(call).listMailingLists(),
                                ),
                            )
                        }
                        post("/t/subscribe/{id}") {
                            service(call).subscribe(call.parameters["id"]!!)
                            call.respondText("OK")
                        }
                        post("/t/unsubscribe/{id}") {
                            service(call).unsubscribe(call.parameters["id"]!!)
                            call.respondText("OK")
                        }
                        post("/t/consent/{id}") {
                            service(
                                call,
                            ).setTrackingConsent(mailingListId = call.parameters["id"]!!, openTracking = true, clickTracking = false)
                            call.respondText("OK")
                        }
                        post("/t/admin-subscribe") {
                            val q = call.request.queryParameters
                            service(call).adminSubscribeMember(mailingListId = q["listId"]!!, memberId = q["memberId"]!!)
                            call.respondText("OK")
                        }
                        get("/t/subscribers/{id}") {
                            call.respondText(service(call).listSubscribers(call.parameters["id"]!!).size.toString())
                        }
                        post("/t/draft") {
                            val q = call.request.queryParameters
                            call.respondText(
                                service(call).createDraftMessage(mailingListId = q["listId"]!!, subject = "Betreff", bodyText = "Text").id,
                            )
                        }
                        post("/t/send/{id}") {
                            call.respondText(service(call).sendMailingMessage(call.parameters["id"]!!).status.name)
                        }
                        get("/t/estimate/{id}") {
                            call.respondText(service(call).mailingSendEstimate(call.parameters["id"]!!).recipientCount.toString())
                        }
                        get("/t/progress/{id}") {
                            call.respondText(
                                Json.encodeToString(
                                    MailingSendProgressDto.serializer(),
                                    service(call).mailingSendProgress(call.parameters["id"]!!),
                                ),
                            )
                        }
                        get("/t/preview-message/{id}") {
                            val dto = service(call).previewMailingMessage(call.parameters["id"]!!)
                            call.respondText("${dto.subject}|${dto.plainText}|${dto.html}")
                        }
                    }
                }
                runBlocking { client.block() }
            }
        }

        suspend fun HttpClient.createDyn(
            asMember: Uuid,
            rule: String,
            mandatory: Boolean = false,
        ): HttpResponse =
            post("/t/create?mandatory=$mandatory") {
                header("X-Member-Id", asMember.toString())
                setBody(rule)
            }

        suspend fun HttpClient.newList(
            committee: Uuid,
            mandatory: Boolean = false,
        ): MailingListDto {
            val r = createDyn(boardId, committeeRule(committee), mandatory)
            r.status shouldBe HttpStatusCode.OK
            return json.decodeFromString(MailingListDto.serializer(), r.bodyAsText())
        }

        suspend fun HttpClient.listsFor(member: Uuid): List<MailingListDto> =
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(MailingListDto.serializer()),
                get("/t/lists") { header("X-Member-Id", member.toString()) }.bodyAsText(),
            )

        suspend fun HttpClient.post(
            path: String,
            asMember: Uuid,
        ): HttpResponse = post(path) { header("X-Member-Id", asMember.toString()) }

        // ---------------------------------------------------------------------------------------------------------------------------

        test("only BOARD and ADMIN create dynamic lists; MEMBER and TREASURER are forbidden") {
            testApp {
                val c = fx.committee()
                createDyn(plainId, committeeRule(c)).status shouldBe HttpStatusCode.Forbidden
                createDyn(treasurerId, committeeRule(c)).status shouldBe HttpStatusCode.Forbidden
                createDyn(boardId, committeeRule(c)).status shouldBe HttpStatusCode.OK
                createDyn(adminId, committeeRule(c), mandatory = true).status shouldBe HttpStatusCode.OK
            }
        }

        test("updating a rule and previewing recipients are board-only too") {
            testApp {
                val c = fx.committee()
                val list = newList(c)
                post("/t/update/${list.id}") {
                    header("X-Member-Id", plainId.toString())
                    setBody(committeeRule(c))
                }.status shouldBe HttpStatusCode.Forbidden
                post("/t/update/${list.id}") {
                    header("X-Member-Id", treasurerId.toString())
                    setBody(committeeRule(c))
                }.status shouldBe HttpStatusCode.Forbidden
                post("/t/preview") {
                    header("X-Member-Id", plainId.toString())
                    setBody(committeeRule(c))
                }.status shouldBe HttpStatusCode.Forbidden
                post("/t/preview") {
                    header("X-Member-Id", boardId.toString())
                    setBody(committeeRule(c))
                }.status shouldBe HttpStatusCode.OK
            }
        }

        test("the preview is rate limited") {
            testApp(limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                val c = fx.committee()
                val statuses =
                    (1..4).map {
                        post("/t/preview") {
                            header("X-Member-Id", boardId.toString())
                            setBody(committeeRule(c))
                        }.status
                    }
                statuses.take(2).forEach { it shouldBe HttpStatusCode.OK }
                statuses.drop(2).forEach { it shouldBe HttpStatusCode.Conflict }
            }
        }

        test("an invalid rule is a BAD_REQUEST that never echoes the input") {
            testApp {
                val secret = "GEHEIM-EINGABE-4711"
                val r = createDyn(boardId, """{"v":1,"op":"ALL","conditions":[{"type":"$secret"}]}""")
                r.status shouldBe HttpStatusCode.BadRequest
                r.bodyAsText() shouldNotContain secret
                createDyn(boardId, "{}").status shouldBe HttpStatusCode.BadRequest
                // STATUS is not offered for mailing lists
                createDyn(boardId, """{"v":1,"op":"ALL","conditions":[{"type":"STATUS","values":["ACTIVE"]}]}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("a rule must name existing, active committees and existing tiers") {
            testApp {
                createDyn(boardId, committeeRule(Uuid.random())).status shouldBe HttpStatusCode.BadRequest
                createDyn(boardId, committeeRule(fx.committee(active = false))).status shouldBe HttpStatusCode.BadRequest
                createDyn(
                    boardId,
                    """{"v":1,"op":"ALL","conditions":[{"type":"TIER","tierIds":["${Uuid.random()}"]}]}""",
                ).status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("creating stores the canonical rule and audits it without any personal data") {
            testApp {
                val c = fx.committee()
                val list = newList(c, mandatory = true)
                list.kind shouldBe MailingListKind.DYNAMIC
                list.mandatory shouldBe true
                list.ruleJson shouldNotBe null
                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(list.id)) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.MAILING_LIST)
                            }.toList()
                    }
                audit.size shouldBe 1
                val after = audit.single()[AuditLogEntryTable.afterSnapshot]!!
                after shouldContain "DYNAMIC"
                after shouldContain c.toString()
                transaction {
                    MailingListTable
                        .selectAll()
                        .where {
                            MailingListTable.id eq
                                Uuid.parse(
                                    list.id,
                                )
                        }.single()[MailingListTable.ruleUpdatedAt]
                } shouldNotBe null
                val text = audit.joinToString { "${it[AuditLogEntryTable.beforeSnapshot]}${it[AuditLogEntryTable.afterSnapshot]}" }
                text shouldNotContain "Dyn-Testmitglied"
                text shouldNotContain "@example.org"
                text shouldNotContain boardId.toString()
            }
        }

        test("a rule change audits before and after; an unchanged rule writes nothing; a MANUAL list is a conflict") {
            testApp {
                val c1 = fx.committee()
                val c2 = fx.committee()
                val list = newList(c1)

                fun updates() =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(list.id)) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.MAILING_LIST)
                            }.count()
                    }
                updates() shouldBe 1L
                post("/t/update/${list.id}") {
                    header("X-Member-Id", boardId.toString())
                    setBody(committeeRule(c2))
                }.status shouldBe HttpStatusCode.OK
                updates() shouldBe 2L
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq Uuid.parse(list.id)) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.MAILING_LIST)
                            }.toList()
                            .last()
                    }
                entry[AuditLogEntryTable.beforeSnapshot]!! shouldContain c1.toString()
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain c2.toString()
                post("/t/update/${list.id}") {
                    header("X-Member-Id", boardId.toString())
                    setBody(committeeRule(c2))
                }.status shouldBe HttpStatusCode.OK
                updates() shouldBe 2L

                val manual = json.decodeFromString(MailingListDto.serializer(), post("/t/create-manual", boardId).bodyAsText())
                post("/t/update/${manual.id}") {
                    header("X-Member-Id", boardId.toString())
                    setBody(committeeRule(c1))
                }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("kind and the mandatory flag are immutable: no RPC writes them after the insert (tripwire)") {
            val methods =
                IMailingService::class.java.methods
                    .map { it.name }
                    .toSet()
            methods.none { it.contains("Kind", ignoreCase = true) || it.contains("Mandatory", ignoreCase = true) } shouldBe true
            val source = File("src/main/kotlin/network/lapis/cloud/server/rpc/MailingService.kt").readText()
            Regex("""it\[kind]\s*=""").findAll(source).count() shouldBe 1
            Regex("""it\[MailingListTable\.mandatory]\s*=""").findAll(source).count() shouldBe 1
            // the one write of each is inside createDynamicMailingList
            val create =
                source
                    .substringAfter(
                        "override suspend fun createDynamicMailingList",
                    ).substringBefore("override suspend fun updateMailingListRule")
            create shouldContain "it[kind] = MailingListKind.DYNAMIC"
        }

        test("opt-out: mandatory list is refused, a list that does not include the caller looks like a missing list") {
            testApp {
                val c = fx.committee()
                val inside = fx.member()
                fx.seat(committeeId = c, memberId = inside)
                val outsider = fx.member()
                val normal = newList(c)
                val mandatory = newList(c, mandatory = true)
                val manual = json.decodeFromString(MailingListDto.serializer(), post("/t/create-manual", boardId).bodyAsText())

                post("/t/optout/${mandatory.id}", inside).status shouldBe HttpStatusCode.BadRequest
                val missing = post("/t/optout/${Uuid.random()}", inside)
                val notIncluded = post("/t/optout/${normal.id}", outsider)
                val onManual = post("/t/optout/${manual.id}", inside)
                missing.status shouldBe HttpStatusCode.NotFound
                notIncluded.status shouldBe HttpStatusCode.NotFound
                onManual.status shouldBe HttpStatusCode.NotFound
                // an outsider asking about the MANDATORY list gets the generic answer too, not the mandatory hint
                post("/t/optout/${mandatory.id}", outsider).status shouldBe HttpStatusCode.NotFound
                post("/t/optout/not-a-uuid", inside).status shouldBe HttpStatusCode.NotFound
                post("/t/revoke/${normal.id}", outsider).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("opt-out and revoke are idempotent; an opted-out member still sees the list and can return") {
            testApp {
                val c = fx.committee()
                val inside = fx.member()
                fx.seat(committeeId = c, memberId = inside)
                val list = newList(c)
                val listId = Uuid.parse(list.id)

                fun optOuts() =
                    transaction { MailingListOptOutTable.selectAll().where { MailingListOptOutTable.mailingListId eq listId }.count() }

                post("/t/optout/${list.id}", inside).status shouldBe HttpStatusCode.OK
                post("/t/optout/${list.id}", inside).status shouldBe HttpStatusCode.OK
                optOuts() shouldBe 1L
                val seen = listsFor(inside).single { it.id == list.id }
                seen.currentMemberOptedOutAt shouldNotBe null
                seen.isSubscribedByCurrentMember shouldBe false

                post("/t/revoke/${list.id}", inside).status shouldBe HttpStatusCode.OK
                post("/t/revoke/${list.id}", inside).status shouldBe HttpStatusCode.OK
                optOuts() shouldBe 0L
                val back = listsFor(inside).single { it.id == list.id }
                back.currentMemberOptedOutAt shouldBe null
                back.isSubscribedByCurrentMember shouldBe true
            }
        }

        test("visibility: outsiders do not see the list; members see it without numbers or JSON, only the condition they meet") {
            testApp {
                val c1 = fx.committee()
                val c2 = fx.committee()
                val inside = fx.member()
                fx.seat(committeeId = c1, memberId = inside)
                val outsider = fx.member()
                // ANY over two committees: the member sits in c1 only and must not learn about c2
                val anyRule = """{"v":1,"op":"ANY","conditions":[{"type":"COMMITTEE","committeeIds":["$c1","$c2"]}]}"""
                val list = json.decodeFromString(MailingListDto.serializer(), createDyn(boardId, anyRule).bodyAsText())

                listsFor(outsider).map { it.id } shouldNotContain list.id
                val own = listsFor(inside).single { it.id == list.id }
                own.subscriberCount shouldBe 0
                own.ruleJson shouldBe null
                own.isSubscribedByCurrentMember shouldBe true
                val item = own.ruleExplanation!!.items.single()
                item.kind shouldBe RuleExplanationKind.COMMITTEE
                item.labels.size shouldBe 1
                item.labels.single() shouldContain c1.toString()

                val board = listsFor(boardId).single { it.id == list.id }
                board.ruleJson shouldNotBe null
                board.subscriberCount shouldBe 1
                board.ruleExplanation!!
                    .items
                    .single()
                    .labels.size shouldBe 2
            }
        }

        test("the old self-service and admin paths on a DYNAMIC list answer like a missing list or refuse") {
            testApp {
                val c = fx.committee()
                val inside = fx.member()
                fx.seat(committeeId = c, memberId = inside)
                val list = newList(c)
                post("/t/subscribe/${list.id}", inside).status shouldBe HttpStatusCode.NotFound
                post("/t/unsubscribe/${list.id}", inside).status shouldBe HttpStatusCode.NotFound
                post("/t/consent/${list.id}", inside).status shouldBe HttpStatusCode.NotFound
                post("/t/admin-subscribe?listId=${list.id}&memberId=$inside") { header("X-Member-Id", boardId.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                get("/t/subscribers/${list.id}") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "0"
            }
        }

        test("sending writes no delivery rows; the estimate equals the resolution; the progress says it is waiting") {
            testApp {
                val c = fx.committee()
                val a = fx.member()
                val b = fx.member()
                val noMail = fx.member(email = "kein-at-zeichen")
                val anon = fx.member(anonymised = true)
                val optedOut = fx.member()
                listOf(a, b, noMail, anon, optedOut).forEach { fx.seat(committeeId = c, memberId = it) }
                val list = newList(c)
                fx.optOut(listId = Uuid.parse(list.id), memberId = optedOut)

                val draft = post("/t/draft?listId=${list.id}") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                get("/t/estimate/$draft") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "2"
                post("/t/send/$draft", boardId).bodyAsText() shouldBe "QUEUED"
                transaction {
                    MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.mailingMessageId eq Uuid.parse(draft) }.count()
                } shouldBe 0L
                get("/t/estimate/$draft") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "2"
                val progress =
                    json.decodeFromString(
                        MailingSendProgressDto.serializer(),
                        get("/t/progress/$draft") { header("X-Member-Id", boardId.toString()) }.bodyAsText(),
                    )
                progress.awaitingRecipientResolution shouldBe true
                progress.total shouldBe 0
                fx.messageRow(Uuid.parse(draft))[MailingMessageTable.recipientsResolvedAt] shouldBe null
                fx.messageIds += Uuid.parse(draft)
            }
        }

        test("the preview of a message to a mandatory list shows the notice; a normal dynamic list shows the unsubscribe hint") {
            testApp {
                val c = fx.committee()
                val mandatory = newList(c, mandatory = true)
                val normal = newList(c)
                val m = post("/t/draft?listId=${mandatory.id}") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val n = post("/t/draft?listId=${normal.id}") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                val mandatoryPreview = get("/t/preview-message/$m") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                mandatoryPreview shouldContain "[Pflichtmitteilung] Betreff"
                mandatoryPreview shouldContain "role=\"note\""
                mandatoryPreview shouldContain "kann nicht abbestellt werden"
                mandatoryPreview shouldNotContain "Abmelden:"
                val normalPreview = get("/t/preview-message/$n") { header("X-Member-Id", boardId.toString()) }.bodyAsText()
                normalPreview shouldNotContain "Pflichtmitteilung"
                normalPreview shouldContain "Abmelden:"
            }
        }

        test("previewDynamicRecipients counts eligible members and the left-out ones, numbers only") {
            testApp {
                val c = fx.committee()
                val a = fx.member()
                val noMail = fx.member(email = "ohne-adresse")
                listOf(a, noMail).forEach { fx.seat(committeeId = c, memberId = it) }
                val r =
                    post("/t/preview") {
                        header("X-Member-Id", boardId.toString())
                        setBody(committeeRule(c))
                    }
                val dto = json.decodeFromString(RecipientPreviewDto.serializer(), r.bodyAsText())
                dto.eligible shouldBe 1
                dto.excludedNoEmail shouldBe 1
                dto.excludedOptedOut shouldBe 0
                r.bodyAsText() shouldNotContain a.toString()
            }
        }

        test("tripwire: the worker is the only place that writes the MAILING_MESSAGE audit entry") {
            val sources =
                File("src/main/kotlin")
                    .walkTopDown()
                    .filter { it.extension == "kt" && it.readText().contains("AuditEntityType.MAILING_MESSAGE") }
                    .map { it.name }
                    .toList()
            sources shouldBe listOf("MailingDeliveryWorker.kt")
        }
    })
