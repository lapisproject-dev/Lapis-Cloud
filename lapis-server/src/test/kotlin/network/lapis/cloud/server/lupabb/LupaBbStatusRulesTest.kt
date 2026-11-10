package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusTransitions

/** V1.9.299 -- tripwire: the sync-only exception FRIEND -> DONOR is the only one, and the manual transition table stays closed for FRIEND. */
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

        test("a FRIEND may go nowhere else through the sync") {
            for (to in MemberStatus.entries.filter { it != MemberStatus.DONOR }) {
                LupaBbStatusRules.syncAllows(from = MemberStatus.FRIEND, to = to) shouldBe false
            }
        }

        test("the manual transition table stays closed for FRIEND (the exception never reaches the admin path)") {
            MemberStatusTransitions.allowedTargets(MemberStatus.FRIEND).shouldBeEmpty()
        }

        test("the exception is exactly FRIEND to DONOR") {
            LupaBbStatusRules.SYNC_ONLY_TRANSITIONS shouldBe setOf(MemberStatus.FRIEND to MemberStatus.DONOR)
        }

        test("for every other pair the sync follows the transition table exactly") {
            for (from in MemberStatus.entries) {
                for (to in MemberStatus.entries) {
                    if (from == MemberStatus.FRIEND && to == MemberStatus.DONOR) continue
                    LupaBbStatusRules.syncAllows(from = from, to = to) shouldBe (to in MemberStatusTransitions.allowedTargets(from))
                }
            }
        }
    })
