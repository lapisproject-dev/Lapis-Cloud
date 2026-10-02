package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.38 -- the client has ONE zone: the organization's. The browser's own zone (`TimeZone.currentSystemDefault()`) must never
 * decide what "today" or "now" is, or what a stored time means -- a member on a laptop in another country would otherwise see and
 * enter times in a zone the organization does not use. `OrganizationTime.kt` is the only file that may touch a zone source; every other
 * file asks it (`organizationNow()`, `organizationToday()`, `toOrganizationZone(...)`). Budget 0, comment lines exempt.
 *
 * A bare `Clock.System.now()` stays legal (epoch milliseconds for ids and timers carry no zone); what is forbidden is turning it
 * into a wall-clock with `.toLocalDateTime(`, which is exactly where a zone sneaks in.
 */
private val CLIENT_MAIN_DIR =
    File("../lapis-client/src/jsMain/kotlin").let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val BROWSER_ZONE = Regex("""currentSystemDefault""")
private val NOW_TO_WALL_CLOCK = Regex("""Clock\.System\s*\.now\(\)\s*\.toLocalDateTime\(""")

private fun codeOf(file: File): String =
    file
        .readLines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .joinToString("\n")

class ClientImplicitZoneTripwireTest :
    FunSpec({
        val files = CLIENT_MAIN_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "OrganizationTime.kt" }.toList()

        test("Z4: no browser-zone API in the client outside OrganizationTime.kt") {
            val offenders = files.filter { BROWSER_ZONE.containsMatchIn(codeOf(it)) }.map { it.name }
            withClue(
                "Use organizationNow()/organizationToday()/toOrganizationZone() from OrganizationTime.kt instead:\n${offenders.joinToString(
                    "\n",
                )}",
            ) {
                offenders.shouldBeEmpty()
            }
        }

        test("Z5: Clock.System.now() is never turned into a wall-clock outside OrganizationTime.kt") {
            val offenders = files.filter { NOW_TO_WALL_CLOCK.containsMatchIn(codeOf(it)) }.map { it.name }
            withClue("Use organizationNow() instead:\n${offenders.joinToString("\n")}") { offenders.shouldBeEmpty() }
        }

        test("Z6 (self-test): the patterns match the forbidden shapes and not the legal ones") {
            BROWSER_ZONE.containsMatchIn("TimeZone.currentSystemDefault()") shouldBe true
            NOW_TO_WALL_CLOCK.containsMatchIn("Clock.System.now().toLocalDateTime(TimeZone.UTC)") shouldBe true
            NOW_TO_WALL_CLOCK.containsMatchIn("Clock.System\n        .now()\n        .toLocalDateTime(zone)") shouldBe true
            NOW_TO_WALL_CLOCK.containsMatchIn("Clock.System.now().toEpochMilliseconds()") shouldBe false
            BROWSER_ZONE.containsMatchIn("resolveZone(OrganizationTime.zoneId)") shouldBe false
        }

        test("Z7: OrganizationTime.kt does not use Intl or toLocale formatting (a zone name would then depend on the browser's ICU)") {
            val text = codeOf(File(CLIENT_MAIN_DIR, "network/lapis/cloud/client/OrganizationTime.kt"))
            Regex("""Intl\.DateTimeFormat|toLocaleDateString\(|toLocaleTimeString\(|toLocaleString\(""").containsMatchIn(text) shouldBe
                false
        }
    })
