package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.rpc.MemberService
import network.lapis.cloud.server.rpc.RegionalChapterEnforcementConfig
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val PINNED = "2026-10-16T10:00:00Z"
private val ADMIN_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * V1.9.144 -- what ends when a consent ends: an own opt-out, a change away from ACTIVE (through the real `MemberService.updateMemberStatus` and
 * `RegistrationService.leaveMembership`), and the addressee's side of a request, which is detached (not deleted) so the sender sees no change.
 */
class MemberFinderLifecycleTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

        fun revokeReasons(member: Uuid) =
            transaction {
                MemberFinderConsentEventTable
                    .selectAll()
                    .where { MemberFinderConsentEventTable.memberId eq member }
                    .mapNotNull { it[MemberFinderConsentEventTable.revokeReason] }
            }

        test(
            "an opt-out revokes, removes own requests and accepted connections, detaches received requests and removes only the own blocks",
        ) {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                val a = six[0]
                val b = six[1]
                val c = six[2]
                val d = six[3]
                val e = six[4]
                val f = six[5]
                val ownOut = data.request(from = a, to = b)
                val incoming = data.request(from = c, to = a, note = "Hallo A")
                data.request(from = d, to = a, status = MemberFinderRequestStatus.ACCEPTED)
                transaction {
                    MemberFinderBlockStore.block(blockerMemberId = a, blockedMemberId = e, now = ServerClock.now())
                    MemberFinderBlockStore.block(blockerMemberId = f, blockedMemberId = a, now = ServerClock.now())
                }
                data.computeBands()
                finderTestApplication {
                    call(member = a) { optOut() }.optedIn shouldBe false
                    revokeReasons(a) shouldBe listOf(MemberFinderRevokeReason.MEMBER)
                    val rows = data.requests()
                    rows.none { it.id == ownOut } shouldBe true
                    rows.none { it.fromMemberId == d } shouldBe true
                    // the received request stays as a phantom without counterpart and without the note
                    val detached = rows.single { it.id == incoming }
                    detached.toMemberId.shouldBeNull()
                    detached.note.shouldBeNull()
                    detached.status shouldBe MemberFinderRequestStatus.OPEN
                    // the sender of it sees no change at all
                    call(member = c) { listOutgoingRequests() }.single().state shouldBe MemberFinderOutgoingState.PENDING
                    // only the own block went; the block set against the leaver stays
                    data.blockCount() shouldBe 1
                    // a second opt-out writes nothing
                    val events = data.eventCount(a)
                    call(member = a) { optOut() }
                    data.eventCount(a) shouldBe events
                }
            }
        }

        test("a status change away from ACTIVE ends the consent the same way (STATUS_LOST) and a reactivation does not bring it back") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                val leaver = six[0]
                data.request(from = leaver, to = six[1])
                data.request(from = six[2], to = leaver)
                data.computeBands()
                testApplication {
                    application(memberServiceRoute())
                    for (target in listOf(MemberStatus.WITHDRAWN, MemberStatus.DONOR)) {
                        val m = data.member(label = "status-$target", postalCode = "38700").also { data.consent(memberId = it) }
                        data.request(from = m, to = six[3])
                        client
                            .post("/test/status/$m?status=${target.name}") { header("X-Member-Id", ADMIN_ID.toString()) }
                            .status shouldBe HttpStatusCode.OK
                        revokeReasons(m) shouldBe listOf(MemberFinderRevokeReason.STATUS_LOST)
                        data.requests().none { it.fromMemberId == m } shouldBe true
                        // back to ACTIVE: no consent, nothing in the participants
                        client
                            .post("/test/status/$m?status=ACTIVE") { header("X-Member-Id", ADMIN_ID.toString()) }
                            .status shouldBe HttpStatusCode.OK
                        transaction {
                            MemberFinderConsentStore.openGrant(m)
                        }.shouldBeNull()
                    }
                }
            }
        }

        test("leaving the membership through the self-service path ends the consent") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val m = data.member(label = "leaver", postalCode = "38700").also { data.consent(memberId = it) }
                data.request(from = m, to = null)
                testApplication {
                    application(memberServiceRoute())
                    client.post("/test/leave") { header("X-Member-Id", m.toString()) }.status shouldBe HttpStatusCode.OK
                    revokeReasons(m) shouldBe listOf(MemberFinderRevokeReason.STATUS_LOST)
                    data.requests().none { it.fromMemberId == m } shouldBe true
                }
            }
        }

        test(
            "a request is refused (NotFound) and marked EXPIRED when the sender moved to another region; the same when the addressee did",
        ) {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val six = data.region(count = 6, label = "six")
                data.computeBands()
                val sender = six[0]
                val x = six[1]
                val moved = data.request(from = sender, to = x)
                val stays = data.request(from = six[2], to = six[3])
                transaction { MemberTable.update({ MemberTable.id eq sender }) { it[postalCode] = "10115" } }
                finderTestApplication {
                    attempt(member = x) {
                        respondToRequest(requestId = moved.toString(), accept = true, block = false)
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    data.requests().single { it.id == moved }.status shouldBe MemberFinderRequestStatus.EXPIRED
                    // the control: an untouched pair is answered normally
                    call(member = six[3]) { respondToRequest(requestId = stays.toString(), accept = true, block = false) }
                    data.requests().single { it.id == stays }.status shouldBe MemberFinderRequestStatus.ACCEPTED
                    // the moved person does not see incoming requests of the old region either
                    call(member = x) { listIncomingRequests() }.shouldBeEmpty()
                }
            }
        }

        test("a consent of a member who is not ACTIVE any more does not count, even before any cleanup ran (live filter)") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                val five = data.region(count = 5, label = "five")
                data.setStatus(memberId = five[0], status = MemberStatus.WITHDRAWN)
                data.computeBands()
                data.bandRows() shouldBe 0
            }
        }
    })

private fun memberServiceRoute(): Application.() -> Unit =
    {
        install(StatusPages) {
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
        }
        routing {
            post("/test/status/{id}") {
                val service =
                    MemberService(
                        call = call,
                        passwordResetMailer = FakePasswordResetMailer(),
                        adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                        smtpConfigState = SmtpConfigState.NotConfigured,
                        adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                        adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                        adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                        memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                        memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                        regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
                    )
                val dto =
                    service.updateMemberStatus(
                        memberId = call.parameters["id"]!!,
                        newStatus = MemberStatus.valueOf(call.request.queryParameters["status"]!!),
                        reason = "Testgrund fuer Statuswechsel",
                        dateOfDeath = null,
                    )
                call.respondText(dto.status.name)
            }
            post("/test/leave") {
                val service =
                    RegistrationService(
                        call = call,
                        registrationRateLimiter = LoginRateLimiter(),
                        friendRegistrationRateLimiter = LoginRateLimiter(),
                        friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                        friendVerificationMailer = FakeFriendVerificationMailer(),
                        keycloakConfig = KeycloakConfig.load(env = { null }),
                        regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
                    )
                call.respondText(service.leaveMembership().status.name)
            }
        }
    }
