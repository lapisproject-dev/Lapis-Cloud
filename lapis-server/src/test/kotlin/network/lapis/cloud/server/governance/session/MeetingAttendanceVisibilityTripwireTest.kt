package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.241 -- the visibility rule stays in one place: `loadAttendance(` is called only by the three reads that gate on
 * [MeetingAttendanceVisibility], and [MeetingSessionAccess] reaches its role only through the same rule.
 */
class MeetingAttendanceVisibilityTripwireTest :
    FunSpec({
        val service = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/GovernanceService.kt").readText())

        fun bodyOf(function: String): String {
            val start = service.indexOf("override suspend fun $function(")
            check(start >= 0) { "missing $function" }
            val next = service.indexOf("\n    override suspend fun ", start + 10)
            return service.substring(start, if (next < 0) service.length else next)
        }

        test("loadAttendance is called only from getMeetingDetail, getAttendance and generateProtocolDraft -- each gated by the rule") {
            val allowed = listOf("getMeetingDetail", "getAttendance", "generateProtocolDraft")
            val calls = Regex("""\bloadAttendance\(""").findAll(service).count()
            // one declaration plus one call per allowed function
            calls shouldBe allowed.size + 1
            allowed.forEach { name ->
                val body = bodyOf(name)
                Regex("""\bloadAttendance\(""").containsMatchIn(body) shouldBe true
                body.contains("MeetingAttendanceVisibility") shouldBe true
            }
        }

        test("checkQuorum is gated by the rule too") {
            bodyOf("checkQuorum").contains("MeetingAttendanceVisibility.requireVisibleMeetingRow") shouldBe true
        }

        test("MeetingSessionAccess resolves a role only after the visibility rule") {
            val file = File(SourceScan.mainRoot(), "network/lapis/cloud/server/governance/session/MeetingSessionAccess.kt")
            val code = SourceScan.blank(file.readText())
            Regex("""MeetingAttendanceVisibility\.canSeeAttendance""").findAll(code).count() shouldBe 2
            // no other caller of rolesOf in the main sources besides the rule itself and resolveMeeting's context
            Regex("""\brolesOf\(""").findAll(code).count() shouldBe 2
        }
    })
