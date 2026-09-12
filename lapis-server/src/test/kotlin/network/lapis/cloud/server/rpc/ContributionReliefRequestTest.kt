package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
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

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- happy paths (T-1), payload validation (T-2), and a
 * representative sample of illegal state transitions (T-7) for [ContributionReliefService]. Mirrors
 * [AuctionServiceTest]'s house style (throwaway routes calling the service class directly). Every
 * fresh member/tier/contribution is created by this file and cleaned up in [afterSpec] -- same S-6
 * discipline every other test in this suite follows against the shared H2 database.
 */
class ContributionReliefRequestTest :
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

        fun newTier(
            amount: BigDecimal = BigDecimal("100.00"),
            active: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Relief-Testtarif-$id"
                    it[description] = "Nur fuer ContributionReliefRequestTest"
                    it[contributionAmount] = amount
                    it[billingInterval] = BillingInterval.MONTHLY
                    it[MembershipTierTable.active] = active
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun newMember(
            tierId: Uuid?,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Relief Testmitglied"
                    it[email] = "relief-request-$id@example.org"
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
            status: ContributionStatus = ContributionStatus.OPEN,
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
                    it[ContributionTable.status] = status
                    it[createdAt] = LocalDateTime(2027, 1, 1, 0, 0)
                    it[ContributionTable.dueDate] = dueDate
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                }
            }
            return id
        }

        fun dueDateOf(contributionId: Uuid): LocalDate =
            transaction {
                ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.dueDate]
            }

        test("DEFERRAL happy path: request -> BOARD approves -> EXECUTED, due date moved, previousDueDate recorded") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))

                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=FINANCIAL_HARDSHIP" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                requested[3] shouldBe ContributionReliefStatus.REQUESTED.name // status

                val requestId = requested[0]
                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.EXECUTED.name
                decided[8] shouldBe "2027-03-01" // deferralPreviousDueDate
                dueDateOf(contributionId) shouldBe LocalDate(2027, 6, 1)
            }
        }

        test("EXEMPTION happy path: request -> ADMIN approves -> EXECUTED, member exemption fields set") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)

                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=EXEMPTION&reason=UNEMPLOYMENT" +
                                "&exemptFrom=2027-04-01&exemptUntil=2027-09-30",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                val requestId = requested[0]

                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Genehmigt") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.EXECUTED.name

                transaction {
                    val row = MemberTable.selectAll().where { MemberTable.id eq memberId }.single()
                    row[MemberTable.contributionExemptFrom] shouldBe LocalDate(2027, 4, 1)
                    row[MemberTable.contributionExemptUntil] shouldBe LocalDate(2027, 9, 30)
                    row[MemberTable.contributionExemptRequestId] shouldBe Uuid.parse(requestId)
                }
            }
        }

        test("REDUCTION happy path: request -> BOARD approves -> EXECUTED, member's tier changed") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val expensiveTier = newTier(amount = BigDecimal("100.00"))
                val cheapTier = newTier(amount = BigDecimal("40.00"))
                val memberId = newMember(expensiveTier)

                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=STUDENT_TRAINEE&targetTierId=$cheapTier",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                val requestId = requested[0]

                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.EXECUTED.name

                transaction {
                    MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.membershipTierId]
                } shouldBe cheapTier
            }
        }

        test(
            "withdraw: REQUESTED -> WITHDRAWN by the subject itself, active_request_key cleared, a fresh request of the same kind then succeeds",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))

                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                val requestId = requested[0]

                val withdrawn =
                    client
                        .post(
                            "/test/relief/withdraw?id=$requestId",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                withdrawn[3] shouldBe ContributionReliefStatus.WITHDRAWN.name

                transaction {
                    ContributionReliefRequestTable
                        .selectAll()
                        .where { ContributionReliefRequestTable.id eq Uuid.parse(requestId) }
                        .single()[ContributionReliefRequestTable.activeRequestKey]
                } shouldBe null

                val secondContribution = newContribution(memberId, tierId, dueDate = LocalDate(2027, 4, 1))
                val secondRequest =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$secondContribution&newDueDate=2027-07-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                secondRequest[3] shouldBe ContributionReliefStatus.REQUESTED.name
            }
        }

        test("K-1: a second open request of the SAME kind for the SAME member is rejected with 409") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))

                client.post(
                    "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                        "&contributionId=$contributionId&newDueDate=2027-06-01",
                ) { header("X-Member-Id", memberId.toString()) }

                val secondContribution = newContribution(memberId, tierId, dueDate = LocalDate(2027, 4, 1))
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$secondContribution&newDueDate=2027-07-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── T-2: payload validation (representative sample, all via HTTP 400) ────────────

        test("requestRelief validation: DEFERRAL without deferralContributionId -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val response =
                    client.post("/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("requestRelief validation: DEFERRAL new due date not after current due date -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 6, 1))
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$contributionId&newDueDate=2027-05-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("requestRelief validation: DEFERRAL beyond MAX_DEFERRAL_DAYS (730) -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$contributionId&newDueDate=2030-01-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("requestRelief validation: EXEMPTION with exemptionUntil before exemptionFrom -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=EXEMPTION&reason=OTHER" +
                            "&exemptFrom=2027-06-01&exemptUntil=2027-01-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("requestRelief validation: REDUCTION targeting an inactive tier -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val currentTier = newTier(amount = BigDecimal("100.00"))
                val inactiveTier = newTier(amount = BigDecimal("40.00"), active = false)
                val memberId = newMember(currentTier)
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=OTHER&targetTierId=$inactiveTier",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("requestRelief validation: REDUCTION targeting a MORE EXPENSIVE tier -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val cheapTier = newTier(amount = BigDecimal("40.00"))
                val expensiveTier = newTier(amount = BigDecimal("100.00"))
                val memberId = newMember(cheapTier)
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=OTHER&targetTierId=$expensiveTier",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "requestRelief validation: REDUCTION for a member with NO membership tier of their own (e.g. a family " +
                "dependent billed through the family's payer) -> 400 (Review fix: `currentTierId != null` used to " +
                "hide BOTH fachlich checks entirely for such a member, letting them request ANY active tier -- " +
                "including a more expensive one -- and be billed directly IN ADDITION to the family invoice)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val anyTier = newTier(amount = BigDecimal("999.00"))
                val dependentMemberId = newMember(tierId = null)
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$dependentMemberId&kind=REDUCTION&reason=OTHER&targetTierId=$anyTier",
                    ) { header("X-Member-Id", dependentMemberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("requestRelief validation: reasonText over 500 characters -> 400") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val response =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=EXEMPTION&reason=OTHER" +
                            "&exemptFrom=2027-01-01&reasonText=${"x".repeat(501)}",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── T-7 (representative sample): illegal state transitions -> 409 ────────────────

        test("decideReliefRequest on a non-REQUESTED request -> 409") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))
                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                val requestId = requested[0]
                client.post("/test/relief/decide?id=$requestId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }

                val response =
                    client.post("/test/relief/decide?id=$requestId&approve=true&note=Nochmal") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("retryReliefExecution on a REQUESTED (not yet APPROVED) request -> 409") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))
                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                val requestId = requested[0]

                val response = client.post("/test/relief/retry?id=$requestId") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("withdrawReliefRequest on an already-EXECUTED request -> 409") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))
                val requested =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")
                val requestId = requested[0]
                client.post("/test/relief/decide?id=$requestId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }

                val response = client.post("/test/relief/withdraw?id=$requestId") { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "withdrawReliefRequest by BOARD/ADMIN who is neither subject nor requester -> 403, not a silent isPrivileged " +
                "bypass (Review fix: withdraw must never let BOARD/ADMIN camouflage their own decision as \"the member " +
                "changed their mind\" -- that path is decideReliefRequest(approve=false) instead)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 3, 1))
                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                val response = client.post("/test/relief/withdraw?id=$requestId") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Forbidden

                // Still REQUESTED -- the forbidden call must not have mutated anything.
                transaction {
                    ContributionReliefRequestTable
                        .selectAll()
                        .where { ContributionReliefRequestTable.id eq Uuid.parse(requestId) }
                        .single()[ContributionReliefRequestTable.status]
                } shouldBe ContributionReliefStatus.REQUESTED
            }
        }

        test(
            "decideReliefRequest(approve=false) rejects a stuck APPROVED-with-executionError request, freeing " +
                "active_request_key for a fresh request of the same kind (Review fix: previously there was no exit " +
                "from a permanently unexecutable APPROVED request)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val stuckContributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 1, 1))
                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$stuckContributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                // The contribution is finally settled between request and decision -- DEFERRABLE
                // no longer holds, and (unlike T-10's fixture) nothing will ever move it back.
                transaction {
                    ContributionTable.update({ ContributionTable.id eq stuckContributionId }) { it[status] = ContributionStatus.PAID }
                }
                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.APPROVED.name

                // A SECOND, still-deferrable contribution -- isolates the active_request_key
                // uniqueness check (what this test is actually about) from the first request's own
                // now-unrelated "contribution not deferrable" validation.
                val otherContributionId = newContribution(memberId, tierId, dueDate = LocalDate(2027, 2, 1))

                // Without an exit, a second DEFERRAL request for the same member would 409 forever
                // against uq_crr_active_request. Confirm that dead end, then confirm the exit.
                val blocked =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$otherContributionId&newDueDate=2027-07-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                blocked.status shouldBe HttpStatusCode.Conflict

                val rejected =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=false") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                rejected[3] shouldBe ContributionReliefStatus.REJECTED.name
                // GoBD/Review fix: rejecting WITHOUT a note (note is optional for approve=false, see
                // the `approve && trimmedNote == null` guard) must not blank out the mandatory
                // decision note the earlier `approve=true` wrote -- ContributionReliefSnapshot never
                // carries decisionNote, so this DB column is the only place that text survives.
                rejected[14] shouldBe "Genehmigt" // decisionNote -- preserved, not overwritten with null

                val freshRequest =
                    client.post(
                        "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                            "&contributionId=$otherContributionId&newDueDate=2027-07-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                freshRequest.status shouldBe HttpStatusCode.OK
                freshRequest.bodyAsText().split("|")[3] shouldBe ContributionReliefStatus.REQUESTED.name
            }
        }

        test(
            "listReliefRequests filters by status, kind, and reviewDueOnly -- reviewDueOnly means the review date " +
                "has actually arrived (review_due_on <= today), not merely that one is set (Review fix: the " +
                "previous isNotNull()-only filter would have shown a review date decades in the future as \"due\")",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val cheapTier = newTier(amount = BigDecimal("10.00"))
                val memberDeferral = newMember(tierId)
                val memberExemptionNotDueYet = newMember(tierId)
                val memberReductionDue = newMember(tierId)

                val contributionId = newContribution(memberDeferral, tierId, dueDate = LocalDate(2027, 3, 1))
                val deferralRequestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberDeferral&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberDeferral.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                val exemptionRequestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberExemptionNotDueYet&kind=EXEMPTION&reason=OTHER" +
                                "&exemptFrom=2027-01-01&reviewDueOn=2099-01-01",
                        ) { header("X-Member-Id", memberExemptionNotDueYet.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                val reductionRequestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberReductionDue&kind=REDUCTION&reason=OTHER" +
                                "&targetTierId=$cheapTier&reviewDueOn=2020-01-01",
                        ) { header("X-Member-Id", memberReductionDue.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/relief/decide?id=$exemptionRequestId&approve=false") { header("X-Member-Id", BOARD_ID) }

                suspend fun listIds(query: String): List<String> =
                    client
                        .get("/test/relief/list?$query") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .let { if (it.isBlank()) emptyList() else it.split(";") }
                        .map { it.split("|")[0] }

                // kind filter.
                listIds("kind=DEFERRAL") shouldContain deferralRequestId
                listIds("kind=DEFERRAL") shouldNotContain exemptionRequestId
                listIds("kind=DEFERRAL") shouldNotContain reductionRequestId

                // status filter -- the EXEMPTION request was just rejected above.
                listIds("status=REJECTED") shouldContain exemptionRequestId
                listIds("status=REQUESTED") shouldNotContain exemptionRequestId
                listIds("status=REQUESTED") shouldContain deferralRequestId

                // reviewDueOnly: due (2020, in the past) vs. not yet due (2099) vs. never set (DEFERRAL).
                val due = listIds("reviewDueOnly=true")
                due shouldContain reductionRequestId
                due shouldNotContain exemptionRequestId
                due shouldNotContain deferralRequestId
            }
        }

        test(
            "listReliefRequests caps at MAX_LIST_RESULTS per page but the keyset cursor reaches every row -- " +
                "Review fix: the query used to sort newest-first with no offset/cursor to page past the cap, so " +
                "once the matched-row count outgrew the cap the longest-waiting requests became permanently " +
                "unreachable; a later fix flipped to ascending order alone, which fixed that but made the " +
                "NEWEST row of any monotonically growing filter unreachable instead -- afterRequestedAt/afterId " +
                "keyset pagination is what actually closes the gap in both directions",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)

                // MAX_LIST_RESULTS (200) + 1 rows, all REQUESTED/EXEMPTION, inserted directly
                // (bypassing the service) so `requestedAt` can be pinned precisely -- one
                // minute-granularity slot per row via (hour, minute) = (i / 60, i % 60), strictly
                // increasing and collision-free for i in 0..200, and all dated 2020 so they sort
                // before every other REQUESTED row this shared spec's other tests may have left
                // behind (those go through the real service and get DbClock's real, present-day
                // timestamp).
                val rowCount = 201
                val ids = List(rowCount) { Uuid.random() }
                try {
                    transaction {
                        ids.forEachIndexed { i, id ->
                            ContributionReliefRequestTable.insert {
                                it[ContributionReliefRequestTable.id] = id
                                it[subjectMemberId] = memberId
                                it[kind] = ContributionReliefKind.EXEMPTION
                                it[status] = ContributionReliefStatus.REQUESTED
                                it[reasonCategory] = ContributionReliefReason.OTHER
                                it[exemptionFrom] = LocalDate(2020, 1, 1)
                                it[requestedAt] = LocalDateTime(2020, 1, 1, i / 60, i % 60, 0)
                                it[requestedBy] = memberId
                            }
                        }
                    }

                    val page1 =
                        client
                            .get("/test/relief/list?status=REQUESTED") { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                            .split(";")
                    val page1Ids = page1.map { it.split("|")[0] }

                    page1Ids.size shouldBe 200 // MAX_LIST_RESULTS -- a page size, not a hard cutoff anymore.
                    page1Ids shouldContain ids.first().toString() // oldest requestedAt -- first page, first row.
                    page1Ids shouldNotContain ids.last().toString() // newest of these 201 -- pushed to page 2.

                    // Test-hygiene note: a stale cap test left its 201 rows for the shared afterSpec to
                    // clean up, deferring failures caused by declaration-order assumptions to whichever
                    // future test got appended below it. This test cleans up its own rows itself (finally
                    // block below), same discipline every other test in this suite already follows.
                    //
                    // Keyset-pagination fix (Review Runde 4): the newest row of this 201-row batch is not
                    // lost -- it is reachable on the NEXT page via the (requestedAt, id) cursor formed
                    // from the last row of page 1, the same round-trip idiom
                    // SocialNetworkServiceTest's beforeReportedAt/beforeId pagination test already
                    // establishes (toString() the DTO field, pass it back as the next query param,
                    // LocalDateTime.parse it server-side) -- just in `after` instead of `before`
                    // direction, matching this query's ASC order.
                    val lastOfPage1 = page1.last().split("|")
                    val cursorId = lastOfPage1[0]
                    val cursorRequestedAt = lastOfPage1[17] // toPipeString()'s last field.
                    val page2Ids =
                        client
                            .get(
                                "/test/relief/list?status=REQUESTED&afterId=$cursorId&afterRequestedAt=$cursorRequestedAt",
                            ) { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                            .split(";")
                            .map { it.split("|")[0] }

                    // The very next row after the page-1/page-2 boundary must be the newest of the 201,
                    // not skipped and not repeated.
                    page2Ids.first() shouldBe ids.last().toString()
                    page2Ids shouldNotContain ids.first().toString() // no repeat of page 1's first row.
                } finally {
                    transaction {
                        ContributionReliefRequestTable.deleteWhere { ContributionReliefRequestTable.id inList ids }
                    }
                }
            }
        }
    })
