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
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

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

        /**
         * Direct-DB `Member(status=GAST)` + `Account(role=MEMBER)` insert, mirroring the identical
         * idiom used across the suite (e.g. `CrowdfundingServiceTest`/`PoliticianServiceTest`'s own
         * `createTestMember(..., status = MemberStatus.GUEST)`) -- this is exactly the shape
         * [network.lapis.cloud.server.federation.OidcGuestMemberStore] produces for a real federated
         * guest, so resolving this member via the trusted `X-Member-Id` test header exercises the
         * same [network.lapis.cloud.server.security.CurrentMember.isGuest] path a real guest session
         * would.
         */
        fun createTestGuestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Gast-Testmitglied (Document-Guest-Access-Test)"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.GUEST
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            return id
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
                                    membershipTierId = DevSeedData.standardTierId.toString(),
                                    periodStart = LocalDate(2026, 9, 1),
                                    periodEnd = LocalDate(2026, 9, 30),
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
                                    contributionId = call.parameters["contributionId"]!!,
                                    paidAt = LocalDateTime(2026, 9, 15, 12, 0),
                                    paidAmount = java.math.BigDecimal("10.00"),
                                    note = "Integrationstest",
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
                            val list = service.createMailingList(name = "Newsletter", description = "Test-Liste")
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
                            service.sendDirectMessage(recipientId = BOARD_ID, body = "Hallo vom Integrationstest")
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

        test(
            "documents: board/treasurer/admin create folder + document + delete it, member without privilege cannot (Welle Treasurer Document Upload)",
        ) {
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
                            val folder = service.createFolder(name = "Satzungen")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "Vereinssatzung 2026",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(doc.id)
                        }
                        post("/test/delete-document/{documentId}") {
                            val service = DocumentService(call)
                            service.deleteDocument(call.parameters["documentId"]!!)
                            call.respondText("ok")
                        }
                    }
                }

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val docId =
                    client
                        .post("/test/create-document/$folderId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                docId.isBlank() shouldBe false

                // TREASURER: ESCALATED_ROLES (BOARD/TREASURER/ADMIN) grants the same three write
                // gates as BOARD/ADMIN -- this is the exact gap "Marc Levi Mousa" hit in production
                // (TREASURER could no longer create folders/upload documents).
                val treasurerFolderId =
                    client.post("/test/create-folder") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                treasurerFolderId.isBlank() shouldBe false
                val treasurerDocId =
                    client
                        .post("/test/create-document/$treasurerFolderId") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                treasurerDocId.isBlank() shouldBe false
                val treasurerDelete =
                    client.post("/test/delete-document/$treasurerDocId") { header("X-Member-Id", TREASURER_ID) }
                treasurerDelete.status shouldBe HttpStatusCode.OK

                // ADMIN: same three write gates as BOARD/TREASURER -- review finding fix, the test
                // NAME already claimed this axis was covered but the body never actually exercised
                // ADMIN_ID before this addition, so a regression on ADMIN's ESCALATED_ROLES
                // membership would have stayed green.
                val adminFolderId =
                    client.post("/test/create-folder") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                adminFolderId.isBlank() shouldBe false
                val adminDocId =
                    client
                        .post("/test/create-document/$adminFolderId") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                adminDocId.isBlank() shouldBe false
                val adminDelete =
                    client.post("/test/delete-document/$adminDocId") { header("X-Member-Id", ADMIN_ID) }
                adminDelete.status shouldBe HttpStatusCode.OK

                // MEMBER: still rejected on all three write gates -- ESCALATED_ROLES did not widen
                // access beyond BOARD/TREASURER/ADMIN.
                val forbiddenFolder = client.post("/test/create-folder") { header("X-Member-Id", MEMBER_ID) }
                forbiddenFolder.status shouldBe HttpStatusCode.Forbidden
                val forbiddenDoc = client.post("/test/create-document/$folderId") { header("X-Member-Id", MEMBER_ID) }
                forbiddenDoc.status shouldBe HttpStatusCode.Forbidden
                val forbiddenDelete = client.post("/test/delete-document/$docId") { header("X-Member-Id", MEMBER_ID) }
                forbiddenDelete.status shouldBe HttpStatusCode.Forbidden
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
                            val folder = service.createFolder(name = "Executive Board Documents")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}/{level}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "Secretdokument",
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
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

        test(
            "documents: deleteDocument enforces canAccessDocumentAtLevel, not just the ESCALATED_ROLES role gate " +
                "(review finding fix, Welle \"Treasurer Document Upload\")",
        ) {
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
                            val folder = service.createFolder(name = "Beitragsrechnungen (Delete-Authz-Test)")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}/{level}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "Spendenbescheinigung",
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(doc.id)
                        }
                        post("/test/delete-document/{documentId}") {
                            val service = DocumentService(call)
                            service.deleteDocument(call.parameters["documentId"]!!)
                            call.respondText("ok")
                        }
                    }
                }

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val adminOnlyDocId =
                    client
                        .post("/test/create-document/$folderId/ADMIN_ONLY") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()

                // TREASURER is role-wise in ESCALATED_ROLES but cannot read ADMIN_ONLY content ->
                // must not be able to soft-delete it by proxy either. This is exactly the gap MAJOR
                // #2 fixed in production code -- pin it so a future "simplification" that reverts to
                // the old role-only gate on DocumentTable.update{} fails a test instead of shipping.
                val treasurerDelete =
                    client.post("/test/delete-document/$adminOnlyDocId") { header("X-Member-Id", TREASURER_ID) }
                treasurerDelete.status shouldBe HttpStatusCode.Forbidden

                // BOARD is likewise ESCALATED_ROLES but below ADMIN_ONLY's access level.
                val boardDelete =
                    client.post("/test/delete-document/$adminOnlyDocId") { header("X-Member-Id", BOARD_ID) }
                boardDelete.status shouldBe HttpStatusCode.Forbidden

                // ADMIN can delete its own ADMIN_ONLY document.
                val adminDelete =
                    client.post("/test/delete-document/$adminOnlyDocId") { header("X-Member-Id", ADMIN_ID) }
                adminDelete.status shouldBe HttpStatusCode.OK

                // Deleting an unknown document id is a 404, not a silent no-op 200.
                val unknownDelete =
                    client.post("/test/delete-document/${Uuid.random()}") { header("X-Member-Id", ADMIN_ID) }
                unknownDelete.status shouldBe HttpStatusCode.NotFound

                // Deleting an already-soft-deleted document id is a 404 as well (not idempotent 200).
                val alreadyDeletedDelete =
                    client.post("/test/delete-document/$adminOnlyDocId") { header("X-Member-Id", ADMIN_ID) }
                alreadyDeletedDelete.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "documents: createDocument enforces canAccessDocumentAtLevel, not just the ESCALATED_ROLES role gate " +
                "(review finding fix Runde 4, Welle \"Treasurer Document Upload\")",
        ) {
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
                            val folder = service.createFolder(name = "Vorstandsprotokolle (Create-Authz-Test)")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}/{level}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "Vertrauliches Dokument",
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(doc.id)
                        }
                    }
                }

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()

                // TREASURER is role-wise in ESCALATED_ROLES (passes the createFolder/createDocument
                // role gate) but cannot read ADMIN_ONLY content -> must not be able to create an
                // ADMIN_ONLY document either. This is exactly the MAJOR #5 gap: without this check,
                // the resulting document would be invisible in listDocuments, un-uploadable and
                // un-deletable by its own creator -- an orphaned row only an ADMIN could ever fix.
                val treasurerCreate =
                    client.post("/test/create-document/$folderId/ADMIN_ONLY") { header("X-Member-Id", TREASURER_ID) }
                treasurerCreate.status shouldBe HttpStatusCode.Forbidden

                // BOARD is likewise ESCALATED_ROLES but below ADMIN_ONLY's access level.
                val boardCreate =
                    client.post("/test/create-document/$folderId/ADMIN_ONLY") { header("X-Member-Id", BOARD_ID) }
                boardCreate.status shouldBe HttpStatusCode.Forbidden

                // ADMIN can create its own ADMIN_ONLY document.
                val adminCreate =
                    client.post("/test/create-document/$folderId/ADMIN_ONLY") { header("X-Member-Id", ADMIN_ID) }
                adminCreate.status shouldBe HttpStatusCode.OK
                adminCreate.bodyAsText().isBlank() shouldBe false

                // TREASURER can still create at a level it is itself allowed to read (BOARD_ONLY is
                // ESCALATED_ROLES-gated, same as canAccessDocumentAtLevel's BOARD_ONLY branch).
                val treasurerBoardOnlyCreate =
                    client.post("/test/create-document/$folderId/BOARD_ONLY") { header("X-Member-Id", TREASURER_ID) }
                treasurerBoardOnlyCreate.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "documents: a guest session is excluded from PUBLIC_MEMBERS in listDocuments and listVersions rejects it, while a real member's access is unchanged",
        ) {
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
                            val folder = service.createFolder(name = "Satzungen (Guest-Scope-Test)")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "Vereinssatzung (Guest-Scope-Test)",
                                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
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

                val guestId = createTestGuestMember("svc-guest-doc-scope@example.org")

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val publicDocId =
                    client
                        .post("/test/create-document/$folderId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()

                // Regression: a real local member (plain MEMBER role) still sees and can read a
                // PUBLIC_MEMBERS document exactly as before this fix -- the fix must not tighten
                // access for anyone who is NOT a guest.
                val listedByMember =
                    client.get("/test/list-documents/$folderId") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                (publicDocId in listedByMember.split(",")) shouldBe true
                val versionsForMember =
                    client.get("/test/list-versions/$publicDocId") { header("X-Member-Id", MEMBER_ID) }
                versionsForMember.status shouldBe HttpStatusCode.OK

                // The fix: a guest session (role = MEMBER, status = GAST) is excluded from
                // PUBLIC_MEMBERS-level documents -- filtered out of listDocuments, and listVersions
                // rejects a direct-by-id attempt with Forbidden, exactly like the BOARD_ONLY/
                // ADMIN_ONLY tiers already did for non-privileged callers.
                val listedByGuest =
                    client.get("/test/list-documents/$folderId") { header("X-Member-Id", guestId.toString()) }.bodyAsText()
                (publicDocId in listedByGuest.split(",")) shouldBe false
                val versionsForGuest =
                    client.get("/test/list-versions/$publicDocId") { header("X-Member-Id", guestId.toString()) }
                versionsForGuest.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "documents: listFolders' documentCount respects the caller's access level, matches listDocuments " +
                "for the same caller, excludes soft-deleted documents, and is 0 for an empty folder",
        ) {
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
                        post("/test/create-folder2") {
                            val service = DocumentService(call)
                            val folder = service.createFolder(name = "Mischordner (Folder-Count-Test)")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document2/{folderId}/{level}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    folderId = call.parameters["folderId"]!!,
                                    title = "Folder-Count-Test-Dokument",
                                    accessLevel = DocumentAccessLevel.valueOf(call.parameters["level"]!!),
                                )
                            call.respondText(doc.id)
                        }
                        get("/test/list-folders") {
                            val service = DocumentService(call)
                            val folders = service.listFolders()
                            call.respondText(folders.joinToString(";") { "${it.id}=${it.documentCount}" })
                        }
                        get("/test/list-documents2/{folderId}") {
                            val service = DocumentService(call)
                            val docs = service.listDocuments(call.parameters["folderId"]!!)
                            call.respondText(docs.size.toString())
                        }
                        post("/test/delete-document2/{documentId}") {
                            val service = DocumentService(call)
                            service.deleteDocument(call.parameters["documentId"]!!)
                            call.respondText("ok")
                        }
                    }
                }

                fun documentCountOf(
                    body: String,
                    folderId: String,
                ): Int =
                    body
                        .split(";")
                        .single { it.startsWith("$folderId=") }
                        .substringAfter("=")
                        .toInt()

                // Ordner ohne Dokumente -> documentCount == 0, nicht null/fehlend.
                val emptyFolderId = client.post("/test/create-folder2") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val emptyFolderListing =
                    client.get("/test/list-folders") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                documentCountOf(emptyFolderListing, emptyFolderId) shouldBe 0

                // Gemischter Ordner: 2x PUBLIC_MEMBERS, 3x BOARD_ONLY, 1x PUBLIC_MEMBERS (wird gleich
                // soft-geloescht).
                val folderId = client.post("/test/create-folder2") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val publicDocIds =
                    (1..2).map {
                        client
                            .post("/test/create-document2/$folderId/PUBLIC_MEMBERS") { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                    }
                repeat(3) {
                    client.post("/test/create-document2/$folderId/BOARD_ONLY") { header("X-Member-Id", BOARD_ID) }
                }
                val softDeletedDocId =
                    client
                        .post("/test/create-document2/$folderId/PUBLIC_MEMBERS") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()

                // Vor dem Soft-Delete: MEMBER (nur PUBLIC_MEMBERS) sieht 3 (2 + das gleich zu
                // loeschende), BOARD (PUBLIC_MEMBERS + BOARD_ONLY) sieht alle 6.
                val listingForMemberBefore =
                    client.get("/test/list-folders") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                documentCountOf(listingForMemberBefore, folderId) shouldBe 3
                val listedCountForMemberBefore =
                    client.get("/test/list-documents2/$folderId") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().toInt()
                documentCountOf(listingForMemberBefore, folderId) shouldBe listedCountForMemberBefore

                // Board/Admin sieht mehr als ein einfaches Mitglied im selben Ordner -- die Zaehlung
                // ist wirklich pro-Aufrufer, nicht global gecacht.
                val listingForBoardBefore =
                    client.get("/test/list-folders") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                documentCountOf(listingForBoardBefore, folderId) shouldBe 6
                val listedCountForBoardBefore =
                    client.get("/test/list-documents2/$folderId") { header("X-Member-Id", BOARD_ID) }.bodyAsText().toInt()
                documentCountOf(listingForBoardBefore, folderId) shouldBe listedCountForBoardBefore

                // Soft-Delete eines der PUBLIC_MEMBERS-Dokumente -> Count faellt fuer MEMBER von 3
                // auf 2, fuer BOARD von 6 auf 5.
                client.post("/test/delete-document2/$softDeletedDocId") { header("X-Member-Id", BOARD_ID) }

                val listingForMemberAfter =
                    client.get("/test/list-folders") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                documentCountOf(listingForMemberAfter, folderId) shouldBe 2

                val listingForBoardAfter =
                    client.get("/test/list-folders") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                documentCountOf(listingForBoardAfter, folderId) shouldBe 5

                publicDocIds.size shouldBe 2
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
                            val list = service.createMailingList(name = "Rundschreiben", description = null)
                            call.respondText(list.id)
                        }
                        post("/test/draft/{listId}") {
                            val service = MailingService(call)
                            val message =
                                service.createDraftMessage(
                                    mailingListId = call.parameters["listId"]!!,
                                    subject = "Betreff",
                                    bodyText = "Text",
                                )
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

        // V1.2.11 (PdV-CSV-Import, security fix): listMembers now requires authentication -- see
        // IMemberService.listMembers KDoc for the full rationale. Split into two tests: the
        // UnauthenticatedException path (was previously the whole point of this test) and the
        // authenticated happy path (was previously implicit, since the call needed no header at all).
        test("members: listMembers rejects a caller with no X-Member-Id / no session") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                    }
                    routing {
                        get("/test/list-members") {
                            val service =
                                MemberService(
                                    call = call,
                                    friendVerificationMailer = FakeFriendVerificationMailer(),
                                    memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(),
                                    memberCoreDataFriendMailActorRateLimiter = FederationInboxRateLimiter(),
                                    passwordResetMailer = FakePasswordResetMailer(),
                                    adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                                    smtpConfigState = SmtpConfigState.NotConfigured,
                                    adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                                    adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                                    adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                                    memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                                )
                            val members = service.listMembers()
                            call.respondText(members.joinToString(",") { "${it.id}:${it.displayName}" })
                        }
                    }
                }

                val response = client.get("/test/list-members")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("members: listMembers works for an authenticated caller and leaks no email/role") {
            testApplication {
                application {
                    routing {
                        get("/test/list-members") {
                            val service =
                                MemberService(
                                    call = call,
                                    friendVerificationMailer = FakeFriendVerificationMailer(),
                                    memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(),
                                    memberCoreDataFriendMailActorRateLimiter = FederationInboxRateLimiter(),
                                    passwordResetMailer = FakePasswordResetMailer(),
                                    adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                                    smtpConfigState = SmtpConfigState.NotConfigured,
                                    adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                                    adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                                    adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                                    memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                                )
                            val members = service.listMembers()
                            // MemberSummaryDto only has id + displayName — this would not compile
                            // (and thus not leak email/role) if listMembers ever went back to
                            // returning the full MemberDto.
                            call.respondText(members.joinToString(",") { "${it.id}:${it.displayName}" })
                        }
                    }
                }

                val response = client.get("/test/list-members") { header("X-Member-Id", MEMBER_ID) }
                response.status shouldBe HttpStatusCode.OK

                val entries = response.bodyAsText().split(",")
                entries.size shouldBe 4
                (entries.any { it.startsWith("$MEMBER_ID:") }) shouldBe true
            }
        }
    })
