package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * R34 ratchet (W5, V1.4.31): a screen that READS from the server shows its load as one of the shared states (loading text, error with
 * "Erneut versuchen", empty text, content) -- `dataSection`, or the building blocks `dataSectionViews`/`dataErrorState`. A static scan
 * cannot prove a screen does that; what it can do is count the read calls of every client file that uses NONE of those building
 * blocks, and let that number only go down. The list of files below is the ledger of what is still to migrate.
 *
 * A "read call" is `rpcService<..>().<name>(` where the name starts with list/get/find/load/search/read/fetch/current/my/status/count/
 * summary/preview/resolve/history. The scan is a floor, not a ceiling: a load through a helper or an unusual verb is not seen. It is also
 * FILE-granular (audit V1.4.31): a file with ONE state block counts as migrated even if it reads elsewhere without one -- so "0 reads outside a
 * state region" for a migrated file means "has a state block", not "every read of it is inside one". Counting per read would need the
 * region boundaries, which a text scan cannot see.
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val READ_CALL =
    Regex(
        """rpcService<\w+>\(\)\s*\.\s*(?:list|get|find|load|search|read|fetch|current|my|status|count|summary|preview|resolve|history)\w*\(""",
    )

private val STATE_BLOCK = Regex("""\b(?:dataSection|dataSectionViews|dataErrorState)\b""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** Read calls per file, for files that use no state block at all. */
private fun readsOutsideStateRegions(files: List<File>): Map<String, Int> =
    files
        .associate { file ->
            val code = file.readLines().filterNot { isCommentLine(it) }.joinToString("\n")
            file.name to if (STATE_BLOCK.containsMatchIn(code)) 0 else READ_CALL.findAll(code).count()
        }.filterValues { it > 0 }

/**
 * Measured after W5's migration (PostalMail, EventRooms, Catering, MyVolunteerShifts, the LTR balance strip). Only ever lowered.
 * The lower bound keeps the scanner honest: a broken regex that suddenly finds much less fails the second assertion.
 */
private const val READ_OUTSIDE_STATE_REGION_MAX = 147
private const val FILES_OUTSIDE_STATE_REGION_MAX = 44

/**
 * A `when` branch that returns a raw German label (`X -> "Geplant"`) shows German in every language: a label goes through
 * `gettext`/`tr`. Measured before W5: 33 such branches (committee types, meeting/resolution/motion/vote statuses). Now 0.
 */
private val RAW_GERMAN_BRANCH = Regex("""^\s*[\w.]+\s*->\s*"[A-ZÄÖÜ]""")

private fun rawGermanBranchFindings(files: List<File>): List<String> =
    files.flatMap { file ->
        file
            .readLines()
            .filterNot { isCommentLine(it) }
            .filter { RAW_GERMAN_BRANCH.containsMatchIn(it) }
            .map { "${file.name}: ${it.trim()}" }
    }

class ClientDataStateTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("R34 ratchet: read calls in files without any state block only ever go down, and the scanner is not vacuous") {
            val findings = readsOutsideStateRegions(files)
            val total = findings.values.sum()
            (total <= READ_OUTSIDE_STATE_REGION_MAX) shouldBe true
            (total >= READ_OUTSIDE_STATE_REGION_MAX - 5) shouldBe true
            (findings.size <= FILES_OUTSIDE_STATE_REGION_MAX) shouldBe true
            (findings.size >= FILES_OUTSIDE_STATE_REGION_MAX - 3) shouldBe true
        }

        test("no when branch returns a raw German label (must go through gettext/tr), and the detector sees one") {
            rawGermanBranchFindings(files) shouldBe emptyList()
            RAW_GERMAN_BRANCH.containsMatchIn("        MeetingStatus.PLANNED -> \"Geplant\"") shouldBe true
            RAW_GERMAN_BRANCH.containsMatchIn("        MeetingStatus.PLANNED -> gettext(\"Geplant\")") shouldBe false
            RAW_GERMAN_BRANCH.containsMatchIn("        Kind.LIVE -> \"rtmp://example\"") shouldBe false
        }

        test("the migrated W5 screens use a state block, the detector sees a read call and a state block, and skips comments") {
            listOf("PostalMailScreen.kt", "EventRoomsScreen.kt", "CateringScreen.kt", "MyVolunteerShiftsScreen.kt", "LtrLedgerScreen.kt")
                .forEach { name -> (name in readsOutsideStateRegions(files)) shouldBe false }
            READ_CALL.containsMatchIn("    guarded { rpcService<IEventService>().listEvents(q) }") shouldBe true
            READ_CALL.containsMatchIn("    rpcService<IEventService>()\n        .getEvent(id)") shouldBe true
            READ_CALL.containsMatchIn("    rpcService<IEventService>().createEvent(input)") shouldBe false
            STATE_BLOCK.containsMatchIn("    root.dataSection<List<X>>(") shouldBe true
            isCommentLine("    // rpcService<IEventService>().listEvents(q)") shouldBe true
        }
    })
