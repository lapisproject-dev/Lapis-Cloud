package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MemberService
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogListQuery
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Scenario 1 of the V1.0 end-to-end integration test wave -- see the wave's CHANGELOG entry and
 * [E2eSupport] KDoc for the shared "real, fully-wired `module()` + real login/session + throwaway
 * RPC test routes on top" idiom every scenario in this package uses.
 *
 * Crosses SIX waves in one continuous, real-HTTP-driven story: V0.7 (self-registration + real
 * login + board approval) -> V0.6 (LTR minted to the applicant) -> V0.2 (a General-Assembly
 * Meritokratische Vote, refused while ANTRAG and accepted by the SAME session after approval) ->
 * V0.1/V0.3 (a membership contribution, marked paid, manually booked into accounting -- see "Real,
 * confirmed scope gap" below for why this is two separate calls, not one automatic posting) ->
 * V0.4 (a real Beitragsrechnung PDF generated from the just-paid contribution) -> V0.5 (the GoBD
 * audit hash chain records the vote's settlement Resolution and still verifies).
 *
 * **Real, confirmed scope gap found while researching this scenario (see V1.0 CHANGELOG "Known
 * limitations" -- flagged, not fixed, per the wave's own plan)**: [ContributionService
 * .markContributionPaid] does NOT post anything to [AccountingService] -- it only flips
 * [network.lapis.cloud.server.db.generated.ContributionTable.status] to `PAID`. The V0.1<->V0.3
 * seam a treasurer actually relies on in this codebase is two independent, manually-sequenced
 * calls (`markContributionPaid`, then a separate `postJournalEntry`), not one automatic posting --
 * this test reproduces exactly that real two-call idiom rather than asserting a feature that does
 * not exist.
 *
 * **A second real, confirmed gap**: there is no production RPC path that ever assigns
 * [network.lapis.cloud.server.db.generated.MemberTable.membershipTierId] after a member is
 * created -- [RegistrationService.registerApplication]/`createMemberDirect` both always insert
 * `membershipTierId = null`, and [network.lapis.cloud.shared.rpc.IMemberService] has no
 * "assign tier" method at all. This test sets it via a direct DB update, explicitly flagged as
 * test-only setup for a state the production RPC surface genuinely cannot reach today -- NOT
 * something this wave fixes (adding tier assignment is a real product decision, out of scope for
 * an E2E test wave).
 *
 * **A third confirmed gap (found by writing step 7)**: nothing in the schema links a
 * [network.lapis.cloud.server.db.generated.ContributionTable] row to the
 * [network.lapis.cloud.server.db.generated.JournalEntryTable] row that books it. The only member
 * attribution `journal_entry` carries at all is `donor_member_id`, which is the V0.5.1 §25-PartG
 * DONATION identity (it forces a [DonorCategory] alongside it) -- semantically a Spende marker,
 * not a Beitrag marker. Step 7 therefore has to characterize the booked membership fee as a
 * donation to be able to assert the V0.1 <-> V0.3 attribution seam at all. Flagged as a follow-up,
 * not fixed here: closing it is a schema change (a `contribution_id` FK on `journal_entry`, or a
 * dedicated Beitrag posting path), which is a product decision, not a test-wave decision.
 *
 * **Why the ANTRAG-gate assertions are shaped the way they are (step 4).** A plain "ANTRAG
 * applicant gets 403 on `castVoteBallot`" assertion proves nothing about the V0.9.0
 * `requireActiveMembership` gate: for a [CommitteeType.GENERAL_ASSEMBLY] Committee,
 * `eligibleMemberIds` is literally "all members with status AKTIV", so an ANTRAG caller is
 * refused with the very same [network.lapis.cloud.shared.rpc.ForbiddenException] -- and therefore
 * the very same HTTP 403 -- with or without that gate. Two things make step 4 actually
 * discriminating: (a) the applicant is funded with real LTR BEFORE the refusal is asserted, so a
 * 403 cannot be an insufficient-balance artifact, and (b) the refusal is asserted a second time
 * against a NON-EXISTENT voteId. `requireActiveMembership` runs before the Vote row is looked up,
 * so the gate yields 403; without the gate, the lookup would be reached and would raise
 * `NotFoundException`, which `module()`'s StatusPages does NOT map (it maps only
 * Unauthenticated/Forbidden) and which therefore surfaces as 500. 403-vs-500 is the observable
 * difference that makes this assertion fail if the seam is broken.
 */
class MembershipToGovernanceJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMembershipTierIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                hardDeleteGovernanceAndMembershipFixtures(committeeIds = createdCommitteeIds, memberIds = createdMemberIds)
                // Safe unconditionally: contributions referencing these tiers are already gone
                // (hardDeleteGovernanceAndMembershipFixtures deleted them), and no member outside
                // this Spec was ever assigned to a scenario-private tier.
                if (createdMembershipTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdMembershipTierIds }
                }
            }
        }

        test(
            "real registration -> ANTRAG vote-gate holds -> board approval -> the SAME session can now vote -> " +
                "contribution paid+booked -> invoice PDF reflects it -> vote settlement recorded in the GoBD hash chain",
        ) {
            testApplication {
                application {
                    module()
                    routing {
                        post("/e2e1/register") {
                            RegistrationService(call = call, registrationRateLimiter = LoginRateLimiter()).registerApplication(
                                RegistrationInput(
                                    displayName = APPLICANT_DISPLAY_NAME,
                                    email = call.request.queryParameters["email"]!!,
                                    password = E2E_STRONG_PASSWORD,
                                    agreementVersion = MembershipAgreementDisclaimer.VERSION,
                                    agreementSha256 = MembershipAgreementDisclaimer.SHA256,
                                ),
                            )
                            call.respondText("OK")
                        }
                        post("/e2e1/approve/{id}") {
                            val dto =
                                RegistrationService(
                                    call = call,
                                    registrationRateLimiter = LoginRateLimiter(),
                                ).approveApplication(call.parameters["id"]!!)
                            call.respondText(dto.status.name)
                        }
                        post("/e2e1/mint-ltr/{memberId}") {
                            LtrLedgerService(call = call).mintLtr(
                                MintLtrInput(
                                    memberId = call.parameters["memberId"]!!,
                                    amountLtr = BigDecimal("25.00"),
                                    note = "E2E Scenario 1 Startguthaben",
                                ),
                            )
                            call.respondText("OK")
                        }
                        post("/e2e1/create-committee") {
                            val c =
                                GovernanceService(call = call).createCommittee(
                                    CommitteeInput(
                                        name = "Mitgliederversammlung (Journey 1)",
                                        type = CommitteeType.GENERAL_ASSEMBLY,
                                        description = "E2E Scenario 1",
                                        quorumPercent = 50,
                                    ),
                                )
                            call.respondText(c.id)
                        }
                        post("/e2e1/create-meeting/{committeeId}") {
                            val m =
                                GovernanceService(call = call).createMeeting(
                                    MeetingInput(
                                        committeeId = call.parameters["committeeId"]!!,
                                        title = "Journey-1-Meeting",
                                        scheduledAt = LocalDateTime(2026, 11, 20, 18, 0),
                                        location = "Vereinsheim",
                                        format = MeetingFormat.IN_PERSON,
                                    ),
                                )
                            call.respondText(m.id)
                        }
                        post("/e2e1/submit-motion/{committeeId}") {
                            val motion =
                                GovernanceService(call = call).submitMotion(
                                    MotionInput(
                                        targetCommitteeId = call.parameters["committeeId"]!!,
                                        title = "Journey-1-Antrag",
                                        rationale = "E2E Scenario 1",
                                        text = "Beschlusstext Journey 1",
                                    ),
                                )
                            call.respondText(motion.id)
                        }
                        post("/e2e1/review-motion/{id}") {
                            val m =
                                GovernanceService(
                                    call = call,
                                ).reviewMotion(id = call.parameters["id"]!!, decision = MotionReviewDecision.ACCEPT)
                            call.respondText(m.status.name)
                        }
                        post("/e2e1/schedule-motion/{id}/{meetingId}") {
                            val m =
                                GovernanceService(
                                    call = call,
                                ).scheduleMotion(id = call.parameters["id"]!!, meetingId = call.parameters["meetingId"]!!, position = 1)
                            call.respondText(m.status.name)
                        }
                        post("/e2e1/open-vote/{motionId}") {
                            val v = GovernanceService(call = call).openVote(VoteOpenInput(motionId = call.parameters["motionId"]!!))
                            val optionsStr = v.options.joinToString(";") { "${it.id}=${it.label}" }
                            call.respondText("${v.id}:$optionsStr")
                        }
                        post("/e2e1/cast-vote/{voteId}/{optionId}") {
                            val s =
                                GovernanceService(call = call).castVoteBallot(
                                    VoteBallotInput(
                                        voteId = call.parameters["voteId"]!!,
                                        optionId = call.parameters["optionId"]!!,
                                        stakeLtr = BigDecimal("5.00"),
                                    ),
                                )
                            call.respondText(s.id)
                        }
                        post("/e2e1/close-vote/{id}") {
                            val v = GovernanceService(call = call).closeVote(call.parameters["id"]!!)
                            // The whole Vickrey settlement outcome is carried out of the RPC layer,
                            // not just the resolutionId: `winnerOptionId`/`secondPriceLtr` are what
                            // computeVickreySettlement actually decided, and they are the only
                            // values through which step 9 can prove the applicant's ballot was
                            // counted rather than merely stored. Pipe-delimited because a
                            // BigDecimal's toString contains '.', and the id fields are UUIDs --
                            // neither can contain '|'.
                            call.respondText(
                                "${v.status}|${v.winnerOptionId ?: ""}|${v.secondPriceLtr ?: ""}|${v.resolutionId ?: ""}",
                            )
                        }
                        post("/e2e1/create-tier") {
                            val tier =
                                ContributionService(call).createMembershipTier(
                                    MembershipTierInput(
                                        name = "E2E-Journey-1-Beitragsstufe",
                                        description = "Scenario-private tier -- see the test's tier-isolation comment",
                                        contributionAmount = BigDecimal("10.00"),
                                        billingInterval = BillingInterval.MONTHLY,
                                    ),
                                )
                            call.respondText(tier.id)
                        }
                        post("/e2e1/generate-contributions/{tierId}") {
                            val count =
                                ContributionService(call).generateContributionsForPeriod(
                                    membershipTierId = call.parameters["tierId"]!!,
                                    periodStart = LocalDate(2026, 10, 1),
                                    periodEnd = LocalDate(2026, 10, 31),
                                )
                            call.respondText(count.toString())
                        }
                        get("/e2e1/contribution-for/{memberId}") {
                            val list = ContributionService(call).listContributions(memberId = call.parameters["memberId"]!!)
                            // amountDue/periodStart/periodEnd are carried out of the RPC layer (not
                            // just the id) so the test can assert the tier -> contribution ->
                            // payment -> accounting -> invoice-PDF chain against ONE amount that
                            // originates in ContributionService, instead of re-stating a hardcoded
                            // literal at each hop (which would make every downstream assertion
                            // tautological -- see step 7's comments).
                            call.respondText(
                                list.joinToString(",") {
                                    "${it.id}=${it.amountDue}=${it.periodStart}=${it.periodEnd}"
                                },
                            )
                        }
                        post("/e2e1/mark-paid/{contributionId}") {
                            val dto =
                                ContributionService(call).markContributionPaid(
                                    contributionId = call.parameters["contributionId"]!!,
                                    paidAt = LocalDateTime(2026, 10, 15, 12, 0),
                                    paidAmount = BigDecimal(call.request.queryParameters["amount"]!!),
                                    note = "E2E Scenario 1",
                                )
                            call.respondText("${dto.status.name}:${dto.paidAmount}")
                        }
                        get("/e2e1/ledger-account/{accountNumber}") {
                            val accounts = AccountingService(call).listLedgerAccounts()
                            val account = accounts.single { it.accountNumber == call.parameters["accountNumber"]!! }
                            call.respondText(account.id)
                        }
                        post("/e2e1/post-journal-entry") {
                            val q = call.request.queryParameters
                            // The amount is the one ContributionService itself computed for this
                            // member's contribution, threaded through the test -- NOT a literal.
                            val amount = BigDecimal(q["amount"]!!)
                            val entry =
                                AccountingService(call).postJournalEntry(
                                    JournalEntryInput(
                                        entryDate = LocalDate(2026, 10, 15),
                                        description = "Journey-1-Mitgliedsbeitrag",
                                        voucherReference = "E2E-1",
                                        postings =
                                            listOf(
                                                PostingInput(
                                                    ledgerAccountId = q["bankAccountId"]!!,
                                                    side = PostingSide.DEBIT,
                                                    amount = amount,
                                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                                ),
                                                PostingInput(
                                                    ledgerAccountId = q["incomeAccountId"]!!,
                                                    side = PostingSide.CREDIT,
                                                    amount = amount,
                                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                                ),
                                            ),
                                        // donorMemberId + donorCategory are the ONLY member
                                        // attribution `journal_entry` carries -- there is no
                                        // Contribution -> JournalEntry link anywhere in the schema
                                        // (see class KDoc "third confirmed gap"), so this is the
                                        // only handle the V0.1 <-> V0.3 seam can actually be
                                        // asserted through today. `postJournalEntry` rejects a
                                        // donorMemberId without a category (V0.5.1 §25 PartG
                                        // invariant, AccountingService
                                        // .requireDonorMutualExclusionAndCategory).
                                        donorMemberId = q["donorMemberId"],
                                        donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                                    ),
                                )
                            call.respondText("${entry.id}:${entry.status}:${creditTotal(entry)}")
                        }
                        get("/e2e1/journal-for/{memberId}") {
                            val list = AccountingService(call).listJournal(donorMemberId = call.parameters["memberId"]!!)
                            // status + donorCategory + credited total, so the read-back assertion
                            // can check the persisted VALUES, not merely that some row with the id
                            // the test itself just received comes back.
                            call.respondText(
                                list.joinToString(",") { "${it.id}:${it.status}:${it.donorCategory}:${creditTotal(it)}" },
                            )
                        }
                        post("/e2e1/update-address/{memberId}") {
                            MemberService(call).updateMemberAddress(
                                memberId = call.parameters["memberId"]!!,
                                street = "Musterstrasse 1",
                                postalCode = "38100",
                                city = "Braunschweig",
                                country = "DE",
                            )
                            call.respondText("OK")
                        }
                        get("/e2e1/audit-resolutions") {
                            val entries = AuditLogService(call).listAuditLog(AuditLogListQuery(entityType = AuditEntityType.RESOLUTION))
                            call.respondText(entries.joinToString(",") { it.entityId })
                        }
                        get("/e2e1/verify-chain") {
                            val q = call.request.queryParameters
                            val result =
                                AuditLogService(call).verifyChainIntegrity(
                                    fromSequenceNumber = q["from"]?.toLong(),
                                    toSequenceNumber = q["to"]?.toLong(),
                                )
                            call.respondText("${result.valid}:${result.brokenAtSequenceNumber ?: ""}:${result.checkedCount}")
                        }
                    }
                }

                // Sequence-number watermark taken BEFORE this scenario writes anything, so step 10's
                // chain verification can be scoped to exactly the rows this scenario produced. An
                // unscoped verifyChainIntegrity() would be order-dependent, not stronger: all ~1100
                // tests share one H2 database per JVM, and other Specs (e.g. AuditLogPersonalDataTest,
                // AuditLogServiceTest's deleted-row tamper case) legitimately delete audit rows in
                // their own cleanup, leaving sequence gaps this scenario must not be judged by. It
                // would also eventually trip AuditLogService's 10_000-row MAX_VERIFY_RANGE cap, which
                // raises an unmapped BadRequestException (HTTP 500). Scoping is the same discipline
                // AuditLogServiceTest's own KDoc documents for this genuinely global, single-chain table.
                val chainWatermark = latestAuditSequenceNumber()

                // ── Step 1: real self-registration (V0.7, unauthenticated RPC surface) ──────────
                // Randomized local part: the applicant row is retired, not deleted, in afterSpec
                // (see hardDeleteGovernanceAndMembershipFixtures KDoc -- the GoBD hash chain covers
                // actor_member_id), and `member.email` is UNIQUE, so a fixed address would make this
                // Spec non-rerunnable within one JVM and collision-prone against other Specs.
                val email = "journey-one-applicant-${Uuid.random()}@example.org"
                client.post("/e2e1/register?email=$email").status shouldBe HttpStatusCode.OK
                val applicantId =
                    transaction {
                        MemberTable.selectAll().where { MemberTable.email eq email }.single()[MemberTable.id]
                    }
                createdMemberIds += applicantId
                // The registration seam itself: a real applicant starts ANTRAG, not AKTIV.
                memberStatusOf(applicantId) shouldBe MemberStatus.ANTRAG

                // ── Step 2: real login (real HTTP, real session cookie) -- ANTRAG can log in by design ──
                val rawToken = client.realLogin(email = email, password = E2E_STRONG_PASSWORD)

                // ── Step 3: BOARD sets up the General-Assembly vote this applicant will (eventually) cast ──
                val committeeId = client.post("/e2e1/create-committee") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                val meetingId = client.post("/e2e1/create-meeting/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val motionId = client.post("/e2e1/submit-motion/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                // Both responses ARE asserted: the Motion status machine (SUBMITTED -> REVIEWED ->
                // SCHEDULED) is a precondition of openVote, and dropping these on the floor would
                // turn a 403/500 here into a confusing downstream failure several steps later.
                client.post("/e2e1/review-motion/$motionId") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe
                    MotionStatus.REVIEWED.name
                client
                    .post("/e2e1/schedule-motion/$motionId/$meetingId") { header("X-Member-Id", BOARD_ID) }
                    .bodyAsText() shouldBe MotionStatus.SCHEDULED.name
                val openResponse = client.post("/e2e1/open-vote/$motionId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val voteId = openResponse.substringBefore(":")
                val yesOptionId =
                    openResponse
                        .substringAfter(":")
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")

                // ── Step 4: cross-domain seam #1 -- V0.6 LTR economy funds the applicant FIRST, so ──
                // ── the refusals below cannot be an insufficient-balance artifact, then the REAL ──
                // ── login->session->RPC path proves the V0.9.0 ANTRAG membership gate holds. ─────
                // ── See the class KDoc for why the non-existent-voteId probe is the assertion that ─
                // ── actually discriminates "gate present" from "gate removed". ───────────────────
                client
                    .post("/e2e1/mint-ltr/$applicantId") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.OK
                ltrBalanceOf(applicantId) shouldBe BigDecimal("25.00")

                val forbiddenOnMissingVote =
                    client.post("/e2e1/cast-vote/${Uuid.random()}/${Uuid.random()}") { withSession(rawToken) }
                forbiddenOnMissingVote.status shouldBe HttpStatusCode.Forbidden

                val forbidden = client.post("/e2e1/cast-vote/$voteId/$yesOptionId") { withSession(rawToken) }
                forbidden.status shouldBe HttpStatusCode.Forbidden
                // The refusal must have been a refusal, not a silently-half-applied write.
                ballotCountFor(voteId = voteId, memberId = applicantId) shouldBe 0
                ltrBalanceOf(applicantId) shouldBe BigDecimal("25.00")

                // ── Step 5: BOARD approves (a DIFFERENT service, RegistrationService, in a ──
                // ── completely separate transaction) -- ANTRAG -> AKTIV ─────────────────────────
                val approved = client.post("/e2e1/approve/$applicantId") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                approved.bodyAsText() shouldBe "AKTIV"

                // ── Step 6: cross-domain seam #2 -- the SAME session cookie, no re-login, now ──
                // ── succeeds: the status gate re-reads live DB status per call rather than ──────
                // ── trusting a status captured at login time. ────────────────────────────────────
                val castResponse = client.post("/e2e1/cast-vote/$voteId/$yesOptionId") { withSession(rawToken) }
                castResponse.status shouldBe HttpStatusCode.OK
                ballotCountFor(voteId = voteId, memberId = applicantId) shouldBe 1
                // Cross-domain seam #3 (V0.2 -> V0.6): casting a Meritokratische ballot must DEBIT
                // the staked LTR through the ledger, not merely record a ballot row. 25.00 - 5.00.
                ltrBalanceOf(applicantId) shouldBe BigDecimal("20.00")
                voteStakeDebitCountFor(applicantId) shouldBe 1

                // ── Step 7: V0.1/V0.3 seam -- assign the tier (see class KDoc "second real, ──
                // ── confirmed gap"), generate + pay a contribution, then manually book it ───────
                //
                // Tier isolation (do NOT reuse DevSeedData.standardTierId here): all ~1100 tests
                // share one H2 database per JVM, and `generateContributionsForPeriod` writes a
                // Contribution row for EVERY member on the given tier. Every seeded demo member is
                // on `standardTierId`, so running it against that tier silently gives Amara/Boris/
                // Theresa/Max an extra contribution that this Spec's own cleanup (scoped to the
                // members it created) would never remove -- which then breaks any later Spec that
                // asserts a seeded member's contribution count, e.g. ServiceIntegrationTest's
                // "contribution lifecycle" case, in an order-dependent way. A scenario-private tier,
                // created through the REAL createMembershipTier RPC, makes that impossible by
                // construction: only members this scenario itself assigns to it can ever be swept up.
                val tierId =
                    client.post("/e2e1/create-tier") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                createdMembershipTierIds += Uuid.parse(tierId)
                transaction {
                    MemberTable.update({ MemberTable.id eq applicantId }) {
                        it[membershipTierId] = Uuid.parse(tierId)
                    }
                }
                val generatedCount =
                    client.post("/e2e1/generate-contributions/$tierId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                // Exactly one member is on this scenario's private tier -- proof the isolation holds.
                generatedCount shouldBe "1"
                val contributionFields =
                    client
                        .get("/e2e1/contribution-for/$applicantId") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                        .split(",")
                        .first()
                        .split("=")
                val contributionId = contributionFields[0]
                val contributionAmount = contributionFields[1]
                val periodStart = contributionFields[2]
                val periodEnd = contributionFields[3]
                contributionId.isBlank() shouldBe false
                // Seam V0.1 (tier) -> V0.1 (contribution generation): the generated contribution's
                // amountDue must come from the tier this scenario created through the REAL
                // createMembershipTier RPC. Asserting it here (rather than re-stating "10.00" at
                // every later hop) is what makes steps 7/8 below non-tautological: every downstream
                // assertion compares against THIS value, which the production code produced.
                contributionAmount shouldBe "10.00"

                val paidResult =
                    client
                        .post("/e2e1/mark-paid/$contributionId?amount=$contributionAmount") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                paidResult shouldBe "PAID:$contributionAmount"

                val bankAccountId =
                    client.get("/e2e1/ledger-account/18000") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                val incomeAccountId =
                    client.get("/e2e1/ledger-account/40000") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                val postedEntry =
                    client
                        .post(
                            "/e2e1/post-journal-entry?bankAccountId=$bankAccountId" +
                                "&incomeAccountId=$incomeAccountId&donorMemberId=$applicantId&amount=$contributionAmount",
                        ) { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                val postedEntryId = postedEntry.substringBefore(":")
                postedEntry shouldBe "$postedEntryId:${JournalEntryStatus.POSTED.name}:$contributionAmount"

                // Cross-domain seam #4: the SAME amount ContributionService computed (V0.1) is what
                // AccountingService's own INDEPENDENT read path (V0.3) reports as booked, attributed
                // to the applicant who registered in step 1. Asserting the full tuple -- id, status,
                // donor category and credited total -- rather than a `contains` on the id the test
                // itself already holds is what makes this fail if the posting engine dropped,
                // rounded or mis-attributed the amount.
                val journalForApplicant =
                    client.get("/e2e1/journal-for/$applicantId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                journalForApplicant shouldBe
                    "$postedEntryId:${JournalEntryStatus.POSTED.name}:" +
                    "${DonorCategory.GERMAN_NATURAL_PERSON}:$contributionAmount"

                // ── Step 8: V0.4 seam -- the invoice PDF is generated from the EXACT contribution ──
                // ── row a different service (ContributionService) just wrote ────────────────────
                // Asserted, not dropped: `generateBeitragsrechnung` calls `requireCompleteAddress`,
                // so a silently-failed address update would surface only as an unmapped
                // ConflictException (HTTP 500) on the PDF request three lines below -- a confusing
                // symptom for a cause that belongs here. Same discipline as steps 3's motion-status
                // assertions.
                client
                    .post("/e2e1/update-address/$applicantId") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK
                val pdfResponse =
                    client.get("/api/mailmerge/contributions/$contributionId/invoice.pdf") { header("X-Member-Id", TREASURER_ID) }
                pdfResponse.status shouldBe HttpStatusCode.OK
                pdfResponse.contentType()?.withoutParameters() shouldBe ContentType.Application.Pdf
                pdfResponse.bodyAsBytes().take(4).toByteArray() shouldBe "%PDF".toByteArray(Charsets.US_ASCII)
                // `%PDF` magic bytes alone prove only "some PDF was produced" -- they would pass
                // just as happily if the route had rendered a DIFFERENT member's contribution.
                // The V0.4 -> V0.1 seam that actually discriminates is the archive side effect:
                // generateBeitragsrechnung writes a Document row whose title is composed from the
                // billed MEMBER's display name and the CONTRIBUTION's own period, so asserting on
                // that exact title ties the generated PDF back to the applicant who registered in
                // step 1 and to the contribution ContributionService generated in step 7.
                // (This is the same archive assertion MailmergeRoutesTest makes, tightened from
                // "the document count went up" to "this specific document exists".)
                archivedInvoiceDocumentCount(
                    "Beitragsrechnung $APPLICANT_DISPLAY_NAME $periodStart - $periodEnd",
                ) shouldBe 1

                // ── Step 9: cross-domain seam #5 (V0.2 ballot -> V0.2.3 Vickrey settlement -> ────
                // ── V0.2.1 Resolution book) -- the applicant's ballot must actually DECIDE the ──
                // ── vote, not merely have been stored. ──────────────────────────────────────────
                //
                // Asserting only "a resolutionId came back" (the shape this step had before) is
                // vacuous: closeVote always writes a Resolution for an OPEN vote, including the
                // POSTPONED/undecided one it produces when it counts no ballots at all. So a
                // settlement that silently ignored the ballot cast in step 6 -- or attributed it to
                // the wrong option -- would pass unnoticed. The four values below are exactly the
                // ones computeVickreySettlement derives FROM the ballot, so they discriminate:
                //
                //  * winnerOptionId == the YES option the applicant staked on. If the ballot were
                //    dropped, every option would tie at 0, `topOptions.size != 1` would make the
                //    vote undecided, and this would come back empty (and the Resolution POSTPONED).
                //  * secondPriceLtr == 0.00 -- uncontested: NO drew no stake, so the winner pays
                //    nothing (VoteSettlement KDoc point 3). A non-zero value here would mean stake
                //    leaked in from somewhere other than this scenario's single ballot.
                //  * the ballot's settled_ltr == 0.00 -- the per-member charge apportioned from
                //    that same second price, and no longer null once the vote is CLOSED.
                //  * the Resolution's own tally/status/mode -- ADOPTED, 1 Yes / 0 No, and
                //    MERITOCRATIC rather than the COMMITTEE_QUORUM default, which is what proves
                //    the Vickrey path (not the headcount path) produced this Resolution.
                val closeFields =
                    client.post("/e2e1/close-vote/$voteId") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                closeFields[0] shouldBe VoteStatus.CLOSED.name
                closeFields[1] shouldBe yesOptionId
                closeFields[2] shouldBe "0.00"
                val resolutionId = closeFields[3]
                resolutionId.isBlank() shouldBe false
                ballotSettledLtrFor(voteId = voteId, memberId = applicantId) shouldBe BigDecimal("0.00")
                resolutionFactsOf(resolutionId) shouldBe
                    ResolutionFacts(
                        status = ResolutionStatus.ADOPTED,
                        votesYes = 1,
                        votesNo = 0,
                        mode = ResolutionMode.MERITOCRATIC,
                        voteId = Uuid.parse(voteId),
                    )

                // ── Step 10: V0.5 GoBD payoff -- the audit hash chain recorded the Resolution ──
                // ── closeVote (V0.2) just created, and this scenario's own chain segment verifies ─
                val auditResolutions =
                    client.get("/e2e1/audit-resolutions") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                (resolutionId in auditResolutions.split(",")) shouldBe true

                // Every audit row with a sequenceNumber above the watermark is one this scenario
                // produced (the chain-state counter is monotonic, so nothing older can appear
                // above it). The verification window deliberately DROPS this scenario's own first
                // row and uses it as the window's anchor instead: `verifyRows` resolves a window's
                // expected-previous-hash from the row at `first - 1`, and if that predecessor
                // belongs to some earlier Spec it may legitimately no longer exist -- both
                // AuditLogPersonalDataTest (deleteWhere) and AuditLogServiceTest's deleted-row
                // tamper case remove audit rows in this same shared database. Anchoring on a row
                // this scenario itself wrote (and which nothing deletes before the assertion runs)
                // makes the result independent of Spec execution order without weakening it: the
                // window still spans the Resolution closeVote just recorded and still verifies
                // every hash link across it.
                val ownSequenceNumbers = auditSequenceNumbersAbove(chainWatermark)
                // Guards against a vacuous pass: an empty (or single-row) window would verify
                // trivially, so require that this scenario genuinely appended a chain segment.
                (ownSequenceNumbers.size >= 2) shouldBe true
                val anchorSequenceNumber = ownSequenceNumbers.first()
                val lastSequenceNumber = ownSequenceNumbers.last()
                val verifyResult =
                    client
                        .get("/e2e1/verify-chain?from=${anchorSequenceNumber + 1}&to=$lastSequenceNumber") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                verifyResult shouldBe "true::${ownSequenceNumbers.size - 1}"
            }
        }
    })

/**
 * Shared between the registration input and step 8's archived-invoice-title assertion --
 * `generateBeitragsrechnung` composes the archived [DocumentTable] title from the billed member's
 * display name, so the two must not drift apart.
 */
private const val APPLICANT_DISPLAY_NAME = "Journey One Applicant"

/** Σ of the CREDIT-side postings of [entry] -- the amount the entry actually booked as income. */
private fun creditTotal(entry: JournalEntryDto): BigDecimal =
    entry.postings
        .filter { it.side == PostingSide.CREDIT }
        .fold(BigDecimal.ZERO.setScale(2)) { acc, posting -> acc + posting.amount }

private fun latestAuditSequenceNumber(): Long =
    transaction {
        AuditLogEntryTable
            .selectAll()
            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AuditLogEntryTable.sequenceNumber)
            ?: 0L
    }

/** Ascending sequence numbers of every audit row written after [watermark] -- i.e. this scenario's own chain segment. */
private fun auditSequenceNumbersAbove(watermark: Long): List<Long> =
    transaction {
        AuditLogEntryTable
            .selectAll()
            .where { AuditLogEntryTable.sequenceNumber greater watermark }
            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
            .map { it[AuditLogEntryTable.sequenceNumber] }
    }

/**
 * Number of NON-deleted archived documents carrying exactly [title] -- the Beitragsrechnung
 * archive row `generateBeitragsrechnung` writes as a side effect of the invoice-PDF route.
 * Read straight from the table rather than through `listDocuments`, because the row is archived at
 * [network.lapis.cloud.shared.domain.DocumentAccessLevel.ADMIN_ONLY] and this scenario's scene
 * partners are BOARD/TREASURER, not ADMIN.
 */
private fun archivedInvoiceDocumentCount(title: String): Long =
    transaction {
        DocumentTable
            .selectAll()
            .where { (DocumentTable.title eq title) and (DocumentTable.isDeleted eq false) }
            .count()
    }

/**
 * The Resolution facts step 9 asserts as ONE tuple -- a per-field assertion chain would let a
 * regression in an un-asserted field slip through silently, and the failure message for a tuple
 * mismatch names every field at once.
 */
private data class ResolutionFacts(
    val status: ResolutionStatus,
    val votesYes: Int,
    val votesNo: Int,
    val mode: ResolutionMode,
    val voteId: Uuid?,
)

/**
 * Read straight from [network.lapis.cloud.server.db.generated.ResolutionTable]: `resolutionMode`
 * and `voteId` are the two fields that distinguish a Vickrey-produced Resolution from the
 * pre-existing COMMITTEE_QUORUM headcount path, and both are persistence-level -- the point of the
 * assertion is what closeVote actually WROTE.
 */
private fun resolutionFactsOf(resolutionId: String): ResolutionFacts =
    transaction {
        val row =
            ResolutionTable
                .selectAll()
                .where { ResolutionTable.id eq Uuid.parse(resolutionId) }
                .single()
        ResolutionFacts(
            status = row[ResolutionTable.status],
            votesYes = row[ResolutionTable.votesYes],
            votesNo = row[ResolutionTable.votesNo],
            mode = row[ResolutionTable.resolutionMode],
            voteId = row[ResolutionTable.voteId],
        )
    }

/** The per-member Vickrey charge closeVote apportioned onto this member's ballot -- null while the Vote is still OPEN. */
private fun ballotSettledLtrFor(
    voteId: String,
    memberId: Uuid,
): BigDecimal? =
    transaction {
        VoteBallotTable
            .selectAll()
            .where {
                (VoteBallotTable.voteId eq Uuid.parse(voteId)) and (VoteBallotTable.memberId eq memberId)
            }.single()[VoteBallotTable.settledLtr]
    }

private fun memberStatusOf(memberId: Uuid): MemberStatus =
    transaction {
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]
    }

private fun ltrBalanceOf(memberId: Uuid): BigDecimal =
    transaction {
        LtrLedgerEntryTable
            .selectAll()
            .where { LtrLedgerEntryTable.memberId eq memberId }
            .fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[LtrLedgerEntryTable.amountLtr] }
    }

private fun voteStakeDebitCountFor(memberId: Uuid): Long =
    transaction {
        LtrLedgerEntryTable
            .selectAll()
            .where {
                (LtrLedgerEntryTable.memberId eq memberId) and
                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.VOTE_STAKE)
            }.count()
    }

private fun ballotCountFor(
    voteId: String,
    memberId: Uuid,
): Long =
    transaction {
        VoteBallotTable
            .selectAll()
            .where {
                (VoteBallotTable.voteId eq Uuid.parse(voteId)) and (VoteBallotTable.memberId eq memberId)
            }.count()
    }
