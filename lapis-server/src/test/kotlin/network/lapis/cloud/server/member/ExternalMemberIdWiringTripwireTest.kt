package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.307 -- `Application.module` must hand the configured [VotingRightPolicy] to every service that has a LEGACY default (a default that is never
 * overridden would make the switch silently ineffective in production), load the configuration once and verify it at startup.
 */
class ExternalMemberIdWiringTripwireTest :
    FunSpec({
        val application = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/Application.kt").readText())

        fun callBody(name: String): String {
            val start = Regex("(?<![A-Za-z0-9_])" + name + "\\(").find(application)?.range?.first ?: -1
            check(start >= 0) { "$name( not found in Application.kt" }
            var depth = 0
            var i = application.indexOf('(', start)
            val begin = i
            while (i < application.length) {
                when (application[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return application.substring(begin, i + 1)
                    }
                }
                i++
            }
            error("unbalanced call $name")
        }

        test("every service with a LEGACY default receives the policy") {
            listOf(
                "GovernanceService",
                "TenderService",
                "ConferenceService",
                "DashboardService",
                "AuthService",
                "ProtocolDraftService",
                "MeetingProtocolService",
            ).forEach {
                callBody(it).contains("votingRightPolicy = votingRightPolicy") shouldBe true
            }
            listOf("ElectionService", "SystemicConsensusService").forEach {
                callBody(it).contains("votingRightPolicy = votingRightPolicy") shouldBe true
            }
            callBody("MemberService").contains("externalMemberIdConfig = externalMemberIdConfig") shouldBe true
            callBody("KeycloakMemberProvisioner").contains("externalIdConfig = externalMemberIdConfig") shouldBe true
        }

        test("the mobile conference routes receive the policy and hand it to their ConferenceService") {
            callBody("registerMobileConferenceRoutes").contains("votingRightPolicy = votingRightPolicy") shouldBe true
            val mobileFile = File(SourceScan.mainRoot(), "network/lapis/cloud/server/routes/MobileConferenceRoutes.kt")
            val mobile = SourceScan.blank(mobileFile.readText())
            val start = mobile.indexOf("ConferenceService(")
            check(start >= 0) { "ConferenceService( not found in MobileConferenceRoutes.kt" }
            val serviceCall = mobile.substring(start, mobile.indexOf("\n    get(", start))
            serviceCall.contains("votingRightPolicy = votingRightPolicy") shouldBe true
        }

        test("the configuration is loaded once, verified at startup, and the policy derives from it") {
            application.split("ExternalMemberIdConfig.load()").size - 1 shouldBe 1
            application.contains("externalMemberIdConfig.verifyAndLog(") shouldBe true
            application.contains("val votingRightPolicy = externalMemberIdConfig.votingPolicy()") shouldBe true
        }
    })
