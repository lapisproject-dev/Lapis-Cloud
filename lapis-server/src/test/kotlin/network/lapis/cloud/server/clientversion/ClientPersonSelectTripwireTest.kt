package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * A plain `<select>` is unusable once it lists hundreds of PEOPLE. Every person picker of the client is a
 * `SearchableSelect` (`searchableSelect(...)` / `searchableSelectField(...)`, see `SearchableSelect.kt`); this
 * tripwire fails when a NEW plain `select(...)`/`selectField(...)` is built from person names again.
 *
 * What it detects (a heuristic over source text, like the sibling tripwires, with a stated recall limit):
 *  A. a `select(`/`selectField(` CALL whose text mentions a person-name field ([PERSON_FIELD]) -- directly
 *     (`options = members.map { it.id to it.displayName }`) or through a local `val memberOptions = ...` that the call
 *     refers to by name (`options = memberOptions`), resolved in the same file;
 *  B. an ASSIGNMENT `x.options = ...` (including its more deeply indented continuation lines) that mentions a person-name field, where `x` was
 *     bound in the same file to a KVision `Select` (`val x = ....select(...)` or `val x = ... as Select`). Names bound to a
 *     `SearchableSelect` are ignored.
 *
 * NOT detected (known gaps, stated rather than hidden): options built in a helper function or another file and handed in
 * as a parameter; person names under a field name that is not in [PERSON_FIELD]; a `Select` reached through a property
 * chain instead of a local `val`.
 *
 * The [ALLOWED] ledger lists the plain selects that are person pickers ON PURPOSE. It may only shrink: an entry that no
 * longer matches anything fails the test (stale), and so does a finding that is not listed.
 */
private val CLIENT_SOURCES_DIR =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val PERSON_FIELD = Regex("""\b(?:displayName|memberDisplayName|fullName|personName|donorName|contactName|participantName)\b""")
private val SELECT_CALL = Regex("""(?<![A-Za-z0-9_])(?:select|selectField)\(""")
private val OPTIONS_IDENTIFIER = Regex("""options\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*[,)]""")
private val SELECT_BINDING = Regex("""val\s+(\w+)\s*=[^\n]*?(?:(?<![A-Za-z0-9_])select\(|as\s+Select\b)""")
private val OPTIONS_ASSIGNMENT = Regex("""(\w+)\.options\s*=""")

/** file name -> (number of tolerated findings, why). Only ever shrinks. */
private val ALLOWED: Map<String, Pair<Int, String>> =
    mapOf(
        "MemberFamiliesScreen.kt" to
            (
                1 to
                    "memberPicker: a search field plus a server-side search (`listMembersForAdministration`, limit = 20) that fills " +
                    "a plain Select -- the server already narrows the list to 20 entries, a client filter would only filter those. " +
                    "Its options go through untrustedOptions (MemberFamiliesScreenTest pins the marker case)."
            ),
    )

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun codeOnly(text: String): String = text.lines().joinToString("\n") { if (isCommentLine(it)) "" else it }

/** The text from the `(` at [open] to its matching `)` (strings are not parsed, as in the sibling tripwires). */
private fun balanced(
    text: String,
    open: Int,
): String {
    var depth = 0
    var i = open
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return text.substring(open, i + 1)
            }
        }
        i++
    }
    return text.substring(open)
}

/** The statement of `val [name] = ...`: its line plus the continuation lines (indented deeper than the `val`). */
private fun valStatement(
    code: String,
    name: String,
): String? {
    val lines = code.lines()
    val start = lines.indexOfFirst { Regex("""\bval\s+$name\s*=""").containsMatchIn(it) }
    if (start < 0) return null
    val indent = lines[start].takeWhile { it == ' ' }.length
    val continuation = lines.drop(start + 1).takeWhile { it.isNotBlank() && it.takeWhile { c -> c == ' ' }.length > indent }
    return (listOf(lines[start]) + continuation).joinToString("\n")
}

internal fun plainPersonSelectFindings(text: String): List<String> {
    val code = codeOnly(text)
    val findings = mutableListOf<String>()
    // A: calls
    for (match in SELECT_CALL.findAll(code)) {
        val call = balanced(text = code, open = match.range.last)
        val direct = PERSON_FIELD.containsMatchIn(call)
        val viaVal =
            OPTIONS_IDENTIFIER.find(call)?.groupValues?.get(1)?.let { name ->
                valStatement(code = code, name = name)?.let { PERSON_FIELD.containsMatchIn(it) }
            }
        if (direct || viaVal == true) findings += "call: ${call.lines().first().trim().take(100)}"
    }
    // B: assignments to a Select-bound name
    val selectNames = SELECT_BINDING.findAll(code).map { it.groupValues[1] }.toSet()
    val lines = code.lines()
    lines.forEachIndexed { index, line ->
        val assignment = OPTIONS_ASSIGNMENT.find(line) ?: return@forEachIndexed
        if (assignment.groupValues[1] !in selectNames) return@forEachIndexed
        val indent = line.takeWhile { it == ' ' }.length
        val continuation = lines.drop(index + 1).takeWhile { it.isNotBlank() && it.takeWhile { c -> c == ' ' }.length > indent }
        val statement = (listOf(line) + continuation).joinToString("\n")
        if (PERSON_FIELD.containsMatchIn(statement)) findings += "assignment: ${line.trim().take(100)}"
    }
    return findings
}

class ClientPersonSelectTripwireTest :
    FunSpec({
        fun actual(): Map<String, Int> =
            CLIENT_SOURCES_DIR
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate { it.name to plainPersonSelectFindings(it.readText()).size }
                .filterValues { it > 0 }

        test("every person picker is a SearchableSelect: no plain select built from person names outside the ledger") {
            val found = actual()
            val problems =
                (found.keys + ALLOWED.keys).sorted().mapNotNull { file ->
                    val n = found[file] ?: 0
                    val allowed = ALLOWED[file]?.first ?: 0
                    if (n != allowed) {
                        "$file: $n plain person select(s), ledger says $allowed -- use searchableSelect/searchableSelectField " +
                            "(SearchableSelect.kt), or justify the exception in ALLOWED (it may only shrink)"
                    } else {
                        null
                    }
                }
            problems shouldBe emptyList()
        }

        test("the scanner sees the client sources (not vacuous)") {
            (CLIENT_SOURCES_DIR.walkTopDown().count { it.isFile && it.extension == "kt" } > 100) shouldBe true
            // a file that really uses the component is scanned and reports nothing
            val sample = File(CLIENT_SOURCES_DIR, "network/lapis/cloud/client/SearchableSelect.kt")
            sample.exists() shouldBe true
        }

        test("detector: flags a plain select over person names, directly and through a local val") {
            val direct = """panel.select(options = members.map { it.id to it.displayName }, label = "Mitglied")"""
            plainPersonSelectFindings(direct).size shouldBe 1
            val viaVal =
                """
                val memberOptions = members.map { it.id to it.displayName }
                val s = panel.select(options = memberOptions, label = "Mitglied")
                """.trimIndent()
            plainPersonSelectFindings(viaVal).size shouldBe 1
            val field =
                """form.selectField(label = "Mitglied", options = untrustedOptions(members.map { it.id to it.memberDisplayName }))"""
            plainPersonSelectFindings(field).size shouldBe 1
        }

        test("detector: flags an options assignment on a Select, ignores a SearchableSelect and comments") {
            val select =
                """
                val picker = row.select(options = emptyList(), label = "Mitglied")
                picker.options = page.rows.map { it.id to it.displayName }
                """.trimIndent()
            plainPersonSelectFindings(select).size shouldBe 1
            val cast =
                """
                val s = field.control as Select
                s.options = members.map { it.id to it.displayName }
                """.trimIndent()
            plainPersonSelectFindings(cast).size shouldBe 1
            val searchable =
                """
                val s = field.control as SearchableSelect
                s.options = members.map { it.id to it.displayName }
                val t = panel.searchableSelect(options = members.map { it.id to it.displayName }, label = "Mitglied")
                // panel.select(options = members.map { it.id to it.displayName })
                """.trimIndent()
            plainPersonSelectFindings(searchable).size shouldBe 0
        }

        test("detector: a select over non-person data is not a finding") {
            plainPersonSelectFindings("""panel.select(options = accounts.map { it.id to it.name }, label = "Konto")""").size shouldBe 0
            plainPersonSelectFindings("""panel.select(options = tiers.map { it.id to it.name }, label = "Tarif")""").size shouldBe 0
        }
    })
