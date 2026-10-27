package network.lapis.cloud.server.governance.meetingevent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.227 -- `event.meeting_id` has exactly one writer: the `governance.meetingevent` package. A second writer (an edit path that clears or
 * moves the link by accident, a service that links behind the audit trail) would break both the 1:1 rule's audit and the lock order.
 */
class MeetingEventLinkWriterTripwireTest :
    FunSpec({
        val mainRoot = File("src/main/kotlin")
        val writeLine = Regex("""\[(EventTable\.)?meetingId\]\s*=[^=]""")

        test("no file outside the governance.meetingevent package assigns EventTable.meetingId") {
            mainRoot.exists() shouldBe true
            val offenders =
                mainRoot
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { !it.path.replace('\\', '/').contains("/governance/meetingevent/") }
                    .filter { it.name != "EventTable.kt" }
                    .filter { file ->
                        val text = file.readText()
                        // Only files that touch the event table can be writers of its column.
                        text.contains("EventTable") && text.lineSequence().any { writeLine.containsMatchIn(it) }
                    }.map { it.path }
                    .toList()
            offenders.shouldBeEmpty()
        }

        test("the package itself writes the column (the tripwire pattern matches what it guards)") {
            val own = File(mainRoot, "network/lapis/cloud/server/governance/meetingevent/MeetingEventLinkStore.kt").readText()
            own.lineSequence().any { writeLine.containsMatchIn(it) } shouldBe true
        }

        test("EventStore.insertEvent / updateEvent never mention the column") {
            File(mainRoot, "network/lapis/cloud/server/events/EventStore.kt").readText().contains("meetingId") shouldBe false
        }
    })
