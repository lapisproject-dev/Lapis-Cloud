package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwire for the W5 page header (V1.4.31, rules R6/R7): every screen builds its ONE `h1` through `pageHeader`
 * (`network.lapis.cloud.client.PageHeader`), section titles are `h2.h5`, and `document.title` is written only by
 * the header. A static text scan, not a proof of the rendered DOM -- the behavioural evidence is
 * `PageHeaderDomTest`/`PageHeaderScreensDomTest` in the Karma tests of `lapis-client`.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** A raw `h1(...)` factory call (the constructor `H1(` of the header itself has a capital `H`). */
private val RAW_H1 = Regex("""(?<![\w])h1\(""")

/** A call of the page header (not its declaration). */
private val PAGE_HEADER_CALL = Regex("""(?<![\w])pageHeader\(""")

private fun isPageHeaderCall(line: String): Boolean = PAGE_HEADER_CALL.containsMatchIn(line) && !line.contains("fun ")

/** A size class on the same line: `h5`/`h6` as a whole word (inside a class string or a class list). */
private val SIZE_CLASS = Regex("""\bh[56]\b""")

/** An `h2(`/`h3(` factory call. */
private val H2_OR_H3_CALL = Regex("""(?<![\w])h([23])\(""")

/** A `document.title` WRITE (an assignment, not a read). */
private val DOCUMENT_TITLE_WRITE = Regex("""document\.title\s*=[^=]""")

/**
 * Files that build the page header more than once, with the reason. Every other file has at most one call.
 * Each of these builds its calls in DIFFERENT states of one screen (never twice in one render).
 */
private val PAGE_HEADER_CALLS_PER_FILE: Map<String, Pair<Int, String>> =
    mapOf(
        "RegistrationScreen.kt" to (2 to "form state and the 'application submitted' state"),
        "FriendRegistrationScreen.kt" to (2 to "form state and the 'account created' state"),
        "SocialNetworkScreen.kt" to (2 to "timeline state and thread state"),
        "MemberFinancialHistoryScreen.kt" to (2 to "own history (isSelf) and another member's history: exclusive branches"),
    )

/** Files that may write `document.title` besides the header itself, with the reason. */
private val DOCUMENT_TITLE_WRITERS: Map<String, String> =
    mapOf(
        "PageHeader.kt" to "the header itself",
        "ConferenceScreen.kt" to "recording/streaming marker prefix and its restore when the room is left",
    )

private fun clientFiles(): List<File> = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

private fun codeLines(file: File): List<String> = file.readLines().filterNot { isCommentLine(it) }

private fun rawH1Findings(files: List<File>): List<String> =
    files.filter { it.name != "PageHeader.kt" }.flatMap { file ->
        codeLines(file).filter { RAW_H1.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" }
    }

private fun pageHeaderCalls(files: List<File>): Map<String, Int> =
    files
        .filter { it.name != "PageHeader.kt" }
        .associate { file -> file.name to codeLines(file).count { isPageHeaderCall(it) } }
        .filterValues { it > 0 }

/** `h2`/`h3` calls whose line carries no `h5`/`h6` size class (the W5 step-down of R7). */
private fun unsizedSectionTitles(files: List<File>): List<String> =
    files.flatMap { file ->
        codeLines(file)
            .filter { H2_OR_H3_CALL.containsMatchIn(it) && !SIZE_CLASS.containsMatchIn(it) }
            .map { "${file.name}: ${it.trim()}" }
    }

class ClientPageHeaderTripwireTest :
    FunSpec({
        test("the scan sees the client sources and the page header (not vacuous)") {
            clientFiles().size shouldBeGreaterThan 100
            pageHeaderCalls(clientFiles()).values.sum() shouldBeGreaterThan 60
        }

        test("no screen builds a raw h1 -- the page header is the only source of the h1") {
            rawH1Findings(clientFiles()).shouldBeEmpty()
        }

        test("at most one page header call per file, except the audited multi-state screens") {
            val problems =
                pageHeaderCalls(clientFiles()).mapNotNull { (file, count) ->
                    val allowed = PAGE_HEADER_CALLS_PER_FILE[file]?.first ?: 1
                    if (count > allowed) "$file: $count pageHeader calls, audited $allowed" else null
                }
            problems.shouldBeEmpty()
            // A table entry that no longer matches reality is stale.
            val actual = pageHeaderCalls(clientFiles())
            PAGE_HEADER_CALLS_PER_FILE.filter { (file, entry) -> actual[file] != entry.first }.keys.shouldBeEmpty()
        }

        test("document.title is written only by the page header and the audited conference marker") {
            val writers =
                clientFiles()
                    .filter { file -> codeLines(file).any { DOCUMENT_TITLE_WRITE.containsMatchIn(it) } }
                    .map { it.name }
                    .filterNot { it in DOCUMENT_TITLE_WRITERS }
            writers.shouldBeEmpty()
        }

        test("every h2 is sized h5 and every h3 h6 (R7): the h1 is the only large heading") {
            unsizedSectionTitles(clientFiles()).shouldBeEmpty()
        }

        test("the detectors recognise a positive and a negative example") {
            RAW_H1.containsMatchIn("    root.h1(tr(\"X\"))") shouldBe true
            RAW_H1.containsMatchIn("    val heading = H1(content = title)") shouldBe false
            RAW_H1.containsMatchIn("    root.pageHeader(tr(\"X\"))") shouldBe false
            isPageHeaderCall("    root.pageHeader(tr(\"X\"))") shouldBe true
            isPageHeaderCall("fun Container.pageHeader(") shouldBe false
            SIZE_CLASS.containsMatchIn("    root.h2(tr(\"X\")) { addCssClasses(\"h5 mt-2\") }") shouldBe true
            SIZE_CLASS.containsMatchIn("    root.h2(tr(\"X\"))") shouldBe false
            DOCUMENT_TITLE_WRITE.containsMatchIn("    document.title = \"x\"") shouldBe true
            DOCUMENT_TITLE_WRITE.containsMatchIn("    val t = document.title") shouldBe false
            DOCUMENT_TITLE_WRITE.containsMatchIn("    if (a == document.title == b)") shouldBe false
            H2_OR_H3_CALL.containsMatchIn("    root.h2(tr(\"X\"))") shouldBe true
            H2_OR_H3_CALL.containsMatchIn("    root.h2(tr(\"X\")) { addCssClass(\"h5\") }") shouldBe true
        }
    })
