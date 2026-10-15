package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.encounter.EncounterSourceScan

private val TIMER_FILES =
    listOf(
        "rpc/ConferenceTimerService.kt",
        "conference/ConferenceTimerReconciler.kt",
        "conference/ConferenceTimerState.kt",
    )

/**
 * V1.9.133 -- the timer files log counts and exception classes only, never a room id, a member id or an identity. Every
 * `logger.<level> { ... }` template of the three files may interpolate only the names below.
 */
class ConferenceTimerLogPrivacyTripwireTest :
    FunSpec({
        val allowedInterpolations = setOf("failedRooms", "e::class.simpleName")

        test("a logger template of the timer files interpolates only counts and exception class names") {
            var seen = 0
            val offenders = mutableListOf<String>()
            TIMER_FILES.forEach { name ->
                EncounterSourceScan.mainFile(name).readLines().forEachIndexed { index, line ->
                    if (!Regex("""logger\.(trace|debug|info|warn|error)\b""").containsMatchIn(line)) return@forEachIndexed
                    seen++
                    Regex("""\$\{([^}]*)}|\$([A-Za-z_]\w*)""").findAll(line).forEach { match ->
                        val interpolated = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        if (interpolated !in allowedInterpolations) offenders += "$name:${index + 1}: \${$interpolated}"
                    }
                }
            }
            seen shouldBeGreaterThan 2
            offenders.shouldBeEmpty()
        }

        test("the timer files never log through println or the exception message") {
            TIMER_FILES.forEach { name ->
                val code = EncounterSourceScan.mainFile(name).readText()
                listOf("println(", "e.message", "e.stackTrace", "printStackTrace").forEach { forbidden ->
                    code.contains(forbidden) shouldBe false
                }
            }
        }
    })
