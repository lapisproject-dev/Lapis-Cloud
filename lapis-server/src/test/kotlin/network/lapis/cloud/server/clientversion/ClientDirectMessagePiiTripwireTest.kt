package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.34 -- direct messages and volunteer-allowance declarations are personal data. A tripwire on the source of the three new client
 * files (same heuristic as [ClientSelfServicePiiTripwireTest]; behavioural evidence is in the Karma tests `DirectMessageConversationDomTest`,
 * `UnreadMessagesCounterTest`, `VolunteerAllowanceDeclarationsCardDomTest`):
 *
 * - (a) no console, no browser storage, no exception `message`;
 * - (b) no message body, name or recorder reaches a toast, the route, the document title or the history;
 * - (c) no timer in the counter or the conversation view (the counter refreshes on events only -- no polling);
 * - (d) `CommunicationScreen.kt` never combines `unreadCount` with a timer API.
 */
private val PII_FILES = listOf("DirectMessageConversation.kt", "UnreadMessagesCounter.kt", "VolunteerAllowanceDeclarationsCard.kt")
private val NO_TIMER_FILES = listOf("UnreadMessagesCounter.kt", "DirectMessageConversation.kt")

private val SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun codeLines(file: File): List<String> =
    file.readLines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }

private fun sourceOf(name: String): File = SOURCES.walkTopDown().first { it.isFile && it.name == name }

private val FORBIDDEN_API = Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""")
private val PII_FIELDS = "body|senderDisplayName|recipientDisplayName|recordedByDisplayName|otherDisplayName"
private val SINK = Regex("""(?:notify\w*|toast\w*|navigateTo|document\.title|window\.location|history\.).*\b(?:$PII_FIELDS)\b""")
private val TIMER = Regex("""\bsetInterval\b|\bsetTimeout\b|window\.setTimeout\b|\bdelay\(""")
private val UNREAD_WITH_TIMER = Regex("""unreadCount.*(?:setInterval|setTimeout|delay\()|(?:setInterval|setTimeout|delay\().*unreadCount""")

class ClientDirectMessagePiiTripwireTest :
    FunSpec({
        test("the files never use console, browser storage or an exception message") {
            val findings =
                PII_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { FORBIDDEN_API.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("no personal-data field reaches a toast, the route, the title or the history") {
            val findings =
                PII_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { SINK.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("the counter and the conversation view have no timer (no polling)") {
            val findings =
                NO_TIMER_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { TIMER.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("CommunicationScreen never combines unreadCount with a timer API") {
            val findings = codeLines(sourceOf("CommunicationScreen.kt")).filter { UNREAD_WITH_TIMER.containsMatchIn(it) }
            findings shouldBe emptyList()
        }

        test("the detectors see what they are meant to see") {
            FORBIDDEN_API.containsMatchIn("""notify(e.message)""") shouldBe true
            FORBIDDEN_API.containsMatchIn("""console.log(x)""") shouldBe true
            FORBIDDEN_API.containsMatchIn("""val message = x""") shouldBe false
            SINK.containsMatchIn("""notifyError(dto.body)""") shouldBe true
            SINK.containsMatchIn("""navigateTo("/m/" + message.recipientDisplayName)""") shouldBe true
            SINK.containsMatchIn("""notifySuccess(tr("Antwort wurde gesendet."))""") shouldBe false
            TIMER.containsMatchIn("""window.setTimeout({ refresh() }, 5000)""") shouldBe true
            TIMER.containsMatchIn("""delay(1000)""") shouldBe true
            TIMER.containsMatchIn("""UnreadMessages.refresh()""") shouldBe false
            UNREAD_WITH_TIMER.containsMatchIn("""delay(100); rpc.unreadCount()""") shouldBe true
            UNREAD_WITH_TIMER.containsMatchIn("""markReadThenRefreshCounter(ids)""") shouldBe false
        }
    })
