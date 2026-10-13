package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.encounter.EncounterSourceScan

/**
 * V1.9.127 -- the raised-hand files log counts and exception classes only, never an identity, a name or a room id (a raised hand ties a
 * person to a speaking request). Every `logger.<level> { ... }` template of the three files may interpolate only the names below.
 * (The one log line of `setRoomReactions` carries no interpolation at all.)
 */
class ConferenceHandsLogPrivacyTripwireTest :
    FunSpec({
        val files =
            listOf(
                "rpc/ConferenceHandService.kt",
                "conference/ConferenceHandQueueReconciler.kt",
                "conference/ConferenceHandQueueState.kt",
            )
        val allowedInterpolations = setOf("removed", "failedRooms", "e::class.simpleName")

        test("a logger template of the hand files interpolates only counts and exception class names") {
            var seen = 0
            val offenders = mutableListOf<String>()
            files.forEach { name ->
                EncounterSourceScan.mainFile(name).readLines().forEachIndexed { index, line ->
                    if (!Regex("""logger\.(trace|debug|info|warn|error)\b""").containsMatchIn(line)) return@forEachIndexed
                    seen++
                    Regex("""\$\{([^}]*)}|\$([A-Za-z_]\w*)""").findAll(line).forEach { match ->
                        val name2 = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        if (name2 !in allowedInterpolations) offenders += "$name:${index + 1}: \${$name2}"
                    }
                }
            }
            seen shouldBeGreaterThan 3
            offenders.shouldBeEmpty()
        }

        test("the hand service and the state never log through println or the exception message") {
            listOf(
                "rpc/ConferenceHandService.kt",
                "conference/ConferenceHandQueueReconciler.kt",
                "conference/ConferenceHandQueueState.kt",
            ).forEach { name ->
                val code = EncounterSourceScan.mainFile(name).readText()
                listOf("println(", "e.message", "e.stackTrace", "printStackTrace").forEach { forbidden ->
                    code.contains(forbidden) shouldBe false
                }
            }
        }
    })
