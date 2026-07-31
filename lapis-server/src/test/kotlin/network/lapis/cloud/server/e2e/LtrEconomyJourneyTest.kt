package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.CrowdfundingService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MintLtrInput
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Scenario 2 of the V1.0 end-to-end integration test wave -- see [E2eSupport] KDoc for the shared
 * "real, fully-wired `module()` + real login/session + throwaway RPC test routes on top" idiom
 * every scenario in this package uses.
 *
 * Crosses THREE waves in one continuous, real-HTTP-driven story: V0.1 (Contribution funds the EUR
 * pool) -> V0.6.1 (Internes Crowdfunding -- project stake, board approval, a real member's Like) ->
 * V0.6.1 (monthly EUR distribution, apportioned across the LTR-unweighted "Verteilungs-Korb" of
 * Like/Dislike counts, deducting a fixed per-payer platform minimum).
 *
 * **Actor choices, and why.** The LTR staker is [DevSeedData]'s existing AKTIV `MEMBER` demo
 * account ("Max Mitglied") -- a real login against a *pre-existing* AKTIV member, distinct from
 * Scenario 1's real-*registration*-then-approval story. The Like-caster is a SECOND, freshly
 * created, real-login member (see [createRealMember]) -- a different member than the submitter, so
 * `castReaction`'s "any member may react, not just the submitter" contract is genuinely exercised.
 * The two contribution PAYERS that fund the EUR pool are two more freshly created members, kept
 * deliberately separate from the staker/liker: `computeMonthlyDistribution` sums `PAID`
 * contributions GLOBALLY over the requested period with no per-project attribution (V0.1 and V0.6.1
 * are related only by "the pool exists", not by "this payer funds this project") -- reusing the
 * staker or liker as a payer would not make the cross-domain seam any stronger, and *would* mean
 * touching [DevSeedData]'s seeded `standardTierId`-linked bookkeeping for "Max Mitglied", which
 * this scenario deliberately avoids for the same tier-isolation reason Scenario 1's own KDoc
 * documents (a scenario-private [network.lapis.cloud.server.db.generated.MembershipTierTable] row,
 * assigned only to members this scenario itself creates).
 *
 * **The wave's documented, deliberate scope cut, asserted as current behavior (step 9) -- flagged,
 * not fixed.** [CrowdfundingService.computeMonthlyDistribution] writes a decision/ALLOCATION record
 * only ([network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable] rows: who gets how
 * much of the monthly EUR pool); it invokes no accounting-posting path whatsoever, so the EUR
 * transfer that allocation implies is never booked into the GoBD-audited ledger by this method.
 * Step 9 pins that down with a global [network.lapis.cloud.server.db.generated.JournalEntryTable]
 * before/after row-count comparison, which is what turns the CHANGELOG's "Known limitations" entry
 * from a prose claim into a CHARACTERIZED behavior: if a later wave wires up automatic posting (a
 * real product decision, deliberately out of scope for a test wave), that assertion fails and forces
 * the limitation entry to be revisited rather than letting it silently rot.
 *
 * **Distribution period is 2027-02, deliberately disjoint from every other Spec's contribution
 * `paidAt` window in this codebase** (Scenario 1 uses 2026-10, `CrowdfundingServiceTest`'s own
 * `computeMonthlyDistribution` case uses 2030-06) -- `computeMonthlyDistribution` sums `PAID`
 * contributions across ALL members/tiers whose `paidAt` falls in the requested window, not just
 * this scenario's own tier, so an accidental period collision with another Spec's paid contribution
 * would silently inflate this test's pool and distinct-payer count and turn the "exact BigDecimal"
 * assertion below into a flaky one.
 *
 * **Why the mint/stake ledger rows for the SEEDED "Max Mitglied" account are cleaned up by exact
 * id, not by `memberId inList`.** Every other Spec in this ~1100-test suite that touches
 * [E2eSupport.MEMBER_ID] assumes it stays a normal, un-retired AKTIV `MEMBER` account with whatever
 * ledger/tier state `DevSeedData` itself set up -- unlike [E2eSupport.hardDeleteGovernanceAndMembershipFixtures]
 * (which is only ever called with FRESH, this-scenario-only member ids), this scenario must not
 * retire or otherwise touch "Max Mitglied" itself. The two [LtrLedgerEntryTable] rows this scenario
 * adds to that account (the treasury MINT, and the PROJECT_STAKE `submitProject` binds) are instead
 * tracked by their own generated ids and deleted precisely, leaving every OTHER row on that
 * account -- including any left by a differently-ordered Spec run before this one -- untouched.
 */
class LtrEconomyJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdMembershipTierIds = mutableListOf<Uuid>()
        val createdProjectIds = mutableListOf<Uuid>()
        val createdLedgerEntryIdsOnSeededMember = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // Crowdfunding rows first (mirrors CrowdfundingServiceTest's own cleanup order --
                // distributions/reactions before the project row they reference).
                if (createdProjectIds.isNotEmpty()) {
                    CrowdfundingDistributionTable.deleteWhere { CrowdfundingDistributionTable.projectId inList createdProjectIds }
                    CrowdfundingReactionTable.deleteWhere { CrowdfundingReactionTable.projectId inList createdProjectIds }
                    CrowdfundingProjectTable.deleteWhere { CrowdfundingProjectTable.id inList createdProjectIds }
                }
                // See class KDoc: exact-id cleanup on the SEEDED member, never memberId-scoped.
                if (createdLedgerEntryIdsOnSeededMember.isNotEmpty()) {
                    LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.id inList createdLedgerEntryIdsOnSeededMember }
                }
                // Retires the fresh liker/payer members (never the seeded staker) and, as a side
                // effect, deletes their Contribution/LtrLedgerEntry/Session rows -- see
                // hardDeleteGovernanceAndMembershipFixtures KDoc.
                hardDeleteGovernanceAndMembershipFixtures(emptyList(), createdMemberIds)
                if (createdMembershipTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdMembershipTierIds }
                }
            }
        }

        test(
            "real login as a seeded AKTIV member -> LTR minted -> project staked (balance drop proven via an " +
                "independent LtrLedgerService read) -> board approval -> a SECOND real-login member's real " +
                "session Likes it -> real paid contributions fund the EUR pool -> the monthly distribution's " +
                "exact amount is derived from the real paid sum, and re-running it is idempotent",
        ) {
            testApplication {
                application {
                    module()
                    routing {
                        post("/e2e2/mint-ltr/{memberId}") {
                            val dto =
                                LtrLedgerService(call).mintLtr(
                                    MintLtrInput(
                                        memberId = call.parameters["memberId"]!!,
                                        amountLtr = BigDecimal(call.request.queryParameters["amount"]!!),
                                        note = "E2E Scenario 2 Startguthaben",
                                    ),
                                )
                            call.respondText(dto.id)
                        }
                        get("/e2e2/my-balance") {
                            call.respondText(LtrLedgerService(call).getMyBalance().freeBalanceLtr.toString())
                        }
                        post("/e2e2/submit-project") {
                            val q = call.request.queryParameters
                            val p =
                                CrowdfundingService(call).submitProject(
                                    CrowdfundingProjectInput(
                                        title = q["title"]!!,
                                        description = "E2E Scenario 2",
                                        initialWeightLtr = BigDecimal(q["weight"]!!),
                                    ),
                                )
                            call.respondText("${p.id}:${p.status}")
                        }
                        post("/e2e2/approve-project/{id}") {
                            val p = CrowdfundingService(call).approveProject(call.parameters["id"]!!)
                            call.respondText(p.status.name)
                        }
                        post("/e2e2/cast-reaction/{id}/{value}") {
                            val r =
                                CrowdfundingService(call).castReaction(
                                    call.parameters["id"]!!,
                                    CrowdfundingReactionValue.valueOf(call.parameters["value"]!!),
                                )
                            call.respondText(r.value.name)
                        }
                        post("/e2e2/create-tier") {
                            val tier =
                                ContributionService(call).createMembershipTier(
                                    MembershipTierInput(
                                        name = "E2E-Journey-2-Beitragsstufe",
                                        description = "Scenario-private tier -- see the test's tier-isolation comment",
                                        contributionAmount = BigDecimal("12.50"),
                                        billingInterval = BillingInterval.MONTHLY,
                                    ),
                                )
                            call.respondText("${tier.id}:${tier.contributionAmount}")
                        }
                        post("/e2e2/generate-contributions/{tierId}") {
                            val count =
                                ContributionService(call).generateContributionsForPeriod(
                                    call.parameters["tierId"]!!,
                                    LocalDate(2027, 2, 1),
                                    LocalDate(2027, 2, 28),
                                )
                            call.respondText(count.toString())
                        }
                        get("/e2e2/contribution-for/{memberId}") {
                            val list = ContributionService(call).listContributions(memberId = call.parameters["memberId"]!!)
                            call.respondText(list.joinToString(",") { "${it.id}=${it.amountDue}" })
                        }
                        post("/e2e2/mark-paid/{contributionId}/{paidDay}") {
                            val day = call.parameters["paidDay"]!!.toInt()
                            val amount = BigDecimal(call.request.queryParameters["amount"]!!)
                            val dto =
                                ContributionService(call).markContributionPaid(
                                    call.parameters["contributionId"]!!,
                                    LocalDateTime(2027, 2, day, 12, 0),
                                    amount,
                                    "E2E Scenario 2",
                                )
                            call.respondText("${dto.status.name}:${dto.paidAmount}")
                        }
                        post("/e2e2/compute-distribution/{start}/{end}") {
                            val results =
                                CrowdfundingService(call).computeMonthlyDistribution(
                                    LocalDate.parse(call.parameters["start"]!!),
                                    LocalDate.parse(call.parameters["end"]!!),
                                )
                            call.respondText(results.joinToString(";") { "${it.projectId}=${it.amountEur}" })
                        }
                    }
                }

                // ── Step 1: real login as a PRE-EXISTING seeded AKTIV member (V0.6.1's own house ──
                // ── style for the LTR-staking actor -- distinct from Scenario 1's fresh-registration ─
                // ── story) ─────────────────────────────────────────────────────────────────────────
                val staker = DevSeedData.demoMembers.single { it.role == AccountRole.MEMBER }
                val stakerToken = client.realLogin(staker.email, DevSeedData.DEMO_PASSWORD)

                // ── Step 2: TREASURER mints LTR to the staker ────────────────────────────────────
                val mintEntryId =
                    client.post("/e2e2/mint-ltr/${staker.id}?amount=75.00") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                createdLedgerEntryIdsOnSeededMember += Uuid.parse(mintEntryId)
                val balanceAfterMint = client.get("/e2e2/my-balance") { withSession(stakerToken) }.bodyAsText()

                // ── Step 3: cross-domain seam #1 -- the SAME real session submits a Crowdfunding ──
                // ── project, staking real LTR. The stake weight (60.00) is comfortably above every ─
                // ── weight any other Spec in this suite ever submits (max observed: 10.00 in ────────
                // ── CrowdfundingServiceTest), so this project always clears the global,
                // ── decaying entry hurdle regardless of Spec execution order -- the same accepted ──
                // ── risk class CrowdfundingServiceTest's own exact-amount distribution assertion ───
                // ── already lives with. ────────────────────────────────────────────────────────────
                val submitResponse =
                    client.post("/e2e2/submit-project?title=E2E-Scenario-2-Projekt&weight=60.00") { withSession(stakerToken) }
                submitResponse.status shouldBe HttpStatusCode.OK
                val (projectId, projectStatus) = submitResponse.bodyAsText().split(":")
                createdProjectIds += Uuid.parse(projectId)
                projectStatus shouldBe "PENDING"
                val stakeEntryId =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq staker.id) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.PROJECT_STAKE)
                            }.single()[LtrLedgerEntryTable.id]
                    }
                createdLedgerEntryIdsOnSeededMember += stakeEntryId

                // Cross-domain seam #2 -- the balance drop is read back through LtrLedgerService,
                // a service completely independent of CrowdfundingService's own write path, and
                // must equal EXACTLY the stake (not "some balance"), proving submitProject's debit
                // actually landed in the shared ledger rather than some Crowdfunding-private counter.
                val balanceAfterStake = client.get("/e2e2/my-balance") { withSession(stakerToken) }.bodyAsText()
                (BigDecimal(balanceAfterMint) - BigDecimal(balanceAfterStake)).compareTo(BigDecimal("60.00")) shouldBe 0

                // ── Step 4: BOARD approves the project ────────────────────────────────────────────
                client.post("/e2e2/approve-project/$projectId") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe
                    "APPROVED"

                // ── Step 5: a SECOND, freshly created, real-login member casts a real Like via a ──
                // ── real session -- a different member than the submitter, proving castReaction ──────
                // ── is genuinely open to any AKTIV member, not just the project's own author. ───────
                val likerEmail = "e2e2-liker-${Uuid.random()}@example.org"
                val likerId = createRealMember("E2E Scenario 2 Liker", likerEmail, password = E2E_STRONG_PASSWORD)
                createdMemberIds += likerId
                val likerToken = client.realLogin(likerEmail, E2E_STRONG_PASSWORD)
                client.post("/e2e2/cast-reaction/$projectId/LIKE") { withSession(likerToken) }.bodyAsText() shouldBe "LIKE"

                // ── Step 6: V0.1 seam -- two fresh, scenario-private members fund the EUR pool via ──
                // ── real, TREASURER-generated-and-paid contributions on a scenario-private tier. ──────
                // ── See class KDoc for why these are NOT the staker/liker, and why the tier is ────────
                // ── scenario-private (same tier-isolation discipline Scenario 1's KDoc documents). ──
                val payer1 = createRealMember("E2E Scenario 2 Payer One", "e2e2-payer1-${Uuid.random()}@example.org")
                val payer2 = createRealMember("E2E Scenario 2 Payer Two", "e2e2-payer2-${Uuid.random()}@example.org")
                createdMemberIds += payer1
                createdMemberIds += payer2

                val createTierResponse =
                    client.post("/e2e2/create-tier") { header("X-Member-Id", TREASURER_ID) }.bodyAsText().split(":")
                val tierId = createTierResponse[0]
                val tierAmount = BigDecimal(createTierResponse[1])
                createdMembershipTierIds += Uuid.parse(tierId)
                tierAmount.compareTo(BigDecimal("12.50")) shouldBe 0

                transaction {
                    MemberTable.update({ MemberTable.id inList listOf(payer1, payer2) }) {
                        it[membershipTierId] = Uuid.parse(tierId)
                    }
                }
                val generatedCount =
                    client.post("/e2e2/generate-contributions/$tierId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                // Exactly the two payers this scenario just assigned to its own private tier --
                // proof the tier isolation holds (no seeded member is on this tier).
                generatedCount shouldBe "2"

                suspend fun contributionIdFor(memberId: Uuid): String =
                    client
                        .get("/e2e2/contribution-for/$memberId") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                        .split(",")
                        .single()
                        .substringBefore("=")

                val contribution1 = contributionIdFor(payer1)
                val contribution2 = contributionIdFor(payer2)

                val paid1 =
                    client
                        .post("/e2e2/mark-paid/$contribution1/5?amount=$tierAmount") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                val paid2 =
                    client
                        .post("/e2e2/mark-paid/$contribution2/20?amount=$tierAmount") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                paid1 shouldBe "PAID:$tierAmount"
                paid2 shouldBe "PAID:$tierAmount"

                // ── Step 7: cross-domain payoff -- computeMonthlyDistribution's amountEur must equal ─
                // ── EXACTLY (sum of the two REAL paid contributions) minus (the fixed per-payer ────────
                // ── platform deduction, 2.00 EUR, times 2 distinct payers) -- not merely ">0". The ────
                // ── 2.00 figure mirrors CrowdfundingService's own private MIN_PLATFORM_CONTRIBUTION_EUR ─
                // ── constant (duplicated here as a literal, same practice CrowdfundingServiceTest's ───
                // ── own "computeMonthlyDistribution" case already uses, since the constant is private ──
                // ── and not exposed to tests). Only ONE project (this scenario's own) has a positive ──
                // ── basket at this point, so it receives the WHOLE pool, not a proportional share -- ───
                // ── the apportionment math is exercised elsewhere (CrowdfundingServiceTest's own ────────
                // ── multi-project case); this scenario's payoff is the cross-domain wiring, not the ────
                // ── apportionment algorithm itself. ─────────────────────────────────────────────────
                val platformDeductionPerPayer = BigDecimal("2.00")
                val expectedPoolEur = (tierAmount + tierAmount) - (platformDeductionPerPayer + platformDeductionPerPayer)

                // Captured immediately before the distribution runs -- see step 9's assertion for
                // what this pins down. Nothing else writes a JournalEntry between here and there
                // (Kotest runs Specs sequentially in this JVM), so a global count is deterministic.
                val journalEntryCountBeforeDistribution = journalEntryCount()

                val firstRun =
                    client
                        .post("/e2e2/compute-distribution/2027-02-01/2027-02-28") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                val firstEntries = firstRun.split(";").filter { it.isNotBlank() }
                firstEntries.size shouldBe 1
                val (distProjectId, distAmount) = firstEntries.single().split("=")
                distProjectId shouldBe projectId
                BigDecimal(distAmount).compareTo(expectedPoolEur) shouldBe 0

                // ── Step 8: idempotent re-run -- identical result, no duplicate row. ─────────────────
                val secondRun =
                    client
                        .post("/e2e2/compute-distribution/2027-02-01/2027-02-28") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                secondRun shouldBe firstRun
                val distributionRowCount =
                    transaction {
                        CrowdfundingDistributionTable
                            .selectAll()
                            .where {
                                (CrowdfundingDistributionTable.projectId eq Uuid.parse(projectId)) and
                                    (CrowdfundingDistributionTable.periodStart eq LocalDate(2027, 2, 1)) and
                                    (CrowdfundingDistributionTable.periodEnd eq LocalDate(2027, 2, 28))
                            }.count()
                    }
                distributionRowCount shouldBe 1L

                // ── Step 9: the wave's OWN documented, deliberate scope cut, asserted as current ─────
                // ── behavior rather than silently "fixed" -- see the CHANGELOG's "Known ──────────────
                // ── limitations". computeMonthlyDistribution writes a decision/ALLOCATION record ──────
                // ── only (the CrowdfundingDistributionTable rows asserted above); it calls no ─────────
                // ── accounting-posting path at all, so the EUR transfer that allocation implies is ────
                // ── never booked into the GoBD-audited ledger by this method. Pinning that down here ──
                // ── is what makes the limitation a CHARACTERIZED behavior instead of a prose claim: ───
                // ── if a later wave wires up automatic posting (a real product decision, deliberately ─
                // ── out of scope for a test wave), this assertion fails and forces the CHANGELOG's ────
                // ── "Known limitations" entry to be revisited, rather than letting it silently rot. ───
                journalEntryCount() shouldBe journalEntryCountBeforeDistribution
            }
        }
    })

/**
 * Total [JournalEntryTable] row count across the whole database -- deliberately UNSCOPED.
 * `computeMonthlyDistribution` carries no member/project attribution into accounting at all (that is
 * precisely the documented scope cut step 9 asserts), so there is no narrower handle to scope this
 * by: a global before/after comparison is the only way to prove the method posted *nothing anywhere*
 * rather than merely "nothing attributable to this scenario".
 */
private fun journalEntryCount(): Long = transaction { JournalEntryTable.selectAll().count() }
