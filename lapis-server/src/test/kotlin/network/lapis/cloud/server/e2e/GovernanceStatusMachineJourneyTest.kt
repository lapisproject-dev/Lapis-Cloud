package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Scenario 4 of the V1.0 end-to-end integration test wave -- see the wave's CHANGELOG entry and
 * [E2eSupport] KDoc for the shared "real, fully-wired `module()` + real login/session + throwaway
 * RPC test routes on top" idiom every scenario in this package uses.
 *
 * Crosses V0.7 (real self-registration + real login + board approval + self-service exit) ->
 * V0.2 (the v0.9.0 `addCommitteeMember` status gate, a full non-General-Assembly Vote cycle, and
 * the v0.9.0-audit-disclosed "stale Committee seat" gap) in one continuous, real-HTTP-driven
 * story: a Committee-seat *refusal* while ANTRAG, the IDENTICAL call *succeeding* once approved,
 * a real ballot the newly-seated member casts using ONLY that Committee seat's eligibility (a
 * `WORKING_GROUP` Committee, not `GENERAL_ASSEMBLY` -- see [eligibleMemberIds
 * ][network.lapis.cloud.server.rpc.eligibleMemberIds] KDoc), self-service exit, and finally a
 * second vote-casting attempt from the now-`AUSGETRETEN` member's own prior session.
 *
 * **Deviation from the wave plan's literal expected HTTP status for the final step, found while
 * implementing this scenario -- documented here, not silently "fixed", because the ACTUAL
 * behavior traced below is a genuine, verified, STRONGER guarantee than the plan anticipated, not
 * a regression.** The plan expected the applicant's post-exit `castVoteBallot` replay to fail
 * with `403 Forbidden` (attributed to
 * [network.lapis.cloud.server.rpc.requireActiveMembership]'s live per-call status re-check).
 * Tracing the ACTUAL code shows this specific path can never reach that check at all:
 * [RegistrationService.leaveMembership] calls
 * `SessionStore.revokeAllForMember(current.memberId)` on EVERY live session belonging to the
 * caller -- including the very session that just called `leaveMembership` itself -- immediately
 * after the status flips to `AUSGETRETEN`. [network.lapis.cloud.server.security.resolveCurrentMember]
 * therefore already throws `UnauthenticatedException` (**401**, not 403) on any subsequent request
 * replaying that same cookie, for every RPC call, `castVoteBallot` included -- the authentication
 * layer forecloses the request before the [network.lapis.cloud.server.rpc.requireActiveMembership]
 * *authorization* gate is ever reached. And because [RegistrationService] never sets
 * `MemberStatus.WITHDRAWN` via ANY OTHER path in this codebase (grepped: `leaveMembership` is the
 * sole call site), there is no real, production-reachable way to observe a live session paired
 * with an `AUSGETRETEN` status at all -- the "stale Committee seat + live session" combination the
 * plan's 403 assertion was designed to probe does not actually exist as an attack surface for THIS
 * exit path. This is arguably a better outcome than the plan anticipated (two independent layers,
 * not one, block the replay), so nothing here is changed in production code -- this KDoc and the
 * assertion below simply record the verified reality in place of the plan's untraced assumption.
 *
 * **The "stale Committee seat" gap this scenario used to surface (previously documented, NOT fixed,
 * per the wave's original "flag, don't fix" instruction) is now CLOSED (V0.13.1)**:
 * [RegistrationService.leaveMembership] previously did NOT end the member's open
 * [network.lapis.cloud.server.db.generated.CommitteeMembershipTable] row, so `listCommitteeMembers
 * (activeOnly = true)` kept listing the now-`AUSGETRETEN` member as an active seat holder on the
 * Committee they were validly seated on while `AKTIV`. [RegistrationService.leaveMembership] (and,
 * symmetrically, `RegistrationService.rejectApplication`) now call the shared
 * `endAllOpenCommitteeMembershipsForMember` helper (extracted from
 * [GovernanceService.endCommitteeMembership]'s own per-row ending logic) inside the SAME transaction
 * as the status flip, so the member's open seat is ended (`until` set) atomically with `AKTIV ->
 * AUSGETRETEN`. Step 10 below now asserts the FIXED behavior through the REAL production read path
 * (`GovernanceService.listCommitteeMembers(activeOnly = true)`, via the `/e2e4/committee-members`
 * route) -- not merely through this file's local direct-DB helper, which does not reproduce
 * `listCommitteeMembers`' `innerJoin MemberTable`; observing through the characterized API is what
 * makes the assertion fail if the characterized behavior regresses. This was never separately
 * exploitable for voting even while the gap was open (the session-revocation-plus-authorization
 * double lock proven below holds regardless of the seat's state), but it WAS a real, live data-
 * inconsistency bug -- see the CHANGELOG `[Unreleased]` entry for the fix.
 */
class GovernanceStatusMachineJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                hardDeleteGovernanceAndMembershipFixtures(committeeIds = createdCommitteeIds, memberIds = createdMemberIds)
            }
        }

        test(
            "real registration -> ANTRAG Committee-seat refusal -> board approval -> the IDENTICAL " +
                "seat call now succeeds -> a real vote decided ONLY via that just-created seat -> " +
                "self-service exit -> the Committee seat is now closed on exit (V0.13.1 fix) -> " +
                "the exited member's OWN prior session can no longer vote at all",
        ) {
            testApplication {
                application {
                    module()
                    routing {
                        post("/e2e4/register") {
                            RegistrationService(
                                call = call,
                                registrationRateLimiter = LoginRateLimiter(),
                                friendRegistrationRateLimiter = LoginRateLimiter(),
                                friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                                friendVerificationMailer = FakeFriendVerificationMailer(),
                            ).registerApplication(
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
                        post("/e2e4/approve/{id}") {
                            val dto =
                                RegistrationService(
                                    call = call,
                                    registrationRateLimiter = LoginRateLimiter(),
                                    friendRegistrationRateLimiter = LoginRateLimiter(),
                                    friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                                    friendVerificationMailer = FakeFriendVerificationMailer(),
                                ).approveApplication(call.parameters["id"]!!)
                            call.respondText(dto.status.name)
                        }
                        post("/e2e4/leave-membership") {
                            val dto =
                                RegistrationService(
                                    call = call,
                                    registrationRateLimiter = LoginRateLimiter(),
                                    friendRegistrationRateLimiter = LoginRateLimiter(),
                                    friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                                    friendVerificationMailer = FakeFriendVerificationMailer(),
                                ).leaveMembership()
                            call.respondText(dto.status.name)
                        }
                        post("/e2e4/mint-ltr/{memberId}") {
                            LtrLedgerService(call = call).mintLtr(
                                MintLtrInput(
                                    memberId = call.parameters["memberId"]!!,
                                    amountLtr = BigDecimal("10.00"),
                                    note = "E2E Scenario 4 Startguthaben",
                                ),
                            )
                            call.respondText("OK")
                        }
                        post("/e2e4/create-committee") {
                            val c =
                                GovernanceService(call = call).createCommittee(
                                    CommitteeInput(
                                        name = "Arbeitsgruppe Journey 4",
                                        type = CommitteeType.WORKING_GROUP,
                                        description =
                                            "E2E Scenario 4 -- deliberately NOT General Assembly, " +
                                                "see class KDoc",
                                        quorumPercent = 50,
                                    ),
                                )
                            call.respondText(c.id)
                        }
                        post("/e2e4/add-committee-member/{committeeId}/{memberId}") {
                            val dto =
                                GovernanceService(call = call).addCommitteeMember(
                                    committeeId = call.parameters["committeeId"]!!,
                                    input =
                                        CommitteeMembershipInput(
                                            memberId = call.parameters["memberId"]!!,
                                            role = CommitteeRole.MEMBER,
                                            since = LocalDate(2026, 1, 1),
                                        ),
                                )
                            call.respondText(dto.id)
                        }
                        get("/e2e4/committee-members/{committeeId}") {
                            val activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: true
                            val list =
                                GovernanceService(
                                    call = call,
                                ).listCommitteeMembers(committeeId = call.parameters["committeeId"]!!, activeOnly = activeOnly)
                            call.respondText(list.joinToString(",") { "${it.memberId}=${it.until ?: "null"}" })
                        }
                        post("/e2e4/create-meeting/{committeeId}") {
                            val m =
                                GovernanceService(call = call).createMeeting(
                                    MeetingInput(
                                        committeeId = call.parameters["committeeId"]!!,
                                        title = "Journey-4-Meeting",
                                        scheduledAt = LocalDateTime(2026, 11, 20, 18, 0),
                                        location = "Vereinsheim",
                                        format = MeetingFormat.IN_PERSON,
                                    ),
                                )
                            call.respondText(m.id)
                        }
                        post("/e2e4/submit-motion/{committeeId}") {
                            val motion =
                                GovernanceService(call = call).submitMotion(
                                    MotionInput(
                                        targetCommitteeId = call.parameters["committeeId"]!!,
                                        title = "Journey-4-Antrag-${call.request.queryParameters["n"]}",
                                        rationale = "E2E Scenario 4",
                                        text = "Beschlusstext Journey 4",
                                    ),
                                )
                            call.respondText(motion.id)
                        }
                        post("/e2e4/review-motion/{id}") {
                            val m =
                                GovernanceService(
                                    call = call,
                                ).reviewMotion(id = call.parameters["id"]!!, decision = MotionReviewDecision.ACCEPT)
                            call.respondText(m.status.name)
                        }
                        post("/e2e4/schedule-motion/{id}/{meetingId}/{position}") {
                            val m =
                                GovernanceService(call = call).scheduleMotion(
                                    id = call.parameters["id"]!!,
                                    meetingId = call.parameters["meetingId"]!!,
                                    position = call.parameters["position"]!!.toInt(),
                                )
                            call.respondText(m.status.name)
                        }
                        post("/e2e4/open-vote/{motionId}") {
                            val v = GovernanceService(call = call).openVote(VoteOpenInput(motionId = call.parameters["motionId"]!!))
                            val optionsStr = v.options.joinToString(";") { "${it.id}=${it.label}" }
                            call.respondText("${v.id}:$optionsStr")
                        }
                        post("/e2e4/cast-vote/{voteId}/{optionId}") {
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
                        post("/e2e4/close-vote/{id}") {
                            val v = GovernanceService(call = call).closeVote(call.parameters["id"]!!)
                            call.respondText("${v.status}|${v.winnerOptionId ?: ""}|${v.resolutionId ?: ""}")
                        }
                    }
                }

                // ── Step 1: real self-registration (V0.7) -- ANTRAG, not AKTIV ──────────────────
                val email = "journey-four-applicant-${Uuid.random()}@example.org"
                client.post("/e2e4/register?email=$email").status shouldBe HttpStatusCode.OK
                val applicantId =
                    transaction {
                        MemberTable.selectAll().where { MemberTable.email eq email }.single()[MemberTable.id]
                    }
                createdMemberIds += applicantId
                memberStatusOf(applicantId) shouldBe MemberStatus.APPLICATION

                // ── Step 2: real login (real HTTP, real session cookie) -- ANTRAG can log in ────
                val rawToken = client.realLogin(email = email, password = E2E_STRONG_PASSWORD)

                // ── Step 3: BOARD stands up a non-GENERAL_ASSEMBLY Committee (deliberately, see ──
                // ── class KDoc) whose eligibility is Committee-membership alone, not org-wide ────
                // ── AKTIV status -- the only shape that can actually discriminate "the seat is ───
                // ── what grants eligibility" from "AKTIV status alone would have sufficed". ──────
                val committeeId = client.post("/e2e4/create-committee") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                // ── Step 4: the v0.9.0 addCommitteeMember status gate, exercised for the first ──
                // ── time against a member whose ANTRAG status arose from a REAL registration in ──
                // ── THIS test (not a hand-inserted row) -- BOARD tries to seat the STILL-ANTRAG ──
                // ── applicant and is refused. ─────────────────────────────────────────────────
                val refusedSeat =
                    client.post("/e2e4/add-committee-member/$committeeId/$applicantId") { header("X-Member-Id", BOARD_ID) }
                refusedSeat.status shouldBe HttpStatusCode.Forbidden
                // The refusal must have been a refusal, not a silently-half-applied seat.
                committeeMembersOf(committeeId = committeeId) shouldBe emptyMap()

                // ── Step 5: BOARD approves (RegistrationService, a completely separate ───────────
                // ── transaction) -- ANTRAG -> AKTIV ───────────────────────────────────────────
                val approved = client.post("/e2e4/approve/$applicantId") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                approved.bodyAsText() shouldBe "ACTIVE"
                memberStatusOf(applicantId) shouldBe MemberStatus.ACTIVE

                // ── Step 6: the IDENTICAL addCommitteeMember call, replayed with no other change, ─
                // ── now succeeds. ──────────────────────────────────────────────────────────────
                val seated =
                    client.post("/e2e4/add-committee-member/$committeeId/$applicantId") { header("X-Member-Id", BOARD_ID) }
                seated.status shouldBe HttpStatusCode.OK
                committeeMembersOf(committeeId = committeeId) shouldBe mapOf(applicantId to null)

                // ── Step 7: BOARD sets up a Motion + Vote on the SAME Committee ─────────────────
                val meetingId = client.post("/e2e4/create-meeting/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val motionId =
                    client.post("/e2e4/submit-motion/$committeeId?n=1") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                client.post("/e2e4/review-motion/$motionId") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe
                    MotionStatus.REVIEWED.name
                client
                    .post("/e2e4/schedule-motion/$motionId/$meetingId/1") { header("X-Member-Id", BOARD_ID) }
                    .bodyAsText() shouldBe MotionStatus.SCHEDULED.name
                val openResponse = client.post("/e2e4/open-vote/$motionId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val voteId = openResponse.substringBefore(":")
                val yesOptionId =
                    openResponse
                        .substringAfter(":")
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")

                // Funds the newly-AKTIV member's stake -- a supporting step, not itself the seam
                // under test.
                client.post("/e2e4/mint-ltr/$applicantId") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                    HttpStatusCode.OK

                // ── Step 8: cross-domain seam -- the member's REAL session casts a real ballot, ──
                // ── and can only be eligible via the Committee seat step 6 just created: this ────
                // ── Committee is WORKING_GROUP, so eligibleMemberIds is Committee-membership ─────
                // ── alone (see network.lapis.cloud.server.rpc.eligibleMemberIds KDoc), NOT ───────
                // ── "any AKTIV member" -- a 200 here is proof the just-created seat was picked ───
                // ── up LIVE by the eligibility check, not merely that AKTIV status sufficed. ─────
                val castResponse = client.post("/e2e4/cast-vote/$voteId/$yesOptionId") { withSession(rawToken) }
                castResponse.status shouldBe HttpStatusCode.OK
                ballotCountFor(voteId = voteId, memberId = applicantId) shouldBe 1

                val closeFields =
                    client.post("/e2e4/close-vote/$voteId") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                closeFields[0] shouldBe VoteStatus.CLOSED.name
                closeFields[1] shouldBe yesOptionId

                // ── Step 9: member-initiated exit (V0.7), the SAME session that has been live ────
                // ── since step 2 -- AKTIV -> AUSGETRETEN. ─────────────────────────────────────
                val leftResponse = client.post("/e2e4/leave-membership") { withSession(rawToken) }
                leftResponse.status shouldBe HttpStatusCode.OK
                leftResponse.bodyAsText() shouldBe "WITHDRAWN"
                memberStatusOf(applicantId) shouldBe MemberStatus.WITHDRAWN

                // ── Step 10: V0.13.1 FIX, previously a documented gap -- see class KDoc. ─────────
                // ── leaveMembership now ends the open CommitteeMembershipTable row atomically ────
                // ── with the AKTIV -> AUSGETRETEN flip: the now-AUSGETRETEN member is NO LONGER ───
                // ── listed as an active Committee seat holder. ───────────────────────────────────
                //
                // Asserted through the REAL production read path (GovernanceService
                // .listCommitteeMembers(activeOnly = true), via the /e2e4/committee-members route)
                // -- NOT merely through this file's local direct-DB helper. That distinction is the
                // whole point of a characterization test: `listCommitteeMembers` does
                // `CommitteeMembershipTable innerJoin MemberTable`, which `committeeMembersOf`
                // below deliberately does not, so the two are genuinely different queries -- both
                // must now agree the seat is closed, not just one of them.
                client
                    .get("/e2e4/committee-members/$committeeId?activeOnly=true") { header("X-Member-Id", BOARD_ID) }
                    .bodyAsText() shouldBe ""
                // The underlying row itself, unfiltered -- still exists (the row is ENDED, not
                // deleted) but its `until` is no longer null, so the activeOnly-scoped helper query
                // returns nothing for it either.
                committeeMembersOf(committeeId = committeeId, activeOnly = true) shouldBe emptyMap()
                val closedRow = committeeMembersOf(committeeId = committeeId, activeOnly = false)
                closedRow.keys shouldBe setOf(applicantId)
                closedRow[applicantId] shouldNotBe null

                // ── Step 11: a FRESH vote on the same Committee, so the stale seat from step 10 ──
                // ── has something new to (fail to) be leveraged against. ─────────────────────────
                val motion2Id =
                    client.post("/e2e4/submit-motion/$committeeId?n=2") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                client.post("/e2e4/review-motion/$motion2Id") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe
                    MotionStatus.REVIEWED.name
                client
                    .post("/e2e4/schedule-motion/$motion2Id/$meetingId/2") { header("X-Member-Id", BOARD_ID) }
                    .bodyAsText() shouldBe MotionStatus.SCHEDULED.name
                val openResponse2 = client.post("/e2e4/open-vote/$motion2Id") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val vote2Id = openResponse2.substringBefore(":")
                val yesOptionId2 =
                    openResponse2
                        .substringAfter(":")
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")

                // ── Step 12: the PAYOFF -- see class KDoc "Deviation from the wave plan" for the ─
                // ── full trace of why this is 401, not the plan's originally-expected 403: ───────
                // ── leaveMembership already revoked EVERY session belonging to this member ───────
                // ── (including the one that called it), so resolveCurrentMember throws ───────────
                // ── UnauthenticatedException before requireActiveMembership is ever reached. ─────
                // ── The stale Committee seat from step 10 buys the ex-member nothing: two ─────────
                // ── independent layers (session validity, then live membership status) both ──────
                // ── block a vote here, not just one. ──────────────────────────────────────────
                val staleSessionAttempt = client.post("/e2e4/cast-vote/$vote2Id/$yesOptionId2") { withSession(rawToken) }
                staleSessionAttempt.status shouldBe HttpStatusCode.Unauthorized
                ballotCountFor(voteId = vote2Id, memberId = applicantId) shouldBe 0
            }
        }
    })

private const val APPLICANT_DISPLAY_NAME = "Journey Four Applicant"

private fun memberStatusOf(memberId: Uuid): MemberStatus =
    transaction {
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]
    }

/** memberId -> `until` (null while the seat is still open) for every row in [committeeId], scoped by [activeOnly] exactly like [GovernanceService.listCommitteeMembers]'s own `until.isNull()` filter. */
private fun committeeMembersOf(
    committeeId: String,
    activeOnly: Boolean = false,
): Map<Uuid, LocalDate?> =
    transaction {
        val gId = Uuid.parse(committeeId)
        val conditions =
            if (activeOnly) {
                (CommitteeMembershipTable.committeeId eq gId) and CommitteeMembershipTable.until.isNull()
            } else {
                CommitteeMembershipTable.committeeId eq gId
            }
        CommitteeMembershipTable
            .selectAll()
            .where { conditions }
            .associate { it[CommitteeMembershipTable.memberId] to it[CommitteeMembershipTable.until] }
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
