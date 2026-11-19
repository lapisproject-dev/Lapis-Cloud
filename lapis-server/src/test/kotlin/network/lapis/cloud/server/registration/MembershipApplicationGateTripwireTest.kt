package network.lapis.cloud.server.registration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.315 -- tripwire for the switch "Mitglied werden": (1) every `override suspend fun` of `RegistrationService` that creates a membership
 * application (writes `MemberStatus.APPLICATION`) starts with `requireMembershipApplicationEnabled()`, and (2) no OTHER production file
 * writes `MemberStatus.APPLICATION` as a status without being on the reviewed allowlist (every new path must be decided: gated self-service
 * or internal/admin). Reads the source text on purpose.
 */
class MembershipApplicationGateTripwireTest :
    FunSpec({
        val mainRoot = File("src/main/kotlin")
        val registration = File(mainRoot, "network/lapis/cloud/server/rpc/RegistrationService.kt")

        test("every self-service path of RegistrationService that creates an application calls the gate as its first statement") {
            val source = registration.readText()
            val starts = Regex("""override suspend fun (\w+)\(""").findAll(source).toList()
            val writers = mutableListOf<String>()
            val ungated = mutableListOf<String>()
            starts.forEachIndexed { index, match ->
                val end = starts.getOrNull(index + 1)?.range?.first ?: source.length
                val body = source.substring(match.range.first, end)
                if (Regex("""\[status\]\s*=\s*MemberStatus\.APPLICATION""").containsMatchIn(body)) {
                    val name = match.groupValues[1]
                    writers += name
                    val afterBrace = body.substringAfter("{", "")
                    val firstStatement = afterBrace.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("//") }
                    if (firstStatement != "requireMembershipApplicationEnabled()") ungated += name
                }
            }
            // The two writers today; createMemberDirect is the admin/board path (status chosen by the board, not self-service).
            writers.toSet().containsAll(listOf("registerApplication", "applyForMembership")) shouldBe true
            ungated.filterNot { it == "createMemberDirect" }.shouldBeEmpty()
        }

        test("getMembershipAgreement, registerApplication and applyForMembership each call the gate first") {
            val source = registration.readText()
            for (name in listOf("getMembershipAgreement", "registerApplication", "applyForMembership")) {
                val start = source.indexOf("override suspend fun $name(")
                (start >= 0) shouldBe true
                val body = source.substring(start).substringAfter("{")
                val first = body.lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
                first shouldBe "requireMembershipApplicationEnabled()"
            }
        }

        test("only reviewed production files assign MemberStatus.APPLICATION as a new status") {
            val allowed =
                setOf(
                    "RegistrationService.kt", // gated self-service + the status history rows of the same paths
                    "StagingSeedData.kt", // seed data
                    "LupaBbStatusMapping.kt", // admin-triggered sync of an external register ("in Aufnahme")
                )
            val assignment = Regex("""(?:\[status\]|\bstatus|\bnewStatus)\s*=\s*MemberStatus\.APPLICATION""")
            val assigners =
                mainRoot
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { assignment.containsMatchIn(it.readText()) }
                    .map { it.name }
                    .toSet()
            // A new writer must be decided: a gated self-service path (call requireMembershipApplicationEnabled first) or an internal/admin path.
            (assigners - allowed).shouldBeEmpty()
        }
    })
