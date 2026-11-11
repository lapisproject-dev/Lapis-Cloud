package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusTransitions

/** V1.9.299 -- tripwire: the sync-only exceptions are FRIEND -> DONOR / WITHDRAWN / DECEASED and nothing else, and the manual transition table stays closed for FRIEND. */
class LupaBbStatusRulesTest :
    FunSpec({
        test("the sync may move a legacy FRIEND to DONOR") {
            LupaBbStatusRules.syncAllows(
                from = MemberStatus.FRIEND,
                to = MemberStatus.DONOR,
                memberPersonNumber = 5L,
                recordPersonNumber = 5L,
            ) shouldBe true
        }

        test("FRIEND to DONOR is refused without a matching stored person number") {
            for (member in listOf(null, 6L)) {
                LupaBbStatusRules.syncAllows(
                    from = MemberStatus.FRIEND,
                    to = MemberStatus.DONOR,
                    memberPersonNumber = member,
                    recordPersonNumber = 5L,
                ) shouldBe false
            }
            LupaBbStatusRules.syncAllows(from = MemberStatus.FRIEND, to = MemberStatus.DONOR) shouldBe false
        }

        test("the end of a friendship is taken over: FRIEND to WITHDRAWN and DECEASED need the matching person number") {
            for (to in listOf(MemberStatus.WITHDRAWN, MemberStatus.DECEASED)) {
                LupaBbStatusRules.syncAllows(from = MemberStatus.FRIEND, to = to, memberPersonNumber = 5L, recordPersonNumber = 5L) shouldBe
                    true
                for (member in listOf(null, 6L)) {
                    LupaBbStatusRules.syncAllows(
                        from = MemberStatus.FRIEND,
                        to = to,
                        memberPersonNumber = member,
                        recordPersonNumber = 5L,
                    ) shouldBe
                        false
                }
                LupaBbStatusRules.syncAllows(from = MemberStatus.FRIEND, to = to) shouldBe false
            }
        }

        test(
            "the beginning of a membership is never taken over: FRIEND to ACTIVE, APPLICATION, GUEST, REJECTED stay refused, even with the number",
        ) {
            val allowed = setOf(MemberStatus.DONOR, MemberStatus.WITHDRAWN, MemberStatus.DECEASED)
            for (to in MemberStatus.entries.filter { it !in allowed }) {
                LupaBbStatusRules.syncAllows(from = MemberStatus.FRIEND, to = to) shouldBe false
                LupaBbStatusRules.syncAllows(from = MemberStatus.FRIEND, to = to, memberPersonNumber = 5L, recordPersonNumber = 5L) shouldBe
                    false
            }
        }

        test("the manual transition table stays closed for FRIEND (the exception never reaches the admin path)") {
            MemberStatusTransitions.allowedTargets(MemberStatus.FRIEND).shouldBeEmpty()
        }

        test("the exceptions are exactly FRIEND to DONOR, WITHDRAWN and DECEASED") {
            LupaBbStatusRules.SYNC_ONLY_TRANSITIONS shouldBe
                setOf(
                    MemberStatus.FRIEND to MemberStatus.DONOR,
                    MemberStatus.FRIEND to MemberStatus.WITHDRAWN,
                    MemberStatus.FRIEND to MemberStatus.DECEASED,
                )
        }

        test("for every other pair the sync follows the transition table exactly") {
            for (from in MemberStatus.entries) {
                for (to in MemberStatus.entries) {
                    if ((from to to) in LupaBbStatusRules.SYNC_ONLY_TRANSITIONS) continue
                    LupaBbStatusRules.syncAllows(from = from, to = to) shouldBe (to in MemberStatusTransitions.allowedTargets(from))
                }
            }
        }
    })
