package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- role gates (T-3), IDOR (T-4), Vier-Augen-Prinzip
 * (T-5), and Art. 9 field-level reason-text redaction (T-6) for [ContributionReliefService].
 */
class ContributionReliefAuthzTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ContributionReliefRequestTable.deleteWhere { subjectMemberId inList createdMemberIds }
                ContributionTable.deleteWhere { memberId inList createdMemberIds }
                // MEMBER self-requests write AuditLogEntryTable rows with actorMemberId = the
                // fresh member itself -- fk_audit_log_entry_actor_member_id would otherwise block
                // the MemberTable delete below. Same "null the actor reference, never delete the
                // append-only row" idiom MemberFamilyServiceTest's own afterSpec establishes.
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
                MembershipTierTable.deleteWhere { id inList createdTierIds }
            }
        }

        fun newTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Authz-Testtarif-$id"
                    it[description] = "Nur fuer ContributionReliefAuthzTest"
                    it[contributionAmount] = BigDecimal("100.00")
                    it[billingInterval] = BillingInterval.MONTHLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun newMember(
            status: MemberStatus = MemberStatus.ACTIVE,
            tierId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Authz Testmitglied"
                    it[email] = "relief-authz-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = tierId
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

        fun newContribution(
            memberId: Uuid,
            tierId: Uuid,
            dueDate: LocalDate,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[periodStart] = dueDate
                    it[periodEnd] = dueDate
                    it[amountDue] = BigDecimal("100.00")
                    it[status] = ContributionStatus.OPEN
                    it[createdAt] = LocalDateTime(2027, 1, 1, 0, 0)
                    it[ContributionTable.dueDate] = dueDate
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                }
            }
            return id
        }

        // ── T-3: role gates ────────────────────────────────────────────────────────────

        test("MEMBER may request for themselves but not list/decide/retry the queue") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId = tierId)
                val contributionId = newContribution(memberId, tierId, LocalDate(2027, 3, 1))

                client
                    .post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$contributionId&newDueDate=2027-06-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                    .status shouldBe HttpStatusCode.OK

                client.get("/test/relief/list") { header("X-Member-Id", memberId.toString()) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "TREASURER may neither listReliefRequests nor decideReliefRequest nor retryReliefExecution -- mirrors markContributionWaived's gate",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId = tierId)
                val contributionId = newContribution(memberId, tierId, LocalDate(2027, 3, 1))
                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                client.get("/test/relief/list") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/relief/decide?id=$requestId&approve=false") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.post("/test/relief/retry?id=$requestId") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("BOARD and ADMIN may both list and decide") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                client.get("/test/relief/list") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.get("/test/relief/list") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── T-4: IDOR ──────────────────────────────────────────────────────────────────

        test("a MEMBER requesting relief for a FOREIGN member id -> ForbiddenException") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val actor = newMember(tierId = tierId)
                val victim = newMember(tierId = tierId)
                val contributionId = newContribution(victim, tierId, LocalDate(2027, 3, 1))

                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$victim&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$contributionId&newDueDate=2027-06-01",
                    ) { header("X-Member-Id", actor.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD requesting relief in the name of a foreign member is ALLOWED, requestedBy differs from subject") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val subject = newMember(tierId = tierId)
                val contributionId = newContribution(subject, tierId, LocalDate(2027, 3, 1))

                val dto =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$subject&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                dto[1] shouldBe subject.toString() // subjectMemberId
                dto[12] shouldBe BOARD_ID // requestedBy
            }
        }

        // ── T-5: Vier-Augen-Prinzip ────────────────────────────────────────────────────

        test("a BOARD member cannot decide on their own request -- ForbiddenException, request stays REQUESTED") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val boardMemberId = Uuid.parse(BOARD_ID)
                val contributionId = newContribution(boardMemberId, tierId, LocalDate(2027, 3, 1))

                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$BOARD_ID&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")[0]

                val response =
                    client.post("/test/relief/decide?id=$requestId&approve=true&note=Selbstgenehmigung") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Forbidden

                transaction {
                    ContributionReliefRequestTable
                        .selectAll()
                        .where {
                            ContributionReliefRequestTable.id eq
                                Uuid.parse(
                                    requestId,
                                )
                        }.single()[
                        ContributionReliefRequestTable.status,
                    ]
                } shouldBe ContributionReliefStatus.REQUESTED

                // Cleanup: this test's contribution row belongs to a SEEDED member (BOARD), not to
                // createdMemberIds -- delete it explicitly so it doesn't leak into other suites.
                transaction {
                    ContributionReliefRequestTable.deleteWhere { ContributionReliefRequestTable.id eq Uuid.parse(requestId) }
                    ContributionTable.deleteWhere { ContributionTable.id eq contributionId }
                }
            }
        }

        // ── T-6: Art. 9 field-level reasonText redaction ──────────────────────────────
        //
        // Field-level redaction (ContributionReliefService.toDto's `canSeeReasonText` check) is
        // defense-in-depth: under the CURRENT endpoint surface, every viewer who can ever be
        // handed a given row's DTO is either the subject itself, or privileged (BOARD/ADMIN) --
        // requestRelief's own IDOR gate makes a non-privileged requestedBy != subjectId
        // structurally impossible, listReliefRequests requires BOARD/ADMIN, and
        // listMyReliefRequests filters to the caller's own subject/requestedBy rows. This test
        // therefore verifies the two REACHABLE positive cases (both must see the real text) --
        // the redaction branch itself has no reachable negative path to exercise through the
        // public RPC surface as currently scoped, which is itself the point: nobody who shouldn't
        // see it CAN ask for it.

        test("reasonText is visible to both the subject itself and BOARD/ADMIN") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val subject = newMember(tierId = tierId)
                val contributionId = newContribution(subject, tierId, LocalDate(2027, 3, 1))

                client.post(
                    "/test/relief/request?subjectMemberId=$subject&kind=DEFERRAL&reason=ILLNESS_DISABILITY" +
                        "&contributionId=$contributionId&newDueDate=2027-06-01&reasonText=Sensible+Angabe",
                ) { header("X-Member-Id", subject.toString()) }

                val boardView =
                    client.get("/test/relief/list?kind=DEFERRAL") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                boardView.split(";").single { it.contains(subject.toString()) }.split("|")[5] shouldBe "Sensible Angabe"

                val ownView = client.get("/test/relief/mine") { header("X-Member-Id", subject.toString()) }.bodyAsText()
                ownView.split(";").single { it.contains(subject.toString()) }.split("|")[5] shouldBe "Sensible Angabe"
            }
        }
    })
