package network.lapis.cloud.server.mail.budget

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.mail.secondsUntil
import java.util.Random
import kotlin.time.Duration.Companion.seconds

/** Welle V1.9.81 -- the pure budget decision, including a randomized simulation of the sliding window. */
class MailBudgetTest :
    FunSpec({
        val t0 = LocalDateTime(2031, 5, 1, 10, 0, 0)

        test("limits per lane: SYSTEM may use the whole maximum, BULK only what the reserve leaves") {
            MailBudget.limitFor(lane = MailLane.SYSTEM, max = 250, reserve = 50) shouldBe 250
            MailBudget.limitFor(lane = MailLane.BULK, max = 250, reserve = 50) shouldBe 200
        }

        test("SYSTEM is allowed up to max - 1 used, BULK up to (max - reserve) - 1 used") {
            MailBudget.decide(
                lane = MailLane.SYSTEM,
                usedInWindow = 9,
                max = 10,
                reserve = 2,
                nthOldestForRelease = null,
                now = t0,
            ) shouldBe
                BudgetDecision.Allowed
            MailBudget.decide(lane = MailLane.BULK, usedInWindow = 7, max = 10, reserve = 2, nthOldestForRelease = null, now = t0) shouldBe
                BudgetDecision.Allowed
            MailBudget.decide(lane = MailLane.BULK, usedInWindow = 8, max = 10, reserve = 2, nthOldestForRelease = t0, now = t0) shouldBe
                BudgetDecision.WaitUntil(t0.plusDuration(MailBudget.WINDOW))
            MailBudget.decide(lane = MailLane.SYSTEM, usedInWindow = 10, max = 10, reserve = 2, nthOldestForRelease = t0, now = t0) shouldBe
                BudgetDecision.WaitUntil(t0.plusDuration(MailBudget.WINDOW))
        }

        test("WaitUntil is the releasing slot plus one full window -- exactly 3600 s later") {
            val release = t0.plusDuration(120.seconds)
            val decision =
                MailBudget.decide(lane = MailLane.SYSTEM, usedInWindow = 10, max = 10, reserve = 2, nthOldestForRelease = release, now = t0)
            decision shouldBe BudgetDecision.WaitUntil(release.plusDuration(3600.seconds))
            release.secondsUntil((decision as BudgetDecision.WaitUntil).at) shouldBe 3600
        }

        test("after a configuration change the used count can exceed the maximum: the release slot is further back, never an Allowed") {
            // 14 used, max lowered to 10: four more slots must expire before a send is allowed (the caller loads index used - limit = 4)
            val fifth = t0.plusDuration(40.seconds)
            MailBudget.decide(
                lane = MailLane.SYSTEM,
                usedInWindow = 14,
                max = 10,
                reserve = 2,
                nthOldestForRelease = fifth,
                now = t0,
            ) shouldBe
                BudgetDecision.WaitUntil(fifth.plusDuration(MailBudget.WINDOW))
        }

        test("an exhausted budget without a release slot (inconsistent input) re-checks shortly instead of guessing Allowed") {
            MailBudget.decide(lane = MailLane.BULK, usedInWindow = 99, max = 10, reserve = 2, nthOldestForRelease = null, now = t0) shouldBe
                BudgetDecision.WaitUntil(t0.plusDuration(MailBudget.RECHECK))
        }

        test("simulation: with randomly interleaved lanes no 3600 s window ever holds more than max, BULK never more than max - reserve") {
            val random = Random(81)
            repeat(20) { round ->
                val max = 10 + random.nextInt(40)
                val reserve = 1 + random.nextInt(max - 1)
                val slots = mutableListOf<LocalDateTime>() // reservation instants, ascending
                var now = t0
                var allowedCount = 0
                repeat(1500) {
                    now = now.plusDuration(random.nextInt(60).seconds)
                    val lane = if (random.nextInt(100) < 70) MailLane.BULK else MailLane.SYSTEM
                    val cutoff = now.plusDuration((-3600).seconds)
                    val inWindow = slots.filter { it > cutoff }
                    val limit = MailBudget.limitFor(lane = lane, max = max, reserve = reserve)
                    val nth = if (inWindow.size >= limit) inWindow.sorted()[inWindow.size - limit] else null
                    when (
                        val decision =
                            MailBudget.decide(
                                lane = lane,
                                usedInWindow = inWindow.size,
                                max = max,
                                reserve = reserve,
                                nthOldestForRelease = nth,
                                now = now,
                            )
                    ) {
                        BudgetDecision.Allowed -> {
                            slots += now
                            allowedCount++
                            val after = slots.filter { it > cutoff }.size
                            (after <= max) shouldBe true
                            if (lane == MailLane.BULK) (after <= max - reserve) shouldBe true
                        }
                        is BudgetDecision.WaitUntil -> {
                            (decision.at > now) shouldBe true
                            // at that instant the same request must be allowed
                            val later = decision.at
                            val laterCutoff = later.plusDuration((-3600).seconds)
                            val laterWindow = slots.filter { it > laterCutoff }
                            (laterWindow.size < limit) shouldBe true
                        }
                    }
                }
                withClue("round $round max=$max reserve=$reserve") { (allowedCount > 0) shouldBe true }
            }
        }
    })
