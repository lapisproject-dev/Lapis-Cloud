package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwire of V1.9.68 (UI/UX guideline R59, "one scroll surface"): the page is the ONE scroll surface. A box that scrolls inside the page
 * (a fixed `max-height` plus `overflow: auto`, a sidebar with its own scrollbar, a table in a `table-responsive` frame, ...) is a scroll trap
 * for keyboard, screen-reader and touch users, and hides content behind a scrollbar nobody sees. The sources are scanned as text, in the
 * pattern of [ClientToolbarIconTripwireTest]: comment lines are exempt, every detector proves itself against a positive and a negative
 * example, and the ledger may only SHRINK (a new scroller fails, a scroller that is gone fails too until its ledger entry is deleted).
 *
 * The exception classes (docs/architecture/ui-ux-guideline.adoc, R59):
 * - **E0** a fixed-height fullscreen surface (it IS the scroll root while it is up; the page behind it is out of reach)
 * - **E1** a modal / overlay / bottom sheet (fixed or absolute, bounded by the viewport)
 * - **E2** a popup list (combobox / search results) that opens over the page
 * - **E3** a log (chat) -- a bounded, keyboard-focusable region that follows new messages
 * - **E3d** (V1.9.134, approved narrowly) the document reading area of a registration text (`.lapis-agreement-frame`): bounded, keyboard-focusable
 *   (`tabindex=0`, `role=region`, `aria-label`), and a scroller ONLY from 768 px up -- below that it grows with its text, so a phone never gets a
 *   nested scroller. It is the one place that holds a legal text; the other legal text (`.lapis-legal-text`) stays uncapped.
 * - **E4h** a horizontal strip (`overflow-x` only, `overflow-y: hidden` -- never a vertical scroller)
 * - **E5** anything else: must stay EMPTY
 *
 * Every ledger entry must contain `overscroll-behavior: contain` (or `overscroll-behavior-x: contain` for E4h): a scroller that chains its
 * scroll into the page behind it is the scroll trap this rule exists to remove.
 *
 * `overflow: hidden` is a clip, not a scroller: counted separately ([CLIP_SELECTORS]), never a failure. A clip is allowed only where no
 * focusable element can sit behind its edge -- reviewed by hand when an entry is added.
 */
private val CLIENT_KOTLIN_DIR: File =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val THEME_CSS: File =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

/** One CSS rule: its (whitespace-normalised) selector, the at-rule headers it sits in, and its declaration text. */
internal data class ScrollCssRule(
    val selector: String,
    val atRules: List<String>,
    val body: String,
)

/** The rules of [css] (comments removed, at-rules such as `@media` flattened into [ScrollCssRule.atRules]). */
internal fun scrollCssRules(css: String): List<ScrollCssRule> {
    val source = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

    class Frame(
        val header: String,
        val isRule: Boolean,
    ) {
        val body = StringBuilder()
    }

    val stack = ArrayDeque<Frame>()
    val rules = mutableListOf<ScrollCssRule>()
    val pending = StringBuilder()
    for (c in source) {
        when (c) {
            '{' -> {
                val header = pending.toString().trim().replace(Regex("""\s+"""), " ")
                pending.clear()
                stack.addLast(Frame(header, isRule = !header.startsWith("@")))
            }

            '}' -> {
                val frame = stack.removeLastOrNull() ?: continue
                if (frame.isRule) {
                    rules +=
                        ScrollCssRule(
                            selector = frame.header,
                            atRules = stack.filter { !it.isRule }.map { it.header },
                            body = frame.body.toString() + pending.toString(),
                        )
                }
                pending.clear()
            }

            ';' -> {
                stack
                    .lastOrNull()
                    ?.takeIf { it.isRule }
                    ?.body
                    ?.append(pending)
                    ?.append(";")
                pending.clear()
            }

            else -> pending.append(c)
        }
    }
    return rules
}

private val SCROLLER_DECLARATION = Regex("""overflow(?:-x|-y)?\s*:\s*(?:auto|scroll)\b""")
private val CLIP_DECLARATION = Regex("""overflow(?:-x|-y)?\s*:\s*hidden\b""")
private val OVERSCROLL_CONTAIN = Regex("""overscroll-behavior(?:-x|-y)?\s*:\s*contain\b""")

/** Selector -> the rule bodies of every rule that declares a scrolling overflow (`auto`/`scroll`) in [css]. */
internal fun cssScrollers(css: String): Map<String, String> =
    scrollCssRules(css)
        .filter { SCROLLER_DECLARATION.containsMatchIn(it.body) }
        .groupBy({ it.selector }, { it.body })
        .mapValues { (_, bodies) -> bodies.joinToString(";") }

/** The selectors of every rule that clips (`overflow: hidden`) -- a different category, never a failure. */
internal fun cssClips(css: String): Set<String> =
    scrollCssRules(css).filter { CLIP_DECLARATION.containsMatchIn(it.body) }.map { it.selector }.toSet()

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private val KOTLIN_SCROLLER_PATTERNS =
    listOf(
        Regex("""Overflow\.(?:AUTO|SCROLL)\b"""),
        Regex("""\boverflowY\b"""),
        Regex("""overflow-(?:y-)?auto"""),
        Regex("""overflow-scroll"""),
        Regex("""table-responsive"""),
        Regex("""overflow(?:-x|-y|X|Y)?\s*[:=]\s*\\?"?\s*(?:auto|scroll)\b"""),
        Regex("""setStyle\(\s*"overflow[^"]*"\s*,\s*"(?:auto|scroll)""""),
    )

/** The code lines of [source] (comment lines exempt) that create a scroller, trimmed. */
internal fun kotlinScrollers(source: String): List<String> =
    source
        .split('\n')
        .filter { !isCommentLine(it) }
        .filter { line -> KOTLIN_SCROLLER_PATTERNS.any { it.containsMatchIn(line) } }
        .map { it.trim() }

private fun clientFiles(): List<File> =
    CLIENT_KOTLIN_DIR
        .walkTopDown()
        .filter {
            it.isFile && it.extension == "kt"
        }.sortedBy { it.name }
        .toList()

/**
 * The CSS scrollers that remain after V1.9.68, normalised selector -> exception class. Ledger may only shrink. E5 stays empty.
 */
private val CSS_LEDGER: Map<String, String> =
    mapOf(
        ".lapis-conference-call-panel.lapis-conference-fullscreen" to "E0",
        ".lapis-encounter-room.is-pseudo-fullscreen, .lapis-encounter-room:fullscreen" to "E0",
        ".lapis-conference-fullscreen .lapis-conference-roster, .lapis-conference-fullscreen .lapis-conference-chat" to "E1",
        ".lapis-conference-roster, .lapis-conference-chat, .lapis-conference-fullscreen .lapis-conference-roster, " +
            ".lapis-conference-fullscreen .lapis-conference-chat" to "E1",
        ".lapis-conference-fullscreen .lapis-conference-voting" to "E1",
        ".lapis-conference-voting, .lapis-conference-fullscreen .lapis-conference-voting" to "E1",
        ".lapis-conference-more-sheet" to "E1",
        // V1.9.184: the settings panel (devices) that the gear opens -- the left twin of the "Mehr" sheet, bounded by max-height, scrolls itself
        ".lapis-conference-settings-sheet" to "E1",
        ".lapis-conference-reaction-popover" to "E1",
        ".lapis-encounter-more-sheet" to "E1",
        ".lapis-encounter-map-overlay__panel" to "E1",
        ".lapis-encounter-side" to "E1",
        ".lapis-encounter-side.is-sheet" to "E1",
        // V1.9.203: the command palette (Ctrl+K), a modal dialog bounded by max-height 70vh; at most eight hits, so it scrolls only on a very low screen
        ".lapis-command-palette__dialog" to "E1",
        ".lapis-member-map-search-results" to "E2",
        ".lapis-ssel-dropdown" to "E2",
        ".lapis-conference-chat-log" to "E3",
        ".lapis-encounter-chat-log" to "E3",
        ".lapis-conference-filmstrip" to "E4h",
        ".lapis-agreement-frame" to "E3d",
    )

/** Kotlin scrollers that remain: file -> number of lines. Empty since V1.9.68 (the conference chat log and the filmstrip are CSS classes). */
private val KOTLIN_LEDGER: Map<String, Int> = emptyMap()

/** The clips this wave reviewed (no focusable element sits behind their edge). Informational: a new clip is not a failure. */
@Suppress("unused")
private val CLIP_SELECTORS =
    listOf(
        // V1.9.184: the centre zone of the room bar clips (`overflow: clip` with a margin for the focus rings); the bar itself does not
        ".lapis-room-bar__centre",
        ".lapis-encounter",
        ".lapis-member-map-panel",
    )

class ScrollSurfaceTripwireTest :
    FunSpec({
        test("CSS detector: a scrolling overflow is found, a clip, a video max-height and a comment are not") {
            cssScrollers(".a { max-height: 10px; overflow: auto; }").keys shouldBe setOf(".a")
            cssScrollers(".a { overflow-y: scroll }").keys shouldBe setOf(".a")
            cssScrollers(".a { overflow-x: auto; }").keys shouldBe setOf(".a")
            cssScrollers(".a { overflow: hidden; }") shouldBe emptyMap()
            cssScrollers(".lapis-recording-player { max-height: min(70dvh, 540px); object-fit: contain; }") shouldBe emptyMap()
            cssScrollers("/* .a { overflow: auto; } */ .b { color: red; }") shouldBe emptyMap()
            cssScrollers("/*\n multi\n line .a { overflow-y: auto; }\n*/ .b { overflow: hidden; }") shouldBe emptyMap()
            cssClips(".a { overflow: hidden; } .b { overflow: auto; }") shouldBe setOf(".a")
        }

        test("CSS detector: a rule inside @media is found with its at-rule, selector lists stay one entry") {
            val css = "@media (min-width: 992px) { .x, .y > .z { overflow-y: auto; overscroll-behavior: contain; } }"
            cssScrollers(css).keys shouldBe setOf(".x, .y > .z")
            scrollCssRules(css).single().atRules shouldBe listOf("@media (min-width: 992px)")
            OVERSCROLL_CONTAIN.containsMatchIn(cssScrollers(css).values.single()) shouldBe true
            OVERSCROLL_CONTAIN.containsMatchIn(cssScrollers(".x { overflow: auto; }").values.single()) shouldBe false
        }

        test("Kotlin detector: the five scroller shapes are found, a comment and a clip are not") {
            kotlinScrollers("        overflow = Overflow.AUTO").size shouldBe 1
            kotlinScrollers("    val w = body.div { overflow = Overflow.SCROLL }").size shouldBe 1
            kotlinScrollers("    addCssClasses(\"text-muted overflow-auto\")").size shouldBe 1
            kotlinScrollers("    addCssClasses(\"table-responsive\")").size shouldBe 1
            kotlinScrollers("    el.style.cssText = \"display:flex;overflow-x:auto;gap:8px;\"").size shouldBe 1
            kotlinScrollers("    el.style.cssText = \"max-height:60vh;overflow-y: scroll;\"").size shouldBe 1
            kotlinScrollers("    setStyle(\"overflow\", \"auto\")").size shouldBe 1
            kotlinScrollers("    val x = Modifier.overflowY(1)").size shouldBe 1
            kotlinScrollers("    // overflow = Overflow.AUTO").size shouldBe 0
            kotlinScrollers("     * a `table-responsive` wrapper is gone").size shouldBe 0
            kotlinScrollers("    setStyle(\"overflow\", \"hidden\")").size shouldBe 0
            kotlinScrollers("    el.style.cssText = \"overflow:hidden;min-height:150px;\"").size shouldBe 0
        }

        test("R59: no scroller in theme.css outside the ledger (and none of the ledger is gone)") {
            val actual = cssScrollers(THEME_CSS.readText()).mapValues { (selector, _) -> CSS_LEDGER[selector] ?: "UNLEDGERED" }
            val unledgered = actual.filterValues { it == "UNLEDGERED" }.keys
            withClue(
                "new scroller(s) in theme.css -- remove the max-height/overflow (the page scrolls) or, if it is a real E0-E4h case, ledger it: $unledgered",
            ) {
                unledgered shouldBe emptySet()
            }
            val gone = CSS_LEDGER.keys - actual.keys
            withClue("ledger entries whose scroller is gone -- delete them from CSS_LEDGER: $gone") { gone shouldBe emptySet() }
        }

        test("R59: E5 stays empty, every ledger entry has a known class") {
            CSS_LEDGER.values.toSet().subtract(setOf("E0", "E1", "E2", "E3", "E3d", "E4h")) shouldBe emptySet()
        }

        test("R59: every ledger scroller contains its scroll (overscroll-behavior: contain)") {
            val scrollers = cssScrollers(THEME_CSS.readText())
            val leaking = scrollers.filter { (selector, body) -> selector in CSS_LEDGER && !OVERSCROLL_CONTAIN.containsMatchIn(body) }.keys
            withClue("ledger scrollers without `overscroll-behavior: contain`: $leaking") { leaking shouldBe emptySet() }
        }

        test("R59: the agreement frame (E3d) is a scroller only from 768 px up; below that it has no height cap and no overflow") {
            val rules = scrollCssRules(THEME_CSS.readText()).filter { it.selector == ".lapis-agreement-frame" }
            (rules.size >= 2) shouldBe true
            val scrolling = rules.filter { SCROLLER_DECLARATION.containsMatchIn(it.body) }
            scrolling.size shouldBe 1
            scrolling.single().atRules shouldBe listOf("@media (min-width: 768px)")
            OVERSCROLL_CONTAIN.containsMatchIn(scrolling.single().body) shouldBe true
            val base = rules.single { it.atRules.isEmpty() }
            Regex("""(?:max-)?height\s*:""").containsMatchIn(base.body) shouldBe false
            // E3d is reserved for this one selector
            CSS_LEDGER.filterValues { it == "E3d" }.keys shouldBe setOf(".lapis-agreement-frame")
        }

        test("R59: a horizontal strip (E4h) never scrolls vertically -- overflow-y is hidden") {
            val strips = CSS_LEDGER.filterValues { it == "E4h" }.keys
            val bodies = scrollCssRules(THEME_CSS.readText()).filter { it.selector in strips }.map { it.body }
            bodies.isNotEmpty() shouldBe true
            bodies.forEach { body ->
                withClue("E4h body must set overflow-y: hidden and no vertical scroller: $body") {
                    Regex("""overflow-y\s*:\s*hidden""").containsMatchIn(body) shouldBe true
                    Regex("""overflow-y\s*:\s*(?:auto|scroll)""").containsMatchIn(body) shouldBe false
                }
            }
        }

        test("R59: no scroller in the Kotlin sources outside the ledger") {
            val actual =
                clientFiles()
                    .associate { it.name to kotlinScrollers(it.readText()).size }
                    .filterValues { it > 0 }
            withClue(
                "scroller(s) in Kotlin: ${clientFiles().associate {
                    it.name to
                        kotlinScrollers(
                            it.readText(),
                        )
                }.filterValues { it.isNotEmpty() }}",
            ) {
                actual shouldBe KOTLIN_LEDGER
            }
        }

        test("R59: the sidebar has no scroller of its own and does not stick (it scrolls with the page)") {
            val rules = scrollCssRules(THEME_CSS.readText())
            val sidebar = rules.filter { it.selector.contains(".lapis-sidebar") && !it.selector.contains("offcanvas-body") }
            sidebar.isNotEmpty() shouldBe true
            sidebar.forEach { rule ->
                withClue("${rule.selector} must not scroll or stick: ${rule.body}") {
                    SCROLLER_DECLARATION.containsMatchIn(rule.body) shouldBe false
                    Regex("""position\s*:\s*sticky""").containsMatchIn(rule.body) shouldBe false
                    Regex("""max-height""").containsMatchIn(rule.body) shouldBe false
                }
            }
            // The drawer below 992px (Bootstrap's own `.offcanvas-body` scroller, a modal surface, E1) must not chain into the page.
            val drawer = rules.filter { it.selector == ".lapis-sidebar .offcanvas-body" && it.atRules.any { a -> a.contains("max-width") } }
            drawer.any { OVERSCROLL_CONTAIN.containsMatchIn(it.body) } shouldBe true
        }

        test(
            "R59: the only sticky elements are the navbar, the rating booth head, the transparency banner, (with room for it) the member map, the travel expense totals bar, the encounter dock and the room bar dock",
        ) {
            val sticky = scrollCssRules(THEME_CSS.readText()).filter { Regex("""position\s*:\s*sticky""").containsMatchIn(it.body) }
            // V1.9.122: `.lapis-te-totals` -- the totals bar of the travel expense editor (right column from 992px, bottom edge below). It is a
            // small, non-scrolling summary (no overflow, no max-height), so it adds no scroll surface; the page's own scroll-padding is unaffected.
            // V1.9.165: `.lapis-encounter-dock` -- the bar of the encounter room (bar + "Mehr" sheet + device panel) rests at the bottom edge of the
            // scroll root. Sticky is no scroller: the dock has no overflow and no max-height, so the ledger of scrollers is unchanged.
            // V1.9.184: `.lapis-room-bar-dock` -- the same for the conference bar (it used to be `fixed`): bar + "Mehr" sheet + settings panel rest at
            // the bottom edge of the page; no overflow and no max-height, so the ledger of scrollers stays as it is.
            sticky.map { it.selector }.toSet() shouldBe
                setOf(
                    // V1.9.209: the navbar and the rating booth head stick to the top edge (no overflow, no max-height: no scroller).
                    ".navbar.lapis-navbar",
                    ".lapis-rating-booth-head",
                    ".lapis-conference-transparency-banner",
                    ".lapis-member-map-panel",
                    ".lapis-te-totals",
                    ".lapis-encounter-dock",
                    ".lapis-room-bar-dock",
                    // V1.9.211 (R62): the first column of a wide report table sticks to the left edge of its OWN scroll frame
                    // (.table-responsive, an existing scroller); a cell is no scroller, so the ledger of scrollers is unchanged.
                    ".lapis-table-sticky-first tr > :first-child",
                    // V1.9.225: the save bar of the party report entry sticks to the bottom edge of the page while values are being typed in;
                    // it is a plain block (no overflow, no max-height), so it is no scroller either.
                    ".lapis-partg-savebar",
                )
            for (selector in listOf(".lapis-encounter-dock", ".lapis-room-bar-dock")) {
                val dock = sticky.single { it.selector == selector }
                withClue("the sticky dock $selector is no scroller: ${dock.body}") {
                    SCROLLER_DECLARATION.containsMatchIn(dock.body) shouldBe false
                    Regex("""max-height""").containsMatchIn(dock.body) shouldBe false
                }
            }
            val map = sticky.single { it.selector == ".lapis-member-map-panel" }
            withClue("the sticky map needs a viewport at least 760px high: ${map.atRules}") {
                map.atRules.any { it.contains("min-height: 760px") } shouldBe true
            }
        }

        test("R59: the page scroll container keeps a scroll-padding-top (WCAG 2.4.11) for the sticky banner") {
            val html = scrollCssRules(THEME_CSS.readText()).filter { it.selector == "html" }
            html.any { Regex("""scroll-padding-top\s*:\s*\S+""").containsMatchIn(it.body) } shouldBe true
        }

        test("R59: every log scroller (E3) is keyboard-focusable") {
            val sources = clientFiles().associate { it.name to it.readText() }
            val logs = mapOf("ConferenceScreen.kt" to "lapis-conference-chat-log", "EncounterChatPanel.kt" to "lapis-encounter-chat-log")
            logs.forEach { (file, cls) ->
                val text = sources.getValue(file)
                val at = text.indexOf(cls)
                withClue("$file must use $cls") { (at >= 0) shouldBe true }
                withClue("$file: the log $cls needs tabindex=\"0\" within reach of its declaration") {
                    text.substring(at, minOf(text.length, at + 900)).contains("\"tabindex\", \"0\"") shouldBe true
                }
            }
        }
    })
