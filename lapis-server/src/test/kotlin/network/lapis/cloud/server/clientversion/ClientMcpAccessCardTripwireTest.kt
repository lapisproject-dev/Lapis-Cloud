package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.29 "KI-Zugang" -- what the member's MCP access card (`McpAccessCard.kt`) must never do, as a tripwire on the source (same heuristic
 * as [ConsensusSecrecyTripwireTest]; the behavioural evidence is in the Karma test `McpAccessCardDomTest`):
 *
 *  - the gate is really there: `featureEnabled`, `McpFeatureDisabledException`, `mcpEnabled` and a real `dataErrorState` (the error view);
 *  - the `tokenId` stays in the click closure: never in a widget text, attribute, `title` or `id`;
 *  - the agent-chosen `connectionLabel` is read only next to `sanitizeUntrustedI18nText`/`untrustedDiv` (it is untrusted text);
 *  - no `console.`/`println`/`localStorage`/`sessionStorage`, no exception `.message` anywhere (server text never reaches the screen);
 *  - the read path is not `guarded {}` (its toast would announce "forbidden"/"feature disabled" for a card that simply does not exist);
 *  - `DsgvoRightsScreen.kt` mounts the card right after the public profile card.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private const val CARD_FILE = "McpAccessCard.kt"

private val FORBIDDEN_EVERYWHERE =
    listOf(
        Regex("""\bconsole\."""),
        Regex("""\bprintln\s*\("""),
        Regex("""\blocalStorage\b"""),
        Regex("""\bsessionStorage\b"""),
        Regex("""\.message\b"""),
        Regex("""\bguarded\s*(<[^>]*>)?\s*\{"""),
    )

private val TOKEN_ID_IN_WIDGET =
    Regex("""\b(div|span|p|h\d|button|link|setAttribute|content|title|id|label|text)\b.*\btokenId\b""")

private fun codeLines(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

internal fun mcpAccessCardFindings(text: String): List<String> {
    val findings = mutableListOf<String>()
    val code = codeLines(text)
    code.forEachIndexed { index, line ->
        FORBIDDEN_EVERYWHERE.forEach { rule -> if (rule.containsMatchIn(line)) findings += "forbidden: ${line.trim()}" }
        if (TOKEN_ID_IN_WIDGET.containsMatchIn(line) && !line.contains("rpc.revoke(")) findings += "tokenId in a widget: ${line.trim()}"
        if (Regex("""\bconnectionLabel\b""").containsMatchIn(line)) {
            val window = code.subList(maxOf(0, index - 3), minOf(code.size, index + 4))
            if (window.none { it.contains("sanitizeUntrustedI18nText") || it.contains("untrustedDiv") }) {
                findings += "connectionLabel without sanitizing: ${line.trim()}"
            }
        }
    }
    return findings
}

class ClientMcpAccessCardTripwireTest :
    FunSpec({
        val card = File(CLIENT_DIR, CARD_FILE)

        test("the card file exists (tripwire is not vacuous) and carries its gate") {
            card.isFile shouldBe true
            val code = codeLines(card.readText()).joinToString("\n")
            listOf("featureEnabled", "McpFeatureDisabledException", "mcpEnabled", "dataErrorState").forEach { token ->
                (token in code) shouldBe true
            }
        }

        test("the card never leaks the token id, an unsanitized label, storage, logs or exception text") {
            mcpAccessCardFindings(card.readText()).shouldBeEmpty()
        }

        test("DsgvoRightsScreen mounts the card right after the public profile card") {
            val code = codeLines(File(CLIENT_DIR, "DsgvoRightsScreen.kt").readText())
            val profile = code.indexOfFirst { it.contains("renderMemberPublicProfileSection(root)") }
            val mcp = code.indexOfFirst { it.contains("renderMcpAccessSection(root)") }
            (profile >= 0) shouldBe true
            mcp shouldBe profile + 1
        }

        test("the detector flags each forbidden shape and ignores comments and the allowed uses") {
            mcpAccessCardFindings("console.log(x)").size shouldBe 1
            mcpAccessCardFindings("println(x)").size shouldBe 1
            mcpAccessCardFindings("window.localStorage.setItem(a, b)").size shouldBe 1
            mcpAccessCardFindings("notify(e.message)").size shouldBe 1
            mcpAccessCardFindings("val x = guarded { rpc.getState() }").size shouldBe 1
            mcpAccessCardFindings("val x = guarded<Unit> { rpc.getState() }").size shouldBe 1
            mcpAccessCardFindings("info.div(c.tokenId)").size shouldBe 1
            mcpAccessCardFindings("row.setAttribute(\"data-id\", c.tokenId)").size shouldBe 1
            mcpAccessCardFindings("info.div(c.connectionLabel)").size shouldBe 1
            mcpAccessCardFindings("val s = sanitizeUntrustedI18nText(c.connectionLabel)").size shouldBe 0
            mcpAccessCardFindings("{ launchWrite(null) { rpc.revoke(tokenId) } }").size shouldBe 0
            mcpAccessCardFindings("suspend fun revoke(tokenId: String): McpAccessStateDto").size shouldBe 0
            mcpAccessCardFindings("// console.log(tokenId)").size shouldBe 0
            mcpAccessCardFindings(" * guarded { } never, tokenId never in div(").size shouldBe 0
        }
    })
