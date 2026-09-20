package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwire for a bug found live on staging (V1.4.20, V1.4.21): KVision's `tr()` returns a string wrapped in
 * an internal `###KvI18nS###` marker that is only resolved inside KVision's own vnode patch cycle -- i.e.
 * when the `tr()` result is handed to a widget as its content/label. Anywhere the string is used before it
 * reaches a widget, the marker leaks into the page as visible text ("###KvI18nS###inaktiv"):
 *  - fed straight into `setAttribute(...)` (title / aria-label: a screen reader announces the marker),
 *  - embedded in a string template (`"${tr("E-Mail")}: ..."`),
 *  - concatenated with `+`,
 *  - passed as an argument of `gettext(...)` (`gettext("Status: %1", tr("aktiv"))`).
 * Use `gettext(...)` for all of these. Gradle runs server tests with `lapis-server` as the working
 * directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val TR_CALL = Regex("""\btr\(""")

private val LEAKING_TR_USES: List<Pair<String, Regex>> =
    listOf(
        "tr() fed into setAttribute()" to Regex("""setAttribute\([^)\n]*\btr\("""),
        "tr() inside a string template" to Regex("""\$\{[^}\n]*\btr\("""),
        "tr() concatenated with +" to Regex("""\btr\("[^"\n]*"\)\s*\+|\+\s*tr\("""),
    )

/** `true` if a `tr(` sits INSIDE the parentheses of a `gettext(...)` call on this line (nested argument). */
private fun trNestedInGettext(line: String): Boolean {
    var from = line.indexOf("gettext(")
    while (from >= 0) {
        var depth = 0
        var end = -1
        for (i in from + "gettext".length until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        val call = if (end >= 0) line.substring(from, end) else line.substring(from)
        if (TR_CALL.containsMatchIn(call.removePrefix("gettext("))) return true
        from = line.indexOf("gettext(", from + 1)
    }
    return false
}

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun leaks(line: String): List<String> =
    if (isCommentLine(line)) {
        emptyList()
    } else {
        LEAKING_TR_USES.filter { (_, regex) -> regex.containsMatchIn(line) }.map { it.first } +
            (if (trNestedInGettext(line)) listOf("tr() passed as an argument of gettext()") else emptyList())
    }

class ClientTrAttributeLeakTest :
    FunSpec({
        test("no client source lets a tr() result leak the KVision i18n marker") {
            val offenders =
                CLIENT_SOURCES
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().flatMapIndexed { index, line ->
                            leaks(line).map { kind -> "${file.name}:${index + 1} [$kind]: ${line.trim()}" }
                        }
                    }.toList()
            offenders.shouldBeEmpty()
        }

        test("the scanned client source directory exists (tripwire is not vacuous)") {
            CLIENT_SOURCES.isDirectory shouldBe true
        }

        test("every leaking pattern flags its own bad example, and the good examples pass") {
            val dollar = '$'
            leaks("el.setAttribute(\"title\", tr(\"Ausblenden\"))") shouldBe listOf("tr() fed into setAttribute()")
            leaks("div(\"$dollar{tr(\"E-Mail\")}: x\")") shouldBe listOf("tr() inside a string template")
            leaks("tr(\"Widerrufen am\") + \" x\"") shouldBe listOf("tr() concatenated with +")
            leaks("gettext(\"Status: %1\", if (a) tr(\"aktiv\") else tr(\"inaktiv\"))") shouldBe
                listOf("tr() passed as an argument of gettext()")
            // NOT leaks: separate calls on one line, plain tr() as widget content, comments.
            leaks("row(gettext(\"Ende\"), x ?: tr(\"laufend\"))") shouldBe emptyList()
            leaks("span(content = tr(\"Neu laden\"))") shouldBe emptyList()
            leaks("// gettext(\"x\", tr(\"y\")) is exactly what this test forbids") shouldBe emptyList()
        }
    })
