package network.lapis.cloud.server.dashboard

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import java.io.File

/**
 * V1.9.224 -- source-scan tripwires for the rules of the dashboard that a behavioural test cannot see: the counters never ask the media
 * server, the head-count sources never hand out an id, the gates come from `MemberStatusSets`, nothing logs a member, every source is
 * wired.
 */
class DashboardTripwireTest :
    FunSpec({
        val dashboardFiles =
            SourceScan.mainFiles().filter { it.path.replace('\\', '/').contains("/server/dashboard/") }
        val sourceFiles = dashboardFiles.filter { it.path.replace('\\', '/').contains("/dashboard/sources/") }

        test("the scan finds the files (guard against a path that silently matches nothing)") {
            (dashboardFiles.size >= 10) shouldBe true
            (sourceFiles.size >= 5) shouldBe true
        }

        test("the sidebar counters never ask the media server: no LiveKit anywhere in the dashboard package") {
            dashboardFiles.filter { SourceScan.blank(it.readText()).contains("LiveKit") }.map { it.name }.shouldBeEmpty()
            dashboardFiles
                .filter { it.name == "NavCountsQuery.kt" }
                .single()
                .readText()
                .lines()
                .filter { it.startsWith("import ") }
                .filter { "livekit" in it.lowercase() || "conference.LiveKit" in it }
                .shouldBeEmpty()
        }

        test("the role based head counts (applications, treasury) never hand out an id") {
            val file = sourceFiles.single { it.name == "MembershipSignals.kt" }
            val text = SourceScan.blank(file.readText())
            listOf("ApplicationSignals", "TreasurySignals").forEach { name ->
                val start = text.indexOf("object $name")
                val end = text.indexOf("\n}\n", start)
                withClue(name) { text.substring(start, end).contains("objectId") shouldBe false }
            }
        }

        test("no status is decided inline: the gates go through SignalGates / MemberStatusSets") {
            val offenders =
                dashboardFiles
                    .filter { it.name != "SignalGates.kt" }
                    .filter { SourceScan.blank(it.readText()).contains("MemberStatus.ACTIVE") }
                    .map { it.name }
            offenders.shouldBeEmpty()
        }

        test("nothing in the dashboard package logs a member: a log line carries at most the module key and the exception class") {
            val offenders =
                dashboardFiles.flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> line.contains("logger.") }
                        .filter { (_, line) ->
                            listOf("memberId", "member", "ctx.", "e.message", "e)").any { line.contains(it) }
                        }.map { (index, _) -> "${file.name}:${index + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test("every source object is wired in DashboardSources, with a module key of its own") {
            val declared =
                sourceFiles.sumOf { Regex("""object \w+ : DashboardSignalSource""").findAll(SourceScan.blank(it.readText())).count() }
            DashboardSources.all.size shouldBe declared
            DashboardSources.all
                .map { it.moduleKey }
                .distinct()
                .size shouldBe DashboardSources.all.size
            DashboardSources.all.forEach { (it.moduleKey in DashboardModuleKeys.ALL) shouldBe true }
        }

        test("every reason key of the shared contract is produced by a source, and no source invents one") {
            val code = sourceFiles.joinToString("\n") { SourceScan.blank(it.readText()) }
            val used = Regex("""DashboardReasonKeys\.([A-Z_]+)""").findAll(code).map { it.groupValues[1] }.toSet()
            val declared =
                DashboardReasonKeys::class.java.declaredFields
                    .filter { it.type == String::class.java && it.name != "INSTANCE" }
                    .map { it.name }
                    .toSet()
            used shouldBe declared
            DashboardReasonKeys.ALL.size shouldBe declared.size
        }

        test("the main source root is the one this scan expects") {
            File(SourceScan.mainRoot(), "network/lapis/cloud/server/dashboard").isDirectory shouldBe true
        }
    })
