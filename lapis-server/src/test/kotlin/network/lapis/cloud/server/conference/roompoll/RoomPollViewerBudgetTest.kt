package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan
import network.lapis.cloud.shared.domain.RoomPollRules

/** The polling budget of the viewer page against the per-account limits of the two read calls. */
class RoomPollViewerBudgetTest :
    FunSpec({
        test(
            "a visible viewer tab makes at most 12 version calls a minute, two tabs stay below the limit, the jitter keeps 5 to 6 seconds",
        ) {
            val worstCaseGapMs = RoomPollRules.VIEWER_VERSION_POLL_MS
            val callsPerMinute = 60_000 / worstCaseGapMs
            (callsPerMinute <= 12) shouldBe true
            (2 * callsPerMinute < RoomPollRules.VERSION_READS_PER_MINUTE) shouldBe true
            (RoomPollRules.VIEWER_VERSION_JITTER_MS in 0..1_000) shouldBe true
            // the state read happens only on a change and after an own answer: far below its limit
            (callsPerMinute < RoomPollRules.STATE_READS_PER_MINUTE) shouldBe true
        }

        test("the process-wide limiters in Application.kt use the shared constants, not literals") {
            val app = SourceScan.blank(EncounterSourceScan.mainFile("Application.kt").readText())
            app.contains(
                "val roomPollStateLimiter = FederationInboxRateLimiter(maxRequests = RoomPollRules.STATE_READS_PER_MINUTE",
            ) shouldBe true
            app.contains(
                "val roomPollVersionLimiter = FederationInboxRateLimiter(maxRequests = RoomPollRules.VERSION_READS_PER_MINUTE",
            ) shouldBe true
        }
    })
