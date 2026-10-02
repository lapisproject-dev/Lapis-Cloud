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
 * - (d) `CommunicationScreen.kt` never combines `unreadCount` with a timer API;
 * - (e) V1.9.36: the tab-return listeners of the counter exist only together with the timestamp throttle (no timer), and no navigation
 *   target built from a message/person field (the `SINK` already covers `navigateTo`/`window.location`).
 */
private val PII_FILES =
    listOf(
        "DirectMessageConversation.kt",
        "UnreadMessagesCounter.kt",
        "VolunteerAllowanceDeclarationsCard.kt",
        // V1.9.36
        "NavbarUnreadIndicator.kt",
        "DirectMessagePartnerList.kt",
        "DirectMessageReplyForm.kt",
    )
private val NO_TIMER_FILES =
    listOf(
        "UnreadMessagesCounter.kt",
        "DirectMessageConversation.kt",
        "NavbarUnreadIndicator.kt",
        "DirectMessagePartnerList.kt",
        "DirectMessageReplyForm.kt",
    )

private val SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun codeLines(file: File): List<String> =
    file.readLines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }

private fun sourceOf(name: String): File = SOURCES.walkTopDown().first { it.isFile && it.name == name }

private val FORBIDDEN_API = Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""")
private val PII_FIELDS = "body|senderDisplayName|recipientDisplayName|recordedByDisplayName|otherDisplayName|partnerDisplayName|displayName"
private val SINK = Regex("""(?:notify\w*|toast\w*|navigateTo|document\.title|window\.location|history\.).*\b(?:$PII_FIELDS)\b""")
private val TIMER =
    Regex("""\bsetInterval\b|\bsetTimeout\b|window\.setTimeout\b|window\.setInterval\b|\brequestAnimationFrame\b|\bdelay\(""")
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

        test("the counter's tab-return listeners come with the throttle constant and without any timer") {
            val lines = codeLines(sourceOf("UnreadMessagesCounter.kt"))
            val text = lines.joinToString("\n")
            text.contains("addEventListener(\"visibilitychange\"") shouldBe true
            text.contains("addEventListener(\"focus\"") shouldBe true
            text.contains("TAB_RETURN_REFRESH_THROTTLE_MS") shouldBe true
            lines.filter { TIMER.containsMatchIn(it) } shouldBe emptyList()
        }

        test("the navbar envelope links to a fixed section target without any id or personal field") {
            val lines = codeLines(sourceOf("NavbarUnreadIndicator.kt"))
            val target = lines.filter { it.contains("section=") }
            target.size shouldBe 1
            target.single().contains("memberId") shouldBe false
            lines.filter { SINK.containsMatchIn(it) } shouldBe emptyList()
        }

        test("the detectors see what they are meant to see") {
            TIMER.containsMatchIn("""window.setInterval(f, 10)""") shouldBe true
            TIMER.containsMatchIn("""requestAnimationFrame(f)""") shouldBe true
            SINK.containsMatchIn("""notifyError(partner.partnerDisplayName)""") shouldBe true
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
