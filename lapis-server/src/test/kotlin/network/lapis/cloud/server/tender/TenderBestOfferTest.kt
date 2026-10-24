package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.SystemicConsensusService
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * V1.9.201 -- BEST_OFFER: the award is decided by a committee resolution (the award motion), the bidders are excluded from voting and from both
 * sides of the quorum on THAT motion only, and the other decision paths are shut for it.
 *
 * Committee of five seats (quorum 60 %): s1..s3 are clean, s4 and s5 are the bidders.
 */
class TenderBestOfferTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        suspend fun TenderApp.scenario(
            clock: MutableTestClock,
            present: (clean: List<Uuid>, bidders: List<Uuid>) -> List<Uuid>,
            register: Boolean = true,
        ) = bestOfferScenario(fx = fx, clock = clock, present = present, register = register)

        fun quorumMetOf(motionId: Uuid): Boolean =
            transaction {
                val resolutionId =
                    MotionTable
                        .selectAll()
                        .where { MotionTable.id eq motionId }
                        .single()[MotionTable.resolutionId]!!
                ResolutionTable.selectAll().where { ResolutionTable.id eq resolutionId }.single()[ResolutionTable.quorumMet]
            }

        test("a registered award motion cannot be amended, so a bidder cannot sidestep the conflict of interest through an amendment") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val committee = fx.committee()
                    val id =
                        newTender(
                            board = board,
                            binding = fx.approvedMotion(committee = committee),
                            criterion = TenderAwardCriterion.BEST_OFFER,
                        )
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    val award = fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SCHEDULED)
                    clock.advance(1.days)
                    tender(member = board) { registerAwardMotion(tenderId = id, motionId = award.toString()) }
                    attempt(member = board) {
                        GovernanceService(call = it).submitMotion(
                            network.lapis.cloud.shared.domain.MotionInput(
                                targetCommitteeId = committee.toString(),
                                title = "Amendment",
                                rationale = "Rationale",
                                text = "Award to my own bid",
                                amendsMotionId = award.toString(),
                            ),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>().message shouldBe
                        "The award motion of a tender cannot be amended"
                }
            }
        }

        test("an amendment that exists before registration blocks the registration of the award motion") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val committee = fx.committee()
                    val id =
                        newTender(
                            board = board,
                            binding = fx.approvedMotion(committee = committee),
                            criterion = TenderAwardCriterion.BEST_OFFER,
                        )
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    clock.advance(1.days)
                    val award = fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SCHEDULED)
                    fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SUBMITTED, amends = award)
                    tenderAttempt(
                        member = board,
                    ) { registerAwardMotion(tenderId = id, motionId = award.toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test(
            "registration: only for a closed BEST_OFFER tender, an eligible motion of the same committee; idempotent; one tender per motion",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val committee = fx.committee()
                    val id =
                        newTender(
                            board = board,
                            binding = fx.approvedMotion(committee = committee),
                            criterion = TenderAwardCriterion.BEST_OFFER,
                        )
                    val lowest = newTender(board = board, binding = fx.approvedMotion(committee = committee))
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    tender(member = bidder) { submitBid(tenderId = lowest, input = bidInput(price = "10.00")) }
                    val award = fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SCHEDULED)
                    tenderAttempt(
                        member = board,
                    ) {
                        registerAwardMotion(
                            tenderId = id,
                            motionId = award.toString(),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    clock.advance(1.days)
                    tenderAttempt(
                        member = board,
                    ) {
                        registerAwardMotion(
                            tenderId = lowest,
                            motionId = award.toString(),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    val other = fx.committee()
                    val amended = fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SCHEDULED)
                    listOf(
                        fx.motion(committee = other, submitter = bidder, status = MotionStatus.SCHEDULED),
                        fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SCHEDULED, amends = amended),
                        amended.also {
                            fx.motion(committee = committee, submitter = bidder, status = MotionStatus.SUBMITTED, amends = it)
                        },
                        fx.motion(committee = committee, submitter = bidder, status = MotionStatus.RESOLVED),
                        fx.motion(committee = committee, submitter = bidder, status = MotionStatus.WITHDRAWN),
                    ).forEach { bad ->
                        tenderAttempt(
                            member = board,
                        ) {
                            registerAwardMotion(
                                tenderId = id,
                                motionId = bad.toString(),
                            )
                        }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    val binding =
                        transaction {
                            TenderTable
                                .selectAll()
                                .where {
                                    TenderTable.id eq
                                        Uuid.parse(
                                            id,
                                        )
                                }.single()[TenderTable.bindingMotionId]
                        }
                    tenderAttempt(
                        member = board,
                    ) {
                        registerAwardMotion(
                            tenderId = id,
                            motionId = binding.toString(),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    tender(member = board) { registerAwardMotion(tenderId = id, motionId = award.toString()) }.awardMotionId shouldBe
                        award.toString()
                    tender(member = board) { registerAwardMotion(tenderId = id, motionId = award.toString()) }.awardMotionId shouldBe
                        award.toString()
                    // a second tender cannot take the same motion
                    val second =
                        newTender(
                            board = board,
                            binding = fx.approvedMotion(committee = committee),
                            criterion = TenderAwardCriterion.BEST_OFFER,
                            deadline = LocalDateTime(2026, 11, 5, 11, 0),
                        )
                    tender(member = bidder) { submitBid(tenderId = second, input = bidInput(price = "10.00")) }
                    clock.advance(2.days)
                    tenderAttempt(
                        member = board,
                    ) {
                        registerAwardMotion(
                            tenderId = second,
                            motionId = award.toString(),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    // the binding motion of a tender cannot serve as an award motion
                    val bindingOfSecond =
                        transaction {
                            TenderTable
                                .selectAll()
                                .where {
                                    TenderTable.id eq
                                        Uuid.parse(
                                            second,
                                        )
                                }.single()[TenderTable.bindingMotionId]
                        }
                    transaction { MotionTable.update({ MotionTable.id eq bindingOfSecond }) { it[status] = MotionStatus.SCHEDULED } }
                    tenderAttempt(
                        member = board,
                    ) {
                        registerAwardMotion(
                            tenderId = id,
                            motionId = bindingOfSecond.toString(),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("an open decision path on the motion blocks the registration") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> c + b }, register = false)
                    attempt(
                        member = s.board,
                    ) { GovernanceService(call = it).openVote(VoteOpenInput(motionId = s.awardMotion.toString())) }.getOrThrow()
                    tenderAttempt(member = s.board) { registerAwardMotion(tenderId = s.tenderId, motionId = s.awardMotion.toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("a registered award motion refuses an election and a systemic consensus") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> c + b })
                    attempt(member = s.board) {
                        ElectionService(call = it, streamGuard = NoOpSecretBallotStreamGuard)
                            .openElection(ElectionOpenInput(motionId = s.awardMotion.toString(), electionType = ElectionType.YES_NO))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    attempt(member = s.board) {
                        SystemicConsensusService(
                            call = it,
                            streamGuard = NoOpSecretBallotStreamGuard,
                        ).openSystemicConsensus(SystemicConsensusOpenInput(motionId = s.awardMotion.toString()))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test(
            "quorum: the bidders are excluded from both sides on the award motion only, and the figures must fit the remaining present members",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> c + b })
                    val other =
                        fx.motion(
                            committee = s.committee,
                            submitter = s.clean.first(),
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                        )
                    // five present, but only three of them may be counted on the award motion
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 4, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 3, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    quorumMetOf(s.awardMotion) shouldBe true
                    // another motion of the same meeting keeps the full headcount
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = other.toString(),
                            input = MotionResolutionInput(votesYes = 5, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    quorumMetOf(other) shouldBe true
                }
            }
        }

        test(
            "quorum: present bidders do not make a quorum - three present (one clean, two bidders) is enough for a normal motion but not for the award motion",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> listOf(c.first()) + b })
                    val other =
                        fx.motion(
                            committee = s.committee,
                            submitter = s.clean.first(),
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                        )
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = other.toString(),
                            input = MotionResolutionInput(votesYes = 3, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    quorumMetOf(other) shouldBe true // 3 of 5 present, 60 % needs 3
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 1, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    quorumMetOf(s.awardMotion) shouldBe false // 1 of 3 eligible present, 60 % needs 2
                }
            }
        }

        test("meritocratic vote: the frozen circle of voters has no bidder, and a bidder is refused before anything is staked") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> c + b })
                    val vote =
                        attempt(member = s.board) {
                            GovernanceService(call = it).openVote(VoteOpenInput(motionId = s.awardMotion.toString()))
                        }.getOrThrow()
                    val voters =
                        transaction {
                            VoteEligibleVoterTable
                                .selectAll()
                                .where { VoteEligibleVoterTable.voteId eq Uuid.parse(vote.id) }
                                .map { it[VoteEligibleVoterTable.memberId] }
                        }
                    voters shouldContainExactlyInAnyOrder s.clean
                    s.bidders.forEach { bidder ->
                        attempt(member = bidder) {
                            GovernanceService(
                                call = it,
                            ).castVoteBallot(
                                VoteBallotInput(voteId = vote.id, optionId = vote.options.first().id, stakeLtr = BigDecimal("1.00")),
                            )
                        }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                    transaction { VoteTable.selectAll().count() } shouldBe 1
                }
            }
        }

        test(
            "the award: a justification is mandatory, a rejected or too early resolution does not count, an adopted one does and snapshots the counts",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> c + b })
                    val winnerBid = s.bidIds.first()
                    // not decided yet
                    tenderAttempt(member = s.board) {
                        awardTender(TenderAwardInput(tenderId = s.tenderId, bidId = winnerBid, justification = "weil"))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 3, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    tenderAttempt(
                        member = s.board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = s.tenderId, bidId = winnerBid),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    // a resolution taken before the deadline cannot decide the award
                    transaction {
                        ResolutionTable.update({ ResolutionTable.meetingId eq s.meeting }) {
                            it[decidedAt] = LocalDateTime(2026, 11, 1, 10, 0)
                        }
                    }
                    tenderAttempt(member = s.board) {
                        awardTender(TenderAwardInput(tenderId = s.tenderId, bidId = winnerBid, justification = "weil"))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    transaction {
                        ResolutionTable.update({ ResolutionTable.meetingId eq s.meeting }) {
                            it[decidedAt] = LocalDateTime(2026, 11, 3, 12, 0)
                        }
                    }
                    tender(member = s.board) {
                        awardTender(TenderAwardInput(tenderId = s.tenderId, bidId = winnerBid, justification = "Das beste Konzept"))
                    }.tender.status shouldBe
                        TenderStatus.AWARDED
                    val award = transaction { TenderAwardTable.selectAll().single() }
                    award[TenderAwardTable.conflictedCount] shouldBe 2
                    award[TenderAwardTable.quorumEligibleCount] shouldBe 3
                    award[TenderAwardTable.awardMotionId] shouldBe s.awardMotion
                    award[TenderAwardTable.resolutionId] shouldBe
                        transaction {
                            ResolutionTable.selectAll().where { ResolutionTable.meetingId eq s.meeting }.single()[ResolutionTable.id]
                        }
                }
            }
        }

        test("a REJECTED award resolution cannot award, and the rejected motion may be replaced by another one") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = scenario(clock, { c, b -> c + b })
                    attempt(member = s.board) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 0, votesNo = 3, votesAbstain = 0, status = ResolutionStatus.REJECTED),
                        )
                    }.getOrThrow()
                    tenderAttempt(
                        member = s.board,
                    ) { awardTender(TenderAwardInput(tenderId = s.tenderId, bidId = s.bidIds.first(), justification = "weil")) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    val second =
                        fx.motion(
                            committee = s.committee,
                            submitter = s.clean.first(),
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                        )
                    tender(
                        member = s.board,
                    ) { registerAwardMotion(tenderId = s.tenderId, motionId = second.toString()) }.awardMotionId shouldBe
                        second.toString()
                    // an ADOPTED one is final and cannot be replaced
                    attempt(
                        member = s.board,
                    ) {
                        GovernanceService(
                            call = it,
                        ).resolveMotion(
                            id = second.toString(),
                            input = MotionResolutionInput(votesYes = 3, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    val third =
                        fx.motion(
                            committee = s.committee,
                            submitter = s.clean.first(),
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                        )
                    tenderAttempt(
                        member = s.board,
                    ) {
                        registerAwardMotion(
                            tenderId = s.tenderId,
                            motionId = third.toString(),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }
        }
    })
