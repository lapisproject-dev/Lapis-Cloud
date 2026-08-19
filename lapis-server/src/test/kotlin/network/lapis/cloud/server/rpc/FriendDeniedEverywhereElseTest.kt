package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

/**
 * The negative matrix from the wave's own test plan: [MemberStatus.FRIEND] is refused by every
 * endpoint OUTSIDE its granted scope (conference access, and -- since Welle V1.1.4 -- the LTR
 * economy's self-service surface, see [MemberStatusSets.LTR_ELIGIBLE]). One assertion per gate,
 * mirroring the naming/shape the plan itself specifies. Representative coverage across every risk
 * category the plan's B2 decision table lists (conference room enumeration/creation,
 * politician-rating, direct messages, mailing lists, document access) -- not an exhaustive
 * per-endpoint enumeration of literally every governance/election method, most of which gate on an
 * EXISTING target entity first and would need substantial fixture setup unrelated to the FRIEND
 * question itself; [MembershipGuardsTest] already proves the underlying guard primitives
 * exhaustively for every status × every guard function.
 *
 * **LTR economy note (Welle V1.1.4):** [LtrLedgerService.getMyBalance]/`.listMyEntries` moved OUT
 * of this negative matrix -- FRIEND is deliberately admitted there now
 * ([MemberStatusSets.LTR_ELIGIBLE]), see `LtrLedgerServiceTest`'s own positive coverage. Sending a
 * peer transfer ([PeerTransferService.transferLtr]) remains refused below -- only the sender side
 * is gated, and that gate is unchanged by V1.1.4.
 */
class FriendDeniedEverywhereElseTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // The document-visibility test creates a folder+document authored by an ACTIVE
                // (BOARD-role) member -- document.created_by FKs to member, delete before the
                // member row itself. document_folder has no created_by column of its own.
                DocumentTable.deleteWhere { DocumentTable.createdBy inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createFriendMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Denied-Everywhere Testfreund"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.FRIEND
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[friendSince] = LocalDate(2026, 1, 1)
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

        fun createActiveMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Denied-Everywhere Aktivmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
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

        test("FRIEND is refused by createRoom, listActiveRooms, getRoom -- conference-room enumeration/creation stays ACTIVE-only") {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedConferenceTestRoutes() }
                }
                val friend = createFriendMember("denied-conf-rooms@example.org")

                client.post("/test/create-room?title=Sollte-Scheitern") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/test/list-active-rooms") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/test/get-room?roomId=${Uuid.random()}") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "FRIEND is refused by castRating and retractRating -- an unverified, self-registered name must not move the public trust metric",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedPoliticianTestRoutes() }
                }
                // politicianRankingEnabled defaults false org-wide -- flip it directly (same
                // shared-row-hazard idiom FederationGuestJourneyTest's own class KDoc documents,
                // not through the ADMIN-only RPC which would risk clobbering another already-run
                // Spec's settings row) so requirePoliticianRankingEnabled() does not itself throw
                // a Conflict BEFORE the FRIEND gate is even reached.
                transaction {
                    network.lapis.cloud.server.db.generated.OrganizationSettingsTable.update({
                        network.lapis.cloud.server.db.generated.OrganizationSettingsTable.id eq
                            network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
                    }) { it[politicianRankingEnabled] = true }
                }
                try {
                    val friend = createFriendMember("denied-politician@example.org")
                    val someTarget = Uuid.random().toString()

                    client.post("/test/cast-rating?target=$someTarget") { header("X-Member-Id", friend.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.post("/test/retract-rating?target=$someTarget") { header("X-Member-Id", friend.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                } finally {
                    transaction {
                        network.lapis.cloud.server.db.generated.OrganizationSettingsTable.update({
                            network.lapis.cloud.server.db.generated.OrganizationSettingsTable.id eq
                                network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
                        }) { it[politicianRankingEnabled] = false }
                    }
                }
            }
        }

        test("FRIEND is refused by every LTR-economy endpoint: submitProject (crowdfunding), transferLtr (peer transfer)") {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedLtrTestRoutes() }
                }
                val friend = createFriendMember("denied-ltr@example.org")
                val other = createActiveMember("denied-ltr-recipient@example.org")

                // placeBid (AuctionService) is deliberately NOT exercised here -- it gates on
                // requireAuctionEnabled() BEFORE requireActiveMembership, and enabling auctions
                // requires a full ADMIN compliance-acknowledgment flow unrelated to the FRIEND
                // question this test is about. requireActiveMembership itself is already proven
                // exhaustively for every status by MembershipGuardsTest.
                client.post("/test/submit-project") { header("X-Member-Id", friend.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/test/transfer-ltr?recipientId=$other") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "FRIEND is refused by sendDirectMessage, listInbox, unreadCount -- DirectMessageService's V0.11.0 security fix covers FRIEND too",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedDirectMessageTestRoutes() }
                }
                val friend = createFriendMember("denied-dm@example.org")
                val other = createActiveMember("denied-dm-recipient@example.org")

                client.post("/test/send-dm?recipientId=$other") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/test/inbox") { header("X-Member-Id", friend.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/unread-count") { header("X-Member-Id", friend.toString()) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("FRIEND is refused by listMailingLists and subscribe -- org mailing lists stay ACTIVE-only") {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedMailingTestRoutes() }
                }
                val friend = createFriendMember("denied-mailing@example.org")

                client.get("/test/list-mailing-lists") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/subscribe?listId=${Uuid.random()}") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "Welle V1.1.4 update: FRIEND is now ADMITTED by getMyBalance/listMyEntries (LtrLedgerService) -- LTR_ELIGIBLE " +
                "widens this defence-in-depth gate; a fresh FRIEND simply sees an empty balance/entry list (0.00 LTR, no " +
                "write path has run yet), same as a fresh ACTIVE member. See MembershipGuards.requireLtrEligibleMembership " +
                "KDoc and FriendCapabilityBoundaryTest for the domains that remain ACTIVE-only.",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedLtrLedgerTestRoutes() }
                }
                val friend = createFriendMember("denied-ltr-ledger@example.org")

                client.get("/test/my-balance") { header("X-Member-Id", friend.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/my-entries") { header("X-Member-Id", friend.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "canAccessDocumentAtLevel(PUBLIC_MEMBERS) end to end: a PUBLIC_MEMBERS document is invisible to listDocuments for a FRIEND caller",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDeniedExceptionHandlers() }
                    routing { registerDeniedDocumentTestRoutes() }
                }
                val author = createActiveMember("denied-doc-author@example.org", role = AccountRole.BOARD)
                val friend = createFriendMember("denied-doc-friend@example.org")

                val folderId = client.post("/test/create-folder") { header("X-Member-Id", author.toString()) }.bodyAsText()
                client.post("/test/create-document?folderId=$folderId") { header("X-Member-Id", author.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val asAuthor = client.get("/test/list-documents") { header("X-Member-Id", author.toString()) }.bodyAsText()
                asAuthor.split(",").filter { it.isNotBlank() }.size shouldBe 1

                val asFriend = client.get("/test/list-documents") { header("X-Member-Id", friend.toString()) }.bodyAsText()
                asFriend.split(",").filter { it.isNotBlank() }.size shouldBe 0
            }
        }
    })

private fun StatusPagesConfig.installDeniedExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

private class NoopLiveKitAdminClient : LiveKitAdminClient {
    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0)

    override suspend fun deleteRoom(name: String) = Unit

    override suspend fun listRooms(): List<LiveKitRoomInfo> = emptyList()

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = emptyList()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) = Unit
}

private fun Route.registerDeniedConferenceTestRoutes() {
    fun service(call: ApplicationCall) =
        ConferenceService(
            call = call,
            liveKitAdminClient = NoopLiveKitAdminClient(),
            createRoomRateLimiter = LoginRateLimiter(),
            config = ENABLED_CONFIG,
            joinRoomRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            leaveRoomRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            listRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            guestAccessRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
        )
    post("/test/create-room") {
        val title = call.request.queryParameters["title"]!!
        service(call).createRoom(ConferenceRoomInput(title = title, description = "", allowFederationGuests = false))
        call.respondText("ok")
    }
    get("/test/list-active-rooms") {
        service(call).listActiveRooms()
        call.respondText("ok")
    }
    get("/test/get-room") {
        service(call).getRoom(call.request.queryParameters["roomId"]!!)
        call.respondText("ok")
    }
}

private fun Route.registerDeniedPoliticianTestRoutes() {
    fun service(call: ApplicationCall) = PoliticianService(call = call)
    post("/test/cast-rating") {
        service(call).castRating(
            politicianMemberId = call.request.queryParameters["target"]!!,
            value = PoliticianReactionValue.LIKE,
        )
        call.respondText("ok")
    }
    post("/test/retract-rating") {
        service(call).retractRating(call.request.queryParameters["target"]!!)
        call.respondText("ok")
    }
}

private fun Route.registerDeniedLtrTestRoutes() {
    post("/test/place-bid") {
        AuctionService(call = call).placeBid(
            auctionId = call.request.queryParameters["auctionId"]!!,
            maxBidLtr = BigDecimal("10.00"),
        )
        call.respondText("ok")
    }
    post("/test/submit-project") {
        CrowdfundingService(call = call).submitProject(
            network.lapis.cloud.shared.domain.CrowdfundingProjectInput(
                title = "Sollte-Scheitern",
                description = "Sollte scheitern",
                initialWeightLtr = BigDecimal("1.00"),
            ),
        )
        call.respondText("ok")
    }
    post("/test/transfer-ltr") {
        PeerTransferService(call = call).transferLtr(
            PeerTransferInput(
                recipientMemberId = call.request.queryParameters["recipientId"]!!,
                amountLtr = BigDecimal("1.00"),
                characterization = network.lapis.cloud.shared.domain.PeerTransferCharacterization.SONSTIGES,
                purpose = null,
            ),
        )
        call.respondText("ok")
    }
}

private fun Route.registerDeniedDirectMessageTestRoutes() {
    fun service(call: ApplicationCall) = DirectMessageService(call = call)
    post("/test/send-dm") {
        service(call).sendDirectMessage(recipientId = call.request.queryParameters["recipientId"]!!, body = "Sollte scheitern")
        call.respondText("ok")
    }
    get("/test/inbox") {
        service(call).listInbox()
        call.respondText("ok")
    }
    get("/test/unread-count") {
        service(call).unreadCount()
        call.respondText("ok")
    }
}

private fun Route.registerDeniedMailingTestRoutes() {
    fun service(call: ApplicationCall) = MailingService(call = call)
    get("/test/list-mailing-lists") {
        service(call).listMailingLists()
        call.respondText("ok")
    }
    post("/test/subscribe") {
        service(call).subscribe(call.request.queryParameters["listId"]!!)
        call.respondText("ok")
    }
}

private fun Route.registerDeniedLtrLedgerTestRoutes() {
    fun service(call: ApplicationCall) = LtrLedgerService(call = call)
    get("/test/my-balance") {
        service(call).getMyBalance()
        call.respondText("ok")
    }
    get("/test/my-entries") {
        service(call).listMyEntries(20)
        call.respondText("ok")
    }
}

private fun Route.registerDeniedDocumentTestRoutes() {
    fun service(call: ApplicationCall) = DocumentService(call = call)
    post("/test/create-folder") {
        val folder = service(call).createFolder(name = "Denied-Test-Ordner", parentFolderId = null)
        call.respondText(folder.id)
    }
    post("/test/create-document") {
        val folderId = call.request.queryParameters["folderId"]!!
        val doc =
            service(call).createDocument(
                folderId = folderId,
                title = "Denied-Test-Dokument",
                accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
            )
        call.respondText(doc.id)
    }
    get("/test/list-documents") {
        val docs = service(call).listDocuments(folderId = null)
        call.respondText(docs.joinToString(",") { it.id })
    }
}
