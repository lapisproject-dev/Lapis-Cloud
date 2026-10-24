package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.204 -- where the registrant message may be touched in the server code. It is free text of a (possibly anonymous) person; it must reach
 * exactly one reader (the BOARD/ADMIN overview and export), be written exactly once (the insert of the registration) and removed only by the
 * erasure. A new place that reads or writes the column turns this red and has to be looked at by a reviewer.
 */
class EventRegistrationMessageTripwireTest :
    FunSpec({
        val root = File("src/main/kotlin")

        fun filesUsing(token: String): List<String> =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }
                .filter { file ->
                    file.readLines().any { line ->
                        !line.trimStart().startsWith("*") &&
                            !line.trimStart().startsWith("//") &&
                            token in line
                    }
                }.map { it.name }
                .sorted()
                .toList()

        test(
            "the message column is read or written only by the store, the overview mapper, the export route, the notifier and the DSGVO contributor",
        ) {
            filesUsing("EventRegistrationTable.message") shouldContainExactlyInAnyOrder
                listOf(
                    "EventPersonalData.kt",
                    "EventRegistrationCsvRoutes.kt",
                    "EventRegistrationMessageNotifier.kt",
                    "EventService.kt",
                    "EventStore.kt",
                )
        }

        test("only the overview mapper puts the message into a DTO") {
            val service = File("src/main/kotlin/network/lapis/cloud/server/rpc/EventService.kt").readText()
            Regex("""message = this\[EventRegistrationTable\.message\]""").findAll(service).count() shouldBe 1
            service
                .substringAfter(
                    "private fun ResultRow.toOverviewRegistrationDto",
                ).substringBefore("private fun ResultRow.toRegistrationDto")
                .contains(
                    "message = this[EventRegistrationTable.message]",
                ) shouldBe true
        }

        test("the message is written in exactly two places: the insert of a registration and the erasure") {
            val writers =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }
                    .filter { f -> Regex("""it\[(EventRegistrationTable\.)?message\]\s*=""").containsMatchIn(f.readText()) }
                    .map { it.name }
                    .sorted()
                    .toList()
            writers shouldContainExactlyInAnyOrder listOf("EventPersonalData.kt", "EventStore.kt")
        }
    })
