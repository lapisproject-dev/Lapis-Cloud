package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.io.File

/**
 * V1.9.200 tripwire: the narrow page shell `PageWidth.FORM` (60rem) is for forms. A file that builds a table under it squeezes the columns --
 * that happened to the receivable dunning levels (a table of seven columns on a 60rem page). Every client file with `PageWidth.FORM` that also
 * creates a table must be on [FORM_TABLE_ALLOW_LIST] with the reason, or use `PageWidth.WIDE`.
 *
 * Gradle runs server tests with `lapis-server` as the working directory; the sources are read by relative path, like [PageShellSourceScanTest].
 */
private val CLIENT_SOURCES_DIR =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val FORM_SHELL = Regex("""PageWidth\.FORM""")
private val TABLE_CREATION =
    Regex("""(\.|\s)(table|dataTable|plainDataTable|standardTable|lapisTable)\s*\(|\bTable\s*\(|<table|TAG\.TABLE""")

private fun isCommentOrImport(line: String): Boolean =
    line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("import ") }

/** file name -> why a table under the narrow form shell is fine there. Each entry needs a stated reason. */
private val FORM_TABLE_ALLOW_LIST: Map<String, String> =
    mapOf(
        // V1.9.202: the tender page reads like a form page (one column of facts, a bid form, board actions); its bid table has at most five narrow
        // columns (bidder, price, status, date, one icon action) and scrolls inside its own frame, never the page.
        "TenderDetailScreen.kt" to "reading page with a small bid table of at most five narrow columns",
    )

private fun codeLines(file: File): List<String> = file.readLines().filter { !isCommentOrImport(it) }

class FormShellTableSourceScanTest :
    FunSpec({
        test("no client file combines PageWidth.FORM with a table unless it is allow-listed with a reason") {
            val files = CLIENT_SOURCES_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            files.size shouldBeGreaterThan 100
            val offenders =
                files
                    .filter { it.name != "DataScreenLayout.kt" && it.name !in FORM_TABLE_ALLOW_LIST }
                    .filter { file ->
                        val code = codeLines(file)
                        code.any { FORM_SHELL.containsMatchIn(it) } && code.any { TABLE_CREATION.containsMatchIn(it) }
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }

        test("every allow-list entry has a reason and still is a FORM file with a table") {
            FORM_TABLE_ALLOW_LIST.values.forEach { it.shouldNotBeBlank() }
            FORM_TABLE_ALLOW_LIST.keys.forEach { name ->
                val file = CLIENT_SOURCES_DIR.walkTopDown().firstOrNull { it.isFile && it.name == name }
                (file != null) shouldBe true
                val code = codeLines(file!!)
                code.any { FORM_SHELL.containsMatchIn(it) } shouldBe true
                code.any { TABLE_CREATION.containsMatchIn(it) } shouldBe true
            }
        }

        test("the pattern really sees a table call (guards the guard)") {
            TABLE_CREATION.containsMatchIn("            val table = listPanel.table(") shouldBe true
            TABLE_CREATION.containsMatchIn("    listPanel.dataTable(columns = x)") shouldBe true
            TABLE_CREATION.containsMatchIn("    root.pageShell(PageWidth.FORM)") shouldBe false
        }

        test("the client mirror of the minimum stake equals the server constant") {
            val server =
                File("src/main/kotlin/network/lapis/cloud/server/rpc/SocialPostWeight.kt")
                    .let {
                        if (it.exists()) {
                            it
                        } else {
                            File(
                                "lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc/SocialPostWeight.kt",
                            )
                        }
                    }.readText()
            val client = File(CLIENT_SOURCES_DIR, "network/lapis/cloud/client/StakeSuggestion.kt").readText()
            val serverValue = Regex("""MIN_WEIGHT_LTR: BigDecimal = BigDecimal\("([0-9.]+)"\)""").find(server)?.groupValues?.get(1)
            val clientValue = Regex("""SOCIAL_POST_MIN_WEIGHT_LTR = "([0-9.]+)"""").find(client)?.groupValues?.get(1)
            (serverValue != null) shouldBe true
            clientValue shouldBe serverValue
        }
    })
