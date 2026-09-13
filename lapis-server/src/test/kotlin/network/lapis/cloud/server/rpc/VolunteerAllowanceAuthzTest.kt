package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- role gates, IDOR, and the four-eyes
 * principle over BOTH paths (subject and requester) for [VolunteerAllowanceService], on BOTH
 * [VolunteerAllowanceService.decidePayment] and [VolunteerAllowanceService.retryPosting] (the
 * V1.4.11 security finding this codebase already documents). Mirrors [TravelExpenseAuthzTest]'s
 * house style.
 */
class VolunteerAllowanceAuthzTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                val paymentIds =
                    VolunteerAllowancePaymentTable
                        .selectAll()
                        .where {
                            (VolunteerAllowancePaymentTable.subjectMemberId inList createdMemberIds) or
                                (VolunteerAllowancePaymentTable.requestedBy inList createdMemberIds)
                        }.map { it[VolunteerAllowancePaymentTable.id] }
                VolunteerAllowancePaymentTable.deleteWhere { VolunteerAllowancePaymentTable.id inList paymentIds }
                VolunteerAllowanceSelfDeclarationTable.deleteWhere {
                    VolunteerAllowanceSelfDeclarationTable.memberId inList createdMemberIds
                }
                AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(status: MemberStatus = MemberStatus.ACTIVE): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Authz Testmitglied"
                    it[email] = "volunteer-authz-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        suspend fun HttpClient.createAndSubmit(
            subjectId: Uuid,
            actorHeader: String,
        ): String {
            val draftId =
                post(
                    "/test/volunteerallowance/create?subjectMemberId=$subjectId&category=HONORARY&amount=50.00" +
                        "&description=Vereinshelfer&date=2026-06-01",
                ) { header("X-Member-Id", actorHeader) }.bodyAsText().split("|")[0]
            post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", actorHeader) }
            return draftId
        }

        // ── Role gates ─────────────────────────────────────────────────────────────

        test("MEMBER may create/submit for themselves but not listPayments/decidePayment/retryPosting") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val memberId = newMember()
                val paymentId = client.createAndSubmit(memberId, memberId.toString())

                client.get("/test/volunteerallowance/list") { header("X-Member-Id", memberId.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post("/test/volunteerallowance/decide?id=$paymentId&approve=false&note=x") {
                        header("X-Member-Id", memberId.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", memberId.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("TREASURER may neither listPayments nor decidePayment nor retryPosting -- a discretionary judgment call, not accounting") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val memberId = newMember()
                val paymentId = client.createAndSubmit(memberId, memberId.toString())

                client.get("/test/volunteerallowance/list") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post("/test/volunteerallowance/decide?id=$paymentId&approve=false&note=x") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD and ADMIN may both list") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                client.get("/test/volunteerallowance/list") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.get("/test/volunteerallowance/list") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("recordPaperDeclaration: BOARD/ADMIN only, MEMBER -> 403") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val memberId = newMember()
                val victim = newMember()
                client
                    .post(
                        "/test/volunteerallowance/record-paper-declaration?memberId=$victim&category=HONORARY&year=2026" +
                            "&signedOn=2026-01-10",
                    ) { header("X-Member-Id", memberId.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                client
                    .post(
                        "/test/volunteerallowance/record-paper-declaration?memberId=$victim&category=HONORARY&year=2026" +
                            "&signedOn=2026-01-10",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("recordPaperDeclaration with memberId == current.memberId (a board member declaring 'on paper' for themselves) is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                client
                    .post(
                        "/test/volunteerallowance/record-paper-declaration?memberId=$BOARD_ID&category=HONORARY&year=2027" +
                            "&signedOn=2026-01-10",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("declareSelf always records the CALLER as both memberId and recordedBy -- there is no way to declare for someone else") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val memberId = newMember()
                val dto =
                    client
                        .post("/test/volunteerallowance/declare-self?category=HONORARY&year=2028") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")
                dto[1] shouldBe memberId.toString() // memberId
                dto[6] shouldNotBe "" // recordedByDisplayName is populated (== the caller)
            }
        }

        // ── IDOR ───────────────────────────────────────────────────────────────────

        test("a MEMBER creating a draft for a FOREIGN member -> ForbiddenException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val actor = newMember()
                val victim = newMember()
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$victim&category=HONORARY&amount=50.00" +
                            "&description=Vereinshelfer&date=2026-06-01",
                    ) { header("X-Member-Id", actor.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD creating a draft in the name of a foreign member is ALLOWED, requestedBy differs from subject") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val dto =
                    client
                        .post(
                            "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                                "&description=Vereinshelfer&date=2026-06-01",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                dto[1] shouldBe subject.toString() // subjectMemberId
                dto[7] shouldBe BOARD_ID // requestedBy
            }
        }

        test("withdrawPayment by ADMIN on a fremd payment -> ForbiddenException (no privileged bypass)") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val memberId = newMember()
                val draftId =
                    client
                        .post(
                            "/test/volunteerallowance/create?subjectMemberId=$memberId&category=HONORARY&amount=50.00" +
                                "&description=Vereinshelfer&date=2026-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/volunteerallowance/withdraw?id=$draftId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        // ── Four-eyes, both paths, both methods ─────────────────────────────────────

        test("decidePayment Weg 1: a BOARD member cannot decide their own request (subjectMemberId == decider)") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val boardMemberId = Uuid.parse(BOARD_ID)
                val paymentId = client.createAndSubmit(boardMemberId, BOARD_ID)

                client
                    .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Selbstgenehmigung") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.Forbidden

                transaction {
                    VolunteerAllowancePaymentTable
                        .selectAll()
                        .where { VolunteerAllowancePaymentTable.id eq Uuid.parse(paymentId) }
                        .single()[VolunteerAllowancePaymentTable.status]
                } shouldBe VolunteerAllowancePaymentStatus.REQUESTED
            }
        }

        test(
            "decidePayment Weg 2: a BOARD member who submitted 'on behalf of' someone else may not decide it, a DIFFERENT board member may",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val paymentId = client.createAndSubmit(subject, BOARD_ID)

                client
                    .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/volunteerallowance/decide?id=$paymentId&approve=false&note=Abgelehnt") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "the V1.4.11 fix mirrored here: retryPosting enforces the SAME four-eyes exclusion as decidePayment -- " +
                "a BOARD member cannot retry-post their OWN approved-but-failed booking",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val boardMemberId = Uuid.parse(BOARD_ID)
                val paymentId = client.createAndSubmit(boardMemberId, BOARD_ID)

                // A DIFFERENT board member (ADMIN) approves it -- no self-declaration exists for this
                // subject/category/year in this test suite, so decidePayment lands on APPROVED with
                // executionError=self_declaration_missing, exactly the precondition retryPosting requires.
                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[13] shouldNotBe "-" // executionError set

                // The BOARD member who is the SUBJECT of their own payment may not retry-post it.
                client.post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden

                // A different privileged member may retry it (still fails for the same missing-
                // declaration reason -- retryPosting responds 200 either way, only the four-eyes gate
                // above is under test here).
                client.post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test(
            "retryPosting Weg 2: a BOARD member who submitted 'on behalf of' the subject may not retry-post it either",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val paymentId = client.createAndSubmit(subject, BOARD_ID)

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name

                client.post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK
            }
        }
    })
