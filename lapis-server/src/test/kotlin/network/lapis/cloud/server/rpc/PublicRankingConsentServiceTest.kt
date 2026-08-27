package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- covers [IDsgvoService.getPublicRankingConsents]/
 * [IDsgvoService.getPublicRankingConsentDisclaimer]/[IDsgvoService.grantPublicRankingConsent]/
 * [IDsgvoService.revokePublicRankingConsent] end to end via [DsgvoService], same house style
 * [DsgvoServiceTest] establishes (throwaway routes calling the service class directly).
 */
class PublicRankingConsentServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            if (createdMemberIds.isNotEmpty()) {
                transaction {
                    PublicRankingConsentEventTable.deleteWhere { memberId inList createdMemberIds }
                    AccountTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
            // MEMBER_ID (dev-seeded, ACTIVE) also writes consent rows in several tests below --
            // clean those up too, without touching the seeded member row itself.
            transaction {
                PublicRankingConsentEventTable.deleteWhere { memberId eq Uuid.parse(MEMBER_ID) }
            }
        }

        fun createNonMember(status: MemberStatus): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Test Nichtmitglied"
                    it[email] = "public-ranking-nonmember-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2027, 1, 1)
                }
                // resolveCurrentMember joins member + account -- without an account row the
                // X-Member-Id header resolves to UnauthenticatedException, not the status-based
                // ForbiddenException this helper's callers actually want to exercise.
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun currentRowCount(
            memberId: Uuid,
            kind: PublicRankingKind,
        ): Long =
            transaction {
                PublicRankingConsentEventTable
                    .selectAll()
                    .where {
                        (PublicRankingConsentEventTable.memberId eq memberId) and
                            (PublicRankingConsentEventTable.rankingKind eq kind) and
                            PublicRankingConsentEventTable.supersededAt.isNull()
                    }.count()
            }

        fun totalRowCount(
            memberId: Uuid,
            kind: PublicRankingKind,
        ): Long =
            transaction {
                PublicRankingConsentEventTable
                    .selectAll()
                    .where {
                        (PublicRankingConsentEventTable.memberId eq memberId) and
                            (PublicRankingConsentEventTable.rankingKind eq kind)
                    }.count()
            }

        test("disclaimer: two entries per keyPoints, verbatim in text, distinct per kind") {
            testApplication {
                application {
                    routing {
                        get("/test/disclaimer/{kind}") {
                            val kind = PublicRankingKind.valueOf(call.parameters["kind"]!!)
                            val service = DsgvoService(call = call)
                            val dto = service.getPublicRankingConsentDisclaimer(kind)
                            call.respondText(
                                listOf(dto.version, dto.keyPoints.size.toString(), dto.sha256, dto.text).joinToString("|"),
                            )
                        }
                    }
                }
                val ltr =
                    client.get("/test/disclaimer/LTR_HOLDINGS") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split("|")
                val donations =
                    client.get("/test/disclaimer/DONATIONS") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split("|")
                ltr[1] shouldBe "2"
                donations[1] shouldBe "2"
                (ltr[2] == donations[2]) shouldBe false
                (ltr[3] == donations[3]) shouldBe false
            }
        }

        test("grant then revoke: effective toggles, independent per kind, append-only, exactly one current row") {
            testApplication {
                application {
                    routing {
                        get("/test/disclaimer/{kind}") {
                            val kind = PublicRankingKind.valueOf(call.parameters["kind"]!!)
                            val dto = DsgvoService(call = call).getPublicRankingConsentDisclaimer(kind)
                            call.respondText("${dto.version}|${dto.sha256}")
                        }
                        post("/test/grant/{kind}") {
                            val kind = PublicRankingKind.valueOf(call.parameters["kind"]!!)
                            val version = call.request.queryParameters["v"]!!
                            val sha256 = call.request.queryParameters["h"]!!
                            val dto =
                                DsgvoService(call = call).grantPublicRankingConsent(kind = kind, version = version, sha256 = sha256)
                            call.respondText(dto.effective.toString())
                        }
                        post("/test/revoke/{kind}") {
                            val kind = PublicRankingKind.valueOf(call.parameters["kind"]!!)
                            val dto = DsgvoService(call = call).revokePublicRankingConsent(kind)
                            call.respondText(dto.effective.toString())
                        }
                        get("/test/consents") {
                            val dtos = DsgvoService(call = call).getPublicRankingConsents()
                            call.respondText(dtos.joinToString(";") { "${it.kind}=${it.effective}" })
                        }
                    }
                }
                val (version, sha) =
                    client
                        .get(
                            "/test/disclaimer/LTR_HOLDINGS",
                        ) { header("X-Member-Id", MEMBER_ID) }
                        .bodyAsText()
                        .split("|")

                // No opt-in yet -- both kinds are not effective.
                val before = client.get("/test/consents") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                before shouldBe "LTR_HOLDINGS=false;DONATIONS=false"

                val granted =
                    client.post("/test/grant/LTR_HOLDINGS?v=$version&h=$sha") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                granted shouldBe "true"

                // DONATIONS is untouched by granting LTR_HOLDINGS -- independently revocable (D9).
                val afterLtrGrant = client.get("/test/consents") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                afterLtrGrant shouldBe "LTR_HOLDINGS=true;DONATIONS=false"

                totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 1L
                currentRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 1L

                val revoked = client.post("/test/revoke/LTR_HOLDINGS") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                revoked shouldBe "false"

                val afterRevoke = client.get("/test/consents") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                afterRevoke shouldBe "LTR_HOLDINGS=false;DONATIONS=false"

                // Append-only: grant + revoke left TWO rows, neither physically deleted, exactly
                // ONE of them (the revoke) is the current (non-superseded) row.
                totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 2L
                currentRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 1L
                transaction {
                    PublicRankingConsentEventTable
                        .selectAll()
                        .where {
                            (PublicRankingConsentEventTable.memberId eq Uuid.parse(MEMBER_ID)) and
                                (PublicRankingConsentEventTable.rankingKind eq PublicRankingKind.LTR_HOLDINGS) and
                                PublicRankingConsentEventTable.supersededAt.isNull()
                        }.single()[PublicRankingConsentEventTable.eventType] shouldBe PublicRankingConsentEventType.REVOKED
                }

                // Revoke without any prior grant is a silent, idempotent no-op -- no new row.
                val rowsBeforeNoOp = totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.DONATIONS)
                client.post("/test/revoke/DONATIONS") { header("X-Member-Id", MEMBER_ID) }
                totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.DONATIONS) shouldBe rowsBeforeNoOp

                // Grant again -- append-only means a THIRD row now exists for LTR_HOLDINGS, still
                // exactly one current row.
                client.post("/test/grant/LTR_HOLDINGS?v=$version&h=$sha") { header("X-Member-Id", MEMBER_ID) }
                totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 3L
                currentRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 1L
            }
        }

        test(
            "grant: repeating an already-EFFECTIVE grant under the SAME version writes no new row " +
                "(Security-Fix: row-level idempotency)",
        ) {
            testApplication {
                application {
                    routing {
                        get("/test/disclaimer/{kind}") {
                            val kind = PublicRankingKind.valueOf(call.parameters["kind"]!!)
                            val dto = DsgvoService(call = call).getPublicRankingConsentDisclaimer(kind)
                            call.respondText("${dto.version}|${dto.sha256}")
                        }
                        post("/test/grant/{kind}") {
                            val kind = PublicRankingKind.valueOf(call.parameters["kind"]!!)
                            val version = call.request.queryParameters["v"]!!
                            val sha256 = call.request.queryParameters["h"]!!
                            val dto =
                                DsgvoService(call = call).grantPublicRankingConsent(kind = kind, version = version, sha256 = sha256)
                            call.respondText(dto.effective.toString())
                        }
                    }
                }
                val memberId = createNonMember(MemberStatus.ACTIVE)
                val (version, sha) =
                    client
                        .get("/test/disclaimer/DONATIONS") { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")

                client.post("/test/grant/DONATIONS?v=$version&h=$sha") { header("X-Member-Id", memberId.toString()) }
                totalRowCount(memberId = memberId, kind = PublicRankingKind.DONATIONS) shouldBe 1L

                // Repeated grants under the SAME version, already effective -- no new rows at all.
                repeat(5) {
                    val dto =
                        client.post("/test/grant/DONATIONS?v=$version&h=$sha") { header("X-Member-Id", memberId.toString()) }
                    dto.bodyAsText() shouldBe "true"
                }
                totalRowCount(memberId = memberId, kind = PublicRankingKind.DONATIONS) shouldBe 1L
                currentRowCount(memberId = memberId, kind = PublicRankingKind.DONATIONS) shouldBe 1L
            }
        }

        test(
            "a GRANTED row under a STALE consentVersion is NOT effective -- supersededByNewVersion is true " +
                "(D-invariant: wording change invalidates old grants)",
        ) {
            testApplication {
                application {
                    routing {
                        get("/test/consents") {
                            val dtos = DsgvoService(call = call).getPublicRankingConsents()
                            call.respondText(
                                dtos.joinToString(";") { "${it.kind}=${it.effective}=${it.supersededByNewVersion}" },
                            )
                        }
                    }
                }
                val staleMemberId = createNonMember(MemberStatus.ACTIVE)
                transaction {
                    PublicRankingConsentEventTable.insert {
                        it[id] = Uuid.random()
                        it[PublicRankingConsentEventTable.memberId] = staleMemberId
                        it[rankingKind] = PublicRankingKind.LTR_HOLDINGS
                        it[eventType] = PublicRankingConsentEventType.GRANTED
                        it[occurredAt] = DbClock.nowLocalDateTime()
                        it[supersededAt] = null
                        // A version that can never equal PublicRankingConsentDisclaimer.of(...).version --
                        // simulates a member who consented under a wording that has since changed.
                        it[consentVersion] = "stale-superseded-version.v0"
                        it[consentSha256] = "0".repeat(64)
                    }
                }

                val body = client.get("/test/consents") { header("X-Member-Id", staleMemberId.toString()) }.bodyAsText()
                body shouldBe "LTR_HOLDINGS=false=true;DONATIONS=false=false"
            }
        }

        test(
            "grant: two concurrent grant calls for the same member+kind, SAME version, leave exactly one row " +
                "total (row-lock discipline serializes them, then Security-Fix row-level idempotency skips the second write)",
        ) {
            testApplication {
                application {
                    routing {
                        get("/test/disclaimer") {
                            val dto = DsgvoService(call = call).getPublicRankingConsentDisclaimer(PublicRankingKind.LTR_HOLDINGS)
                            call.respondText("${dto.version}|${dto.sha256}")
                        }
                        post("/test/grant") {
                            val version = call.request.queryParameters["v"]!!
                            val sha256 = call.request.queryParameters["h"]!!
                            DsgvoService(call = call).grantPublicRankingConsent(
                                kind = PublicRankingKind.LTR_HOLDINGS,
                                version = version,
                                sha256 = sha256,
                            )
                            call.respondText("ok")
                        }
                    }
                }
                val concurrentMemberId = createNonMember(MemberStatus.ACTIVE)
                val (version, sha) =
                    client
                        .get("/test/disclaimer") { header("X-Member-Id", concurrentMemberId.toString()) }
                        .bodyAsText()
                        .split("|")

                runConcurrentGrants(client = client, memberId = concurrentMemberId, version = version, sha256 = sha)

                currentRowCount(memberId = concurrentMemberId, kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 1L
                // Row-lock discipline (SELECT ... FOR UPDATE) serializes the two calls against each
                // other: whichever runs first observes "not yet granted" and writes the one row;
                // the second then observes an EFFECTIVE grant under the SAME version and -- per the
                // Security-Fix row-level idempotency in PublicRankingConsentStore.grant -- returns
                // early WITHOUT writing a second, redundant row. Exactly one row total, not two.
                totalRowCount(memberId = concurrentMemberId, kind = PublicRankingKind.LTR_HOLDINGS) shouldBe 1L
            }
        }

        test("grant with a version/hash that does not match the current disclaimer is rejected, writes nothing") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing {
                        post("/test/grant-bad") {
                            DsgvoService(call = call).grantPublicRankingConsent(
                                kind = PublicRankingKind.LTR_HOLDINGS,
                                version = "wrong-version",
                                sha256 = "0".repeat(64),
                            )
                            call.respondText("ok")
                        }
                    }
                }
                val before = totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS)
                val response = client.post("/test/grant-bad") { header("X-Member-Id", MEMBER_ID) }
                response.status shouldBe HttpStatusCode.BadRequest
                totalRowCount(memberId = Uuid.parse(MEMBER_ID), kind = PublicRankingKind.LTR_HOLDINGS) shouldBe before
            }
        }

        test("GUEST and FRIEND cannot grant -- ForbiddenException, unauthenticated is rejected too") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing {
                        post("/test/grant") {
                            val version = call.request.queryParameters["v"]!!
                            val sha256 = call.request.queryParameters["h"]!!
                            DsgvoService(call = call).grantPublicRankingConsent(
                                kind = PublicRankingKind.LTR_HOLDINGS,
                                version = version,
                                sha256 = sha256,
                            )
                            call.respondText("ok")
                        }
                        get("/test/disclaimer") {
                            val dto = DsgvoService(call = call).getPublicRankingConsentDisclaimer(PublicRankingKind.LTR_HOLDINGS)
                            call.respondText("${dto.version}|${dto.sha256}")
                        }
                    }
                }
                val (version, sha) = client.get("/test/disclaimer") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split("|")

                val guestId = createNonMember(MemberStatus.GUEST)
                val guestResponse = client.post("/test/grant?v=$version&h=$sha") { header("X-Member-Id", guestId.toString()) }
                guestResponse.status shouldBe HttpStatusCode.Forbidden

                val friendId = createNonMember(MemberStatus.FRIEND)
                val friendResponse = client.post("/test/grant?v=$version&h=$sha") { header("X-Member-Id", friendId.toString()) }
                friendResponse.status shouldBe HttpStatusCode.Forbidden

                val unauthResponse = client.post("/test/grant?v=$version&h=$sha")
                unauthResponse.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("consent write rate limit: exceeding the budget is rejected with ConflictException") {
            // Hoisted OUTSIDE the route handler -- a fresh limiter per request would never
            // accumulate a count, defeating the whole point of this test.
            // Security-Fix (Review): revoke now runs against its OWN limiter
            // (consentRevokeRateLimiter), never consentRateLimiter -- see DsgvoService's field KDoc.
            val limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing {
                        post("/test/revoke") {
                            DsgvoService(
                                call = call,
                                consentRevokeRateLimiter = limiter,
                            ).revokePublicRankingConsent(PublicRankingKind.DONATIONS)
                            call.respondText("ok")
                        }
                    }
                }
                val rateLimitedId = createNonMember(MemberStatus.ACTIVE)
                repeat(2) { client.post("/test/revoke") { header("X-Member-Id", rateLimitedId.toString()) } }
                val third = client.post("/test/revoke") { header("X-Member-Id", rateLimitedId.toString()) }
                third.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("consent grant and revoke rate limits are independent budgets (Art. 7(3) DSGVO fix)") {
            // A member who exhausts the GRANT budget must still be able to revoke immediately --
            // see DsgvoService.consentRevokeRateLimiter KDoc. Both limiters hoisted OUTSIDE the
            // route handlers for the same reason as the test above.
            val grantLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)
            val revokeLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing {
                        post("/test/grant") {
                            val v = call.request.queryParameters["v"]!!
                            val h = call.request.queryParameters["h"]!!
                            DsgvoService(call = call, consentRateLimiter = grantLimiter, consentRevokeRateLimiter = revokeLimiter)
                                .grantPublicRankingConsent(kind = PublicRankingKind.DONATIONS, version = v, sha256 = h)
                            call.respondText("ok")
                        }
                        post("/test/revoke") {
                            DsgvoService(call = call, consentRateLimiter = grantLimiter, consentRevokeRateLimiter = revokeLimiter)
                                .revokePublicRankingConsent(PublicRankingKind.DONATIONS)
                            call.respondText("ok")
                        }
                        get("/test/disclaimer") {
                            val d = DsgvoService(call = call).getPublicRankingConsentDisclaimer(PublicRankingKind.DONATIONS)
                            call.respondText("${d.version}|${d.sha256}")
                        }
                    }
                }
                val memberId = createNonMember(MemberStatus.ACTIVE)
                val (version, sha) =
                    client
                        .get("/test/disclaimer") { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")

                // Exhaust the GRANT budget (maxRequests = 1).
                client.post("/test/grant?v=$version&h=$sha") { header("X-Member-Id", memberId.toString()) }
                val secondGrant = client.post("/test/grant?v=$version&h=$sha") { header("X-Member-Id", memberId.toString()) }
                secondGrant.status shouldBe HttpStatusCode.Conflict

                // The revoke budget is untouched -- must still succeed.
                val revoke = client.post("/test/revoke") { header("X-Member-Id", memberId.toString()) }
                revoke.status shouldBe HttpStatusCode.OK
            }
        }
    })

/**
 * Fires two concurrent `grant` calls for the SAME [memberId]+`LTR_HOLDINGS` from two independent OS
 * threads, synchronized via [CountDownLatch] so both are issued as close to simultaneously as
 * possible -- same [network.lapis.cloud.server.rpc.PeerTransferServiceTest]
 * `runConcurrentOppositeTransfers` recipe (real thread-level parallelism, not two coroutines
 * cooperatively sharing one thread), exercising [PublicRankingConsentStore]'s `MemberTable` row-lock
 * discipline (class KDoc "Concurrency") instead of [PeerTransferService]'s two-account lock order.
 * Both threads must complete within [timeoutSeconds]; exceeding it fails the test with an explicit
 * deadlock diagnosis rather than hanging the whole suite.
 */
private fun runConcurrentGrants(
    client: HttpClient,
    memberId: Uuid,
    version: String,
    sha256: String,
    timeoutSeconds: Long = 20,
) {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val failures = mutableListOf<Throwable>()

    fun grantThread(): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post("/test/grant?v=$version&h=$sha256") { header("X-Member-Id", memberId.toString()) }
                    check(response.status == HttpStatusCode.OK) { "Unexpected status ${response.status}: ${response.bodyAsText()}" }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val threadA = grantThread()
    val threadB = grantThread()
    threadA.start()
    threadB.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent grant calls did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
}

private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"
