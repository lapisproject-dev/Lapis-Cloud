package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.59 -- exposure tripwire: the member-count history is for BOARD/ADMIN only, through ONE RPC service. The files that
 * reference `MemberStatusHistoryTable`, `MemberCountAggregation` or `MemberStatisticsService` are pinned; none of them may sit in a
 * package that is reachable without a signed-in BOARD/ADMIN (public API, MCP, embeds, webhooks, federation, plain routes).
 */
class MemberStatisticsExposureTripwireTest :
    FunSpec({
        // path (relative to src/main/kotlin) -> why it may touch the history
        val allowlist =
            mapOf(
                "network/lapis/cloud/server/db/generated/MemberStatusHistoryTable.kt" to "the table",
                "network/lapis/cloud/server/member/MemberStatusHistory.kt" to "the one writer (recorder) and the consistency check",
                "network/lapis/cloud/server/member/MemberCountAggregation.kt" to "the pure aggregation",
                "network/lapis/cloud/server/rpc/MemberStatisticsService.kt" to "the BOARD/ADMIN RPC service",
                "network/lapis/cloud/server/dsgvo/MemberStatusHistoryPersonalData.kt" to
                    "Art. 15 export / Art. 17 retention of the subject's own rows",
                "network/lapis/cloud/server/dsgvo/PersonalDataRegistry.kt" to "contributor registration",
                "network/lapis/cloud/server/Application.kt" to "registers the RPC service; the startup consistency signal",
                "network/lapis/cloud/server/keycloak/KeycloakMemberProvisioner.kt" to
                    "counts its own KEYCLOAK_JIT rows of the last hour for the creation rate limit; exposes no count",
            )
        val forbiddenPackages = listOf("mcp", "embed", "routes", "webhook", "federation", "publicapi", "api")
        val reference =
            Regex(
                """MemberStatusHistoryTable|MemberCountAggregation|MemberStatisticsService|MemberStatusHistoryConsistency|MemberStatusHistoryPersonalData""",
            )

        fun referencing(): Set<String> {
            val root = SourceScan.mainRoot()
            return SourceScan
                .mainFiles()
                .filter { reference.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                .toSet()
        }

        test("only the allowlisted files reference the history, its aggregation or its service") {
            // the writers of member.status call MemberStatusHistory (the recorder), which does not match the pattern above
            referencing() shouldBe allowlist.keys
        }

        test("nothing under mcp, embed, routes, webhook, federation or the public API touches the history") {
            val offenders =
                referencing().filter { path ->
                    val pkg = path.removePrefix("network/lapis/cloud/server/").substringBeforeLast('/', "")
                    forbiddenPackages.any { pkg == it || pkg.startsWith("$it/") }
                }
            offenders shouldBe emptyList()
        }

        test("the service is registered once, in the authenticated RPC block of Application.kt") {
            val app = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/Application.kt").readText())
            Regex("""registerService\(IMemberStatisticsService::class\)""").findAll(app).count() shouldBe 1
        }
    })
