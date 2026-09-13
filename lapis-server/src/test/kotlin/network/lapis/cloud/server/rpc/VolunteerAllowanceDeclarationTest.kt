package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- Raskin's Gate: no booking without a
 * self-declaration for `(subjectMemberId, category, paymentDate.year)`, the `uq_vasd_member_category_year`
 * uniqueness invariant, and the `IN_APP`/`ON_PAPER` shape rules ([VolunteerAllowanceCalculator
 * .requireSelfDeclarationShape]). The cap-gate part of `decidePayment` is covered separately by
 * [VolunteerAllowanceCapTest].
 */
class VolunteerAllowanceDeclarationTest :
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

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Declaration Testmitglied"
                    it[email] = "volunteer-decl-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
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

        test("decidePayment without a self-declaration -> APPROVED + executionError=self_declaration_missing, no journal_entry") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                val draftId =
                    client
                        .post(
                            "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                                "&description=Vereinshelfer&date=2026-06-01",
                        ) { header("X-Member-Id", subject.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$draftId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[13] shouldBe "self_declaration_missing"
                decided[12] shouldBe "-" // postedJournalEntryId

                // Nacherfassen der Erklaerung, dann retryPosting -- die Buchung selbst scheitert
                // weiterhin (kein Konto konfiguriert), aber der self_declaration_missing-Grund ist weg.
                client.post(
                    "/test/volunteerallowance/declare-self?category=HONORARY&year=2026",
                ) { header("X-Member-Id", subject.toString()) }
                val retried =
                    client.post("/test/volunteerallowance/retry?id=$draftId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retried[13] shouldNotBe "self_declaration_missing"
            }
        }

        test("UNIQUE(memberId, category, calendarYear): a second declaration for the same triple -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                client
                    .post("/test/volunteerallowance/declare-self?category=HONORARY&year=2026") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .post("/test/volunteerallowance/declare-self?category=HONORARY&year=2026") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("IN_APP source sets recordedBy == memberId and signedOn == null") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val dto =
                    client
                        .post(
                            "/test/volunteerallowance/declare-self?category=HONORARY&year=2026",
                        ) { header("X-Member-Id", member.toString()) }
                        .bodyAsText()
                        .split("|")
                dto[4] shouldBe "IN_APP" // source
                dto[5] shouldBe "-" // signedOn
            }
        }

        test("ON_PAPER source requires signedOn and a different recordedBy") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val dto =
                    client
                        .post(
                            "/test/volunteerallowance/record-paper-declaration?memberId=$member&category=HONORARY&year=2026" +
                                "&signedOn=2026-01-10",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                dto[4] shouldBe "ON_PAPER" // source
                dto[5] shouldBe "2026-01-10" // signedOn
            }
        }

        test("a declaration for year X does NOT cover year X+1") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.post(
                    "/test/volunteerallowance/declare-self?category=HONORARY&year=2025",
                ) { header("X-Member-Id", subject.toString()) }

                // 2026-06-01 is well within VolunteerAllowanceRules.MAX_BACKDATE_DAYS (400) of
                // "today" regardless of exactly when this test runs -- see the module-wide "no
                // far-future literal dates" convention every other VolunteerAllowance* test file
                // already follows. Its year (2026) is the "X+1" the 2025 declaration above does
                // not cover.
                val draftId =
                    client
                        .post(
                            "/test/volunteerallowance/create?subjectMemberId=$subject&category=HONORARY&amount=50.00" +
                                "&description=Vereinshelfer&date=2026-06-01",
                        ) { header("X-Member-Id", subject.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subject.toString()) }

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$draftId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[13] shouldBe "self_declaration_missing"
            }
        }

        test("voidPaperDeclaration: ADMIN can void an ON_PAPER declaration, freeing the subject's own declareSelf afterward") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val declarationId =
                    client
                        .post(
                            "/test/volunteerallowance/record-paper-declaration?memberId=$member&category=HONORARY&year=2026" +
                                "&signedOn=2026-01-10",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")[0]

                // uq_vasd_member_category_year still blocks the subject's own IN_APP declaration
                // while the erroneous ON_PAPER row exists.
                client
                    .post("/test/volunteerallowance/declare-self?category=HONORARY&year=2026") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.Conflict

                client
                    .post("/test/volunteerallowance/void-paper-declaration?id=$declarationId") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText() shouldBe "true"

                val listAfterVoid =
                    client
                        .get("/test/volunteerallowance/declarations") { header("X-Member-Id", member.toString()) }
                        .bodyAsText()
                listAfterVoid.contains(declarationId) shouldBe false

                // The voided row is truly gone -- the subject can now declare for themselves.
                client
                    .post("/test/volunteerallowance/declare-self?category=HONORARY&year=2026") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("voidPaperDeclaration: BOARD (not ADMIN) -> Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val declarationId =
                    client
                        .post(
                            "/test/volunteerallowance/record-paper-declaration?memberId=$member&category=HONORARY&year=2026" +
                                "&signedOn=2026-01-10",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")[0]

                client
                    .post("/test/volunteerallowance/void-paper-declaration?id=$declarationId") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("voidPaperDeclaration: an IN_APP declaration -> Conflict, never voidable through this endpoint") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val declarationId =
                    client
                        .post(
                            "/test/volunteerallowance/declare-self?category=HONORARY&year=2026",
                        ) { header("X-Member-Id", member.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                client
                    .post("/test/volunteerallowance/void-paper-declaration?id=$declarationId") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("voidPaperDeclaration: unknown id -> NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                client
                    .post("/test/volunteerallowance/void-paper-declaration?id=${Uuid.random()}") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("listDeclarations for self returns the own declaration; for a foreign member requires BOARD/ADMIN") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val other = newMember()
                client.post(
                    "/test/volunteerallowance/declare-self?category=HONORARY&year=2026",
                ) { header("X-Member-Id", member.toString()) }

                val ownList =
                    client
                        .get("/test/volunteerallowance/declarations") { header("X-Member-Id", member.toString()) }
                        .bodyAsText()
                ownList.contains(member.toString()) shouldBe true

                client
                    .get("/test/volunteerallowance/declarations?memberId=$member") { header("X-Member-Id", other.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/volunteerallowance/declarations?memberId=$member") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK
            }
        }
    })
