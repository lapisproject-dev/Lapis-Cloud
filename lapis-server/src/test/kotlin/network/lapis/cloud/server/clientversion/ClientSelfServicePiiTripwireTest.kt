package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.33 -- the three member self-service files handle personal data (address, date of birth, nationality, card state) and event
 * registrations. A tripwire on their source (same heuristic as [ClientMcpAccessCardTripwireTest]; the behavioural evidence is in the
 * Karma tests `MemberAddressCardDomTest`, `MemberCardRevokeCardDomTest`, `MemberEventsScreenDomTest`): none of them may write to the
 * console or to browser storage, read an exception's `message` (Kilua RPC never transmits it, and showing it would leak server text),
 * or hand a personal-data DTO field to a toast.
 */
private val SELF_SERVICE_FILES = listOf("MemberAddressCard.kt", "MemberCardRevokeCard.kt", "MemberEventsScreen.kt")

private val SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun codeLines(file: File): List<String> =
    file.readLines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }

private fun sourceOf(name: String): File = SOURCES.walkTopDown().first { it.isFile && it.name == name }

private val PII_FIELDS = "street|postalCode|city|country|dateOfBirth|nationality|memberNumber"

class ClientSelfServicePiiTripwireTest :
    FunSpec({
        test("the self-service files never use console, browser storage or an exception message") {
            val forbidden = Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""")
            val findings =
                SELF_SERVICE_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { forbidden.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("no toast receives a personal-data DTO field") {
            val toastWithPii = Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:$PII_FIELDS)\b""")
            val findings =
                SELF_SERVICE_FILES.flatMap { name ->
                    codeLines(sourceOf(name)).filter { toastWithPii.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
                }
            findings shouldBe emptyList()
        }

        test("the detector sees what it is meant to see") {
            Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""").containsMatchIn("""console.log(x)""") shouldBe true
            Regex("""\bconsole\.|\blocalStorage\b|\bsessionStorage\b|\.message\b""").containsMatchIn("""notify(e.message)""") shouldBe true
            Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:$PII_FIELDS)\b""").containsMatchIn("""notifyError(dto.street)""") shouldBe true
            Regex("""(?:notify\w*|toast\w*)\([^)]*\b(?:$PII_FIELDS)\b""").containsMatchIn("""toastError(gettext("Fehler"))""") shouldBe
                false
        }
    })
