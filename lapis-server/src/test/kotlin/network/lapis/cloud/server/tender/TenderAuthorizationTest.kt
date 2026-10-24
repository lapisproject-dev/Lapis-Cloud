package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.rpc.TenderService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderDraftUpdateInput
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.uuid.Uuid

/**
 * V1.9.201 -- every method of the tender service against every role and member status. BOARD and ADMIN manage; TREASURER only reads (like the board);
 * a plain member, a friend and a guest only see published tenders and bid. A manager role on a member who is no longer ACTIVE manages nothing.
 */
class TenderAuthorizationTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("management methods: BOARD and ADMIN are admitted, everybody else gets ForbiddenException before anything is looked at") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val binding = fx.approvedMotion()
                    val owner = fx.member(label = "owner", role = AccountRole.BOARD)
                    val draft = newTender(board = owner, binding = binding, publish = false)
                    val update =
                        TenderDraftUpdateInput(
                            bindingMotionId = binding.toString(),
                            title = "T",
                            description = "D",
                            expectedResult = "E",
                            deadline = TENDER_DEADLINE,
                            awardCriterion = createInput(binding = binding).awardCriterion,
                        )
                    val unknown = Uuid.random().toString()
                    val denied =
                        mapOf(
                            "member" to fx.member(label = "m"),
                            "treasurer" to fx.member(label = "t", role = AccountRole.TREASURER),
                            "friend" to fx.member(label = "f", status = MemberStatus.FRIEND),
                            "guest" to fx.member(label = "g", status = MemberStatus.GUEST),
                            "board but withdrawn" to fx.member(label = "bw", role = AccountRole.BOARD, status = MemberStatus.WITHDRAWN),
                            "admin but applicant" to fx.member(label = "aa", role = AccountRole.ADMIN, status = MemberStatus.APPLICATION),
                        )
                    val operations: Map<String, suspend TenderService.() -> Any?> =
                        mapOf(
                            "createTender" to { createTender(createInput(binding = binding)) },
                            "updateTenderDraft" to { updateTenderDraft(tenderId = draft, input = update) },
                            "publishTender" to { publishTender(draft) },
                            "cancelTender" to { cancelTender(tenderId = draft, reason = "weil") },
                            "registerAwardMotion" to { registerAwardMotion(tenderId = draft, motionId = unknown) },
                            "awardTender" to { awardTender(TenderAwardInput(tenderId = draft, bidId = unknown)) },
                            "recordAcceptance" to
                                { recordAcceptance(TenderAcceptanceInput(tenderId = draft, decision = TenderAcceptanceDecision.ACCEPTED)) },
                        )
                    operations.forEach { (name, op) ->
                        denied.forEach { (who, member) ->
                            val failure = tenderAttempt(member = member) { op() }.exceptionOrNull()
                            if (failure !is ForbiddenException) error("$name by $who must be Forbidden but was $failure")
                        }
                    }
                    // and the two manager roles are admitted
                    listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                        val manager = fx.member(label = "m-$role", role = role)
                        tender(member = manager) { createTender(createInput(binding = binding)) }.canManage shouldBe true
                    }
                }
            }
        }

        test("reading: members, friends and guests see a published tender, the TREASURER sees drafts too, nobody else gets the defaults") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val draft = newTender(board = board, binding = binding, publish = false)
                    val published = newTender(board = board, binding = binding)
                    val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
                    tender(member = treasurer) { getTender(draft) }.canManage shouldBe false
                    tender(member = treasurer) { listTenders() }.size shouldBe 2
                    listOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN).forEach {
                        tender(member = fx.member(label = "d-$it", role = it)) { getTenderDefaults() }
                    }
                    listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST).forEach { status ->
                        val member = fx.member(label = "r-$status", status = status)
                        tender(member = member) { getTender(published) }.canManage shouldBe false
                        tender(member = member) { listTenders() }.size shouldBe 1
                        tenderAttempt(member = member) { getTenderDefaults() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
        }
    })
