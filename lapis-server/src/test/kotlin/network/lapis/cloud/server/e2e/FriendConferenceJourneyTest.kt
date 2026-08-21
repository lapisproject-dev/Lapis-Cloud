package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.rpc.FriendTermsDisclaimer
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Same hermetic fake as [network.lapis.cloud.server.rpc.ConferenceServiceTest]'s own -- duplicated here because that one is file-private, mirroring [FederationGuestJourneyTest]'s own `E2eFakeLiveKitAdminClient`. */
private class FriendJourneyFakeLiveKitAdminClient : LiveKitAdminClient {
    private val rooms = mutableMapOf<String, LiveKitRoomInfo>()
    private val participantsByRoom = mutableMapOf<String, MutableList<LiveKitParticipantInfo>>()

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo {
        val info = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0)
        rooms[name] = info
        participantsByRoom.getOrPut(name) { mutableListOf() }
        return info
    }

    override suspend fun deleteRoom(name: String) {
        rooms.remove(name)
        participantsByRoom.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> = rooms.values.toList()

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = participantsByRoom[room].orEmpty()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        participantsByRoom[room]?.removeIf { it.identity == identity }
    }
}

private val FRIEND_JOURNEY_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

/**
 * The wave's own mandated E2E scenario -- see the plan's "Test plan / E2E" section. Mirrors the
 * shape of [FederationGuestJourneyTest] (real, fully-wired [module], real login/session, throwaway
 * RPC routes atop it) but crosses the FRIEND-specific lifecycle instead of the federated-guest one:
 * self-registration -> real login -> opted-in conference join -> moderator revoke disconnects the
 * friend -> self-service upgrade to APPLICATION -> board approval -> now-ACTIVE member gains a
 * right ([LtrLedgerService.getMyBalance]) it did not have as a FRIEND.
 */
class FriendConferenceJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdRoomIds.isNotEmpty() || createdMemberIds.isNotEmpty()) {
                    ConferenceGuestConsentAcknowledgmentTable.deleteWhere {
                        (ConferenceGuestConsentAcknowledgmentTable.roomId inList createdRoomIds) or
                            (ConferenceGuestConsentAcknowledgmentTable.memberId inList createdMemberIds)
                    }
                    ConferenceParticipationTable.deleteWhere {
                        (ConferenceParticipationTable.roomId inList createdRoomIds) or
                            (ConferenceParticipationTable.memberId inList createdMemberIds)
                    }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId inList createdMemberIds }
                    MembershipAgreementAcknowledgmentTable.deleteWhere {
                        MembershipAgreementAcknowledgmentTable.memberId inList createdMemberIds
                    }
                }
                hardDeleteGovernanceAndMembershipFixtures(committeeIds = emptyList(), memberIds = createdMemberIds)
            }
        }

        test(
            "friend self-registration -> real login -> opted-in conference join -> moderator revoke disconnects -> " +
                "applyForMembership -> board approves -> now-ACTIVE member gains getMyBalance it lacked as FRIEND",
        ) {
            testApplication {
                val fakeLiveKit = FriendJourneyFakeLiveKitAdminClient()
                application {
                    module()
                    routing {
                        fun registrationService(call: ApplicationCall) =
                            RegistrationService(
                                call = call,
                                registrationRateLimiter = LoginRateLimiter(),
                                friendRegistrationRateLimiter = LoginRateLimiter(),
                                friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                                friendVerificationMailer = FakeFriendVerificationMailer(),
                            )
                        post("/friend-journey/register") {
                            val q = call.request.queryParameters
                            registrationService(call).registerFriend(
                                FriendRegistrationInput(
                                    displayName = q["displayName"]!!,
                                    email = q["email"]!!,
                                    password = E2E_STRONG_PASSWORD,
                                    termsVersion = FriendTermsDisclaimer.VERSION,
                                    termsSha256 = FriendTermsDisclaimer.SHA256,
                                ),
                            )
                            call.respondText("ok")
                        }
                        post("/friend-journey/apply-for-membership") {
                            val dto =
                                registrationService(call).applyForMembership(
                                    agreementVersion = MembershipAgreementDisclaimer.VERSION,
                                    agreementSha256 = MembershipAgreementDisclaimer.SHA256,
                                )
                            call.respondText(dto.status.name)
                        }
                        post("/friend-journey/approve/{id}") {
                            val dto = registrationService(call).approveApplication(call.parameters["id"]!!)
                            call.respondText(dto.status.name)
                        }

                        fun conferenceService(call: ApplicationCall) =
                            ConferenceService(
                                call = call,
                                liveKitAdminClient = fakeLiveKit,
                                createRoomRateLimiter = LoginRateLimiter(),
                                config = FRIEND_JOURNEY_CONFERENCE_CONFIG,
                                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(),
                            )
                        post("/friend-journey/create-room") {
                            val room = conferenceService(call).createRoom(ConferenceRoomInput(title = "Friend-Journey-Raum"))
                            call.respondText(room.id)
                        }
                        post("/friend-journey/set-guest-access/{roomId}/{allow}") {
                            val room =
                                conferenceService(call).setRoomGuestAccess(
                                    roomId = call.parameters["roomId"]!!,
                                    allowFederationGuests = call.parameters["allow"]!!.toBoolean(),
                                )
                            call.respondText(room.allowFederationGuests.toString())
                        }
                        get("/friend-journey/guest-join-info/{roomId}") {
                            val info = conferenceService(call).getGuestJoinInfo(call.parameters["roomId"]!!)
                            call.respondText("${info.allowsFederationGuests}|${info.callerIsNonMember}")
                        }
                        post("/friend-journey/join-room/{roomId}") {
                            val consent =
                                ConferenceGuestConsentAcknowledgmentInput(
                                    consentVersion = ConferenceGuestConsentDisclaimer.VERSION,
                                    consentSha256 = ConferenceGuestConsentDisclaimer.SHA256,
                                )
                            val token = conferenceService(call).joinRoom(roomId = call.parameters["roomId"]!!, guestConsent = consent)
                            call.respondText(token.identity)
                        }
                        get("/friend-journey/list-participants/{roomId}") {
                            val list = conferenceService(call).listParticipants(call.parameters["roomId"]!!)
                            call.respondText(list.joinToString(";") { "${it.memberId}|${it.homeserverUrl ?: "-"}" })
                        }

                        get("/friend-journey/my-balance") {
                            val dto = LtrLedgerService(call = call).getMyBalance()
                            call.respondText(dto.freeBalanceLtr.toString())
                        }
                    }
                }

                // ── Step 1: friend self-registration + real login ────────────────────────
                val email = "friend-journey-${Uuid.random()}@example.org"
                client.post("/friend-journey/register?email=$email&displayName=Friend-Journey-Teilnehmer").status shouldBe
                    HttpStatusCode.OK
                val friendId =
                    transaction { MemberTable.selectAll().where { MemberTable.email eq email }.single()[MemberTable.id] }
                createdMemberIds += friendId
                transaction { MemberTable.selectAll().where { MemberTable.id eq friendId }.single()[MemberTable.status] } shouldBe
                    MemberStatus.FRIEND

                val friendSessionToken = client.realLogin(email = email, password = E2E_STRONG_PASSWORD)

                // ── Step 2: an ACTIVE moderator creates a room and enables allowFederationGuests ──
                val roomId = client.post("/friend-journey/create-room") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                createdRoomIds += Uuid.parse(roomId)
                client.post("/friend-journey/set-guest-access/$roomId/true") { header("X-Member-Id", MEMBER_ID) }.status shouldBe
                    HttpStatusCode.OK

                // ── Step 3: the friend fetches getGuestJoinInfo, acknowledges consent, joins ──────
                val guestInfo =
                    client.get("/friend-journey/guest-join-info/$roomId") { withSession(friendSessionToken) }.bodyAsText()
                guestInfo shouldBe "true|true" // allowsFederationGuests=true, callerIsNonMember=true

                val joinResponse = client.post("/friend-journey/join-room/$roomId") { withSession(friendSessionToken) }
                joinResponse.status shouldBe HttpStatusCode.OK

                val rosterAfterJoin =
                    client.get("/friend-journey/list-participants/$roomId") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val friendRow = rosterAfterJoin.split(";").single { it.startsWith(friendId.toString()) }
                friendRow.substringAfter("|") shouldBe "-" // no homeserverUrl -- a FRIEND has no federated home server

                // ── Step 4: the moderator revokes guest access -> friend disconnected/further probes 403 ──
                client.post("/friend-journey/set-guest-access/$roomId/false") { header("X-Member-Id", MEMBER_ID) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/friend-journey/list-participants/$roomId") { withSession(friendSessionToken) }.status shouldBe
                    HttpStatusCode.Forbidden

                // ── Step 5: the friend calls applyForMembership -> FRIEND becomes APPLICATION ──────
                val applyResponse = client.post("/friend-journey/apply-for-membership") { withSession(friendSessionToken) }
                applyResponse.status shouldBe HttpStatusCode.OK
                applyResponse.bodyAsText() shouldBe MemberStatus.APPLICATION.name

                // Balance is still refused -- APPLICATION is not ORGANIZATION_MEMBER either.
                client.get("/friend-journey/my-balance") { withSession(friendSessionToken) }.status shouldBe HttpStatusCode.Forbidden

                // ── Step 6: BOARD approves -> ACTIVE ────────────────────────────────────────────
                val approveResponse = client.post("/friend-journey/approve/$friendId") { header("X-Member-Id", BOARD_ID) }
                approveResponse.status shouldBe HttpStatusCode.OK
                approveResponse.bodyAsText() shouldBe MemberStatus.ACTIVE.name

                // ── Step 7: the now-ACTIVE member gains a right it lacked as FRIEND ─────────────
                val balanceResponse = client.get("/friend-journey/my-balance") { withSession(friendSessionToken) }
                balanceResponse.status shouldBe HttpStatusCode.OK
            }
        }
    })
