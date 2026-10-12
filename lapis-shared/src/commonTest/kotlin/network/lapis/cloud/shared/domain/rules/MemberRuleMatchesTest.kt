package network.lapis.cloud.shared.domain.rules

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemberRuleMatchesTest {
    private val today = LocalDate(2026, 10, 12)
    private val committeeA = "11111111-1111-1111-1111-111111111111"
    private val committeeB = "22222222-2222-2222-2222-222222222222"
    private val tierA = "33333333-3333-3333-3333-333333333333"

    private fun facts(
        status: MemberStatus = MemberStatus.ACTIVE,
        roles: Set<AccountRole> = setOf(AccountRole.MEMBER),
        joinedOn: LocalDate = LocalDate(2020, 1, 1),
        committees: Set<String> = emptySet(),
        tier: String? = null,
    ) = MemberFacts(status = status, roles = roles, joinedOn = joinedOn, activeCommitteeIds = committees, tierId = tier)

    @Test
    fun statusMatchesAndMisses() {
        val c = RuleCondition.Status(setOf(MemberStatus.ACTIVE, MemberStatus.DONOR))
        assertTrue(c.matches(facts = facts(status = MemberStatus.DONOR), today = today))
        assertFalse(c.matches(facts = facts(status = MemberStatus.GUEST), today = today))
    }

    @Test
    fun roleMatchesOnIntersectionOnly() {
        val c = RuleCondition.Role(setOf(AccountRole.BOARD, AccountRole.TREASURER))
        assertTrue(c.matches(facts = facts(roles = setOf(AccountRole.MEMBER, AccountRole.TREASURER)), today = today))
        assertFalse(c.matches(facts = facts(roles = setOf(AccountRole.MEMBER)), today = today))
        // a member without any account never matches a role condition
        assertFalse(c.matches(facts = facts(roles = emptySet()), today = today))
    }

    @Test
    fun joinedWithinDaysBoundaries() {
        val c = RuleCondition.JoinedWithinDays(30)
        assertTrue(c.matches(facts = facts(joinedOn = today), today = today), "today counts as 0 days ago")
        assertTrue(c.matches(facts = facts(joinedOn = LocalDate(2026, 9, 12)), today = today), "exactly 30 days ago")
        assertFalse(c.matches(facts = facts(joinedOn = LocalDate(2026, 9, 11)), today = today), "31 days ago")
        assertFalse(c.matches(facts = facts(joinedOn = LocalDate(2026, 10, 13)), today = today), "a join date in the future never matches")
    }

    @Test
    fun committeeAndTierAreSetMembership() {
        val committee = RuleCondition.Committee(setOf(committeeA))
        assertTrue(committee.matches(facts = facts(committees = setOf(committeeA, committeeB)), today = today))
        assertFalse(committee.matches(facts = facts(committees = setOf(committeeB)), today = today))
        // an unknown committee id simply matches nobody
        assertFalse(
            RuleCondition
                .Committee(
                    setOf("99999999-9999-9999-9999-999999999999"),
                ).matches(facts = facts(committees = setOf(committeeA)), today = today),
        )
        val tier = RuleCondition.Tier(setOf(tierA))
        assertTrue(tier.matches(facts = facts(tier = tierA), today = today))
        assertFalse(tier.matches(facts = facts(tier = null), today = today))
        assertFalse(tier.matches(facts = facts(tier = "44444444-4444-4444-4444-444444444444"), today = today))
    }

    @Test
    fun allAndAnyWithZeroOneAndManyConditions() {
        val f = facts(roles = setOf(AccountRole.BOARD), committees = setOf(committeeA))
        val role = RuleCondition.Role(setOf(AccountRole.BOARD))
        val committee = RuleCondition.Committee(setOf(committeeA))
        val miss = RuleCondition.Committee(setOf(committeeB))
        assertTrue(MemberRule(op = RuleOp.ALL, conditions = emptyList()).matches(facts = f, today = today), "ALL [] is true")
        assertFalse(MemberRule(op = RuleOp.ANY, conditions = emptyList()).matches(facts = f, today = today), "ANY [] is false")
        assertTrue(MemberRule(op = RuleOp.ALL, conditions = listOf(role)).matches(facts = f, today = today))
        assertTrue(MemberRule(op = RuleOp.ANY, conditions = listOf(role)).matches(facts = f, today = today))
        assertTrue(MemberRule(op = RuleOp.ALL, conditions = listOf(role, committee)).matches(facts = f, today = today))
        assertFalse(MemberRule(op = RuleOp.ALL, conditions = listOf(role, committee, miss)).matches(facts = f, today = today))
        assertTrue(MemberRule(op = RuleOp.ANY, conditions = listOf(miss, committee)).matches(facts = f, today = today))
        assertFalse(MemberRule(op = RuleOp.ANY, conditions = listOf(miss)).matches(facts = f, today = today))
    }

    @Test
    fun contextPolicyHidesStatusFromMailing() {
        assertFalse(RuleCondition.Status::class in RuleContextPolicy.allowed(RuleContext.MAILING))
        assertTrue(RuleCondition.Status::class in RuleContextPolicy.allowed(RuleContext.COMMITTEE))
        assertEquals(4, RuleContextPolicy.allowed(RuleContext.MAILING).size)
    }
}
