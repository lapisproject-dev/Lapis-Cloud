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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/**
 * Exercises the domain services (Contributions/Documents/Mailing/DirectMessages) end to end
 * against the same H2-backed [DatabaseConfig]/[DevSeedData] the real application uses, without
 * needing to reverse-engineer Kilua RPC's wire format: a handful of throwaway plain Ktor routes
 * call the service classes directly (they take the same [io.ktor.server.application.ApplicationCall]
 * a real `registerService { call -> ... }` factory would hand them) and report results as plain
 * text for assertions. Route registration itself (the real `initRpc`/`applyRoutes` path) is
 * covered separately by [network.lapis.cloud.server.ApplicationTest].
 */
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

class ServiceIntegrationTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            // force = true: tests always run against the H2 in-memory default, never a real
            // deployment, so bypassing the LAPIS_SEED_DEMO_DATA opt-in gate here is safe (the
            // H2-in-memory guard inside seedIfEmpty still applies).
            DevSeedData.seedIfEmpty(force = true)
        }

        test("contribution lifecycle: generate for seeded tier, list, mark paid, summary reflects it") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        post("/test/generate") {
                            val service = ContributionService(call)
                            val count =
                                service.generateContributionsForPeriod(
                                    DevSeedData.standardTierId.toString(),
                                    LocalDate(2026, 9, 1),
                                    LocalDate(2026, 9, 30),
                                )
                            call.respondText(count.toString())
                        }
                        get("/test/summary") {
                            val service = ContributionService(call)
                            val summary = service.getMemberContributionSummary(MEMBER_ID)
                            call.respondText("${summary.contributions.size}:${summary.totalOpen}")
                        }
                        post("/test/mark-paid/{contributionId}") {
                            val service = ContributionService(call)
                            val dto =
                                service.markContributionPaid(
                                    call.parameters["contributionId"]!!,
                                    LocalDateTime(2026, 9, 15, 12, 0),
                                    java.math.BigDecimal("10.00"),
                                    "Integrationstest",
                                )
                            call.respondText(dto.status.name)
                        }
                        get("/test/list") {
                            val service = ContributionService(call)
                            val list = service.listContributions(memberId = MEMBER_ID)
                            call.respondText(list.joinToString(",") { it.id })
                        }
                    }
                }

                // All four seeded demo members are assigned to the "Standardbeitrag" tier —
                // one OPEN contribution row per member should be created for this period.
                val generated = client.post("/test/generate") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                generated shouldBe "4"

                // Idempotent: re-running for the exact same tier+period creates nothing new.
                val generatedAgain = client.post("/test/generate") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                generatedAgain shouldBe "0"

                val summaryBefore = client.get("/test/summary") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                summaryBefore shouldBe "1:10.00"

                val contributionIds = client.get("/test/list") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val contributionId = contributionIds.split(",").first()

                val paidStatus =
                    client
                        .post("/test/mark-paid/$contributionId") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                paidStatus shouldBe ContributionStatus.PAID.name

                val summaryAfter = client.get("/test/summary") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                summaryAfter shouldBe "1:0"
            }
        }

        test("mailing: create list, subscribe, subscriber count reflects it") {
            testApplication {
                application {
                    routing {
                        post("/test/create-list") {
                            val service = MailingService(call)
                            val list = service.createMailingList("Newsletter", "Test-Liste")
                            call.respondText(list.id)
                        }
                        post("/test/subscribe/{listId}") {
                            val service = MailingService(call)
                            service.subscribe(call.parameters["listId"]!!)
                            call.respondText("ok")
                        }
                        get("/test/lists") {
                            val service = MailingService(call)
                            val lists = service.listMailingLists()
                            val target = lists.first()
                            call.respondText("${target.subscriberCount}:${target.isSubscribedByCurrentMember}")
                        }
                    }
                }

                val listId = client.post("/test/create-list") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                client.post("/test/subscribe/$listId") { header("X-Member-Id", MEMBER_ID) }
                val summary = client.get("/test/lists") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                summary shouldBe "1:true"
            }
        }

        test("direct messages: send, appears in recipient inbox, unread count, mark read") {
            testApplication {
                application {
                    routing {
                        post("/test/send") {
                            val service = DirectMessageService(call)
                            service.sendDirectMessage(BOARD_ID, "Hallo vom Integrationstest")
                            call.respondText("ok")
                        }
                        get("/test/unread") {
                            val service = DirectMessageService(call)
                            call.respondText(service.unreadCount().toString())
                        }
                        post("/test/mark-read/{id}") {
                            val service = DirectMessageService(call)
                            service.markRead(call.parameters["id"]!!)
                            call.respondText("ok")
                        }
                        get("/test/inbox") {
                            val service = DirectMessageService(call)
                            val inbox = service.listInbox()
                            call.respondText(inbox.joinToString(",") { it.id })
                        }
                    }
                }

                client.post("/test/send") { header("X-Member-Id", MEMBER_ID) }
                val unreadBefore = client.get("/test/unread") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                unreadBefore shouldBe "1"

                val inboxIds = client.get("/test/inbox") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val messageId = inboxIds.split(",").first()

                client.post("/test/mark-read/$messageId") { header("X-Member-Id", BOARD_ID) }
                val unreadAfter = client.get("/test/unread") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                unreadAfter shouldBe "0"
            }
        }

        test("documents: board creates folder + document, member without privilege cannot") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        post("/test/create-folder") {
                            val service = DocumentService(call)
                            val folder = service.createFolder("Satzungen")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    call.parameters["folderId"]!!,
                                    "Vereinssatzung 2026",
                                    DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(doc.id)
                        }
                    }
                }

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val docId =
                    client
                        .post("/test/create-document/$folderId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                docId.isBlank() shouldBe false

                val forbidden = client.post("/test/create-folder") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("documents: ADMIN_ONLY is invisible to BOARD in listDocuments and listVersions rejects it") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing {
                        post("/test/create-folder") {
                            val service = DocumentService(call)
                            val folder = service.createFolder("Executive Board Documents")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}/{level}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    call.parameters["folderId"]!!,
                                    "Secretdokument",
                                    DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(doc.id)
                        }
                        get("/test/list-documents/{folderId}") {
                            val service = DocumentService(call)
                            val docs = service.listDocuments(call.parameters["folderId"]!!)
                            call.respondText(docs.joinToString(",") { it.id })
                        }
                        get("/test/list-versions/{documentId}") {
                            val service = DocumentService(call)
                            val versions = service.listVersions(call.parameters["documentId"]!!)
                            call.respondText(versions.size.toString())
                        }
                    }
                }

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val adminOnlyDocId =
                    client
                        .post("/test/create-document/$folderId/ADMIN_ONLY") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()

                // ADMIN sees it, BOARD does not — the three DocumentAccessLevel tiers must not collapse.
                val listedByAdmin =
                    client.get("/test/list-documents/$folderId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                (adminOnlyDocId in listedByAdmin.split(",")) shouldBe true

                val listedByBoard =
                    client.get("/test/list-documents/$folderId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                (adminOnlyDocId in listedByBoard.split(",")) shouldBe false

                // listVersions must apply the same access check as listDocuments/download, not skip it.
                val versionsForAdmin =
                    client.get("/test/list-versions/$adminOnlyDocId") { header("X-Member-Id", ADMIN_ID) }
                versionsForAdmin.status shouldBe HttpStatusCode.OK

                val versionsForBoard =
                    client.get("/test/list-versions/$adminOnlyDocId") { header("X-Member-Id", BOARD_ID) }
                versionsForBoard.status shouldBe HttpStatusCode.Forbidden

                val versionsForMember =
                    client.get("/test/list-versions/$adminOnlyDocId") { header("X-Member-Id", MEMBER_ID) }
                versionsForMember.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("mailing: sending an already-sent message is rejected, not re-delivered") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        post("/test/create-list2") {
                            val service = MailingService(call)
                            val list = service.createMailingList("Rundschreiben", null)
                            call.respondText(list.id)
                        }
                        post("/test/draft/{listId}") {
                            val service = MailingService(call)
                            val message = service.createDraftMessage(call.parameters["listId"]!!, "Betreff", "Text")
                            call.respondText(message.id)
                        }
                        post("/test/send/{messageId}") {
                            val service = MailingService(call)
                            val message = service.sendMailingMessage(call.parameters["messageId"]!!)
                            call.respondText(message.status.name)
                        }
                    }
                }

                val listId = client.post("/test/create-list2") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val messageId = client.post("/test/draft/$listId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()

                val firstSend = client.post("/test/send/$messageId") { header("X-Member-Id", BOARD_ID) }
                firstSend.status shouldBe HttpStatusCode.OK
                firstSend.bodyAsText() shouldBe "SENT"

                val secondSend = client.post("/test/send/$messageId") { header("X-Member-Id", BOARD_ID) }
                secondSend.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("members: listMembers works without X-Member-Id (picker bootstrap) and leaks no email/role") {
            testApplication {
                application {
                    routing {
                        get("/test/list-members") {
                            val service = MemberService(call)
                            val members = service.listMembers()
                            // MemberSummaryDto only has id + displayName — this would not compile
                            // (and thus not leak email/role) if listMembers ever went back to
                            // returning the full MemberDto.
                            call.respondText(members.joinToString(",") { "${it.id}:${it.displayName}" })
                        }
                    }
                }

                // No X-Member-Id header at all: must still succeed, since this is the
                // unauthenticated bootstrap for choosing a member in the first place.
                val response = client.get("/test/list-members")
                response.status shouldBe HttpStatusCode.OK

                val entries = response.bodyAsText().split(",")
                entries.size shouldBe 4
                (entries.any { it.startsWith("$MEMBER_ID:") }) shouldBe true
            }
        }
    })
