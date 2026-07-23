package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
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
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [CrowdfundingService] end to end, mirroring [GovernanceServiceTest]'s house style
 * (throwaway routes calling the service class directly, no wire format to reverse-engineer).
 * DevSeedData's ADMIN/BOARD/TREASURER accounts are used only as the *actors* performing
 * privileged actions (approve/reject/computeMonthlyDistribution) -- every member that STAKES LTR
 * or otherwise accrues ledger/contribution rows is a fresh test member, same discipline
 * [GovernanceServiceTest] documents for its own fixtures (so this file's assertions never become
 * order-dependent on other Spec classes sharing the same H2 instance). [afterSpec] hard-deletes
 * every row this file created.
 */
class CrowdfundingServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdProjectIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        // The submission entry hurdle is deliberately GLOBAL (computed over every non-REJECTED
        // crowdfunding_project row, see CrowdfundingService.submitProject KDoc) -- so, unlike
        // GovernanceServiceTest's fresh-Committee-per-test isolation, a project surviving from an
        // earlier test in this Spec would silently raise the hurdle for every later test. Cleaning
        // up each test's own projects (and their dependent reaction/distribution rows) right after
        // that test runs -- not deferred to afterSpec -- keeps every test's hurdle computation
        // scoped to only what IT created.
        afterTest {
            if (createdProjectIds.isNotEmpty()) {
                transaction {
                    CrowdfundingDistributionTable.deleteWhere { CrowdfundingDistributionTable.projectId inList createdProjectIds }
                    CrowdfundingReactionTable.deleteWhere { CrowdfundingReactionTable.projectId inList createdProjectIds }
                    CrowdfundingProjectTable.deleteWhere { CrowdfundingProjectTable.id inList createdProjectIds }
                }
                createdProjectIds.clear()
            }
        }

        afterSpec { cleanUpCrowdfundingTestData(createdMemberIds, createdProjectIds, createdContributionIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Crowdfunding Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
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

        /** Direct-DB MINT seed, mirroring [GovernanceServiceTest]'s own `seedLtrBalance` idiom, updated for the real ledger. */
        fun mintLtr(
            memberId: Uuid,
            amount: BigDecimal,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = amount
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        fun seedPaidContribution(
            memberId: Uuid,
            amount: BigDecimal,
            paidAt: LocalDateTime,
        ) {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = paidAt.date
                    it[periodEnd] = paidAt.date
                    it[amountDue] = amount
                    it[status] = ContributionStatus.PAID
                    it[ContributionTable.paidAt] = paidAt
                    it[paidAmount] = amount
                    it[note] = null
                    it[createdAt] = paidAt
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = DevSeedData.standardTierId
                }
            }
            createdContributionIds += id
        }

        /** Backdates a project's `submitted_at` directly -- the only way to simulate elapsed time for silence-is-approval/decay without a real clock. */
        fun backdateSubmission(
            projectId: Uuid,
            submittedAt: LocalDateTime,
        ) {
            transaction {
                CrowdfundingProjectTable.update({ CrowdfundingProjectTable.id eq projectId }) {
                    it[CrowdfundingProjectTable.submittedAt] = submittedAt
                }
            }
        }

        test("submitProject: first project succeeds, is immediately PENDING, and binds a PROJECT_STAKE debit") {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val submitter = createTestMember("cf-first-project@example.org")
                mintLtr(submitter, BigDecimal("10.00"))

                val response =
                    client
                        .post("/test/submit-project?title=ErstesProjekt&weight=5.00") {
                            header("X-Member-Id", submitter.toString())
                        }.bodyAsText()
                val (projectId, status) = response.split(":")
                status shouldBe "PENDING"
                createdProjectIds += Uuid.parse(projectId)

                val balance = client.get("/test/my-balance") { header("X-Member-Id", submitter.toString()) }.bodyAsText()
                balance shouldBe "5.00"

                val stakeRow =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq submitter) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.PROJECT_STAKE)
                            }.single()
                    }
                stakeRow[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("-5.00")) shouldBe 0
                stakeRow[LtrLedgerEntryTable.referenceId] shouldBe Uuid.parse(projectId)
            }
        }

        test(
            "submitProject: entry hurdle -- below the current top weight is rejected, exactly equal succeeds; stake above free balance is rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val first = createTestMember("cf-hurdle-first@example.org")
                val second = createTestMember("cf-hurdle-second@example.org")
                val third = createTestMember("cf-hurdle-third@example.org")
                mintLtr(first, BigDecimal("20.00"))
                mintLtr(second, BigDecimal("20.00"))
                mintLtr(third, BigDecimal("1.00"))

                val firstResp =
                    client.post("/test/submit-project?title=Top&weight=10.00") { header("X-Member-Id", first.toString()) }.bodyAsText()
                createdProjectIds += Uuid.parse(firstResp.substringBefore(":"))

                val belowHurdle =
                    client.post("/test/submit-project?title=ZuNiedrig&weight=9.99") { header("X-Member-Id", second.toString()) }
                belowHurdle.status shouldBe HttpStatusCode.Conflict

                val exactlyAtHurdle =
                    client.post("/test/submit-project?title=GenauGleich&weight=10.00") { header("X-Member-Id", second.toString()) }
                exactlyAtHurdle.status shouldBe HttpStatusCode.OK
                createdProjectIds += Uuid.parse(exactlyAtHurdle.bodyAsText().substringBefore(":"))

                val insufficientBalance =
                    client.post("/test/submit-project?title=ZuTeuer&weight=10.00") { header("X-Member-Id", third.toString()) }
                insufficientBalance.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("submitProject: requires AKTIV membership") {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val gast = createTestMember("cf-gast@example.org", status = MemberStatus.GAST)
                mintLtr(gast, BigDecimal("10.00"))
                val response = client.post("/test/submit-project?title=X&weight=1.00") { header("X-Member-Id", gast.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("approveProject/rejectProject: BOARD/ADMIN only, requires PENDING, reject requires a non-blank reason") {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val submitter = createTestMember("cf-approve@example.org")
                mintLtr(submitter, BigDecimal("5.00"))
                val projectId =
                    client
                        .post("/test/submit-project?title=Approve-Test&weight=1.00") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdProjectIds += Uuid.parse(projectId)

                val forbidden = client.post("/test/approve-project/$projectId") { header("X-Member-Id", submitter.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val blankReason = client.post("/test/reject-project/$projectId?reason=") { header("X-Member-Id", BOARD_ID) }
                blankReason.status shouldBe HttpStatusCode.Conflict

                val approved = client.post("/test/approve-project/$projectId") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                approved.bodyAsText() shouldBe "APPROVED"

                val secondDecision = client.post("/test/reject-project/$projectId?reason=zu spaet") { header("X-Member-Id", BOARD_ID) }
                secondDecision.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "silence-is-approval: effectiveStatus flips to APPROVED after 14 days without a board action; a late board decision is rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val submitter = createTestMember("cf-silence@example.org")
                mintLtr(submitter, BigDecimal("5.00"))
                val projectId =
                    client
                        .post("/test/submit-project?title=Silence-Test&weight=1.00") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val pId = Uuid.parse(projectId)
                createdProjectIds += pId
                backdateSubmission(pId, LocalDateTime(2020, 1, 1, 12, 0, 0))

                val detail = client.get("/test/get-project/$projectId") { header("X-Member-Id", submitter.toString()) }.bodyAsText()
                val (persistedStatus, effectiveStatus, autoApproved) = detail.split(":")
                persistedStatus shouldBe "PENDING"
                effectiveStatus shouldBe "APPROVED"
                autoApproved shouldBe "true"

                val lateApproval = client.post("/test/approve-project/$projectId") { header("X-Member-Id", BOARD_ID) }
                lateApproval.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "castReaction: requires an APPROVED project, upserts (no duplicate row), retractReaction removes it, basket total never negative",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val submitter = createTestMember("cf-reaction-submitter@example.org")
                val liker = createTestMember("cf-reaction-liker@example.org")
                mintLtr(submitter, BigDecimal("5.00"))
                val projectId =
                    client
                        .post("/test/submit-project?title=Reaction-Test&weight=1.00") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val pId = Uuid.parse(projectId)
                createdProjectIds += pId

                val reactBeforeApproval = client.post("/test/cast-reaction/$projectId/LIKE") { header("X-Member-Id", liker.toString()) }
                reactBeforeApproval.status shouldBe HttpStatusCode.Conflict

                client.post("/test/approve-project/$projectId") { header("X-Member-Id", BOARD_ID) }

                client.post("/test/cast-reaction/$projectId/LIKE") { header("X-Member-Id", liker.toString()) }
                val afterLike = client.get("/test/get-project/$projectId") { header("X-Member-Id", liker.toString()) }.bodyAsText()
                afterLike.split(":").let {
                    it[3] shouldBe "1"
                    it[4] shouldBe "0"
                    it[5] shouldBe "1"
                } // like/dislike/basket

                // Upsert: switching to DISLIKE must not create a second row.
                client.post("/test/cast-reaction/$projectId/DISLIKE") { header("X-Member-Id", liker.toString()) }
                val reactionRowCount =
                    transaction {
                        CrowdfundingReactionTable
                            .selectAll()
                            .where { (CrowdfundingReactionTable.projectId eq pId) and (CrowdfundingReactionTable.memberId eq liker) }
                            .count()
                    }
                reactionRowCount shouldBe 1L
                val afterDislike = client.get("/test/get-project/$projectId") { header("X-Member-Id", liker.toString()) }.bodyAsText()
                afterDislike.split(":").let {
                    it[3] shouldBe "0"
                    it[4] shouldBe "1"
                    it[5] shouldBe "0"
                } // basket floored at 0, not -1

                client.post("/test/retract-reaction/$projectId") { header("X-Member-Id", liker.toString()) }
                val afterRetract = client.get("/test/get-project/$projectId") { header("X-Member-Id", liker.toString()) }.bodyAsText()
                afterRetract.split(":").let {
                    it[3] shouldBe "0"
                    it[4] shouldBe "0"
                    it[5] shouldBe "0"
                }

                // Retracting a reaction that was never cast is a silent no-op, not an error.
                val noopRetract = client.post("/test/retract-reaction/$projectId") { header("X-Member-Id", liker.toString()) }
                noopRetract.status shouldBe HttpStatusCode.OK
            }
        }

        test("computeMonthlyDistribution: TREASURY-only, per-payer minimum deducted once, proportional split, idempotent re-run") {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val submitterA = createTestMember("cf-dist-a@example.org")
                val submitterB = createTestMember("cf-dist-b@example.org")
                val payer1 = createTestMember("cf-dist-payer1@example.org")
                val payer2 = createTestMember("cf-dist-payer2@example.org")
                val liker = createTestMember("cf-dist-liker@example.org")
                mintLtr(submitterA, BigDecimal("5.00"))
                mintLtr(submitterB, BigDecimal("5.00"))

                val projectA =
                    client
                        .post("/test/submit-project?title=DistA&weight=1.00") { header("X-Member-Id", submitterA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val projectB =
                    client
                        .post("/test/submit-project?title=DistB&weight=1.00") { header("X-Member-Id", submitterB.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdProjectIds += Uuid.parse(projectA)
                createdProjectIds += Uuid.parse(projectB)
                client.post("/test/approve-project/$projectA") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/approve-project/$projectB") { header("X-Member-Id", BOARD_ID) }

                // Only projectA gets a basket (Like) -- projectB has basket 0 and must receive nothing.
                client.post("/test/cast-reaction/$projectA/LIKE") { header("X-Member-Id", liker.toString()) }

                // payer1 pays twice in the period (must be deducted only ONCE, distinct-payer, not per-row);
                // payer2 pays once.
                val periodStart = LocalDate(2030, 6, 1)
                val periodEnd = LocalDate(2030, 6, 30)
                seedPaidContribution(payer1, BigDecimal("10.00"), LocalDateTime(2030, 6, 5, 10, 0))
                seedPaidContribution(payer1, BigDecimal("10.00"), LocalDateTime(2030, 6, 10, 10, 0))
                seedPaidContribution(payer2, BigDecimal("10.00"), LocalDateTime(2030, 6, 15, 10, 0))
                // total paid = 30.00, distinct payers = 2, MIN_PLATFORM_CONTRIBUTION_EUR = 2.00 -> pool = 30.00 - 4.00 = 26.00

                val forbidden =
                    client.post("/test/compute-distribution/2030-06-01/2030-06-30") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val first =
                    client
                        .post("/test/compute-distribution/2030-06-01/2030-06-30") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                val entries = first.split(";").filter { it.isNotBlank() }
                entries.size shouldBe 1 // only projectA has a positive basket
                val (distProjectId, amount) = entries.single().split("=")
                distProjectId shouldBe projectA
                BigDecimal(amount).compareTo(BigDecimal("26.00")) shouldBe 0

                // Idempotent re-run: no duplicate rows, same result.
                val second =
                    client
                        .post("/test/compute-distribution/2030-06-01/2030-06-30") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                second shouldBe first
                val distributionCount =
                    transaction {
                        CrowdfundingDistributionTable
                            .selectAll()
                            .where {
                                (CrowdfundingDistributionTable.periodStart eq periodStart) and
                                    (CrowdfundingDistributionTable.periodEnd eq periodEnd)
                            }.count()
                    }
                distributionCount shouldBe 1L

                val listed =
                    client
                        .get("/test/list-distributions/$projectA") { header("X-Member-Id", MEMBER_ID) }
                        .bodyAsText()
                (listed.contains(projectA)) shouldBe true
            }
        }

        test("reads (listProjects/getProject/getMyReaction/listDistributions) require authentication but no elevated role") {
            testApplication {
                application {
                    install(StatusPages) { installCrowdfundingExceptionHandlers() }
                    routing { registerCrowdfundingTestRoutes() }
                }
                val submitter = createTestMember("cf-reads@example.org")
                mintLtr(submitter, BigDecimal("5.00"))
                val projectId =
                    client
                        .post("/test/submit-project?title=Reads-Test&weight=1.00") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                createdProjectIds += Uuid.parse(projectId)

                val unauthenticatedList = client.get("/test/list-projects")
                unauthenticatedList.status shouldBe HttpStatusCode.Unauthorized

                val listedByAnyMember = client.get("/test/list-projects") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                (listedByAnyMember.contains(projectId)) shouldBe true

                val myReaction = client.get("/test/my-reaction/$projectId") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                myReaction shouldBe "none"
            }
        }
    })

private fun cleanUpCrowdfundingTestData(
    memberIds: List<Uuid>,
    projectIds: List<Uuid>,
    contributionIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && projectIds.isEmpty() && contributionIds.isEmpty()) return
    transaction {
        if (projectIds.isNotEmpty()) {
            CrowdfundingDistributionTable.deleteWhere { CrowdfundingDistributionTable.projectId inList projectIds }
            CrowdfundingReactionTable.deleteWhere { CrowdfundingReactionTable.projectId inList projectIds }
            CrowdfundingProjectTable.deleteWhere { CrowdfundingProjectTable.id inList projectIds }
        }
        if (contributionIds.isNotEmpty()) {
            ContributionTable.deleteWhere { ContributionTable.id inList contributionIds }
        }
        if (memberIds.isNotEmpty()) {
            LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}

private fun StatusPagesConfig.installCrowdfundingExceptionHandlers() {
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
}

/** Shared throwaway routes for [CrowdfundingService] -- mirrors [GovernanceServiceTest]'s `registerGovernanceTestRoutes` style. */
private fun Route.registerCrowdfundingTestRoutes() {
    post("/test/submit-project") {
        val service = CrowdfundingService(call)
        val q = call.request.queryParameters
        val p =
            service.submitProject(
                CrowdfundingProjectInput(
                    title = q["title"] ?: "Testprojekt",
                    description = q["description"] ?: "Testbeschreibung",
                    initialWeightLtr = BigDecimal(q["weight"] ?: "1.00"),
                ),
            )
        call.respondText("${p.id}:${p.status}")
    }
    get("/test/get-project/{id}") {
        val service = CrowdfundingService(call)
        val p = service.getProject(call.parameters["id"]!!)
        call.respondText("${p.status}:${p.effectiveStatus}:${p.isAutoApproved}:${p.likeCount}:${p.dislikeCount}:${p.basketTotal}")
    }
    get("/test/list-projects") {
        val service = CrowdfundingService(call)
        call.respondText(service.listProjects().joinToString(",") { it.id })
    }
    post("/test/approve-project/{id}") {
        val service = CrowdfundingService(call)
        val p = service.approveProject(call.parameters["id"]!!)
        call.respondText(p.status.name)
    }
    post("/test/reject-project/{id}") {
        val service = CrowdfundingService(call)
        val reason = call.request.queryParameters["reason"] ?: ""
        val p = service.rejectProject(call.parameters["id"]!!, reason)
        call.respondText(p.status.name)
    }
    post("/test/cast-reaction/{id}/{value}") {
        val service = CrowdfundingService(call)
        val r =
            service.castReaction(
                call.parameters["id"]!!,
                CrowdfundingReactionValue.valueOf(call.parameters["value"]!!),
            )
        call.respondText(r.value.name)
    }
    post("/test/retract-reaction/{id}") {
        val service = CrowdfundingService(call)
        service.retractReaction(call.parameters["id"]!!)
        call.respondText("ok")
    }
    get("/test/my-reaction/{id}") {
        val service = CrowdfundingService(call)
        val r = service.getMyReaction(call.parameters["id"]!!)
        call.respondText(r.singleOrNull()?.value?.name ?: "none")
    }
    post("/test/compute-distribution/{start}/{end}") {
        val service = CrowdfundingService(call)
        val results =
            service.computeMonthlyDistribution(
                LocalDate.parse(call.parameters["start"]!!),
                LocalDate.parse(call.parameters["end"]!!),
            )
        call.respondText(results.joinToString(";") { "${it.projectId}=${it.amountEur}" })
    }
    get("/test/list-distributions/{projectId}") {
        val service = CrowdfundingService(call)
        val results = service.listDistributions(call.parameters["projectId"])
        call.respondText(results.joinToString(",") { it.projectId })
    }
    get("/test/my-balance") {
        val service = LtrLedgerService(call)
        call.respondText(service.getMyBalance().freeBalanceLtr.toString())
    }
}
