package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.NoOpFriendVerificationMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val STRONG_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Exercises [RegistrationService.registerFriend]/[RegistrationService.getFriendTerms] end to end,
 * mirroring [RegistrationServiceTest]'s own house style (throwaway routes calling the service
 * class directly). [afterSpec] hard-deletes every row this file created.
 */
class FriendRegistrationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpFriendRegistrationTestData(createdMemberIds) }

        fun findMemberIdByEmail(email: String): Uuid? =
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.email eq email }
                    .singleOrNull()
                    ?.get(MemberTable.id)
            }

        fun statusOf(memberId: Uuid): MemberStatus =
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]
            }

        test("getFriendTerms is reachable without any authentication") {
            testApplication {
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes() }
                }
                val response = client.get("/test/friend-terms")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().contains(":") shouldBe true
            }
        }

        test("registerFriend: happy path creates a FRIEND member+account+termsAcknowledgment, friendSince set, mailer invoked") {
            testApplication {
                val recordingMailer = RecordingFriendVerificationMailer()
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes(mailer = recordingMailer) }
                }
                val email = "friend-happy@example.org"

                val response = client.post("/test/register-friend?email=$email&displayName=Neuer+Freund")
                response.status shouldBe HttpStatusCode.OK

                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId
                statusOf(memberId) shouldBe MemberStatus.FRIEND

                val friendSince =
                    transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.friendSince] }
                friendSince shouldBe
                    network.lapis.cloud.server.db.DbClock
                        .nowLocalDateTime()
                        .date

                val ackCount =
                    transaction {
                        FriendTermsAcknowledgmentTable.selectAll().where { FriendTermsAcknowledgmentTable.memberId eq memberId }.count()
                    }
                ackCount shouldBe 1L

                // Deliberately NOT MembershipAgreementAcknowledgmentTable -- a FRIEND has not
                // accepted the Satzung, see RegistrationService.registerFriend KDoc.
                val agreementAckCount =
                    transaction {
                        MembershipAgreementAcknowledgmentTable
                            .selectAll()
                            .where { MembershipAgreementAcknowledgmentTable.memberId eq memberId }
                            .count()
                    }
                agreementAckCount shouldBe 0L

                recordingMailer.sentTo shouldBe listOf(email)
            }
        }

        test("registerFriend: a stale/mismatched terms version+hash is rejected, no row created, mailer never invoked") {
            testApplication {
                val recordingMailer = RecordingFriendVerificationMailer()
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes(mailer = recordingMailer) }
                }
                val email = "friend-bad-terms@example.org"

                val response =
                    client.post("/test/register-friend?email=$email&termsVersion=stale-version&termsSha256=deadbeef")
                response.status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail(email) shouldBe null
                recordingMailer.sentTo shouldBe emptyList()
            }
        }

        test("registerFriend: a weak password is rejected by PasswordPolicy, no row created") {
            testApplication {
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes() }
                }
                val email = "friend-weak-password@example.org"

                val response = client.post("/test/register-friend?email=$email&password=short")
                response.status shouldBe HttpStatusCode.BadRequest
                findMemberIdByEmail(email) shouldBe null
            }
        }

        test("registerFriend: a blank displayName is rejected, an overlong (>200) displayName is rejected, no row created either way") {
            testApplication {
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes() }
                }
                val blankEmail = "friend-blank-name@example.org"
                client.post("/test/register-friend?email=$blankEmail&displayName=").status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail(blankEmail) shouldBe null

                val overlongEmail = "friend-overlong-name@example.org"
                client
                    .post("/test/register-friend?email=$overlongEmail&displayName=${"x".repeat(201)}")
                    .status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail(overlongEmail) shouldBe null
            }
        }

        test(
            "registerFriend: a duplicate email gets the IDENTICAL success response, no second row created (account-enumeration hardening), no verification mail sent for the duplicate",
        ) {
            testApplication {
                val recordingMailer = RecordingFriendVerificationMailer()
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes(mailer = recordingMailer) }
                }
                val email = "friend-duplicate@example.org"

                val first = client.post("/test/register-friend?email=$email")
                first.status shouldBe HttpStatusCode.OK
                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId

                val second = client.post("/test/register-friend?email=$email")
                second.status shouldBe first.status
                second.bodyAsText() shouldBe first.bodyAsText()

                val rowCount = transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() }
                rowCount shouldBe 1L
                // Exactly ONE verification mail -- never a second one for the silent duplicate no-op
                // (that would leak, via a side channel, that the email already belongs to a member).
                recordingMailer.sentTo shouldBe listOf(email)
            }
        }

        test(
            "security-audit F1: the duplicate-email no-op path pays the same bcrypt cost as the new-account path (timing side-channel closed)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes() }
                }
                val email = "friend-timing@example.org"

                // First call creates the account.
                val first = client.post("/test/register-friend?email=$email")
                first.status shouldBe HttpStatusCode.OK
                findMemberIdByEmail(email)?.let { createdMemberIds += it }

                // Second call hits the duplicate-email no-op branch. BEFORE the F1 fix this branch
                // returned after a single, sub-millisecond SELECT COUNT -- well before
                // PasswordHasher.hash (bcrypt cost 12, tens-to-hundreds of ms) was ever reached, so
                // an attacker could distinguish "email exists" from "email is new" by response
                // latency alone, without ever reading the (identical) response body. AFTER the fix,
                // the hash is computed unconditionally before the transaction starts, so this
                // duplicate path now also pays that cost.
                val start = System.nanoTime()
                val second = client.post("/test/register-friend?email=$email")
                val elapsedMillis = (System.nanoTime() - start) / 1_000_000
                second.status shouldBe HttpStatusCode.OK

                // Conservative floor: a plain SELECT COUNT on the in-memory test DB completes in
                // low single-digit milliseconds, while bcrypt at cost 12 takes tens of milliseconds
                // at an absolute minimum on any real hardware. If the F1 fix regresses (the hash
                // moves back inside the "new member" branch only), this duplicate-path call becomes
                // fast again and this assertion starts failing.
                (elapsedMillis >= 20L) shouldBe true
            }
        }

        test(
            "registerFriend: two concurrent requests with the SAME email -- both succeed identically (no 500), exactly one account created, exactly one verification mail sent (V0.13.1 race fix)",
        ) {
            testApplication {
                val recordingMailer = RecordingFriendVerificationMailer()
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes(mailer = recordingMailer) }
                }
                val email = "friend-concurrent-dup@example.org"

                val outcomes = runConcurrentDuplicateFriendRegistrations(client = client, email = email)

                // BEFORE the V0.13.1 fix, the loser of this race hit an uncaught ExposedSQLException
                // from MemberTable's UNIQUE(email) constraint and got a raw 500 instead of the
                // account-enumeration-hardening no-op every OTHER duplicate-email path already gets.
                outcomes.count { it == HttpStatusCode.OK } shouldBe 2

                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId

                val rowCount = transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() }
                rowCount shouldBe 1L
                // Exactly ONE verification mail -- the race loser's silent no-op (whether via the
                // synchronous alreadyExists pre-check or the ExposedSQLException backstop) must never
                // send a second one, same "createdMemberId != null" gate as the sequential-duplicate
                // test above.
                recordingMailer.sentTo shouldBe listOf(email)
            }
        }

        test("registerFriend: repeated attempts eventually trip the failure-window rate limiter") {
            testApplication {
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing { registerFriendRegistrationTestRoutes(friendRateLimiter = LoginRateLimiter(maxFailures = 3)) }
                }
                val statuses =
                    (1..6).map { i ->
                        client.post("/test/register-friend?email=friend-rate-$i@example.org").status
                    }
                statuses.contains(HttpStatusCode.Conflict) shouldBe true
                (1..6).forEach { i ->
                    findMemberIdByEmail("friend-rate-$i@example.org")?.let { createdMemberIds += it }
                }
            }
        }

        test("registerFriend: repeated attempts eventually trip the hard per-IP request-rate cap") {
            testApplication {
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing {
                        registerFriendRegistrationTestRoutes(
                            friendIpRateLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes),
                        )
                    }
                }
                val statuses =
                    (1..6).map { i ->
                        client.post("/test/register-friend?email=friend-ip-rate-$i@example.org").status
                    }
                statuses.contains(HttpStatusCode.Conflict) shouldBe true
                (1..6).forEach { i ->
                    findMemberIdByEmail("friend-ip-rate-$i@example.org")?.let { createdMemberIds += it }
                }
            }
        }

        test("registerFriend: the global FRIEND-account cap refuses once the configured maximum is reached") {
            testApplication {
                // The cap is computed RELATIVE to the FRIEND count already in the shared H2
                // database at this point (every Spec in this JVM run shares one in-memory DB, see
                // DatabaseConfig KDoc) -- a hard-coded absolute cap like "2" would spuriously trip
                // if any earlier-run test in the same suite already created FRIEND accounts.
                val alreadyExistingFriendCount =
                    transaction { MemberTable.selectAll().where { MemberTable.status eq MemberStatus.FRIEND }.count() }
                val cap = alreadyExistingFriendCount + 2
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing {
                        registerFriendRegistrationTestRoutes(
                            config =
                                FriendRegistrationConfig.load { key ->
                                    if (key ==
                                        "LAPIS_FRIEND_MAX_ACCOUNTS"
                                    ) {
                                        cap.toString()
                                    } else {
                                        null
                                    }
                                },
                        )
                    }
                }
                val first = client.post("/test/register-friend?email=friend-cap-1@example.org")
                first.status shouldBe HttpStatusCode.OK
                findMemberIdByEmail("friend-cap-1@example.org")?.let { createdMemberIds += it }

                val second = client.post("/test/register-friend?email=friend-cap-2@example.org")
                second.status shouldBe HttpStatusCode.OK
                findMemberIdByEmail("friend-cap-2@example.org")?.let { createdMemberIds += it }

                val third = client.post("/test/register-friend?email=friend-cap-3@example.org")
                third.status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail("friend-cap-3@example.org") shouldBe null
            }
        }

        test(
            "security-audit F2: once the cap is reached, a DUPLICATE (already-registered) email also gets Conflict -- not a silent-success oracle",
        ) {
            testApplication {
                // Same "cap relative to shared H2 DB state" reasoning as the test above.
                val alreadyExistingFriendCount =
                    transaction { MemberTable.selectAll().where { MemberTable.status eq MemberStatus.FRIEND }.count() }
                val cap = alreadyExistingFriendCount + 1
                application {
                    install(StatusPages) { installFriendRegistrationExceptionHandlers() }
                    routing {
                        registerFriendRegistrationTestRoutes(
                            config =
                                FriendRegistrationConfig.load { key ->
                                    if (key == "LAPIS_FRIEND_MAX_ACCOUNTS") cap.toString() else null
                                },
                        )
                    }
                }
                // Fill the cap with one real FRIEND account -- its email doubles as the "duplicate"
                // probed below.
                val email = "friend-cap-oracle@example.org"
                val filling = client.post("/test/register-friend?email=$email")
                filling.status shouldBe HttpStatusCode.OK
                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId

                // Cap is now reached. BEFORE the F2 fix, re-submitting this SAME (already existing)
                // email would still short-circuit through the duplicate-email no-op BEFORE the cap
                // check ran, returning the identical OK "success" response -- while a genuinely NEW
                // email would reach the cap check and get Conflict. That asymmetry was the oracle:
                // submit candidate email X once the cap is known to be full -- OK meant X already
                // existed, Conflict meant X was new. AFTER the fix, the cap check runs FIRST, so an
                // EXISTING email also gets Conflict once the cap is reached -- both outcomes
                // converge and the oracle is closed.
                val duplicateAfterCap = client.post("/test/register-friend?email=$email")
                duplicateAfterCap.status shouldBe HttpStatusCode.Conflict

                val newEmail = "friend-cap-oracle-new@example.org"
                val newEmailAfterCap = client.post("/test/register-friend?email=$newEmail")
                newEmailAfterCap.status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail(newEmail) shouldBe null

                // No second row was created for the duplicate email either way.
                val rowCount = transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() }
                rowCount shouldBe 1L
            }
        }
    })

private class RecordingFriendVerificationMailer : FriendVerificationMailer {
    val sentTo = mutableListOf<String>()

    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        sentTo += email
        return DeliveryStatus.SENT
    }
}

/**
 * Fires TWO `/test/register-friend` requests with the SAME [email] from two independent OS threads,
 * synchronized via [CountDownLatch] so both are issued as close to simultaneously as possible --
 * same shape as [RegistrationServiceTest]'s own `runConcurrentApproveAndReject`/
 * `runConcurrentDuplicateRegistrations` helpers, reused here to exercise the V0.13.1
 * concurrent-duplicate-registration race fix in [RegistrationService.registerFriend].
 */
private fun runConcurrentDuplicateFriendRegistrations(
    client: HttpClient,
    email: String,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<HttpStatusCode>()
    val failures = mutableListOf<Throwable>()

    fun requestThread(): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post("/test/register-friend?email=$email")
                    synchronized(results) { results += response.status }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val first = requestThread()
    val second = requestThread()
    first.start()
    second.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent duplicate FRIEND registrations did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun cleanUpFriendRegistrationTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable.deleteWhere {
            network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable.memberId inList memberIds
        }
        FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId inList memberIds }
        MembershipAgreementAcknowledgmentTable.deleteWhere { MembershipAgreementAcknowledgmentTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installFriendRegistrationExceptionHandlers() {
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
    exception<WeakPasswordException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

/** Shared throwaway routes for [RegistrationService.registerFriend]/[RegistrationService.getFriendTerms]. */
private fun Route.registerFriendRegistrationTestRoutes(
    friendRateLimiter: LoginRateLimiter = LoginRateLimiter(),
    friendIpRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(),
    config: FriendRegistrationConfig = FriendRegistrationConfig.load { null },
    mailer: FriendVerificationMailer = NoOpFriendVerificationMailer(),
) {
    fun registrationService(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = LoginRateLimiter(),
            friendRegistrationRateLimiter = friendRateLimiter,
            friendSignupIpRateLimiter = friendIpRateLimiter,
            friendRegistrationConfig = config,
            friendVerificationMailer = mailer,
        )
    get("/test/friend-terms") {
        val dto = registrationService(call).getFriendTerms()
        call.respondText("${dto.version}:${dto.sha256}")
    }
    post("/test/register-friend") {
        val q = call.request.queryParameters
        registrationService(call).registerFriend(
            FriendRegistrationInput(
                displayName = q["displayName"] ?: "Testfreund",
                email = q["email"]!!,
                password = q["password"] ?: STRONG_PASSWORD,
                termsVersion = q["termsVersion"] ?: FriendTermsDisclaimer.VERSION,
                termsSha256 = q["termsSha256"] ?: FriendTermsDisclaimer.SHA256,
            ),
        )
        call.respondText("OK")
    }
}
