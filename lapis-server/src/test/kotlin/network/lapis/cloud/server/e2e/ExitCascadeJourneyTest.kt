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
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.CrowdfundingService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.RegistrationInput
import org.jetbrains.exposed.v1.core.SortOrder
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
 * Scenario 5 of the V1.0 end-to-end integration test wave -- see the wave's CHANGELOG entry and
 * [E2eSupport] KDoc for the shared "real, fully-wired `module()` + real login/session + throwaway
 * RPC test routes on top" idiom every scenario in this package uses.
 *
 * Crosses V0.7 (real self-registration + real login + board rejection/approval + self-service
 * exit + live session-revocation) -> V0.6/V0.2 (continued LTR/Governance lockout after exit,
 * proven with BOTH the caller's own dead session AND the H2-only `X-Member-Id` trusted-header
 * fallback) -> V0.3/V0.5 (GoBD historical-record integrity: a former member's accounting history
 * and the audit hash chain covering it must survive that member's own exit unchanged) in one
 * continuous, real-HTTP-driven story.
 *
 * **Two independent live-session-revocation call sites, proven with genuinely live sessions, not
 * hand-inserted [network.lapis.cloud.server.db.generated.SessionTable] rows.** Applicant B's
 * session is obtained via a REAL [network.lapis.cloud.server.routes.registerAuthRoutes] login
 * BEFORE the board's decision, so [RegistrationService.rejectApplication]'s
 * `SessionStore.revokeAllForMember` call (closes the V0.7.2-audit-disclosed session-hygiene gap,
 * commit 95c29cf) is exercised against a session that really was live at rejection time -- not a
 * session that could never have existed in the first place. Applicant A's exit mirrors Scenario
 * 4's [RegistrationService.leaveMembership] trace exactly (see that class's KDoc "Deviation from
 * the wave plan" for the full two-independent-layers explanation this scenario relies on without
 * re-deriving): `leaveMembership` revokes EVERY one of the caller's own live sessions, including
 * the one that just called it, so a replay of A's session against ANY RPC call -- LTR or
 * Governance alike -- throws `UnauthenticatedException` (401) at the authentication layer, before
 * any [network.lapis.cloud.server.rpc.requireActiveMembership] authorization check is ever
 * reached.
 *
 * **Why the `X-Member-Id` fallback assertion is a genuinely different, additional proof, not a
 * restatement of the session-replay assertion above.** [network.lapis.cloud.server.security
 * .resolveFromTrustedHeader] performs NO [MemberStatus] check at all -- it resolves whatever
 * `role` [network.lapis.cloud.server.db.generated.AccountTable] currently holds for the header's
 * id, unconditionally. Sending `X-Member-Id: <A>` therefore lets A's identity resolve
 * successfully even after exit (this is expected -- `resolveCurrentMember`'s job is authentication,
 * not authorization). The gate that actually has to hold here is
 * [network.lapis.cloud.server.rpc.requireActiveMembership], re-reading A's LIVE
 * [network.lapis.cloud.server.db.generated.MemberTable.status] inside
 * [CrowdfundingService.submitProject]'s own transaction and throwing `ForbiddenException` (403,
 * not 401) because that status is now [MemberStatus.AUSGETRETEN]. A 403 (not a 200, and not a
 * fallback-triggered 401) is therefore the one outcome that discriminates "the AUSGETRETEN gate
 * holds via the fallback authentication path too" from "the fallback path bypasses it entirely".
 *
 * **THE PAYOFF (GoBD): exit does not retroactively alter or break A's own prior accounting
 * history or the audit hash chain covering it.** A's donation `JournalEntry` is posted, and the
 * chain-segment watermark is taken, BEFORE A calls `leaveMembership`; every assertion against that
 * entry and that chain segment runs AFTER A's exit has committed -- so a regression that let exit
 * silently mutate, detach, or corrupt a former member's own prior postings (e.g. nulling
 * `donor_member_id`, which [network.lapis.cloud.server.audit.AuditHashChain.canonicalPayload]
 * folds into the row's `entry_hash` -- see [hardDeleteGovernanceAndMembershipFixtures] KDoc for
 * why THIS scenario's own `afterSpec` is careful never to do that) would be caught here, not just
 * asserted away as "obviously fine".
 */
class ExitCascadeJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdMembershipTierIds = mutableListOf<Uuid>()
        val createdProjectIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // Crowdfunding rows first -- mirrors Scenario 2's own cleanup order (distribution/
                // reaction before the project row they reference, project before the member that
                // submitted it is retired).
                if (createdProjectIds.isNotEmpty()) {
                    CrowdfundingDistributionTable.deleteWhere { CrowdfundingDistributionTable.projectId inList createdProjectIds }
                    CrowdfundingReactionTable.deleteWhere { CrowdfundingReactionTable.projectId inList createdProjectIds }
                    CrowdfundingProjectTable.deleteWhere { CrowdfundingProjectTable.id inList createdProjectIds }
                }
                // Retires A and B (never deletes -- see hardDeleteGovernanceAndMembershipFixtures
                // KDoc) and, as a side effect, deletes A's Contribution/LtrLedgerEntry/Session rows
                // and the JournalEntry/Posting rows this scenario posted with donorMemberId = A.
                hardDeleteGovernanceAndMembershipFixtures(committeeIds = emptyList(), memberIds = createdMemberIds)
                if (createdMembershipTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdMembershipTierIds }
                }
            }
        }

        test(
            "real rejection revokes B's live session -> real approval+exit for A -> A's dead session " +
                "is locked out of BOTH LTR and Governance -> the X-Member-Id fallback still enforces " +
                "the AUSGETRETEN gate -> A's real accounting history and the GoBD hash chain survive " +
                "A's own exit unchanged",
        ) {
            testApplication {
                application {
                    module()
                    routing {
                        post("/e2e5/register") {
                            RegistrationService(call = call, registrationRateLimiter = LoginRateLimiter()).registerApplication(
                                RegistrationInput(
                                    displayName = call.request.queryParameters["name"]!!,
                                    email = call.request.queryParameters["email"]!!,
                                    password = E2E_STRONG_PASSWORD,
                                    agreementVersion = MembershipAgreementDisclaimer.VERSION,
                                    agreementSha256 = MembershipAgreementDisclaimer.SHA256,
                                ),
                            )
                            call.respondText("OK")
                        }
                        post("/e2e5/reject/{id}") {
                            val dto =
                                RegistrationService(call = call, registrationRateLimiter = LoginRateLimiter()).rejectApplication(
                                    memberId = call.parameters["id"]!!,
                                    reason = "E2E Scenario 5 -- deliberate rejection",
                                )
                            call.respondText(dto.status.name)
                        }
                        post("/e2e5/approve/{id}") {
                            val dto =
                                RegistrationService(
                                    call = call,
                                    registrationRateLimiter = LoginRateLimiter(),
                                ).approveApplication(call.parameters["id"]!!)
                            call.respondText(dto.status.name)
                        }
                        post("/e2e5/leave-membership") {
                            val dto = RegistrationService(call = call, registrationRateLimiter = LoginRateLimiter()).leaveMembership()
                            call.respondText(dto.status.name)
                        }
                        // Session-only-gated (resolveCurrentMember, no requireActiveMembership, no
                        // requireRole) -- the "LTR: Unauthenticated" probe against a dead session.
                        get("/e2e5/my-balance") {
                            call.respondText(LtrLedgerService(call = call).getMyBalance().freeBalanceLtr.toString())
                        }
                        // Session-only-gated (resolveCurrentMember, no requireActiveMembership, no
                        // requireRole) -- the "Governance: can't authenticate" probe against the SAME
                        // dead session, proving the lockout is not LTR-specific.
                        get("/e2e5/list-committees") {
                            GovernanceService(call = call).listCommittees(activeOnly = true)
                            call.respondText("OK")
                        }
                        post("/e2e5/mint-ltr/{memberId}") {
                            LtrLedgerService(call = call).mintLtr(
                                MintLtrInput(
                                    memberId = call.parameters["memberId"]!!,
                                    // Comfortably covers the 60.00 stake below (mirrors Scenario 2's
                                    // own mint/stake split -- see its class KDoc for why 60.00 is a
                                    // safe stake weight against every other Spec in this shared DB).
                                    amountLtr = BigDecimal("75.00"),
                                    note = "E2E Scenario 5 Startguthaben",
                                ),
                            )
                            call.respondText("OK")
                        }
                        get("/e2e5/member-balance/{memberId}") {
                            val dto = LtrLedgerService(call = call).getMemberBalance(call.parameters["memberId"]!!)
                            call.respondText(dto.freeBalanceLtr.toString())
                        }
                        // requireActiveMembership-gated (CrowdfundingService.submitProject), no
                        // requireRole beyond that -- used BOTH for A's genuine real-session stake
                        // (while AKTIV) AND for the post-exit X-Member-Id fallback 403 probe (see
                        // class KDoc). requireActiveMembership runs FIRST inside the transaction, so
                        // the ForbiddenException fires before the entry-hurdle/balance checks are
                        // ever reached -- the weight value below is irrelevant to that outcome.
                        post("/e2e5/submit-project") {
                            val p =
                                CrowdfundingService(call = call).submitProject(
                                    CrowdfundingProjectInput(
                                        title = "E2E-Scenario-5-Projekt",
                                        description = "E2E Scenario 5",
                                        initialWeightLtr = BigDecimal("60.00"),
                                    ),
                                )
                            call.respondText("${p.id}:${p.status}")
                        }
                        post("/e2e5/create-tier") {
                            val tier =
                                ContributionService(call).createMembershipTier(
                                    MembershipTierInput(
                                        name = "E2E-Journey-5-Beitragsstufe",
                                        description = "Scenario-private tier -- see the tier-isolation comment in Scenario 1/2",
                                        contributionAmount = BigDecimal("15.00"),
                                        billingInterval = BillingInterval.MONTHLY,
                                    ),
                                )
                            call.respondText(tier.id)
                        }
                        post("/e2e5/generate-contributions/{tierId}") {
                            val count =
                                ContributionService(call).generateContributionsForPeriod(
                                    membershipTierId = call.parameters["tierId"]!!,
                                    periodStart = LocalDate(2027, 3, 1),
                                    periodEnd = LocalDate(2027, 3, 31),
                                )
                            call.respondText(count.toString())
                        }
                        get("/e2e5/contribution-for/{memberId}") {
                            val list = ContributionService(call).listContributions(memberId = call.parameters["memberId"]!!)
                            call.respondText(list.joinToString(",") { "${it.id}=${it.amountDue}" })
                        }
                        post("/e2e5/mark-paid/{contributionId}") {
                            val dto =
                                ContributionService(call).markContributionPaid(
                                    contributionId = call.parameters["contributionId"]!!,
                                    paidAt = LocalDateTime(2027, 3, 15, 12, 0),
                                    paidAmount = BigDecimal(call.request.queryParameters["amount"]!!),
                                    note = "E2E Scenario 5",
                                )
                            call.respondText("${dto.status.name}:${dto.paidAmount}")
                        }
                        get("/e2e5/ledger-account/{accountNumber}") {
                            val accounts = AccountingService(call).listLedgerAccounts()
                            val account = accounts.single { it.accountNumber == call.parameters["accountNumber"]!! }
                            call.respondText(account.id)
                        }
                        post("/e2e5/post-journal-entry") {
                            val q = call.request.queryParameters
                            val amount = BigDecimal(q["amount"]!!)
                            val entry =
                                AccountingService(call).postJournalEntry(
                                    JournalEntryInput(
                                        entryDate = LocalDate(2027, 3, 15),
                                        description = "Journey-5-Spende",
                                        voucherReference = "E2E-5",
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
                                        // donorCategory only accompanies a set donorMemberId --
                                        // AccountingService.requireDonorMutualExclusionAndCategory
                                        // rejects a category with no donor/externalDonorId. Omitting
                                        // the query param altogether (not passed at all) is how this
                                        // route also posts a plain, non-donor entry -- see the "neutral
                                        // anchor entry" step in the test body.
                                        donorMemberId = q["donorMemberId"],
                                        donorCategory = q["donorMemberId"]?.let { DonorCategory.GERMAN_NATURAL_PERSON },
                                    ),
                                )
                            call.respondText("${entry.id}:${entry.status}:${creditTotal(entry)}")
                        }
                        get("/e2e5/journal-for/{memberId}") {
                            val list = AccountingService(call).listJournal(donorMemberId = call.parameters["memberId"]!!)
                            call.respondText(
                                list.joinToString(",") { "${it.id}:${it.status}:${it.donorCategory}:${creditTotal(it)}" },
                            )
                        }
                        get("/e2e5/verify-chain") {
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

                // Sequence-number watermark taken BEFORE this scenario writes anything -- same
                // discipline Scenario 1's own chain-verification step documents (all ~1100 tests
                // share one H2 database per JVM; scoping above a watermark makes the verification
                // independent of Spec execution order without weakening it).
                val chainWatermark = latestAuditSequenceNumber()

                // ── Step 1: two REAL self-registrations, both ANTRAG ─────────────────────────────
                val emailA = "journey-five-applicant-a-${Uuid.random()}@example.org"
                val emailB = "journey-five-applicant-b-${Uuid.random()}@example.org"
                client.post("/e2e5/register?email=$emailA&name=${APPLICANT_A_NAME}").status shouldBe HttpStatusCode.OK
                client.post("/e2e5/register?email=$emailB&name=${APPLICANT_B_NAME}").status shouldBe HttpStatusCode.OK
                val applicantAId = memberIdByEmail(emailA)
                val applicantBId = memberIdByEmail(emailB)
                createdMemberIds += applicantAId
                createdMemberIds += applicantBId
                memberStatusOf(applicantAId) shouldBe MemberStatus.ANTRAG
                memberStatusOf(applicantBId) shouldBe MemberStatus.ANTRAG

                // ── Step 2: both real logins (real HTTP, real session cookies) -- ANTRAG can log in ──
                val rawTokenA = client.realLogin(email = emailA, password = E2E_STRONG_PASSWORD)
                val rawTokenB = client.realLogin(email = emailB, password = E2E_STRONG_PASSWORD)

                // ── Step 3: BOARD rejects B -- a session that really was live at rejection time ────
                val rejected = client.post("/e2e5/reject/$applicantBId") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                rejected.bodyAsText() shouldBe MemberStatus.ABGELEHNT.name
                memberStatusOf(applicantBId) shouldBe MemberStatus.ABGELEHNT

                // ── Step 4: the v0.9.0 fix -- B's session, genuinely live a moment ago, now fails ──
                // ── on replay across BOTH domains, closing the V0.7.2-audit-disclosed session- ─────
                // ── hygiene gap (commit 95c29cf). ─────────────────────────────────────────────────
                client.get("/e2e5/my-balance") { withSession(rawTokenB) }.status shouldBe HttpStatusCode.Unauthorized
                client.get("/e2e5/list-committees") { withSession(rawTokenB) }.status shouldBe HttpStatusCode.Unauthorized

                // ── Step 5: BOARD approves A (a completely separate transaction) -- ANTRAG -> AKTIV ──
                val approved = client.post("/e2e5/approve/$applicantAId") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                approved.bodyAsText() shouldBe MemberStatus.AKTIV.name
                memberStatusOf(applicantAId) shouldBe MemberStatus.AKTIV

                // ── Step 6: A accumulates real cross-domain history, all BEFORE exit ─────────────
                // LTR minted, then staked into a real Crowdfunding project via A's OWN real session.
                client.post("/e2e5/mint-ltr/$applicantAId") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                    HttpStatusCode.OK
                val submitResponse = client.post("/e2e5/submit-project") { withSession(rawTokenA) }
                submitResponse.status shouldBe HttpStatusCode.OK
                val projectId = submitResponse.bodyAsText().substringBefore(":")
                createdProjectIds += Uuid.parse(projectId)
                // The stake actually landed -- balance dropped by exactly the staked weight,
                // read back through A's own real session while it is still live. 75.00 - 60.00.
                client.get("/e2e5/my-balance") { withSession(rawTokenA) }.bodyAsText() shouldBe "15.00"

                // A real, TREASURER-generated-and-paid contribution on a scenario-private tier (see
                // Scenario 1/2's own KDoc for why the tier must be scenario-private).
                val tierId = client.post("/e2e5/create-tier") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                createdMembershipTierIds += Uuid.parse(tierId)
                transaction {
                    MemberTable.update({ MemberTable.id eq applicantAId }) {
                        it[membershipTierId] = Uuid.parse(tierId)
                    }
                }
                val generatedCount =
                    client.post("/e2e5/generate-contributions/$tierId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                generatedCount shouldBe "1"
                val contributionId =
                    client
                        .get("/e2e5/contribution-for/$applicantAId") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                        .substringBefore("=")
                client
                    .post("/e2e5/mark-paid/$contributionId?amount=15.00") { header("X-Member-Id", TREASURER_ID) }
                    .bodyAsText() shouldBe "PAID:15.00"

                // A real accounting JournalEntry attributing a donation to A -- the artifact whose
                // survival across A's own exit this scenario's payoff assertion checks.
                val bankAccountId =
                    client.get("/e2e5/ledger-account/18000") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                val incomeAccountId =
                    client.get("/e2e5/ledger-account/40000") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()

                // A plain, non-donor "neutral anchor" entry, posted FIRST -- this scenario's own
                // audit-log-covered writes ([AuditEntityType.JOURNAL_ENTRY] is the only one of the
                // four entity types `14-audit-log.kuml.kts` covers that this story touches at all,
                // since no Vote/Resolution or Committee seat is involved) would otherwise be exactly
                // ONE row, which makes the anchored-window technique below (drop the window's own
                // first row as the anchor, verify the rest -- same discipline Scenario 1's own chain
                // step documents) checkedCount == 0: a VACUOUS pass that would not actually exercise
                // the hash link covering A's donation entry. This neutral entry exists purely to give
                // the window a second, genuine row so the verified segment actually spans A's own
                // posting.
                client
                    .post(
                        "/e2e5/post-journal-entry?bankAccountId=$bankAccountId&incomeAccountId=$incomeAccountId&amount=1.00",
                    ) { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.OK

                val postedEntry =
                    client
                        .post(
                            "/e2e5/post-journal-entry?bankAccountId=$bankAccountId" +
                                "&incomeAccountId=$incomeAccountId&donorMemberId=$applicantAId&amount=$DONATION_AMOUNT",
                        ) { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                val postedEntryId = postedEntry.substringBefore(":")
                postedEntry shouldBe "$postedEntryId:${JournalEntryStatus.POSTED.name}:$DONATION_AMOUNT"

                // A's real, TREASURER-readable balance, captured BEFORE exit, so the post-exit read
                // below (step 9) can assert it is genuinely UNCHANGED, not merely "still readable".
                val balanceBeforeExit =
                    client.get("/e2e5/member-balance/$applicantAId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                balanceBeforeExit shouldBe "15.00"

                // ── Step 7: A's self-service exit -- the SAME session live since step 2. ────────
                val leftResponse = client.post("/e2e5/leave-membership") { withSession(rawTokenA) }
                leftResponse.status shouldBe HttpStatusCode.OK
                leftResponse.bodyAsText() shouldBe MemberStatus.AUSGETRETEN.name
                memberStatusOf(applicantAId) shouldBe MemberStatus.AUSGETRETEN

                // ── Step 8: continued lockout, A's OWN dead session, across MULTIPLE domains -- ──
                // ── mirrors Scenario 4's traced 401 (session revoked before any authorization ─────
                // ── gate is reached, see class KDoc). ─────────────────────────────────────────────
                client.get("/e2e5/my-balance") { withSession(rawTokenA) }.status shouldBe HttpStatusCode.Unauthorized
                client.get("/e2e5/list-committees") { withSession(rawTokenA) }.status shouldBe HttpStatusCode.Unauthorized

                // ── Step 9: the H2-only X-Member-Id=A trusted-header fallback -- see class KDoc ──
                // ── "Why the X-Member-Id fallback assertion is a genuinely different, additional ──
                // ── proof": resolution succeeds (no exception), but requireActiveMembership re- ────
                // ── reads A's LIVE, now-AUSGETRETEN status and refuses with 403, NOT bypassed. ─────
                val fallbackAttempt = client.post("/e2e5/submit-project") { header("X-Member-Id", applicantAId.toString()) }
                fallbackAttempt.status shouldBe HttpStatusCode.Forbidden
                // Only the ONE project from step 6 exists for A -- the refusal was a genuine refusal,
                // not a silently-half-applied second project.
                createdProjectCountFor(applicantAId) shouldBe 1L

                // ── Step 10: a privileged TREASURER read shows A's real historical LTR balance ──
                // ── UNCHANGED by exit -- GoBD: history survives exit. ─────────────────────────────
                client.get("/e2e5/member-balance/$applicantAId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText() shouldBe
                    balanceBeforeExit

                // ── Step 11: THE PAYOFF -- A's real JournalEntry (V0.3), correctly attributed, ──
                // ── unchanged, still POSTED, is still returned via AccountingService's own ─────────
                // ── independent read path after A's exit. ─────────────────────────────────────────
                val journalForA =
                    client.get("/e2e5/journal-for/$applicantAId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                journalForA shouldBe
                    "$postedEntryId:${JournalEntryStatus.POSTED.name}:${DonorCategory.GERMAN_NATURAL_PERSON}:$DONATION_AMOUNT"

                // ── Step 12: THE PAYOFF -- the GoBD audit hash chain covering A's own prior ────────
                // ── postings still verifies AFTER A's exit -- concrete, mechanical proof that exit ─
                // ── does not retroactively alter or break the chain. Same anchored-window ──────────
                // ── discipline Scenario 1's own chain-verification step documents (drop this ───────
                // ── scenario's own first row as the window's anchor, so the result is independent ──
                // ── of Spec execution order in this ~1100-test shared-database suite). ─────────────
                val ownSequenceNumbers = auditSequenceNumbersAbove(chainWatermark)
                (ownSequenceNumbers.size >= 2) shouldBe true
                val anchorSequenceNumber = ownSequenceNumbers.first()
                val lastSequenceNumber = ownSequenceNumbers.last()
                val verifyResult =
                    client
                        .get("/e2e5/verify-chain?from=${anchorSequenceNumber + 1}&to=$lastSequenceNumber") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                verifyResult shouldBe "true::${ownSequenceNumbers.size - 1}"
            }
        }
    })

private const val APPLICANT_A_NAME = "Journey Five Applicant A"
private const val APPLICANT_B_NAME = "Journey Five Applicant B"

/** Deliberately distinct from the paid contribution's amount (15.00) -- this models a genuine donation, not a restatement of the membership fee. */
private const val DONATION_AMOUNT = "42.00"

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

private fun memberIdByEmail(email: String): Uuid =
    transaction {
        MemberTable.selectAll().where { MemberTable.email eq email }.single()[MemberTable.id]
    }

private fun memberStatusOf(memberId: Uuid): MemberStatus =
    transaction {
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]
    }

private fun createdProjectCountFor(memberId: Uuid): Long =
    transaction {
        CrowdfundingProjectTable
            .selectAll()
            .where { CrowdfundingProjectTable.submitterMemberId eq memberId }
            .count()
    }
