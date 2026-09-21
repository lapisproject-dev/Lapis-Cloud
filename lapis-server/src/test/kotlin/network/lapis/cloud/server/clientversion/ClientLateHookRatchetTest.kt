package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * A RATCHET (not a proof) for the KVision "late hook" trap, found in V1.4.25 on `dataTable` and audited across
 * the whole client afterwards.
 *
 * `Widget.addAfterInsertHook`/`addAfterDestroyHook` give the widget a distinct snabbdom key on the FIRST hook.
 * Registered after the widget was rendered into a mounted tree, the key changes between two renders: the next
 * patch replaces the element, runs the destroy hook on a widget that is still alive, and fires the insert hook
 * only for the replacement -- or never, if nothing patches again. The safe shapes are: hooks registered before
 * the widget is added (`addWithLifecycle`, `network.lapis.cloud.client.KvisionLifecycle`), before its first
 * render (`getElement() ?: addAfterInsertHook { }`), or on a widget that has no element yet (built in a
 * hidden or detached tree).
 *
 * A static scan cannot tell which of these a call site is in. What it CAN do is make every new direct hook
 * call a conscious decision: the table below lists how many direct calls each client file has and WHY they
 * are fine, and a new one breaks this test until someone has looked at it (and, ideally, used
 * `addWithLifecycle` instead, which needs no entry). The behavioural evidence lives in the Karma tests of
 * `lapis-client` (`KvisionHookOrderDomTest`, `LateHookAuditDomTest`, `ConferenceScreenRootLifecycleDomTest`,
 * `PriceOracleChartLifecycleDomTest`, `ConferenceNotesFocusDomTest`, `ConferenceBackgroundSectionDomTest`, `DataTableModeSwitchDomTest`).
 *
 * If you REMOVE a direct call, lower the count. Gradle runs server tests with `lapis-server` as the working
 * directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

/** A direct hook call: not a `return@addAfterInsertHook` label, followed by `(` or a trailing lambda. */
private val DIRECT_HOOK_CALL = Regex("""(?<![@\w])addAfter(?:Insert|Destroy)Hook\b\s*[({]""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** file name -> (allowed number of direct hook calls, why each is fine). */
private val AUDITED_DIRECT_HOOK_CALLS: Map<String, Pair<Int, String>> =
    mapOf(
        "KvisionLifecycle.kt" to (2 to "the helper itself: registers, then adds"),
        "DataTable.kt" to
            (
                3 to
                    "host hooks registered before add(host); the sort button's hook runs inside singleRender, " +
                    "the button has no element yet"
            ),
        "PriceOracleScreen.kt" to
            (
                2 to
                    "screen-root destroy hook and canvas host insert hook are registered late, but load() patches synchronously " +
                    "right after, before any chart exists (PriceOracleChartLifecycleDomTest)"
            ),
        "ConferenceBackgroundSection.kt" to
            (2 to "RawAttributes hook and tile keyboard hook: the group is hidden while the tiles are built, so no element exists yet"),
        "ConferenceRecordingsPanel.kt" to
            (1 to "raw <video> in a late insert hook; more widgets follow in the same card, exactly one video results"),
        "ConferenceWhiteboardController.kt" to
            (5 to "built in the hidden whiteboard panel: no element exists when the hooks are registered"),
        "ConferenceScreen.kt" to
            (
                10 to
                    "role=alert banners (hidden first; the live call path cannot be mounted in a test, so they were not converted), " +
                    "roster/chat badges (raw child, next add patches), stage/grid zones " +
                    "(first fire is the replacement, before any tile exists), chatRow (hidden panel), setStaticA11yLabel/" +
                    "setDynamicA11yTitle (getElement() ?: hook idiom)"
            ),
    )

/**
 * A raw DOM `setAttribute` goes around KVision's patch cycle: the attribute is lost when the root is rebuilt (language switch) and,
 * for text, carries the `###KvI18nS###` marker. KVision's `Widget.setAttribute(name, value)` (survives a re-render) or a property is
 * the way.
 *
 * Two detectors (audit V1.4.31 -- the first alone only saw ONE spelling of the trap and the "hard zero" claim was wrong):
 *  1. [RAW_SET_ATTRIBUTE], the direct chain `getElement()?.setAttribute(`: a hard zero, nothing is audited.
 *  2. The WINDOWED scan: a `setAttribute(` within [RAW_DOM_WINDOW] lines after a raw element source (`getElement()` or `vnode.elm`) --
 *     the spellings `?.let { el -> el.setAttribute }`, `?.apply { setAttribute }`, `vnode.elm ... setAttribute`, `!!.`, a stored
 *     element variable. It cannot tell a raw DOM element from a `Widget` (both call `setAttribute`), so what it finds is a LEDGER
 *     ([AUDITED_RAW_DOM_SET_ATTRIBUTE], file -> number of matches + why each is fine); a new one breaks the test until someone has
 *     looked at it. A recall limit is stated, not hidden: an element kept in a field and written to further than the window away is
 *     not seen. Counted per MATCH, not per line (two writes on one line are two findings).
 */
private val RAW_SET_ATTRIBUTE = Regex("""getElement\(\)\??\.setAttribute\(""")
private val RAW_DOM_SOURCE = Regex("""getElement\(\)|\.elm\b""")
private val ANY_SET_ATTRIBUTE = Regex("""\bsetAttribute\(""")
private const val RAW_DOM_WINDOW = 5
private const val RAW_SETATTRIBUTE_MAX = 0

/** file -> (matches, why each is fine). The FinTS PIN fields were moved to `Widget.setAttribute` (tested); the conference banners were not (no test path). */
private val AUDITED_RAW_DOM_SET_ATTRIBUTE: Map<String, Pair<Int, String>> =
    mapOf(
        "ConferenceBackgroundSection.kt" to
            (2 to "RawAttributes: the tile attributes are re-applied by an insert hook on every (re-)insert; the group is built hidden"),
        "ConferenceScreen.kt" to
            (
                9 to
                    "the three `role=\"alert\"` banner hooks (the live call path is not testable in Karma, so not converted to " +
                    "Widget.setAttribute) and setStaticA11yLabel / setDynamicA11yTitle: `getElement() ?: hook` idiom, written again on " +
                    "every call -- the label changes with the connection state, a Widget attribute would re-render the control bar"
            ),
    )

/** The 1-based-free indexes of the lines that hold a `setAttribute(` within the window after a raw element source, per file. */
private fun rawDomWriteMatches(lines: List<String>): Int {
    val hits = mutableSetOf<Int>()
    lines.forEachIndexed { index, line ->
        if (isCommentLine(line) || !RAW_DOM_SOURCE.containsMatchIn(line)) return@forEachIndexed
        for (offset in 0..RAW_DOM_WINDOW) {
            val at = index + offset
            if (at < lines.size && !isCommentLine(lines[at]) && ANY_SET_ATTRIBUTE.containsMatchIn(lines[at])) hits += at
        }
    }
    return hits.sumOf { ANY_SET_ATTRIBUTE.findAll(lines[it]).count() }
}

class ClientLateHookRatchetTest :
    FunSpec({
        fun directHookCallsByFile(): Map<String, Int> =
            CLIENT_SOURCES
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate { file ->
                    file.name to file.readLines().filterNot { isCommentLine(it) }.sumOf { DIRECT_HOOK_CALL.findAll(it).count() }
                }.filterValues { it > 0 }

        test("the scan sees the client sources (not vacuous)") {
            directHookCallsByFile().values.sum() shouldBeGreaterThan 0
        }

        test("every direct addAfterInsertHook/addAfterDestroyHook call is audited") {
            val actual = directHookCallsByFile()
            val problems =
                (actual.keys + AUDITED_DIRECT_HOOK_CALLS.keys).sorted().mapNotNull { file ->
                    val found = actual[file] ?: 0
                    val allowed = AUDITED_DIRECT_HOOK_CALLS[file]?.first ?: 0
                    if (found != allowed) {
                        "$file: $found direct hook call(s), audited $allowed -- register hooks BEFORE the widget is added " +
                            "(addWithLifecycle), or audit the new call and update the table with the reason"
                    } else {
                        null
                    }
                }
            problems.shouldBeEmpty()
        }

        test("no raw getElement()?.setAttribute chain is left in the client, and the detector sees one") {
            val findings =
                CLIENT_SOURCES
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file
                            .readLines()
                            .filter { !isCommentLine(it) && RAW_SET_ATTRIBUTE.containsMatchIn(it) }
                            .map { "${file.name}: ${it.trim()}" }
                    }.toList()
            findings.size shouldBe RAW_SETATTRIBUTE_MAX
            RAW_SET_ATTRIBUTE.containsMatchIn("    rosterToggleButton.getElement()?.setAttribute(\"aria-pressed\", \"true\")") shouldBe true
            RAW_SET_ATTRIBUTE.containsMatchIn("    button.setAttribute(\"aria-pressed\", \"true\")") shouldBe false
        }

        test("every windowed raw-DOM setAttribute (?.let / ?.apply / vnode.elm / stored element) is audited") {
            val actual =
                CLIENT_SOURCES
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .associate { it.name to rawDomWriteMatches(it.readLines()) }
                    .filterValues { it > 0 }
            val problems =
                (actual.keys + AUDITED_RAW_DOM_SET_ATTRIBUTE.keys).sorted().mapNotNull { file ->
                    val found = actual[file] ?: 0
                    val allowed = AUDITED_RAW_DOM_SET_ATTRIBUTE[file]?.first ?: 0
                    if (found != allowed) {
                        "$file: $found raw-DOM setAttribute match(es), audited $allowed -- use Widget.setAttribute(name, value), " +
                            "or audit the call and update the table with the reason"
                    } else {
                        null
                    }
                }
            problems.shouldBeEmpty()
            actual.values.sum() shouldBeGreaterThan 0
        }

        test("the windowed detector recognises the spellings the chain regex misses, and ignores comments and far-away writes") {
            rawDomWriteMatches(listOf("  getElement()?.let { el ->", "    el.setAttribute(\"a\", \"b\")", "  }")) shouldBe 1
            rawDomWriteMatches(listOf("  (vnode.elm as? HTMLElement)?.apply {", "    setAttribute(\"a\", \"b\")", "  }")) shouldBe 1
            rawDomWriteMatches(
                listOf("  val el = w.getElement()!!", "  el.setAttribute(\"a\", \"b\"); el.setAttribute(\"c\", \"d\")"),
            ) shouldBe
                2
            rawDomWriteMatches(listOf("  (vnode.elm as? HTMLElement)?.setAttribute(\"role\", \"alert\")")) shouldBe 1
            rawDomWriteMatches(listOf("  // getElement()?.let { el.setAttribute(\"a\", \"b\") }")) shouldBe 0
            rawDomWriteMatches(listOf("  w.setAttribute(\"a\", \"b\")")) shouldBe 0
            rawDomWriteMatches(
                listOf("  val el = w.getElement()") + List(RAW_DOM_WINDOW + 1) { "  x()" } + listOf("  el.setAttribute(\"a\", \"b\")"),
            ) shouldBe
                0
        }

        test("the detector recognises a direct call and ignores labels and comments") {
            DIRECT_HOOK_CALL.containsMatchIn("    row.addAfterInsertHook { vnode ->") shouldBe true
            DIRECT_HOOK_CALL.containsMatchIn("    host.addAfterDestroyHook {") shouldBe true
            DIRECT_HOOK_CALL.containsMatchIn("        val el = x ?: return@addAfterInsertHook") shouldBe false
            isCommentLine("    // row.addAfterInsertHook { }") shouldBe true
            isCommentLine("     * `addAfterInsertHook` fires once") shouldBe true
        }
    })
