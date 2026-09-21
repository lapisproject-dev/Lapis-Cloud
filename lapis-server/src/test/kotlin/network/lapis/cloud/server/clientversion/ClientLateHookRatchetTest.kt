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
        "BankAccountsScreen.kt" to (1 to "hardenSecretInput: the modal is built completely before modal.show()"),
        "ConferenceRecordingsPanel.kt" to
            (1 to "raw <video> in a late insert hook; more widgets follow in the same card, exactly one video results"),
        "ConferenceWhiteboardController.kt" to
            (5 to "built in the hidden whiteboard panel: no element exists when the hooks are registered"),
        "ConferenceScreen.kt" to
            (
                10 to
                    "role=alert banners (hidden first), roster/chat badges (raw child, next add patches), stage/grid zones " +
                    "(first fire is the replacement, before any tile exists), chatRow (hidden panel), setStaticA11yLabel/" +
                    "setDynamicA11yTitle (getElement() ?: hook idiom)"
            ),
    )

class ClientLateHookRatchetTest :
    FunSpec({
        fun directHookCallsByFile(): Map<String, Int> =
            CLIENT_SOURCES
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate { file ->
                    file.name to file.readLines().count { !isCommentLine(it) && DIRECT_HOOK_CALL.containsMatchIn(it) }
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

        test("the detector recognises a direct call and ignores labels and comments") {
            DIRECT_HOOK_CALL.containsMatchIn("    row.addAfterInsertHook { vnode ->") shouldBe true
            DIRECT_HOOK_CALL.containsMatchIn("    host.addAfterDestroyHook {") shouldBe true
            DIRECT_HOOK_CALL.containsMatchIn("        val el = x ?: return@addAfterInsertHook") shouldBe false
            isCommentLine("    // row.addAfterInsertHook { }") shouldBe true
            isCommentLine("     * `addAfterInsertHook` fires once") shouldBe true
        }
    })
