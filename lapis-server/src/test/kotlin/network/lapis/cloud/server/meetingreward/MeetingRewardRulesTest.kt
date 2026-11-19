package network.lapis.cloud.server.meetingreward

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MeetingRewardBlockedReason
import network.lapis.cloud.shared.domain.MeetingRewardLineReason
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal
import kotlin.uuid.Uuid

private fun money(s: String) = BigDecimal(s)

private fun policy(
    base: String = "10.00",
    chair: String = "5.00",
    deputy: String = "4.00",
    secretary: String = "3.00",
    director: String = "6.00",
    capYear: String = "240.00",
    capMeeting: String = "1000.00",
) = MeetingRewardPolicyValues(
    enabled = true,
    attendanceAmount = money(base),
    bonusChair = money(chair),
    bonusDeputyChair = money(deputy),
    bonusSecretary = money(secretary),
    bonusManagingDirector = money(director),
    capPerPersonPerYear = money(capYear),
    capPerMeeting = money(capMeeting),
    committeeIds = emptySet(),
)

class MeetingRewardRulesTest :
    FunSpec({
        test("amounts: two decimal places are fine, a third is refused, never rounded") {
            validateAmount(value = money("12.5"), max = MAX_BONUS_OR_BASE) shouldBe money("12.50")
            validateAmount(value = money("0"), max = MAX_BONUS_OR_BASE) shouldBe money("0.00")
            validateAmount(value = money("10.100"), max = MAX_BONUS_OR_BASE) shouldBe money("10.10")
            shouldThrow<ConflictException> { validateAmount(value = money("1.005"), max = MAX_BONUS_OR_BASE) }.message shouldBe
                AMOUNT_SCALE_INVALID
        }

        test("amounts: negative and above the maximum are refused, the maximum itself is fine") {
            shouldThrow<ConflictException> { validateAmount(value = money("-0.01"), max = MAX_BONUS_OR_BASE) }.message shouldBe
                AMOUNT_OUT_OF_RANGE
            shouldThrow<ConflictException> { validateAmount(value = money("10000.01"), max = MAX_BONUS_OR_BASE) }.message shouldBe
                AMOUNT_OUT_OF_RANGE
            validateAmount(value = money("10000.00"), max = MAX_BONUS_OR_BASE) shouldBe money("10000.00")
            shouldThrow<ConflictException> { validateAmount(value = money("1000000.01"), max = MAX_CAP) }
        }

        test("bonus: the maximum over the bonus roles, never the sum") {
            val (role, bonus) =
                bonusFor(
                    roles = setOf(CommitteeRole.CHAIR, CommitteeRole.SECRETARY, CommitteeRole.MEMBER),
                    policy = policy(),
                )
            bonus shouldBe money("5.00")
            role shouldBe CommitteeRole.CHAIR
            val (role2, bonus2) = bonusFor(roles = setOf(CommitteeRole.SECRETARY, CommitteeRole.MANAGING_DIRECTOR), policy = policy())
            bonus2 shouldBe money("6.00")
            role2 shouldBe CommitteeRole.MANAGING_DIRECTOR
        }

        test("bonus: a tie takes the lowest rank, GENERAL_SECRETARY and TREASURER and MEMBER earn nothing") {
            val tie = policy(chair = "5.00", deputy = "5.00")
            bonusFor(roles = setOf(CommitteeRole.DEPUTY_CHAIR, CommitteeRole.CHAIR), policy = tie).first shouldBe CommitteeRole.CHAIR
            val (role, bonus) =
                bonusFor(
                    roles = setOf(CommitteeRole.GENERAL_SECRETARY, CommitteeRole.TREASURER, CommitteeRole.MEMBER),
                    policy = policy(),
                )
            bonus shouldBe money("0.00")
            role shouldBe CommitteeRole.GENERAL_SECRETARY // highest by rank, display only
            bonusFor(roles = emptySet(), policy = policy()) shouldBe (null to money("0.00"))
        }

        fun line(
            status: AttendanceStatus,
            memberStatus: MemberStatus = MemberStatus.ACTIVE,
            roles: Set<CommitteeRole> = emptySet(),
            paid: String = "0.00",
            policy: MeetingRewardPolicyValues = policy(),
            settled: Boolean = false,
        ): ComputedLine {
            val id = Uuid.random()
            return computeLines(
                attendance = listOf(AttendanceFact(memberId = id, status = status)),
                year = 2026,
                rolesByMember = mapOf(id to roles),
                statusByMember = mapOf(id to memberStatus),
                alreadyPaidThisYear = mapOf(id to money(paid)),
                alreadySettled = if (settled) setOf(id) else emptySet(),
                policy = policy,
            ).single()
        }

        test("lines: every status other than PRESENT gets nothing and its reason") {
            line(AttendanceStatus.EXCUSED).reason shouldBe MeetingRewardLineReason.EXCUSED
            line(AttendanceStatus.UNEXCUSED).reason shouldBe MeetingRewardLineReason.NOT_PRESENT
            line(AttendanceStatus.REPRESENTED).reason shouldBe MeetingRewardLineReason.REPRESENTED
            line(AttendanceStatus.GUEST).reason shouldBe MeetingRewardLineReason.GUEST
            listOf(AttendanceStatus.EXCUSED, AttendanceStatus.UNEXCUSED, AttendanceStatus.REPRESENTED, AttendanceStatus.GUEST).forEach {
                line(it, roles = setOf(CommitteeRole.CHAIR)).amount shouldBe money("0.00")
            }
        }

        test("lines: a present person gets the base amount plus the function bonus, once") {
            line(AttendanceStatus.PRESENT).amount shouldBe money("10.00")
            val chair = line(AttendanceStatus.PRESENT, roles = setOf(CommitteeRole.CHAIR, CommitteeRole.SECRETARY))
            chair.amount shouldBe money("15.00")
            chair.baseAmount shouldBe money("10.00")
            chair.bonusAmount shouldBe money("5.00")
            chair.reason shouldBe null
        }

        test("lines: a guest status or a non-eligible member status is not recognised, an already settled person neither") {
            line(AttendanceStatus.PRESENT, memberStatus = MemberStatus.GUEST).reason shouldBe MeetingRewardLineReason.MEMBER_NOT_ELIGIBLE
            line(AttendanceStatus.PRESENT, memberStatus = MemberStatus.WITHDRAWN).reason shouldBe
                MeetingRewardLineReason.MEMBER_NOT_ELIGIBLE
            line(AttendanceStatus.PRESENT, memberStatus = MemberStatus.FRIEND).reason shouldBe MeetingRewardLineReason.MEMBER_NOT_ELIGIBLE
            line(AttendanceStatus.PRESENT, settled = true).reason shouldBe MeetingRewardLineReason.ALREADY_SETTLED
            line(AttendanceStatus.PRESENT, settled = true).amount shouldBe money("0.00")
        }

        test("lines: the yearly cap reduces to the exact rest and then stops, without a rounding remainder") {
            val reduced = line(AttendanceStatus.PRESENT, roles = setOf(CommitteeRole.CHAIR), paid = "235.50")
            reduced.amount shouldBe money("4.50")
            reduced.reduced shouldBe true
            reduced.reason shouldBe MeetingRewardLineReason.CAP_REDUCED
            val reached = line(AttendanceStatus.PRESENT, paid = "240.00")
            reached.amount shouldBe money("0.00")
            reached.reason shouldBe MeetingRewardLineReason.CAP_REACHED
            line(AttendanceStatus.PRESENT, paid = "230.00").amount shouldBe money("10.00") // exactly what is left is not reduced
        }

        test("lines: a zero amount is no cap case, lines are sorted by member id") {
            line(AttendanceStatus.PRESENT, policy = policy(base = "0.00", chair = "0.00"), paid = "999.00").reason shouldBe null
            val ids = List(6) { Uuid.random() }
            val lines =
                computeLines(
                    attendance = ids.map { AttendanceFact(memberId = it, status = AttendanceStatus.PRESENT) },
                    year = 2026,
                    rolesByMember = emptyMap(),
                    statusByMember = ids.associateWith { MemberStatus.ACTIVE },
                    alreadyPaidThisYear = emptyMap(),
                    alreadySettled = emptySet(),
                    policy = policy(),
                )
            lines.map { it.memberId.toString() } shouldBe ids.map { it.toString() }.sorted()
            settlementTotal(lines) shouldBe money("60.00")
        }

        test("hash: deterministic, 64 hex chars and sensitive to every field") {
            val meeting = Uuid.random()
            val change = Uuid.random()
            val protocol = Uuid.random()
            val member = Uuid.random()
            val base =
                ComputedLine(
                    memberId = member,
                    attendanceStatus = AttendanceStatus.PRESENT,
                    functionRole = CommitteeRole.CHAIR,
                    baseAmount = money("10.00"),
                    bonusAmount = money("5.00"),
                    amount = money("15.00"),
                    reduced = false,
                    reason = null,
                    periodYear = 2026,
                )
            val hash = previewHash(meetingId = meeting, policyChangeId = change, protocolVersionId = protocol, lines = listOf(base))
            hash shouldHaveLength 64
            previewHash(meetingId = meeting, policyChangeId = change, protocolVersionId = protocol, lines = listOf(base)) shouldBe hash
            val variants =
                listOf(
                    previewHash(meetingId = Uuid.random(), policyChangeId = change, protocolVersionId = protocol, lines = listOf(base)),
                    previewHash(meetingId = meeting, policyChangeId = Uuid.random(), protocolVersionId = protocol, lines = listOf(base)),
                    previewHash(meetingId = meeting, policyChangeId = change, protocolVersionId = Uuid.random(), lines = listOf(base)),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(amount = money("14.99"))),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(baseAmount = money("9.00"))),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(bonusAmount = money("4.00"))),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(reduced = true)),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(reason = MeetingRewardLineReason.CAP_REDUCED)),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(functionRole = CommitteeRole.SECRETARY)),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(periodYear = 2027)),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(attendanceStatus = AttendanceStatus.EXCUSED)),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base.copy(memberId = Uuid.random())),
                    ),
                    previewHash(
                        meetingId = meeting,
                        policyChangeId = change,
                        protocolVersionId = protocol,
                        lines = listOf(base, base.copy(memberId = Uuid.random())),
                    ),
                )
            (variants + hash).toSet().size shouldBe variants.size + 1
            // scale differences of the same amount do not change the hash
            previewHash(
                meetingId = meeting,
                policyChangeId = change,
                protocolVersionId = protocol,
                lines = listOf(base.copy(amount = money("15.0"))),
            ) shouldBe
                hash
        }

        test("the limit per meeting is refused above, never reduced") {
            requireMeetingCap(total = money("1000.00"), cap = money("1000.00"))
            shouldThrow<ConflictException> { requireMeetingCap(total = money("1000.01"), cap = money("1000.00")) }.message shouldBe
                MEETING_CAP_EXCEEDED
        }

        test("blocked reasons: not admin, four eyes, not releasable") {
            settlementBlockedReason(isAdmin = false, status = null, callerIsPreparer = false) shouldBe MeetingRewardBlockedReason.NOT_ADMIN
            settlementBlockedReason(isAdmin = true, status = null, callerIsPreparer = false) shouldBe null
            settlementBlockedReason(isAdmin = true, status = MeetingRewardSettlementStatus.PREPARED, callerIsPreparer = true) shouldBe
                MeetingRewardBlockedReason.FOUR_EYES
            settlementBlockedReason(isAdmin = true, status = MeetingRewardSettlementStatus.PREPARED, callerIsPreparer = false) shouldBe null
            releaseBlockedReason(isAdmin = false, status = MeetingRewardSettlementStatus.PREPARED, callerIsPreparer = false) shouldBe
                MeetingRewardBlockedReason.NOT_ADMIN
            releaseBlockedReason(isAdmin = true, status = null, callerIsPreparer = false) shouldBe MeetingRewardBlockedReason.NOT_RELEASABLE
            releaseBlockedReason(isAdmin = true, status = MeetingRewardSettlementStatus.RELEASED, callerIsPreparer = false) shouldBe
                MeetingRewardBlockedReason.NOT_RELEASABLE
            releaseBlockedReason(isAdmin = true, status = MeetingRewardSettlementStatus.PREPARED, callerIsPreparer = true) shouldBe
                MeetingRewardBlockedReason.FOUR_EYES
            releaseBlockedReason(isAdmin = true, status = MeetingRewardSettlementStatus.PREPARED, callerIsPreparer = false) shouldBe null
        }

        test("only administrators may handle the rules and the settlement") {
            MEETING_REWARD_POLICY_ROLES shouldBe setOf(AccountRole.ADMIN)
            MEETING_REWARD_SETTLEMENT_ROLES shouldBe setOf(AccountRole.ADMIN)
        }

        test("sameAs ignores scale and the order of the committees") {
            val a = Uuid.random()
            val b = Uuid.random()
            policy().copy(committeeIds = setOf(a, b)).sameAs(policy(base = "10.0").copy(committeeIds = setOf(b, a))) shouldBe true
            policy().sameAs(policy(base = "10.01")) shouldBe false
        }
    })
