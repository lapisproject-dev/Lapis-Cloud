package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.145 -- privacy tripwire of the member finder client. Party membership is a special category of data and the finder is built so that nobody learns
 * who is behind a request before both sides agreed. The client side of that promise is checked statically, on the code of `MemberFinder*.kt` with comments and
 * string contents blanked ([SourceScan.blank]):
 *
 * - (a) no console, no browser storage, no `innerHTML`, no raw HTML (`rich = true`, `unsafe`);
 * - (b) no id of a member is ever read: the fields `senderId`, `recipientId`, `memberId`, `fromMemberId`, `toMemberId` do not appear (the answer of
 *   `contactConnection` carries both ids of the conversation and is dropped);
 * - (c) the two free texts of somebody else -- the note and the name of the other side -- only ever reach the page through the `untrusted*` helpers, and
 *   never reach a toast, an attribute, the title, the navigation, `tr()` or `gettext()`;
 * - (d) the navigation probe keeps its answer in memory only.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val FINDER_FILES: List<File> =
    CLIENT_DIR.listFiles { f -> f.isFile && f.name.startsWith("MemberFinder") && f.extension == "kt" }.orEmpty().sortedBy { it.name }

private val BANNED_CALLS =
    listOf(
        Regex("""\bconsole\b"""),
        Regex("""\blocalStorage\b"""),
        Regex("""\bsessionStorage\b"""),
        Regex("""\bindexedDB\b"""),
        Regex("""\binnerHTML\b"""),
        Regex("""\bunsafe\b"""),
        Regex("""\brich\s*=\s*true\b"""),
    )

private val MEMBER_IDENTITY_FIELDS = Regex("""\b(?:senderId|recipientId|memberId|fromMemberId|toMemberId)\b""")

/** A line that hands the name or the note to something that must never show them. */
private val FORBIDDEN_SINKS =
    Regex(
        """(?:\bnotify\w*|\bsetAttribute|\bnavigateTo|\btr|\bgettext)\s*\([^\n]*\b(?:otherDisplayName|note)\b|document\.title[^\n]*\b(?:otherDisplayName|note)\b""",
    )

class ClientMemberFinderPrivacyTripwireTest :
    FunSpec({
        val code = FINDER_FILES.associate { it.name to SourceScan.blank(it.readText()) }

        test("the scan sees the finder files (not vacuous)") {
            (FINDER_FILES.size >= 5) shouldBe true
            code.values.all { it.isNotBlank() } shouldBe true
        }

        test("(a) no console, no browser storage, no innerHTML, no raw HTML") {
            val findings =
                code.flatMap { (name, text) ->
                    BANNED_CALLS.flatMap { rule -> rule.findAll(text).map { "$name: ${it.value}" }.toList() }
                }
            findings.shouldBeEmpty()
        }

        test("(b) no id of a member is read anywhere") {
            val findings = code.flatMap { (name, text) -> MEMBER_IDENTITY_FIELDS.findAll(text).map { "$name: ${it.value}" }.toList() }
            findings.shouldBeEmpty()
        }

        test("(c) the name of the other side and the note reach the page only through the untrusted helpers") {
            val sinks =
                code.flatMap { (name, _) ->
                    // string contents matter here (a gettext template), so the raw text with comment lines removed is scanned
                    File(CLIENT_DIR, name)
                        .readLines()
                        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                        .filter { FORBIDDEN_SINKS.containsMatchIn(it) }
                        .map { "$name: ${it.trim()}" }
                }
            sinks.shouldBeEmpty()
            val screen = File(CLIENT_DIR, "MemberFinderScreen.kt").readText()
            withClue("the name goes through the heading helper, the note through the div helper") {
                (screen.contains("untrustedHeading(connection.otherDisplayName")) shouldBe true
                (screen.contains("untrustedDiv(note")) shouldBe true
            }
            val uses =
                code
                    .filterKeys { it != "MemberFinderLabels.kt" }
                    .flatMap { (name, text) ->
                        text
                            .lines()
                            .filter { Regex("""\botherDisplayName\b""").containsMatchIn(it) }
                            .map { "$name: ${it.trim()}" }
                    }
            withClue("otherDisplayName is used once: in the connection card") { uses.size shouldBe 1 }
        }

        test("(c) the detector sees what it is meant to see") {
            FORBIDDEN_SINKS.containsMatchIn("""notifySuccess(gettext("x %1", connection.otherDisplayName))""") shouldBe true
            FORBIDDEN_SINKS.containsMatchIn("""el.setAttribute("aria-label", request.note)""") shouldBe true
            FORBIDDEN_SINKS.containsMatchIn("""item.untrustedDiv(note, className = "text-break")""") shouldBe false
            MEMBER_IDENTITY_FIELDS.containsMatchIn("answer.recipientId") shouldBe true
            BANNED_CALLS.any { it.containsMatchIn("localStorage[\"x\"] = y") } shouldBe true
        }

        test("(d) the navigation probe keeps its answer in memory only") {
            val nav = code.getValue("MemberFinderNav.kt")
            listOf("localStorage", "sessionStorage", "indexedDB", "document.cookie", "history.").forEach { nav.contains(it) shouldBe false }
        }

        test("only the random ids of requests, connections and blocks are written into the DOM as data attributes") {
            val written =
                code.flatMap { (_, text) ->
                    Regex("""setAttribute\(\s*"(data-[a-z-]+)"""").findAll(text).map { it.groupValues[1] }.toList()
                }
            (written.toSet() - setOf("data-request-id", "data-connection-id", "data-block-id")).shouldBeEmpty()
        }
    })
