package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.201 -- a tender is bound to an ADOPTED resolution of the committee the motion is addressed to; checked at creation and again at publication. */
class TenderMotionBindingTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("only a RESOLVED motion with an ADOPTED resolution is a valid binding motion") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val committee = fx.committee()
                    val someone = fx.member(label = "submitter")
                    listOf(
                        MotionStatus.SUBMITTED,
                        MotionStatus.REVIEWED,
                        MotionStatus.SCHEDULED,
                        MotionStatus.REJECTED,
                        MotionStatus.REJECTED_PRELIMINARY,
                        MotionStatus.POSTPONED,
                        MotionStatus.WITHDRAWN,
                    ).forEach { status ->
                        val motion = fx.motion(committee = committee, submitter = someone, status = status)
                        tenderAttempt(
                            member = board,
                        ) { createTender(createInput(binding = motion)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    listOf(ResolutionStatus.REJECTED, ResolutionStatus.POSTPONED).forEach { resolutionStatus ->
                        val motion =
                            fx.motion(
                                committee = committee,
                                submitter = someone,
                                status = MotionStatus.RESOLVED,
                                resolutionStatus = resolutionStatus,
                            )
                        tenderAttempt(
                            member = board,
                        ) { createTender(createInput(binding = motion)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    // a RESOLVED motion without any resolution row
                    val orphan = fx.motion(committee = committee, submitter = someone, status = MotionStatus.SCHEDULED)
                    transaction { MotionTable.update({ MotionTable.id eq orphan }) { it[status] = MotionStatus.RESOLVED } }
                    tenderAttempt(
                        member = board,
                    ) { createTender(createInput(binding = orphan)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    tender(member = board) {
                        createTender(createInput(binding = fx.approvedMotion(committee = committee, submitter = someone)))
                    }.tender.status shouldBe
                        TenderStatus.DRAFT
                }
            }
        }

        test("an amendment is never a binding motion, a resolution of another committee does not count, an unknown id is NotFound") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val committee = fx.committee()
                    val other = fx.committee()
                    val someone = fx.member(label = "submitter")
                    val main = fx.motion(committee = committee, submitter = someone, status = MotionStatus.SCHEDULED)
                    val amendment = fx.motion(committee = committee, submitter = someone, status = MotionStatus.RESOLVED, amends = main)
                    tenderAttempt(
                        member = board,
                    ) { createTender(createInput(binding = amendment)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    val wrongCommittee =
                        fx.motion(
                            committee = committee,
                            submitter = someone,
                            status = MotionStatus.RESOLVED,
                            resolutionCommittee = other,
                        )
                    tenderAttempt(
                        member = board,
                    ) { createTender(createInput(binding = wrongCommittee)) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    tenderAttempt(
                        member = board,
                    ) { createTender(createInput(binding = Uuid.random())) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    tenderAttempt(member = board) {
                        createTender(createInput(binding = Uuid.random()).copy(bindingMotionId = "not-a-uuid"))
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }
        }

        test("one adopted resolution carries several tenders") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val first = newTender(board = board, binding = binding)
                    val second = newTender(board = board, binding = binding)
                    (first != second) shouldBe true
                    tender(member = board) { listTenders() }.size shouldBe 2
                }
            }
        }

        test("the binding is checked again at publication") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val id = newTender(board = board, binding = binding, publish = false)
                    transaction { MotionTable.update({ MotionTable.id eq binding }) { it[status] = MotionStatus.WITHDRAWN } }
                    tenderAttempt(member = board) { publishTender(id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    storedStatus(id) shouldBe TenderStatus.DRAFT
                }
            }
        }
    })
