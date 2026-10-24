package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardMotionIneligibility
import network.lapis.cloud.shared.domain.TenderMotionIneligibility
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.202 -- the two option lists behind the screens: every reason, the access rule and a stable order. */
class TenderMotionOptionsTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("binding options: an adopted motion is selectable, every other state carries its reason") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val submitter = fx.member(label = "submitter")
                    val committee = fx.committee()
                    val adopted = fx.motion(committee = committee, submitter = submitter, status = MotionStatus.RESOLVED)
                    val pending = fx.motion(committee = committee, submitter = submitter, status = MotionStatus.SCHEDULED)
                    val notAdopted =
                        fx.motion(
                            committee = committee,
                            submitter = submitter,
                            status = MotionStatus.RESOLVED,
                            resolutionStatus = ResolutionStatus.REJECTED,
                        )
                    val otherCommittee =
                        fx.motion(
                            committee = committee,
                            submitter = submitter,
                            status = MotionStatus.RESOLVED,
                            resolutionCommittee = fx.committee(),
                        )
                    val amendment =
                        fx.motion(
                            committee = committee,
                            submitter = submitter,
                            status = MotionStatus.RESOLVED,
                            amends = adopted,
                        )
                    val mine = setOf(adopted, pending, notAdopted, otherCommittee, amendment).map { it.toString() }.toSet()
                    val options =
                        tender(
                            member = board,
                        ) { listTenderMotionOptions() }.filter { it.motionId in mine }.associateBy { it.motionId }
                    options.size shouldBe 5
                    options.getValue(adopted.toString()).selectable shouldBe true
                    options.getValue(adopted.toString()).reason shouldBe null
                    options.getValue(adopted.toString()).committeeName.isNotEmpty() shouldBe true
                    options.getValue(pending.toString()).reason shouldBe TenderMotionIneligibility.NOT_RESOLVED
                    options.getValue(notAdopted.toString()).reason shouldBe TenderMotionIneligibility.RESOLUTION_NOT_ADOPTED
                    options.getValue(otherCommittee.toString()).reason shouldBe TenderMotionIneligibility.WRONG_COMMITTEE
                    options.getValue(amendment.toString()).reason shouldBe TenderMotionIneligibility.AMENDMENT
                    // parity: what is offered as selectable is accepted by createTender, the rest is refused
                    tenderAttempt(member = board) { createTender(createInput(binding = adopted)) }.isSuccess shouldBe true
                    tenderAttempt(member = board) { createTender(createInput(binding = pending)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("binding options: newest first, the same order on every call, and only the board may ask") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
                    val submitter = fx.member(label = "submitter")
                    val committee = fx.committee()
                    val a = fx.motion(committee = committee, submitter = submitter, status = MotionStatus.RESOLVED)
                    val b = fx.motion(committee = committee, submitter = submitter, status = MotionStatus.RESOLVED)
                    transaction { MotionTable.update({ MotionTable.id eq b }) { it[submittedAt] = LocalDateTime(2026, 10, 15, 0, 0) } }
                    val mine = setOf(a.toString(), b.toString())
                    val first = tender(member = board) { listTenderMotionOptions() }.map { it.motionId }.filter { it in mine }
                    first shouldBe listOf(b.toString(), a.toString())
                    tender(member = admin) { listTenderMotionOptions() }.map { it.motionId }.filter { it in mine } shouldBe first
                    listOf(
                        fx.member(label = "plain"),
                        fx.member(label = "treasurer", role = AccountRole.TREASURER),
                        fx.member(label = "friend", status = MemberStatus.FRIEND),
                        fx.member(label = "guest", status = MemberStatus.GUEST),
                    ).forEach {
                        tenderAttempt(member = it) { listTenderMotionOptions() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
        }

        test("award options: the award motion of another tender is shown as taken, for its own tender it stays eligible") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = bestOfferScenario(fx = fx, clock = clock, present = { c, b -> c + b }, register = true)
                    val otherTender =
                        newTender(
                            board = s.board,
                            binding = fx.approvedMotion(committee = s.committee),
                            criterion = TenderAwardCriterion.BEST_OFFER,
                            deadline = LATER,
                        )
                    transaction {
                        val award = MotionTable.selectAll().where { MotionTable.id eq s.awardMotion }.single()
                        val other = TenderStore.find(Uuid.parse(otherTender))!!
                        val own = TenderStore.find(Uuid.parse(s.tenderId))!!
                        TenderMotionBinding.awardIneligibility(motionRow = award, tenderRow = other) shouldBe
                            TenderAwardMotionIneligibility.ALREADY_BOUND
                        TenderMotionBinding.awardIneligibility(motionRow = award, tenderRow = own) shouldBe null
                    }
                }
            }
        }

        test("award options: every reason, the bound binding motion and a started decision path are shown disabled") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = bestOfferScenario(fx = fx, clock = clock, present = { c, b -> c + b }, register = false)
                    val submitter = s.clean.first()
                    val started =
                        fx.motion(
                            committee = s.committee,
                            submitter = submitter,
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                        )
                    val resolved = fx.motion(committee = s.committee, submitter = submitter, status = MotionStatus.RESOLVED)
                    val withAmendment =
                        fx.motion(
                            committee = s.committee,
                            submitter = submitter,
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                        )
                    fx.motion(
                        committee = s.committee,
                        submitter = submitter,
                        status = MotionStatus.SCHEDULED,
                        meeting = s.meeting,
                        amends = withAmendment,
                    )
                    val binding = bindingOf(s.tenderId)
                    val amendment =
                        fx.motion(
                            committee = s.committee,
                            submitter = submitter,
                            status = MotionStatus.SCHEDULED,
                            meeting = s.meeting,
                            amends = binding,
                        )
                    // a motion that already carries another tender is no award motion
                    val bindingOfOther = fx.approvedMotion(committee = s.committee)
                    tender(member = s.board) { createTender(createInput(binding = bindingOfOther, deadline = LATER)) }
                    transaction { MotionTable.update({ MotionTable.id eq bindingOfOther }) { it[status] = MotionStatus.SCHEDULED } }
                    val elsewhere = fx.motion(committee = fx.committee(), submitter = submitter, status = MotionStatus.SCHEDULED)
                    attempt(
                        member = s.board,
                    ) { GovernanceService(call = it).openVote(VoteOpenInput(motionId = started.toString())) }.getOrThrow()

                    val options = tender(member = s.board) { listAwardMotionOptions(s.tenderId) }.associateBy { it.motionId }
                    options.getValue(s.awardMotion.toString()).selectable shouldBe true
                    options.getValue(binding.toString()).reason shouldBe TenderAwardMotionIneligibility.SAME_AS_BINDING
                    options.getValue(started.toString()).reason shouldBe TenderAwardMotionIneligibility.DECISION_STARTED
                    options.getValue(resolved.toString()).reason shouldBe TenderAwardMotionIneligibility.NOT_OPEN
                    options.getValue(withAmendment.toString()).reason shouldBe TenderAwardMotionIneligibility.HAS_AMENDMENT
                    options.getValue(bindingOfOther.toString()).reason shouldBe TenderAwardMotionIneligibility.ALREADY_BOUND
                    options.getValue(amendment.toString()).reason shouldBe TenderAwardMotionIneligibility.AMENDMENT
                    options.containsKey(elsewhere.toString()) shouldBe false
                    // parity with the writing path
                    tenderAttempt(member = s.board) { registerAwardMotion(tenderId = s.tenderId, motionId = started.toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = s.board) { registerAwardMotion(tenderId = s.tenderId, motionId = s.awardMotion.toString()) }
                        .isSuccess shouldBe true
                }
            }
        }

        test("award options: nothing for a bidder, a member or a LOWEST_PRICE tender") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = bestOfferScenario(fx = fx, clock = clock, present = { c, b -> c + b }, register = false)
                    val otherBoard = fx.member(label = "otherBoard", role = AccountRole.BOARD)
                    val lowest =
                        newTender(
                            board = s.board,
                            binding = fx.approvedMotion(committee = s.committee),
                            criterion = TenderAwardCriterion.LOWEST_PRICE,
                            deadline = LATER,
                        )
                    tenderAttempt(
                        member = s.board,
                    ) { listAwardMotionOptions(lowest) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = s.clean.first()) { listAwardMotionOptions(s.tenderId) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    tenderAttempt(member = otherBoard) { listAwardMotionOptions(s.tenderId) }.isSuccess shouldBe true
                    // a board member who has a bid on the tender never gets the list
                    val bidden = fx.member(label = "bidden", role = AccountRole.BOARD)
                    legacyBidOn(tenderId = s.tenderId, member = bidden)
                    tenderAttempt(
                        member = bidden,
                    ) { listAwardMotionOptions(s.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }
    })

private val LATER = LocalDateTime(2026, 11, 20, 11, 0)

private fun legacyBidOn(
    tenderId: String,
    member: Uuid,
) {
    TenderFixtures().legacyBid(tenderId = tenderId, bidder = member, price = "99.00")
}

private fun bindingOf(tenderId: String): Uuid =
    transaction {
        network.lapis.cloud.server.db.generated.TenderTable
            .selectAll()
            .where { network.lapis.cloud.server.db.generated.TenderTable.id eq Uuid.parse(tenderId) }
            .single()[network.lapis.cloud.server.db.generated.TenderTable.bindingMotionId]
    }
