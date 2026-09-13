package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- the state machine (every transition of
 * the [VolunteerAllowanceService] class KDoc table exercised once, every forbidden transition
 * rejected with [network.lapis.cloud.shared.rpc.ConflictException]) plus the formal-shape
 * validation in `createDraft`/`updateDraft`/`submitPayment`. Mirrors [TravelExpenseStateMachineTest]'s
 * house style. The cap/self-declaration-gated part of `decidePayment` is covered separately by
 * [VolunteerAllowanceCapTest]/[VolunteerAllowanceDeclarationTest].
 */
class VolunteerAllowancePaymentTest :
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
                    it[displayName] = "Payment Testmitglied"
                    it[email] = "volunteer-payment-$id@example.org"
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

        suspend fun HttpClient.createDraft(
            subjectId: Uuid,
            actorHeader: String = subjectId.toString(),
            category: String = "HONORARY",
            amount: String = "50.00",
            description: String = "Vereinshelfer",
            date: String = "2026-06-01",
        ): String =
            post(
                "/test/volunteerallowance/create?subjectMemberId=$subjectId&category=$category&amount=$amount" +
                    "&description=$description&date=$date",
            ) { header("X-Member-Id", actorHeader) }.bodyAsText().split("|")[0]

        // ── Happy-path transitions, each exactly once ─────────────────────────────

        test("(-) -> DRAFT via createDraft") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                val row =
                    transaction {
                        VolunteerAllowancePaymentTable
                            .selectAll()
                            .where {
                                VolunteerAllowancePaymentTable.id eq
                                    Uuid.parse(
                                        draftId,
                                    )
                            }.single()
                    }
                row[VolunteerAllowancePaymentTable.status] shouldBe VolunteerAllowancePaymentStatus.DRAFT
            }
        }

        test("DRAFT -> DRAFT via updateDraft, category cannot be changed") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject, category = "HONORARY")
                val updated =
                    client
                        .post(
                            "/test/volunteerallowance/update?id=$draftId&category=HONORARY&amount=75.00" +
                                "&description=AktualisierteBeschreibung&date=2026-06-02",
                        ) { header("X-Member-Id", subject.toString()) }
                updated.status shouldBe HttpStatusCode.OK
                updated.bodyAsText().split("|")[4] shouldBe "75.00"

                val categoryChange =
                    client.post(
                        "/test/volunteerallowance/update?id=$draftId&category=INSTRUCTOR&amount=75.00" +
                            "&description=Versuch&date=2026-06-02",
                    ) { header("X-Member-Id", subject.toString()) }
                categoryChange.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("DRAFT -> REQUESTED via submitPayment") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                val submitted =
                    client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }
                submitted.status shouldBe HttpStatusCode.OK
                submitted.bodyAsText().split("|")[2] shouldBe VolunteerAllowancePaymentStatus.REQUESTED.name
            }
        }

        test("DRAFT -> WITHDRAWN and REQUESTED -> WITHDRAWN via withdrawPayment") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject1 = newMember()
                val draftId = client.createDraft(subject1)
                val withdrawnFromDraft =
                    client.post("/test/volunteerallowance/withdraw?id=$draftId") { header("X-Member-Id", subject1.toString()) }
                withdrawnFromDraft.bodyAsText().split("|")[2] shouldBe VolunteerAllowancePaymentStatus.WITHDRAWN.name

                val subject2 = newMember()
                val draftId2 = client.createDraft(subject2)
                client.post("/test/volunteerallowance/submit?id=$draftId2") { header("X-Member-Id", subject2.toString()) }
                val withdrawnFromRequested =
                    client.post("/test/volunteerallowance/withdraw?id=$draftId2") { header("X-Member-Id", subject2.toString()) }
                withdrawnFromRequested.bodyAsText().split("|")[2] shouldBe VolunteerAllowancePaymentStatus.WITHDRAWN.name
            }
        }

        test("REQUESTED -> REJECTED via decidePayment(approve=false)") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }
                val decided =
                    client.post("/test/volunteerallowance/decide?id=$draftId&approve=false&note=NichtPlausibel") {
                        header("X-Member-Id", BOARD_ID)
                    }
                decided.bodyAsText().split("|")[2] shouldBe VolunteerAllowancePaymentStatus.REJECTED.name
            }
        }

        test("APPROVED -> REJECTED via decidePayment(approve=false) -- 'no dead end'") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }
                val approved =
                    client.post("/test/volunteerallowance/decide?id=$draftId&approve=true&note=Genehmigt") {
                        header("X-Member-Id", BOARD_ID)
                    }
                approved.bodyAsText().split("|")[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name

                val rejected =
                    client.post("/test/volunteerallowance/decide?id=$draftId&approve=false&note=DochAbgelehnt") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                rejected.bodyAsText().split("|")[2] shouldBe VolunteerAllowancePaymentStatus.REJECTED.name
            }
        }

        // ── Forbidden transitions ──────────────────────────────────────────────────

        test("submitPayment on a non-DRAFT payment -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("updateDraft on a REQUESTED payment -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }
                client
                    .post(
                        "/test/volunteerallowance/update?id=$draftId&category=HONORARY&amount=99.00" +
                            "&description=Versuch&date=2026-06-02",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("withdrawPayment on an already-EXECUTED-path payment (APPROVED) -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }
                client.post("/test/volunteerallowance/decide?id=$draftId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/volunteerallowance/withdraw?id=$draftId") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("decidePayment on a DRAFT payment -> ConflictException (must be REQUESTED)") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId = client.createDraft(subject)
                client
                    .post("/test/volunteerallowance/decide?id=$draftId&approve=true&note=ZuFrueh") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── MAX_OPEN_PER_MEMBER ────────────────────────────────────────────────────

        test("the 11th open (DRAFT/REQUESTED/APPROVED) payment for the same subject -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                repeat(10) { client.createDraft(subject) }
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                            "&description=ElfterAntrag&date=2026-06-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Formal validation ──────────────────────────────────────────────────────

        test("activityDescription shorter than 3 or longer than 200 characters -> BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                            "&description=ab&date=2026-06-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest

                val tooLong = "x".repeat(201)
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                            "&description=$tooLong&date=2026-06-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("amount <= 0, > MAX_PAYMENT_AMOUNT, or with more than 2 fractional digits -> BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=0.00" +
                            "&description=Vereinshelfer&date=2026-06-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest

                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=10001" +
                            "&description=Vereinshelfer&date=2026-06-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest

                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.123" +
                            "&description=Vereinshelfer&date=2026-06-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("paymentDate too far in the past or future -> BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                            "&description=Vereinshelfer&date=2000-01-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest

                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                            "&description=Vereinshelfer&date=2099-01-01",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a member whose membership has ended cannot have a draft created for them") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember(status = MemberStatus.WITHDRAWN)
                client
                    .post(
                        "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                            "&description=Vereinshelfer&date=2026-06-01",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }
    })
